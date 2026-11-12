// Public libghostty-vt C ABI only. The Rust-facing ABI is deliberately small.
#include <ghostty/vt.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef void (*ApcFn)(void *, const uint8_t *, size_t);
typedef void (*CellFn)(void *, uint16_t, uint16_t, const uint32_t *, size_t,
                       const uint8_t *, const uint8_t *, uint32_t, uint8_t, const uint8_t *, size_t);
typedef struct {
  uint16_t columns, rows, cursor_x, cursor_y;
  uint8_t cursor_visible, foreground[3], background[3];
  uint64_t scroll_total, scroll_offset, scroll_visible;
  uint8_t input_flags;
  /* Kitty graphics: placement-list generation, cell size the placements use,
     and the next animation deadline in ms from now (-1: none playing). */
  uint64_t image_generation;
  uint32_t cell_width_px, cell_height_px;
  int64_t animation_delay_ms;
} RpFrame;
/* One drawable kitty placement, already positioned by the render state. */
typedef struct {
  uint32_t image_id;
  uint64_t image_generation;
  uint8_t layer;
  int32_t z, col, row;
  uint32_t cols, rows, offset_x, offset_y, width, height;
  uint32_t source_x, source_y, source_width, source_height;
} RpImage;
typedef void (*ImageFn)(void *, const RpImage *);
typedef struct {
  GhosttyTerminal terminal;
  GhosttyRenderState render;
  GhosttyRenderStateRowIterator rows;
  GhosttyRenderStateRowCells cells;
  GhosttyRenderStateImageIterator images;
  ApcFn callback;
  void *userdata;
} RpTerminal;

/* Per-pane kitty image budget. Decoded pixels count toward it; the oldest
   images are evicted first, exactly as in Ghostty. */
#define RP_IMAGE_LIMIT ((uint64_t)32 * 1024 * 1024)

/* PNG decoding lives in Rust (png crate); it returns an RGBA8 buffer that the
   shim copies into Ghostty's allocator so the library can own and free it. */
extern uint8_t *rex_png_decode(const uint8_t *data, size_t len, uint32_t *width,
                               uint32_t *height, size_t *out_len);
extern void rex_png_free(uint8_t *data, size_t len);
static bool decode_png(void *userdata, const GhosttyAllocator *allocator,
                       const uint8_t *data, size_t len, GhosttySysImage *out) {
  (void)userdata;
  uint32_t width = 0, height = 0; size_t pixels_len = 0;
  uint8_t *pixels = rex_png_decode(data, len, &width, &height, &pixels_len);
  if (!pixels) return false;
  uint8_t *owned = ghostty_alloc(allocator, pixels_len);
  if (owned) memcpy(owned, pixels, pixels_len);
  rex_png_free(pixels, pixels_len);
  if (!owned) return false;
  out->width = width; out->height = height;
  out->data = owned; out->data_len = pixels_len;
  return true;
}
static pthread_once_t sys_once = PTHREAD_ONCE_INIT;
static void sys_init(void) {
  ghostty_sys_set(GHOSTTY_SYS_OPT_DECODE_PNG, (const void *)decode_png);
}
static uint64_t now_ms(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

void rp_free(RpTerminal *t) {
  if (!t) return;
  ghostty_render_state_row_cells_free(t->cells);
  ghostty_render_state_image_iterator_free(t->images);
  ghostty_render_state_row_iterator_free(t->rows);
  ghostty_render_state_free(t->render);
  ghostty_terminal_free(t->terminal);
  free(t);
}
static void unknown(GhosttyTerminal terminal, void *userdata,
                    const GhosttyTerminalUnknownSequence *sequence) {
  (void)terminal;
  RpTerminal *t = userdata;
  if (sequence->tag == GHOSTTY_TERMINAL_UNKNOWN_SEQUENCE_APC &&
      !sequence->value.apc.truncated && t->callback) {
    GhosttyString s = sequence->value.apc.content;
    t->callback(t->userdata, (const uint8_t *)s.ptr, s.len);
  }
}
static int configure(RpTerminal *t) {
  size_t limit = 65536;
  uint64_t images = RP_IMAGE_LIMIT;
  int r = ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, t);
  /* File, temp-file and shared-memory media stay off: they name paths on the
     server, which this replica must never read. */
  if (r == 0) r = ghostty_terminal_set(t->terminal,
      GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT, &images);
  if (r == 0) r = ghostty_terminal_set(t->terminal,
      GHOSTTY_TERMINAL_OPT_UNKNOWN_SEQUENCE, (const void *)unknown);
  if (r == 0) r = ghostty_terminal_set(t->terminal,
      GHOSTTY_TERMINAL_OPT_UNKNOWN_MAX_BYTES, &limit);
  if (r == 0) r = ghostty_terminal_set(t->terminal,
      GHOSTTY_TERMINAL_OPT_CONTINUATION_MAX_BYTES, &limit);
  const GhosttyTerminalData defaults[] = {GHOSTTY_TERMINAL_DATA_COLOR_FOREGROUND_DEFAULT,
      GHOSTTY_TERMINAL_DATA_COLOR_BACKGROUND_DEFAULT};
  const GhosttyTerminalOption color_options[] = {GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND,
      GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND};
  for (int i=0; r==0 && i<2; i++) {
    GhosttyColorRgb value;
    int present=ghostty_terminal_get(t->terminal,defaults[i],&value);
    if (present==GHOSTTY_NO_VALUE) {
      value=(GhosttyColorRgb){i==0?255:0,i==0?255:0,i==0?255:0};
      r=ghostty_terminal_set(t->terminal,color_options[i],&value);
    } else if(present!=0) r=present;
  }
  // No WRITE_PTY callback: the server's canonical terminal answers queries.
  if (r == 0) r = ghostty_render_state_new(NULL, &t->render);
  if (r == 0) r = ghostty_render_state_row_iterator_new(NULL, &t->rows);
  if (r == 0) r = ghostty_render_state_row_cells_new(NULL, &t->cells);
  if (r == 0) r = ghostty_render_state_image_iterator_new(NULL, &t->images);
  return r;
}
int rp_new(uint16_t cols, uint16_t rows, RpTerminal **out) {
  *out = NULL;
  pthread_once(&sys_once, sys_init);
  RpTerminal *t = calloc(1, sizeof(*t));
  if (!t) return GHOSTTY_OUT_OF_MEMORY;
  int r = ghostty_terminal_new(NULL, &t->terminal, cols, rows);
  if (r == 0) r = configure(t);
  if (r != 0) { rp_free(t); return r; }
  *out = t;
  return 0;
}
int rp_decode(const uint8_t *bytes, size_t len, RpTerminal **out, size_t *offset) {
  *out = NULL;
  pthread_once(&sys_once, sys_init);
  RpTerminal *t = calloc(1, sizeof(*t));
  if (!t) return GHOSTTY_OUT_OF_MEMORY;
  GhosttySnapshotDecoder decoder = NULL;
  uint64_t images = RP_IMAGE_LIMIT;
  int r = ghostty_snapshot_decoder_new_buf(NULL, &decoder, bytes, len);
  if (r == 0) r = ghostty_snapshot_decoder_set(decoder,
      GHOSTTY_SNAPSHOT_DECODER_OPT_KITTY_IMAGE_STORAGE_LIMIT, &images);
  if (r == 0) r = ghostty_snapshot_decoder_decode(decoder, &t->terminal);
  if (r == 0) r = ghostty_snapshot_decoder_get(decoder,
      GHOSTTY_SNAPSHOT_DECODER_DATA_SOURCE_OFFSET, offset);
  ghostty_snapshot_decoder_free(decoder);
  if (r == 0) r = configure(t);
  if (r != 0) { rp_free(t); return r; }
  *out = t;
  return 0;
}
int rp_write(RpTerminal *t, const uint8_t *bytes, size_t len, size_t *consumed,
             ApcFn callback, void *userdata) {
  t->callback = callback;
  t->userdata = userdata;
  GhosttyTerminalWriteStop stop = GHOSTTY_INIT_SIZED(GhosttyTerminalWriteStop);
  int r = ghostty_terminal_vt_write_until(t->terminal, bytes, len,
      GHOSTTY_TERMINAL_BOUNDARY_APC_END, &stop);
  t->callback = NULL;
  t->userdata = NULL;
  if (r == GHOSTTY_NO_VALUE) { *consumed = len; return 0; }
  *consumed = stop.consumed;
  return r;
}
int rp_resize(RpTerminal *t, uint16_t cols, uint16_t rows, uint32_t cw, uint32_t ch) {
  return ghostty_terminal_resize(t->terminal, cols, rows, cw, ch);
}
int rp_color(RpTerminal *t, int which, const uint8_t *rgb) {
  const GhosttyTerminalOption options[] = {GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND,
      GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR,
      GHOSTTY_TERMINAL_OPT_COLOR_PALETTE};
  if (which < 0 || which > 3) return GHOSTTY_INVALID_VALUE;
  GhosttyColorRgb color;
  // RenderState only updates fg/bg together when both defaults are set.
  // These are Ghostty RenderState.empty's built-in fallback colors.
  const uint8_t fallback[3] = {which == 0 ? 255 : 0, which == 0 ? 255 : 0, which == 0 ? 255 : 0};
  if (!rgb && which < 2) rgb = fallback;
  if (rgb) { color.r=rgb[0]; color.g=rgb[1]; color.b=rgb[2]; }
  return ghostty_terminal_set(t->terminal, options[which], rgb ? &color : NULL);
}
int rp_palette(RpTerminal *t, uint8_t index, const uint8_t *rgb) {
  GhosttyColorRgb palette[256];
  int r = ghostty_terminal_get(t->terminal, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE_DEFAULT, palette);
  if (r != 0) return r;
  palette[index] = (GhosttyColorRgb){rgb[0],rgb[1],rgb[2]};
  return ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, palette);
}
int rp_text(RpTerminal *t, uint8_t **out, size_t *len) {
  GhosttyFormatterTerminalOptions options = GHOSTTY_INIT_SIZED(GhosttyFormatterTerminalOptions);
  options.extra = (GhosttyFormatterTerminalExtra)GHOSTTY_INIT_SIZED(GhosttyFormatterTerminalExtra);
  options.extra.screen = (GhosttyFormatterScreenExtra)GHOSTTY_INIT_SIZED(GhosttyFormatterScreenExtra);
  options.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
  options.trim = true;
  GhosttyFormatter formatter = NULL;
  int r = ghostty_formatter_terminal_new(NULL, &formatter, t->terminal, options);
  if (r == 0) r = ghostty_formatter_format_alloc(formatter, NULL, out, len);
  ghostty_formatter_free(formatter);
  return r;
}
int rp_encode(RpTerminal *t, uint8_t **out, size_t *len) {
  return ghostty_snapshot_encode_alloc(t->terminal, NULL, out, len);
}
void rp_bytes_free(uint8_t *bytes, size_t len) { ghostty_free(NULL, bytes, len); }

void rp_scroll(RpTerminal *t, int32_t lines, int bottom) {
  GhosttyTerminalScrollViewport behavior = {0};
  behavior.tag = bottom ? GHOSTTY_SCROLL_VIEWPORT_BOTTOM : GHOSTTY_SCROLL_VIEWPORT_DELTA;
  behavior.value.delta = lines;
  ghostty_terminal_scroll_viewport(t->terminal, behavior);
}
int rp_input_flags(RpTerminal *t, uint8_t *flags) {
  bool mouse=false;
  GhosttyTerminalScreen screen=GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  GhosttyTerminalModeConfig alt={.mode=GHOSTTY_MODE_ALT_SCROLL},cursor={.mode=GHOSTTY_MODE_DECCKM};
  int r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING,&mouse);
  if(r==0) r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN,&screen);
  if(r==0) r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_MODE,&alt);
  if(r==0) r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_MODE,&cursor);
  *flags=(mouse?1:0)|(screen==GHOSTTY_TERMINAL_SCREEN_ALTERNATE?2:0)|(alt.value?4:0)|(cursor.value?8:0);
  return r;
}
static int rp_mouse(RpTerminal *t,GhosttyMouseButton button,int release,int col,int row,uint32_t cw,uint32_t ch,char *out,size_t capacity,size_t *len) {
  GhosttyMouseEncoder encoder=NULL;GhosttyMouseEvent event=NULL;
  uint16_t cols=0,rows=0;*len=0;
  int r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_COLS,&cols);
  if(r==0) r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_ROWS,&rows);
  if(r==0) r=ghostty_mouse_encoder_new(NULL,&encoder);
  if(r==0) r=ghostty_mouse_event_new(NULL,&event);
  if(r==0) {
    if(col<0)col=0;if(col>=cols)col=cols-1;if(row<0)row=0;if(row>=rows)row=rows-1;
    if(cw==0)cw=1;if(ch==0)ch=1;
    GhosttyMouseEncoderSize geometry=GHOSTTY_INIT_SIZED(GhosttyMouseEncoderSize);
    geometry.screen_width=cols*cw;geometry.screen_height=rows*ch;geometry.cell_width=cw;geometry.cell_height=ch;
    ghostty_mouse_encoder_setopt_from_terminal(encoder,t->terminal);
    ghostty_mouse_encoder_setopt(encoder,GHOSTTY_MOUSE_ENCODER_OPT_SIZE,&geometry);
    ghostty_mouse_event_set_action(event,GHOSTTY_MOUSE_ACTION_PRESS);
    ghostty_mouse_event_set_button(event,button);
    ghostty_mouse_event_set_position(event,(GhosttyMousePosition){(col+.5f)*cw,(row+.5f)*ch});
    r=ghostty_mouse_encoder_encode(encoder,event,out,capacity,len);
    if(r==0 && release && *len<=capacity) {
      size_t tail=0;
      ghostty_mouse_event_set_action(event,GHOSTTY_MOUSE_ACTION_RELEASE);
      r=ghostty_mouse_encoder_encode(encoder,event,out+*len,capacity-*len,&tail);
      if(r==0) *len+=tail;
    }
  }
  ghostty_mouse_event_free(event);ghostty_mouse_encoder_free(encoder);return r;
}
int rp_wheel(RpTerminal *t,int down,int col,int row,uint32_t cw,uint32_t ch,char *out,size_t capacity,size_t *len) {
  return rp_mouse(t,down?GHOSTTY_MOUSE_BUTTON_FIVE:GHOSTTY_MOUSE_BUTTON_FOUR,0,col,row,cw,ch,out,capacity,len);
}
int rp_click(RpTerminal *t,int col,int row,uint32_t cw,uint32_t ch,char *out,size_t capacity,size_t *len) {
  return rp_mouse(t,GHOSTTY_MOUSE_BUTTON_LEFT,1,col,row,cw,ch,out,capacity,len);
}
/* Set the image budget; zero disables kitty graphics on this replica only. */
int rp_image_limit(RpTerminal *t, uint64_t bytes) {
  return ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT, &bytes);
}
/* Borrow one image's decoded pixels. Valid until the next mutating call. */
int rp_image_pixels(RpTerminal *t, uint32_t id, uint32_t *width, uint32_t *height,
                    uint32_t *format, const uint8_t **data, size_t *len) {
  GhosttyKittyGraphics graphics = NULL;
  int r = ghostty_terminal_get(t->terminal, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS, &graphics);
  if (r != 0) return r;
  GhosttyKittyGraphicsImage image = ghostty_kitty_graphics_image(graphics, id);
  if (!image) return GHOSTTY_NO_VALUE;
  GhosttyKittyImageFormat kind = GHOSTTY_KITTY_IMAGE_FORMAT_RGBA;
  r = ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_WIDTH, width);
  if (r == 0) r = ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_HEIGHT, height);
  if (r == 0) r = ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_FORMAT, &kind);
  if (r == 0) r = ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_DATA_PTR, data);
  if (r == 0) r = ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_DATA_LEN, len);
  *format = (uint32_t)kind;
  return r;
}
static int64_t tick_animations(RpTerminal *t) {
  GhosttyKittyGraphics graphics = NULL;
  if (ghostty_terminal_get(t->terminal, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS, &graphics) != 0 || !graphics)
    return -1;
  uint64_t now = now_ms(), next = 0;
  if (ghostty_kitty_graphics_animation_tick(graphics, now, &next) != GHOSTTY_SUCCESS) return -1;
  return next > now ? (int64_t)(next - now) : 0;
}
static int report_images(RpTerminal *t, ImageFn callback, void *userdata) {
  int r = ghostty_render_state_get(t->render, GHOSTTY_RENDER_STATE_DATA_IMAGE_ITERATOR, &t->images);
  if (r != 0) return r;
  while (ghostty_render_state_image_iterator_next(t->images)) {
    GhosttyImageProtocol protocol = GHOSTTY_IMAGE_PROTOCOL_KITTY;
    r = ghostty_render_state_image_get(t->images, GHOSTTY_RENDER_STATE_IMAGE_DATA_PROTOCOL, &protocol);
    if (r != 0) return r;
    if (protocol != GHOSTTY_IMAGE_PROTOCOL_KITTY) continue; /* skip protocols we don't know */
    RpImage image = {0};
    GhosttyRenderStateImageLayer layer = GHOSTTY_RENDER_STATE_IMAGE_LAYER_ABOVE_TEXT;
    const GhosttyRenderStateImageData keys[] = {
      GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_ID, GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_GENERATION,
      GHOSTTY_RENDER_STATE_IMAGE_DATA_LAYER, GHOSTTY_RENDER_STATE_IMAGE_DATA_Z,
      GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_COL, GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_ROW,
      GHOSTTY_RENDER_STATE_IMAGE_DATA_GRID_COLS, GHOSTTY_RENDER_STATE_IMAGE_DATA_GRID_ROWS,
      GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_X, GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_Y,
      GHOSTTY_RENDER_STATE_IMAGE_DATA_PIXEL_WIDTH, GHOSTTY_RENDER_STATE_IMAGE_DATA_PIXEL_HEIGHT,
      GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_X, GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_Y,
      GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_WIDTH, GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_HEIGHT};
    void *values[] = {&image.image_id, &image.image_generation, &layer, &image.z,
      &image.col, &image.row, &image.cols, &image.rows, &image.offset_x, &image.offset_y,
      &image.width, &image.height, &image.source_x, &image.source_y,
      &image.source_width, &image.source_height};
    r = ghostty_render_state_image_get_multi(t->images, sizeof(keys)/sizeof(keys[0]), keys, values, NULL);
    if (r != 0) return r;
    image.layer = (uint8_t)layer;
    callback(userdata, &image);
  }
  return 0;
}
int rp_frame(RpTerminal *t, RpFrame *frame, CellFn callback, void *userdata,
             uint64_t known_images, ImageFn image_callback, void *image_userdata) {
  frame->animation_delay_ms = tick_animations(t);
  int r = ghostty_render_state_update(t->render, t->terminal);
  if (r != 0) return r;
  GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  GhosttyRenderStateCursor cursor = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
#define GET(key,out) do { r=ghostty_render_state_get(t->render,key,out); if(r!=0)return r; } while(0)
  GET(GHOSTTY_RENDER_STATE_DATA_COLS,&frame->columns);
  GET(GHOSTTY_RENDER_STATE_DATA_ROWS,&frame->rows);
  GET(GHOSTTY_RENDER_STATE_DATA_COLORS,&colors);
  GET(GHOSTTY_RENDER_STATE_DATA_CURSOR,&cursor);
  GhosttyTerminalScrollbar scrollbar = {0};
  r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_SCROLLBAR,&scrollbar);
  if(r!=0)return r;
  frame->scroll_total=scrollbar.total;
  frame->scroll_offset=scrollbar.offset;
  frame->scroll_visible=scrollbar.len;
  r=rp_input_flags(t,&frame->input_flags);if(r!=0)return r;
  frame->cursor_x=cursor.viewport_x; frame->cursor_y=cursor.viewport_y;
  // Bit 0: drawn cursor. Bit 1: position known even when hidden, as TUIs park it for IMEs.
  frame->cursor_visible=(cursor.visible && cursor.viewport_has_value) | (cursor.viewport_has_value<<1);
  memcpy(frame->foreground,&colors.foreground,3);
  memcpy(frame->background,&colors.background,3);
  uint32_t width_px=0,height_px=0;
  if(ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_WIDTH_PX,&width_px)==0 && frame->columns)
    frame->cell_width_px=width_px/frame->columns;
  if(ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_HEIGHT_PX,&height_px)==0 && frame->rows)
    frame->cell_height_px=height_px/frame->rows;
  GET(GHOSTTY_RENDER_STATE_DATA_IMAGE_PLACEMENTS_GENERATION,&frame->image_generation);
  /* Only walk the image list when it changed; the caller keeps the last one. */
  if(frame->image_generation!=known_images && image_callback) {
    r=report_images(t,image_callback,image_userdata);
    if(r!=0)return r;
  }
  GET(GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,&t->rows);
  uint16_t y=0;
  uint32_t *graphemes=NULL; size_t capacity=0;
  uint8_t *uri=NULL; size_t uri_capacity=0;
  while (ghostty_render_state_row_iterator_next(t->rows)) {
    r=ghostty_render_state_row_get(t->rows,GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,&t->cells);
    if(r!=0)break;
    GhosttyRow raw_row=0; bool wrapped=false,continuation=false;
    r=ghostty_render_state_row_get(t->rows,GHOSTTY_RENDER_STATE_ROW_DATA_RAW,&raw_row);if(r!=0)break;
    r=ghostty_row_get(raw_row,GHOSTTY_ROW_DATA_WRAP,&wrapped);if(r!=0)break;
    r=ghostty_row_get(raw_row,GHOSTTY_ROW_DATA_WRAP_CONTINUATION,&continuation);if(r!=0)break;
    uint16_t x=0;
    while (ghostty_render_state_row_cells_next(t->cells)) {
      uint32_t count=0;
      GhosttyStyle style=GHOSTTY_INIT_SIZED(GhosttyStyle);
      GhosttyColorRgb fg=colors.foreground,bg=colors.background;
      GhosttyCell raw=0; GhosttyCellWide wide=GHOSTTY_CELL_WIDE_NARROW;
#define CELL(key,out) do { r=ghostty_render_state_row_cells_get(t->cells,key,out); if(r!=0)goto done; } while(0)
      CELL(GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN,&count);
      CELL(GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,&style);
      CELL(GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW,&raw);
      r=ghostty_cell_get(raw,GHOSTTY_CELL_DATA_WIDE,&wide); if(r!=0)goto done;
      // No explicit color is reported as INVALID_VALUE; retain resolved defaults.
      int fg_default = ghostty_render_state_row_cells_get(t->cells,GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,&fg) != 0;
      int bg_default = ghostty_render_state_row_cells_get(t->cells,GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,&bg) != 0;
      if(count>4096) { r=GHOSTTY_LIMIT_EXCEEDED; goto done; }
      if(count>capacity) {
        uint32_t *next=realloc(graphemes,count*sizeof(*graphemes));
        if(!next) { r=GHOSTTY_OUT_OF_MEMORY; goto done; }
        graphemes=next; capacity=count;
      }
      if(count) { CELL(GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,graphemes); }
      // Client-local row metadata occupies bits above the color-role fields.
      uint32_t row_flags=(1u<<29)|((uint32_t)wrapped<<27)|((uint32_t)continuation<<28);
      uint32_t flags=row_flags | style.bold | (style.italic<<1) | (style.faint<<2) |
          (style.inverse<<3) | (style.invisible<<4) | (style.strikethrough<<5) |
          ((style.underline!=0)<<6) | (fg_default<<7) | (bg_default<<8);
      if (style.fg_color.tag == GHOSTTY_STYLE_COLOR_PALETTE)
        flags |= (1u<<9) | ((uint32_t)style.fg_color.value.palette<<11);
      GhosttyCellContentTag content_tag=GHOSTTY_CELL_CONTENT_CODEPOINT;
      ghostty_cell_get(raw,GHOSTTY_CELL_DATA_CONTENT_TAG,&content_tag);
      if (content_tag == GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE) {
        GhosttyColorPaletteIndex index=0;
        ghostty_cell_get(raw,GHOSTTY_CELL_DATA_COLOR_PALETTE,&index);
        flags |= (1u<<10) | ((uint32_t)index<<19);
      } else if (style.bg_color.tag == GHOSTTY_STYLE_COLOR_PALETTE) {
        flags |= (1u<<10) | ((uint32_t)style.bg_color.value.palette<<19);
      }
      uint8_t width=wide==GHOSTTY_CELL_WIDE_WIDE ? 2 :
          (wide==GHOSTTY_CELL_WIDE_SPACER_TAIL || wide==GHOSTTY_CELL_WIDE_SPACER_HEAD ? 0 : 1);
      size_t uri_len=0; bool has_link=false;
      r=ghostty_cell_get(raw,GHOSTTY_CELL_DATA_HAS_HYPERLINK,&has_link);if(r!=0)goto done;
      if(has_link && width>0) {
        GhosttyGridRef ref=GHOSTTY_INIT_SIZED(GhosttyGridRef);
        GhosttyPoint point={.tag=GHOSTTY_POINT_TAG_VIEWPORT,.value.coordinate={.x=x,.y=y}};
        r=ghostty_terminal_grid_ref(t->terminal,point,&ref);if(r!=0)goto done;
        r=ghostty_grid_ref_hyperlink_uri(&ref,uri,uri_capacity,&uri_len);
        if(r==GHOSTTY_OUT_OF_SPACE && uri_len<=65536) {
          uint8_t *next=realloc(uri,uri_len);
          if(!next) {r=GHOSTTY_OUT_OF_MEMORY;goto done;}
          uri=next;uri_capacity=uri_len;
          r=ghostty_grid_ref_hyperlink_uri(&ref,uri,uri_capacity,&uri_len);
        }
        if(uri_len>65536) {uri_len=0;r=0;}
        if(r!=0)goto done;
      }
      callback(userdata,x++,y,graphemes,count,(const uint8_t *)&fg,(const uint8_t *)&bg,flags,width,uri,uri_len);
    }
    y++;
  }
done:
  free(graphemes);free(uri);
  if(r==0)r=ghostty_render_state_clean(t->render);
  return r;
}
