//! Safe, single-thread-owned libghostty-vt replica for Rex's terminal stream.
use crate::snapshot::SnapshotBuffer;
use anyhow::{Context, Result, ensure};
use serde_json::Value;
use std::{
    ffi::c_void,
    marker::PhantomData,
    ptr::{self, NonNull},
    rc::Rc,
};

type ApcFn = unsafe extern "C" fn(*mut c_void, u8, *const u8, usize);
type CellFn =
    unsafe extern "C" fn(*mut c_void, u16, u16, *const u32, usize, *const u8, *const u8, u32, u8, *const u8, usize);
#[repr(C)]
#[derive(Default)]
struct RawFrame {
    columns: u16,
    rows: u16,
    cursor_x: u16,
    cursor_y: u16,
    cursor_visible: u8,
    foreground: [u8; 3],
    background: [u8; 3],
    scroll_total: u64,
    scroll_offset: u64,
    scroll_visible: u64,
    input_flags: u8,
}
unsafe extern "C" {
    fn rp_new(cols: u16, rows: u16, out: *mut *mut c_void) -> i32;
    fn rp_decode(bytes: *const u8, len: usize, out: *mut *mut c_void, offset: *mut usize) -> i32;
    fn rp_free(term: *mut c_void);
    fn rp_write(
        term: *mut c_void,
        bytes: *const u8,
        len: usize,
        consumed: *mut usize,
        callback: ApcFn,
        userdata: *mut c_void,
    ) -> i32;
    fn rp_resize(term: *mut c_void, cols: u16, rows: u16, cw: u32, ch: u32) -> i32;
    fn rp_color(term: *mut c_void, which: i32, rgb: *const u8) -> i32;
    fn rp_palette(term: *mut c_void, index: u8, rgb: *const u8) -> i32;
    fn rp_text(term: *mut c_void, out: *mut *mut u8, len: *mut usize) -> i32;
    fn rp_command_text(term: *mut c_void,col:u16,row:u16,output:i32,out:*mut *mut u8,len:*mut usize)->i32;
    fn rp_encode(term: *mut c_void, out: *mut *mut u8, len: *mut usize) -> i32;
    fn rp_bytes_free(bytes: *mut u8, len: usize);
    fn rp_scroll(term: *mut c_void, lines: i32, bottom: i32);
    fn rp_scroll_prompts(term: *mut c_void,prompts: i32);
    fn rp_input_flags(term: *mut c_void, flags: *mut u8) -> i32;
    fn rp_render_hold_state(term: *mut c_void,held: *mut u8,generation: *mut u64) -> i32;
    fn rp_wheel(term: *mut c_void, down: i32, col: i32, row: i32, cw: u32, ch: u32, out: *mut u8, capacity: usize, len: *mut usize) -> i32;
    fn rp_click(term: *mut c_void, col: i32, row: i32, cw: u32, ch: u32, out: *mut u8, capacity: usize, len: *mut usize) -> i32;
    fn rp_frame(
        term: *mut c_void,
        frame: *mut RawFrame,
        callback: CellFn,
        userdata: *mut c_void,
    ) -> i32;
}
pub(crate) fn check(code: i32, action: &str) -> Result<()> {
    ensure!(code == 0, "libghostty {action} failed (result {code})");
    Ok(())
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Cell {
    pub x: u16,
    pub y: u16,
    pub text: String,
    pub foreground: [u8; 3],
    pub background: [u8; 3],
    pub attributes: u32,
    pub width: u8,
    pub hyperlink: Option<String>,
}
#[derive(Debug)]
pub struct Frame {
    pub columns: u16,
    pub rows: u16,
    pub cursor: Option<(u16, u16)>,
    pub foreground: [u8; 3],
    pub background: [u8; 3],
    pub cells: Vec<Cell>,
    pub scroll_total: u64,
    pub scroll_offset: u64,
    pub scroll_visible: u64,
    pub input_flags: u8,
    pub next_image_delay_ms:Option<u64>,
    pub image_cell_width:u32,pub image_cell_height:u32,pub images:Vec<crate::graphics::Placement>,
}
impl Frame {
    pub fn text(&self) -> String {
        (0..self.rows)
            .map(|y| {
                self.cells
                    .iter()
                    .filter(|c| c.y == y && c.width > 0)
                    .map(|c| if c.text.is_empty() { " " } else { &c.text })
                    .collect::<String>()
                    .trim_end()
                    .to_owned()
            })
            .collect::<Vec<_>>()
            .join("\n")
            .trim_end()
            .to_owned()
    }
    /// ANSI output is only a diagnostic renderer; Android will draw these cells itself.
    pub fn ansi(&self, local_cols: u16, local_rows: u16) -> Vec<u8> {
        use std::fmt::Write;
        let mut s = String::from("\x1b[?25l\x1b[0m\x1b[2J\x1b[H");
        for c in &self.cells {
            if c.x >= local_cols || c.y >= local_rows || c.width == 0 {
                continue;
            }
            let (fg, bg) = if c.attributes & 8 != 0 {
                (c.background, c.foreground)
            } else {
                (c.foreground, c.background)
            };
            let _ = write!(
                s,
                "\x1b[{};{}H\x1b[0;38;2;{};{};{};48;2;{};{};{}m",
                c.y + 1,
                c.x + 1,
                fg[0],
                fg[1],
                fg[2],
                bg[0],
                bg[1],
                bg[2]
            );
            for (mask, code) in [(1, 1), (2, 3), (4, 2), (16, 8), (32, 9), (64, 4)] {
                if c.attributes & mask != 0 {
                    let _ = write!(s, "\x1b[{code}m");
                }
            }
            if c.text.is_empty() {
                s.push(' ');
            } else {
                s.push_str(&c.text);
            }
        }
        s.push_str("\x1b[0m");
        if let Some((x, y)) = self
            .cursor
            .filter(|(x, y)| *x < local_cols && *y < local_rows)
        {
            let _ = write!(s, "\x1b[{};{}H\x1b[?25h", y + 1, x + 1);
        }
        s.into_bytes()
    }
}

pub struct Terminal {
    handle: NonNull<c_void>,
    _owner_thread: PhantomData<Rc<()>>,
    images:crate::graphics::Images,
    graphics_clock:std::time::Instant,
    render_hold:Option<(u64,u64)>,
}
impl Drop for Terminal {
    fn drop(&mut self) {
        unsafe {
            rp_free(self.handle.as_ptr());
        }
    }
}
impl Terminal {
    pub fn new(columns: u16, rows: u16) -> Result<Self> {
        ensure!(
            columns > 0 && rows > 0,
            "terminal dimensions must be positive"
        );
        crate::graphics::init()?;
        let mut handle = ptr::null_mut();
        check(unsafe { rp_new(columns, rows, &mut handle) }, "create")?;
        Ok(Self {
            handle: NonNull::new(handle).context("null terminal")?,
            _owner_thread: PhantomData,images:Default::default(),graphics_clock:std::time::Instant::now(),render_hold:None,
        })
    }
    pub fn decode(bytes: &[u8]) -> Result<(Self, usize)> {
        crate::graphics::init()?;
        let mut handle = ptr::null_mut();
        let mut offset = 0;
        check(
            unsafe { rp_decode(bytes.as_ptr(), bytes.len(), &mut handle, &mut offset) },
            "decode snapshot",
        )?;
        Ok((
            Self {
                handle: NonNull::new(handle).context("null decoded terminal")?,
                _owner_thread: PhantomData,images:Default::default(),graphics_clock:std::time::Instant::now(),render_hold:None,
            },
            offset,
        ))
    }
    fn step(&mut self, bytes: &[u8]) -> Result<(usize, Vec<(u8,Vec<u8>)>)> {
        unsafe extern "C" fn apc(userdata: *mut c_void, kind: u8, bytes: *const u8, len: usize) {
            // Synchronous callback, borrowed bytes copied before native code returns.
            unsafe {
                let events=&mut *userdata.cast::<Vec<(u8,Vec<u8>)>>();
                if kind==1 {events.retain(|(kind,_)|*kind!=1);}
                let data=if len==0 {Vec::new()} else {std::slice::from_raw_parts(bytes,len).to_vec()};
                events.push((kind,data));
            }
        }
        let mut consumed = 0;
        let mut events = Vec::<(u8,Vec<u8>)>::new();
        check(
            unsafe {
                rp_write(
                    self.handle.as_ptr(),
                    bytes.as_ptr(),
                    bytes.len(),
                    &mut consumed,
                    apc,
                    (&mut events as *mut Vec<(u8,Vec<u8>)>).cast(),
                )
            },
            "write VT",
        )?;
        ensure!(
            consumed > 0 && consumed <= bytes.len(),
            "Ghostty parser made no progress"
        );
        Ok((consumed, events))
    }
    pub fn write(&mut self, mut bytes: &[u8]) -> Result<()> {
        while !bytes.is_empty() {
            let (n, _) = self.step(bytes)?;
            bytes = &bytes[n..];
        }
        Ok(())
    }
    pub fn resize(&mut self, cols: u16, rows: u16, cw: u32, ch: u32) -> Result<()> {
        ensure!(cols > 0 && rows > 0, "invalid resize");
        check(
            unsafe { rp_resize(self.handle.as_ptr(), cols, rows, cw, ch) },
            "resize",
        )
    }
    fn buffer(&mut self, encode: bool) -> Result<Vec<u8>> {
        let mut bytes = ptr::null_mut();
        let mut len = 0;
        let code = unsafe {
            if encode {
                rp_encode(self.handle.as_ptr(), &mut bytes, &mut len)
            } else {
                rp_text(self.handle.as_ptr(), &mut bytes, &mut len)
            }
        };
        check(code, if encode { "encode snapshot" } else { "format" })?;
        let result = if len == 0 {
            Vec::new()
        } else {
            unsafe { std::slice::from_raw_parts(bytes, len) }.to_vec()
        };
        unsafe {
            rp_bytes_free(bytes, len);
        }
        Ok(result)
    }
    pub fn text(&mut self) -> Result<String> {
        Ok(String::from_utf8(self.buffer(false)?)?)
    }
    pub fn encode(&mut self) -> Result<Vec<u8>> {
        self.buffer(true)
    }
    pub fn input_flags(&mut self) -> Result<u8> {
        let mut flags=0;check(unsafe {rp_input_flags(self.handle.as_ptr(),&mut flags)},"input modes")?;Ok(flags)
    }
    pub fn wheel(&mut self,down: bool,col: i32,row: i32,cw: u32,ch: u32) -> Result<Vec<u8>> {
        let mut bytes=[0u8;64];let mut len=0;
        check(unsafe {rp_wheel(self.handle.as_ptr(),i32::from(down),col,row,cw,ch,bytes.as_mut_ptr(),bytes.len(),&mut len)},"wheel encoding")?;
        ensure!(len<=bytes.len(),"mouse encoder exceeded buffer");Ok(bytes[..len].to_vec())
    }
    /// Encode a whole tap atomically using the application's negotiated mouse mode.
    pub fn click(&mut self,col: i32,row: i32,cw: u32,ch: u32) -> Result<Vec<u8>> {
        if self.input_flags()?&1==0 {return Ok(Vec::new());}
        let mut bytes=[0u8;128];let mut len=0;
        check(unsafe {rp_click(self.handle.as_ptr(),col,row,cw,ch,bytes.as_mut_ptr(),bytes.len(),&mut len)},"click encoding")?;
        ensure!(len<=bytes.len(),"mouse encoder exceeded buffer");Ok(bytes[..len].to_vec())
    }
    pub fn scroll_viewport(&mut self, lines: i32) {
        unsafe { rp_scroll(self.handle.as_ptr(), lines, 0) };
    }
    pub fn command_text(&mut self,col:u16,row:u16,output:bool)->Result<Option<String>> {
        let mut bytes=ptr::null_mut();let mut len=0;
        let code=unsafe {rp_command_text(self.handle.as_ptr(),col,row,output as i32,&mut bytes,&mut len)};
        // GHOSTTY_NO_VALUE means this location has no matching command/output.
        if code== -4 {return Ok(None);}
        check(code,"select semantic command")?;
        let data=if len==0 {Vec::new()} else {unsafe {std::slice::from_raw_parts(bytes,len)}.to_vec()};
        unsafe {rp_bytes_free(bytes,len)};
        let text=String::from_utf8(data)?;
        ensure!(text.len()<=262144,"Command is too large to copy (256 KiB limit)");
        Ok(Some(text))
    }
    pub fn scroll_prompts(&mut self,prompts: i32) {
        unsafe {rp_scroll_prompts(self.handle.as_ptr(),prompts)};
    }
    pub fn scroll_to_bottom(&mut self) {
        unsafe { rp_scroll(self.handle.as_ptr(), 0, 1) };
    }
    /// Keep the previous complete screen during DEC synchronized output.
    /// A missing reset must not freeze the display indefinitely.
    pub fn render_ready(&mut self) -> Result<bool> {
        self.render_ready_at(self.graphics_clock.elapsed().as_millis() as u64)
    }
    fn render_ready_at(&mut self,now:u64) -> Result<bool> {
        let mut held=0;let mut generation=0;
        check(unsafe {rp_render_hold_state(self.handle.as_ptr(),&mut held,&mut generation)},"render hold")?;
        if held==0 {self.render_hold=None;return Ok(true);}
        let start=match self.render_hold {Some((epoch,start)) if epoch==generation=>start,_=>{self.render_hold=Some((generation,now));now}};
        Ok(now.saturating_sub(start)>=1000)
    }
    pub fn frame(&mut self) -> Result<Frame> {
        self.frame_at(self.graphics_clock.elapsed().as_millis() as u64)
    }
    pub(crate) fn frame_at(&mut self,now:u64) -> Result<Frame> {
        let next_image_delay_ms=crate::graphics::animation_delay(self.handle.as_ptr(),now)?;
        unsafe extern "C" fn cell(
            userdata: *mut c_void,
            x: u16,
            y: u16,
            points: *const u32,
            len: usize,
            fg: *const u8,
            bg: *const u8,
            attributes: u32,
            width: u8,
            uri: *const u8,
            uri_len: usize,
        ) {
            let points = if len == 0 {
                &[]
            } else {
                unsafe { std::slice::from_raw_parts(points, len) }
            };
            let points=if points.first()==Some(&0x10eeee) {&[][..]} else {points};
            let text = points
                .iter()
                .map(|p| char::from_u32(*p).unwrap_or('\u{fffd}'))
                .collect();
            let mut foreground = [0; 3];
            let mut background = [0; 3];
            unsafe {
                foreground.copy_from_slice(std::slice::from_raw_parts(fg, 3));
                background.copy_from_slice(std::slice::from_raw_parts(bg, 3));
                (&mut *userdata.cast::<Vec<Cell>>()).push(Cell {
                    x,
                    y,
                    text,
                    foreground,
                    background,
                    attributes,
                    width,
                    hyperlink: if uri_len==0 {None} else {Some(String::from_utf8_lossy(std::slice::from_raw_parts(uri,uri_len)).into_owned())},
                });
            }
        }
        let mut raw = RawFrame::default();
        let mut cells = Vec::<Cell>::new();
        check(
            unsafe {
                rp_frame(
                    self.handle.as_ptr(),
                    &mut raw,
                    cell,
                    (&mut cells as *mut Vec<Cell>).cast(),
                )
            },
            "render state",
        )?;
        let (image_cell_width,image_cell_height,images)=self.images.frame(self.handle.as_ptr())?;
        let next_image_delay_ms=next_image_delay_ms.filter(|_|!images.is_empty());
        Ok(Frame {
            columns: raw.columns,
            rows: raw.rows,
            cursor: (raw.cursor_visible != 0).then_some((raw.cursor_x, raw.cursor_y)),
            foreground: raw.foreground,
            background: raw.background,
            scroll_total: raw.scroll_total,
            scroll_offset: raw.scroll_offset,
            scroll_visible: raw.scroll_visible,
            input_flags: raw.input_flags,
            cells,image_cell_width,image_cell_height,images,next_image_delay_ms,
        })
    }
    fn theme(&mut self, event: &Value) -> Result<()> {
        for (which, name) in ["foreground", "background", "cursor"].iter().enumerate() {
            let rgb = event
                .get(*name)
                .filter(|v| !v.is_null())
                .map(|v| color(v.as_str().context("theme color must be text")?))
                .transpose()?;
            check(
                unsafe {
                    rp_color(
                        self.handle.as_ptr(),
                        which as i32,
                        rgb.as_ref().map_or(ptr::null(), |v| v.as_ptr()),
                    )
                },
                "set theme color",
            )?;
        }
        check(
            unsafe { rp_color(self.handle.as_ptr(), 3, ptr::null()) },
            "reset palette",
        )?;
        if let Some(entries) = event.get("palette").filter(|v| !v.is_null()) {
            for entry in entries.as_array().context("palette must be an array")? {
                let (index, rgb) = entry
                    .as_str()
                    .context("palette entry must be text")?
                    .split_once('=')
                    .context("palette entry needs index=color")?;
                let index = index.parse::<u8>()?;
                let rgb = color(rgb)?;
                check(
                    unsafe { rp_palette(self.handle.as_ptr(), index, rgb.as_ptr()) },
                    "set palette color",
                )?;
            }
        }
        Ok(())
    }
}
fn color(s: &str) -> Result<[u8; 3]> {
    let s = s.strip_prefix('#').unwrap_or(s);
    ensure!(s.len() == 6 && s.is_ascii(), "invalid RGB color");
    Ok([
        u8::from_str_radix(&s[0..2], 16)?,
        u8::from_str_radix(&s[2..4], 16)?,
        u8::from_str_radix(&s[4..6], 16)?,
    ])
}

pub struct Replica {
    nonce: u64,
    snapshot: SnapshotBuffer,
    terminal: Option<Terminal>,
    pub events: Vec<Value>,
    pub snapshot_bytes: usize,
    pub clipboard_write: Option<String>,
}
impl Replica {
    pub fn new(nonce: u64) -> Self {
        Self {
            nonce,
            snapshot: SnapshotBuffer::default(),
            terminal: None,
            events: Vec::new(),
            snapshot_bytes: 0,
            clipboard_write: None,
        }
    }
    pub fn ready(&self) -> bool {
        self.terminal.is_some()
    }
    pub fn terminal(&mut self) -> Result<&mut Terminal> {
        self.terminal.as_mut().context("snapshot is incomplete")
    }
    pub fn feed(&mut self, bytes: &[u8]) -> Result<()> {
        if self.terminal.is_none() {
            let Some((snapshot, tail)) = self.snapshot.push(bytes)? else {
                return Ok(());
            };
            let (terminal, consumed) = Terminal::decode(&snapshot)?;
            ensure!(
                consumed == snapshot.len(),
                "snapshot decoder boundary mismatch"
            );
            self.snapshot_bytes = consumed;
            self.terminal = Some(terminal);
            return self.live(&tail);
        }
        self.live(bytes)
    }
    fn live(&mut self, mut bytes: &[u8]) -> Result<()> {
        while !bytes.is_empty() {
            let (n, apcs) = self.terminal()?.step(bytes)?;
            bytes = &bytes[n..];
            for (kind,apc) in apcs {
                if kind==1 {
                    if let Ok(text)=String::from_utf8(apc) {self.clipboard_write=Some(text);}
                    continue;
                }
                let Ok(apc) = std::str::from_utf8(&apc) else {
                    continue;
                };
                let Some(json) = apc.strip_prefix(&format!("rex1;{};", self.nonce)) else {
                    continue;
                };
                let event: Value =
                    serde_json::from_str(json).context("invalid nonce-matching Rex APC")?;
                match event["type"].as_str() {
                    Some("resize") => {
                        let cols =
                            u16::try_from(event["cols"].as_u64().context("resize cols missing")?)?;
                        let rows =
                            u16::try_from(event["rows"].as_u64().context("resize rows missing")?)?;
                        let cw = u32::try_from(event["cell_width_px"].as_u64().unwrap_or(0))?;
                        let ch = u32::try_from(event["cell_height_px"].as_u64().unwrap_or(0))?;
                        self.terminal()?.resize(cols, rows, cw, ch)?;
                    }
                    Some("theme") => self.terminal()?.theme(&event)?,
                    _ => (),
                }
                ensure!(
                    self.events.len() < 4096,
                    "too many undrained Rex APC events"
                );
                self.events.push(event);
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn osc52_delivers_decoded_text_only_from_live_writes() {
        let mut terminal=Terminal::new(30,6).unwrap();
        terminal.write(b"\x1b]52;c;b2xk\x07").unwrap();
        let mut replica=Replica::new(42);replica.feed(&terminal.encode().unwrap()).unwrap();
        assert!(replica.clipboard_write.is_none(),"snapshot must not repeat clipboard side effects");
        for byte in b"\x1b]52;c;aGVsbG8K8J+mgA==\x1b\\" {replica.feed(&[*byte]).unwrap();}
        assert_eq!(replica.clipboard_write.take().as_deref(),Some("hello\n🦀"));
        replica.feed(b"\x1b]52;c;?\x07").unwrap();
        assert!(replica.clipboard_write.is_none(),"read queries cannot become writes");
        replica.feed(b"\x1b]52;c;!!!!\x07\x1b]52;c;/w==\x07").unwrap();
        assert!(replica.clipboard_write.is_none(),"malformed base64 and non-UTF-8 are ignored");
        replica.feed(b"\x1b]52;p;b25l\x07\x1b]52;c;dHdv\x07").unwrap();
        assert_eq!(replica.clipboard_write.take().as_deref(),Some("two"));
        let large=format!("\x1b]52;c;{}\x07","YWFh".repeat(90000));
        replica.feed(large.as_bytes()).unwrap();assert!(replica.clipboard_write.is_none());
        replica.feed(b"\x1b]52;c;b2s=\x07").unwrap();
        assert_eq!(replica.clipboard_write.take().as_deref(),Some("ok"));
    }
    fn input_rows(frame: &Frame) -> Vec<u16> {
        let mut rows=frame.cells.iter().filter(|c|c.attributes & (1<<30)!=0).map(|c|c.y).collect::<Vec<_>>();
        rows.sort();rows.dedup();rows
    }
    #[test] fn semantic_input_tracks_command_lifecycle_and_only_current_prompt() {
        let mut t=Terminal::new(30,8).unwrap();
        t.write(b"\x1b]133;A\x07$ ").unwrap();
        assert_eq!(t.frame().unwrap().input_flags & 16,0);
        t.write(b"\x1b]133;B\x07").unwrap();
        assert_eq!(input_rows(&t.frame().unwrap()),vec![0]);
        t.write(b"echo hi\x1b]133;C\x07").unwrap();
        assert!(input_rows(&t.frame().unwrap()).is_empty());
        t.write(b"\r\nhi\r\n\x1b]133;D;0\x07\x1b]133;A\x07$ \x1b]133;B\x07").unwrap();
        assert_eq!(input_rows(&t.frame().unwrap()),vec![2]);
        let snapshot=t.encode().unwrap();let (mut restored,_)=Terminal::decode(&snapshot).unwrap();
        assert_eq!(input_rows(&restored.frame().unwrap()),vec![2]);
        t.write(b"\x1b[?1049h").unwrap();assert!(input_rows(&t.frame().unwrap()).is_empty());
        t.write(b"\x1b[?1049l").unwrap();assert_eq!(input_rows(&t.frame().unwrap()),vec![2]);
    }
    #[test] fn semantic_input_handles_line_limited_and_wrapped_input() {
        let mut t=Terminal::new(10,8).unwrap();
        t.write(b"\x1b]133;A\x07$ \x1b]133;B\x07abcdefghijklmnop").unwrap();
        assert_eq!(input_rows(&t.frame().unwrap()),vec![0,1]);
        t.write(b"\r\n\x1b]133;P;k=c\x07> \x1b]133;B\x07more").unwrap();
        assert_eq!(input_rows(&t.frame().unwrap()),vec![0,1,2]);
        t.write(b"\x1b]133;C\x07\r\n\x1b]133;D\x07\x1b]133;A\x07$ \x1b]133;I\x07x").unwrap();
        assert_eq!(input_rows(&t.frame().unwrap()),vec![3]);
        t.write(b"\r\noutput").unwrap();assert!(input_rows(&t.frame().unwrap()).is_empty());
    }
    #[test] fn semantic_copy_is_available_after_restoring_a_running_command() {
        let mut t=Terminal::new(30,4).unwrap();
        t.write(b"\x1b]133;A\x07$ \x1b]133;B\x07long-task\x1b]133;C\x07\r\none\r\ntwo\r\nthree\r\nfour\r\nfive").unwrap();
        let (mut restored,_)=Terminal::decode(&t.encode().unwrap()).unwrap();
        assert_eq!(restored.frame().unwrap().input_flags & 32,32);
        assert_eq!(restored.command_text(0,1,false).unwrap().as_deref(),Some("long-task"));
    }
    #[test] fn semantic_copy_uses_command_boundaries_and_unwraps_long_commands() {
        let mut t=Terminal::new(20,5).unwrap();
        t.write(b"\x1b]133;A\x07project $ \x1b]133;B\x07echo abcdefghijklmnopqrstuvwxyz\x1b]133;C\x07\r\nfirst\r\nsecond\r\nthird\r\nfourth\r\nfifth\r\n\x1b]133;D;0\x07\x1b]133;A\x07$ \x1b]133;B\x07").unwrap();
        t.scroll_viewport(-1000);
        assert_eq!(t.command_text(10,0,false).unwrap().as_deref(),Some("echo abcdefghijklmnopqrstuvwxyz"));
        assert_eq!(t.command_text(10,0,true).unwrap().as_deref(),Some("first\nsecond\nthird\nfourth\nfifth"));
        assert_eq!(t.command_text(0,2,false).unwrap().as_deref(),Some("echo abcdefghijklmnopqrstuvwxyz"));
        let (mut restored,_)=Terminal::decode(&t.encode().unwrap()).unwrap();restored.scroll_viewport(-1000);
        assert_eq!(restored.command_text(0,2,true).unwrap().as_deref(),Some("first\nsecond\nthird\nfourth\nfifth"));
    }
    #[test] fn semantic_copy_omits_multiline_prompt_text_and_rejects_unmarked_apps() {
        let mut t=Terminal::new(30,8).unwrap();
        t.write("\x1b]133;A\x07$ \x1b]133;B\x07echo 'λ\r\n\x1b]133;P;k=c\x07> \x1b]133;B\x07🦀'\x1b]133;C\x07\r\nλ\r\n🦀\r\n\x1b]133;D;0\x07\x1b]133;A\x07$ \x1b]133;B\x07".as_bytes()).unwrap();
        assert_eq!(t.command_text(0,2,false).unwrap().as_deref(),Some("echo 'λ\n🦀'"));
        assert_eq!(t.command_text(0,2,true).unwrap().as_deref(),Some("λ\n🦀"));
        t.write(b"\x1b[?1049hneovim").unwrap();assert!(t.command_text(0,0,true).unwrap().is_none());
        let mut plain=Terminal::new(20,4).unwrap();plain.write(b"$ fake command\r\nfake output").unwrap();
        assert!(plain.command_text(0,1,true).unwrap().is_none());
    }
    #[test] fn prompt_navigation_uses_retained_semantic_boundaries_after_restore() {
        let mut t=Terminal::new(30,4).unwrap();
        for command in 0..8 {
            t.write(format!("\x1b]133;A\x07$ \x1b]133;B\x07command_{command}\x1b]133;C\x07\r\noutput_{command}\r\n\x1b]133;D;0\x07").as_bytes()).unwrap();
        }
        t.write(b"\x1b]133;A\x07$ \x1b]133;B\x07").unwrap();
        let (mut restored,_)=Terminal::decode(&t.encode().unwrap()).unwrap();
        let bottom=restored.frame().unwrap().scroll_offset;
        restored.scroll_prompts(-1);let previous=restored.frame().unwrap();
        assert!(previous.scroll_offset<bottom);assert!(previous.text().starts_with("$ command_"));
        restored.scroll_prompts(-1);let earlier=restored.frame().unwrap();assert!(earlier.scroll_offset<previous.scroll_offset);
        restored.scroll_prompts(1);assert_eq!(restored.frame().unwrap().scroll_offset,previous.scroll_offset);
        restored.write(b"\x1b[?1049hfullscreen").unwrap();restored.scroll_prompts(-1);
        assert_eq!(restored.frame().unwrap().scroll_offset,0);
        let mut plain=Terminal::new(20,4).unwrap();plain.write(b"a\r\nb\r\nc\r\nd\r\ne").unwrap();
        let before=plain.frame().unwrap().scroll_offset;plain.scroll_prompts(-1);assert_eq!(plain.frame().unwrap().scroll_offset,before);
    }
    #[test] fn semantic_input_remains_tappable_when_the_program_hides_the_cursor() {
        let mut t=Terminal::new(20,6).unwrap();
        t.write(b"\x1b]133;A\x07$ \x1b]133;B\x07hello\x1b[?25l").unwrap();
        let frame=t.frame().unwrap();assert!(frame.cursor.is_none());
        assert_eq!(input_rows(&frame),vec![0]);
    }
    #[test] fn semantic_input_survives_prompt_redraws_and_reflow() {
        let mut t=Terminal::new(20,8).unwrap();
        t.write(b"\x1b]133;A\x07$ \x1b]133;B\x07abcdefghijklmnop").unwrap();
        t.resize(10,8,8,16).unwrap();assert_eq!(input_rows(&t.frame().unwrap()),vec![0,1]);
        t.write(b"\r\x1b[1A\x1b]133;P;k=i\x07$ \x1b]133;B\x07redrawn\x1b[K").unwrap();
        assert!(t.frame().unwrap().input_flags & 16!=0);
        t.write(b"\x1b]133;C\x07").unwrap();assert!(input_rows(&t.frame().unwrap()).is_empty());
    }
    #[test] fn semantic_input_is_not_exposed_over_history() {
        let mut t=Terminal::new(20,4).unwrap();
        t.write(b"one\r\ntwo\r\nthree\r\nfour\r\nfive\r\n\x1b]133;A\x07$ \x1b]133;B\x07").unwrap();
        assert!(!input_rows(&t.frame().unwrap()).is_empty());
        t.scroll_viewport(-100);assert!(input_rows(&t.frame().unwrap()).is_empty());
        t.scroll_to_bottom();assert!(!input_rows(&t.frame().unwrap()).is_empty());
    }
    #[test]
    fn restored_scrollback_scrolls_locally_and_keeps_new_output_anchored() {
        let mut original = Terminal::new(30, 4).unwrap();
        for i in 0..40 {
            original
                .write(format!("HISTORY_{i:03}\r\n").as_bytes())
                .unwrap();
        }
        let snapshot = original.encode().unwrap();
        let (mut terminal, _) = Terminal::decode(&snapshot).unwrap();
        let bottom = terminal.frame().unwrap();
        assert!(bottom.scroll_total > bottom.scroll_visible);
        assert!(bottom.text().contains("HISTORY_039"));
        terminal.scroll_viewport(-10000);
        let top = terminal.frame().unwrap();
        assert_eq!(top.scroll_offset, 0);
        assert!(top.text().contains("HISTORY_000"));
        terminal.write(b"NEW_WHILE_SCROLLED\r\n").unwrap();
        assert!(terminal.frame().unwrap().text().contains("HISTORY_000"));
        terminal.scroll_to_bottom();
        assert!(
            terminal
                .frame()
                .unwrap()
                .text()
                .contains("NEW_WHILE_SCROLLED")
        );
        terminal.write(b"\x1b[?1049hALTERNATE").unwrap();
        terminal.scroll_viewport(-1000);
        let alternate = terminal.frame().unwrap();
        assert_eq!(alternate.scroll_total, alternate.scroll_visible);
        assert!(alternate.text().contains("ALTERNATE"));
    }
    #[test]
    fn restores_binary_snapshot_then_handles_fragmented_vt_and_nonce_apcs() {
        let mut terminal = Terminal::new(40, 6).unwrap();
        terminal
            .write("before \x1b[1;38;2;12;200;90mgreen\x1b[0m λ🦀\r\n".as_bytes())
            .unwrap();
        let snapshot = terminal.encode().unwrap();
        let mut replica = Replica::new(9876543210123456789);
        for byte in &snapshot {
            replica.feed(&[*byte]).unwrap();
        }
        assert_eq!(
            replica.terminal().unwrap().text().unwrap(),
            terminal.text().unwrap()
        );
        let frame = replica.terminal().unwrap().frame().unwrap();
        let g = frame.cells.iter().find(|c| c.text == "g").unwrap();
        assert_eq!(g.foreground, [12, 200, 90]);
        assert_ne!(g.attributes & 1, 0);
        let live = "\x1b_rex1;1;{\"type\":\"resize\",\"cols\":1,\"rows\":1}\x1b\\\x1b_rex1;9876543210123456789;{\"type\":\"resize\",\"cols\":55,\"rows\":9}\x1b\\after 🦀";
        for b in live.as_bytes() {
            replica.feed(&[*b]).unwrap();
        }
        let frame = replica.terminal().unwrap().frame().unwrap();
        assert_eq!((frame.columns, frame.rows), (55, 9));
        assert!(frame.text().contains("after 🦀"));
        assert_eq!(replica.events.len(), 1);
        let theme = "\x1b_rex1;9876543210123456789;{\"type\":\"theme\",\"background\":\"#112233\",\"palette\":[\"2=abcdef\"]}\x1b\\\x1b[32mT";
        replica.feed(theme.as_bytes()).unwrap();
        let frame = replica.terminal().unwrap().frame().unwrap();
        assert_eq!(frame.background, [17, 34, 51]);
        assert_eq!(
            frame
                .cells
                .iter()
                .find(|c| c.text == "T")
                .unwrap()
                .foreground,
            [171, 205, 239]
        );
    }
    #[test]
    fn native_decoder_rejects_corruption_and_truncation() {
        let mut terminal = Terminal::new(20, 4).unwrap();
        terminal.write(b"test").unwrap();
        let mut bytes = terminal.encode().unwrap();
        assert!(Terminal::decode(&bytes[..bytes.len() - 1]).is_err());
        bytes[25] ^= 0x40;
        assert!(Terminal::decode(&bytes).is_err());
    }

    #[test]
    fn snapshot_restores_incomplete_utf8_and_csi_and_alternate_screen() {
        for (prefix, suffix) in [
            (b"\xf0\x9f".as_slice(), b"\xa6\x80".as_slice()),
            (b"\x1b[38;2;12;200;90", b"mX"),
        ] {
            let mut original = Terminal::new(20, 4).unwrap();
            original.write(prefix).unwrap();
            let bytes = original.encode().unwrap();
            let (mut decoded, _) = Terminal::decode(&bytes).unwrap();
            decoded.write(suffix).unwrap();
            original.write(suffix).unwrap();
            assert_eq!(decoded.text().unwrap(), original.text().unwrap());
        }
        let mut original = Terminal::new(20, 4).unwrap();
        original
            .write(b"PRIMARY\x1b[?1049h\x1b[HALTERNATE")
            .unwrap();
        let (mut decoded, _) = Terminal::decode(&original.encode().unwrap()).unwrap();
        assert!(decoded.frame().unwrap().text().contains("ALTERNATE"));
        decoded.write(b"\x1b[?1049l").unwrap();
        assert!(decoded.frame().unwrap().text().contains("PRIMARY"));
    }
    #[test]
    fn frame_retains_color_roles_for_client_side_appearance() {
        let mut terminal = Terminal::new(20, 3).unwrap();
        terminal
            .write(b"A\x1b[31mB\x1b[38;2;12;34;56mC\x1b[0m\x1b[42m ")
            .unwrap();
        let frame = terminal.frame().unwrap();
        let a = &frame.cells[0];
        let b = &frame.cells[1];
        let c = &frame.cells[2];
        let bg = &frame.cells[3];
        assert_ne!(a.attributes & 128, 0);
        assert_ne!(a.attributes & 256, 0);
        assert_ne!(b.attributes & 512, 0);
        assert_eq!((b.attributes >> 11) & 255, 1);
        assert_eq!(c.attributes & (128 | 512), 0);
        assert_eq!(c.foreground, [12, 34, 56]);
        assert_ne!(bg.attributes & 1024, 0);
        assert_eq!((bg.attributes >> 19) & 255, 2);
    }
    #[test] fn synchronized_redraw_holds_partial_frames_and_has_a_bounded_timeout() {
        let mut terminal=Terminal::new(40,5).unwrap();
        terminal.write(b"complete screen").unwrap();
        assert!(terminal.render_ready_at(0).unwrap());
        terminal.write(b"\x1b[?2026h\x1b[2J\x1b[H").unwrap();
        assert!(!terminal.render_ready_at(10).unwrap());
        terminal.write(b"new screen").unwrap();
        assert!(!terminal.render_ready_at(500).unwrap());
        terminal.write(b"\x1b[?2026l").unwrap();
        assert!(terminal.render_ready_at(510).unwrap());
        assert!(terminal.frame().unwrap().text().contains("new screen"));
        terminal.write(b"\x1b[?2026h").unwrap();
        assert!(!terminal.render_ready_at(600).unwrap());
        assert!(terminal.render_ready_at(1600).unwrap());
        assert!(terminal.render_ready_at(1700).unwrap());
        // A new hold must reset the timer even if its end/start share one chunk.
        terminal.write(b"\x1b[?2026l\x1b[?2026h").unwrap();
        assert!(!terminal.render_ready_at(1800).unwrap());
        terminal.resize(40,6,8,16).unwrap();
        assert!(terminal.render_ready_at(1810).unwrap());
    }

    #[test] fn clicks_obey_mouse_modes_and_pair_press_with_release() {
        let mut terminal=Terminal::new(80,24).unwrap();
        assert!(terminal.click(4,5,10,20).unwrap().is_empty());
        terminal.write(b"\x1b[?1000h\x1b[?1006h").unwrap();
        assert_eq!(terminal.click(4,5,10,20).unwrap(),b"\x1b[<0;5;6M\x1b[<0;5;6m");
        assert_eq!(terminal.click(-20,999,0,0).unwrap(),b"\x1b[<0;1;24M\x1b[<0;1;24m");
        let snapshot=terminal.encode().unwrap();let (mut restored,_)=Terminal::decode(&snapshot).unwrap();
        assert_eq!(restored.click(79,23,10,20).unwrap(),b"\x1b[<0;80;24M\x1b[<0;80;24m");
        restored.write(b"\x1b[?1006l").unwrap();
        assert_eq!(restored.click(4,5,10,20).unwrap(),b"\x1b[M %&\x1b[M#%&");
        restored.write(b"\x1b[?1000l").unwrap();assert!(restored.click(4,5,10,20).unwrap().is_empty());
    }

    #[test] fn mouse_modes_survive_snapshots_and_encode_wheel_direction() {
        let mut terminal=Terminal::new(80,24).unwrap();
        terminal.write(b"\x1b[?1049h\x1b[?1007l\x1b[?1000h\x1b[?1006h").unwrap();
        assert_eq!(terminal.input_flags().unwrap()&7,3);
        assert_eq!(terminal.wheel(false,4,5,10,20).unwrap(),b"\x1b[<64;5;6M");
        assert_eq!(terminal.wheel(true,4,5,10,20).unwrap(),b"\x1b[<65;5;6M");
        let snapshot=terminal.encode().unwrap();let (mut restored,_)=Terminal::decode(&snapshot).unwrap();
        assert_eq!(restored.frame().unwrap().input_flags&7,3);
        restored.write(b"\x1b[?1000l\x1b[?1007h").unwrap();assert_eq!(restored.input_flags().unwrap()&7,6);
    }

    #[test]
    fn osc8_destinations_survive_wrapping_snapshot_and_metadata_only_replacement() {
        let mut terminal=Terminal::new(8,5).unwrap();
        terminal.write(b"\x1b]8;id=docs;https://example.com/one\x1b\\Open docs now\x1b]8;;\x1b\\!").unwrap();
        let first=terminal.frame().unwrap();
        let linked: Vec<_>=first.cells.iter().filter(|c|c.hyperlink.is_some()).collect();
        assert!(linked.len()>=12);assert!(linked.iter().any(|c|c.y>0));
        assert!(linked.iter().all(|c|c.hyperlink.as_deref()==Some("https://example.com/one")));
        assert!(first.cells.iter().any(|c|c.text=="!" && c.hyperlink.is_none()));
        let snapshot=terminal.encode().unwrap();let (mut restored,_)=Terminal::decode(&snapshot).unwrap();
        assert_eq!(first.cells,restored.frame().unwrap().cells);
        restored.write(b"\x1b[H\x1b]8;;https://example.com/two\x07Open\x1b]8;;\x07").unwrap();
        let next=restored.frame().unwrap();
        assert_eq!(next.cells[0].text,first.cells[0].text);
        assert_eq!(next.cells[0].hyperlink.as_deref(),Some("https://example.com/two"));
        assert_ne!(next.cells[0],first.cells[0]);
    }

    #[test]
    fn soft_wrap_flags_survive_snapshots_and_distinguish_hard_newlines() {
        let mut terminal=Terminal::new(10,5).unwrap();
        terminal.write(b"https://example.com/abc\r\nNEXT").unwrap();
        let frame=terminal.frame().unwrap();
        let flags=|row:u16|frame.cells.iter().find(|c|c.x==0 && c.y==row).unwrap().attributes;
        assert_ne!(flags(0)&(1<<29),0);assert_ne!(flags(0)&(1<<27),0);
        assert_ne!(flags(1)&(1<<28),0);assert_ne!(flags(1)&(1<<27),0);
        assert_ne!(flags(2)&(1<<28),0);assert_eq!(flags(2)&(1<<27),0);
        assert_eq!(flags(3)&(1<<28),0);
        let (mut restored,_)=Terminal::decode(&terminal.encode().unwrap()).unwrap();
        assert_eq!(frame.cells,restored.frame().unwrap().cells);
    }

}
