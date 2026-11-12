//! Owned pixels from libghostty, shared by frames instead of copied on each redraw.
use anyhow::Result;
use std::{
    collections::HashMap,
    ffi::c_void,
    sync::{Arc, OnceLock},
};
#[derive(Debug)]
pub struct Image {
    pub id: u32,
    pub generation: u64,
    pub width: u32,
    pub height: u32,
    pub rgba: Arc<[u8]>,
}
#[derive(Clone, Debug)]
pub struct Placement {
    pub image: Arc<Image>,
    pub layer: u32,
    pub z: i32,
    pub col: i32,
    pub row: i32,
    pub ox: u32,
    pub oy: u32,
    pub width: u32,
    pub height: u32,
    pub sx: u32,
    pub sy: u32,
    pub sw: u32,
    pub sh: u32,
}
#[repr(C)]
struct RawImage {
    generation: u64,
    id: u32,
    width: u32,
    height: u32,
    format: u32,
    layer: u32,
    z: i32,
    col: i32,
    row: i32,
    ox: u32,
    oy: u32,
    dw: u32,
    dh: u32,
    sx: u32,
    sy: u32,
    sw: u32,
    sh: u32,
}
#[repr(C)]
struct Decoded {
    width: u32,
    height: u32,
    data: *mut u8,
    len: usize,
}
type Decoder =
    unsafe extern "C" fn(*mut c_void, *const c_void, *const u8, usize, *mut Decoded) -> bool;
unsafe extern "C" {
    fn rp_animation_tick(terminal: *mut c_void, now: u64, next: *mut u64, playing: *mut u8) -> i32;
    fn rp_graphics_init(decoder: Decoder) -> i32;
    fn rp_graphics_alloc(allocator: *const c_void, len: usize) -> *mut u8;
    fn rp_images(
        terminal: *mut c_void,
        cw: *mut u32,
        ch: *mut u32,
        callback: unsafe extern "C" fn(*mut c_void, *const RawImage, *const u8, usize),
        userdata: *mut c_void,
    ) -> i32;
}
const MAX_BYTES: usize = 32 * 1024 * 1024;
fn decode_png(bytes: &[u8]) -> Option<(u32, u32, Vec<u8>)> {
    let mut decoder = png::Decoder::new_with_limits(
        std::io::Cursor::new(bytes),
        png::Limits {
            bytes: 16 * 1024 * 1024,
        },
    );
    decoder.set_transformations(png::Transformations::EXPAND | png::Transformations::STRIP_16);
    let mut reader = decoder.read_info().ok()?;
    let info = reader.info();
    let (w, h) = (info.width, info.height);
    let output = (w as usize).checked_mul(h as usize)?.checked_mul(4)?;
    if w == 0 || h == 0 || w > 8192 || h > 8192 || output > 16 * 1024 * 1024 {
        return None;
    }
    let size = reader.output_buffer_size()?;
    if size > 16 * 1024 * 1024 {
        return None;
    }
    let mut pixels = vec![0; size];
    let info = reader.next_frame(&mut pixels).ok()?;
    pixels.truncate(info.buffer_size());
    let rgba = rgba(
        &pixels,
        match info.color_type {
            png::ColorType::Rgba => 1,
            png::ColorType::Rgb => 0,
            png::ColorType::GrayscaleAlpha => 3,
            png::ColorType::Grayscale => 4,
            _ => return None,
        },
        w,
        h,
    )?;
    Some((w, h, rgba))
}
fn rgba(bytes: &[u8], format: u32, width: u32, height: u32) -> Option<Vec<u8>> {
    let count = (width as usize).checked_mul(height as usize)?;
    let size = count.checked_mul(4)?;
    if width == 0 || height == 0 || width > 8192 || height > 8192 || size > MAX_BYTES {
        return None;
    }
    let channels = match format {
        0 => 3,
        1 => 4,
        3 => 2,
        4 => 1,
        _ => return None,
    };
    if bytes.len() != count.checked_mul(channels)? {
        return None;
    }
    if format == 1 {
        return Some(bytes.to_vec());
    }
    let mut out = Vec::with_capacity(size);
    for p in bytes.chunks_exact(channels) {
        match format {
            0 => out.extend_from_slice(&[p[0], p[1], p[2], 255]),
            1 => out.extend_from_slice(p),
            3 => out.extend_from_slice(&[p[0], p[0], p[0], p[1]]),
            4 => out.extend_from_slice(&[p[0], p[0], p[0], 255]),
            _ => unreachable!(),
        }
    }
    Some(out)
}
pub(crate) fn init() -> Result<()> {
    unsafe extern "C" fn decode(
        _: *mut c_void,
        allocator: *const c_void,
        data: *const u8,
        len: usize,
        out: *mut Decoded,
    ) -> bool {
        std::panic::catch_unwind(|| {
            if data.is_null() || out.is_null() || len > 16 * 1024 * 1024 {
                return false;
            }
            let Some((width, height, pixels)) =
                decode_png(unsafe { std::slice::from_raw_parts(data, len) })
            else {
                return false;
            };
            let buffer = unsafe { rp_graphics_alloc(allocator, pixels.len()) };
            if buffer.is_null() {
                return false;
            }
            unsafe {
                std::ptr::copy_nonoverlapping(pixels.as_ptr(), buffer, pixels.len());
                *out = Decoded {
                    width,
                    height,
                    data: buffer,
                    len: pixels.len(),
                };
            }
            true
        })
        .unwrap_or(false)
    }
    static RESULT: OnceLock<i32> = OnceLock::new();
    super::ghostty::check(
        *RESULT.get_or_init(|| unsafe { rp_graphics_init(decode) }),
        "initialize PNG decoder",
    )
}
#[derive(Default)]
pub(crate) struct Images {
    cache: HashMap<(u32, u64), Arc<Image>>,
    placements: Vec<Placement>,
}
impl Images {
    pub fn frame(&mut self, terminal: *mut c_void) -> Result<(u32, u32, Vec<Placement>)> {
        self.placements.clear();
        let mut cw = 0;
        let mut ch = 0;
        unsafe extern "C" fn collect(
            user: *mut c_void,
            raw: *const RawImage,
            data: *const u8,
            len: usize,
        ) {
            let this = unsafe { &mut *user.cast::<Images>() };
            let r = unsafe { &*raw };
            if r.layer > 2
                || r.dw == 0
                || r.dh == 0
                || r.sw == 0
                || r.sh == 0
                || [r.ox, r.oy, r.dw, r.dh]
                    .iter()
                    .any(|v| *v > i32::MAX as u32)
                || u64::from(r.sx) + u64::from(r.sw) > u64::from(r.width)
                || u64::from(r.sy) + u64::from(r.sh) > u64::from(r.height)
            {
                return;
            }
            let key = (r.id, r.generation);
            if !this.cache.contains_key(&key) {
                let Some(rgba) = rgba(
                    unsafe { std::slice::from_raw_parts(data, len) },
                    r.format,
                    r.width,
                    r.height,
                ) else {
                    return;
                };
                this.cache.insert(
                    key,
                    Arc::new(Image {
                        id: r.id,
                        generation: r.generation,
                        width: r.width,
                        height: r.height,
                        rgba: rgba.into(),
                    }),
                );
            }
            this.placements.push(Placement {
                image: this.cache[&key].clone(),
                layer: r.layer,
                z: r.z,
                col: r.col,
                row: r.row,
                ox: r.ox,
                oy: r.oy,
                width: r.dw,
                height: r.dh,
                sx: r.sx,
                sy: r.sy,
                sw: r.sw,
                sh: r.sh,
            });
        }
        super::ghostty::check(
            unsafe {
                rp_images(
                    terminal,
                    &mut cw,
                    &mut ch,
                    collect,
                    (self as *mut Self).cast(),
                )
            },
            "image render state",
        )?;
        let visible: std::collections::HashSet<_> = self
            .placements
            .iter()
            .map(|p| (p.image.id, p.image.generation))
            .collect();
        self.cache.retain(|key, _| visible.contains(key));
        self.placements.sort_by_key(|p| (p.layer, p.z));
        Ok((cw.max(1), ch.max(1), self.placements.clone()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ghostty::Terminal;
    use base64::Engine;
    fn image(terminal: &mut Terminal, id: u32, pixels: &[u8], png: bool) {
        let payload = base64::engine::general_purpose::STANDARD.encode(pixels);
        terminal
            .write(
                format!(
                    "\x1b_Ga=T,f={},s=2,v=1,i={id},c=2,r=1;{payload}\x1b\\",
                    if png { 100 } else { 32 }
                )
                .as_bytes(),
            )
            .unwrap();
    }
    #[test]
    fn inline_rgba_placements_share_pixels_and_survive_snapshots() {
        let mut terminal = Terminal::new(10, 5).unwrap();
        terminal.resize(10, 5, 8, 16).unwrap();
        let pixels = [255, 0, 0, 255, 0, 255, 0, 128];
        image(&mut terminal, 7, &pixels, false);
        let first = terminal.frame().unwrap();
        assert_eq!(first.images.len(), 1);
        assert_eq!(&*first.images[0].image.rgba, &pixels);
        assert_eq!((first.image_cell_width, first.image_cell_height), (8, 16));
        assert_eq!(first.images[0].col, 0);
        assert_eq!(first.images[0].row, 0);
        terminal.write(b"text").unwrap();
        let next = terminal.frame().unwrap();
        assert!(Arc::ptr_eq(&first.images[0].image, &next.images[0].image));
        let snapshot = terminal.encode().unwrap();
        let (mut restored, _) = Terminal::decode(&snapshot).unwrap();
        let replay = restored.frame().unwrap();
        assert_eq!(replay.images.len(), 1);
        assert_eq!(&*replay.images[0].image.rgba, &pixels);
        terminal.write(b"\x1b_Ga=d,d=I,i=7\x1b\\").unwrap();
        assert!(terminal.frame().unwrap().images.is_empty());
    }
    #[test]
    fn chunked_replacement_keeps_the_visible_image_until_complete() {
        let mut terminal = Terminal::new(10, 5).unwrap();
        let original = [255, 0, 0, 255, 0, 255, 0, 255];
        let replacement = [0, 0, 255, 255, 255, 255, 0, 255];
        image(&mut terminal, 7, &original, false);
        let before = terminal.frame().unwrap();
        let payload = base64::engine::general_purpose::STANDARD.encode(replacement);
        let (first, last) = payload.split_at(4);
        terminal
            .write(format!("\x1b_Ga=T,f=32,s=2,v=1,i=7,c=2,r=1,m=1;{first}\x1b\\").as_bytes())
            .unwrap();
        let pending = terminal.frame().unwrap();
        assert!(!pending.images.is_empty());
        assert_eq!(&*pending.images[0].image.rgba, &original);
        assert!(Arc::ptr_eq(&before.images[0].image, &pending.images[0].image));
        terminal
            .write(format!("\x1b_Gm=0;{last}\x1b\\").as_bytes())
            .unwrap();
        let complete = terminal.frame().unwrap();
        assert!(!complete.images.is_empty());
        assert_eq!(&*complete.images[0].image.rgba, &replacement);
        assert!(!Arc::ptr_eq(&before.images[0].image, &complete.images[0].image));
    }
    #[test]
    fn png_decoder_and_chunked_transmission_preserve_alpha() {
        let mut bytes = Vec::new();
        {
            let mut encoder = png::Encoder::new(&mut bytes, 2, 1);
            encoder.set_color(png::ColorType::Rgba);
            encoder.set_depth(png::BitDepth::Eight);
            encoder
                .write_header()
                .unwrap()
                .write_image_data(&[255, 0, 0, 255, 0, 0, 255, 64])
                .unwrap();
        }
        assert_eq!(
            decode_png(&bytes).unwrap().2,
            [255, 0, 0, 255, 0, 0, 255, 64]
        );
        let mut terminal = Terminal::new(10, 5).unwrap();
        let payload = base64::engine::general_purpose::STANDARD.encode(&bytes);
        let (a, b) = payload.split_at(16);
        for byte in
            format!("\x1b_Ga=T,f=100,i=11,m=1,c=2,r=1;{a}\x1b\\\x1b_Gm=0;{b}\x1b\\").as_bytes()
        {
            terminal.write(&[*byte]).unwrap();
        }
        let frame = terminal.frame().unwrap();
        assert_eq!(frame.images.len(), 1);
        assert_eq!(
            &*frame.images[0].image.rgba,
            &[255, 0, 0, 255, 0, 0, 255, 64]
        );
        assert!(decode_png(b"not a png").is_none());
    }
    #[test]
    fn placeholders_follow_text_and_animations_use_library_deadlines() {
        let mut terminal = Terminal::new(10, 5).unwrap();
        terminal
            .write(b"\x1b_Ga=T,U=1,f=32,i=3,s=1,v=1,c=2,r=1,q=2;AP8A/w==\x1b\\")
            .unwrap();
        terminal
            .write(
                "\r\n\x1b[38;5;3m\u{10eeee}\u{0305}\u{0305}\u{10eeee}\u{0305}\u{030d}\x1b[39m"
                    .as_bytes(),
            )
            .unwrap();
        let frame = terminal.frame_at(0).unwrap();
        assert!(!frame.images.is_empty());
        assert!(frame.images.iter().all(|p| p.layer == 1 && p.row == 1));
        terminal.write(b"\x1b[2J\x1b[H").unwrap();
        assert!(terminal.frame_at(0).unwrap().images.is_empty());
        terminal.write(b"\x1b_Ga=T,f=32,i=2,s=1,v=1,q=2;/wAA/w==\x1b\\\x1b_Ga=f,f=32,i=2,s=1,v=1,z=40,q=2;AAD//w==\x1b\\\x1b_Ga=a,i=2,r=1,z=30,q=2\x1b\\\x1b_Ga=a,i=2,s=3,q=2\x1b\\").unwrap();
        let red = terminal.frame_at(0).unwrap();
        assert_eq!(red.next_image_delay_ms, Some(30));
        assert_eq!(&*red.images[0].image.rgba, &[255, 0, 0, 255]);
        let blue = terminal.frame_at(30).unwrap();
        assert_eq!(blue.next_image_delay_ms, Some(40));
        assert_eq!(&*blue.images[0].image.rgba, &[0, 0, 255, 255]);
        assert_ne!(
            red.images[0].image.generation,
            blue.images[0].image.generation
        );
        terminal.write(b"\x1b_Ga=a,i=2,s=1,q=2\x1b\\").unwrap();
        assert_eq!(terminal.frame_at(70).unwrap().next_image_delay_ms, None);
    }
    #[test]
    fn image_identity_and_geometry_survive_screen_switches_resizes_and_scrollback() {
        let mut terminal = Terminal::new(10, 5).unwrap();
        image(&mut terminal, 7, &[255; 8], false);
        let first = terminal.frame().unwrap();
        terminal.resize(10, 5, 16, 32).unwrap();
        let resized = terminal.frame().unwrap();
        assert_eq!(resized.image_cell_width, 16);
        assert_eq!(resized.image_cell_height, 32);
        assert!(Arc::ptr_eq(
            &first.images[0].image,
            &resized.images[0].image
        ));
        terminal.write(b"\x1b[?1049h").unwrap();
        image(&mut terminal, 7, &[0; 8], false);
        let alternate = terminal.frame().unwrap();
        assert_eq!(&*alternate.images[0].image.rgba, &[0; 8]);
        terminal.write(b"\x1b[?1049l").unwrap();
        assert_eq!(&*terminal.frame().unwrap().images[0].image.rgba, &[255; 8]);
        terminal.write(b"\r\n\r\n\r\n\r\n\r\n\r\n\r\n\r\n").unwrap();
        assert!(terminal.frame().unwrap().images.is_empty());
        terminal.scroll_viewport(-100);
        assert!(!terminal.frame().unwrap().images.is_empty());
        terminal.scroll_to_bottom();
        assert!(terminal.frame().unwrap().images.is_empty());
    }
    #[test]
    fn deleting_and_replacing_the_same_id_changes_its_generation() {
        let mut terminal = Terminal::new(10, 5).unwrap();
        image(&mut terminal, 1, &[255; 8], false);
        let first = terminal.frame().unwrap();
        terminal.write(b"\x1b_Ga=d,d=I,i=1\x1b\\").unwrap();
        image(&mut terminal, 1, &[0; 8], false);
        let second = terminal.frame().unwrap();
        assert_ne!(
            first.images[0].image.generation,
            second.images[0].image.generation
        );
        assert_eq!(&*second.images[0].image.rgba, &[0; 8]);
    }
}

pub(crate) fn animation_delay(terminal: *mut c_void, now: u64) -> Result<Option<u64>> {
    let mut next = 0;
    let mut playing = 0;
    super::ghostty::check(
        unsafe { rp_animation_tick(terminal, now, &mut next, &mut playing) },
        "image animation tick",
    )?;
    Ok((playing != 0).then(|| next.saturating_sub(now).max(16)))
}
