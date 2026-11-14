//! Android's embedded Go transport exposes connected byte streams as owned FDs.
use anyhow::{Result, Context};
use std::{ffi::{CString,CStr,c_char,c_int,c_void}, sync::atomic::{AtomicBool,Ordering}, os::fd::FromRawFd};
static BUILT_IN: AtomicBool=AtomicBool::new(false);
unsafe extern "C" {
    fn rexnet_command(request: *const c_char) -> *mut c_char;
    fn rexnet_dial(endpoint: *const c_char, milliseconds: c_int, error: *mut *mut c_char) -> c_int;
    fn rexnet_free(pointer: *mut c_void);
}
pub fn enabled() -> bool {BUILT_IN.load(Ordering::Acquire)}
pub fn command(request: &str) -> Result<String> {
    let parsed: serde_json::Value=serde_json::from_str(request)?;
    let input=CString::new(request)?;
    let pointer=unsafe {rexnet_command(input.as_ptr())};
    anyhow::ensure!(!pointer.is_null(),"Native network response missing");
    let response=unsafe {CStr::from_ptr(pointer).to_string_lossy().into_owned()};
    unsafe {rexnet_free(pointer.cast())};
    let value: serde_json::Value=serde_json::from_str(&response)?;
    if parsed["type"]=="configure" && value["error"].is_null() {
        BUILT_IN.store(parsed["config"]["built_in"].as_bool().unwrap_or(false),Ordering::Release);
    }
    Ok(response)
}
pub async fn connect(endpoint: String, timeout: std::time::Duration) -> Result<tokio::net::UnixStream> {
    let socket=tokio::task::spawn_blocking(move || -> Result<std::os::unix::net::UnixStream> {
        let endpoint=CString::new(endpoint)?;
        let mut error=std::ptr::null_mut();
        let descriptor=unsafe {rexnet_dial(endpoint.as_ptr(),timeout.as_millis().min(c_int::MAX as u128) as c_int,&mut error)};
        if descriptor<0 {
            let message=if error.is_null() {"Connection failed".into()} else {
                let text=unsafe {CStr::from_ptr(error).to_string_lossy().into_owned()};
                unsafe {rexnet_free(error.cast())};text
            };
            anyhow::bail!("{message}");
        }
        let socket=unsafe {std::os::unix::net::UnixStream::from_raw_fd(descriptor)};
        socket.set_nonblocking(true)?;
        Ok(socket)
    }).await.context("Network worker stopped")??;
    Ok(tokio::net::UnixStream::from_std(socket)?)
}
