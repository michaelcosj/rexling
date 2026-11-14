// Public libghostty-vt C ABI only. The Rust-facing ABI is deliberately small.
#include <ghostty/vt.h>
#include <stdlib.h>
#include <string.h>

typedef void (*ApcFn)(void *, uint8_t, const uint8_t *, size_t);
typedef void (*CellFn)(void *, uint16_t, uint16_t, const uint32_t *, size_t,
                       const uint8_t *, const uint8_t *, uint32_t, uint8_t, const uint8_t *, size_t);
typedef struct {
  uint16_t columns, rows, cursor_x, cursor_y;
  uint8_t cursor_visible, foreground[3], background[3];
  uint64_t scroll_total, scroll_offset, scroll_visible;
  uint8_t input_flags;
} RpFrame;
typedef struct {
  GhosttyTerminal terminal;
  GhosttyRenderState render;
  GhosttyRenderStateRowIterator rows;
  GhosttyRenderStateRowCells cells;
  ApcFn callback;
  void *userdata;
  uint64_t render_hold_generation;
  bool semantic_seen, semantic_accepting, semantic_scanned, semantic_metadata;
} RpTerminal;

void rp_free(RpTerminal *t) {
  if (!t) return;
  ghostty_render_state_row_cells_free(t->cells);
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
    t->callback(t->userdata, 0, (const uint8_t *)s.ptr, s.len);
  }
}
// libghostty decodes OSC 52; only bounded UTF-8 text is forwarded to Android.
static void clipboard_write(GhosttyTerminal terminal,void *userdata,const GhosttyClipboardWrite *write) {
  (void)terminal;RpTerminal *t=userdata;
  GhosttyClipboardWriteReply reply={.result=GHOSTTY_CLIPBOARD_WRITE_RESULT_UNSUPPORTED};
  for(size_t i=0;t->callback && i<write->contents_len;i++) {
    const GhosttyClipboardContent *content=&write->contents[i];
    if(content->mime.len==10 && memcmp(content->mime.ptr,"text/plain",10)==0 && content->data.len<=262144) {
      t->callback(t->userdata,1,(const uint8_t *)content->data.ptr,content->data.len);
      reply.result=GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS;break;
    }
  }
  // Read callbacks are intentionally not registered: remote clipboard reads are denied.
  write->reply(write,&reply);
}
static void semantic_prompt(GhosttyTerminal terminal,void *userdata,const GhosttyTerminalSemanticPrompt *event) {
  (void)terminal;
  RpTerminal *t=userdata;t->semantic_seen=true;
  switch(event->kind) {
    case GHOSTTY_SEMANTIC_PROMPT_INPUT_START:t->semantic_accepting=true;break;
    case GHOSTTY_SEMANTIC_PROMPT_OUTPUT_START:
    case GHOSTTY_SEMANTIC_PROMPT_COMMAND_END:t->semantic_accepting=false;break;
    case GHOSTTY_SEMANTIC_PROMPT_PROMPT_START:
      if(event->prompt_kind==GHOSTTY_SEMANTIC_PROMPT_PROMPT_PRIMARY)t->semantic_accepting=false;
      break;
    default:break;
  }
}
static void render_hold(GhosttyTerminal terminal, void *userdata, bool held) {
  (void)terminal;
  RpTerminal *t=userdata;
  if(held)t->render_hold_generation++;
}
int rp_render_hold_state(RpTerminal *t,uint8_t *held,uint64_t *generation) {
  GhosttyTerminalModeConfig mode={.mode=GHOSTTY_MODE_SYNCHRONIZED_OUTPUT};
  int r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_MODE,&mode);
  *held=mode.value?1:0;*generation=t->render_hold_generation;
  return r;
}
#define RP_IMAGE_LIMIT (16ull * 1024 * 1024)
int rp_graphics_init(GhosttySysDecodePngFn decode) {
  return ghostty_sys_set(GHOSTTY_SYS_OPT_DECODE_PNG, (const void *)decode);
}
uint8_t *rp_graphics_alloc(const GhosttyAllocator *allocator,size_t len) {
  return ghostty_alloc(allocator,len);
}
static int configure(RpTerminal *t) {
  size_t limit = 65536;
  int r = ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, t);
  if(r==0)r=ghostty_terminal_set(t->terminal,GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE,(const void *)clipboard_write);
  if(r==0)r=ghostty_terminal_set(t->terminal,GHOSTTY_TERMINAL_OPT_RENDER_HOLD,(const void *)render_hold);
  if(r==0)r=ghostty_terminal_set(t->terminal,GHOSTTY_TERMINAL_OPT_SEMANTIC_PROMPT,(const void *)semantic_prompt);
  if (r == 0) r = ghostty_terminal_set(t->terminal,
      GHOSTTY_TERMINAL_OPT_UNKNOWN_SEQUENCE, (const void *)unknown);
  if (r == 0) r = ghostty_terminal_set(t->terminal,
      GHOSTTY_TERMINAL_OPT_UNKNOWN_MAX_BYTES, &limit);
  if (r == 0) r = ghostty_terminal_set(t->terminal,
      GHOSTTY_TERMINAL_OPT_CONTINUATION_MAX_BYTES, &limit);
  uint64_t image_limit=RP_IMAGE_LIMIT;
  bool remote=false;
  if(r==0)r=ghostty_terminal_set(t->terminal,GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT,&image_limit);
  // A remote terminal must never read files or shared memory on this phone.
  if(r==0)r=ghostty_terminal_set(t->terminal,GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_FILE,&remote);
  if(r==0)r=ghostty_terminal_set(t->terminal,GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_SHARED_MEM,&remote);
  if(r==0)r=ghostty_terminal_set(t->terminal,GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_TEMP_FILE,NULL);
  uint16_t cols=0,rows=0;uint32_t pixel_width=0,pixel_height=0;
  if(r==0)r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_COLS,&cols);
  if(r==0)r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_ROWS,&rows);
  if(r==0)r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_WIDTH_PX,&pixel_width);
  if(r==0)r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_HEIGHT_PX,&pixel_height);
  if(r==0 && (!pixel_width || !pixel_height))r=ghostty_terminal_resize(t->terminal,cols,rows,8,16);
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
  return r;
}
int rp_new(uint16_t cols, uint16_t rows, RpTerminal **out) {
  *out = NULL;
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
  RpTerminal *t = calloc(1, sizeof(*t));
  if (!t) return GHOSTTY_OUT_OF_MEMORY;
  GhosttySnapshotDecoder decoder = NULL;
  int r = ghostty_snapshot_decoder_new_buf(NULL, &decoder, bytes, len);
  uint64_t image_limit=RP_IMAGE_LIMIT;
  if(r==0)r=ghostty_snapshot_decoder_set(decoder,GHOSTTY_SNAPSHOT_DECODER_OPT_KITTY_IMAGE_STORAGE_LIMIT,&image_limit);
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
  GhosttyFormatter formatter = NULL;
  int r = ghostty_formatter_terminal_new(NULL, &formatter, t->terminal);
  if (r == 0) r = ghostty_formatter_format_alloc(formatter, NULL, out, len);
  ghostty_formatter_free(formatter);
  return r;
}
static int screen_ref(RpTerminal *t,uint16_t x,uint32_t y,GhosttyGridRef *ref) {
  GhosttyPoint point={.tag=GHOSTTY_POINT_TAG_SCREEN,.value.coordinate={.x=x,.y=y}};
  return ghostty_terminal_grid_ref(t->terminal,point,ref);
}
static int format_selection(RpTerminal *t,const GhosttySelection *selection,bool trim,uint8_t **out,size_t *len) {
  GhosttyFormatter formatter=NULL;bool unwrap=true;
  int r=ghostty_formatter_terminal_new(NULL,&formatter,t->terminal);
  if(r==0)r=ghostty_formatter_set(formatter,GHOSTTY_FORMATTER_OPT_SELECTION,selection);
  if(r==0)r=ghostty_formatter_set(formatter,GHOSTTY_FORMATTER_OPT_UNWRAP,&unwrap);
  if(r==0)r=ghostty_formatter_set(formatter,GHOSTTY_FORMATTER_OPT_TRIM,&trim);
  if(r==0)r=ghostty_formatter_format_alloc(formatter,NULL,out,len);
  ghostty_formatter_free(formatter);return r;
}
static int append_command(uint8_t **buffer,size_t *size,const uint8_t *text,size_t len) {
  if(len>262144-*size)return GHOSTTY_LIMIT_EXCEEDED;
  if(!len)return 0;
  uint8_t *next=realloc(*buffer,*size+len);if(!next)return GHOSTTY_OUT_OF_MEMORY;
  memcpy(next+*size,text,len);*buffer=next;*size+=len;return 0;
}
// Selection derives from libghostty semantic cells, never from prompt-looking text.
int rp_command_text(RpTerminal *t,uint16_t col,uint16_t row,int output,uint8_t **out,size_t *len) {
  *out=NULL;*len=0;uint16_t cols=0;GhosttyTerminalScreen screen;
  int r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN,&screen);
  if(r!=0)return r;if(screen!=GHOSTTY_TERMINAL_SCREEN_PRIMARY)return GHOSTTY_NO_VALUE;
  r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_COLS,&cols);if(r!=0)return r;
  GhosttyPoint viewport={.tag=GHOSTTY_POINT_TAG_VIEWPORT,.value.coordinate={.x=col,.y=row}};
  GhosttyGridRef ref={0};GhosttyPointCoordinate position={0};
  r=ghostty_terminal_grid_ref(t->terminal,viewport,&ref);if(r!=0)return r;
  r=ghostty_terminal_point_from_grid_ref(t->terminal,&ref,GHOSTTY_POINT_TAG_SCREEN,&position);if(r!=0)return r;
  uint32_t start=position.y;bool found=false;
  for(;;) {
    GhosttyGridRef current={0};GhosttyRow raw=0;GhosttyRowSemanticPrompt kind;bool continuation=false;
    r=screen_ref(t,0,start,&current);if(r!=0)return r;
    r=ghostty_grid_ref_row(&current,&raw);if(r!=0)return r;
    r=ghostty_row_get(raw,GHOSTTY_ROW_DATA_SEMANTIC_PROMPT,&kind);if(r!=0)return r;
    r=ghostty_row_get(raw,GHOSTTY_ROW_DATA_WRAP_CONTINUATION,&continuation);if(r!=0)return r;
    if(kind==GHOSTTY_ROW_SEMANTIC_PROMPT && !continuation) {found=true;break;}
    if(start==0)break;start--;
  }
  if(!found)return GHOSTTY_NO_VALUE;
  GhosttyTerminalScrollbar scrollbar={0};r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_SCROLLBAR,&scrollbar);if(r!=0)return r;
  if(scrollbar.total>UINT32_MAX)return GHOSTTY_LIMIT_EXCEEDED;
  uint8_t *buffer=NULL;size_t size=0;bool input_seen=false;
  for(uint32_t y=start;y<scrollbar.total;y++) {
    GhosttyGridRef current={0};GhosttyRow raw=0;GhosttyRowSemanticPrompt kind;bool continuation=false,wrap=false;
    r=screen_ref(t,0,y,&current);if(r!=0)goto done;
    r=ghostty_grid_ref_row(&current,&raw);if(r!=0)goto done;
    r=ghostty_row_get(raw,GHOSTTY_ROW_DATA_SEMANTIC_PROMPT,&kind);if(r!=0)goto done;
    r=ghostty_row_get(raw,GHOSTTY_ROW_DATA_WRAP_CONTINUATION,&continuation);if(r!=0)goto done;
    r=ghostty_row_get(raw,GHOSTTY_ROW_DATA_WRAP,&wrap);if(r!=0)goto done;
    if(y>start && kind==GHOSTTY_ROW_SEMANTIC_PROMPT && !continuation)break;
    if(!output && y>start && kind==GHOSTTY_ROW_SEMANTIC_NONE && !continuation)break;
    bool row_input=false;
    for(uint16_t x=0;x<cols;x++) {
      GhosttyCell cell=0;GhosttyCellSemanticContent semantic;uint32_t codepoint=0;
      r=screen_ref(t,x,y,&current);if(r!=0)goto done;
      r=ghostty_grid_ref_cell(&current,&cell);if(r!=0)goto done;
      r=ghostty_cell_get(cell,GHOSTTY_CELL_DATA_SEMANTIC_CONTENT,&semantic);if(r!=0)goto done;
      r=ghostty_cell_get(cell,GHOSTTY_CELL_DATA_CODEPOINT,&codepoint);if(r!=0)goto done;
      if(output && semantic==GHOSTTY_CELL_SEMANTIC_OUTPUT && codepoint!=0) {
        GhosttySelection selection={0};r=ghostty_terminal_select_output(t->terminal,&current,&selection);
        if(r!=0)goto done;
        r=format_selection(t,&selection,true,out,len);goto done;
      }
      if(output || semantic!=GHOSTTY_CELL_SEMANTIC_INPUT || codepoint==0)continue;
      GhosttyTerminalSelectLineOptions options=GHOSTTY_INIT_SIZED(GhosttyTerminalSelectLineOptions);
      options.ref=current;options.semantic_prompt_boundary=true;
      GhosttySelection selection={0};r=ghostty_terminal_select_line(t->terminal,&options,&selection);if(r!=0)goto done;
      // Restrict each span to this physical row; join soft wraps ourselves and omit continuation prompts.
      GhosttyPointCoordinate first,last;
      r=ghostty_terminal_point_from_grid_ref(t->terminal,&selection.start,GHOSTTY_POINT_TAG_SCREEN,&first);if(r!=0)goto done;
      r=ghostty_terminal_point_from_grid_ref(t->terminal,&selection.end,GHOSTTY_POINT_TAG_SCREEN,&last);if(r!=0)goto done;
      if(first.y<y) {r=screen_ref(t,0,y,&selection.start);if(r!=0)goto done;}
      if(last.y>y) {r=screen_ref(t,cols-1,y,&selection.end);if(r!=0)goto done;last.x=cols-1;}
      uint8_t *part=NULL;size_t part_len=0;r=format_selection(t,&selection,false,&part,&part_len);
      if(r==0)r=append_command(&buffer,&size,part,part_len);
      ghostty_free(NULL,part,part_len);if(r!=0)goto done;
      input_seen=row_input=true;x=last.x;
    }
    if(!output && !wrap && (row_input || (input_seen && kind==GHOSTTY_ROW_SEMANTIC_PROMPT_CONTINUATION))) {
      r=append_command(&buffer,&size,(const uint8_t *)"\n",1);if(r!=0)goto done;
    }
  }
  if(output || !input_seen) {r=GHOSTTY_NO_VALUE;goto done;}
  if(size && buffer[size-1]=='\n')size--;
  if(size) { *out=ghostty_alloc(NULL,size);if(!*out) {r=GHOSTTY_OUT_OF_MEMORY;goto done;}memcpy(*out,buffer,size); }
  *len=size;r=0;
done:
  free(buffer);return r;
}
int rp_encode(RpTerminal *t, uint8_t **out, size_t *len) {
  return ghostty_snapshot_encode_alloc(t->terminal, NULL, out, len);
}
void rp_bytes_free(uint8_t *bytes, size_t len) { ghostty_free(NULL, bytes, len); }

void rp_scroll(RpTerminal *t, int32_t lines, int bottom) {
  GhosttyTerminalScrollViewport behavior = {0};
  behavior.tag = bottom ? GHOSTTY_TERMINAL_SCROLL_VIEWPORT_BOTTOM : GHOSTTY_TERMINAL_SCROLL_VIEWPORT_DELTA;
  behavior.value.delta = lines;
  ghostty_terminal_scroll_viewport(t->terminal, behavior);
}
void rp_scroll_prompts(RpTerminal *t,int32_t prompts) {
  GhosttyTerminalScrollViewport behavior={.tag=GHOSTTY_TERMINAL_SCROLL_VIEWPORT_DELTA_PROMPT,.value.delta_prompt=prompts};
  ghostty_terminal_scroll_viewport(t->terminal,behavior);
}
int rp_input_flags(RpTerminal *t, uint8_t *flags) {
  bool mouse=false,at_prompt=false;
  GhosttyTerminalScreen screen=GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  GhosttyTerminalModeConfig alt={.mode=GHOSTTY_MODE_MOUSE_ALTERNATE_SCROLL},cursor={.mode=GHOSTTY_MODE_CURSOR_KEYS};
  int r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING,&mouse);
  if(r==0) r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN,&screen);
  if(r==0) r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_MODE,&alt);
  if(r==0) r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_MODE,&cursor);
  if(r==0)r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_CURSOR_AT_PROMPT,&at_prompt);
  bool accepting=at_prompt && (!t->semantic_seen || t->semantic_accepting);
  bool known=screen==GHOSTTY_TERMINAL_SCREEN_PRIMARY && (t->semantic_seen || t->semantic_metadata || at_prompt);
  *flags=(accepting?16:0)|(known?32:0)|(mouse?1:0)|(screen==GHOSTTY_TERMINAL_SCREEN_ALTERNATE?2:0)|(alt.value?4:0)|(cursor.value?8:0);
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
    ghostty_mouse_encoder_set_from_terminal(encoder,t->terminal);
    ghostty_mouse_encoder_set(encoder,GHOSTTY_MOUSE_ENCODER_OPT_SIZE,&geometry);
    ghostty_mouse_event_set_action(event,GHOSTTY_MOUSE_ACTION_PRESS);
    ghostty_mouse_event_set_button(event,button);
    ghostty_mouse_event_set_position(event,(GhosttyMousePosition){(col+.5f)*cw,(row+.5f)*ch});
    r=ghostty_mouse_encoder_encode(encoder,event,(uint8_t *)out,capacity,len);
    if(r==0 && release && *len<=capacity) {
      size_t tail=0;
      ghostty_mouse_event_set_action(event,GHOSTTY_MOUSE_ACTION_RELEASE);
      r=ghostty_mouse_encoder_encode(encoder,event,(uint8_t *)out+*len,capacity-*len,&tail);
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
static int semantic_row(RpTerminal *t,uint16_t y,GhosttyRowSemanticPrompt *kind,bool *continuation) {
  GhosttyPoint point={.tag=GHOSTTY_POINT_TAG_VIEWPORT,.value.coordinate={.x=0,.y=y}};
  GhosttyGridRef ref={0};GhosttyRow row=0;
  int r=ghostty_terminal_grid_ref(t->terminal,point,&ref);
  if(r==0)r=ghostty_grid_ref_row(&ref,&row);
  if(r==0)r=ghostty_row_get(row,GHOSTTY_ROW_DATA_SEMANTIC_PROMPT,kind);
  if(r==0)r=ghostty_row_get(row,GHOSTTY_ROW_DATA_WRAP_CONTINUATION,continuation);
  return r;
}
int rp_frame(RpTerminal *t, RpFrame *frame, CellFn callback, void *userdata) {
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
  frame->cursor_visible=cursor.visible && cursor.viewport_has_value;
  // A restored terminal can have command boundaries while its cursor is in output.
  if(!(frame->input_flags&2) && !t->semantic_scanned && !t->semantic_seen) {
    t->semantic_scanned=true;
    GhosttyPoint point={.tag=GHOSTTY_POINT_TAG_VIEWPORT,.value.coordinate={.x=0,.y=cursor.viewport_has_value?cursor.viewport_y:0}};
    GhosttyGridRef ref={0};GhosttySelection output={0};
    if(ghostty_terminal_grid_ref(t->terminal,point,&ref)==0 &&
        ghostty_terminal_select_output(t->terminal,&ref,&output)==0)t->semantic_metadata=true;
    if(t->semantic_metadata)frame->input_flags|=32;
  }
  int input_start=-1,input_end=-1;
  if((frame->input_flags&16) && cursor.viewport_has_value && scrollbar.offset+scrollbar.len>=scrollbar.total) {
    input_start=input_end=cursor.viewport_y;
    for(int y=cursor.viewport_y;y>=0;y--) {
      GhosttyRowSemanticPrompt kind=GHOSTTY_ROW_SEMANTIC_NONE;bool continuation=false;
      r=semantic_row(t,(uint16_t)y,&kind,&continuation);if(r!=0)return r;
      if(kind==GHOSTTY_ROW_SEMANTIC_PROMPT) {input_start=y;break;}
      if(kind!=GHOSTTY_ROW_SEMANTIC_PROMPT_CONTINUATION && !continuation)break;
      input_start=y;
    }
    for(int y=cursor.viewport_y+1;y<frame->rows;y++) {
      GhosttyRowSemanticPrompt kind=GHOSTTY_ROW_SEMANTIC_NONE;bool continuation=false;
      r=semantic_row(t,(uint16_t)y,&kind,&continuation);if(r!=0)return r;
      if(kind!=GHOSTTY_ROW_SEMANTIC_PROMPT_CONTINUATION && !continuation)break;
      input_end=y;
    }
  }
  memcpy(frame->foreground,&colors.foreground,3);
  memcpy(frame->background,&colors.background,3);
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
      GhosttyStyle style={0};
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
      if(count) {
        GhosttyCodepointBuffer buffer={.ptr=graphemes,.cap=capacity};
        CELL(GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,&buffer);
      }
      // Client-local row metadata occupies bits above the color-role fields.
      uint32_t row_flags=((input_start>=0 && y>=input_start && y<=input_end)?(1u<<30):0)|(1u<<29)|((uint32_t)wrapped<<27)|((uint32_t)continuation<<28);
      uint32_t flags=row_flags | ((style.flags & GHOSTTY_STYLE_FLAG_BOLD) != 0) | (((style.flags & GHOSTTY_STYLE_FLAG_ITALIC) != 0)<<1) | (((style.flags & GHOSTTY_STYLE_FLAG_FAINT) != 0)<<2) |
          (((style.flags & GHOSTTY_STYLE_FLAG_INVERSE) != 0)<<3) | (((style.flags & GHOSTTY_STYLE_FLAG_INVISIBLE) != 0)<<4) | (((style.flags & GHOSTTY_STYLE_FLAG_STRIKETHROUGH) != 0)<<5) |
          ((GHOSTTY_STYLE_UNDERLINE(style)!=0)<<6) | (fg_default<<7) | (bg_default<<8);
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
        GhosttyGridRef ref={0};
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

// Borrowed pixels and placement geometry: copied or reused by Rust before returning.
typedef struct {
  uint64_t generation;
  uint32_t id,width,height,format,layer;
  int32_t z,col,row;
  uint32_t ox,oy,dw,dh,sx,sy,sw,sh;
} RpImage;
typedef void (*ImageFn)(void*,const RpImage*,const uint8_t*,size_t);
int rp_images(RpTerminal *t,uint32_t *cw,uint32_t *ch,ImageFn callback,void *userdata) {
  uint32_t pw=0,ph=0;uint16_t cols=0,rows=0;
  int r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_WIDTH_PX,&pw);
  if(r==0)r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_HEIGHT_PX,&ph);
  if(r==0)r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_COLS,&cols);
  if(r==0)r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_ROWS,&rows);
  if(r!=0)return r;
  *cw=cols?pw/cols:8;*ch=rows?ph/rows:16;
  GhosttyKittyGraphics storage=NULL;
  r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS,&storage);
  if(r==GHOSTTY_NO_VALUE || !storage)return 0;
  if(r!=0)return r;
  GhosttyRenderStateImageIterator images=NULL;
  r=ghostty_render_state_image_iterator_new(NULL,&images);
  if(r==0)r=ghostty_render_state_get(t->render,GHOSTTY_RENDER_STATE_DATA_IMAGE_ITERATOR,&images);
  size_t count=0;
  while(r==0 && count++<4096 && ghostty_render_state_image_iterator_next(images)) {
    RpImage image={0};GhosttyImageProtocol protocol=0;
    GhosttyRenderStateImageData keys[]={GHOSTTY_RENDER_STATE_IMAGE_DATA_PROTOCOL,GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_ID,
      GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_GENERATION,GHOSTTY_RENDER_STATE_IMAGE_DATA_LAYER,GHOSTTY_RENDER_STATE_IMAGE_DATA_Z,
      GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_COL,GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_ROW,
      GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_X,GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_Y,
      GHOSTTY_RENDER_STATE_IMAGE_DATA_PIXEL_WIDTH,GHOSTTY_RENDER_STATE_IMAGE_DATA_PIXEL_HEIGHT,
      GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_X,GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_Y,
      GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_WIDTH,GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_HEIGHT};
    void *values[]={&protocol,&image.id,&image.generation,&image.layer,&image.z,&image.col,&image.row,
      &image.ox,&image.oy,&image.dw,&image.dh,&image.sx,&image.sy,&image.sw,&image.sh};
    r=ghostty_render_state_image_get_multi(images,15,keys,values,NULL);
    if(r!=0)break;
    if(protocol!=GHOSTTY_IMAGE_PROTOCOL_KITTY)continue;
    GhosttyKittyGraphicsImage pixels=ghostty_kitty_graphics_image(storage,image.id);
    if(!pixels)continue;
    GhosttyString pixels_data={0};
    GhosttyKittyGraphicsImageData pkeys[]={GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_WIDTH,GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_HEIGHT,
      GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_FORMAT,GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_PIXELS};
    void *pvalues[]={&image.width,&image.height,&image.format,&pixels_data};
    int result=ghostty_kitty_graphics_image_get_multi(pixels,4,pkeys,pvalues,NULL);
    if(result==GHOSTTY_NO_VALUE)continue;
    if(result!=0){r=result;break;}
    if(pixels_data.ptr && pixels_data.len && image.width && image.height && image.dw && image.dh)callback(userdata,&image,(const uint8_t *)pixels_data.ptr,pixels_data.len);
  }
  ghostty_render_state_image_iterator_free(images);return r;
}
int rp_animation_tick(RpTerminal *t,uint64_t now,uint64_t *next,uint8_t *playing) {
  *playing=0;GhosttyKittyGraphics storage=NULL;
  int r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS,&storage);
  if(r==GHOSTTY_NO_VALUE || !storage)return 0;
  if(r!=0)return r;
  r=ghostty_kitty_graphics_animation_tick(storage,now,next);
  if(r==GHOSTTY_NO_VALUE)return 0;
  if(r==0)*playing=1;
  return r;
}
