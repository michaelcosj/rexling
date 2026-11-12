//! Local JNI format, not Rex wire protocol. One decoder per native handle.
use crate::engine::Shared;
use rex_core::ghostty::{Cell, Frame};
use std::sync::{Arc, Condvar, Mutex};

#[derive(Default)]
pub struct Updates {
    state: Mutex<(u64, bool)>,
    ready: Condvar,
}
impl Updates {
    pub fn publish(&self, revision: u64) {
        if let Ok(mut state) = self.state.lock() {
            state.0 = revision;
            self.ready.notify_all();
        }
    }
    pub fn close(&self) {
        if let Ok(mut state) = self.state.lock() {
            state.1 = true;
            self.ready.notify_all();
        }
    }
    pub fn wait(&self, after: u64, ms: u64) -> i64 {
        let Ok(state) = self.state.lock() else {
            return -1;
        };
        let Ok((state, _)) =
            self.ready
                .wait_timeout_while(state, std::time::Duration::from_millis(ms), |s| {
                    !s.1 && s.0 <= after
                })
        else {
            return -1;
        };
        if state.1 { -1 } else { state.0 as i64 }
    }
}
#[derive(Default)]
pub struct WireBase {
    revision: u64,
    metadata: String,
    activity: Arc<serde_json::Value>,
    key: String,
    frame: Option<Arc<Frame>>,
}
fn u16le(out: &mut Vec<u8>, v: u16) {
    out.extend(v.to_le_bytes());
}
fn u32le(out: &mut Vec<u8>, v: u32) {
    out.extend(v.to_le_bytes());
}
fn u64le(out: &mut Vec<u8>, v: u64) {
    out.extend(v.to_le_bytes());
}
fn text(out: &mut Vec<u8>, v: &str) {
    u32le(out, v.len() as u32);
    out.extend(v.as_bytes());
}
fn rgb(v: [u8; 3]) -> u32 {
    0xff000000 | ((v[0] as u32) << 16) | ((v[1] as u32) << 8) | v[2] as u32
}
fn rows(frame: &Frame) -> Vec<&[Cell]> {
    let mut result = Vec::with_capacity(frame.rows as usize);
    let mut start = 0;
    for y in 0..frame.rows {
        let end = start + frame.cells[start..].iter().take_while(|c| c.y == y).count();
        result.push(&frame.cells[start..end]);
        start = end;
    }
    result
}
pub fn encode(state: &mut Shared, after: u64) -> Vec<u8> {
    if after >= state.revision {
        return Vec::new();
    }
    let base = &state.wire;
    let reset = after != base.revision || base.key != state.key;
    let mut out = b"RXT3".to_vec();
    u64le(&mut out, state.revision);
    let previews = std::mem::take(&mut state.pending_previews);
    let activity_changed=reset || !Arc::ptr_eq(&base.activity,&state.activity);
    if reset || base.metadata != state.json || activity_changed || !previews.is_empty() {
        let mut metadata: serde_json::Value =
            serde_json::from_str(&state.json).expect("internal metadata JSON");
        if activity_changed {metadata["activity"]=state.activity.as_ref().clone();}
        if !previews.is_empty() {
            metadata["previews"] = serde_json::to_value(previews).expect("preview frames");
        }
        text(&mut out, &metadata.to_string());
    } else {
        text(&mut out, "");
    }
    match &state.frame {
        None => out.push(1),
        Some(frame)
            if !reset
                && base
                    .frame
                    .as_ref()
                    .is_some_and(|old| Arc::ptr_eq(old, frame)) =>
        {
            out.push(0)
        }
        Some(frame) => {
            let previous = base
                .frame
                .as_ref()
                .filter(|old| !reset && old.columns == frame.columns && old.rows == frame.rows);
            out.push(if previous.is_some() { 2 } else { 3 });
            u16le(&mut out, frame.columns);
            u16le(&mut out, frame.rows);
            let (x, y) = frame
                .cursor
                .map_or((-1, -1), |(x, y)| (i32::from(x), i32::from(y)));
            out.extend(x.to_le_bytes());
            out.extend(y.to_le_bytes());
            u32le(&mut out, rgb(frame.foreground));
            u32le(&mut out, rgb(frame.background));
            for v in [
                frame.scroll_total,
                frame.scroll_offset,
                frame.scroll_visible,
            ] {
                u64le(&mut out, v);
            }
            out.push(frame.input_flags);
            let next = rows(frame);
            let old = previous.map(|old| rows(old));
            let changed: Vec<_> = next
                .iter()
                .enumerate()
                .filter(|(y, row)| old.as_ref().is_none_or(|old| old[*y] != **row))
                .collect();
            u16le(&mut out, changed.len() as u16);
            for (y, row) in changed {
                u16le(&mut out, y as u16);
                u16le(&mut out, row.len() as u16);
                for cell in *row {
                    u16le(&mut out, cell.x);
                    out.push(cell.width);
                    u32le(&mut out, cell.attributes);
                    u32le(&mut out, rgb(cell.foreground));
                    u32le(&mut out, rgb(cell.background));
                    text(&mut out, &cell.text);
                }
                // Sparse contiguous link ranges keep ordinary rows cheap.
                let mut links: Vec<(u16,u16,&str)>=Vec::new();
                for cell in *row {
                    if cell.width==0 {continue;}
                    if let Some(uri)=cell.hyperlink.as_deref() {
                        if let Some(last)=links.last_mut().filter(|last|last.1==cell.x && last.2==uri) {
                            last.1=cell.x+u16::from(cell.width);
                        } else {links.push((cell.x,cell.x+u16::from(cell.width),uri));}
                    }
                }
                u16le(&mut out,links.len() as u16);
                for (start,end,uri) in links {u16le(&mut out,start);u16le(&mut out,end);text(&mut out,uri);}

            }
        }
    }
    state.wire = WireBase {
        revision: state.revision,
        metadata: state.json.clone(),
        activity: state.activity.clone(),
        key: state.key.clone(),
        frame: state.frame.clone(),
    };
    out
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn notification_wakes_for_updates_and_shutdown() {
        let updates = Arc::new(Updates::default());
        let writer = updates.clone();
        let thread = std::thread::spawn(move || {
            writer.publish(4);
        });
        assert_eq!(updates.wait(0, 1000), 4);
        thread.join().unwrap();
        assert_eq!(updates.wait(4, 1), 4);
        updates.close();
        assert_eq!(updates.wait(4, 1), -1);
    }
}

#[cfg(test)]
mod wire_tests {
    use super::*;
    use std::collections::BTreeMap;
    fn frame() -> Frame {
        Frame {
            columns: 100,
            rows: 50,
            cursor: Some((0, 0)),
            cursor_at: Some((0, 0)),
            foreground: [255; 3],
            background: [0; 3],
            cells: (0..50)
                .flat_map(|y| {
                    (0..100).map(move |x| Cell {
                        x,
                        y,
                        text: "x".into(),
                        foreground: [255; 3],
                        background: [0; 3],
                        attributes: 0,
                        width: 1,
                        hyperlink: None,
                    })
                })
                .collect(),
            scroll_total: 50,
            scroll_offset: 0,
            scroll_visible: 50,
            input_flags: 0,
        }
    }
    #[test]
    fn packets_diff_against_delivered_frame_and_reset_on_unknown_base() {
        let mut state = Shared {
            revision: 1,
            json: "{}".into(),
            pending_previews: BTreeMap::new(),
            frame: Some(Arc::new(frame())),
            key: "a".into(),
            wire: WireBase::default(),activity:Arc::default(),
        };
        let full = encode(&mut state, 0);
        let metadata_len=u32::from_le_bytes(full[12..16].try_into().unwrap()) as usize;
        assert_eq!(full[16+metadata_len], 3);
        let mut next = frame();
        next.cells[200].text = "λ🦀".into();
        state.frame = Some(Arc::new(next));
        state.revision = 3;
        let delta = encode(&mut state, 1);
        assert_eq!(delta[16], 2);
        assert!(delta.len() < full.len() / 20);
        state.revision = 4;
        let reset = encode(&mut state, 1);
        assert_eq!(reset[16+u32::from_le_bytes(reset[12..16].try_into().unwrap()) as usize], 3);
        state.key = "other host pane".into();
        state.revision = 5;
        let reset=encode(&mut state,4);
        assert_eq!(reset[16+u32::from_le_bytes(reset[12..16].try_into().unwrap()) as usize], 3);
        assert!(encode(&mut state, 5).is_empty());
        println!(
            "wire payload bytes: full={} one-row delta={}",
            full.len(),
            delta.len()
        );
    }
    #[test]
    fn a_closed_notifier_wakes_a_blocked_reader() {
        let updates = Arc::new(Updates::default());
        let reader = updates.clone();
        let thread = std::thread::spawn(move || reader.wait(0, 1000));
        updates.close();
        assert_eq!(thread.join().unwrap(), -1);
    }
    #[test]
    fn hyperlink_only_changes_emit_a_sparse_row_delta_and_removal() {
        let mut first=frame();
        for c in &mut first.cells[..3] {c.hyperlink=Some("https://example.com/one".into());}
        let mut state=Shared {revision:1,json:"{}".into(),pending_previews:BTreeMap::new(),frame:Some(Arc::new(first)),key:"a".into(),wire:WireBase::default(),activity:Arc::default()};
        let full=encode(&mut state,0);assert_eq!(&full[..4],b"RXT3");
        assert_eq!(full.windows(b"https://example.com/one".len()).filter(|w|*w==b"https://example.com/one").count(),1);
        let mut next=frame();for c in &mut next.cells[..3] {c.hyperlink=Some("https://example.com/two".into());}
        state.frame=Some(Arc::new(next));state.revision=2;
        let delta=encode(&mut state,1);assert_eq!(delta[16],2);assert!(delta.len()<full.len()/20);
        assert!(delta.ends_with(b"https://example.com/two"));
        state.frame=Some(Arc::new(frame()));state.revision=3;
        let removed=encode(&mut state,2);assert_eq!(removed[16],2);assert!(removed.ends_with(&[0,0]));
    }

    #[test] fn activity_catalog_is_sent_only_when_changed_and_resets_clear_it() {
        let mut state=Shared {revision:1,json:"{}".into(),activity:Arc::new(serde_json::json!([{"title":"Task"}])),pending_previews:BTreeMap::new(),frame:None,key:"a".into(),wire:WireBase::default()};
        let metadata=|packet:&[u8]|->serde_json::Value {let n=u32::from_le_bytes(packet[12..16].try_into().unwrap()) as usize;serde_json::from_slice(&packet[16..16+n]).unwrap()};
        assert!(metadata(&encode(&mut state,0))["activity"].is_array());
        state.revision=2;state.json="{\"titles\":{\"a\":\"Changed\"}}".into();
        assert!(metadata(&encode(&mut state,1)).get("activity").is_none());
        state.revision=3;state.activity=Arc::default();assert!(metadata(&encode(&mut state,2))["activity"].is_null());
        state.revision=4;assert!(metadata(&encode(&mut state,1))["activity"].is_null());
    }

}
