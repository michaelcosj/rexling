# Plan: Kitty graphics protocol support

Show images sent with the kitty graphics protocol (icat, yazi previews,
image.nvim, chafa/timg `--format kitty`, etc.) inside Rexling's terminal.

Status: draft, not reviewed.

## What we already know

Verified on 2026-11-12 against the local macOS Rex server with a disposable session.

- **libghostty-vt (rev `f54340ad`) has kitty graphics compiled in.** Our built
  `libghostty-vt.a` exports `ghostty_kitty_graphics_*` and
  `ghostty_render_state_image_*`.
- **The library handles the protocol and the layout.** It stores images and
  placements per screen, and supports cell placements, Unicode placeholders,
  relative placements and animation. The render state returns an image list
  with viewport position, pixel size, source rect, layer and z-index already
  worked out. Pixels come back decoded (RGB, RGBA, gray or gray+alpha, never
  PNG).
- **The Rex server passes images through untouched.** An image printed while
  connected arrives as the raw `ESC _ G … ESC \` APC in the live stream.
- **Reconnect snapshots keep images.** Images printed before connecting were
  restored from the `GHOSTSNP` snapshot with pixels and size intact (8×8 PNG,
  4×4 RGBA).
- **Our shim forwards unhandled APCs** to Rust, which drops anything that isn't
  `rex1;<nonce>;…`. This doesn't block anything: Ghostty consumes `G` APCs
  itself before the unknown-sequence callback.
- **The library's default storage limit is 10 MB.** We never set one, so
  images already work, but we should choose the limit on purpose.
- **We need to supply a PNG decoder** (`GHOSTTY_SYS_OPT_DECODE_PNG`) for `f=100`
  images that arrive live. The snapshot test above restored PNG pixels because
  the server had already decoded them.

## Phase 0 results (2026-11-12, local macOS Rex)

A raw-mode probe inside a disposable Rex session sent the queries a tool
would and recorded Rex's replies:

| Query | Rex reply | Meaning |
|---|---|---|
| `a=q` support query + DA1 | `ESC_Gi=31;OK` before DA1 | Rex says yes, so detecting tools will send images |
| `a=t` transmit | `ESC_Gi=32;OK` | Rex acknowledges transfers |
| XTVERSION | `rex 0.0.…` | Terminal identifies as Rex |
| `CSI 16 t` cell size | `6;16;8` (16×8 px) | Rex's default cell size, see below |
| `CSI 14 t` window size | 384×640 px | 80×24 × 16×8 |
| env | `TERM=xterm-256color`, `TERM_PROGRAM=rex` | Tools that detect by name may not recognise Rex |

Findings:
- **Capability queries are fine.** Rex answers `a=q` with OK, so query-based
  tools (icat, chafa auto, timg auto, image.nvim with kitty backend) will send.
- **Name-based detection may fail.** Tools that guess from `TERM_PROGRAM`
  (yazi's adapter list, some plugins) might not know `rex`. Workaround: their
  protocol override flags or environment variables. Recheck on the phone.
- **Rexling never sends a cell pixel size.** `terminal.resize` accepts
  `cell_width_px`/`cell_height_px`, but our resize sends only
  `columns`/`rows`, so Rex reports 8×16 px cells. Tools then send images
  sized for tiny cells, which we'd upscale and blur. **Add to Phase 1:**
  send our real cell size in device pixels (rounded) with every resize, and
  use the replica's cell size from Rex's resize APC to scale placements.
- **Replies are covered.** Our shim deliberately has no `WRITE_PTY` callback,
  so the replica never answers. Open question 3 is resolved.
- **Not done yet:** a real-tool survey (no image tools installed on this Mac)
  and the Linux server repeat. Do both on the phone during Phase 3.

## Open questions (original list for Phase 0)

1. **Capability queries.** Tools send `a=q` or check terminal identity before
   sending images. Rex, not Rexling, answers queries on the server side. Does
   Rex answer `a=q` with OK? Test with `kitty +kitten icat`, `chafa -f kitty`,
   `timg -pk`, yazi and image.nvim through Rex. If Rex says no, file
   upstream/Superlogical and document a workaround such as forcing the protocol
   with a flag or environment variable.
2. **Cell pixel size.** Placements without `c`/`r` are sized in pixels relative
   to the cell size the *server* believes in. We report it via resize
   (`cell_width_px`/`cell_height_px`). Check that what we send matches what
   libghostty uses for `PIXEL_WIDTH`/`HEIGHT`, so images come out the right
   size at our font size.
3. **Responses.** Kitty replies to the PTY (`write_pty`). On the server, Rex
   answers these. Confirm that our client-side replica must *not* also send
   replies upstream (it shouldn't; we're a viewer of Rex's terminal).
4. **Linux server.** Repeat the snapshot and live test on the Linux Rex host.

## Design

```
Rex stream ─▶ libghostty-vt (replica, per pane)
                 │  render state: image placements (generation-tracked)
                 │  kitty storage: decoded pixels (per image generation)
                 ▼
             C shim  rp_images(...)  ──▶ Rust ghostty.rs  ImageFrame
                 ▼
             jni updates.rs   RXT4 packet: placements every change,
                              pixels once per (image id, image generation)
                 ▼
             Kotlin TerminalUpdates ─▶ TerminalScreen.images
                 ▼
             TerminalView.onDraw: below-text layer → text rows → above-text layer
```

### 1. Native shim (`rexling-core/native/ghostty_shim.c`)
- In `configure()`, set `GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT`, for
  example 32 MB per terminal. Also set it on the snapshot decoder (decoder
  opt `KITTY_IMAGE_STORAGE_LIMIT`) so a restore can't exceed it.
- Leave file, temp-file and shared-memory media off (defaults). They name
  paths on the server, which a remote replica can't read.
- Register a PNG decoder once per process via `ghostty_sys_set`. Options:
  - **(a)** A Rust callback using the `png` crate (pure Rust, small, builds for
    Android and host tests alike). **Preferred.**
  - (b) A JNI upcall to `BitmapFactory`. Avoid it: it calls back into the JVM
    from the writer thread.
- Add `rp_images(t, ImageFn cb, void *ud, uint64_t *placements_gen)`:
  - Call it after `ghostty_render_state_update` (inside `rp_frame` or right
    after it, while the render state is fresh).
  - Read `IMAGE_PLACEMENTS_GENERATION`. If it's unchanged from the last call,
    return early.
  - Otherwise iterate `IMAGE_ITERATOR`. For each kitty placement, report image
    id, image generation, layer, z, viewport col/row (may be negative), grid
    cols/rows, cell offset x/y, pixel w/h and source rect.
- Add `rp_image_pixels(t, id, *w, *h, *format, *ptr, *len)` to look up pixels
  by id. These are borrowed, so copy them before the next VT write.
- Call `ghostty_kitty_graphics_animation_tick` on each frame. Expose the next
  deadline so the engine can schedule a redraw when an animation is playing.

### 2. Rust core (`rexling-core/src/ghostty.rs`)
- `pub struct ImagePlacement { id, generation, layer, z, col, row, cols, rows,
  off_x, off_y, px_w, px_h, src: [u32;4] }`.
- `Frame` gains `images: Arc<[ImagePlacement]>` and `images_generation: u64`.
- `pub fn image_pixels(&mut self, id) -> Option<ImagePixels { w, h, rgba: Vec<u8> }>`
  converts RGB, gray and gray+alpha to RGBA8 so Kotlin has one format.
- Tests in `ghostty.rs` with a fixture stream:
  - an RGBA image placed at a cell
  - a PNG decoded through the callback
  - an image restored from a snapshot
  - deletion (`a=d`)
  - scroll moves placements and keeps generation semantics
  - Unicode placeholder placement

### 3. JNI packet (`rexling-core/jni/src/updates.rs`, `engine.rs`)
- Bump the packet to `RXT4` and keep decoding RXT3 in Kotlin for older builds.
  After the cell rows, add:
  - `u8 imageFlag`: 0 unchanged, 1 replace list.
  - Placement list: `u16 count`, then the fixed-size fields above.
  - Pixel blobs: `u16 count`, then `u32 id, u64 generation, u16 w, u16 h,
    u32 len, rgba[len]`. Send a blob only for (id, generation) pairs Kotlin
    hasn't been sent yet, tracked per decoder in `WireBase` and reset on
    `reset`.
- Caps: at most 256 placements per frame and 32 MB of pixel bytes per packet.
  Anything over the cap is dropped with a notice. Kotlin already rejects
  packets over 128 MB.
- When a pane isn't visible or a preview frame is shown, don't send pixels;
  placements only. Send pixels when the pane becomes visible.
- If animation is playing, the engine schedules a re-publish at the deadline
  `animation_tick` returns.

### 4. Kotlin (`TerminalUpdates.kt`, `TerminalView.kt`)
- `TerminalScreen.images: List<TerminalImage>` holds the placements and
  `TerminalScreen.imagePixels` is a shared `ImageCache`.
- `ImageCache`: an LRU keyed by (id, generation) storing `Bitmap` built with
  `Bitmap.createBitmap(w,h,ARGB_8888)` + `copyPixelsFromBuffer`. Rust sends
  pixels already in the byte order `ARGB_8888` expects, so Kotlin does no
  per-pixel work. Budget about 48 MB across panes. Evict bitmaps for panes
  removed from the carousel.
- `TerminalView.onDraw`:
  1. Background, then images with layer *below text* (z < 0 in kitty terms, as
     reported by `LAYER`).
  2. Recorded text rows (unchanged; cells under images are usually blank).
  3. Images with layer *above text*.
  4. Cursor and selection as today.

  Destination rect: `x = inset + col*cellWidth + off_x*scaleX`, `w = px_w*scaleX`,
  where `scaleX = cellWidth / serverCellPxW` (same for y). Clip to the content
  rect. Draw with `canvas.drawBitmap(bmp, srcRect, dstRect, filterPaint)`.
- Don't put images into the row recordings. Draw them each frame from the
  cached bitmaps (cheap) so scrolling doesn't invalidate the recordings.
- Selection/copy: images aren't text. Leave copy behaviour as is.
- Read-only fit and scaled previews: apply the same scale as the grid.
- Frost and Haze capture: images are part of the terminal view, so they're
  blurred behind sheets automatically. Nothing to do.

### 5. Preferences
- Add a "Show images" toggle (`SettingsToggleRow`) under Preferences, on by
  default. When it's off, the shim sets the storage limit to 0. That disables
  the protocol on the replica only, not on the server.

## Phases

| Phase | Work | Done when |
|---|---|---|
| 0 | Tool survey through Rex (open questions 1–4) | We know which tools draw and which need flags |
| 1 | Shim: limit, PNG decoder, `rp_images`/`rp_image_pixels` + Rust types + tests | `cargo test` covers placement, PNG, snapshot, delete, scroll, placeholder |
| 2 | RXT4 packet + Kotlin decoder + decoder unit tests | Round-trip test for placements and pixel blobs; RXT3 still decodes |
| 3 | `TerminalView` drawing + `ImageCache` | icat image shows at the right spot and size on the phone; scrolls with text |
| 4 | Animation tick, hidden-pane pixel deferral, memory caps, preference toggle | Animated GIF via icat plays; memory stays under budget with many images |
| 5 | Docs: PROTOCOL.md (local packet), VALIDATION.md, README fork list | — |

## Testing
- **Rust:** fixture streams in `rexling-core/tests` plus live tests that create
  a disposable Rex session, print images and check the restored and live
  storage. The probe C program from the investigation shows the approach.
- **Kotlin unit:** `TerminalUpdates` RXT4 decode, caps and errors;
  `ImageCache` eviction.
- **Android instrumented:** a `TerminalView` screenshot test with a synthetic
  frame containing one image below the text and one above it.
- **Manual on the phone:** `kitty +kitten icat`, yazi image preview,
  image.nvim, `chafa -f kitty`, and an animated GIF. Each should survive a
  reconnect and switching panes.

## Risks
- **Rex not answering capability queries.** Tools may not send images at all.
  This is outside our control; mitigated by tool flags.
- **Memory.** Large or many images per pane, multiplied by panes. Mitigated
  by native per-terminal limits, the JNI per-packet cap, a Kotlin LRU budget
  and deferring hidden panes.
- **Snapshot format v1 is marked "work in progress"** upstream. A Ghostty bump
  on the server could change it; we pin `ghostty-source.json`, and unknown
  records are skipped.
- **JNI packet size and latency** when a large image first arrives. Pixels are
  sent once per generation, so it's a one-off cost.

## Out of scope
- Sixel and iTerm2 inline images (libghostty-vt doesn't expose them in this
  build).
- Sending local images to programs (that's the existing upload feature).
- Replacing our Canvas renderer with Ghostty's GPU renderer.
