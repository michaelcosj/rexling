//! Phone-local TCP listeners carried by owned Rex API 14 tunnel blocks.
use crate::{
    control::{Control, RpcError},
    transport::{self, Endpoint, Ws},
    upload::BYTES,
};
use anyhow::{Context, Result, bail, ensure};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use futures_util::SinkExt;
use serde_json::{Value, json};
use std::time::Duration;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    sync::oneshot,
    task::JoinSet,
};
use tokio_tungstenite::tungstenite::Message;

pub struct Request {
    pub endpoint: Endpoint,
    pub session: String,
    pub host: String,
    pub port: u16,
    pub local_port: u16,
    pub id: String,
}
fn missing(error: &anyhow::Error) -> bool {
    error.downcast_ref::<RpcError>().is_some_and(|e| {
        matches!(
            e.code.as_str(),
            "not_found"
                | "session_not_found"
                | "bad_request"
                | "forbidden"
                | "unauthorized"
                | "revision_too_low"
                | "method_not_found"
                | "conflict"
        )
    })
}

pub async fn run(
    request: Request,
    mut cancel: oneshot::Receiver<()>,
    status: impl Fn(Value),
) -> Result<()> {
    let listener = TcpListener::bind(("127.0.0.1", request.local_port))
        .await
        .map_err(|error| {
            if request.local_port == 0 {
                anyhow::anyhow!("Could not open a local port on this phone: {error}")
            } else {
                anyhow::anyhow!("Phone port {} is unavailable: {error}. Leave Phone port blank to choose automatically.", request.local_port)
            }
        })?;
    let address = listener.local_addr()?.to_string();
    let mut block: Option<String> = None;
    let mut token: Option<String> = None;
    let label = format!("Rexling forward {}", request.id);
    let mut delay = 1;
    let mut created = false;
    loop {
        status(json!({"phase":if created {"reconnecting"} else {"connecting"},"address":address}));
        let operation =
            async {
                let mut control =
                    Control::connect_forward(request.endpoint.clone(), token.as_deref()).await?;
                ensure!(
                    control.welcome()["api_revision"] == 14,
                    "Port forwarding requires Rex API 14 or newer"
                );
                token = control.token().map(str::to_owned);
                control.attach(&request.session).await?;
                if block.is_none() {
                    // Reconcile a lost creation reply before creating another block.
                    let blocks = control
                        .call("session.list_blocks", json!({"session_id":request.session}))
                        .await?;
                    block = blocks["blocks"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .find(|b| b["label"] == label)
                        .and_then(|b| b["block_id"].as_str())
                        .map(str::to_owned);
                }
                if block.is_none() {
                    let result = control
                        .call(
                            "session.new_block",
                            json!({"session_id":request.session,
                    "flavor":"com.superlogical.tunnel.dial","label":label,
                    "options":{"host":request.host,"port":request.port,"client_address":address}}),
                        )
                        .await?;
                    block = Some(
                        result["block_id"]
                            .as_str()
                            .context("Missing tunnel block")?
                            .to_owned(),
                    );
                }
                let block_id = block.as_deref().unwrap();
                let ownership = control
                .call(
                    "session.own_block",
                    json!({"session_id":request.session,"block_ids":[block_id],"mode":"request"}),
                )
                .await?;
                ensure!(
                    ownership["blocks"]
                        .as_array()
                        .is_some_and(|blocks| blocks.iter().any(|b| b["block_id"] == block_id
                            && b["owner"].as_str() == control.client_id())),
                    "Tunnel ownership changed"
                );
                created = true;
                delay = 1;
                status(json!({"phase":"active","address":address,"block":block_id}));
                serve(&request, &listener, &mut control, block_id).await
            };
        let result = tokio::select! { result = operation => result, _ = &mut cancel => break };
        if let Err(error) = result {
            if missing(&error)
                || error.to_string().contains("requires Rex API 14")
                || error.to_string().contains("Tunnel ownership changed")
            {
                status(json!({"phase":"failed","error":error.to_string(),"address":address}));
                break;
            }
            status(
                json!({"phase":"reconnecting","address":address,"error":"Connection lost; retrying"}),
            );
        }
        tokio::select! { _ = tokio::time::sleep(Duration::from_secs(delay)) => (), _ = &mut cancel => break }
        delay = (delay * 2).min(8);
    }
    // This connection only closes the detached tunnel that this worker created.
    let cleanup = async {
        let mut control =
            Control::connect_forward(request.endpoint.clone(), token.as_deref()).await?;
        control.attach(&request.session).await?;
        if block.is_none() {
            let blocks = control
                .call("session.list_blocks", json!({"session_id":request.session}))
                .await?;
            block = blocks["blocks"]
                .as_array()
                .into_iter()
                .flatten()
                .find(|b| b["label"] == label)
                .and_then(|b| b["block_id"].as_str())
                .map(str::to_owned);
        }
        if let Some(id) = &block {
            control
                .call(
                    "block.close",
                    json!({"session_id":request.session,"block_id":id}),
                )
                .await?;
        }
        Ok::<_, anyhow::Error>(())
    };
    let _ = tokio::time::timeout(Duration::from_secs(5), cleanup).await;
    Ok(())
}
async fn serve(
    request: &Request,
    listener: &TcpListener,
    control: &mut Control,
    block: &str,
) -> Result<()> {
    let mut connections = JoinSet::new();
    let mut heartbeat = tokio::time::interval(Duration::from_secs(10));
    loop {
        tokio::select! {
            accepted = listener.accept(), if connections.len() < 32 => {
                let (socket, _) = accepted?;
                let reference = connection_ref()?;
                let ticket = control.call("data.connect", json!({"session_id":request.session,"block_id":block,
                    "data_ref_id":reference,"subprotocol":BYTES})).await?;
                let endpoint = request.endpoint.clone();
                connections.spawn(async move { let _ = relay(endpoint, ticket, socket).await; });
            }
            _ = connections.join_next(), if !connections.is_empty() => (),
            message = control.next_message() => { message?; },
            _ = heartbeat.tick() => {
                let info = control.call("com.superlogical.tunnel.info", json!({"session_id":request.session,"block_id":block,"args":{}})).await?;
                ensure!(info["last_owner"].as_str() == control.client_id(), "Tunnel ownership changed");
            }
        }
    }
}
fn connection_ref() -> Result<String> {
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes).map_err(|_| anyhow::anyhow!("Could not create connection ID"))?;
    const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    let mut result = String::from("conn:");
    let mut bits = 0u32;
    let mut count = 0;
    for b in bytes {
        bits = (bits << 8) | b as u32;
        count += 8;
        while count >= 5 {
            count -= 5;
            result.push(ALPHABET[((bits >> count) & 31) as usize] as char);
        }
    }
    if count > 0 {
        result.push(ALPHABET[((bits << (5 - count)) & 31) as usize] as char);
    }
    Ok(result)
}
async fn relay(endpoint: Endpoint, ticket: Value, socket: TcpStream) -> Result<()> {
    let bytes =
        URL_SAFE_NO_PAD.decode(ticket["ticket"].as_str().context("Missing tunnel ticket")?)?;
    ensure!(bytes.len() == 16, "Invalid tunnel ticket");
    let mut ws = endpoint.open("/api/data", BYTES).await?;
    let mut hello = vec![1];
    hello.extend(bytes);
    ws.send(Message::Binary(hello.into())).await?;
    let welcome = tokio::time::timeout(endpoint.timeout, transport::receive(&mut ws)).await??;
    ensure!(
        matches!(welcome, Message::Binary(ref b) if b.len()==32 && b[0]==0x81),
        "Tunnel handshake rejected"
    );
    pipe(&mut ws, socket).await
}
async fn pipe(ws: &mut Ws, socket: TcpStream) -> Result<()> {
    let (mut reader, mut writer) = socket.into_split();
    let mut input = [0u8; 16384];
    let (mut local_eof, mut remote_eof) = (false, false);
    while !local_eof || !remote_eof {
        tokio::select! {
            read = reader.read(&mut input), if !local_eof => {
                let size = read?;
                tokio::time::timeout(Duration::from_secs(30),ws.send(Message::Binary(input[..size].to_vec().into()))).await??;
                local_eof = size == 0;
            }
            message = transport::receive(ws), if !remote_eof => {
                let Message::Binary(bytes) = message? else {bail!("Invalid tunnel data");};
                ensure!(bytes.len() <= 65536, "Oversized tunnel data");
                if bytes.is_empty() { remote_eof = true; writer.shutdown().await?; }
                else {tokio::time::timeout(Duration::from_secs(30),writer.write_all(&bytes)).await??;}
            }
        }
    }
    ws.close().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn occupied_phone_port_reports_the_conflict_before_contacting_rex() {
        let occupied = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = occupied.local_addr().unwrap().port();
        let request = Request {
            endpoint: Endpoint::new("http://127.0.0.1:1", Duration::from_millis(100)).unwrap(),
            session: "unused".into(),
            host: "127.0.0.1".into(),
            port: 3000,
            local_port: port,
            id: "occupied-test".into(),
        };
        let (_stop, cancel) = oneshot::channel();
        let error = run(request, cancel, |_| panic!("Should fail before connecting"))
            .await
            .unwrap_err();
        assert!(
            error
                .to_string()
                .contains(&format!("Phone port {port} is unavailable"))
        );
        assert!(error.to_string().contains("choose automatically"));
    }
}
