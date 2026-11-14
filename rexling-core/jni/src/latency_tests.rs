//! Real actor + Ghostty + loopback protocol peers with deliberately held replies.
use super::*;
use futures_util::{SinkExt, StreamExt, stream::FuturesUnordered};
use std::sync::atomic::{AtomicUsize, Ordering};
use tokio::sync::Semaphore;
use tokio_tungstenite::{
    accept_hdr_async,
    tungstenite::{
        Message,
        handshake::server::{Request, Response},
    },
};

const NONCE: u64 = 11418691820941138289;
const SNAPSHOT: &[u8] = include_bytes!("../../tests/fixtures/ghostty-remote.bin");
struct Peer {
    endpoint: Endpoint,
    gate: Arc<Semaphore>,
    writes: Arc<Mutex<Vec<String>>>,
    acknowledged: Arc<AtomicUsize>,
    methods: Arc<Mutex<Vec<String>>>,
    output: mpsc::Sender<Vec<u8>>,
    task: tokio::task::JoinHandle<()>,
}
impl Drop for Peer {
    fn drop(&mut self) {
        self.task.abort();
    }
}
impl Peer {
    async fn new(delay: Duration, reject: bool, drop_first: bool) -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let endpoint = Endpoint::new(
            &format!("http://{}", listener.local_addr().unwrap()),
            Duration::from_secs(3),
        )
        .unwrap();
        let gate = Arc::new(Semaphore::new(0));
        let writes = Arc::new(Mutex::new(Vec::new()));
        let acknowledged = Arc::new(AtomicUsize::new(0));
        let methods = Arc::new(Mutex::new(Vec::new()));
        let (output_tx, output_rx) = mpsc::channel::<Vec<u8>>(128);
        let output_rx = Arc::new(tokio::sync::Mutex::new(output_rx));
        let (g, w, a, m) = (
            gate.clone(),
            writes.clone(),
            acknowledged.clone(),
            methods.clone(),
        );
        let output=output_tx.clone();
        let task = tokio::spawn(async move {
            let mut peers = tokio::task::JoinSet::new();
            loop {
                let Ok((socket, _)) = listener.accept().await else {
                    break;
                };
                socket.set_nodelay(true).unwrap();
                let (gate, writes, acks, methods, output, rx) = (
                    g.clone(),
                    w.clone(),
                    a.clone(),
                    m.clone(),
                    output_tx.clone(),
                    output_rx.clone(),
                );
                peers.spawn(async move {
                    let mut ws=accept_hdr_async(socket,|request:&Request,mut response:Response|{
                        response.headers_mut().insert("Sec-WebSocket-Protocol",request.headers()["Sec-WebSocket-Protocol"].clone());Ok(response)
                    }).await.unwrap();
                    let Some(Ok(hello))=ws.next().await else{return;};
                    if hello.is_binary() {
                        let mut welcome=vec![0;32];welcome[0]=0x81;welcome[1..17].fill(1);
                        welcome[20..24].copy_from_slice(&60000u32.to_le_bytes());welcome[24..32].copy_from_slice(&NONCE.to_le_bytes());
                        ws.send(Message::Binary(welcome.into())).await.unwrap();
                        ws.send(Message::Binary(SNAPSHOT.to_vec().into())).await.unwrap();
                        let mut rx=rx.lock().await;
                        loop {tokio::select! {
                            data=rx.recv()=>{let Some(data)=data else{break;};if ws.send(Message::Binary(data.into())).await.is_err(){break;}},
                            msg=ws.next()=>if !matches!(msg,Some(Ok(msg)) if !msg.is_close()){break;}
                        }}
                    } else {
                        ws.send(Message::Text(json!({"type":"welcome","protocol":"rex.control.v1","api_revision":8,"limits":{"max_message_bytes":1_048_576},"client":{"id":"client:test","resumed":false}}).to_string().into())).await.unwrap();
                        let (mut sink,mut stream)=ws.split();
                        let mut replies=FuturesUnordered::<Pin<Box<dyn Future<Output=(Value,bool)>+Send>>>::new();
                        loop {tokio::select! {
                            msg=stream.next()=>{
                                let Some(Ok(msg))=msg else{break;};if msg.is_close(){break;}
                                if !msg.is_text(){continue;}
                                let request:Value=serde_json::from_str(msg.to_text().unwrap()).unwrap();
                                let method=request["method"].as_str().unwrap().to_owned();methods.lock().unwrap().push(method.clone());
                                let input=method=="com.superlogical.terminal.write" || method=="block.event";
                                let mut response=json!({"type":"response","id":request["id"],"payload":{}});
                                if input {
                                    let text=if method=="block.event" {request["payload"]["event"]["payload"]["text"].as_str().unwrap()} else {request["payload"]["args"]["data"].as_str().unwrap()};
                                    let first={let mut w=writes.lock().unwrap();w.push(text.into());w.len()==1};
                                    let _=output.send(b"\r\nVT_BEFORE_ACK\r\n".to_vec()).await;
                                    if drop_first && first {break;}
                                    if reject {response["error"]=json!({"code":"not_owner","message":"Control changed","status":403});}
                                } else if method=="data.connect" {response["payload"]=json!({"ticket":"AAAAAAAAAAAAAAAAAAAAAA"});}
                                else if method=="session.list" || method=="session.attach_all" {response["payload"]=json!({"sessions":[]});}
                                else if method=="session.view" || method=="session.attach" {
                                    let view=json!({"windows":[{"window_id":"window:test","layers":[{"blocks":[{"block_id":"block:test","creator_name":"com.superlogical.terminal","owner":if reject {"client:other"} else {"client:test"}}]}]}]});
                                    response["payload"]=if method=="session.attach" {json!({"view":view})} else {view};
                                }
                                let gate=gate.clone();replies.push(Box::pin(async move {
                                    if input {let permit=gate.acquire().await.unwrap();permit.forget();tokio::time::sleep(delay).await;}
                                    (response,input)
                                }));
                            },
                            reply=replies.next(),if !replies.is_empty()=>{
                                let (response,input)=reply.unwrap();if sink.send(Message::Text(response.to_string().into())).await.is_err(){break;}
                                if input {acks.fetch_add(1,Ordering::SeqCst);}
                            }
                        }}
                    }
                });
            }
        });
        Self {
            endpoint,
            gate,
            writes,
            acknowledged,
            methods,
            output,
            task,
        }
    }
    async fn actor(&self, limit: usize, batch: bool) -> (Actor, Arc<Mutex<Shared>>) {
        let shared = Arc::new(Mutex::new(Shared {
            revision: 0,
            json: String::new(),
            activity: Arc::default(),
            pending_previews: BTreeMap::new(),
            frame: None,
            key: String::new(),
            wire: crate::updates::WireBase::default(),
        }));
        let mut actor = Actor::new(shared.clone(), Arc::new(crate::updates::Updates::default()));
        let mut control = Control::connect(self.endpoint.clone(), None, Trace::default())
            .await
            .unwrap();
        let mut data = Data::open(
            &self.endpoint,
            &mut control,
            "session:test",
            "block:test",
            Trace::default(),
        )
        .await
        .unwrap();
        let mut replica = Replica::new(data.nonce);
        replica.feed(&data.read().await.unwrap()).unwrap();
        actor.endpoint = Some(self.endpoint.clone());
        actor.control = Some(control);
        actor.data = Some(data);
        actor.replica = Some(replica);
        actor.phase = "connected".into();
        actor.controlled = true;
        actor.connection_epoch = 1;
        actor.selected_session = "session:test".into();
        actor.selected_block = "block:test".into();
        actor.input_limit = limit;
        actor.batch_text = batch;
        actor.program_refresh = tokio::time::Instant::now() + Duration::from_secs(3600);
        actor.activity_refresh = actor.program_refresh;
        (actor, shared)
    }
}
async fn until(mut condition: impl FnMut() -> bool) {
    tokio::time::timeout(Duration::from_secs(4), async {
        while !condition() {
            tokio::time::sleep(Duration::from_millis(2)).await;
        }
    })
    .await
    .expect("actor did not reach expected state");
}
async fn input(tx: &mpsc::Sender<Value>, text: &str) {
    tx.send(json!({"type":"input","text":text})).await.unwrap();
}
async fn shutdown(tx: &mpsc::Sender<Value>) {
    tx.send(json!({"type":"shutdown"})).await.unwrap();
}

#[tokio::test]
async fn synchronized_redraw_never_publishes_the_intermediate_blank_screen() {
    let peer=Peer::new(Duration::ZERO,false,false).await;
    let (mut actor,shared)=peer.actor(1,false).await;
    actor.dirty=true;
    let (tx,rx)=mpsc::channel(128);
    tokio::join!(actor.run(rx),async {
        until(||shared.lock().unwrap().frame.is_some()).await;
        let complete=shared.lock().unwrap().frame.clone().unwrap();
        peer.output.send(b"\x1b[?2026h\x1b[2J\x1b[H".to_vec()).await.unwrap();
        tokio::time::sleep(Duration::from_millis(80)).await;
        assert!(Arc::ptr_eq(shared.lock().unwrap().frame.as_ref().unwrap(),&complete),"Clearing inside a synchronized redraw must retain the old complete frame");
        peer.output.send(b"partial redraw".to_vec()).await.unwrap();
        tokio::time::sleep(Duration::from_millis(80)).await;
        assert!(Arc::ptr_eq(shared.lock().unwrap().frame.as_ref().unwrap(),&complete),"A partial redraw must not reach the Android renderer");
        peer.output.send(b" completed\x1b[?2026l".to_vec()).await.unwrap();
        until(||shared.lock().unwrap().frame.as_ref().is_some_and(|frame|frame.text().contains("partial redraw completed"))).await;
        shutdown(&tx).await;
    });
}

#[tokio::test]
async fn output_and_rendering_continue_before_input_acknowledgment() {
    let peer = Peer::new(Duration::ZERO, false, false).await;
    let (actor, shared) = peer.actor(1, false).await;
    let (tx, rx) = mpsc::channel(128);
    let scenario = async {
        input(&tx, "hello").await;
        until(|| {
            shared
                .lock()
                .unwrap()
                .frame
                .as_ref()
                .is_some_and(|f| f.text().contains("VT_BEFORE_ACK"))
        })
        .await;
        assert_eq!(
            peer.acknowledged.load(Ordering::SeqCst),
            0,
            "output must paint while acknowledgment is held"
        );
        peer.gate.add_permits(1);
        until(|| peer.acknowledged.load(Ordering::SeqCst) == 1).await;
        shutdown(&tx).await;
    };
    tokio::join!(actor.run(rx), scenario);
}

#[tokio::test]
async fn rejected_input_discards_unsent_text_without_reconnecting_or_taking_control() {
    let peer = Peer::new(Duration::ZERO, true, false).await;
    let (actor, shared) = peer.actor(1, false).await;
    let (tx, rx) = mpsc::channel(128);
    tokio::join!(actor.run(rx), async {
        input(&tx, "first").await;
        input(&tx, "must-not-send").await;
        until(|| peer.writes.lock().unwrap().len() == 1).await;
        peer.gate.add_permits(1);
        until(|| {
            serde_json::from_str::<Value>(&shared.lock().unwrap().json).unwrap()["controlled"]
                == false
        })
        .await;
        tokio::time::sleep(Duration::from_millis(30)).await;
        assert_eq!(*peer.writes.lock().unwrap(), ["first"]);
        assert!(
            !peer
                .methods
                .lock()
                .unwrap()
                .iter()
                .any(|m| m == "session.own_block")
        );
        shutdown(&tx).await;
    });
}

#[tokio::test]
async fn connection_loss_discards_queued_input_and_stale_bindings_cannot_send() {
    let peer = Peer::new(Duration::ZERO, false, true).await;
    let (actor, shared) = peer.actor(1, false).await;
    let (tx, rx) = mpsc::channel(128);
    tokio::join!(actor.run(rx), async {
        // Wrong activation cannot write even while this connection is healthy.
        tx.send(json!({"type":"input","text":"wrong-pane","block":"block:other"}))
            .await
            .unwrap();
        tx.send(json!({"type":"input","text":"old-connection","connectionEpoch":0}))
            .await
            .unwrap();
        input(&tx, "uncertain").await;
        input(&tx, "queued").await;
        until(|| {
            serde_json::from_str::<Value>(&shared.lock().unwrap().json).unwrap()["phase"]
                == "reconnecting"
        })
        .await;
        let state: Value = serde_json::from_str(&shared.lock().unwrap().json).unwrap();
        assert!(state["notice"].as_str().unwrap().contains("not resent"));
        assert_eq!(*peer.writes.lock().unwrap(), ["uncertain"]);
        until(|| {
            let v: Value = serde_json::from_str(&shared.lock().unwrap().json).unwrap();
            v["phase"] == "connected" && v["connectionEpoch"] == 2
        })
        .await;
        tx.send(json!({"type":"input","text":"stale-after-reconnect","connectionEpoch":1}))
            .await
            .unwrap();
        peer.gate.add_permits(8);
        input(&tx, "fresh").await;
        until(|| peer.acknowledged.load(Ordering::SeqCst) == 1).await;
        assert_eq!(*peer.writes.lock().unwrap(), ["uncertain", "fresh"]);
        shutdown(&tx).await;
    });
}

async fn timed_run(limit: usize) -> Duration {
    let peer = Peer::new(Duration::from_millis(100), false, false).await;
    let (actor, _) = peer.actor(limit, false).await;
    let (tx, rx) = mpsc::channel(128);
    peer.gate.add_permits(24);
    let start = tokio::time::Instant::now();
    tokio::join!(actor.run(rx), async {
        for n in 0..24 {
            input(&tx, &format!("{n},")).await;
        }
        until(|| peer.acknowledged.load(Ordering::SeqCst) == 24).await;
        shutdown(&tx).await;
    });
    assert_eq!(
        *peer.writes.lock().unwrap(),
        (0..24).map(|n| format!("{n},")).collect::<Vec<_>>()
    );
    start.elapsed()
}
#[tokio::test]
async fn bounded_pipeline_removes_the_per_keystroke_round_trip_backlog() {
    let serial = timed_run(1).await;
    let pipelined = timed_run(8).await;
    eprintln!(
        "24 ordered inputs, 100ms held replies: one in flight={serial:?}, eight={pipelined:?}"
    );
    assert!(
        pipelined < serial / 2,
        "pipelining must improve acknowledgment throughput without changing order"
    );
}

#[tokio::test]
async fn paste_remains_a_barrier_even_with_eight_input_requests_allowed() {
    let peer = Peer::new(Duration::ZERO, false, false).await;
    let (actor, _) = peer.actor(8, true).await;
    let (tx, rx) = mpsc::channel(128);
    tokio::join!(actor.run(rx), async {
        input(&tx, "before").await;
        tx.send(json!({"type":"paste","text":"PASTE"}))
            .await
            .unwrap();
        input(&tx, "after").await;
        until(|| peer.writes.lock().unwrap().len() == 1).await;
        tokio::time::sleep(Duration::from_millis(25)).await;
        assert_eq!(*peer.writes.lock().unwrap(), ["before"]);
        peer.gate.add_permits(1);
        until(|| peer.writes.lock().unwrap().len() == 2).await;
        tokio::time::sleep(Duration::from_millis(25)).await;
        assert_eq!(*peer.writes.lock().unwrap(), ["before", "PASTE"]);
        peer.gate.add_permits(1);
        until(|| peer.writes.lock().unwrap().len() == 3).await;
        assert_eq!(*peer.writes.lock().unwrap(), ["before", "PASTE", "after"]);
        peer.gate.add_permits(1);
        until(|| peer.acknowledged.load(Ordering::SeqCst) == 3).await;
        shutdown(&tx).await;
    });
}
