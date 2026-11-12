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
/// Cells compare without `y`, so a row that only moved still matches.
fn same_row(a: &[Cell], b: &[Cell]) -> bool {
    a.len() == b.len() && a.iter().zip(b).all(|(a, b)| {
        a.x == b.x && a.width == b.width && a.attributes == b.attributes && a.foreground == b.foreground
            && a.background == b.background && a.text == b.text && a.hyperlink == b.hyperlink
    })
}
/// Scroll distance between two frames: how far the viewport's top moved in
/// the scrollback (scrolling, or output pushing a bottom-pinned view up).
/// Trusted only when most of the moved rows still match.
fn row_shift(old: &[&[Cell]], next: &[&[Cell]], previous: &Frame, frame: &Frame) -> i16 {
    let moved = frame.scroll_offset as i64 - previous.scroll_offset as i64;
    checked_shift(old, next, moved)
}
fn checked_shift(old: &[&[Cell]], next: &[&[Cell]], shift: i64) -> i16 {
    let rows = next.len() as i64;
    if shift == 0 || shift.abs() >= rows || shift.abs() > i64::from(i16::MAX) {return 0;}
    let overlap: Vec<_> = (0..rows).filter(|y| (0..rows).contains(&(y + shift))).collect();
    let same = overlap.iter().filter(|y| same_row(old[(**y + shift) as usize], next[**y as usize])).count();
    if same * 2 >= overlap.len() {shift as i16} else {0}
}
fn encode_row(out: &mut Vec<u8>, row: &[Cell]) {
    u16le(out, row.len() as u16);
    for cell in row {
            u16le(out, cell.x);
            out.push(cell.width);
            u32le(out, cell.attributes);
            u32le(out, rgb(cell.foreground));
            u32le(out, rgb(cell.background));
            text(out, &cell.text);
        }
        // Sparse contiguous link ranges keep ordinary rows cheap.
        let mut links: Vec<(u16, u16, &str)> = Vec::new();
        for cell in row {
            if cell.width == 0 {
                continue;
            }
            if let Some(uri) = cell.hyperlink.as_deref() {
                if let Some(last) = links
                    .last_mut()
                    .filter(|last| last.1 == cell.x && last.2 == uri)
                {
                    last.1 = cell.x + u16::from(cell.width);
                } else {
                    links.push((cell.x, cell.x + u16::from(cell.width), uri));
                }
            }
        }
        u16le(out, links.len() as u16);
        for (start, end, uri) in links {
            u16le(out, start);
            u16le(out, end);
            text(out, uri);
        }
}
fn encode_frame(out: &mut Vec<u8>, frame: &Frame, previous: Option<&Frame>) {
    out.push(if previous.is_some() { 2 } else { 3 });
    u16le(out, frame.columns);
    u16le(out, frame.rows);
    let (x, y) = frame
        .cursor
        .map_or((-1, -1), |(x, y)| (i32::from(x), i32::from(y)));
    out.extend(x.to_le_bytes());
    out.extend(y.to_le_bytes());
    u32le(out, rgb(frame.foreground));
    u32le(out, rgb(frame.background));
    for v in [
        frame.scroll_total,
        frame.scroll_offset,
        frame.scroll_visible,
    ] {
        u64le(out, v);
    }
    out.push(frame.input_flags);
    let next = rows(frame);
    let old = previous.map(|old| rows(old));
    // RXT5 shift: content moved by `shift` rows (positive: up). The decoder
    // moves its rows first, so only rows that are really new are sent.
    let shift = old.as_ref().zip(previous).map_or(0, |(old, previous)| row_shift(old, &next, previous, frame));
    out.extend(shift.to_le_bytes());
    let base = |y: usize| -> Option<&[Cell]> {
        let old = old.as_ref()?;
        let from = y as i64 + i64::from(shift);
        (from >= 0 && (from as usize) < old.len()).then(|| old[from as usize])
    };
    let changed: Vec<_> = next
        .iter()
        .enumerate()
        .filter(|(y, row)| base(*y).is_none_or(|old| !same_row(old, row)))
        .collect();
    u16le(out, changed.len() as u16);
    for (y, row) in changed {
        u16le(out, y as u16);
        encode_row(out, row);
    }
    // RXT5: spare rows around the viewport for smooth scrolling. Sent in full;
    // there are at most a few on each side.
    for side in [&frame.above, &frame.below] {
        out.push(side.len() as u8);
        for row in side {encode_row(out, row);}
    }
    u32le(out, frame.image_cell_width);
    u32le(out, frame.image_cell_height);
    let unique: std::collections::BTreeMap<_, _> = frame
        .images
        .iter()
        .map(|p| ((p.image.id, p.image.generation), &p.image))
        .collect();
    let changed: Vec<_> = unique
        .values()
        .filter(|image| {
            previous.is_none_or(|old| !old.images.iter().any(|p| Arc::ptr_eq(&p.image, image)))
        })
        .collect();
    u16le(out, changed.len() as u16);
    for image in changed {
        u32le(out, image.id);
        u64le(out, image.generation);
        u32le(out, image.width);
        u32le(out, image.height);
        u32le(out, image.rgba.len() as u32);
        out.extend_from_slice(&image.rgba);
    }
    u16le(out, frame.images.len() as u16);
    for p in &frame.images {
        u32le(out, p.image.id);
        u64le(out, p.image.generation);
        out.push(p.layer as u8);
        for v in [p.z, p.col, p.row] {
            out.extend(v.to_le_bytes());
        }
        for v in [p.ox, p.oy, p.width, p.height, p.sx, p.sy, p.sw, p.sh] {
            u32le(out, v);
        }
    }
}
pub fn encode(state: &mut Shared, after: u64) -> Vec<u8> {
    if after >= state.revision {
        return Vec::new();
    }
    let base = &state.wire;
    let reset = after != base.revision || base.key != state.key;
    let mut out = b"RXT5".to_vec();
    u64le(&mut out, state.revision);
    let previews: Vec<_> = std::mem::take(&mut state.pending_previews)
        .into_iter()
        .take(32)
        .collect();
    let activity_changed = reset || !Arc::ptr_eq(&base.activity, &state.activity);
    if reset || base.metadata != state.json || activity_changed || !previews.is_empty() {
        let mut metadata: serde_json::Value =
            serde_json::from_str(&state.json).expect("internal metadata JSON");
        if activity_changed {
            metadata["activity"] = state.activity.as_ref().clone();
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
            encode_frame(&mut out, frame, previous.map(Arc::as_ref));
        }
    }
    u16le(&mut out, previews.len() as u16);
    for (id, frame) in previews {
        text(&mut out, &id);
        encode_frame(&mut out, &frame, None);
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
            next_image_delay_ms: None,
            image_cell_width: 8,
            image_cell_height: 16,
            images: Vec::new(),
            above: Vec::new(),
            below: Vec::new(),
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
            wire: WireBase::default(),
            activity: Arc::default(),
        };
        let full = encode(&mut state, 0);
        let metadata_len = u32::from_le_bytes(full[12..16].try_into().unwrap()) as usize;
        assert_eq!(full[16 + metadata_len], 3);
        let mut next = frame();
        next.cells[200].text = "λ🦀".into();
        state.frame = Some(Arc::new(next));
        state.revision = 3;
        let delta = encode(&mut state, 1);
        assert_eq!(delta[16], 2);
        assert!(delta.len() < full.len() / 20);
        state.revision = 4;
        let reset = encode(&mut state, 1);
        assert_eq!(
            reset[16 + u32::from_le_bytes(reset[12..16].try_into().unwrap()) as usize],
            3
        );
        state.key = "other host pane".into();
        state.revision = 5;
        let reset = encode(&mut state, 4);
        assert_eq!(
            reset[16 + u32::from_le_bytes(reset[12..16].try_into().unwrap()) as usize],
            3
        );
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
        let mut first = frame();
        for c in &mut first.cells[..3] {
            c.hyperlink = Some("https://example.com/one".into());
        }
        let mut state = Shared {
            revision: 1,
            json: "{}".into(),
            pending_previews: BTreeMap::new(),
            frame: Some(Arc::new(first)),
            key: "a".into(),
            wire: WireBase::default(),
            activity: Arc::default(),
        };
        let full = encode(&mut state, 0);
        assert_eq!(&full[..4], b"RXT5");
        assert_eq!(
            full.windows(b"https://example.com/one".len())
                .filter(|w| *w == b"https://example.com/one")
                .count(),
            1
        );
        let mut next = frame();
        for c in &mut next.cells[..3] {
            c.hyperlink = Some("https://example.com/two".into());
        }
        state.frame = Some(Arc::new(next));
        state.revision = 2;
        let delta = encode(&mut state, 1);
        assert_eq!(delta[16], 2);
        assert!(delta.len() < full.len() / 20);
        assert!(
            delta
                .windows(b"https://example.com/two".len())
                .any(|w| w == b"https://example.com/two")
        );
        state.frame = Some(Arc::new(frame()));
        state.revision = 3;
        let removed = encode(&mut state, 2);
        assert_eq!(removed[16], 2);
        assert!(removed.ends_with(&[0, 0]));
    }

    #[test]
    fn activity_catalog_is_sent_only_when_changed_and_resets_clear_it() {
        let mut state = Shared {
            revision: 1,
            json: "{}".into(),
            activity: Arc::new(serde_json::json!([{"title":"Task"}])),
            pending_previews: BTreeMap::new(),
            frame: None,
            key: "a".into(),
            wire: WireBase::default(),
        };
        let metadata = |packet: &[u8]| -> serde_json::Value {
            let n = u32::from_le_bytes(packet[12..16].try_into().unwrap()) as usize;
            serde_json::from_slice(&packet[16..16 + n]).unwrap()
        };
        assert!(metadata(&encode(&mut state, 0))["activity"].is_array());
        state.revision = 2;
        state.json = "{\"titles\":{\"a\":\"Changed\"}}".into();
        assert!(metadata(&encode(&mut state, 1)).get("activity").is_none());
        state.revision = 3;
        state.activity = Arc::default();
        assert!(metadata(&encode(&mut state, 2))["activity"].is_null());
        state.revision = 4;
        assert!(metadata(&encode(&mut state, 1))["activity"].is_null());
    }
    fn scrolled(offset: u64) -> Frame {
        // Rows show scrollback line numbers: row y shows line offset+y.
        let mut f = frame();
        f.scroll_total = 1000;
        f.scroll_offset = offset;
        for c in &mut f.cells {c.text = format!("{}", (offset + u64::from(c.y)) % 10);c.attributes = (offset + u64::from(c.y)) as u32;}
        f
    }
    fn shift_and_rows(packet: &[u8]) -> (i16, u16) {
        // Header: magic 4, revision 8, metadata len 4 (+ bytes), flag 1, cols 2, rows 2, cursor 8, colors 8, scroll 24, input 1.
        let meta = u32::from_le_bytes(packet[12..16].try_into().unwrap()) as usize;
        let at = 16 + meta + 1 + 2 + 2 + 8 + 8 + 24 + 1;
        (i16::from_le_bytes(packet[at..at + 2].try_into().unwrap()), u16::from_le_bytes(packet[at + 2..at + 4].try_into().unwrap()))
    }
    #[test]
    fn scrolling_sends_a_shift_and_only_the_new_rows() {
        let mut state = Shared {
            revision: 1, json: "{}".into(), pending_previews: BTreeMap::new(),
            frame: Some(Arc::new(scrolled(100))), key: "a".into(), wire: WireBase::default(), activity: Arc::default(),
        };
        let full = encode(&mut state, 0);
        assert_eq!(shift_and_rows(&full), (0, 50));
        // Scroll down 3 lines: rows move up by 3, three new rows at the bottom.
        state.frame = Some(Arc::new(scrolled(103)));
        state.revision = 2;
        let delta = encode(&mut state, 1);
        assert_eq!(shift_and_rows(&delta), (3, 3));
        assert!(delta.len() < full.len() / 10, "{} vs {}", delta.len(), full.len());
        // Scroll back up 2: shift -2, two new rows at the top.
        state.frame = Some(Arc::new(scrolled(101)));
        state.revision = 3;
        assert_eq!(shift_and_rows(&encode(&mut state, 2)), (-2, 2));
        // A jump larger than the screen is a full repaint, no shift.
        state.frame = Some(Arc::new(scrolled(500)));
        state.revision = 4;
        assert_eq!(shift_and_rows(&encode(&mut state, 3)), (0, 50));
    }
}

#[cfg(test)]
mod graphics_tests {
    use super::*;
    use std::collections::BTreeMap;
    #[test]
    fn unchanged_image_pixels_are_not_retransmitted_and_previews_keep_images() {
        let mut terminal = rex_core::ghostty::Terminal::new(10, 5).unwrap();
        terminal.resize(10, 5, 8, 16).unwrap();
        terminal
            .write(b"\x1b_Ga=T,f=32,s=2,v=1,i=7,c=2,r=1;/wAA/wD/AP8=\x1b\\")
            .unwrap();
        let frame = Arc::new(terminal.frame().unwrap());
        assert_eq!(frame.images.len(), 1);
        let mut state = Shared {
            revision: 1,
            json: r#"{"phase":"preview","selectedBlock":"image"}"#.into(),
            activity: Arc::default(),
            pending_previews: BTreeMap::new(),
            frame: Some(frame.clone()),
            key: "image".into(),
            wire: WireBase::default(),
        };
        let full = encode(&mut state, 0);
        terminal.write(b"x").unwrap();
        let next = Arc::new(terminal.frame().unwrap());
        assert!(Arc::ptr_eq(&frame.images[0].image, &next.images[0].image));
        state.frame = Some(next);
        state.revision = 2;
        let delta = encode(&mut state, 1);
        assert!(delta.len() < full.len());
        state.pending_previews.insert("other".into(), frame);
        state.revision = 3;
        let preview = encode(&mut state, 2);
        assert!(preview.len() > delta.len());
        terminal.write(b"\x1b_Ga=d,d=I,i=7\x1b\\").unwrap();
        state.frame = Some(Arc::new(terminal.frame().unwrap()));
        state.revision = 4;
        let deleted = encode(&mut state, 3);
        if let Ok(path) = std::env::var("REXLING_GRAPHICS_FIXTURES") {
            std::fs::create_dir_all(&path).unwrap();
            for (name, bytes) in [
                ("full", full),
                ("delta", delta),
                ("preview", preview),
                ("deleted", deleted),
            ] {
                std::fs::write(
                    std::path::Path::new(&path).join(format!("kitty_{name}.rxt")),
                    bytes,
                )
                .unwrap();
            }
        }
    }
}
