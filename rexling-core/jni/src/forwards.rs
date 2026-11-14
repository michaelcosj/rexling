use anyhow::{Context, Result, ensure};
use rex_core::{forward, transport::Endpoint};
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};
use tokio::sync::oneshot;
struct Job {
    state: Arc<Mutex<Value>>,
    cancel: oneshot::Sender<()>,
}
static JOBS: OnceLock<Mutex<HashMap<u64, Job>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
fn jobs() -> &'static Mutex<HashMap<u64, Job>> {
    JOBS.get_or_init(|| Mutex::new(HashMap::new()))
}
pub fn create(value: Value) -> Result<u64> {
    let field = |key: &str| {
        value[key]
            .as_str()
            .filter(|s| !s.is_empty())
            .map(str::to_owned)
            .with_context(|| format!("Forward {key} missing"))
    };
    let endpoint = Endpoint::new(&field("endpoint")?, Duration::from_secs(10))?;
    let port = value["port"]
        .as_u64()
        .filter(|p| *p > 0 && *p <= 65535)
        .context("Enter a port from 1 to 65535")? as u16;
    let local_port = value["local_port"].as_u64().unwrap_or(0);
    ensure!(local_port <= 65535, "Invalid local port");
    let request = forward::Request {
        endpoint,
        session: field("session")?,
        host: field("host")?,
        port,
        local_port: local_port as u16,
        id: field("id")?,
    };
    ensure!(
        request.id.len() <= 64 && request.host.len() <= 253,
        "Forward address is too long"
    );
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    let state = Arc::new(Mutex::new(json!({"phase":"connecting"})));
    let shared = state.clone();
    let (cancel, rx) = oneshot::channel();
    let mut registry = jobs()
        .lock()
        .map_err(|_| anyhow::anyhow!("Forward registry unavailable"))?;
    ensure!(
        registry.len() < 8,
        "Stop a forward before adding another (maximum 8)"
    );
    std::thread::Builder::new()
        .name("rexling-forward".into())
        .spawn(move || {
            let result =
                std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<()> {
                    tokio::runtime::Builder::new_current_thread()
                        .enable_all()
                        .build()?
                        .block_on(forward::run(request, rx, |value| {
                            if let Ok(mut state) = shared.lock() {
                                *state = value;
                            }
                        }))
                }));
            let error = match result {
                Ok(Ok(())) => None,
                Ok(Err(e)) => Some(e.to_string()),
                Err(_) => Some("Forward worker failed".into()),
            };
            if let Some(error) = error {
                if let Ok(mut state) = shared.lock() {
                    *state = json!({"phase":"failed","error":error});
                }
            }
        })?;
    registry.insert(id, Job { state, cancel });
    Ok(id)
}
pub fn poll(id: u64) -> Value {
    jobs()
        .lock()
        .ok()
        .and_then(|jobs| {
            jobs.get(&id)
                .and_then(|job| job.state.lock().ok().map(|v| v.clone()))
        })
        .unwrap_or_else(|| json!({"phase":"failed","error":"Forward is closed"}))
}
pub fn destroy(id: u64) {
    if let Ok(mut jobs) = jobs().lock() {
        if let Some(job) = jobs.remove(&id) {
            let _ = job.cancel.send(());
        }
    }
}
