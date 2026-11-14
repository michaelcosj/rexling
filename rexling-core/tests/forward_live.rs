//! Requires an explicitly supplied, disposable API 14 Rex server.
use rexling_core::{control::Control, forward, transport::Endpoint};
use serde_json::json;
use std::time::Duration;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    sync::{mpsc, oneshot},
};
#[tokio::test(flavor = "current_thread")]
#[ignore = "requires an isolated API 14 Rex server"]
async fn forwards_concurrent_tcp_connections_and_half_closes_then_removes_its_block() {
    let endpoint = Endpoint::new(
        &std::env::var("REX_TEST_SERVER").unwrap(),
        Duration::from_secs(5),
    )
    .unwrap();
    let mut control = Control::connect_forward(endpoint.clone(), None)
        .await
        .unwrap();
    let session = control
        .call("session.create", json!({"label":"rexling-forward-test"}))
        .await
        .unwrap()["session_id"]
        .as_str()
        .unwrap()
        .to_owned();
    control.attach(&session).await.unwrap();
    let target = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = target.local_addr().unwrap().port();
    let echo = tokio::spawn(async move {
        loop {
            let (mut socket, _) = target.accept().await.unwrap();
            tokio::spawn(async move {
                let mut bytes = Vec::new();
                socket.read_to_end(&mut bytes).await.unwrap();
                socket.write_all(&bytes).await.unwrap();
                socket.shutdown().await.unwrap();
            });
        }
    });
    // Cut every proxy socket to simulate a network change while preserving the server.
    let server_url = url::Url::parse(&std::env::var("REX_TEST_SERVER").unwrap()).unwrap();
    let upstream = format!(
        "{}:{}",
        server_url.host_str().unwrap(),
        server_url.port().unwrap()
    );
    let proxy_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let proxy_address = proxy_listener.local_addr().unwrap();
    let (fault, changes) = tokio::sync::watch::channel(0u32);
    let proxy = tokio::spawn(async move {
        loop {
            let (mut local, _) = proxy_listener.accept().await.unwrap();
            let upstream = upstream.clone();
            let mut changed = changes.clone();
            changed.borrow_and_update();
            tokio::spawn(async move {
                let mut remote = TcpStream::connect(upstream).await.unwrap();
                tokio::select! {
                    _=tokio::io::copy_bidirectional(&mut local,&mut remote)=>(),
                    _=changed.changed()=>(),
                }
            });
        }
    });
    let endpoint =
        Endpoint::new(&format!("http://{proxy_address}"), Duration::from_secs(5)).unwrap();
    let (stop, cancel) = oneshot::channel();
    let (events, mut updates) = mpsc::unbounded_channel();
    let request = forward::Request {
        endpoint,
        session: session.clone(),
        host: "127.0.0.1".into(),
        port,
        local_port: 0,
        id: "live-half-close".into(),
    };
    let worker = tokio::spawn(async move {
        forward::run(request, cancel, |state| {
            println!("forward state: {state}");
            let _ = events.send(state);
        })
        .await
    });
    let address = tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            let state = updates.recv().await.unwrap();
            assert_ne!(state["phase"], "failed", "{state}");
            if state["phase"] == "active" {
                break state["address"].as_str().unwrap().to_owned();
            }
        }
    })
    .await
    .unwrap();
    let mut clients = tokio::task::JoinSet::new();
    for i in 0..8 {
        let address = address.clone();
        clients.spawn(async move {
            let mut socket = TcpStream::connect(address).await.unwrap();
            let bytes = vec![i; 130_000];
            socket.write_all(&bytes).await.unwrap();
            socket.shutdown().await.unwrap();
            let mut response = Vec::new();
            socket.read_to_end(&mut response).await.unwrap();
            assert_eq!(response, bytes);
        });
    }
    tokio::time::timeout(Duration::from_secs(15), async {
        while let Some(result) = clients.join_next().await {
            result.unwrap();
        }
    })
    .await
    .unwrap();
    fault.send(1).unwrap();
    tokio::time::timeout(Duration::from_secs(15), async {
        let mut reconnecting = false;
        loop {
            let state = updates.recv().await.unwrap();
            if state["phase"] == "reconnecting" {
                reconnecting = true;
            }
            if reconnecting && state["phase"] == "active" {
                assert_eq!(state["address"], address);
                break;
            }
        }
    })
    .await
    .unwrap();
    let mut socket = TcpStream::connect(&address).await.unwrap();
    socket.write_all(b"after reconnect").await.unwrap();
    socket.shutdown().await.unwrap();
    let mut reply = Vec::new();
    tokio::time::timeout(Duration::from_secs(10), socket.read_to_end(&mut reply))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(reply, b"after reconnect");
    stop.send(()).unwrap();
    tokio::time::timeout(Duration::from_secs(8), worker)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    let blocks = control
        .call("session.list_blocks", json!({"session_id":session}))
        .await
        .unwrap();
    assert!(blocks["blocks"].as_array().unwrap().is_empty(), "{blocks}");
    control
        .call("session.destroy", json!({"session_id":session}))
        .await
        .unwrap();
    echo.abort();
    proxy.abort();
}
