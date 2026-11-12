# Android creation protocol observations

Verified against the user's existing owned Tailscale Rex server on 2026-11-01,
API revision 7. The server's advertised schemas were read again before adding
creation. Both methods were exercised through the app's Rust actor and Android
UI in disposable sessions. No server configuration or authentication changed.

The base client/control/data protocol and native Ghostty source provenance are
described in README.md. The app connects directly with authenticated control and
data WebSockets. It does not invoke a Rex executable or a bridge at runtime.

## New tab

UI JNI command: `{"type":"new_tab","session":"session:<id>"}`.
The actor requires this session to still be selected and connected, then sends
`session.new_window` with:

```json
{
  "session_id": "session:<id>",
  "window_label": "Terminal 3",
  "focus": false,
  "layout": {"block": {"flavor": "com.superlogical.terminal.shell"}}
}
```

The label is generated from the current window count. The reply supplies
`window_id`, `block_ids` and `revision`. The app opens the first returned block
with the existing authenticated data-ticket/snapshot/VT path, refreshes the view,
and selects the corresponding local tab. `focus:false` avoids requesting a
change to the server's active window for other clients.

## New pane

UI JNI command: `{"type":"new_pane","session":"session:<id>","anchor":"block:<id>","direction":"horizontal"}`.
**Split right** sends `horizontal`; **Split below** sends `vertical`. An omitted
direction retains the legacy right split; unsupported values are rejected before
creation starts.
The Panes footer uses Open pane and New pane. New pane opens an anchored direction
menu; opening or dismissing it sends no command. The menu closes if the selected
target, tab, section or action availability changes. Both menu choices still bind
the explicit panel-selected anchor. The map has no title row; its floating-layer
visibility control appears only when overlays exist.
The session must still match the actor's selection. The explicit anchor must
exist among that session's known terminal panes; it can be different from the
currently open pane. The panel's local selection is not terminal navigation.
It sends `session.new_split`:

```json
{
  "session_id": "session:<id>",
  "anchor_id": "block:<id>",
  "direction": "horizontal",
  "side": "after",
  "focus": false,
  "layout": {"block": {"flavor": "com.superlogical.terminal.shell"}}
}
```

The reply supplies `anchor_id`, `block_ids`, `split_ids` and `revision`. The app
opens the new block as above. This changes the actual server layout: the new
shell is to the right of the anchored pane for `horizontal`, or below it for
`vertical`, with the server's default split ratio. The phone displays one pane
at a time.

The terminal flavor needs no custom options: it uses the user's default shell.
The advertised `session.view` schema defines layer blocks in layout order; that
order supplies swipe navigation and the pane-position bars. Non-terminal blocks
remain excluded from this terminal-only UI.

Creation progress disables the UI's creation action and pane/tab changes. The
actor never queues a creation for replay during reconnection. If a reply is lost,
the result may be uncertain; view refresh reveals existing resources without
starting the shell again. Stale session/anchor commands are rejected locally
before any server mutation, as verified by native tests.

## Pane layout map

The Panes switcher maps the current window's server layout, rather than assuming
equal-width tiles from block order. The API revision 8 `session.view` schema
defines each `layers[].layout` leaf's `rect` as `[x,y,width,height]` in fractions
of the whole window. Nested `split.before` / `split.after` coordinates are already
absolute within that window; neither the parent rectangle nor floating layer
bounds are multiplied in again.

JNI window metadata includes `layout: [{id,rect,layer,floating}]`, retaining only
known terminal blocks and finite positive rectangles. Android clips rectangles
to the window and draws them in layer order, preserving nested proportions. The
88 dp map uses small program tiles drawn from cached vector paths at their final
pixel size, with the panel's selected target
highlighted. Layout-only updates preserve pane selection and program metadata.
Floating rectangles use translucent fill and dashed borders, retaining their real
geometry and stacking order. A compact Hide/Show floating control removes/restores
only those overlays, allowing normal touch selection on a covered tiled pane.
Toggling visibility does not select, open, close or move anything on the server.
Floating panes remain in the full list with a Floating caption. Visibility
survives geometry refreshes within the same window and can be inspected while
terminal actions are disabled.
Tapping a usable tile or pane list row changes local panel selection only.
**Open pane** navigates; split actions carry the selected target's explicit anchor.
Tabs likewise select locally and navigate with **Open tab**. The selected tab
determines which panes the Panes section manages, without opening that tab.
“Viewing” in row captions identifies the actual open terminal separately from
panel selection. A removed target disables Open/split instead of falling back
silently to another pane. Tiny tiles remain identifiable through the full list. Missing or
incomplete geometry displays “Layout unavailable” instead of inventing a grid;
the pane list and split actions remain available. The terminal still displays
one pane at a time. This adds no polling or server mutations.

## Reordering from the tab/pane panel

Hold-and-drag reorders either vertical list. Ordinary swipes scroll, and held
movement is consumed before row selection; release commits only a changed order.
Edge holding scrolls automatically. Cancellation, disabled actions, changed list
membership/order or session/window identity discard the drag. Map tiles and other
non-row content cannot begin a reorder. TalkBack rows expose Move up/down actions.

Tabs use the existing `session.move_window` command and acknowledgment path.
Pane commands bind `{session,window,id,before,blocks,requestId}`. Native code reads
a fresh view and requires the exact expected terminal-pane order in that window,
then computes swaps to insert the pane before the target (empty before means the
end). Each `session.swap_blocks` receives explicit `session_id`, `block_id` and
`other_block_id`. The server's revision-8 schema specifies that swaps retain split
IDs/ratios, floating layers and window order. Programs keep their block IDs. Focus
can follow a moved program on the server; the phone never navigates, takes control
or sends a resize as part of reordering.

The UI projects the pending pane list order while retaining reported geometry;
the refreshed server view supplies the actual updated map. `paneMoveId` acknowledges
success or failure so that stale optimistic state is discarded. Swaps are sent
sequentially and never automatically replayed. A failed sequence refreshes the
actual layout and reports a potentially partial move, rather than retrying an
operation that might undo an already-applied swap. This is server layout order,
not a separate phone-only preference.

## Read-only carousel previews and session details

The actor polls cancellable preview futures on its existing owner thread. A
preview uses a separately labelled authenticated control connection, attaches
the current session, obtains the normal data ticket and decodes the real Ghostty
snapshot through FINISH. It then closes the data/control connections. Preview
clients never take ownership or send input. At most two neighbors are requested
per update, with a bounded timeout. One-shot preview frames remain queued until
JNI polling consumes them; ordinary live-frame publication cannot overwrite them.

Session counts come from attached `session.view` responses. Attachment state
comes from `client.list` with kinds `["app"]` and its session_ids, connection
state and authenticated device node. The current client and labelled internal
preview/summary clients are identified separately. The UI shows known values
and omits unavailable remote usage dates. New Session calls verified
`session.create` with one `com.superlogical.terminal.shell` initial window.

Local navigationId values correlate UI requests with completed native selection;
they are not Rex wire fields. Native cell flags retain default-color and palette
roles so Android can render System/Dark/Light while preserving explicit RGB.
Session and pane snapshots are not persisted to disk.

## Terminal appearance and Neovim

Verified on the owned revision-7 server: `com.superlogical.terminal.set_theme`
uses `{session_id, block_id, args}`. `args` contains `scheme: "dark" | "light"`,
`background`, `foreground`, `cursor` as `#RRGGBB`, and `palette` as strings such
as `"0=#202432"` (Android sends entries 0–15). The return value is `{}`.
Rex applies these colors immediately if the caller owns the block or no client
owns it, and otherwise queues them until that client becomes owner. This is the
same ownership policy documented for terminal resize.

Compose sends its resolved System/Dark/Light palette to the native actor. The
actor retains it across disconnect/pause, registers it before opening/resuming
the selected data stream, and reapplies it on takeover and appearance changes.
It sends no theme requests for neighbor previews or unselected panes. The
canonical server terminal answers program color queries and notifies programs
of scheme changes. Android's Ghostty replica still has no WRITE_PTY callback;
it never injects its own OSC replies into the remote PTY.

The live actor regression runs a disposable `nvim --clean -n -i NONE` with a
statusline showing `&background`. On installed Neovim 0.12.6, changing appearance
updates that value light → dark → light, including pause/resume and takeover
from another client. A non-owner's pending light theme leaves the current
owner's dark theme in effect. The test never sets Neovim's background option.
All disposable sessions are destroyed. Neovim configurations that force their
own background/colorscheme can override automatic detection.

Reproduce the native test (requires an owned server with `nvim` on PATH):

```sh
REX_TEST_SERVER=https://rex.taildaac1.ts.net \
GHOSTTY_PREFIX="$PWD/.build/ghostty-host" \
CARGO_TARGET_DIR="$PWD/.build/rust" \
cargo test --locked --manifest-path native/Cargo.toml \
  live_neovim_theme_follows_ownership_and_survives_pause -- --ignored
```

Neovim's automatic color response behavior is documented in its
[defaults implementation](https://github.com/neovim/neovim/blob/master/runtime/lua/vim/_core/defaults.lua)
and [`background` option](https://neovim.io/doc/user/options/#'background').


## Program icons and working directories

Verified read-only terminal methods: `com.superlogical.terminal.process` and
`com.superlogical.terminal.program_status`, each with
`{session_id, block_id, args:{}}`. `process.foreground` supplies executable name,
argv0, resolved/invoked executable paths, optional script and cwd. `process.home`
provides the actual home path; abbreviation checks a complete path boundary.
Missing foreground cwd stays blank rather than displaying the shell's old cwd.
When foreground itself is unavailable, child metadata provides the fallback.

`program_status.records` may expose app/effective_app identifiers. Android uses
those only when record.owner matches the current foreground **pid and name**;
a record from an exited program cannot incorrectly label the next program.
Reports remain program-supplied hints, not trusted identity. The app selects
bundled vector assets by known identifiers. No server-supplied image is fetched
or executed. Unknown programs fall back to a generic terminal symbol.

Cloudflare is recognized from `cloudflared` executable names/paths and the
`cloudflare` app identifier. Header/tab tiles use the supplied catalog Cloudflare
vector; inline program glyphs use its retained monochrome SVG. Display titles
alone never choose program icons. The layout map uses the same program artwork
as the list, without titles or numbers. Unknown metadata uses the existing
neutral loading tile until an actual program is known.

A separate authenticated, labelled `rex-android-programs` helper reads metadata
for the selected session's panes. It never owns blocks, writes input, resizes,
or sets themes. Its future runs alongside live input/output on the actor's
existing runtime. Refresh is approximately every four seconds and is brought
forward by selected-pane process/status APCs. Queries are bounded to 64 panes
and eight seconds; metadata is cancelled on session change, pause and disconnect.
Closed/replaced pane lists discard stale metadata. Helper clients are excluded
from user-facing attachment summaries.

JNI state carries `programs` (block ID → executable identifier candidates) and
`directories` (block ID → display cwd). It omits process arguments, other
process/account details and persists neither collection. Header/tab stacks show
up to three icons, with the selected or remembered pane in front. All panes stay
available in the picker. Inner programs behind SSH/tmux are only identifiable
when the server supplies suitable process/report information.

The native regression `live_program_icons_follow_reports_in_selected_and_background_tabs`
uses disposable OSC 7501 fixtures for Claude/Codex app reports, changes and clears
them, and verifies that a background tab refreshes without selecting or owning
it. These are protocol fixtures, not launched coding agents. The existing live
Neovim test also verifies actual foreground recognition. Identifier mappings,
stale owners, home-path boundaries and missing/invisible directory text have
native and Kotlin tests.

SVG paths are from Simple Icons 15.0.0 (CC0), converted to Android vector assets.
Pinned source URLs/checksums and the license ship in APK assets under `licenses/`.
The Android label and control client name are Rexlet/rexlet. The package remains
`dev.shulhi.rex`, preserving the existing installation, preferences and JNI ABI.


## Local scrollback and edge rendering

The pinned libghostty-vt exposes `ghostty_terminal_scroll_viewport` and
`GHOSTTY_TERMINAL_DATA_SCROLLBAR`. Android now uses these on its existing
replica. Scrollbar total/offset/visible counts are exported with each frame.
Local `scroll` JNI commands carry the expected block ID; stale targets are
ignored. Scrolling sends no Rex request or PTY bytes and requires no ownership.
The native snapshot retains history, not only the live screen. New VT remains
applied while the history viewport is anchored. Input and paste first return
the local viewport to the active area; reconnect with a new replica starts live.
The alternate screen has no terminal history; application-level scrolling is
not emulated by these local history gestures.

A native round-trip test encodes/imports a 40-line history, scrolls to the top,
adds live output without moving the viewport, returns to the bottom and verifies
alternate-screen behavior. The owned-server actor test imports 80 history lines,
scrolls without ownership, receives new output while anchored, then takes control
and types to verify return to live output. Android dragging distinguishes vertical
history scrolling from horizontal pane navigation. Pixel movement accumulates
into whole terminal rows; flings use Android OverScroller. The Latest control and
Shift+PageUp/PageDown/mouse wheel also use the local viewport API.

Padding moved into TerminalView so background spans can extend the first/last
visible rows and columns to the rounded card edges. Each adjacent color is
preserved separately, including inverse and wide-cell styles. Glyphs/cursor stay
clipped to the content rectangle. Resizes exclude the insets and footer space.


## Multiple direct servers and shared session navigation

Each saved host owns an independent native Session actor, control/data sockets,
resume state and Ghostty replica. There is no multiplexing proxy or new server
API. The coordinator saves host profiles and routes server-qualified local UI
keys to the correct connection. Identical session IDs on different hosts cannot
collide. Only the original Rex session ID is sent to that host. The header and
search picker flatten connected hosts in saved order; disconnected hosts are
omitted. Long-press history can return to a session on another host.

Switching the visible host releases the old block claim, keeps its sockets and
terminal state, and blocks stale hidden-view input/resize callbacks. Hidden
hosts continue polling at a lower cadence. Foreground/background lifecycle
applies to every connected actor. Releasing without a selected block is ignored.
Disconnect/Forget only affect the selected local connection/profile; they never
delete remote sessions. New sessions are created on the currently viewed host.

Integration used two distinct existing owned endpoints:
`https://rex.taildaac1.ts.net` (Linux, server API 7) and
`https://shulhi-mbp-rex.taildaac1.ts.net` (Mac, server API 9, accepted the existing
7–8 client negotiation). Both authenticated directly over the existing Tailscale
HTTPS listeners. No server configuration or authentication settings changed.

Reproduce the native two-host test with explicitly owned servers:

```sh
REX_TEST_SERVER=https://rex.taildaac1.ts.net \
REX_TEST_SECOND_SERVER=https://shulhi-mbp-rex.taildaac1.ts.net \
GHOSTTY_PREFIX="$PWD/.build/ghostty-host" CARGO_TARGET_DIR="$PWD/.build/rust" \
cargo test --locked --manifest-path native/Cargo.toml \
  live_two_servers_isolate_input_selection_and_lifecycle -- --ignored
```

The native test creates and destroys its own sessions. Android MultiServerTest
requires two pre-created disposable shell fixtures passed as instrumentation
arguments `server_a`, `server_b`, `session_a`, `session_b`; it intentionally skips
without explicit fixture arguments. It writes only to those fixtures and checks
host-isolated input, hidden-view rejection, shared session routing/jump-last,
independent disconnect and lifecycle recovery. Destroy fixtures after running.


## Notified local display updates (RXT1)

This is a private JNI transport inside Rexlet, separate from Rex's control/data
wire formats. Rex authentication, snapshots and VT streams are unchanged.
`awaitUpdate(handle, afterRevision, timeoutMs)` waits on a condition variable
without holding the native handle registry. Publish and destroy wake readers.
`readUpdate` coalesces to the newest state, with one decoder per handle. JNI
commands and the legacy diagnostic `poll` API remain JSON; production live
screen delivery uses UTF-8 binary changed rows.

The little-endian packet begins `RXT1`, revision u64, metadata length u32 and
metadata UTF-8 bytes. Unchanged metadata has length zero. The frame flag is:
0 unchanged, 1 no frame, 2 row delta, 3 full replacement. Flags 2/3 carry columns
and rows u16, cursor x/y i32 (-1 hidden), foreground/background ARGB u32, then
scroll total/offset/visible u64. A changed-row count u16 follows. Each row has
its y and cell count u16; cells contain x u16, width u8, attributes/foreground/
background u32, and length-prefixed UTF-8 text (u32 length). Unchanged rows retain
their Kotlin list identity. Missing rows are empty in full replacements.

The encoder compares against the last delivered frame, so skipped native
revisions are safe. Unknown bases, dimensions or pane identity changes force a
full frame. The decoder validates bounds, row uniqueness, lengths, flags and
revisions before committing its new base. Preview snapshots remain one-shot
bounded JSON metadata and are not repeatedly attached to live frame updates.
No reconnect tokens, data tickets or terminal process arguments are added.

A visible native actor coalesces extraction at 16 ms while dirty; clean actors
only perform maintenance. A hidden actor keeps applying incoming VT and updating
session metadata but does not extract screen frames. Selecting it requests a
fresh frame. Android waits for revisions on IO, decodes away from the UI thread,
and publishes visible frames on the display clock. Metadata flows omit screen
and revision churn. Pane movement reads translation in the graphics layer.

## Closing sessions from the picker

Installed server API discovery confirms `session.destroy {session_id}` closes
all windows/blocks and hangs up their programs. The caller must be attached;
missing sessions fail with `session_not_found`. A short-lived authenticated
`rex-android-close` control client attaches to the explicitly confirmed target,
sends destroy once and closes. It takes no block ownership and does not move
the main client's viewed session when closing an inactive session. Errors or
uncertain replies are reported, never automatically replayed. The normal
session list/events synchronize all connected clients afterward.

The UI binds confirmations to both server endpoint and server-qualified session
ID, rejects stale targets, and prevents duplicate closes while one is pending.
Both swipe directions reveal Close; the row menu and accessibility action offer
the same operation. Cancel sends no mutation. The previous-session marker is
plain text and the old footer gesture hints are removed. Closing the current
session opens another available session through the existing selection logic.
Disconnect and Forget remain local connection/profile actions in Servers.


## Application wheel input, takeover focus and viewport fitting

Private JNI packets now use RXT2. An input-flags u8 follows the three scrollbar
u64 values: mouse reporting=1, alternate screen=2, alternate scroll=4, application
cursor keys=8. The decoder also accepts historical RXT1 packets with flags zero.
These flags come from the pinned Ghostty public C API, including imported
snapshots; no program-name guessing decides whether a gesture sends input.

Local history scrolling remains independent of ownership. A live, visible pane
that negotiates mouse input receives Ghostty-encoded wheel press events with
coordinates and cell geometry. Without mouse reporting, alternate-scroll mode
uses cursor-key sequences according to DECCKM. The selected server/session/block
must match. The first application scroll takes ownership, fits the requested
phone grid, and writes the wheel batch once; uncertain writes are not replayed.
The keyboard opens only for an explicit content tap or keyboard/takeover action.
Horizontal pane swipes and local history drags do not request keyboard activation.
The existing UTF-8 terminal.write interface remains the transport; non-UTF-8
legacy mouse reports are rejected instead of corrupting input.

Takeover reapplies the resolved theme, then sends the verified block.event focus
payload `{type:focus,payload:{action:in}}`. This lets Neovim re-query the canonical
terminal background even when set_theme deduplicates an unchanged theme. The
replica still installs no WRITE_PTY callback and never fabricates OSC replies.
The approved Mac Neovim config resolves the owning client before choosing
appearance: native Mac Rex uses macOS appearance, while Rexlink control follows
the canonical terminal. Other non-Rex Mac terminals keep OS-based behavior.
Existing editors must restart to load it.
Clean Linux and configured Mac theme assertions pass through takeover/resume.
The tested Mac command-session process metadata reports /usr/bin/login rather
than nvim; exact foreground recognition is asserted on the Linux fixture only.

The user's font size stays fixed. An explicit content tap takes control and sends
the requested phone columns/rows with the takeover command, resizing the canonical
PTY so full-screen programs redraw/reflow. Opening a tab does not take ownership;
a wider terminal owned elsewhere can remain cropped until the user takes control.
When a neighbor preview becomes active, dimensions are replayed even if its View
size did not change. Regressions verify the fixed font and requested grid. The
pane indicator reserves 14 dp, down from 22 dp, reducing the gap above its dots.


## Task titles, read-only appearance and stale session closure

`com.superlogical.terminal.title` returns `{title,owner:{pid,name}}`, and
`program_status.records` can supply a root task title. The client prefers the live terminal title, then an owned root task record;
both must match the current foreground process by PID and name. Old shell/agent titles cannot label a new program.
Titles are bounded plain text; executable metadata independently selects icons.
The existing helper refreshes title metadata on `title_changed` and program
status/process events, with the periodic refresh as fallback. Header/default tab
labels follow the remembered focused pane. Explicit custom tab names remain.
OSC 7501 `title` values in fixtures are base64 UTF-8, as specified in the
[official protocol](https://www.superlogical.com/rex/docs/build/program-status).

Appearance is now sent only while Rexlink owns the pane. Merely viewing an
unowned pane must not overwrite its canonical colors. An observed Mac pane was
owned by native Rex Beta and rendered in dark macOS appearance, but retained
Rexlink's prior light palette in the server; its Neovim startup selected light.
The local Neovim helper reads ownership using the installed Rex CLI and applies
macOS appearance for native Mac Rex. Other owners use OSC 11. Async results are
generation-checked so a late OS lookup cannot override a later Android focus.
This optional local editor workaround is separate from the Android client's
independent direct connection; no bridge or server configuration is added.

Closing a session that disappeared is idempotent for the typed server error
`session_not_found` from attach/destroy. The catalog refreshes and stale selection
clears. Other errors remain errors; no uncertain destructive request is retried.

The extra-keys chevron beside Esc expands F1-F12 plus Home/PgUp/PgDn/End/Ins/Del.
F1-F4 use SS3 P/Q/R/S; F5-F12 use CSI 15/17/18/19/20/21/23/24~. Collapse or hiding
the system keyboard resets the horizontal strip to Esc. Input still requires
ownership. The pane overlay no longer arms an untimed toast when a swipe settles
back to the same pane.


## Shell identity, working state and keyboard reveal

Known shell executables (fish/zsh/bash/etc.) keep their executable name as the
header/default tab title. Their OSC title often repeats cwd, which already has
a dedicated directory caption. Non-shell applications retain their reported task
titles, and explicitly named tabs remain unchanged.

Program status records with state=working and a matching foreground PID/name
supply the `working` block-ID list in local metadata. Idle/blocked/done/error,
cleared records and stale owners do not animate. No title-text heuristic is used.
The active tab's existing blue underline gets a moving highlight. Program
icons stay static; their earlier pulse was removed. No indicator adds width. Animation values are consumed in drawing/layer updates, and idle
programs have no animation clock. Captions use explicit line heights without
font padding so the session chevron aligns with the small text.

The keyboard's leading chevron starts offscreen before Esc. A rightward swipe
reveals it; collapse or hiding the IME restores the offset that hides it again.
Normal keys share the available width, with a slightly wider Ctrl key and 3 dp
gaps. The normal row has a minimum width for narrow displays and remains
scrollable. The existing key palette and disabled-state opacity are preserved.


## Streamed titles, selection and catalog assets

The verified control envelope is a session_event whose payload.event is
`{type:block_event,payload:{block_id,creator_name,name,payload}}`.
Terminal title_changed payloads contain the title and owning process PID/name.
They update presentation directly for all panes in the attached session. The
selected pane's nonce-verified data APCs feed the same handler. Events queued
while awaiting RPC replies are processed rather than discarded. Title frames
need no per-frame metadata RPC. Latest streamed titles are merged over older
poll results, and process changes invalidate titles from the previous owner.
Unicode animation characters remain part of the title; no spinner is invented
or parsed out of it. An empty title is respected as a clear.

Long press freezes the displayed terminal screen for selection, selects a word,
and offers native Copy / Select all actions. Selection handles can extend or
reverse the range. Copy uses plain cell text and includes wide glyphs once,
retaining spaces and physical line breaks. Selection never acquires ownership,
sends terminal input or opens the keyboard. Dismissal/pane departure releases
the frozen screen. The existing Copy viewport menu remains available.

The pane picker now uses the current program tile, live title (or executable
name), directory caption, and selected-pane marker. The supplied Superlogical
catalog SVGs are retained under artwork/program-icons/superlogical with hashes
and attribution. A local WebKit converter renders their SVG masks, gradients
and Display-P3 colors into packaged PNG tiles. Runtime Android rendering needs
no browser or network request. Supported kinds use those tiles in the header,
tabs, pane picker and swipe preview; unsupported kinds retain their fallback.
The user's final transparent launcher PNG is copied exactly, preserving alpha.


## Closing tabs and panes

Verified `block.close` with explicit `session_id` and `block_id`, and `session.close_window` with explicit `session_id` and `window_id`. A tab close ends every program in the window; closing its final pane also removes that window. The session can remain empty, and `session.view` may omit `windows` then.

Commands bind the selected session and explicit target. Tab confirmation also binds the pane-ID list and rejects changed membership before sending. Typed `not_found` / `session_not_found` are treated as already gone; uncertain network failures are not retried. Background closes preserve the active stream and control. Closing the selected target opens a neighbor read-only, without resizing or taking ownership.


## Shift and navigation keys

Shift+arrows, Home/End, editing and function keys use xterm modifier parameters (1 + Shift + 2*Alt + 4*Ctrl); Shift+Tab sends CSI Z. Printable Shift applies ordinary ASCII case/symbol mapping. Reference: [xterm PC-style function keys](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html#h2-PC-Style-Function-Keys).

Shift can latch for one key or remain held for multiple keys. Cancellation, pane/session departure, hiding a server, loss of control and pause clear it. A late finger-up cannot relatch cleared Shift. The shared navigation strip scrolls independently; scrolling and cancellation send no key. Hardware named keys also carry modifier parameters.


## Visible web links in the Android renderer

Web URL detection is local presentation logic; no new Rex method or server configuration is involved. Visible HTTP(S) and www-prefixed URLs are mapped from UTF-16 text back to terminal cell ranges, excluding wide-character spacers, hidden text and surrounding punctuation. Row-record caches retain underline/link ranges and track adjacent-row continuation changes only for boundary links.

A deliberate tap opens an HTTP(S) ACTION_VIEW/BROWSABLE intent; pointer-down and pointer-up must still identify the same link. Scrolling, swipes, cancellation and selection never launch it. Browser launch does not acquire terminal ownership or write input, and missing handlers are handled locally. [Android intent reference](https://developer.android.com/training/basics/intents/sending).

Printed URLs now join only across confirmed soft wraps, using row metadata described below. OSC 8 destinations are exported explicitly and their labels can span rows. Localhost is not rewritten.


## OSC 8 metadata and RXT3

The pinned public Ghostty C API exposes GHOSTTY_CELL_DATA_HAS_HYPERLINK and ghostty_grid_ref_hyperlink_uri. Only linked leading cells perform viewport grid-reference lookup; copied destinations are bounded to 64KiB. No extra server methods or configuration are required. Native cells carry an optional URI, so changing only the destination invalidates the row.

Local JNI packets now use RXT3. Its header, input flags and cell records match RXT2. Each changed row appends u16 link-range count followed by sorted, non-overlapping ranges: u16 start column, u16 exclusive end, u32 UTF-8 length and URI bytes. Adjacent cells sharing one URI coalesce. Plain rows cost two extra bytes. Android still accepts RXT1/RXT2. Full frames and row deltas both carry ranges; zero ranges removes old links. JSON preview cells append an optional eighth URI element.

Explicit OSC 8 destinations take precedence over visible URL-shaped labels; automatic matching is masked inside explicit links. Only HTTP(S) targets are opened. Known OSC 8 targets remain usable when labels wrap across rows. Printed URL continuations use explicit row flags instead of guesses. Long-press continues selecting the label text. Snapshot, streaming, destination-only replacement/removal and resume were verified on both owned servers.


## Wrapped printed URLs and local link actions

The public Ghostty render-row RAW value exposes GHOSTTY_ROW_DATA_WRAP and WRAP_CONTINUATION. The C shim adds client-local attributes bits 27 (wraps into next row), 28 (continuation of preceding row) and 29 (row metadata present) to each row's cells. These sit above palette-index bits 0–26, use the existing RXT3 u32 attributes field and naturally participate in row-delta comparison. Snapshot restoration preserves them. No Rex wire format or server setting changes.

Android joins adjacent rows only when both the preceding wrap and following continuation flags are present. It maps the full detected destination back to separate physical hit/underline ranges. A logical-line cache tracks all its source rows, so replacing a wrapped tail updates unchanged prefix hit ranges. Hard newlines remain separate. Incomplete viewport fragments are not guessed; older packets retain conservative single-row matching. Explicit OSC 8 links still take precedence.

Long press freezes both text and link target. The native floating selection toolbar adds Open link, Copy link address and Share link while retaining Copy/Select all. Copy address uses the clipboard; Share creates an Android ACTION_SEND/text/plain chooser containing only the URL. Merely opening the chooser sends nothing. Actions do not take ownership or type terminal input.

Hold-Ctrl shortcuts are local UI controls. A short tap keeps one-shot Ctrl behavior. A long press displays eight labels without descriptions; moving highlights a choice and release sends exactly its control byte, guarded by current ownership/navigation state. Outside release/cancellation sends nothing. No descriptions or automatic shortcut execution are inferred from terminal content.


## Program activity snapshots (2026-11-02)

Official program-status documentation: https://www.superlogical.com/rex/docs/build/program-status . Installed Linux API 7 and Mac API 9 expose terminal `program_status`, including root/child records, `effective_app`, `owner`, `state`, `kind`, `msg`, `progress`, and `updated_at`. States are working, blocked, done, error and idle. Done/error reports intentionally survive process exit. Live owned reports are matched against the foreground pid/name; an exited process does not hide its completed report.

No dedicated activity feed was discovered. A separately authenticated read-only control connection lists sessions, attaches their views and walks `view.windows[].layers[].blocks[]`. Only `com.superlogical.terminal` blocks are queried. Status is requested before process/title; no records means those additional queries are skipped. The helper never opens PTY data, takes ownership, writes input or resizes. Its client label is `rex-android-activity` and is excluded from user attachment summaries.

While foregrounded, each connected server gets a read-only Activity baseline after connecting, when opening or refreshing the sheet, and after structural/session changes. The primary control stream uses `session.attach_all` with `event_scope: "all"` to receive off-session block events. Status, process and exit events queue targeted refreshes for known panes; unknown panes request a new baseline. No fixed Activity polling timer remains. At most one scan runs at once, with a one-second minimum refresh interval and five-second retry after scan failure. Closing the sheet keeps status monitoring active but hides the catalog from JNI. A full scan uses passive `session.view`, has a 15-second timeout, ten-second traversal budget and caps of 64 sessions, 256 panes and 1,024 records. Errors retain the last snapshot; partial scans are identified. Selecting an entry carries server, session and block identity through navigation and opens the exact pane read-only. A missing pane reports that activity needs refreshing.

Reproduce against an owned server with `REX_TEST_SERVER=https://your-rex-server GHOSTTY_PREFIX="$PWD/.build/ghostty-host" CARGO_TARGET_DIR="$PWD/.build/rust" cargo test --locked --manifest-path native/Cargo.toml --workspace live_activity_ -- --ignored`. This creates and removes only its own two fixture sessions, verifies root/child reports across sessions, exact-pane navigation and unchanged ownership/dimensions.

Root activity labels prefer a nonempty terminal OSC title validated against that report's foreground owner. Existing title events update matching root records in the attached session immediately and are reapplied after slow scans. Child tasks retain their own titles; exited-process results cannot inherit a new foreground program's title. Off-session title events update visible Activity records immediately. The sheet strips a leading animated braille spinner because its status is already explicit; terminal/header/tab titles retain the live animation.

The native Activity cache is retained when the sheet closes, but its report array is excluded from ordinary JNI metadata while hidden. Reopening republishes the cache and starts a fresh scan. While visible, the immutable catalog snapshot is sent only when its contents change. Missing `activity` in incremental JNI metadata means retain the prior list; explicit null on closure/reset clears it. This avoids catalog serialization/parsing on each streamed title frame without changing the Rex wire protocol.


## Active-pane ownership policy (2026-11-02)

Android now claims the active pane once after navigation is acknowledged, its terminal frame exists and positive phone dimensions are available. This supersedes tap-only ownership in the Android UI, including Activity-entry navigation. The lower-level native open operation remains read-only until an explicit `take` command is sent. Automatic takeover does not set the soft-keyboard request. Keyboard focus follows the controlled pane.

Taking control still uses `session.own_block`, applies appearance/focus and resizes the shared PTY. Bound local take/resize commands carry session, block and navigation ID; stale commands, hidden connections and paused clients cannot affect the newly selected pane. Pending resize and held-key gestures cancel on navigation. An old owned frame does not consume a new activation. A claim is attempted only once per activation; another client's takeover does not start an ownership fight. Explicit Release preserves viewing mode, including resume, until another navigation.

The existing old-pane disown on block/session changes is retained. Hiding a server's workspace explicitly disowns its selected pane; lifecycle pause makes a bounded two-second disown attempt before dropping connections. Background connections cannot claim ownership.


## Full-app preset themes and ownership ordering (2026-11-02)

The installed Mac application bundles 41 themes in `SuperUI_SuperDesignTokens.bundle/Contents/Resources/themes.json`: 27 dark and 14 light. No catalog-listing or selected-theme sync method was found in the installed server discovery. Android therefore bundles the numeric palette projection in `app/src/main/assets/themes/rex-builtins.json`, with source hash and conversion provenance in `SOURCE.json`. `scripts/import_rex_themes.py <themes.json>` reproduces it. The runtime needs neither the Mac app nor an additional network service. Android retains its original light/dark **Rexlink Default** presets; the chosen light and dark IDs are local preferences.

Built-ins include semantic UI tokens and all 256 terminal colors. Display-P3 data is converted and gamut-clipped to the renderer's sRGB representation using the transfer/primary conversion described in https://www.w3.org/TR/css-color-4/#color-conversion-code . Colored-button foregrounds are adjusted to black/white only if clipping drops their contrast below 4.5:1. Named presets need not match a wide-gamut Mac display exactly. The default blue presets are unchanged. Catalog parsing is cached, and default startup avoids loading it until needed.

The existing verified `com.superlogical.terminal.set_theme` path sends explicit background/foreground/cursor, scheme and indexed palette values only while owning a pane. The app independently uses the preset's semantic UI colors and cursor-text color. ANSI/indexed cells follow the chosen palette; explicit RGB program colors are preserved. Light/dark preference changes retain the existing ownership, focus and appearance-notification handling. The selected theme name does not switch a program's separately configured colorscheme.

Control-connection views/events are the source of ownership state. Terminal data/APC and control streams have independent arrival order: a delayed resize APC can describe a previous owner after takeover succeeds. Grid resizes are still consumed, but their owner field no longer overwrites canonical ownership. A queued layout resize is silently dropped if ownership has ended. Typing remains rejected while unowned and is never replayed. Confirmed ownership clears only the specific stale ownership error, preserving unrelated uncertain-input errors. Lifecycle regression fixtures test repeated takeover/resume cycles and another client's control takeover.


## Activity attention, reconnect feedback and contextual control (2026-11-02)

Blocked/error reports produce a scalar attention count even with the sheet closed. The eye displays a dot when any connected server has unseen attention. Opening a record or choosing its checkmark/Mark seen acknowledges its current version in local preferences keyed by endpoint. Report identity uses session/block/report ID; the version hashes state/kind/update time/message/owner PID, excluding animated title changes. A changed report can alert again. Seen reports and completed work rank below current work; acknowledgments do not mutate Rex server reports.

The initial control handshake is a cancellable actor task. Retry now and foreground Android default-network callbacks cancel stale connections and reset retry delay. Wi-Fi/cellular/VPN transport changes are tracked without requiring public-internet validation for a private Tailscale connection. `ACCESS_NETWORK_STATE` is a normal Android permission. Disconnection pauses input; uncertain input is never queued or replayed. A connection epoch re-arms the existing active-pane policy after real reconnection while preserving explicit viewing mode. Reconnect and brief Ready-to-type feedback overlay an existing terminal so they do not change PTY dimensions.

The permanent header Take/Release icon is removed. Both actions remain in the overflow menu. If canonical session views show another owner, a delayed contextual pill shows `Sized by another client · columns×rows · Take over`. Automatic navigation still claims a pane only once; this notice does not start an ownership fight. It disappears immediately when the owner/readiness changes, including cancelled activation.


## Codex Action required versus Rex program status (2026-11-02)

The user reported Codex showing “Action required” while the Activity row says
Working. Read-only inspection of current owned-server `program_status` and title
metadata returned Codex root states working/done without a blocked kind; no
structured approval signal was captured for that reported case. The attention
badge deliberately follows unseen blocked/error reports, rather than guessing
from terminal text. A live disposable blocked-report UI test verifies the full
native-to-RexModel pipeline, actual visible badge pixels, and acknowledgment.
This confirms normal blocked reports render; it does not resolve a missing
upstream working-to-blocked transition from Codex integration.

Codex documents a PermissionRequest lifecycle hook for approval requests at
https://learn.chatgpt.com/docs/hooks#permissionrequest . A supported integration
could publish the corresponding Rex blocked status, but no Codex hooks or server
configuration were changed, and that integration has not been implemented or
validated here. The Android client cannot infer the hidden approval state from
a `working` report alone. Follow current build paths in the repository-root
BUILDING.md; older protocol entries retain their original paths.


## Session labels (2026-11-02)

Installed discovery verifies `session.set_label` with `{session_id, label}` and
revision-only response. The server trims names, permits at most 128 Unicode
characters, rejects control characters, and emits `session_label_changed` then
`session_view_changed`. Empty labels restore the generated default; Android's
Rename dialog requires a nonempty name and does not expose reset in this release.

Android's `rename_session` command binds to its connection's endpoint and exact
session ID. A short-lived `rex-android-rename` control client attaches to that
session, calls `session.set_label`, closes, then refreshes `session.list`. It does
not open data, own/disown panes, or resize a terminal. Label events refresh the
combined session strip/list, including changes from another client. Session IDs,
server settings and authentication stay unchanged. Uncertain outcomes are not
replayed; the user can inspect the returned session name before trying again.

The user later confirmed that the Activity badge is visible. The earlier
Working/Action-required explanation was not a reproduced causal diagnosis.
The clipping adjustment only changed geometry; this release does not change
badge logic.


## Ordered input without blocking streamed output (2026-11-02)

The actor previously awaited each input RPC inside its command handler. During
that wait, it could not process data-stream VT or render deadlines. Control now
tracks requests by ID and dispatches replies to individual waiters. Input sends
return after the WebSocket send, leaving the actor free to receive VT, render,
process control events and accept more input. Ordinary dependent RPCs retain
the existing awaitable `call` interface. Event sequence checks, timeouts and
typed RPC errors remain intact.

**Do not pipeline `com.superlogical.terminal.write`.** Rex documents general
block methods as concurrent. A disposable raw-PTY test on Linux demonstrated
reordered bytes when eight such writes overlapped. The Mac run happened to
preserve them; that does not establish an ordering guarantee.

Production typing instead uses `block.event`, which Rex explicitly serializes
on each control connection. Its event is:

```json
{"type":"key","payload":{"key":"unidentified","mods":"","consumed_mods":"","text":"<already encoded input>","unshifted_codepoint":0,"composing":false,"action":"press"}}
```

The surrounding RPC payload identifies the session and block. This sends the
existing Android-encoded text/control/escape bytes directly to the block and
bypasses Lua key bindings, matching the previous raw-write behavior. Paste
continues to use the distinct `paste` event and Rex’s bracketed-paste handling.
Raw PTY capture on both owned servers verifies byte-for-byte ordering of eight
outstanding key events, long UTF-8 text, emoji, every C0 control byte, DEL, arrow
sequences, bracketed paste and subsequent typing.

The input window is bounded to eight requests. The local work queue is bounded
to 128 commands and 1 MiB of text, in addition to the existing bounded JNI
channel. Already queued adjacent plain text for the same activation can combine
up to 4 KiB, with no timer or deliberate delay. Control characters, pastes,
navigation and other commands remain boundaries. Paste waits for preceding
acknowledgments and blocks subsequent typing until its reply. Other commands
also wait for outstanding input; lifecycle/network cancellation can interrupt.

Android stamps input with session, block, navigation ID and connection epoch.
The actor rejects stale bindings before sending. Disconnect and ownership
rejection clear unsent typing, and a transport failure drops pending requests.
Uncertain input is never retried, including after reconnection. An error does
not automatically fight another client's ownership.

The screen still streams over `/api/data`; no screen polling, speculative local
echo, server configuration, or authentication changes were introduced. Network
round-trip time and Tailscale direct/relay routing still affect the earliest
possible remote echo. These changes remove client-side serialization and
render starvation; they do not remove cellular network delay.

Reproduce the live byte-order check from the monorepo root:

```sh
REX_TEST_SERVER=https://<owned-server> \
GHOSTTY_PREFIX="$PWD/rexlink-core/.build/ghostty-host" \
CARGO_TARGET_DIR="$PWD/rexlink-core/.build/rust" \
cargo test --locked --manifest-path rexlink-core/Cargo.toml \
  --test control_pipeline live_ordered -- --ignored --nocapture
```

Only disposable fixture sessions are created and destroyed.


## Completion alerts and compact drawers (2026-11-05)

The Activity unread count includes unseen done, blocked and error records. Working
and idle do not badge. Done reports can be marked seen individually or together;
Needs you still filters only blocked/error, and Finished filters done. Stable
attention versions exclude title animation, so changing titles does not relight
a seen completion. The dot sits above the eye’s upper-right edge, outside its
background clipping layer. The eye stays neutral; only its unread dot uses the
accent color. Activity rows use state bars instead of status words: solid blue for done, a
blue bar with the tab’s moving highlight for working, pulsing amber for blocked, red for error, and muted
bars once seen. Explicit status/progress remains in accessibility and filters.

Background monitoring uses the existing attention channel (renamed Task updates)
for new done reports as well as requests/errors. A complete first snapshot
baselines historical completions; partial startup snapshots do not replay them.
Work observed during this service run can finish before the baseline completes.
Once baselined, first-seen fast completions also alert. Foreground and seen
versions are consumed without delayed alerts, and persisted stamps prevent
reconnection duplicates. A completion notification remains until seen, foreground
return or dismissal even if its report clears. Private content uses a redacted
“Rexling task finished” lock-screen title. No push server, auto-start, input,
terminal ownership or resize behavior changes.

Activity and Sessions use compact modal drawers, matching typography, small
headers and tighter cards. Sessions no longer expands a duplicate carousel
above its drawer. Search, cross-server navigation, New, Rename, Close and normal
workspace session swipes remain available.


### Activity state bars and descriptions — release 70

Rows use a stable two-line title/description block and vector program tile of
matching height, including font scaling. The subtitle combines report message
and human context into one ellipsized line. Generated window labels matching
Window followed by a hexadecimal ID are omitted from presentation; meaningful
window names and routing IDs remain unchanged. Mark seen retains its own fixed
action slot, so acknowledgment does not move or resize the title column.

Only working bars own an infinite animation. Its linear phase is read during
drawing; a short blue segment wraps down the left rail without recomposition,
layout changes or flashing the row. Done is steady blue, blocked amber, error
red and acknowledged/idle muted. Activity disposal removes its animation clock.
Notification eligibility, report stamps and exact-pane navigation are unchanged.


### Activity working pulse — release 71

The working rail now stays filled and changes brightness uniformly between
45% and 100%, on the same 1.2-second cadence as the tab working highlight. It
uses the shared working-phase helper, with a smooth cosine cycle observed during
drawing. No segment travels along the rail, and it never disappears. Finished
remains steady blue; other colors, row geometry and notification behavior are
unchanged.


### Shared working highlight and quiet refresh — release 72

Working now uses the tab’s actual drawing function: a filled blue bar with a
white translucent highlight moving along its long axis. Activity applies it
vertically, tabs horizontally, with identical colors, gradient proportions and
1.2-second phase. Unseen blocked/Needs you reports use the uniform amber pulse;
finished remains steady blue and acknowledged attention stops animating.

The extra blue line immediately under the Activity filters was its own metadata
refresh LinearProgressIndicator, not a leaked tab underline. It is removed while
its two-pixel layout gap remains stable. Existing rows refresh without flashing
a second indicator; the empty-state Checking activity text still explains the
first load. Per-report progress and accessibility metadata remain available.


## Focus-aware attention and experimental Codex titles — release 74

A pane is watched only when Rexling’s window is focused, foregrounded, interactive
and unlocked, its current screen is ready, navigation is settled and no drawer or
preferences panel obscures it. The active server, endpoint, session and block
must all match. Unseen notifiable reports for that pane are acknowledged using
their current versions before computing the eye count. Other panes/tabs/servers
retain their unread indication. Background/locked monitoring has no watched pane
and keeps the existing background-only notification policy.

Native activity snapshots remain available while the drawer is closed so Kotlin
can apply watched-pane and optional title policies. Unchanged snapshots share the
same Arc, and the wire layer retains revision/identity shortcuts. Normal terminal
frames reuse cached policy results; they do not repeatedly parse episode data.
Off-session/monitor title events update activity and override stale scan titles
with cached events, guarded by the record’s process owner.

The default-off Experimental setting recognizes only the explicit bracketed
Action Required title, including [!] and observed dot/space variants followed by
Action Required |. Native metadata marks only a root Working report owned by the
current Codex foreground process, with matching title owner. It does not alter
the server’s report. Kotlin infers generic Blocked only with the setting on;
explicit Blocked/Done/Error and other programs retain their reported states.
Disabling immediately restores raw states. Raw working metadata is preserved so
the active tab also stops showing Working while inferred attention is active.

Per-endpoint, per-record attention episodes persist in app preferences. Title
animation and reconnect retain a version; returning to ordinary work closes the
episode and a new question receives a fresh version. UI and monitoring service
share the policy; foreground UI is authoritative while visible, the service in
background, with non-authoritative observations predicting the same next epoch.
The existing attention ledger consumes foreground reports and deduplicates alerts.
This remains a title-format-dependent compatibility feature, not an upstream fix.


## Native terminal image uploads (API revision 11)

Build 79 prefers `com.superlogical.terminal.upload_begin` and a separate
`rex.data.bytes.v1` data connection. The dedicated upload control connection
negotiates 7–11; live terminal negotiation remains 7–8. A server below revision
11, or one returning method_not_found/revision_too_low, prompts the user to update
Rex. Build 80 removes the alternate upload backend; errors never change destination.

Staged images stream as 16 KiB binary chunks with an exact byte count. An empty
binary message half-closes the upload; only a successful server close after EOF
confirms completion. The server inserts the path into the originally captured
session/block, so Kotlin never pastes it again or sends Enter. Native transfers
run in a separate cancellable worker; metadata polling does not block the
terminal actor. Closing the upload cancels before completion; an error/loss after
attempting EOF is treated as uncertain and disables Retry. Images remain capped
at 100 MiB locally, with server limits authoritative.

Picker, clipboard and IME image input use native Rex directly. Build 80 removes
Portside settings, client code, path-insertion handoff and app-launch integration. Full investigation and
wire evidence are recorded in `../docs/REX_UPLOADS.md`.


### Upload progress checkpoints (build 86)

File bytes and EOF framing remain unchanged. Every 64 KiB, and after the final
partial chunk, the client sends a standard WebSocket Ping containing `rex-upload`
plus the sent-byte offset as little-endian u64. Only matching Pongs for emitted,
outstanding offsets advance progress. A later checkpoint covers earlier ones,
allowing coalesced responses. Up to 4 MiB can be in flight; there is no round trip
per file chunk. Stream replies are drained while writing and while awaiting the
last checkpoint. Cancellation remains active throughout.

This measures transport receipt of preceding data frames, not file validation
or durable storage. Only successful EOF/server-close completion means the path
was inserted. Unknown Pongs do not advance progress; a checkpoint timeout before
EOF is a transfer failure, while lost confirmation after EOF remains uncertain.
The existing native metadata field `sent` now carries confirmed progress.

Ping/Pong behavior follows [RFC 6455 sections 5.5.2–5.5.3](https://www.rfc-editor.org/rfc/rfc6455#section-5.5.2).
It was verified against both configured Rex servers and throttled loopback peers.
