# Build validation

Verified on the development Mac and **API 37 arm64 Android emulator** on
**2026-11-01**. The app was installed and launched on `Medium_Phone_API_37.0`
(`emulator-5554`). The S22+ received the latest build over USB and passed the
terminal-font instrumentation check. No Rex server settings were changed.
Older layout descriptions below are retained as historical validation notes.

| Check | Result |
| --- | --- |
| Android arm64 Ghostty + Rust JNI compilation | Passed |
| Emulator installation and launch | Passed; cold launch completed |
| Emulator native terminal preview | Rendered captured snapshot, colors, Unicode and cursor |
| Emulator live connection | Authenticated server connection and live terminal visible; control mode and keyboard observed |
| Emulator crash log | No AndroidRuntime errors or libc fatal entries during smoke run |
| `assembleDebug` | Passed; debug APK signed |
| Kotlin model/navigation/appearance JVM tests | 31 passed |
| Native offline unit tests | 16 passed |
| Explicit real-server actor host tests | 6 passed, including separate Mac/Linux hosts and session closing |
| Actual JNI bridge on host JVM | Passed in earlier validation |
| Android instrumentation | 11 emulator tests passed; earlier base-font check passed on S22+ |
| Latest mockup APK installed on S22+ | USB install returned Success |
| Android `lintDebug` | No errors; version/target-scope suggestions remain |
| Native Clippy with warnings denied | Passed |
| JNI exports and packaged ABI | Four expected JNI exports; arm64-v8a only |
| 16 KiB ELF and APK ZIP alignment | Passed |

The actor integration test created an isolated shell, connected the app's native
worker directly, selected its session/block, took ownership, sent Unicode input,
resized, paused, generated output during the gap, resumed, released control and
disconnected. Cleanup destroyed the disposable session. Existing user terminals
were not used for test input.

The host JVM test loaded the real JNI library, created a worker, requested the
offline stream replay, received Unicode and screen state, verified no ticket or
reconnect-token fields reached the UI, rejected invalid input, and destroyed the
handle safely. The Kotlin compiled native method declarations were inspected and
match the exported symbols.

The packaged native libraries are the Rust/Ghostty JNI library, the NDK C++
runtime, and AndroidX's graphics-path library. Each has 16 KiB-compatible load
segments, and each is stored uncompressed with the required APK alignment. The
debug signature was verified with the SDK tools.

Artifact details and checksum are recorded in [validation/apk.json](validation/apk.json).
Re-run `python3 scripts/verify_apk.py` after rebuilding; debug build bytes and
checksums can change. Build caches, native binaries and APKs are intentionally
ignored by Git; the source, wrapper, lockfile, fixtures and build recipes are
tracked.

The first emulator run revealed that Canvas background drawing escaped the
terminal viewport and covered the header. Explicit Canvas clipping fixed it;
the updated live view retained the header and terminal controls. System bars
were also switched to light icons for the dark app theme. The rebuilt APK,
Kotlin tests and lint passed after these changes.

The emulator reached the existing Tailscale server through its host networking
without a guest VPN setup. This does not establish phone-side VPN/DNS behavior.
The live UI smoke run was observed manually; no comprehensive emulator test suite
was run. IME composition, real-phone rendering, rotation, network loss and
background/process-death behavior still need testing on the S22+.

## Compact navigation update

The normal terminal layout now uses a 44 dp session/pane/control header, a 36 dp
scrollable window tab strip, and a 40 dp terminal key row. The title, dimension
row, session/pane chip rows and routine status footer no longer take shell space.
Navigation remains visible while the keyboard is open. Server and terminal
options live in the overflow menu.

The rebuilt app was installed on the same API 37 emulator. A disposable test
session contained an Editor tab with two panes and a Logs tab with one pane;
a second disposable session exercised cross-session switching. ADB-driven UI
checks confirmed selecting a session, changing tabs and panes, restoring the
last pane when returning to a tab/session, and access to all top navigation
controls while the IME was open. Input sent through the app produced
`REPLY:compact-input-check` in the disposable shell, verified using the independent
probe's snapshot command. Both test sessions were destroyed afterward.

The IME-visible terminal viewport measured `[0,274][1080,1323]` on the emulator;
the terminal reported 49 columns by 22 rows at the 14 sp font used for that check. The prior
layout's observed IME-visible terminal was 49 by 17. Navigation history unit tests
also cover removed panes/windows and empty windows. `assembleDebug`, all five
Kotlin tests and `lintDebug` passed; lint has no errors. The crash log contained
no AndroidRuntime error or libc fatal entries during these checks.

## Swipe panes and single-strip navigation

The latest layout replaces the former 44 + 36 dp navigation rows with one 48 dp
strip: session access, scrollable tab pills, a + creation menu and overflow. The
40 dp key row remains. Pane position is overlaid on the terminal without taking
viewport height. A newly selected tab scrolls into view. The default font is now
10 sp (the earlier 14 sp viewport measurements above are historical).

Nine Kotlin tests pass, including horizontal gesture direction, taps vs short
drags, vertical/cancelled gesture rejection, same-tab neighbours, edge clamping,
and pane history/fallbacks. The native suite passes eleven tests: eight core,
one preview and two real-server actor tests. The new actor test creates a tab
and split pane, opens their snapshots, checks the resulting window/pane counts,
and rejects stale session/anchor IDs before mutation. It destroys its disposable
session afterward. Native Clippy with warnings denied also passes.

On the API 37 emulator, ADB swipes selected the next/previous panes and updated
the floating indicator. A vertical drag and a swipe before the first pane kept
the current pane. + New pane created and opened pane 2/3; + New tab created and
opened Terminal 3. Independent server inspection confirmed three windows with
3, 1 and 1 panes. Emulator input `printf ANDROID_TAB_OK` reached the new shell
and returned the marker in the independent probe's snapshot. The UI remained
usable with the IME open. Tests used only the disposable `android-swipe-test`
workspace; the user's pre-existing terminal received no test input.

The final polished build also verified swiping through all three panes, clamping
at the last pane, opening the picker from the selected tab title, and restoring
pane 2/3 after visiting another tab. Session access is a separate target on the
selected tab's subtitle and the left header button. Both the tab strip and native
view are clipped to their bounds. The app returned to the original workspace
and the disposable swipe workspace was destroyed. No Java/native crash entries
were present during these checks.

## Mockup delivery: carousel, appearance and sessions

The delivered UI follows the provided dark/light references: lime/olive accents,
compact session strip and takeover control, rounded terminal cards, pane swipe
feedback, bottom tabs and a dock of terminal keys. The session sheet provides
search, New, current-first ordering, last-session marking and gesture hints.
The session header supports sideways swiping and long-press return to last.

The S22+'s OEM monospace alias measured M at 19 px and i at 6 px at the tested
size. The bundled JetBrains Mono NL measured both at 14 px. The real-phone test
verified all printable ASCII advances in regular/bold/italic/bold-italic at
8, 10, 12, 14, 16 and 18 sp. Integer cell spacing and RGB/Unicode rendering were
also inspected in actual app captures. The phone was left available for user
use; remaining scripted UI checks ran on the emulator.

Emulator captures verified Light and Dark terminal cards, the session sheet
with actual attachment/count metadata, long-press last-session switching, direct
bottom-tab taps and an animated pane swipe showing PANE_B. Off-screen frames are
read-only snapshots, not continuously streamed replicas. Only the selected pane
is live. Explicit application RGB colors remain unchanged by appearance mode.

The owned-server actor test also validates read-only neighbor snapshot retrieval
without changing the selected block or ownership, session counts/attachment
metadata, and creation of a new session and shell. Each host test cleans up its
own sessions. Navigation request IDs guard rapid tab changes and prevent typing
until the matching snapshot is ready. The core tests cover exact theme color
roles. Native tests total 12 (10 offline plus 2 owned-server tests); Clippy passes
with warnings denied. Android build, all 16 JVM tests and lint pass.

The mockup debug APK was installed over USB on RFCT21390SD (Galaxy S22+) after
reconnection, with ADB reporting Success. No program-icon work is included.


## Neovim fixes and blue light theme

The previous renderer failed two pixel regressions: a uniform explicit RGB
background developed seams at quarter/half/three-quarter pixel canvas offsets,
and a wide glyph's default-colored spacer erased half its background. Cell
backgrounds now use a separate non-antialiased Paint and merge equal contiguous
row fills. Wide leading cells cover their spacers. Carousel translation is
snapped to whole pixels. Text retains antialiasing and the bundled font grid.
Both background regressions plus the typography test pass on the emulator
(3 instrumentation tests). The tests failed before the renderer fix.

All 13 native tests pass: 10 offline tests plus 3 owned-server integration tests.
The new integration runs real Neovim 0.12.6 and verifies `&background` changes
through Rex `set_theme`, deferred non-owner appearance, takeover, and lifecycle
pause/resume. All test sessions, including the version-probe session, were
cleaned up. Clippy with warnings denied, all 16 Kotlin JVM tests, Android debug
build and lint pass. APK signature and 16 KiB alignment pass.

Light now uses blue #3156f4 accents with cool gray chrome and white terminal
cards. The emulator Appearance picker and a captured preview visually confirm
the new palette. The redundant `/ shell` header label, visible pane fractions
and “swipe to switch” text are removed. Dots remain tappable to open the pane
picker; accessibility descriptions still identify the pane position. The
explicit Take over/Release control remains.

The final APK was installed via USB on RFCT21390SD (Galaxy S22+, SM_S906E),
with ADB reporting Success. No phone UI automation or program-icon work was
performed for this delivery. `validation/apk.json` records the installed APK.


## Rexlet branding, icons and revised header

The first program-icon APK was installed on RFCT21390SD before header work,
as requested (ADB Success; SHA256
`1b9815c34a9589a2d846e54d75f628c4a5dd38d3f344be5a616d1b84039e3f71`).
It included blue dark mode, 6 dp horizontal / 8 dp top terminal-card padding,
neutral menus and the centered + button. The + fix centers the 36 dp control
inside its 41 dp circular background. Surface container/accent roles are explicit
and dropdowns use the app surface with zero tonal elevation.

The later build is named Rexlet, with the connection-screen subtitle
“Unofficial Android client for Rex.” The package/application ID is unchanged.
AAPT and the APK verifier confirm the launcher label. The launcher terminal
mark uses blue; Rex control connections identify their app as rexlet.

The revised header shows a selected program icon/name and cwd, with the session
below. Multi-pane tabs use real per-pane icon stacks in the header and bottom
tabs; single-pane tabs have one icon. The active bottom tab uses a full-width blue underline,
with touch feedback clipped to rounded tab bounds. Pane swipe feedback
includes the destination program icon and the footer shows dots alone.

Emulator checks verified both appearances, the new header, multi-pane stacks,
a swipe from Neovim to a Claude report fixture, pill selection, session picker,
app branding, and neutral menus. Native read-only metadata recognized actual
Neovim 0.12.6. Claude/Codex visuals used explicit disposable OSC 7501 fixtures;
no agent account or existing user terminal was driven. Saved-session startup
now restores the requested session: the pager's initial page emission no longer
overrides persisted selection. Phone interaction tests were not performed.

All 20 Kotlin JVM tests and all 16 native tests pass (12 offline, 4 owned-server
integrations). Clippy with warnings denied, Android debug build and lint pass.
The renderer/typography instrumentation suite passed all 3 emulator tests during
this delivery. APK signature, arm64 libraries and 16 KiB alignment pass. All
changes are committed by scope. The only unrelated untracked file is Android
Studio's `gradle/gradle-daemon-jvm.properties`.


## Scrollback, full-width cards and final voxel icon

The requested voxel dinosaur supersedes the earlier smooth illustration. Its
original is preserved in `artwork/rexlet/source.png`. Built-in imagegen extracted
the transparent foreground and cleaned alpha edges; both prompts and checksums
are recorded alongside it. Android applies the white background and launcher
mask. The packaged adaptive icon is inflated/drawn in instrumentation and its
rendered preview is visually inspected.

Cards now span the screen width. Internal padding extends adjacent visible-cell
backgrounds through all four edges. The emulator's two-color Neovim split verifies
that the sidebar's color extends left/top while the editor's different color
extends right/top. The cursor is filled with a contrasting glyph. Header icon
tiles have distinct dark program colors, bright glyphs, border and subtle shadow.
The active tab underline spans its tab; the permanent pill fill is removed while
pressed feedback remains rounded.

Real-server history and viewport round-trip tests pass. Emulator touch scrolling
shows old HISTORY lines and the Latest button, then returns to live output on tap.
Vertical drag does not switch panes or type. Instrumentation covers adjacent
padding colors, dimensions excluding insets, filled cursor, original cell-seam
and wide-character regressions, typography, and launcher inflation (7 tests).
All 21 JVM tests and 17 native tests pass (13 offline, 4 owned-server integrations).
Clippy, debug build, lint, signature and 16 KiB alignment pass. UI tests only use
disposable sessions, which are removed after validation.


Final Rexlet voxel-icon APK installed via USB on Galaxy S22+ RFCT21390SD with
ADB Success. SHA256:
`d596333eb0a8d1f1957dc45797c6f3821b777c634582f802696d7625fecf932b`.
`validation/apk.json` records the signed installed artifact. After replacing the
artwork, the launcher/gesture instrumentation class passed again and the actual
192 px adaptive launcher render was visually inspected. The final header also
renders its session chevron beside the session label.


## Nerd Fonts, reflective tiles and connected-server navigation

The terminal now bundles JetBrainsMonoNL Nerd Font Mono 3.5.1, base 2.304, in
four styles. The release archive SHA256 is
`04d5e8f903693f9dd13e16f867e994834e681eb3c72c0d337a770dcda09010cf`.
The source record, per-file hashes, licenses and attribution table ship in APK
assets. Emulator typography tests verify ASCII and Nerd Font glyphs including
Powerline, folder/terminal/devicons and supplementary U+F031D at all supported
sizes and styles, each using exactly one terminal cell. No server-side font
installation is required. Program tiles now share colored gradient faces,
reflective highlights, a thin rim and shadow in the header and tabs.

Saved servers retain independent native connections and terminal state. Real
Mac and Linux endpoints authenticated through existing Tailscale HTTPS listeners.
A native two-host regression verifies independent input, selection, disconnect
and lifecycle. Android ViewModel instrumentation verifies two live actors,
hidden-view input rejection, combined session selection, cross-server last-session
history and removal of disconnected hosts from the catalog. JVM tests cover
colliding session IDs on separate hosts, stable server grouping, disconnected
hosts, loading-location preservation and correct per-host attachment counts.
The previous test fixtures no longer existed at the first rerun; fresh owned
fixtures were created and the complete instrumentation suite then passed.

A real emulator header swipe crossed from the last Linux session to the first
Mac session without takeover or typing; long-press returned to the Linux test
session. The combined picker then selected the disposable Mac fixture. Dark and
Light screenshots were inspected only on the fixtures. The header is 50 dp
(previously 58 dp); front tiles are 30 dp in the header and 18 dp in tabs. The
full-width terminal card retains 12 dp inner side padding, and the active tab
uses a plain full-width 2 dp blue line with rounded touch feedback.

Final verification: 29 JVM tests, 9 Android emulator instrumentation tests,
18 native tests (13 offline + 5 live, unchanged native implementation), Clippy
with warnings denied, debug APK/test APK builds, lint, signing and 16 KiB
alignment all pass. The emulator's previous app preferences were restored and
only the two newly created disposable server sessions were destroyed. Existing
user sessions and server configuration remain intact. No phone UI automation
was performed for this delivery.

The final build was installed on Galaxy S22+ RFCT21390SD (SM_S906E) via USB;
ADB returned Success. APK SHA256:
`8c337154936573817467dd6fac58d3012f6654a3e651883f9b9731f7e171db00`.
`validation/apk.json` records this signed artifact (40996753 bytes).
Changes are committed separately for fonts, tile rendering, content spacing,
multiple connections, cross-server sessions, compact chrome and documentation.


## Performance pass, rectangular icons and session actions

The live display path uses condition-variable revision notifications and binary
row deltas instead of periodic full-screen JSON polling. Metadata and terminal
flows are separate; a live two-host assertion confirms that terminal output alone
does not publish new chrome state. One initial test raced the first program
metadata result; it now waits for that independent result before measuring screen
updates. Hidden hosts apply VT without extracting frames and publish a fresh
screen when selected. Native tests verify this without losing hidden output.
Handle destruction wakes waiting readers; JNI waits release the global registry
before blocking, so other server handles remain usable.

Terminal rows cache drawing commands. Compatible ASCII text is batched and other
glyph advances use a bounded cache; unchanged rows retain their list identity.
A pixel regression compares cached versus fresh drawing and proves that changing
one row records only that row. Existing color seams, wide glyphs, padding, cursor,
Nerd Font and typography checks pass. Pane translation reads state in the graphics
layer, and destination badges only recompose when the destination changes.

Measurements are saved in `validation/performance.json`:

| Workload | Result |
| --- | --- |
| Dense 100×50 terminal, one changed row, software Canvas median | 8.127 → 0.844 ms (about 90% less CPU draw time) |
| Same renderer workload p95 | 8.644 → 1.803 ms |
| Local full-frame / one-row JNI packet | 100,265 / 2,072 bytes |
| Input dispatch → Android terminal state, including network, 10 samples | median 17.769 ms; p95 33.985 ms |
| Optimized cold startup with / without Baseline Profile | median 583.848 / 588.291 ms; overlapping distributions |
| Offline gesture frame CPU p50 / p95 after translation deferral | 1.711 / 17.479 ms |

These are emulator results, not S22+ performance guarantees. The draw workload
is isolated software rendering; input timing ends before final pixel display.
The gesture workload is offline preview pane-edge dragging. Its long frame tail
still needs physical-device profiling; no blanket 60/120 fps or battery claim is
made. Profile installation/packaging is verified, but the emulator did not establish
a material startup gain from profiles alone. Raw Perfetto traces remain in the
ignored benchmark build output. An offline rerun initially found the live-fixture
preferences; it was rerun successfully after resetting only the test emulator.

The baseline generator records the real offline startup → terminal → appearance
journey. It produced 17,986 rules; R8 rewrites these into the packaged ART profile.
The release is non-debuggable, minified and resource-shrunk. It keeps the existing
sideload signing identity and package, preserving phone preferences. The separate
optimized variant adds profiling support. The generator script rejects phone
serials because Gradle's connected-test cleanup may uninstall the target app/data.

Header/tab tiles are now compact landscape rectangles with white rims, program
colors, reflection highlights and rounded corners. A disposable two-pane release
session verified both swipe directions, destination icons and stacked tiles.
Dark and Light visuals were inspected on owned fixtures. The session sheet uses
plain “previous” text and has no footer gesture hints. Either swipe direction
reveals Close; ⋮ and accessibility actions expose it too. Confirmation includes
session/server names and explains that all programs will end. Cancel sent no
mutation; confirmation closed only the disposable target. The inactive-session
native test preserves the viewed session; another closes the current session.
The actual installed server schema is in `validation/session-destroy-schema.json`.

Final checks: 31 JVM tests, 22 native tests (16 offline + 6 owned-server tests),
11 Android instrumentation tests, one successful profile-generation journey and
three Macrobenchmark tests pass. The affected gesture benchmark passed again
after translation deferral. Debug/release builds, lint, native Clippy and the
host JVM JNI smoke pass. Release verification checks the six JNI exports,
arm64 libraries, signature, 16 KiB ELF/ZIP alignment, non-debuggable manifest and
packaged ART profile. No phone UI automation was performed.

Final release APK: `app/build/outputs/apk/release/app-release.apk`,
15806134 bytes. SHA256:
`152fd90a9c1fc1548a1673e65f301f86c04f2b6d7c99d755eb66d6f1474c21d6`.
ADB installed this build on Galaxy S22+ RFCT21390SD; the installed base APK's
SHA256 was read back and matched. `validation/apk.json` records that artifact.
The emulator received the same release, its original preferences were restored,
and only disposable test sessions/panes were removed. Existing user sessions,
server settings and authentication remain intact. Changes are committed by scope.


## Rexlink release: scrolling, fitting, grouping and theme follow-up

The app is renamed Rexlink, with the unofficial subtitle and package unchanged.
Its new full-body dinosaur foreground was prepared with built-in imagegen,
packaged with alpha, and drawn through the real adaptive launcher in instrumentation.
Header/tab front tiles and label line boxes share their vertical center.

All 34 JVM tests, 24 native tests (17 offline + 7 owned-server tests), and 14
Android instrumentation tests pass. Clippy with warnings denied, the host-JVM
JNI smoke, debug/release builds and lint pass. Profiles were regenerated from
the current offline journey (17,998 rules). Binary RXT2 flags survive snapshots
and JNI; RXT1 compatibility remains tested.

An owned raw-mode Python fixture negotiated the same full-screen SGR mouse modes
used by modern TUIs and had zero terminal history. Drags moved its visible counter;
the first application scroll acquired control without showing the keyboard.
A separate content tap acquired control and opened the keyboard. This fixture
is not a launched Codex agent and made no model/API calls. A 160-column view
retained its rightmost END marker before takeover and resized to the phone grid
after ownership. Group order was checked with both Linux and Mac selected. Search,
plain previous labels and confirmed close actions remain available.

A configured Neovim 0.12.5 session on the Mac passed light/dark, takeover and
pause/resume assertions after the explicitly approved Rex-aware config change.
Its statusline plugin initially hid the test indicator; the fixture now reports
its actual &background independently of that plugin. A clean fixture also
verified the protocol behavior. Existing user editor sessions were not driven.
The separate dotfiles commit is `1e2c9c3`; a pre-edit copy is retained at
`/private/tmp/rexlink-nvim-init-before.lua`. Running editors need one restart.
No Rex server/authentication configuration changed.

The final non-debuggable release was installed over USB on S22+ RFCT21390SD.
ADB returned Success and the installed APK SHA256 matched:
`36bba3df87131a16a7bbb822852b84f29626a7ddc375b67e917a541e70803549`.
The artifact is `app/build/outputs/apk/release/app-release.apk` (15584982 bytes),
with signature, arm64 libraries, 16 KiB alignment and ART profiles verified.
`validation/apk.json` records it. App settings and saved connections were preserved.


## Fixed-font reflow correction

The automatic zoom-to-fit approach was rejected after delivery. It is removed:
font size stays fixed while viewing desktop-width terminals. The user's selected
policy is explicit takeover/reflow on content tap, not automatic control when a
tab opens. The cached phone columns/rows now accompany takeover, resizing the
actual PTY so its program redraws. A disposable 160-column terminal was tapped
and reported 63 columns afterward, with the keyboard visible and the requested
font preserved. The affected six Android renderer regressions pass, including
a pixel assertion that wide remote frames do not shrink the font cells. Build,
JVM checks, lint and Clippy pass. No extra broad benchmark pass was added.

The multi-pane content/footer reservation is reduced from 22 dp to 14 dp; Latest
moves with it. This reduces the space between terminal content and pane dots.
The corrected release was installed on S22+ RFCT21390SD (ADB Success), and its
installed APK hash matches `validation/apk.json`:
`611bc0c2f3e2b8772f9aba4de6fcbcd468513e587b55b7251387673a3da3a3ef`.
The prior zoom-to-fit screenshots/claims above are historical, not current policy.


## Compact header, task titles, extra keys and stale-session follow-up

All 36 JVM tests and 25 native tests pass, including owned-server title changes,
repeat close of a missing session, unowned dark-pane viewing, takeover and
reconnection. Configured Mac Neovim also passes the same-scheme takeover case
with a 63-column resize. A test indicator now uses its disposable buffer to avoid
creating a hit-enter prompt during resize; the first apparent reproduction was
that test artifact, not a confirmed failure in production focus delivery.

The live user Mac pane was inspected without sending editor input: macOS was
Dark, native Rex Beta owned it, canonical palette entries matched Android light,
and Neovim reported background=light / catppuccin-latte. The approved local
config now resolves the owner before choosing OS versus terminal appearance
(dotfiles commit 0704207). Mocked checks cover both owners, lookup failure and
stale asynchronous results. A real read-only ownership lookup against that pane
selected dark OS appearance. Running Neovim must be restarted for the new helper;
its in-place running user editor was not changed or restarted by automation.

Emulator UI checks used one disposable two-pane fixture and verified the task
title in both header and tab, disappearance of the canceled pane overlay,
F1-F12 expansion/collapse, and the strip resetting to Esc. Screenshots were
visually checked; all four arrows fit without edge clipping at emulator width.
Original emulator preferences were restored and only the fixture was removed.
Debug/release builds, JVM checks, release lint, and Clippy with warnings denied
pass. Existing baseline profiles remain packaged; no unrelated benchmark rerun.

The non-debuggable release was installed on Galaxy S22+ RFCT21390SD over USB.
ADB returned Success; its installed base APK SHA256 matched the verified build:
`354a19dbc7c1cee51df9706cac961b4a1105629ae0ecd92f56825c75a7d773ea`.
`validation/apk.json` records signature, profiles and arm64 alignment checks.
Saved phone connections and settings were preserved. Changes are committed per scope.


## Shell captions, working underline and corrected keyboard reveal

37 JVM tests pass, including shell-directory duplication and preserving Codex
and custom task titles. All 19 offline native tests and the affected live
selected/background program-status test pass; the live test transitions working
to cleared and rejects stale ownership. Clippy with warnings denied and release
build/lint pass. No unrelated full live or performance rerun was added.

Emulator checks verify the chevron starts hidden before Esc, a rightward drag
reveals it, F12 is available when expanded, and collapse hides the chevron again.
The ordinary keys fill the width to the keyboard control. An initial gap assertion
was corrected for Android's expanded minimum touch bounds (which can overlap
slightly); screenshot inspection confirms the actual 3 dp painted gap. A drag
starting farther from the system back edge verified the reveal gesture.

Two screenshots of the active working tab showed 2,327 changed pixels within
its bounds, verifying animation without extra indicator space. An idle report
stopped the working state. The shell fixture visibly shows fish once as the
header program and ~ as its separate directory. The final shell check was resumed
after the standalone launcher instrumentation stopped the test activity; it
passed after relaunch. The adaptive launcher test passed, and its rendered icon
was visually checked. Only owned disposable fixtures were driven and removed;
the emulator's original preferences were restored.

The cropped dinosaur was prepared with built-in imagegen; source, hashes and
prompt are in artwork/rexlink. Android supplies the white adaptive background.
The final release was installed on S22+ RFCT21390SD with ADB Success; the installed
base APK hash matches validation/apk.json:
`f9b4d12bc54f8f19180ce105740a7b51a62bbc58e0784c7cd97cddf6f2a59b08`.
Saved phone settings and connections were preserved. Changes are committed by scope.


## Live title frames, text selection, pane picker and official catalog delivery

All 27 native checks (20 offline + 7 owned-server tests) pass with both server
environment variables supplied. The first broad invocation lacked the second
server variable; the corrected invocation passes. The title test observes three
successive braille-prefixed terminal titles, each within 700 ms of its input,
then verifies a title update in a background pane. A static status record cannot
mask them. Unit coverage rejects old process owners, stale polling responses
and resurrection after clearing a title. Clippy with warnings denied passes.

All 41 JVM tests pass, including four selection tests for wide characters,
sparse-cell spaces, reversed multi-line ranges and moving either endpoint.
Five Android interaction/launcher tests pass, including selection without
ownership and preserving the frozen frame while live output changes.
Debug/release builds and release lint pass. The source-only linear extraction
cleanup was included in the final JVM/build pass.

Emulator UI checks observed each live title in both header and tab, showed the
new pane picker with program names/directories and catalog tiles, and confirmed
zero changed pixels in the header icon between animation frames. Long press
visibly highlighted TITLE_READY and displayed native Copy / Select all without
taking ownership. The floating toolbar was absent from the UiAutomator tree;
the coordinate-based clipboard roundtrip did not complete and is not claimed
as a passing check. Selection and frozen-frame behavior are covered at model/interaction level;
the clipboard action was not exercised by instrumentation. Physical copy
behavior remains for user testing. Original emulator preferences were restored and the
owned disposable fixture was removed. No user editor was driven.

The catalog was accessed through native Edge window automation after the
browser-specific host failed. The user then supplied the complete SVG download,
which was used directly. Ten selected source SVGs, source hashes, attribution
and reproducible WebKit rendering script are committed. Codex/Claude renderings
were visually inspected. No authentication material is packaged.

At the user's request to ship promptly, the final launcher uses their supplied
transparent paper dinosaur PNG exactly; no image generation or editing was
applied to this final file. The release was installed over USB on Galaxy S22+
RFCT21390SD and its installed base APK SHA256 was compared with the build.
validation/apk.json records the delivered artifact. Saved phone settings and
connections were preserved. Feature and artwork changes are committed by scope.


The user's final replacement is the already-transparent paper dinosaur supplied
as 5BD6830D-F403-4478-9E09-6AC36F3C38F5.png. Its bytes and alpha were preserved.
The installed S22+ release hash was read back and matched:
`561e5fdc370b01568cfef5f33d89c9259cc1258380cf1b2dea56624f206c573c`.
This supersedes the briefly installed opaque paper-icon build. The final APK
signature, non-debuggable setting, native alignment and ART profiles are verified.


## Close controls and shareable release — 2026-11-01

Targeted live native close test passed: stale-session and changed-tab guards, background ownership preservation, repeated missing-pane close, active-neighbor selection and final empty session. Clippy with warnings denied passed. Debug/release builds, release lint and 42 JVM tests passed, including fresh-install/legacy-server migration checks.

Emulator checks exercised tab long-press and pane-picker close, cancellation and confirmation. The last-pane helper initially indexed an omitted `windows` field; the empty-session check was corrected to accept the server's omitted field. Fresh-install setup and offline preview were checked. Physical interaction remains for user testing.

The optimized, non-debuggable release is copied to `dist/Rexlink-0.1.0-arm64-release.apk` with a SHA256 sidecar. Signature, native alignment and ART profiles passed verification. Installed S22+ base APK readback matches SHA256 `3782dee4540e3afb23baf54a7f1ddf23c22f7865dde2cf0c08c332da99d7c5a1`. Existing phone data was preserved. Fresh installs no longer contain a preset personal server.


## Shift keyboard release — 2026-11-02

44 JVM tests pass. Four new emulator Compose gesture tests pass: one-shot Shift and Shift+Tab, holding Shift through navigation scrolling and multiple taps, cancellation, and disabled controls. The scrolling test asserts that no input is emitted by the scroll gesture. AndroidX test runner/JUnit and Espresso were updated to stable API-37-compatible versions; the initial older Espresso failed before injection because InputManager.getInstance was removed. See [AndroidX Test release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0).

Debug/release builds and release lint pass. The optimized release passes APK signature, non-debuggable, native alignment and ART profile checks. Shareable artifact: `dist/Rexlink-0.1.0-arm64-release.apk`, SHA256 `55d53e1f372eaa829847cb8dbf27ef14d0c710c8c32e6dde5db3ad9d6fbd57eb`. After USB reconnection, this keyboard release was installed on S22+ RFCT21390SD; its installed base APK hash matches the shareable artifact. Existing application data was preserved.


## Corrected single-row keyboard — 2026-11-02

This supersedes the two-row accessory. Five separate vector-symbol modifier buttons keep their spacing, followed by an independently scrolling, clipped group of four arrows and a keyboard toggle. Navigation keys return to the optional expanded panel. The normal accessory has one row.

All five emulator Compose tests pass: one-shot Shift/Shift+Tab, held Shift with scrolling and repeated arrow taps, cancellation, disabled controls, and a 320dp-wide layout asserting all controls share a vertical center and do not overlap. Arrow scrolling is checked to leave modifier positions unchanged and emit no input. All 44 JVM tests, debug/release builds and release lint pass. APK release checks pass.

Updated shareable APK: `dist/Rexlink-0.1.0-arm64-release.apk`, SHA256 `5b0b7c8e9e599dc2bd5dab5df708aba1fd05f2fdd83cef01851b922e8a11c9fa`. After USB reconnection, the corrected release was installed on S22+ RFCT21390SD; its installed base APK hash matches the shareable artifact. Existing application data was preserved.


## Readable keyboard icons while viewing — 2026-11-02

Connected, ready terminals now retain full keyboard icon contrast when not owned. Interactivity still requires ownership. Disconnected/busy controls retain their inactive styling. Program catalog tiles were already independent of ownership and are unchanged.

All six emulator keyboard tests pass. The added regression compares every toolbar pixel before/after switching to read-only mode, then asserts disabled semantics and no emitted input when those keys are tapped. The 44 JVM tests, debug/release builds, release lint and APK checks pass. Updated shareable release SHA256: `62c65b9d404789bc45b35e0367eefb6665cc51c12ae7ab5694369bcd7066bd02`. Installed on S22+ RFCT21390SD and verified by installed base APK hash; existing settings preserved.


## Pane indicator continuity and pointer ownership toggle — 2026-11-02

The swipe badge retains the target pane through settling and the timed display; its dismissal timer starts after motion. Animation handoff state updates together, and badge identity stays bound to the target instead of briefly using an old composition index. Derived state preserves the existing efficient animation rendering. Cancelled gestures clear the badge without resurrecting it.

Two manual-clock emulator tests pass: sampling 22 frames across the handoff verifies continuous visibility and target identity, and a cancelled gesture stays dismissed after settling and the old timeout. A third test verifies take/release action semantics, selected state and the long-press label on the pointer-only ownership toggle. The intermediate cached-index variant failed a handoff assertion and was corrected before delivery. No eye icon is used for ownership.

All 44 JVM tests, debug/release builds and release lint pass; APK checks pass. Updated shareable artifact SHA256: `7e29850bb61ee16cc9826022faa646be72733fb89d90d1d4cc012ee27ed5c19f`. Installed on S22+ RFCT21390SD with matching installed base APK hash; settings preserved.


## Tappable visible web URLs — 2026-11-02

49 JVM tests pass, including URLs with paths/queries/fragments, localhost/private IP/IPv6, Unicode paths and wide-cell prefixes, punctuation trimming, missing hosts, disallowed schemes, hidden text and sparse rows. Seventeen emulator checks pass: five URL interactions plus existing terminal selection/scrolling, background/cursor/cache and rendering performance tests. Browser intents were captured by a ContextWrapper; tests did not browse external pages.

URL taps send ACTION_VIEW with BROWSABLE and the exact detected URL, with no activation/input. Tests cover swiping, cancellation, long-press copy, output changing between down/up, padding, ordinary taps, and rejecting possible wrapped prefixes while adjacent-row state changes. Debug/release builds, release lint and static APK checks pass.

Shareable release SHA256: `63a0d0bc907ef78d41c7902e109ed8d9c065c033cf4d5b1b31ffb6ec86bba61c`. Installed on S22+ RFCT21390SD and verified by installed base APK hash; settings preserved.


## OSC 8, stable navigation keys and compact control — 2026-11-02

52 JVM tests pass, including RXT1/RXT2 compatibility, RXT3 sparse ranges, destination-only changes/removal, untouched-row reuse, invalid ranges, explicit-link precedence and non-web/hidden-text rejection. Native workspace tests pass (22 non-live tests); Clippy with warnings denied passes. New native tests verify Ghostty OSC 8 parsing, wrapped cells, snapshot preservation and sparse metadata-only deltas.

The targeted owned-server OSC 8 test passes on Linux and Mac: create a disposable session, restore the initial link from a snapshot, replace the target through live output, pause/resume, remove the link without replacing its label, then destroy the fixture. An initial fixture test omitted explicit pane selection; the harness was corrected before these successful runs. No user sessions or server settings were modified.

All 28 emulator checks pass. These cover OSC 8 target precedence at a row boundary; existing URL, copy, scroll, render-cache and pane tests; and pixel-identical keyboard appearance through navigation's disabled state while input remains blocked. The smaller pointer keeps its accessible take/release action and tooltip. Debug/release builds, release lint, signature, non-debuggable setting, native alignment and ART-profile checks pass.

Shareable release SHA256: `fd7fb1a89fef60f55caa75daf3eca5421b41b631d42dee9c748919302ec8f62e`. Installed on S22+ RFCT21390SD and verified by installed base APK hash; settings preserved.


## Wrapped links, link actions and hold-Ctrl popup — 2026-11-02

56 JVM tests and 23 non-live native tests pass; Clippy with warnings denied passes. Added native coverage confirms soft-wrap and continuation flags distinguish hard newlines and survive snapshots. Logical-link tests verify multi-row URLs/hit ranges, incomplete-fragment rejection and changed-tail invalidation of unchanged prefix rows. Shortcut tests verify the eight emitted control bytes. Existing live snapshot/stream/resume checks pass on both owned Linux and Mac servers; disposable fixtures were removed.

All 37 emulator checks pass, including real hold/slide/release selection across the popup window, outside cancellation, short Ctrl toggle and read-only guards. Link-menu checks copy the actual destination through Android's clipboard (restoring prior clipboard contents), capture Open and Sharesheet intents, preserve frozen destinations through output changes and keep ordinary text selection. No messages were sent and no external browser page was opened by tests. Wrapped continuation taps open the complete URI. Existing keyboard/pane pixel stability, selection, rendering-cache and performance checks pass.

Debug/release builds, release lint, signature, non-debuggable, native alignment and ART-profile checks pass. Updated shareable release SHA256: `0f3d4bf23105661f360df058da2657d83b12d63f8c7b91cdc72457fbe1d401e5`. Installed on S22+ RFCT21390SD and verified by installed base APK hash; existing settings preserved.


## Keyboard repeat and multi-server activity — 2026-11-02

All 44 emulator checks pass, including held-arrow repeat/release, scroll cancellation, target changes, disabled input, activity grouping/filtering, stale records and the combined pane menu with Activity on the right. Existing Ctrl popup, Shift, links, selection and rendering checks still pass. 59 JVM tests and 24 non-live native tests pass; Clippy with warnings denied passes. Debug/release builds and release lint pass.

The disposable-session live activity test passes on both owned Linux and Mac servers. It verifies root/child reports from multiple sessions, exact-pane navigation, no ownership or dimension changes, and stale-pane handling. Fixture sessions were removed. No user sessions or server settings were changed.

Signature, non-debuggable release, ART profiles and 16KiB native alignment are verified. Installed on S22+ RFCT21390SD; installed base APK matches shareable release `dist/Rexlink-0.1.0-arm64-release.apk`, SHA256 `d3d35b6f3ff2cb34569cf3e93c928c5db347d07029e0b03b67a4919ce76062a8`. Existing application data was preserved. Performance review follows this delivery.


## Activity title and eye-button correction — 2026-11-02

The rounded background is on the rightmost Activity eye, with a plain pane-options button beside it. All three Activity emulator checks pass. Native title coverage verifies live OSC precedence, exact owner/session binding, child-task independence and completed reports from exited processes. The live activity fixture changes its terminal title while its status title remains static and passes on both owned servers. All 25 non-live native tests, 59 JVM tests, Clippy and release lint pass; debug/release APKs build.

Activity titles in the attached session consume existing title events; other sessions refresh on the four-second activity scan. Signature, non-debuggable release, ART-profile and 16KiB checks pass. Installed on S22+ RFCT21390SD and verified by installed base APK hash `9cd369e300abb5e3dac4079d67272a843cd46fd6cc8eacd173634f7129d2085d`. The shareable APK and checksum were updated. This correction was shipped before resuming performance optimization.


## S22+ release performance review — 2026-11-02

Requested order was preserved: the Activity/title and eye-button fixes were installed before performance optimization resumed. Measurements and reproducible benchmark commands are in `validation/performance-2026-11-02.json`; prior emulator measurements remain in `validation/performance.json`.

On the S22+ (SM-S906E, Android 16/API 36), four force-stopped launches reached initial activity display in 435, 438, 363 and 446 ms (median 436.5 ms). This excludes completion of remote terminal restoration. A no-touch but animated live terminal sample recorded 4/1,203 modern-jank frames (0.33%), p95 13 ms/p99 15 ms. The Activity open/scroll sample recorded 47/1,425 (3.30%), p95 17 ms/p99 19 ms; this global counter includes the underlying window. These are separate workload observations, not before/after speedup figures.

The review found hidden cached activity being serialized into ordinary title-update metadata. The isolated fix keeps the cache natively for reopening while publishing null activity when the sheet is closed. In a 200-sample optimized Mac benchmark with 256 records, the previous-path-equivalent payload was 54,950 bytes, median 470 µs/p95 1,197 µs; the optimized closed path is 389 bytes, median 10 µs/p95 19 µs. Open-sheet behavior is unchanged. These timings are host measurements, not S22+ timings or an app-wide speedup. A native regression checks hidden payload exclusion, retained cache and reopening.

Next profiling target: Activity transitions/list drawing alongside live title and underline animation. Visible-terminal CPU samples were high (roughly 92–96% of one core), but later thread sampling occurred after drawing stopped, so attribution is unresolved. A controlled foreground trace is needed before selecting rendering changes or claiming battery improvements. Physical key-to-pixel latency was not measured; repeat delivery/cancellation was verified in the emulator tests.

All 26 non-live native tests, 59 JVM tests, Clippy and release lint pass. The live Activity fixture passes after optimization, including title updates and unchanged control/size. Signature, non-debuggable release, ART profiles and 16KiB alignment pass. Installed optimized release on S22+ RFCT21390SD; installed base APK matches shareable `dist/Rexlink-0.1.0-arm64-release.apk`, SHA256 `1f9e92d5f13a637d77aed027858f54e6b6ee32c52247b2908ae0b3a035f9eaf6`. Existing settings were preserved. Raw diagnostic files stay in `/private/tmp/rexlink-performance-20261102` and are not committed because UI captures can contain user terminal titles.


## Automatic active-pane ownership and Ctrl+J — 2026-11-02

All 64 JVM tests, 27 non-live native tests, Clippy with warnings denied, debug/release builds and release lint pass. New policy tests cover readiness/dimensions, one claim per activation, external ownership changes, explicit viewing through resume, hidden/paused clients and stale owned frames. Native target-guard tests cover wrong session/block/navigation IDs, hidden/paused take and resize commands. The native lifecycle fixture passes on both owned servers; the two-server native isolation fixture passes after hidden panes began explicitly releasing ownership.

All 46 emulator checks pass. The two-server Android fixture verifies automatic take/resize, old-server release, explicit viewing mode, Activity-entry takeover, returning across servers and foreground recovery. A takeover-metadata race in the test was corrected by waiting for the final notice/process data before asserting terminal-only output leaves chrome unchanged. The stale-owned-frame activation race was fixed and covered. No user terminal input was used; only disposable fixture sessions were driven and removed. The separate Ctrl+J test holds Ctrl, slides onto the ninth cell and releases; the emitted line-feed byte is checked by JVM coverage. Existing Ctrl W selection, popup cancellation, repeat/Shift, links, selection and pane tests still pass.

Signature, non-debuggable setting, ART profiles and 16KiB native alignment pass. Installed on S22+ RFCT21390SD and verified by installed base APK hash `6faa75d532b586ecaf6cbeece9e6dcd41b4f2fbfd5b129fe6b0f8db7ac20e332`. Shareable APK `dist/Rexlink-0.1.0-arm64-release.apk` and checksum were updated; existing settings preserved.


## Preset themes, 1 sp sizing and reconnect ownership — 2026-11-02

68 JVM checks pass, including all 41 catalog entries plus two original defaults, wrong/missing-ID fallback, 256-color mapping and preserved RGB, default UI identity and invalid-color rejection. All 50 emulator checks pass, including theme selection without changing mode, independently persisted light/dark choices and font size, 1 sp controls/slider and light/dark sheet rendering. Both screenshot variants were visually inspected. Existing keyboard, popup, links, selection, terminal caches, panes and two-server ownership checks pass. The saved-preferences fixture uses a unique temporary store and removes it.

30 non-live native tests and Clippy with warnings denied pass. New ownership tests verify delayed resize APCs cannot override canonical ownership, unowned layout resizes are quiet while typing is rejected, and ownership confirmation clears only the stale ownership error. The extended live lifecycle fixture passes on both owned Linux and Mac servers: four reconnect/take/resize/type cycles per server, with another client's canonical takeover checked each time. Disposable sessions were removed; no user sessions or server configuration were changed.

Debug/release builds, release lint, signature, non-debuggable setting, ART profiles and 16KiB checks pass. The preset asset is 128,064 bytes uncompressed. Default-theme startup skips catalog parsing; no new phone startup or battery-performance claim is made. The headless emulator had disconnected before the first UI run; it was restarted and the complete 50-check run passed.

Installed on S22+ RFCT21390SD after USB reconnection and verified against installed base APK SHA256 `3794798a37c1d2d82fc9fd540bff3fbfb2a2cee0a8538e2d359fe403b3617aca`. Updated shareable `dist/Rexlink-0.1.0-arm64-release.apk` and checksum; phone settings preserved.


## Activity, network recovery and contextual ownership — 2026-11-02

74 JVM tests and 33 non-live native tests pass. Clippy with warnings denied, release lint and debug/release builds pass. All 56 emulator checks pass, including the new attention/seen actions, contextual takeover cancellation, ready feedback and retry states. A one-frame stale takeover notice found during validation was corrected. The live disposable Activity fixture and four-cycle lifecycle reconnect fixture pass against both owned Linux and Mac servers. Activity checks include a blocked report from an unselected session while the sheet is closed, local mark-seen and a changed report alerting again. Fixtures were removed; user sessions/server settings were unchanged.

In the optimized Mac host benchmark, repeated title updates with 256 cached reports produce 431-byte JNI packets both open and closed; median publication/encoding is 5 µs, p95 5 µs open and 6 µs closed. This is a metadata microbenchmark, not a whole-app or phone speedup. The list also memoizes filtering/sorting and pauses the underlying working-tab animation while Activity is visible. Physical-device network transitions and new frame-time measurements have not been performed for this follow-up.

An initial feature release was installed on S22+ before follow-up checks, per requested delivery order. The final release adds the transient-feedback overlay and immediate notice dismissal. Its installation attempt found the phone disconnected; USB reconnection is pending. The shareable artifact is `dist/Rexlink-0.1.0-arm64-release.apk`, SHA256 `9d918582e01106bbd0817116c7f694905e17d1c95a3d1a58776c7c0e69a0ee19`. Signature, non-debuggable packaging, ART profiles, ARM64 JNI exports and 16KiB alignment pass. `ACCESS_NETWORK_STATE` is the only new platform permission.

Activity visual follow-up: the six-dp eye attention dot is inset seven dp so it remains entirely inside the circular button. Per-row acknowledgment uses a compact “Mark seen” text action instead of the tick glyph, and “Seen” status uses the muted foreground. The updated release builds and passes static APK verification. Installation was attempted before follow-up checks; S22+ remains disconnected.

Three-line header: title now uses the available width, the working directory has its own smaller muted line when nonempty, and the session strip shows session · server. The icon remains vertically centered against the stack. Normal text measures approximately 53 dp tall with a directory and retains the 50-dp minimum without one. Release build and APK verification pass; the five existing SessionCatalog tests pass with the new label order. Installation was attempted immediately after building; S22+ was disconnected.


## Monorepo, Apache 2.0 and Activity badge diagnosis — 2026-11-02

Both source histories were merged without rewriting IDs: 6 probe commits and
138 Android commits are reachable, including separately signed commits for the
previously pending Activity, reconnect and three-line header changes. Original
checkouts remain intact; their ignored caches were cloned only as local build
accelerators. The old Android daemon-JVM settings were not imported. There are
no nested Git repositories or source/build dependencies on the old checkouts.
Signed migration commits were cryptographically verified with the configured
public key. The original signatures and commit objects remain unchanged.

The source-build command in the root README succeeds from the monorepo root.
Release build, 74 JVM tests, release lint, all-feature Rust workspace tests
(45 passed; live tests remain opt-in), Clippy with warnings denied, and the actual
host JVM/JNI smoke check pass. The smoke assertion was corrected from RXT2 to the
already existing RXT3 format; the protocol format was not changed by migration.
The standalone probe build also succeeds. APK signature, stable Android package,
JNI exports, ART profile and 16KiB alignment checks pass. The built APK contains
byte-identical copies of the root Apache 2.0 LICENSE and NOTICE. The artifact
hash and history audit are recorded in validation/apk.json and validation/monorepo.json.

The six targeted emulator Activity checks pass against an owned disposable
Linux-server fixture, including actual blue-pixel rendering for a blocked report,
local acknowledgment without opening a terminal, and the hidden-sheet path.
The committed runner reproduces the check without an old repo dependency and
removes its own session. The user's “Working while Codex says Action required”
case is a missing structured blocked transition, not reproduced by the normal
blocked-report fixture. No screen-text heuristic, Codex configuration or server
authentication/configuration change was added.


## Session rename and shorter overflow menu — 2026-11-02

Added **session row ⋮ → Rename session** with a prefilled field, server label,
Unicode-aware 128-character validation, Save and Cancel. The request stays bound
to the original server/session. The live native fixture passes on both owned
Linux and Mac servers: active/background rename, whitespace/Unicode,
external-client label events, invalid controls and missing-session errors.
The active block, ownership and 40×20 grid remain unchanged. Only its own two
fixture sessions were created and removed on each server.

All 76 JVM tests and three targeted emulator rename tests pass. The UI checks
cover nested clicks without selecting/closing a session, prefill, validation,
trimmed Save and Cancel without sending. Native unit tests, all-feature Clippy
with warnings denied, debug/release builds and release lint pass. Redundant
Switch pane / Close pane / Close tab entries were removed from the top ⋮; the
pane/tab controls remain. Feature and menu changes were committed separately.

ADB reported Success installing the release on S22+ RFCT21390SD immediately after
building and before follow-up checks, using -r to retain app data. The phone
subsequently disconnected before installed-APK hash readback, so that optional
comparison was not completed. The shareable APK has SHA256 `e0bf1a516d77dcf19e713c0278ef34a5e133a7e48e7d08213808435433f856f6`.
Signature, non-debuggable packaging, JNI exports, ART profiles and 16KiB alignment
pass. The APK/checksum are in dist/ and validation/apk.json records this release.


## Fanned program icons and circular swiping — 2026-11-02

Multi-pane stacks expose their back tiles horizontally and use stable -6° front,
+3° near-back and +6° far-back rotations; single-pane icons remain upright.
A rendered header screenshot was inspected, then the existing header gesture
check was rerun after rotation. The same component styles both header and tabs.

Session paging uses two sentinel pages with logical-index rebasing after settling.
Only a change of logical session emits navigation. Catalog membership changes
recreate the pager at the current session. Pane previews wrap within their tab;
first/last neighbors are prefetched. Two-pane rings render one neighbor on the
gesture side, maintaining unique AndroidView keys. Animation direction follows
the swipe rather than the numerical destination index.

All 77 JVM tests and nine targeted emulator gesture tests pass. The checks
cover bidirectional first/last wrapping, two-item rings, single/empty catalogs,
catalog changes without unsolicited navigation, target-badge continuity and
cancellation at the boundary. The one-test header rerun after the rotation also
passes. Debug/release builds and release lint pass. No Rex protocol, ownership
policy or server settings were changed.

Both the initial sideways-stack release and the final rotated-stack release
were installed on S22+ RFCT21390SD immediately after building. ADB reported
Success. Follow-up static verification passes signature, non-debuggable APK,
JNI exports, ART profiles and 16KiB alignment. The final shareable release is
`dist/Rexlink-0.1.0-arm64-release.apk`, SHA256 `8b5e6689f15dc9a09436c79e41744774564cded33af7aaed33700f8593ce3158`. Icon styling, circular navigation,
and the angle correction were recorded in separate signed commits on main.


Tab spacing follow-up: the explicit icon/title gap is reduced from 5 dp to 2 dp. The release builds and passes static APK verification. It was installed on S22+ RFCT21390SD with ADB Success before follow-up checks. Release SHA256: `9655d431561287dbb5398dcf938817988685ec2a86626a9007fedc3fd0914976`.


Header icon slot follow-up: the top bar reserves a constant 52×35 dp slot for one, two or three program tiles, centering their painted widths inside it. Other ProgramStack callers retain their existing sizing. The release builds and passes static APK verification. Installation was attempted but the S22+ was disconnected; the user confirmed it is not currently connected. The shareable APK is ready for later installation, SHA256 `272c35fef943adcb2f0bfeadb76e8f346c6a6ffca5419d0581d8a5975bcc2bde`.


## Cellular typing backlog follow-up (2026-11-02)

Input RPC acknowledgments are dispatched by request ID instead of being awaited
inside the actor’s command handler. The output/render regression deliberately
holds an acknowledgment until VT has arrived and a frame containing its marker
has been published; output remains responsive with zero replies received.

An overlapping raw-write experiment exposed real byte reordering on the Linux
Rex server. Production uses the documented ordered `block.event` key path
instead, with an eight-request window. Both owned Linux/Mac servers pass raw
PTY capture of ordered UTF-8, long text, emoji, all C0 bytes, DEL, arrows, paste
wrappers and post-paste typing. Each fixture was destroyed. The live lifecycle
and canonical-ownership/reconnect typing fixture also passes on both servers.

56 non-live Rust workspace tests pass. Added checks cover response ID
demultiplexing, out-of-order replies, retained event sequences/RPC error codes,
timeouts without retransmission, bounded queues, text batching boundaries,
stale pane/connection bindings, rendering before acknowledgment, ownership
rejection, paste barriers and reconnect without replay. Clippy with warnings
denied and the JNI smoke check pass. All 77 JVM tests, debug/release builds and
release lint pass.

In the controlled loopback actor benchmark, 24 ordered inputs with 100 ms
held replies drain in 322 ms with eight in flight versus 2,505 ms with one.
Batching is disabled for that comparison. This measures acknowledgment backlog
under synthetic delay, not first-key latency or physical-phone pixel timing.

The emulator’s two-host Android test passes with the production JNI/connection
code: hidden-view input rejection, independent sessions, selection, automatic
ownership and foreground recovery. Ten local-route input-dispatch-to-terminal-
state samples measured median 33.44 ms and maximum/p95 49.92 ms. This excludes
final pixel presentation and is not a physical-phone or cellular measurement.

The release was installed on S22+ RFCT21390SD with ADB Success immediately after
building and required checks. Its installed base APK hash matches the shareable
`dist/Rexlink-0.1.0-arm64-release.apk`:
`caafc78d6ef0756844e971b35054a32e1719a9ace19e96eff3a8ab4fb701e806`.
Static checks pass the release signature, non-debuggable setting, JNI exports,
ART profiles and 16 KiB library/ZIP alignment. This release also delivers the
previously prepared fixed-width header icon slot. The header fix and latency
implementation were recorded as separate signed commits on main after the
user restored 1Password signing; signing was not disabled.


## Physical S22+ cellular diagnostics (2026-11-02)

The connected S22+ was confirmed on cellular through Tailscale, with Android
reporting an LTE anchor and NR_NSA display override (5G NSA). Both the release
app UID and diagnostic shell UID were covered by the VPN. The installed release
was left in place; no preferences, current session selection, server settings
or network settings were changed.

A temporary release-mode ARM64 executable imported the unchanged production
Actor/Control/Replica implementation and ran directly on the phone. It selected
and owned only disposable raw Python PTYs, one per explicitly owned server.
Five warmups preceded 30 isolated measured keystrokes. Each server also received
an 80-key burst at 25 keys/second with the production eight-request window.

| Measurement | Mac server | Linux server |
| --- | ---: | ---: |
| RPC round trip, median / p95 (25 calls) | 92.8 / 117.7 ms | 90.5 / 255.0 ms |
| Input to native frame, median / p95 (30 keys) | 97.7 / 114.4 ms | 98.5 / 232.1 ms |
| Slowest isolated native echo | 240.9 ms | 351.3 ms |
| Final key drain after 80-key burst | 97.1 ms | 101.6 ms |
| Full burst duration, including 3,160 ms injection schedule | 3,260.0 ms | 3,265.8 ms |

All burst counters reached their expected totals. Neither run showed a sustained
native input backlog at that tested rate. The similar RPC and native echo
medians are consistent with network-dominated native-path latency, with
occasional jitter. These separate sample distributions cannot identify an exact
local processing cost. They are not input-to-pixel measurements and exclude
Android key delivery, JNI transfer, Kotlin state handling and Canvas drawing.
There was no pre-change phone A/B run.

Fifteen ICMP probes per server reported no loss in this short sample, with means
158.5 ms (Mac) and 165.4 ms (Linux); maxima were 284.5 and 321.5 ms. Different
sampling intervals/traffic patterns mean these should not be treated as the
same population as the RPC/echo samples.

A separate physical Mac-to-phone `tailscale ping` stayed on DERP(sin) for all
five probes (94–162 ms), and reported no direct connection. This is evidence
about that specific peer pair, not proof of the embedded Rex virtual nodes'
routes. [Tailscale documents](https://tailscale.com/docs/reference/connection-types)
that relayed paths can have higher latency than direct UDP paths. Checking
direct connectivity is a reasonable next network investigation; no routing or
firewall changes were made.

The release app moved to the launcher during CPU/frame sampling; gfxinfo
reported zero rendered frames. That sample cannot support claims about rendering
jank, foreground CPU or battery use. A separate foreground drawing trace remains
necessary. Both disposable sessions and all temporary phone diagnostic files
were removed. The existing app and user sessions were not driven with test input.

Full results and methodology: [s22plus-cellular-2026-11-02.json](validation/s22plus-cellular-2026-11-02.json).


## Physical S22+ Wi-Fi comparison (2026-11-02)

The user switched the same S22+ to Wi-Fi. Android’s default and VPN underlying
networks were verified as Wi-Fi both before and after testing. The exact same
temporary ARM64 executable, unchanged production native source, servers,
fixtures, sample counts and key-injection schedules from the cellular test were
reused. The installed release and user session selection were left untouched.

| Native input to published frame | 5G median / p95 / max | Wi-Fi median / p95 / max |
| --- | ---: | ---: |
| Mac server, 30 isolated keys | 97.7 / 114.4 / 240.9 ms | 23.1 / 33.2 / 37.2 ms |
| Linux server, 30 isolated keys | 98.5 / 232.1 / 351.3 ms | 22.9 / 32.7 / 53.9 ms |

Wi-Fi medians were 4.2–4.3 times faster in this sample. Neither 80-key burst
at 25 keys/second developed a sustained queue. The final Wi-Fi keys drained in
23.4 ms (Mac) and 22.9 ms (Linux), compared with 97.1 and 101.6 ms over 5G.
Wi-Fi RPC median/p95 timings were 14.9/19.0 ms (Mac) and 22.4/26.1 ms (Linux).

Fifteen ICMP probes per server again reported no loss. Means/maxima were
30.3/105.9 ms for Mac and 62.4/113.1 ms for Linux. ICMP uses a different traffic
pattern from the warmed typing samples, so these distributions are not directly
interchangeable.

The separate physical Mac-to-phone Tailscale probe established a direct LAN UDP
path for all five Wi-Fi probes (11–85 ms), whereas the earlier cellular probe
stayed on DERP(sin). This does not prove either embedded Rex node’s actual route
or isolate the relay’s cost from cellular/access-network differences. The
sequential tests do show a substantial network-path contribution to native
echo latency and variability. A short sample cannot rule out later Wi-Fi spikes.

These remain native dispatch-to-frame measurements, excluding Android input,
JNI transfer, Kotlin, Canvas and final pixels. No rendering/CPU/battery claim
is made. Both disposable sessions were destroyed and temporary phone binaries
removed. No network/server settings or application code were changed.

Full results, executable hash and comparison:
[s22plus-wifi-2026-11-02.json](validation/s22plus-wifi-2026-11-02.json).


## Physical S22+ release drawing trace (2026-11-02)

The installed, non-debuggable release was traced directly on the unlocked,
foreground S22+. No APK or application code was replaced. A disposable
two-pane Python PTY repainted 23 rows of colored changing text at up to 30 Hz;
the UI workload included four pane swipes, a second output burst and vertical
scrolling. This is a heavy repaint/navigation stress test, not ordinary Codex
typing. The observed display mode was 120 Hz; thermal status after testing was 0.

The final 22-second Perfetto capture used separate 16 MiB frame-timeline and
64 MiB graphics buffers, existing decode/draw markers, and no UI hierarchy
inspection inside the recording window. Smaller earlier captures were
superseded. The final trace reports no data-loss/error/overrun/buffer-overwrite
counters and its final flush succeeded. An informational discarded-chunk count
alone does not establish lost trace data.

| Measured section | Samples | Mean | p95 |
| --- | ---: | ---: | ---: |
| Native update read/packet decode (`Rexlet.decodeUpdate`) | 597 | 1.90 ms | 4.77 ms |
| Terminal Canvas recording (`Rexlet.drawTerminal`) | 482 | 3.47 ms | 4.94 ms |
| HWUI RenderThread `DrawFrame` wall duration | 662 | 9.39 ms | 14.51 ms |
| FrameTimeline app work, including GPU/buffer post | 725 | 15.83 ms | 22.79 ms |

The frame timeline records 404 app deadline-miss flags, 347 compositor CPU
deadline-miss flags, 324 buffer-stuffed frames and one dropped frame. Flags
overlap; this is mixed attribution. Android gfxinfo separately reports 741
frames and 420 janky frames (56.68%), with duration buckets p50 17 ms and p95
23 ms. Its collection window extends slightly beyond Perfetto, so counts are
not identical. Most problematic frames were late rather than dropped.

The trace shows measurable UI work beyond decoding and Canvas recording.
RenderThread command work and update/presentation pacing are useful next
optimization targets. It does not establish that the app adds exactly 23 ms to
each key: FrameTimeline app work starts at Choreographer callback and ends at
max(GPU completion, buffer post). There are no revision-linked markers to pair
native publication with the final display frame, so exact native-to-pixel and
key-to-pixel delays remain unmeasured. Decode/draw percentiles are separate
populations and must not be summed.

The original selected session and its ownership mode were restored; only the
temporary test session was destroyed. No test input was sent to user terminals.
Phone trace/config files were removed. The final raw trace is retained locally
in ignored `.build/ui-diagnostic/release-clean.pftrace`, not committed.
Aggregate results: [s22plus-ui-drawing-2026-11-02.json](validation/s22plus-ui-drawing-2026-11-02.json).


## Retained terminal rows: measured release follow-up (2026-11-02)

Hardware canvases on Android 10+ now retain one RenderNode display list per row
instead of replaying a Picture into the parent display list each draw. Changed
rows reuse their recording, discarded hardware lists are rebuilt, and geometry
changes/detach release cached hardware resources. Android 9 and software
canvases retain the Picture fallback. Background fills matching the view’s
already-cleared background are skipped; colored application edges/padding
remain intact.

The original UI publication/invalidation pacing remains unchanged. A combined
experiment removing waits reduced some section costs but did not clearly
improve complete-frame timing, so those scheduling changes were backed out.
The final change is confined to row rendering.

All 77 JVM tests, debug/release builds and release lint pass. The initial 34
targeted emulator checks include pixel/background/typography/selection/link/pane
regressions and the two-server input/lifecycle fixture. Nine final GPU/background
checks pass after the last renderer adjustment. The new tests compare actual
GPU pixels against Picture replay at 0, .25, .5 and .75 pixel offsets, verify
re-recording/discard recovery, and compare a changed cached hardware terminal
with a freshly drawn one. Offscreen-renderer destruction can invalidate child
lists; the test harness and real view both check validity before replay.

Matched S22+ releases used the same temporary two-pane workload. Broad gfx
traces were useful for attribution but added compositor overhead; the final
20-second comparison uses FrameTimeline plus app-only markers:

| Metric | Original release | Final renderer release |
| --- | ---: | ---: |
| Terminal draw mean | 3.48 ms | 3.01 ms |
| Terminal draw p95 | 5.08 ms | 4.31 ms |
| Whole-frame work mean | 13.73 ms | 13.11 ms |
| Whole-frame work p95 | 19.59 ms | 18.93 ms |

The sample shows 13.5% lower average Canvas recording time and 15.1% lower p95,
with only modest complete-frame changes. It does **not** establish a substantial
pacing/jank improvement: gfxinfo deadline-miss percentages are 56.90% before
and 57.79% after; FrameTimeline dropped-frame counts are zero and one. This is
one sequential stress-test run per version, not repeated randomized trials or
ordinary typing. Exact key/native-publication-to-pixel latency and battery
effects remain unmeasured. No trace data-loss/overwrite errors were reported.

The tested final release is installed on S22+ RFCT21390SD; its installed base
APK hash matches the shareable `dist/Rexlink-0.1.0-arm64-release.apk`:
`32c5ab86f5746bd8dd23ad2cc93bb7f8cbc82f1f45daf4a016230a4beb0cd5d0`.
Static release/signature/ART-profile/16 KiB alignment checks pass. The original
selected session/control state was restored, the disposable session destroyed,
and temporary phone traces removed. Raw traces remain local in ignored
`.build/ui-renderer-ab/`.

Full measurements: [s22plus-ui-renderer-improvement-2026-11-02.json](validation/s22plus-ui-renderer-improvement-2026-11-02.json).

## Display-synchronized terminal presentation (2026-11-02)

Live screens now use a bounded latest-complete-screen slot with at most one
pending Choreographer callback. Decoded control/navigation state applies before
presentation. Native VT parsing and input ordering are unchanged, as is the
native 16 ms snapshot-production cap. The callback pushes the screen directly
into the bound Android view and invalidates it within the display callback;
Compose observes target, geometry and scroll affordances rather than every
cell/cursor change. Superseded complete snapshots can be replaced without
losing terminal bytes. Target/epoch/navigation guards and cancellation cover
navigation, hidden state, pause, connection changes and close. A stale Compose
update cannot overwrite the newer live screen; cached previews remain until a
complete live screen is ready.

All 82 JVM tests, 72 emulator UI checks and the live two-server emulator fixture
pass. Debug/release builds and release lint pass. New tests cover one-callback
burst coalescing, stale callback cancellation, reentrant updates, idle behavior,
chrome recomposition and preview/live handoff. The separate live attention
fixture was not rerun for this presentation-only change.

Five sequential 20-second S22+ stress traces used the same disposable two-pane
fixture, colored output up to 30 Hz, four pane swipes, restart and scroll. Tests
ran baseline, candidate, warmed candidate, reverse-order baseline and finally a
baseline with correlation markers only. The original baseline whole-frame mean
changed from 11.87 ms to 13.44 ms on repetition, showing substantial run-to-run
variation. Candidate means were 13.00 and 13.11 ms. These samples do **not** show
a clear overall jank/whole-frame improvement: gfxinfo late-frame percentages
were 53.77–58.24% for the baseline and 55.38–56.64% for the candidate, with one
or two FrameTimeline dropped frames per run. This is a synthetic stress test,
not a measurement of normal typing. Missing RenderThread sections in minimal
tracing are not zero-cost samples. No data-loss/error/overwrite counters were
reported.

Correlation markers reveal the narrower improvement this change targets:

| Decoded-ready to first Canvas recording completion | Baseline + markers | Candidate | Warm candidate |
| --- | ---: | ---: | ---: |
| Matched screens | 446 | 443 | 426 |
| Mean | 25.56 ms | 10.15 ms | 10.09 ms |
| Median | 25.56 ms | 9.50 ms | 9.68 ms |
| p95 | 30.45 ms | 16.36 ms | 15.65 ms |

The baseline published state after its display wait, then incurred another UI
handoff before drawing (17.29 ms mean from handoff to draw start); the candidate
pushes the live view during the callback (1.03/0.97 ms). Baseline tokens use
screen object identity; candidate tokens use native handle/revision. Each token
is paired with its first ready/presentation/draw, with monotonic ordering
checked. The diagnostic baseline had trace-only additions and was removed
before rebuilding the final source. These are decoded-ready-to-Canvas timings,
not native-publication, network/key-to-pixel or SurfaceFlinger presentation.
During pane movement an off-center live pane can be recorded too. Runs were
short and sequential, not randomized statistical trials. The change is kept
for lower UI handoff delay; overall frame pacing still needs further work.

The final source rebuild matches the physically tested candidate byte for byte.
The installed S22+ base APK and shareable
`dist/Rexlink-0.1.0-arm64-release.apk` both have SHA-256
`dff755a482dec5c4a8ca56d9159ea939f500e2345cfdab2b72cb8d8a58d82551`.
Signature, non-debuggable status, ART profiles and 16 KiB alignment pass. The
original session was restored under its current work label, with control
released/viewing mode. The client was restarted to recover stale selection
after fixture removal; no server was restarted. Only the disposable session
was destroyed, no test input went to user terminals, and temporary phone trace
files were removed. Raw traces remain local in ignored
`.build/display-coordinator-ab/`.

Full measurements: [s22plus-display-coordinator-2026-11-02.json](validation/s22plus-display-coordinator-2026-11-02.json).

## Terminal mouse taps (2026-11-02)

A controlled, mouse-reporting application now receives a touch tap as one left
press/release pair at the rendered grid cell. Ghostty encodes the negotiated
terminal mouse protocol. Both reports travel together through the ordered
`block.event` input path, with pane/navigation/connection bindings, queue bounds
and no uncertain replay. Mouse clicks form a queue barrier behind earlier input
acknowledgments. The native actor drops stale, hidden, paused, unowned and
mouse-disabled clicks. Legacy reports with non-UTF-8 coordinate bytes are
rejected rather than corrupting input, matching the existing wheel transport.

Web links retain tap priority, long-press still selects text, and horizontal
pane swipes/vertical scrolling send no click. Padding/outside-grid taps are
ignored in mouse mode. Pending taps are canceled if their target, grid,
mouse-reporting enablement or ownership changes. Normal/viewing-mode taps keep
the previous keyboard/take-control behavior. The keyboard button remains the
way to open the keyboard while controlling a mouse-enabled application.

Validation passes: 82 JVM tests, all 77 non-fixture emulator UI checks, final
five mouse checks after adding the ownership-loss guard, host workspace tests,
actual JNI/JVM smoke, Clippy with warnings denied, release lint, debug/release
builds, and APK signature/ART/16 KiB alignment checks. Native tests verify
negotiated SGR/legacy bytes, clamped coordinates, press/release pairing, snapshot
restoration, disabled mouse modes and ordered/bound click queues. An opt-in live
owned-server fixture verifies exactly one press/release pair reaches the PTY,
then confirms stale-pane and mouse-disabled taps are dropped. Only its
disposable session was created/destroyed; no input was sent to user terminals.
This did not exercise the user's particular Neovim/Lazygit configuration.

The final release is installed on S22+ RFCT21390SD. Its base APK hash matches
`dist/Rexlink-0.1.0-arm64-release.apk`:
`47909ac7d258b46ed14765734e622051f7c832542c0ea7894cc5bcebb9c0304c`.
Implementation commit: `184042c`. Reproduce the live check on an explicitly owned
server with `REX_TEST_SERVER=<server>` and the host Ghostty/Cargo environment
from BUILDING.md, then `cargo test --locked --manifest-path rexlink-core/Cargo.toml
-p rexlink-jni live_mouse_click_delivers -- --ignored`.


## Pane navigation progress overlay (2026-11-02)

Moved the conditional 2 dp loading indicator from the content Column into its
parent Box, aligned below the header. It now overlays the existing top inset
instead of reducing terminal height while a pane/navigation request is pending.
The same overlay covers connection/reconnection and creation progress. Release
build, release lint, signature/ART/16 KiB checks pass. This is a layout-only
change; native/input and renderer logic are unchanged. The release is installed
on S22+; its base APK matches the shareable dist APK: `bc430bf37d430098295c08844e1eb5e6047c0a8a4b34735272b358d58729dee6`.


## Stable three-line header (2026-11-02)

The compact header allocates fixed font-scaled slots for its title and directory,
and a fixed minimum session row that grows for large system fonts. The directory
slot remains reserved when metadata is temporarily empty. Fallback star/emoji
fonts can move a title baseline even within a fixed-height box (reproduced as a
3 px change with ✳). The title therefore places its text against a cached
primary-font reference baseline while keeping reported title characters intact.

Five emulator checks pass: animated star/braille/emoji title variants keep their
allocated row size, actual placed baseline, directory/session positions and
header size unchanged at 1×, 1.5× and 2× font scaling; temporary directory absence
retains its slot; existing session wrapping/catalog behavior remains intact.
The geometry check distinguishes variable fallback glyph bounds from the fixed
line allocation and verifies actual placed coordinates. Release build, release
lint, signature, ART profiles and 16 KiB alignment checks pass. Native/input and
terminal renderer logic are unchanged. The final release is installed on S22+;
its base APK matches the shareable dist APK: `2504eae05ec3ccc8261f8f66bb106ba859e5379f337dfbfafe38124cdce5c4d8`.


## Terminal symbol font fallback (2026-11-02)

The bundled Nerd Font lacks U+23F5/U+23E9 and related media controls in its cmap.
Added unmodified Noto Sans Symbols 2 as a bundled fallback, with pinned upstream
revision, checksum and SIL OFL notice. Android 10+ uses a process-cached font
family chain (four original Nerd styles first, symbols next, system fallback
last). Android 9 uses a bounded per-cell fallback resolver; row, cursor and
composing text share font selection. Primary text still defines the integer
cell grid and baseline. Font families are cached without retaining an Activity.

Release/debug builds, release lint, signature/ART/16 KiB checks and all 81
non-fixture emulator UI checks pass. New tests verify media-symbol coverage in
all styles/sizes and the explicit legacy resolution branch, render a real
TerminalView triangle distinct from tofu, and verify following text stays on
its original pixels. Existing ASCII/Nerd Font advance, hardware row, selection,
link, mouse, gesture and header regressions pass. The legacy branch was tested
on the API 37 emulator, not on a physical Android 9 device. The generated footer
image was visually checked: two yellow triangles render correctly. No new
performance comparison is claimed. Native and transport logic are unchanged.

The release is installed on S22+; its base APK matches the shareable dist APK:
`2057ac9d6c44523ae43cd0e899d6f23cd8c86280a4df3e95e6cb0c45a777ae66`. The APK grew by about 0.54 MB compressed.


## Separate rendering optimizations (2026-11-02)

Measured three changes separately: a logical-line URL-prefix precheck that avoids
inferred-link character maps on plain text, reuse of unchanged preview maps, and
a backdrop scrim replacing group-alpha pane fading. Wrapped/uppercase prefixes,
hidden/gapped text and OSC 8 labels remain correct. Preview insertion order,
leaving-frame precedence and the sixteen-frame limit are preserved. GPU tests
compare overlapping interior text/background pixels against the original fade
in both themes. A shared motion counter separates gesture frames in every build.

All 88 normal JVM checks pass (one opt-in benchmark skipped), as do all 82
non-fixture emulator UI checks, debug/release builds and release lint. Five
isolated host benchmark runs and seven physical S22+ traces were collected.
Each phone version warmed with output for five seconds, then used the same
20-second disposable two-pane repaint/URL/OSC 8/swipe workload. Baseline and
combined versions were repeated to expose drift. No trace loss/error/overwrite
counters were reported. The expected app frame budget remained 8.33 ms.

| Phone measurement, mean | Baseline / repeat | Isolated relevant change | Combined / repeat |
| --- | ---: | ---: | ---: |
| Link-index work | 1.315 / 1.322 ms | 0.777 ms | 0.765 / 0.756 ms |
| Preview-cache work | 0.060 / 0.058 ms | 0.033 ms | 0.035 / 0.037 ms |
| Terminal drawing | 3.144 / 3.262 ms | links: 2.804 ms | 2.718 / 2.799 ms |
| Whole-frame work | 13.137 / 13.353 ms | varies | 12.532 / 13.333 ms |

The repeatable gains are about 42% less link-index time and 14% less terminal
draw time. Link work is nested in draw time; these are not additive. Host
allocation measurements went from about 121 KB to 6 KB per changed 30x80
linkless screen, and about 875 to 16 bytes per unchanged sixteen-entry cache
call, including test-loop overhead. Host timings varied between JVMs and are
not phone latency measurements.

The isolated scrim run did not establish a reliable swipe-time gain. Gesture
p95 was 17.50/17.90 ms for baseline, 17.22 ms for scrim alone, and 14.99/18.49 ms
combined. Overall GFX jank also stayed within run variation. The scrim is kept
for avoiding group-alpha compositing with compatible visuals, without claiming
measured GPU-memory, battery or pacing gains. These short sequential stress
runs do not establish ordinary typing or native/key-to-pixel improvements.
Refresh-rate policy, native snapshot cap, transport and ordered input remain
unchanged. Separate live server fixtures were not rerun for this UI-only pass.

The combined release is installed on S22+; installed base APK and shareable
`dist/Rexlink-0.1.0-arm64-release.apk` match SHA-256
`c96de12d3c3fc843ed5a4cbf72db5b38ae35546b80864eee8a585898ba6d225c`.
Signature, non-debuggable status, ART profiles and 16 KiB alignment pass. The
original Ubuntu session/control/keyboard mode was restored, only the disposable
session destroyed, and temporary phone traces removed. Private raw traces and
variant artifacts remain in ignored `.build/render-pass/`.

Full results: [s22plus-render-first-pass-2026-11-02.json](validation/s22plus-render-first-pass-2026-11-02.json).


## Background attention notifications — 2026-11-02

Added opt-in **⋮ → Background alerts**, off by default, with Android notification
permission requested on enable. A low-importance ongoing foreground-service
notification reports connected-server count and has a Stop action. Attention
alerts use a separate high-importance channel, a private lock-screen presentation,
and one-use private notification destinations bound to the saved server endpoint,
session and pane. Foreground requests, title animation, reconnecting snapshots
and already-seen report versions do not generate repeated alerts. Resolving a
request removes its notification. No completion/running alerts or server push
infrastructure were added.

The service uses one independent native monitor per explicitly connected saved
server. Monitor mode is irreversible for that handle and rejects terminal
selection, ownership, resizing, input, theme changes and terminal preview commands.
It publishes Activity metadata without a PTY data connection or terminal replica.
Existing UI pause/release behavior is retained. Stop, disabling alerts, removing
all connected servers, or removing the app task ends monitoring. There is no boot
receiver or sticky service restart. Observer cleanup destroys its JNI handle;
network recovery uses the native reconnection path.

93 JVM tests pass (one optional benchmark skipped), as do 59 non-live native tests,
Clippy with warnings denied, JVM/JNI smoke, release lint and release APK checks.
The extended live native Activity fixture verifies initial and subsequent attention
reports with no selected pane, frame or control in the monitor. All 82 existing
emulator UI checks pass. Two new emulator checks cover private/replayed destination
tokens and a real owned task through foreground suppression, background delivery,
title-only deduplication, resolution, screen-off delivery, notification navigation,
repeat suppression and Stop. Only disposable fixture terminals received test input.

On S22+ RFCT21390SD, the installed release matches the verified shareable APK.
The normal notification-permission flow was exercised. A disposable approval
request arrived while backgrounded in one observed 0.80-second sample (including
host CLI submission and detection); tapping its notification opened that exact
fixture session and foreground entry cleared its alert. The original phone session
was restored. A fresh request also arrived with `deviceLocked=1` in one 2.15-second sample, and
resolving the fixture cleared it. These are individual observations, not latency
guarantees. The final build additionally reuses the existing workspace when the
ongoing notification is tapped; the emulator integration test covers this.
The JSON report distinguishes the phone delivery-test artifact from that final
installed artifact.

A separate emulator check forced deep IDLE with no outstanding fixture alert;
a new request appeared within the four-second observation and remained present
after leaving idle. Battery/idle overrides were restored. This short test does
**not** establish delivery reliability during prolonged Doze or OEM power saving.
The app does not change battery exemptions or hold a permanent wake lock. Android
and connectivity can delay alerts; the setting and README state this limitation.
See `validation/background-alerts.json` for the exact checks and release hash.


Launcher follow-up: replaced the artwork with the exact user-supplied white
wireframe dinosaur on blue, retaining its source hash and provenance. Android
centers the unmodified PNG over a matching blue adaptive background. Notifications
use a simplified transparent white vector head with the prompt eye; Android 13+
themed launchers use the same monochrome mark. The root README icon follows the
same source asset. The ongoing-notification PendingIntent now has a distinct
action so an older installed build's cached intent cannot retain obsolete launch
flags; both notification paths return to the existing workspace.


## Light blue logo — 2026-11-03

Replaced the launcher/README logo with the exact user-supplied close-up wireframe
dinosaur PNG on light blue. The adaptive surround uses the sampled background
color `#9BCBFB`; source/packaged checksums and preparation provenance are recorded
in `artwork/rexlink/metadata.json`. The existing matching monochrome notification
and themed-launcher mark is retained.

The focused Android adaptive-launcher draw check passes, and its actual 192-pixel
render was visually reviewed. Release lint and signature, ART-profile and 16KiB
packaging checks pass. Installed on S22+ RFCT21390SD with its data preserved; the
installed base APK hash matches the shareable release. No terminal or notification
behavior changed. See `validation/logo-2026-11-03.json`.


## Transparent dinosaur icon on white — 2026-11-03

Used the user's final transparent blue paper-style dinosaur PNG directly, with
its alpha preserved and no raster editing. Android supplies the requested white
adaptive background. The foreground inset is 22% to keep the head, tail and feet
inside circular launcher masks. The root README uses the same transparent asset.

The Android launcher inflation/draw check passes, and the actual 192-pixel render
was visually reviewed on white. Release lint and signature, ART-profile and 16KiB
packaging checks pass. Installed on S22+ RFCT21390SD with app data preserved and
verified its base APK hash against the shareable release. Source/provenance and
checks are in `artwork/rexlink/metadata.json` and
`validation/logo-transparent-2026-11-03.json`.


## Close-up paper dinosaur icon — 2026-11-03

The latest user-selected PNG contained an opaque checkerboard. Removed that
background with the built-in image tool in background-extraction edit mode,
retaining transparent alpha over Android's white adaptive background. The supplied
original, source/output hashes and exact edit prompt are retained under
`artwork/rexlink/`. The README uses the prepared image.

The focused Android launcher draw check and release lint pass. The actual
192-pixel launcher render was visually reviewed on white; no checkerboard remains
visible. Release signature, ART profiles and native-library alignment checks pass.
Installed on S22+ RFCT21390SD with data preserved and verified the installed base
APK hash against the shareable release. See
`validation/logo-paper-2026-11-03.json`.


## Sharper header and tab program icons — 2026-11-03

Catalog program tiles now replay native Canvas vector paths from the retained
SVG sources at their actual display size and rotation. This removes bitmap
downscaling and the added fuzzy shadow around the catalog tiles. Original colors,
alpha-mask logos, gradient bevels, stack angles, compact sizes and fixed header
slot are preserved. Fallback icons already use native/vector drawing. Paths and
shaders are parsed once and cached as Android Pictures; drawing does not parse
JSON or allocate a new bitmap per frame. The offline generator is documented in
BUILDING.md and rejects unsupported SVG effects.

All ten tiles were compared against the retained 488×384 PNG references using
visible premultiplied RGBA. Mean absolute channel difference ranges from 0.35 to
7.19 on a 0–255 scale; small native and raster renders were visually compared at
34×27dp and 22×18dp, including rotated stacks at density 3. The native borders and
logos avoid the raster reference's jagged/soft edges. This is a visual rendering
change; no whole-app speed or battery improvement is claimed. Fixed slot checks
cover one, two and three panes.

All 85 non-fixture emulator UI checks pass, including new vector/color/layout
coverage and the existing pane/header, typography and launcher checks. JVM tests
and release lint pass. Release signature, ART profiles and 16KiB packaging checks
pass. Installed on S22+ RFCT21390SD with data preserved and verified its base APK
hash against the shareable release. The latest user-supplied app icon is included;
its opaque checkerboard was removed with the built-in image tool and its original,
prepared-image hashes and exact edit prompt are retained under artwork/rexlink.
See `validation/program-icons-2026-11-03.json` for checks and comparison values.


## Bottom-filled launcher portrait — 2026-11-03

Kept the user-confirmed artwork unchanged and adjusted only Android launcher
framing. Insets are left/right 18%, top 22%, bottom 14%: the portrait is larger
and lower, with matching horizontal/vertical scale. The body extends through the
bottom of the icon mask, so its flat source-image edge no longer leaves a white
gap underneath. Side cropping is intentional; the rendered head remains visible.

Both focused launcher checks pass, including a regression check that samples
foreground coverage immediately inside the bottom-center mask boundary. The
384-pixel adaptive render was visually reviewed. Release lint, signature, ART
profile and 16KiB packaging checks pass. Installed on S22+ RFCT21390SD with data
preserved and verified the installed base APK hash against the shareable release.
See `validation/launcher-framing-2026-11-03.json`.


## Synchronized tab reordering — 2026-11-03

Hold a tab, then drag left or right to reorder it. Other tabs move aside, and
holding near a strip edge scrolls to offscreen tabs. Holding and releasing without
dragging opens Move left, Move right and Close tab; accessibility actions provide
the same moves. Ordinary swipes continue to scroll the strip. Cancelled gestures
and session changes discard the preview.

A drop sends one session.move_window request. The displayed order stays optimistic
until its native acknowledgement; failures restore the authoritative order and
are not retried automatically. Rex broadcasts order changes to other clients.
Window reordering preserves program metadata instead of clearing icons/titles.
The selected pane, terminal stream, size and ownership remain unchanged.

All 92 non-fixture emulator UI checks pass, including six new held-gesture,
auto-scroll, cancellation, menu and accessibility checks. JVM checks: 95 passed
and one optional benchmark skipped. Native checks: 60 passed, 15 ignored; the new
live test was then explicitly run and passed against an owned disposable
three-tab session. It verifies server order, external changes, stable selection,
control and size, and rejected stale targets with acknowledgements. Native clippy,
host JNI smoke, release lint and APK signature/profile/16KiB checks pass.

Installed the release on S22+ RFCT21390SD with app data preserved, reopened it and
verified its base APK hash matches the shareable release. See
`validation/tab-reorder-2026-11-03.json` for results and the release hash.


## About and date-based versions — 2026-11-03

Added **⋮ → About Rexlink** with the app's UTC date version, numeric build code,
build type, Git revision and Copy build info. It also provides repository, Apache
2.0 license and third-party notice links, credits and the best-effort notice. The
version is generated at build time as YYYY.MM.DD; SOURCE_DATE_EPOCH can preserve a
release's version date. Android's versionCode advances to 2. Builds with tracked
local edits are marked; source exports without Git display an unknown revision.

Both focused emulator tests pass: displayed values match the installed APK's
PackageManager metadata, copy includes version/build/type/revision, and project
links and close actions work. The actual optimized release's About screen was
visually inspected and its version/build/source revision verified in UI. Release
lint and signature, baseline-profile and 16KiB packaging checks pass.

Built the release from clean source revision eefb173ac4: version 2026.11.03, build
2. Installed it on S22+ RFCT21390SD with data preserved, reopened it, and verified
its installed version/code and base APK hash against the shareable dated APK.
See `validation/about-2026-11-03.json` for results and the release hash. Native
terminal code was unchanged; no additional network or performance work was done.


## Shareable APK identities — 2026-11-03

Added :app:shareRelease, which builds an optimized release and copies it into
dist with date, numeric build code and source commit in its filename, plus a
SHA-256 sidecar. It uses the same configuration values as About; tracked local
edits add -modified and source exports use unknown. Standard Gradle APK paths
remain unchanged. Build code advances to 3 for this release.

Ran the task from clean source revision 92e0abac39. The resulting
Rexlink-2026.11.03-build3-92e0abac39-arm64-release.apk matches the release manifest
version/code and generated About revision. Its bytes match app-release.apk and
its checksum sidecar verifies. Static release signature, baseline-profile and
16KiB packaging checks pass. The prior installed build 2 was also copied under
its original eefb173ac4 identity with unchanged bytes. No app UI/native changes
were made; no additional device or emulator tests were needed for this task.


## Portside image integration — 2026-11-03

Added Preferences with per-Rex-server Portside URL/folder settings, a connection
check and an Open Portside shortcut. The small upload client uses Portside 0.3+'s
Tailscale-owner authentication; it stores no tokens and embeds no forwarding
engine. Existing Appearance, Font size, Background alerts and About are grouped
under Preferences. Clipboard images, IME rich content and the system photo picker
feed a bounded streaming upload. Progress is an overlay and leaves terminal grid
geometry unchanged. HTTP initialization, file I/O and cleanup run off the UI
thread. Redirects, proxies and automatic mutation retries are disabled; resolved
addresses are restricted to Tailscale/loopback.

Completed paths are pasted without Enter into the same active Codex/Claude pane.
Navigation invalidates automatic insertion; the result remains ready for explicit
insertion into its original controlled pane or copying. Image URI grants are
released once, cancelled/failed work cannot insert into a new target, and local
staged files are cleaned up. Size limits, file signatures and returned paths are
validated. No broad media/storage permissions are required; API 37 requests local
network access when the feature is used.

All 102 non-fixture emulator UI checks pass, including eight keyboard, clipboard,
provider-URI, cancellation, pane-switch and saved-settings checks. JVM checks: 100
passed, one optional benchmark skipped. The opt-in fixture test passed against
Portside's real Rust binary, with an owned temporary loopback listener and test
Tailscale identity responses. It verifies denied identities, exact PNG bytes
written to disk, returned absolute paths, insertion handoff and cleanup. No
running Portside service, real Rex session or sibling checkout was changed.

Release lint, signature, ART-profile and 16KiB packaging checks pass. Built from
clean source revision 1664c0c724, installed build 4 on S22+ RFCT21390SD with data
preserved, and matched the installed base APK hash to the shareable APK. The
temporary test emulator was stopped. Native Rex transport/rendering code was
unchanged; no additional performance work was done. See
`validation/portside-integration-2026-11-03.json` for results and release identity.


## Portside host/service addresses and Mac upload settings — 2026-11-03

Diagnosed separate Tailscale device addresses for Rex's embedded service and the
Mac's OS Tailscale node. The Rex service refused TCP 9417; Portside's actual node
answered but reported uploads disabled. Preferences now explicitly lists Rex
connections to associate with upload destinations, shows each Rex endpoint, and
does not guess the Portside address from it. Bare hostnames/IPs default to 9417;
explicit URLs keep their transport/port. Connection failures identify the target
and distinguish DNS, refusal, timeout and TLS failures.

JVM checks: 102 passed and one optional benchmark skipped. Release lint and static
APK checks pass. Installed build 5 from source 7c81930597 on S22+ with data
preserved and the installed base APK hash matching the shareable release.

With explicit user approval, added an opt-in upload-folder control and chooser
to the sibling Portside Mac hosting UI, persisted the optional folder, and passed
--upload-dir to its bundled Rust helper. Its five hosted-server tests pass under
the full Xcode SDK, including legacy settings, path validation, real uploaded
bytes and persisted folder settings. Built the Mac bundle without overwriting
the running executable. After the user approved relaunch, restored both running
forwards, enabled ~/PortsideUploads and verified live /v1/info advertises it with
the 100 MiB limit. Portside's pre-existing staged changes were preserved; our four
Portside source/doc/test edits remain in that working tree. See
`validation/portside-address-fix-2026-11-03.json` for results and APK identity.

## Preferences and Portside UI polish — 2026-11-03

Preferences now groups display, connection and app settings into rounded cards
with consistent headings, body text, icons, dividers and navigation rows.
Portside uses the same components and separates its upload editor title from
the server name. Uploads have a switch that saves the address and folder while
disabled. Existing configurations remain enabled; disabled uploads release
image permissions and show a setup notice without starting an upload.

Validation: 103 JVM tests passed and one optional benchmark skipped; release
lint passed. Sixteen focused UI checks passed across runs, covering settings,
model recreation, image input/upload, clipboard, appearance and About. Reviewed
light/dark screenshots and repeated settings previews at Android system font
scale 150%, restoring normal scaling afterward. Emulator-only test selectors
and dialog preview setup were corrected before the passing rechecks.

Installed optimized release build 6, source fc0be50f23, on S22+ with data
preserved and the installed base APK SHA-256 matching the shareable release.
The headless emulator was closed after testing. Details are in
validation/preferences-polish-2026-11-03.json and validation/apk.json.

## Direct tab and pane switcher — 2026-11-03

The dock button opens a compact bottom sheet directly, replacing the switch/new
operation dropdown. Tabs and Panes can be selected by tapping their headings or
swiping horizontally. Tabs preserve the focused program stack and title; Panes
shows the current tab's program icons, titles and directories. Active rows have
a blue marker. Each section has its own create button and close actions reuse
the existing confirmation dialog. The terminal pane indicator opens the same
sheet directly on Panes.

Validation: 103 JVM tests passed and one optional benchmark skipped; release
lint passed. Twenty-three focused emulator UI tests passed, covering the direct
button, six switcher scenarios, existing Activity behavior, tab reorder and
looping pane gestures. The six switcher tests passed again after final compact
height and inactive-heading color adjustments. Reviewed light/dark screenshots.
No user terminal sessions were modified by the fixture tests.

Built and installed release build 7, source 4696ec49eb, on S22+ with app data
preserved. Signature, alignment, profiles and release checks passed; installed
base APK SHA-256 matches the shareable release. The headless emulator was closed.
See validation/tab-pane-switcher-2026-11-03.json and validation/apk.json.

## Adaptive active-priority tabs — 2026-11-03

The tab strip measures the space remaining beside its dock controls. The active
tab receives up to 160 dp, reduced to at least a 104 dp title budget when that
lets the full set fit. Inactive tabs share the remaining width and show icons
only below 80 dp. Tabs retain 48 dp minimum touch targets. If these limits cannot
fit, the strip scrolls and brings the expanded selected tab fully into view,
including after a viewport resize. Font size is unchanged. Width allocation
does not depend on animated task titles; fixed icon slots avoid title shifts
when a tab gains panes. Existing reorder and close interactions remain.

Validation: 110 JVM tests passed and one optional benchmark skipped; release
lint passed. Twelve focused UI checks passed across runs: six adaptive-tab
scenarios plus six existing reorder scenarios. The adaptive checks passed again
after whole-pixel allocation fixed the packed strip's one-pixel edge clipping.
Density coverage includes fractional pixel ratios; visual checks cover a packed
light strip, roomy dark strip and selected overflow tab.

Installed optimized release build 8, source 4e08a0fe42, on S22+ with data
preserved. Static release checks pass and the installed base APK SHA-256 matches
the shareable release. The headless emulator was closed after testing. See
validation/adaptive-tabs-2026-11-03.json and validation/apk.json.

## Content-sized tab widths — 2026-11-03

Corrected build 8's oversized active allowance and empty inactive icon slots.
Tab sizing now uses cached text measurements with the displayed font weight,
icon slot and padding. Active and inactive widths stop at actual content, capped
at 160 and 120 dp respectively. A short title such as nvim therefore releases
space to inactive captions. If a caption cannot show useful text, its tab
collapses to the 48 dp icon target and that spare width is reused for labels
that can. Whole-pixel allocation, active-tab visibility and drag reorder remain.

Validation: 111 JVM tests passed and one optional benchmark skipped; release
lint passed. Fourteen focused UI tests passed, including the supplied nvim plus
two Codex tabs scenario, naturally short labels, packed strips, capped animated
titles, stable stacked icons, overflow selection, viewport resize and reorder.
The reorder fixture was narrowed so its edge-scroll test still overflows under
the tighter layout. Reviewed the corrected short-title preview and light/dark
docks. No user terminal session was modified by the fixture tests.

Installed optimized release build 9, source 2482e7fa3d, on S22+ with data
preserved; the installed base APK SHA-256 matches the shareable release. Static
release checks pass. The headless emulator was closed after testing. Details
are in validation/content-sized-tabs-2026-11-03.json and validation/apk.json.

## Accordion caption priority — 2026-11-03

Inactive captions now receive space outward from the active tab. Useful minimum
captions are revealed nearest first; remaining width grows captions by distance,
sharing space between equally close neighbors. Farther tabs collapse first.
Natural content sizing, width caps, 48 dp targets, overflow visibility, cached
measurement and existing motion/reorder behavior remain.

Validation: 114 JVM tests passed and one optional benchmark skipped; release
lint passed. Fifteen focused UI tests passed, including selection transferring
caption priority between either end of the strip, content sizing, short labels,
pixel rounding, overflow/resize visibility, stacked icons and drag reorder.
Pure tests also cover symmetric neighbors and a short distant caption competing
with an adjacent one. Reviewed left/right accordion previews matching the user
screenshot's intended ordering.

Installed optimized release build 10, source a4dd17cc4e, on S22+ with data
preserved. Static release checks pass and installed base APK SHA-256 matches
the shareable release. Headless emulator closed after testing. See
validation/adjacent-tab-priority-2026-11-03.json and validation/apk.json.

## Last workspace restoration and closer tabs — 2026-11-03

Workspace history was previously memory-only. SavedWorkspace now persists the
last session and pane per endpoint, plus each session's last tab and each tab's
last pane. Explicit selections are saved before acknowledgement, so immediately
backgrounding does not lose the requested destination. Resume/new connection
epochs resolve the saved location before recording native defaults or claiming
control. Stale requests from the old connection are replaced by the durable
intent. Saved IDs are checked against current sessions/windows; closed panes
and tabs fall back to an existing terminal. Unchanged metadata does not rewrite
history. The tab gap is now 2 dp, shared by pixel allocation and rendering.

Validation: 120 JVM tests passed and one optional benchmark skipped; release
lint passed. An initial nine-test persistence/settings recreation run passed,
and the final eighteen-test combined run passed for persisted workspaces,
adaptive tabs and reorder. Persistence checks verify an actual preferences
file, fresh history objects, per-server isolation, pending requests, planning
without overwriting saved state and deleted-pane fallback. JVM tests also cover
serialization, saved-session selection, transient states and redundant writes.
Fixtures use isolated settings/state snapshots and do not alter user sessions.
The accordion fixture remains constrained after the smaller gap allows more
caption space.

Installed optimized release build 11, source 66d1313382, on S22+ with data
preserved. Static release checks pass and installed base APK SHA-256 matches
the shareable release. Headless emulator closed after testing. Details are in
validation/workspace-restore-2026-11-03.json and validation/apk.json.

## Single fixed fluid pane indicator — 2026-11-03

Removed the position dots inside each sliding terminal and the duplicate dots
in the temporary pane badge. A single indicator now stays at the carousel
bottom. Its capsules exchange active width/color with a subtle connecting neck,
using the existing swipe/settle progress. The icon/program label uses one fixed
width and anchor directly above it. Cancel and wrap handoffs retain geometry.
The indicator still opens pane selection. Return-to-latest sits above the label
so the two controls do not overlap. The overlay does not change terminal size.

Validation: 126 JVM tests passed and one optional benchmark skipped; release
lint passed. Eleven focused Android tests passed, including fixed positions,
one indicator, stable terminal dimensions, pane-picker access, label/latest
separation, cancellation, looped swipes, motion handoff and existing compositing.
Reviewed progress 0/25/50/75/100% and the dark-theme midpoint. The animation is
small Canvas drawing driven by pane motion, with no new idle animation loop.

Built optimized release 12 from base 918f9265ae with local changes. Static APK
checks pass. Git commit/push remains pending because the existing 1Password
signer returned “failed to fill whole buffer”; no unsigned commit was created.
Phone installation remains pending because S22+ disappeared from ADB before
the install command could run. The headless emulator was closed after testing.
See validation/fixed-pane-indicator-2026-11-03.json and validation/apk.json.

## Ctrl+] and combined local release — 2026-11-03

Added ^] to the long-press Ctrl popup; it emits terminal byte 0x1D. The popup
uses three columns and a row count derived from its ten shortcuts. Gesture hit
testing uses the same dimensions and rejects the two empty cells in its last
row. The existing Ctrl+J/Ctrl+W, toggle, cancellation and read-only paths remain.

Validation: 126 JVM tests passed and one optional benchmark skipped; the existing
control-code test now also checks 29. Seven Android shortcut tests passed,
including hold/slide/release on ^] exactly once and release on an empty cell
without sending or toggling Ctrl. Release lint and static APK checks pass.
This build also includes the fixed liquid pane indicator covered by the prior
eleven-test Android run and its geometry/visual checks.

Built optimized local release 13 from base 918f9265ae, marked modified,
superseding local release 12 for distribution. No phone was connected to ADB at
the final device check, so installation remains pending. The source changes
are staged; commit/push remains pending because the existing 1Password signer
failed with “failed to fill whole buffer.” No unsigned commit was created.
Headless emulator closed. See validation/control-right-bracket-2026-11-03.json
and validation/apk.json for artifact details.

## Startup titles and program icons — 2026-11-03

Tabs, pane picker and header now use a neutral Loading tile until identity is
known, instead of implying a shell. Generated window/tab/block IDs (including
UUID formats) are filtered out of both reported and default titles. A bounded
per-endpoint, per-session presentation cache restores last-known programs, task
titles and directories while fresh metadata loads. Fresh process changes drop
old task titles; deleted panes are pruned. The cache never supplies live control,
activity, screen or image-insertion authority. A freshness marker makes an
identical newly confirmed identity observable to chrome consumers. New program
identities save once; animated titles flush on pause/close/session changes,
without per-frame preference writes or a periodic job.

Validation: 134 JVM tests passed, one optional benchmark skipped, release lint
passed. Seventeen focused Android tests passed: startup loading and per-endpoint
persistence, adaptive tab sizing, and tab reorder behavior. Cache tests cover
round trips, generated labels, stale process titles, raw-state isolation,
metadata freshness, bounds, malformed JSON and unchanged-frame write avoidance.

Optimized local release 14 was built from base 918f9265ae, marked modified. It
also includes the fixed liquid pane indicator and Ctrl+] from local releases
12 and 13. Installed on S22+ RFCT21390SD; the installed APK SHA-256 matches the
verified shareable artifact. App data was preserved; no phone launch or live Rex
pane manipulation was performed. Test emulator closed. Signed source commit
remains pending the existing 1Password signer; no unsigned commit was created.
See validation/startup-tabs-2026-11-03.json and validation/apk.json.

## Compact swipe program overlay — 2026-11-03

The centered swipe program badge now wraps its icon and label instead of
reserving 240 dp. Long labels retain the existing maximum width and ellipsis.
The single-icon stack no longer reserves space for additional stacked icons.
Its center and vertical anchor stay fixed as label widths change.

Nine focused PaneChrome Android checks passed, including centering, compact
short-label bounds, unchanged terminal dimensions, swipe handoff, wrapping,
cancellation and the return-to-latest button. Debug and optimized release builds
passed, as did static release APK checks. Installed release 15 on S22+
RFCT21390SD with app data preserved; installed APK checksum matches the
shareable artifact in validation/apk.json. Source remains based on 918f9265ae
with local changes; signed commit still awaits the existing 1Password signer.

## Initial tab visibility and quick theme switch — 2026-11-03

The initial tab list waits for a terminal screen and known focused program
identities. Its 49 dp dock space and action buttons remain in place during
loading. Once revealed, metadata refreshes within that session do not hide the
strip; changing sessions resets the reveal state. The overflow menu now has a
Dark theme switch reflecting the rendered appearance. It selects and persists
Light or Dark while preserving each selected theme preset; System remains
available under Preferences → Appearance.

Sixteen focused Android checks passed: startup reveal, refresh/session behavior,
unchanged action bounds, saved metadata, both quick-toggle directions, saved
appearance/preset restoration and adaptive tab sizing. Debug and optimized
release builds, release lint and static release APK checks passed. Release 16
is ready for sharing; S22+ was absent from ADB, so installation is pending.
Source is based on 918f9265ae with local changes; signed commit remains pending
the existing 1Password signer. See validation/loading-theme-toggle-2026-11-03.json
and validation/apk.json.

## Prompt-row tap opens keyboard — 2026-11-03

For mouse-reporting Codex and Claude panes, tapping the visible cursor row
forwards the normal mouse click and opens the keyboard through the existing
TerminalView.keyboard action. The bound connection checks live program metadata,
selected pane, ownership, foreground visibility and navigation readiness. It
does not infer behavior from cached program icons. Non-mouse terminal taps keep
the existing keyboard behavior. Other application mouse clicks are unchanged.
The terminal snapshot exposes a cursor, not input-field bounds, so this
recognizes the visible cursor row rather than arbitrary editor areas.

Nineteen focused Android interaction checks passed. The new check covers mouse
click ordering, cursor-row dispatch, hidden cursor and cancelled navigation;
existing mouse, selection, swipe and URL/OSC 8 link checks also pass. Debug and
optimized release builds, release lint and static APK checks passed. This
verifies dispatch in the emulator; a live Codex/Claude phone IME session was not
run. Release 17 is ready for sharing; S22+ was absent from ADB. Source remains
based on 918f9265ae with local changes and the existing signing blocker.
See validation/prompt-keyboard-2026-11-03.json and validation/apk.json.

Release 17 subsequently installed on S22+ RFCT21390SD after USB reconnection.
The installed APK checksum matches validation/apk.json. App data preserved; no
automatic app launch or terminal takeover was performed.

## Shortcut gesture preference and plain theme action — 2026-11-03

The theme overflow action is now a plain menu item alternating “Toggle Dark
theme” and “Toggle Light theme”, without a switch. Preferences → Keyboard →
Ctrl shortcuts offers Hold (default) and Double tap, saved in local preferences.
Single taps toggle Ctrl immediately in either mode; a second tap in Double tap
mode reverses the first toggle and leaves the popup open for a shortcut tap.
Hold/slide/release behavior remains unchanged in Hold mode. Mode changes or
disabling input dismiss the popup and cancel a pending hold.

Eighteen focused Android checks passed: existing hold gestures, cancellation,
readonly behavior, Ctrl+J and Ctrl+], double-tap popup selection, immediate single
tap, double-tap mode hold behavior, preservation of an initially active modifier,
preference controls and restoration, plain theme menu, and both-theme settings
previews. Debug and optimized release builds, release vital lint and static
release APK checks passed. Installed release 19 on S22+ RFCT21390SD with data
preserved; installed APK checksum matches validation/apk.json. Source remains
based on 918f9265ae with local changes and the existing 1Password signing blocker.
See validation/shortcut-gesture-2026-11-03.json.

## Single-tab title width — 2026-11-03

A single tab now fits its actual title width up to the available strip boundary
before the tab/pane menu and Activity buttons, rather than the multi-tab 160 dp
cap. Long titles retain ellipsis at that boundary, short titles remain compact,
and multi-tab accordion allocation is unchanged. Eleven existing sizing tests
and nine adaptive tab Android checks passed. Debug and optimized release builds
and static release APK checks passed. Installed release 20 on S22+ RFCT21390SD
with data preserved; installed APK checksum matches validation/apk.json. Source
remains based on 918f9265ae with local changes and the existing signing blocker.
See validation/single-tab-title-2026-11-03.json.

## Neovim floating key shortcuts — 2026-11-03

A floating colon button appears for the current live Neovim pane, independently
of terminal layout. It opens saved key shortcuts and a customization action.
Preferences → Keyboard → Neovim shortcuts accepts one shortcut per line, with
optional names such as LazyGit = <Space>lg. Supported notation includes Escape,
Enter, Space, Tab, control/alt keys, arrows and F1–F12. Use an actual leader key
rather than assuming Neovim's mapleader. An empty list hides the button.

Shortcuts first send Ctrl-Backslash, Ctrl-N to reach Normal mode. Default :w,
:q and related entries fill the command line and open the keyboard without
Enter; explicit <CR> is available in custom sequences. Modifier state is reset
before sending the complete sequence. Input selection checks the captured pane
target, current live program, foreground/connection/control state and pending
navigation. Menus close when their target or ownership changes. Cached chrome
metadata never authorizes a shortcut.

Validation: 140 JVM checks passed and one optional benchmark skipped; 19 focused
Android checks passed covering menu/editor, invalid notation, customization
persistence, pane transitions and both-theme settings. Release lint, debug and
optimized release builds, and static APK checks passed. An isolated clean
headless Neovim process verified the Normal-mode prefix from Insert, Visual and
Terminal modes, a Space-leader mapping, and no Ex command execution before
explicit Enter. No user Neovim instance/configuration was modified. Preferences
preview inspected. Release 21 is built from base 076b1bfeb3 with local changes.
Phone installation awaits USB reconnection or wireless ADB pairing.
See validation/neovim-shortcuts-2026-11-03.json and validation/apk.json.

Release 21 subsequently installed on S22+ RFCT21390SD through wireless ADB.
The supplied address was its connection service; it accepted the existing Mac
authorization without a new pairing step. Installed APK checksum matches the
verified artifact. App data preserved, with no automatic app launch or editor
interaction. The signed source commit attempt still failed in 1Password with
“failed to fill whole buffer”; no unsigned commit was created.

## Floating shortcut gestures and immediate commands — 2026-11-03

The Neovim shortcut button is now circular with a crisp existing vector Neovim
icon, 48 dp target, and drag placement within the terminal. Position saves as
normalized coordinates only at drag end, survives recreation and remains in
bounds when the keyboard or viewport size changes. Dragging closes the menu
and does not select a shortcut; outside touches continue to the terminal.
A preference toggle hides the feature without deleting customized shortcuts.

At the user's updated request, plain colon commands now append Enter and run
immediately; explicitly encoded Enter is not duplicated. Mapped sequences such
as <Space>lg retain their exact keys. The input ownership/program/navigation
guards remain. Top-bar program icon vertical swipes now select next/previous
sessions once per release, including wrap across connected servers. Existing
horizontal session strip gestures and fixed header line baselines remain.

Validation: 140 JVM checks passed, one optional benchmark skipped; 26 Android
checks passed covering drag/tap separation, outside touch forwarding, resize
bounds, settings/position persistence, menu targeting, vertical and horizontal
session loops, disabled navigation, header geometry and pane feedback. Debug
and optimized release builds, release lint and static APK checks passed.
Installed release 22 over wireless ADB on S22+ RFCT21390SD with app data
preserved. Installed APK checksum matches validation/apk.json. No automatic
app launch or live editor interaction was performed. Source is based on
076b1bfeb3 with local changes. See validation/floating-shortcut-gestures-2026-11-03.json.

## AssistiveTouch-style popup and header icon motion — 2026-11-03

The Neovim button now uses a translucent rounded AssistiveTouch-style enclosure
and concentric rings, retaining its 48 dp target and saved drag placement. Its
popup shares the Ctrl menu's compact card/grid treatment: two columns, three
rows, at most six shortcuts per page, vertical paging, a page counter and a
customization footer. Fixed page bounds prevent movement as the last page
contains fewer items. Dark/light colors are explicit; visual QA caught and
corrected inherited black text in the dark popup before phone installation.

Top-bar program icons follow vertical drags with bounded translation, slight
fade/scale and a spring return after release/cancellation. Motion is read in a
small graphics layer, with no idle animation or header/text layout changes.
Session selection still happens once on release with existing loop behavior.

Validation: 28 focused Android checks passed, including vertical page limits,
no selection during scrolling, stable page bounds, drag/outside touches, header
icon movement/cancel return, fixed title bounds, session loops and existing
pane feedback. The five popup checks passed again after the contrast fix.
Light/dark menu and button previews inspected. Debug/release builds, release
lint and static APK verification passed. Installed final release 23 wirelessly
on S22+ RFCT21390SD, preserving app data; installed APK checksum matches
validation/apk.json. Source is based on 076b1bfeb3 with local changes. Git signing
remains blocked by the prior 1Password error; no unsigned commit was created.
See validation/assistive-shortcuts-2026-11-03.json.

## Arrow popup and Enter key — 2026-11-03

The primary key row now has separate flexible modifier keys, one arrow-menu
button, Enter, a visible extra-key chevron beside the keyboard icon, and no
horizontal scrolling. The arrow button opens a compact Ctrl-style popup above
the bottom dock: Up above Left/Down/Right. The popup stays open for repeated
taps and stationary holds, using the existing repeat/modifier/input-target
guards. Target changes and ownership loss close it; moving off a held arrow
cancels input. Enter sends one carriage return. F1–F12 and other extra keys
remain available under the relocated chevron.

Validation: 25 focused Android checks passed for the keyboard row, arrow pad
repeat and Ctrl shortcut modes, including Shift combinations, cancelled input,
readonly contrast, narrow 320 dp layout, Enter, extra-key expansion, popup
placement above an unchanged dock, target changes, held repeat and cancellation.
Light/dark arrow popup and primary-row previews inspected. Debug/release builds,
release lint and static APK verification passed. Installed release 24 wirelessly
on S22+ RFCT21390SD with app data preserved; installed APK checksum matches
validation/apk.json. Source remains based on 076b1bfeb3 with local changes and
the existing 1Password signing blocker. See validation/arrow-popup-keyboard-2026-11-03.json.

## Persistent arrow popup toggle — 2026-11-03

Outside/back dismissal and the arrow popup BackHandler are removed. Other
keys or outside taps leave the non-focusable popup open; the main arrow button
toggles it closed/open. Input-target and ownership resets remain. This also
avoids outside dismissal resetting state before the trigger's own click.

Fifteen keyboard/repeat Android checks passed. A new regression dispatches
actual Android MotionEvents using accessibility-reported screen bounds: open
the popup, tap Escape/Enter outside it while verifying input and persistent
visibility, then close and reopen with the main arrow button. Existing repeat,
modifier, readonly, narrow-layout and target-change checks also pass. Debug
and optimized release builds and static APK verification passed. Installed
release 25 wirelessly on S22+ RFCT21390SD with data preserved; installed APK
checksum matches validation/apk.json. Source remains based on 076b1bfeb3 with
local changes and the existing 1Password signing blocker.
See validation/sticky-arrow-popup-2026-11-03.json.


## Optional Neovim shortcut groups — 2026-11-03

Optional [Group] section headers create subtly shaded group pills in the
floating shortcut menu. [] returns to ungrouped entries. Group clicks replace
the current grid without sending terminal input; Back restores the root page.
There is one group level, with six actions per vertically paged view and at
most 16 actions total. Menu bounds remain fixed across levels. Existing flat
saved lists and literal bracket key sequences remain compatible.

Validation: 143 JVM checks passed with one optional skip; 13 focused Android
checks passed, covering group navigation, paged children, root-page restoration,
no input on group clicks, legacy lists and grouped preference persistence.
Light/dark group previews inspected. Debug/release builds, release lint and
static APK verification passed. Installed release 26 wirelessly on S22+
RFCT21390SD, preserving app data, and verified installed APK SHA-256 matches
validation/apk.json. The phone app was not launched. Source remains based on
076b1bfeb3 with local changes and the existing 1Password signing blocker.
See validation/neovim-shortcut-groups-2026-11-03.json.


## Speech-bubble popups — 2026-11-03

Neovim shortcuts, Ctrl shortcuts and arrows share one rounded speech-bubble
surface with an integrated pointer, border and shadow. The pointer tracks its
trigger within screen-edge limits and flips to the top when opening below the
trigger. Ctrl slide-to-select bounds exclude the decorative pointer. Existing
menu grouping/paging, hold/double-tap input, arrow repeat and sticky arrow-button
toggle behavior are preserved.

Thirty distinct focused Android checks passed. The added below-trigger Ctrl
regression initially touched the system status bar; its fixture was moved into
the app's content area, then all 12 Ctrl checks passed again. Native Ctrl
slide/release and arrow outside-key/trigger interactions pass. Light/dark Vim
and arrow previews, plus above/below-edge Ctrl previews, were inspected.
Debug/release builds, release lint and static APK verification passed.
Installed release 27 wirelessly on S22+ RFCT21390SD, preserving app data,
without launching the app; installed APK SHA-256 matches validation/apk.json.
Source remains based on 076b1bfeb3 with local changes and the existing
1Password signing blocker. See validation/speech-bubble-popups-2026-11-03.json.


## Compact persistent Vim menu — 2026-11-03

Neovim shortcut actions no longer dismiss the popup. Its group/page stay open
for repeated commands; outside taps dismiss it and Back leaves it open.
Customize still opens settings, while pane changes and control loss retain
their existing safety resets. Maximum width is now 224 dp (from 244), key rows
44 dp (from 48), header/footer 32 dp (from 40), padding 6 dp and gaps 3 dp.
Font sizes are unchanged and the speech-bubble outline remains.

All eight focused Neovim Android checks passed, including repeated root/group
actions, native Back and outside-touch behavior, paging, group navigation,
customization, dragging and pane/control resets. Light/dark previews inspected.
Debug and optimized release builds, release lint, static APK verification and
diff whitespace checks passed. Installed release 28 wirelessly on S22+
RFCT21390SD without launching the app, preserving app data; installed APK
SHA-256 matches validation/apk.json. Source remains based on 076b1bfeb3 with
local changes and the existing 1Password signing blocker.
See validation/compact-persistent-vim-menu-2026-11-03.json.


## Circular Vim menu and unlimited shortcut count — 2026-11-03

Neovim shortcuts now offer a saved Grid/Circular layout choice in their
preferences, defaulting to Circular. The full circular wheel has up to six
outer shortcut targets around an Edit/Back center, vertical paging and optional
single-level groups. Actions leave the current menu/group/page open; outside
taps (including transparent wheel corners) dismiss it. Existing pane/control
safety resets remain. The grid is tighter again: 216 dp maximum width, 40 dp
rows, 28 dp header/footer, 5 dp padding and 2 dp gaps; text sizes are unchanged.
The 16-shortcut limit and old 12,000-character serialized-list limit are removed.
Individual label/key validation remains, and group validation uses set lookups.

Validation: 144 JVM checks passed, one optional skip, including 200-item flat
and grouped lists beyond the old serialized-size cap. Sixteen focused Android
checks passed, including 36-item grid paging, 19-item group paging, repeated
actions, native Back/outside dismissal in both layouts, non-overlapping circular
targets and center control, layout selection and preference restoration with
41 shortcuts. Light/dark circular and compact-grid previews inspected. Final
debug/release builds, release lint, static APK verification and diff whitespace
checks passed. Installed release 29 wirelessly on S22+ RFCT21390SD with app data
preserved, without launching it; installed APK SHA-256 matches validation/apk.json.
Source remains based on 076b1bfeb3 with local changes and the existing 1Password
signing blocker. See validation/circular-vim-menu-2026-11-03.json.


## Vim controls around the AssistiveTouch button — 2026-11-04

The circular layout now centers on the actual floating button, rather than
opening a separate disc above it. Individual shortcut pills orbit the center
without a filled menu background. Maximum footprint is 196 dp, targets 60x40 dp,
center 48 dp and labels 11 sp. At terminal edges the button temporarily moves
inward to fit the ring; outside dismissal restores its saved position without
writing another preference. The covered underlying button drawing is suppressed
while the popup draws the center. Empty spaces and outside taps dismiss; actions
retain their group/page. Grid and unlimited saved shortcut count are unchanged.

All 11 distinct focused Vim Android checks passed, including native screen
geometry at opposite edges, center alignment with the actual anchor, unchanged
saved position, paging, non-overlapping targets, repeated actions and outside
input. The geometry fixture uses Compose window coordinates for the covered
button because a focusable popup hides covered accessibility nodes. The editor
check passed again after scrolling its validation error into view when obscured
by the emulator keyboard. Final light/dark and opposite-edge previews inspected.
Debug/release builds, release lint, static APK and whitespace verification passed.
Installed release 30 wirelessly on S22+ RFCT21390SD without launching it, preserving
app data; installed APK checksum matches validation/apk.json. Source remains
based on 076b1bfeb3 with local changes and the existing 1Password signing blocker.
See validation/anchored-vim-wheel-2026-11-04.json.


## Radial Vim contrast and looping pages — 2026-11-04

Radial keys now use an opaque charcoal fill with white labels, a clearer border
and stronger shadow in both themes. Visible key backgrounds have tighter padding
and 8 dp corners; the center uses an opaque dark fill with 10 dp corners. Root
center taps consume the tap without opening preferences or dismissing the menu;
group center taps still return to root. Settings remain in preferences, and the
grid's explicit Customize footer remains. Both layouts repeat logical pages in
either direction, displaying logical page counts and restoring the root page
when leaving a group. Single-page lists do not scroll.

Eleven distinct focused Vim Android checks passed, including forward/backward
wrap in grid and circular groups, repeated center taps without preferences,
root-page restoration, anchored screen geometry, repeated commands and outside
dismissal. The existing editor check raced a validation update in the full run;
its draft and validation state are now synchronized explicitly, and its isolated
recheck passed. Light/dark radial previews inspected. Debug/release builds,
release lint, static APK and whitespace verification passed.

The phone's previous wireless endpoint had closed. After the user supplied its
updated connection address, existing Mac authorization reconnected successfully.
Installed release 31 wirelessly on S22+ RFCT21390SD with app data preserved and
without launching the app; installed APK SHA-256 matches validation/apk.json.
Source remains based on 076b1bfeb3 with local changes and the existing 1Password
signing blocker.
See validation/vim-wheel-polish-2026-11-04.json.


## Dragging the open radial Vim menu — 2026-11-04

The radial center accepts drags while its controls remain open. The actual
anchor and all controls move together, preserving the current group and logical
page. Pointer movement uses screen-space coordinates to compensate for the native
popup moving under the finger. Dragging starts from the displayed position,
including temporary edge insets. The full ring stays clamped to the terminal;
release saves its normalized position once, while cancellation restores the
previous saved position without typing or navigating Back. Closed-button drag,
looping pages and outside dismissal remain unchanged.

Five distinct focused Android checks passed, including real native MotionEvents
for open-group drag, release/cancel, clamping, anchor/menu alignment and position
persistence after closing. The native cancellation assertion now waits for the
asynchronous accessibility bounds update; its isolated recheck passed. Existing
closed drag, group/page looping, opposite-edge placement and outside dismissal
checks passed. Dragged-menu preview inspected. Debug/release builds, release lint,
static APK verification and whitespace checks passed.

Release 32 is ready but not installed yet: both recently supplied wireless ADB
ports (39537 and 44407) refuse connections. Awaiting a current main-screen address
or USB connection. Source remains based on 076b1bfeb3 with local changes and the
existing 1Password signing blocker.
See validation/open-vim-wheel-drag-2026-11-04.json.


## Direct arrow joystick — 2026-11-04

The persistent arrow popup is replaced with a hold-and-slide joystick near the
keyboard controls. Press shows an 88 dp direction indicator; passing the neutral
zone (10 dp or touch slop) sends an arrow immediately. Holding repeats after
350 ms at 65 ms intervals. Centering pauses repeat; changing direction cancels
the old repeat. Release, cancellation, pane changes and ownership loss stop it.
The gesture captures down and movement in the Initial pass, with local Android
system-gesture exclusion, so rightward movement cannot start an ancestor swipe.
Callbacks retain their original guarded pane target. Modifier input remains in
the existing pipeline, with four discrete accessibility direction actions.
Enter now precedes the joystick; the extra-key chevron remains beside keyboard.

Twenty-three distinct focused Android checks passed: immediate direction input,
hold/repeat/release, neutral pause, direction changes, cancel, target/control
loss, ancestor swipe blocking, simultaneous two-finger Shift repeat, one-shot
Shift, native Android held input, accessibility actions, narrow row geometry,
readonly contrast, extra keys and existing Backspace repeat. The preview fixture
initially expected encoded bytes from a callback that records raw key names;
its assertion was corrected and the isolated preview recheck passed. Light/dark
joystick and dock previews inspected. Debug/release builds, release lint,
static APK verification and whitespace checks passed.

Installed release 33 on S22+ RFCT21390SD via USB with app data preserved, without
launching the app; installed APK SHA-256 matches validation/apk.json. Source is
based on signed commit c12d9913b4 with local changes.
See validation/arrow-joystick-2026-11-04.json.


## Precision joystick and centered overlays — 2026-11-04

Arrow steering still sends one immediate step, but repeat waits 500 ms and then
sends every 160 ms. The overlay stays exactly centered on the actual drawn key,
without clamping/platform repositioning. It uses an 88 dp face with the keyboard
visible and a proportionate 52 dp face when hidden. Steering uses this same
button center. An off-center press stays neutral until deliberate movement
crosses touch slop; tiny press movement sends nothing. Neutral pause, direction
hysteresis, release/cancel guards, pane targeting, modifiers, gesture capture and
accessibility actions remain.

Twenty distinct focused Android checks passed. Timing checks confirm no repeat
before the delay and the slower interval afterward. Native screen checks cover
compact/full sizes and center alignment near the bottom, off-center presses,
steering relative to the visible center and unchanged dock layout. The fixture
compares the actual drawn key coordinates: accessibility bounds are clipped by
the adjacent chevron's expanded touch target. The full-size preview reserves
keyboard space, and native captures wait for a rendered window frame as well as
layout. Rendered compact/full previews inspected. Modifier, simultaneous Shift,
neutral pause, target/control cancellation, native release and swipe prevention
checks passed. Debug/release builds, release lint, static APK verification and
whitespace checks passed.

Installed release 34 on S22+ RFCT21390SD via USB with app data preserved, without
launching the app; installed APK SHA-256 matches validation/apk.json. Source is
based on signed commit c12d9913b4 with local changes.
See validation/precision-arrow-joystick-2026-11-04.json.


## Arrow mode preference and double-tap popup — 2026-11-04

Preferences > Keyboard now has a saved Use joystick for arrows switch, enabled
by default. When enabled, hold/slide retains the precision joystick while a
double-tap toggles the original four-arrow speech-bubble popup. When disabled,
a single tap toggles that popup and dragging does not steer or show a joystick.
Popup arrows support precise taps and the existing hold-repeat/modifier/target
pipeline. The popup stays open across arrow and outside-key taps, closes via its
trigger, and resets on pane, control or mode changes. Starting a joystick drag
closes the popup and resumes steering. Accessibility click toggles the popup.

All 32 focused Android checks passed in one run, including native double-tap
open/close without input, sticky outside-key behavior, precise Shift arrow taps,
classic single-tap mode, popup-to-joystick switching, held repeat cancellation,
preference UI switching and model recreation. Existing slow repeat, neutral pause,
multitouch Shift, native held input, anchor alignment, compact/full overlays,
readonly layout and swipe-capture checks also passed. Debug/release builds,
release lint, static APK verification and whitespace checks passed.

Installed release 35 on S22+ RFCT21390SD via USB with app data preserved, without
launching the app; installed APK SHA-256 matches validation/apk.json. Source is
based on signed commit c12d9913b4 with local changes.
See validation/arrow-mode-preference-2026-11-04.json.


## Page keys and Vim center toggle — 2026-11-04

PgUp/PgDn occupy the two empty upper corners of the arrow popup, preserving its
200 dp width and 100 dp key area. Taps send standard Page Up/Down sequences;
Shift modifiers use the existing guarded input pipeline. Page-key holds use
Android's configured long-press activation and a slower 160 ms repeat interval.
The shared repeat helper retains its default interval for arrows and Backspace.
The Vim radial center now returns Back inside a group and closes the menu at
root. Its AssistiveTouch button reopens it; wrapping and open dragging remain.

Twenty distinct focused Android checks passed: page-key sequences and Shift,
held-page repeat and pane cancellation, both popup modes, native double-tap,
legacy Backspace repeat, Vim group Back/root close/reopen, anchor geometry and
open-menu dragging. The held-page fixture waits for the popup before freezing
the test clock and reads Android's configured long-press delay rather than
assuming 500 ms. Popup preview inspected. Debug/release builds, release lint,
static APK verification and whitespace checks passed.

Installed release 36 on S22+ RFCT21390SD via USB with app data preserved, without
launching it. The installed APK checksum matches validation/apk.json and was
reverified after the follow-up installation request. Source is based on signed
commit c12d9913b4 with local changes. The existing date/versionCode presentation
was left unchanged after the user's version-number question.
See validation/page-keys-vim-toggle-2026-11-04.json.


## Popup trigger dismissal — 2026-11-04

A single tap closes the open arrow popup in either keyboard mode. Joystick
mode retains double-tap opening and hold/slide steering. Tapping Ctrl while its
shortcut popup is open closes it and clears the modifier. Android sends
ACTION_OUTSIDE before DOWN to the underlying trigger, so the native popup root
intercepts outside events over its own trigger and lets that trigger close it.
Other outside taps dismiss normally. Tab remains on the keyboard row.

Twenty-seven distinct focused Android checks passed across the final runs:
12 arrow/native keyboard checks and all 15 Ctrl checks. Native tests cover
closing from selected and unselected Ctrl, ordinary taps afterward, and outside
dismissal. Existing hold/slide, neutral pause, repetition, cancellation and
classic popup behavior remain covered. One existing hold fixture initially
missed the popup accessibility tree while the test clock was frozen; allowing
a frame while waiting resolved it, and all 15 Ctrl tests passed together.
Release build, release lint, static APK verification and whitespace checks pass.

Installed release 37 on S22+ RFCT21390SD via USB, preserving data without
launching the app. Installed APK SHA-256 matches validation/apk.json. Source
changes remain uncommitted based on signed commit c12d9913b4.
See validation/popup-dismiss-taps-2026-11-04.json.


## Hidden keyboard chevron — 2026-11-04

The extra-key chevron is before Esc, hidden until a rightward swipe on the key
row. The keyboard button remains fixed. Closing extras or hiding the keyboard
resets the scroll to Esc first. Seven visible keys reclaim the old chevron slot
(about 4 dp each). An exact-width inner row distributes fractional pixels so Esc
is not clipped at the default scroll position.

Seventeen distinct keyboard checks passed across runs: physical swipe/reveal,
chevron placement and close/reset, keyboard hiding, narrow-phone unclipped key
widths, native joystick steering/repeat, Shift modifiers, popup taps, cancellation
and readable disabled controls. Fifteen passed in the final full-suite run; the
two remaining fixtures then passed together after allowing native popup frames
and isolating keyboard reset from a synthetic swipe still settling. The separate
physical swipe/tap case remains covered. Default and revealed captures inspected.
Release build/lint, static APK verification and whitespace checks passed.

Installed release 38 on S22+ RFCT21390SD via USB, preserving data without launching
it. Installed APK SHA-256 matches validation/apk.json. Changes remain uncommitted.
See validation/hidden-keyboard-chevron-2026-11-04.json.


## Segmented shortcut wheel — 2026-11-04

Circular mode is now a continuous wheel with up to eight positions per page,
rotated labels and a highlighted-command preview in the hub. Hold the closed
button, steer and release to execute exactly once. Returning to the hub or
cancelling the gesture sends nothing. A tap opens the persistent wheel; center
presses steer, outer-ring vertical swipes page and wrap. Groups return to the
remembered root page; center closes at root. The open wheel stays fixed, while
quick closed-button drags still save its position. Edge positioning fits the
wheel to the terminal and restores the resting position when closed. Grid mode
retains six items per page. Arrow/Ctrl shadows and idle-button opacity from the
preceding style update are included.

Three JVM geometry/state tests and the full 15-case Vim UI suite passed.
Native checks cover hold-opening, one command on release after a long hover,
neutral return, touch cancellation, ownership loss, no position saves during
steering, and edge bounds. Paging/group/tap, closed-drag and Grid regressions
passed. Light/dark, eight-item and selected-segment captures inspected. The
first UI run exposed Back falling through the non-focusable popup and a native
accessibility timing issue; both were corrected before the final 15-case pass.
Release build/lint, static APK verification and whitespace checks passed.

Installed release 40 on S22+ RFCT21390SD via USB before finishing remaining
emulator checks, as requested. Installed SHA-256 matches validation/apk.json;
data preserved, app not launched. Source remains uncommitted based on 19f60a163e.
See validation/radial-shortcut-wheel-2026-11-04.json.


## Stationary wheel anchor — 2026-11-04

The circular button's resting position now reserves wheel space at physical
window edges. Opening no longer changes its layout offset. The wheel uses
Android's visible window area and can overlap the header/footer instead of
being restricted to the terminal. Its diameter uses available app space. Native
popup clamping is disabled so it cannot shift the hub away from the fixed
anchor. Saved normalized positions map into the resting range; opening/closing
never writes a new preference position.

Four JVM checks and fifteen distinct Vim UI checks passed across runs. Geometry
checks include app/pane intersections and a simulated reduced area above the
keyboard. Native corner checks verify identical button bounds on open/close,
wheel/hub centering, app containment and allowed pane overlap. The full UI run
passed fourteen checks; one fixture read cached native bounds immediately after
explicitly changing its saved corner. Waiting for the native update fixed the
fixture and the corner test passed. No production changes followed that run.
Release build/lint, static APK verification and whitespace checks passed.

Installed release 41 on S22+ RFCT21390SD via USB after reconnection. The installed
APK checksum matches the verified artifact; app data preserved, app not launched.
Source remains uncommitted based on 19f60a163e.
See validation/stationary-wheel-anchor-2026-11-04.json.


## Circular command button and center gap — 2026-11-04

The shared floating shortcut face is a muted 40 dp circle with an 18 dp Canvas
command glyph and subtle border. The enclosing 48 dp touch/drag target and
stationary anchor remain. Idle opacity stays at 50%. Grid's active button and
the idle root wheel show Close; group Back and command previews remain. The
transparent wheel hub/ring gap is approximately 4 dp at the normal diameter.
The gap stays neutral for steering and does nothing on tap.

Four wheel JVM checks and six focused UI checks passed: native hold/release and
neutral return; paging, groups and root Close; closed dragging in both modes;
Grid actions/customization; and stationary corner/overlap geometry. Closed-button
and wheel previews inspected. Release build/lint, static APK verification and
whitespace checks passed.

Installed release 43 on S22+ RFCT21390SD via USB. The installed APK checksum
matches validation/apk.json. App data preserved; app not launched. Source remains
uncommitted based on 19f60a163e.
See validation/command-button-gap-2026-11-04.json.


## Round AssistiveTouch face and key order — 2026-11-04

Restored the three AssistiveTouch rings inside the circular 40 dp button while
retaining its 48 dp touch/drag target and low idle opacity. Active Close/Back,
the stationary wheel anchor and 4 dp center gap remain. The visible key row now
ends Tab → joystick → Enter → keyboard, with the keyboard button pinned.

Five focused UI checks passed: narrow-phone order/equal unclipped widths,
native joystick hold/drag across the neighboring Enter without sending Enter,
Shift modifiers, native popup close/outside keys, and closed wheel dragging.
The round face capture was inspected. Release build/lint, static APK verification
and whitespace checks passed. This cosmetic face restoration reused the
existing gesture regression fixtures.

Installed release 44 on S22+ RFCT21390SD via USB. Installed checksum matches
validation/apk.json; app data preserved and app not launched. Source remains
uncommitted based on 19f60a163e.
See validation/round-assistive-key-order-2026-11-04.json.


## Circular joystick icon — 2026-11-04

Replaced the crossed-arrow dock glyph with a circular control-pad outline,
neutral center ring and four directional markers. It uses the existing 20 dp
size, shared 1.65-unit stroke and colors. Accessibility and gestures are unchanged.
The existing light/dark joystick overlay fixture passed; the actual-size keyboard
row capture was inspected. Release build/lint, static APK verification and
whitespace checks passed. No new tests were added for this cosmetic change.

Installed release 45 on S22+ RFCT21390SD via USB. Installed checksum matches
validation/apk.json; app data preserved and app not launched. Source remains
uncommitted based on 19f60a163e. Radial open/close animation is a design discussion
at this point and is not part of this build.
See validation/joystick-control-icon-2026-11-04.json.


## Quick radial fan-out and joystick feedback — 2026-11-04

Circular segments fan outward with a small stagger over 120 ms and retract over
80 ms. Drawing/layer transforms read transition state; layout and command hit
geometry remain fixed. Entry is immediately interactive. Exit retains the popup
with commands disabled, supports a quick center tap to reverse into opening, and
drops immediately on pane/ownership changes. The center remains anchored. Compose
supplies the platform MotionDurationScale; installed framework source confirms
integration with Android animator-duration-scale settings.

The dock icon now has a filled center and stronger directional markers. The live
joystick face uses only its moving thumb dot and directional highlight; its
arrow glyphs are removed. Discrete arrow buttons and accessibility actions remain.

Four JVM checks and nineteen distinct Android UI checks passed across runs.
The full UI run passed eighteen cases; one new fixture froze the clock before
initial popup rendering. Waiting for that render fixed the fixture and its
pane/ownership case passed separately. New checks cover input during entry,
blocked stale callbacks during exit, reversal, fixed bounds and cleanup after
closing. Native hold/release, neutral cancellation, groups, paging, Grid and
joystick neighbor-input checks passed. Opening/closing, icon and overlay captures
inspected. Release build/lint, static APK verification and whitespace checks pass.
No new overall-frame performance trace was recorded.

Installed release 46 on S22+ RFCT21390SD via USB. Installed checksum matches
validation/apk.json; app data preserved, app not launched. Source is uncommitted
based on 19f60a163e. See validation/quick-radial-fanout-2026-11-04.json.


## Radial animation options — 2026-11-04

Circular mode now offers saved Fan out and Shutter effects under Preferences →
Keyboard → Neovim shortcuts → Opening effect. Fan out reveals segments clockwise
in an overlapping wave (420 ms open / 260 ms close). Shutter grows and untwists
all blades together (360 ms open / 240 ms close). Drawing and label layers animate
while layout and input geometry remain fixed. Entry remains interactive; exit
blocks stale commands and supports reversing into opening. Effect, pane and
ownership changes discard the old wheel. Grid mode is unchanged.

Six JVM tests and nineteen Android UI checks passed, including settings
persistence, both effects' entry/exit/reversal, native hold/release, cancellation,
paging, groups and Grid behavior. Both mid-opening captures were inspected.
Release build/lint, static signature/release/alignment checks passed. No new
whole-frame performance trace was recorded. Source remains uncommitted based
on 19f60a163e. See validation/radial-animation-options-2026-11-04.json.

Installed release 47 on S22+ RFCT21390SD via USB. Installed checksum matches
validation/apk.json; app data preserved and app not launched.


## Faster radial effects and instant mode — 2026-11-04

Fan out now opens and closes in 260 ms. Shutter opens in 240 ms and closes in
200 ms; its curved blades overlap during motion and settle into the original
sector geometry. Blade fills become opaque early to expose the layered seams.
The saved Opening effect setting adds None: the entire wheel is immediately
visible on entry, and closing removes the popup without an exit transition.

Six JVM tests and twenty distinct Android UI checks passed, including instant
open/close, stale-action guards, saved None selection and existing animated
entry/exit/reversal, paging, groups, Grid and native input/cancellation cases.
Repeated the shutter and instant cases to regenerate previews after model
recreation cleared the cache; both passed. Inspected the shutter mid-opening
capture. Release build/lint, static APK signature/release/alignment verification
and whitespace checks passed. No new whole-frame performance trace was recorded.
Source remains uncommitted based on 19f60a163e.
See validation/radial-effect-refinements-2026-11-04.json.

Installed release 48 on S22+ RFCT21390SD via USB. Installed checksum matches
validation/apk.json; app data preserved and app not launched.


## Coordinated wheel motion, haptics and explicit dismissal — 2026-11-04

Fan out now swings whole segments and their labels together, with overlapping
staggered starts. Shutter pivots overlapping blades around their outer tips
inside a fixed annular housing; the center and neutral gap stay fixed. Timings
remain 260/260 ms for Fan out and 240/200 ms for Shutter. None stays instant.
Circular mode has a saved Selection haptics preference, enabled by default.
Platform SegmentFrequentTick is emitted only on entering a different valid
command. Staying on a segment, invalid slots and the neutral hub do not tick;
disabling the preference suppresses feedback. No vibration permission is added.

Grid and Circular menus ignore outside taps and Back. Grid adds a footer Close
button and keeps its existing floating Close toggle. The wheel center closes
at root or returns from a group; taps in the wheel's empty corners do nothing.
Pane/ownership changes still discard the captured menu.

Six JVM and twenty-one distinct Android UI checks passed, including native
haptic deduplication and preference changes, saved settings, native outside
taps and explicit closing in both layouts, fixed anchors, gesture cancellation,
groups/paging, Grid, None and animation entry/exit/reversal. Inspected both
mid-opening captures. After adding the drawing-only fixed shutter housing,
both lifecycle checks passed again and the final shutter capture was inspected.
Release build/lint, static APK signature/release/alignment checks and whitespace
checks passed. No new whole-frame performance trace or physical haptic-strength
measurement was made. Source remains uncommitted based on 19f60a163e.
See validation/radial-motion-haptics-2026-11-04.json.

Installed release 49 on S22+ RFCT21390SD via USB. Installed checksum matches
validation/apk.json; app data preserved and app not launched.


## Shutter without backing ring — 2026-11-04

Removed only the annular backing fill. Moving blades, clipping, center, timings,
gestures and haptics are unchanged. Content is visible between the moving blades.
Reused the inspected pre-housing capture matching this geometry. No new tests
were added or UI suite rerun for this cosmetic fill removal. Release build/lint,
static APK verification and installed checksum checks passed. Installed release
50 on S22+ RFCT21390SD, preserving app data without launching the app.
See validation/shutter-no-backing-2026-11-04.json.


## Standard selection tick for Vim and joystick — 2026-11-04

Vim selection feedback now requests SegmentTick instead of SegmentFrequentTick.
The joystick adds SegmentTick on entering each new non-neutral direction; key
repeats and movement within the same direction do not buzz. Neutral pauses emit
no feedback. Existing Vim and Android touch-haptic settings are respected,
without changing phone settings or adding vibrator permissions. The shutter
backing remains removed.

Five focused Android checks passed: Vim tick deduplication/toggle, joystick
direction changes without repeat vibrations, native hold/repeat/release without
neighbor activation, target changes and gesture cancellation. Release build/lint,
static APK signature/release/alignment checks and whitespace checks passed.
No physical haptic-strength measurement was made. Source remains uncommitted
based on 19f60a163e. See validation/firmer-selection-haptics-2026-11-04.json.

Installed release 51 on S22+ RFCT21390SD via USB. Installed checksum matches
validation/apk.json; app data preserved and app not launched.


## Reference-based camera iris — 2026-11-04

Replaced the shutter's individually pivoting sectors with a synchronized
six-blade iris reveal. Fixed outer anchors connect to a rotating hexagonal
aperture. As the aperture expands it reveals the final menu and stationary
labels. It circumscribes the circle at full opening, leaving no residual mask;
closing reverses the motion. Center/input geometry remain fixed, with no
separate backing-ring fill. Timing remains 240/200 ms. Fan out, None and
haptics retain their behavior.

Inspected the user's public Shutterstock preview via a temporary contact sheet
and the native mid-opening capture. No stock media is bundled in the app. Seven
focused Android checks passed: entry input, exit/reversal, instant mode, both
layouts' outside-tap/explicit-close behavior, pane/ownership cancellation and
selection haptics. Release build/lint, static signature/release/alignment and
whitespace checks passed. No new whole-frame performance trace was recorded.
Source remains uncommitted based on 19f60a163e.
See validation/reference-camera-iris-2026-11-04.json.

Installed release 52 on S22+ RFCT21390SD via USB. Installed checksum matches
validation/apk.json; app data preserved and app not launched.


## Spin and Expand replace Shutter — 2026-11-04

Removed Shutter rendering and its settings choice. Spin rotates/scales the wheel
into place (280/180 ms). Expand grows it outward from the anchored center with
a soft deceleration (240/160 ms). Fan out and None remain. Segments/labels share
their transforms, with fixed layout/input geometry and the center painted above
moving labels. Effect chips wrap on narrow screens. Legacy SHUTTER preferences
load as EXPAND; existing valid choices remain unchanged.

Inspected both user-supplied public clips as temporary contact sheets, plus
native early/mid frames for Spin and Expand. No downloaded media is bundled.
Seven JVM and twenty-two distinct Android UI checks passed, covering animation
entry/exit/reversal, native steering, groups/paging, Grid, outside-tap/explicit
close behavior, input cancellation, haptics and saved-settings migration. The
two new lifecycle cases passed again with test-only early-frame capture changes.
Release build/lint, static signature/release/alignment and whitespace checks
passed. No new whole-frame performance trace was recorded. Source remains
uncommitted based on 19f60a163e.
See validation/spin-expand-effects-2026-11-04.json.

Installed release 53 on S22+ RFCT21390SD via USB. Installed checksum matches
validation/apk.json; app data preserved and app not launched.


## Rename to Rexling — 2026-11-05

Renamed source projects to rexling-android/rexling-core and Rust packages to
rexling-core/rexling-jni. Updated build bridges, Cargo lock metadata, maintained
build instructions, app/UI/notification/default-theme display names, license
notices, APK naming and client hello. GitHub is now shulhi/rexling and local
origin points to it; main remains the default branch. In-app repository/license
links target the new URL. Existing history is preserved as ordinary file moves.
Historical evidence and artwork provenance keep their recorded names.

Application ID dev.shulhi.rex, JNI ABI, preference store, default-theme IDs and
notification token scheme remain compatible. The release certificate matches
Rexlink release 53. Saved settings/theme checks passed.

48 Rust tests passed (15 live opt-in cases ignored), and the JVM suite passed
151 tests (one optional benchmark skipped). Android About, Appearance and alert
routing checks passed nine cases; one live notification test was skipped because
it requires an explicitly owned disposable fixture. Build/lint, static APK label,
package, signature, profiles, native symbols/alignment and checksum checks passed.
No phone installation: the user confirmed the phone is disconnected. Prepared
Rexling-2026.11.05-build54-f9ec92ddee-modified-arm64-release.apk for sharing.
Source rename remains uncommitted based on f9ec92ddee.
See validation/rexling-rename-2026-11-05.json.


## Overflow menu icons — 2026-11-05

Added shared 19 dp outline icons to terminal, tab, session and server overflow
actions. Theme actions show the target sun/moon glyph. Icons inherit Material
menu colors, including disabled dimming; session closing retains its error
color. Glyphs are decorative and do not duplicate TalkBack labels. Existing
actions, callbacks and pane-creation behavior are unchanged.

Three existing Android checks passed: quick-theme switching, the rename-session
menu and holding/releasing a tab's move/close menu. No new tests were added for
this cosmetic change. Inspected the actual MainActivity menu in light/dark
modes with disabled actions on the reset disposable emulator. Release build,
lint, static APK signature/release/profile/alignment checks and whitespace
checks passed. No live user terminal was changed. Split-direction selection
remains a UI proposal. Source is uncommitted based on 4d93bcda9f.
See validation/overflow-menu-icons-2026-11-05.json.

Installed release 55 on S22+ RFCT21390SD via USB. Installed checksum matches
validation/apk.json; app data preserved and app not launched.


## Pane split direction and compact menus — 2026-11-05

The Panes panel now offers Split right and Split below, with diagrams highlighting
the new half. Right maps to horizontal and Below to vertical, relative to the
selected pane. Both use side:after, the default shell and focus:false. Existing
phone presentation stays one pane at a time. Empty, busy and missing-anchor states
disable both actions. Native code validates the direction before creation starts
and retains horizontal behavior for legacy requests that omit it. Stale session
and anchor guards remain in place; no lost-response creation retry is added.

Overflow icons and labels now share an 8 dp gap, retaining touch targets,
disabled states, destructive colors and single accessibility labels.

151 JVM tests passed (one optional benchmark skipped), 50 Rust checks passed
(15 live opt-in cases ignored), and ten Android checks passed. Coverage includes
both split callbacks, exact native wire arguments, legacy default and invalid
directions, stale-target rejection before creation, empty/busy/missing-anchor
states, pane navigation and existing menu callbacks. Inspected split panels and
actual MainActivity menus in light/dark modes on the disposable emulator. Build,
lint, static APK signature/release/ABI/profile/alignment and whitespace checks
passed. No live server session or pane was created during verification.
Source is uncommitted based on 4d93bcda9f.
See validation/pane-split-menu-spacing-2026-11-05.json.

Installed release 56 on S22+ RFCT21390SD via USB. Installed checksum matches
validation/apk.json; app settings preserved and app not launched.


## Pane layout map — 2026-11-05

Panes now shows the server window's current geometry above its pane list. JNI
retains terminal leaf rectangles from session.view; coordinates are fractions
of the whole window, including nested splits and floating layers. Android clips
to the window, preserves layer order and highlights the active pane. Tile icons
and titles identify programs; tapping a usable tile uses existing pane selection.
The full list remains available for tiny tiles. Missing or incomplete geometry
shows Layout unavailable, rather than a guessed arrangement. Layout-only updates
preserve selection and program metadata. No polling or server mutation is added.

155 JVM tests passed (one optional benchmark skipped), 53 Rust checks passed
(15 live opt-in cases ignored), and 14 Android checks passed. New coverage checks
nested split proportions, floating window coordinates, malformed and offscreen
rectangles, unknown IDs, layer ordering, geometry refresh without selection,
exact tile selection, busy-state disabling and legacy metadata fallback.
Inspected light/dark full panels and nested/updated maps on the isolated API 37
emulator. Build, lint, static APK signature/release/ABI/profile/alignment and
whitespace checks passed. Geometry was verified against the cached API revision
8 schema and synthetic fixtures; no live user session or pane was changed.
Source remains uncommitted based on 4d93bcda9f.
See validation/pane-layout-map-2026-11-05.json.

Installed release 57 on S22+ RFCT21390SD via USB. Installed checksum matches
validation/apk.json; app settings preserved and app not launched.


## Panel selection and explicit navigation — 2026-11-05

Rows and layout tiles now select a local target inside the panel. Open tab and
Open pane perform navigation, consistently across both sections. Selecting a tab
also lets its panes be managed without opening the tab. Viewing captions identify
the actual open tab/pane independently of selection. The layout map is reduced
from 132 to 88 dp and uses numbers matching the full pane list.

Split actions send the selected pane's explicit anchor, even when another pane
is open. Kotlin and native code validate that it belongs to the current session's
known panes. Session mismatch, absent/removed anchors, invalid directions and busy
creation are rejected. Selection itself sends no commands; splitting remains a
single mutation with no automatic retry. Successful creation opens the new pane,
as before. Removed targets disable Open/split instead of targeting another pane.

155 JVM tests passed (one optional benchmark skipped), 54 Rust checks passed
(15 live opt-in cases ignored), and 14 Android checks passed. The updated UI
checks cover viewing pane 1 while selecting/splitting pane 3, explicit Open in
both sections, managing panes in another tab, map/list selection synchronization,
target removal, busy/empty states and missing layout. Native tests verify inactive
anchor arguments and stale/invalid rejection. Inspected numbered map and selected
target panels in light/dark themes on the isolated emulator. Release build, lint,
static signature/release/profile/alignment and whitespace checks passed. No live
user pane or session was changed during verification.
Source remains uncommitted based on 4d93bcda9f.
See validation/pane-selection-navigation-2026-11-05.json.

Installed release 58 on S22+ RFCT21390SD via USB. Installed checksum matches
validation/apk.json; app settings preserved and app not launched.


## Circular pane numbers — 2026-11-05

The map and list share circular pane-number badges, replacing bare map numbers
and the list's number prefix. Selected badges use a primary fill; other badges
have a subtle outline. Map badges shrink to fit small tiles. List state
descriptions include the pane number, while decorative badge text stays silent.
Selection, explicit navigation and split targeting are unchanged.

The existing 14 map/switcher Android checks passed on the isolated emulator.
No new tests were added for this cosmetic change. Inspected light/dark panels
with the map and list selecting the same pane. Release build, lint, static APK
signature/release/profile/alignment and whitespace checks passed.
See validation/pane-number-circles-2026-11-05.json.

Installed release 59 on S22+ RFCT21390SD via USB. Installed checksum matches
validation/apk.json; app settings preserved and app not launched.


## Smaller pane badges and Cloudflare identification — 2026-11-05

Pane-list circles are reduced from 24 to 18 dp to match title-text scale; map
badges stay at their existing size. Added explicit cloudflared executable/path
and cloudflare app-identifier recognition. It uses the user-supplied Superlogical
Cloudflare tile in header/tab stacks and its retained glyph for inline icons.
The existing vector compiler preserves the catalog artwork and native drawing
cache. Asset sources and checksums are retained. Titles do not select icons;
unrelated helper names retain generic fallback. Native process collection is
unchanged; the missing Android mapping caused the reported fallback.

156 JVM tests passed (one optional benchmark skipped), including executable/path,
app identifier, metadata parsing and false-positive cases. Seventeen Android
checks passed on the isolated emulator, covering all eleven catalog vectors,
program stack sizing, map selection and pane management. Cloudflare's mean
premultiplied pixel-channel difference from the supplied reference is 0.299/255.
Inspected smaller list badges in light/dark panels. Release build, lint, static
signature/release/profile/alignment and whitespace checks passed. No live user
terminal or pane was changed during verification.
See validation/cloudflare-small-pane-badges-2026-11-05.json.

Installed release 60 on S22+ RFCT21390SD via USB. Installed checksum matches
validation/apk.json; app settings preserved and app not launched.


## Icon-only pane map, new launcher and flush underline — 2026-11-05

Removed pane numbers from the map, list and split caption. Map tiles now show
small single program icons using the same cached native vector commands as
header/tab stacks, at up to 26 dp wide with aspect ratio preserved. Small tiles
scale the icon to fit. Program-title accessibility labels replace numeric labels.
Panel selection, explicit Open and inactive-pane splitting stay unchanged.

The latest user-supplied rounded paper dinosaur replaces launcher/README artwork.
Built-in image_gen removed the baked-in checkerboard and cleaned the alpha mask;
source, foreground hashes and preparation instructions are retained. Android
keeps a white adaptive background. Left/right insets are 18%, top 26%, bottom
10%, preserving square artwork while shifting it down to fill the mask bottom.
The monochrome notification/themed mark is unchanged. The focused launcher
test's stale pre-rename label expectation was corrected to Rexling.

The 49 dp dock previously centered 48 dp tab items, leaving a half-dp gap below
their underlines. Items now use the dock's full 49 dp height, placing the active
underline directly against the keyboard divider without moving the divider.

Twenty focused Android checks passed in two batches: fourteen map/panel checks,
then six launcher, adaptive-tabs, startup and tab-menu checks. The bottom-mask
foreground coverage check passes with no gaps. Inspected launcher output,
icon-only panels in light/dark themes, and the tab underline at the dock edge.
Release build, lint, static signature/release/profile/alignment and whitespace
checks passed. No live user terminal or pane was changed during verification.
See validation/icon-layout-launcher-2026-11-05.json.

Installed release 61 on S22+ RFCT21390SD via USB. Installed checksum matches
validation/apk.json; app settings preserved and app not launched.


## Compact tab/pane panel and floating overlays — 2026-11-05

Combined section switching, counts and Close in a single header row, with a
16 dp drag handle area. Removed duplicate panel/context headings; the selected
tab's title now labels its map. Rows are at least 56 dp, with smaller vector
stacks, tighter padding and 4 dp separation. Pane Open and split actions share
one bottom row with 48 dp targets. The panel accommodates more rows before
scrolling while retaining section swipes and explicit navigation.

Floating rectangles retain actual positions and layer order, with translucent
fill and dashed outlines. Hide floating removes only the map overlays so touch
can select tiled panes underneath; Show floating restores them. These controls
send no server commands and do not change selection. Floating panes remain in
the list with a Floating caption. Hidden state survives geometry refreshes in
the same window, and visibility can be inspected while terminal actions are busy.

Seventeen Android checks passed: twelve panel cases and five map cases. New
coverage checks all five pane rows with map/actions visible, touch selection of
a covered tiled pane after hiding two overlapping floating layers, topmost
floating selection after restoration, unchanged selection on visibility toggles,
and read-only inspection during geometry updates. Inspected light/dark compact
panels and floating layers shown/hidden on the isolated emulator. Release build,
lint, static signature/release/profile/alignment and whitespace checks passed.
No live user session or pane was changed during verification.
See validation/compact-floating-panel-2026-11-05.json.

Installed release 62 on the S22+ through the user-provided wireless ADB endpoint.
Installed checksum matches validation/apk.json; settings preserved and app not
launched.


## New pane direction menu and no layout heading — 2026-11-05

Replaced the two split buttons with a single New pane button alongside Open
pane, matching the Tabs footer. New pane opens a compact, bordered direction
menu with Split right and Split below previews. The menu has no drop shadow.
Opening/dismissing sends no command; choosing a direction still binds the
panel-selected anchor. It resets when target, tab, section or action availability
changes. Removed the tab title above the layout and its reserved space. Floating
visibility controls appear alone only when floating layers exist.

Nineteen Android checks passed: fourteen panel and five map cases. Updated
coverage exercises both direction choices, splitting an inactive pane, menu
cancellation without navigation/creation, anchor removal while the menu is open,
busy/empty/stale targets and five-pane visibility. Inspected menu/footer light
and dark captures. Release build, lint, static signature/release/profile/alignment
and whitespace checks passed. No live user pane was changed during verification.
See validation/new-pane-direction-menu-2026-11-05.json.

Installed release 63 on the S22+ through the updated user-provided wireless ADB
endpoint. Installed checksum matches validation/apk.json; settings preserved and
app not launched.


## Hold-and-drag tab/pane sorting — 2026-11-05

Both panel lists support held vertical dragging, animated row placement and
automatic edge scrolling. Drop commits once; ordinary scrolls, hold/release,
cancellation, changed sessions/lists and disabled actions do not reorder or
navigate. Non-row map content cannot start a reorder. Rows expose Move up/down
accessibility actions. The existing horizontal tab drag shares the guarded
gesture detector and retains its behavior.

Tabs call the existing synced move-window path. As requested, pane sorting also
changes the server layout: a fresh view must match the expected window pane
order before sequential explicit session.swap_blocks calls rearrange programs
through existing positions. Split geometry, floating-layer bounds and block IDs
are preserved. The actor never opens, owns or resizes a terminal for reordering.
Pending UI order is acknowledged on both success/failure; server metadata updates
the actual map. Failed sequences refresh the actual possibly partial order and
are never replayed automatically. Native source-schema inspection was read-only;
no user session/pane was reordered during verification.

157 JVM tests passed (one optional benchmark skipped), 58 Rust checks passed
(15 live opt-in cases ignored), and 32 Android checks passed. New isolated
WebSocket-peer tests exercise real control RPCs, tiled/floating swaps, retained
selection/ownership/program metadata, stale-order rejection before mutation and
partial failure with acknowledgment/no replay. UI coverage includes held tab/pane
drags, edge scrolling to offscreen panes, cancellation, session change, disabled
actions, map rejection and accessibility moves; existing panel/map/tab-strip
regressions also pass. Release build, lint, static APK signature/release/profile/
alignment and whitespace checks passed.
See validation/panel-drag-reordering-2026-11-05.json.

Installed release 64 on S22+ RFCT21390SD via USB. Installed checksum matches
validation/apk.json; settings preserved and app not launched.


## Compact drawers and completion alerts — 2026-11-05

Activity now matches the compact Tabs/Panes drawer: small header/handle, segmented
filters, sharp small program icons, tighter rows and inline progress percentages.
Sessions uses a compact modal drawer with search and matching typography; the
expanded duplicate session carousel above it is gone. Search, exact cross-server
selection, New/Rename/Close and normal header/icon swipe navigation remain.
Light and dark full-window emulator screenshots were visually reviewed.

Unseen done reports now light the Activity badge and can be marked seen, with
blocked/error still isolated by the Needs you filter. The dot is positioned at
the eye’s upper right without clipping. New completions notify only in background
or with the screen off/locked. Historical completions are baselined, foreground
updates consumed, and stable report stamps prevent repeats. A completion alert
survives immediate report clearing. The existing channel/privacy, exact-pane
targeting, read-only monitoring and stop behavior remain.

161 JVM tests passed (one optional benchmark skipped), 59 Rust checks passed
(15 live opt-in cases ignored), and 18 Android tests passed (one live opt-in
fixture skipped). An isolated loopback WebSocket peer exercises real native
monitor RPCs and Android notifications for foreground suppression, background
and screen-off completion delivery, duplicate suppression, historical baselining
and notification retention after idle. It also checks there are no ownership,
input or resize RPCs. UI coverage includes finished-report acknowledgment, dot
bounds/accessibility, filters/exact targets, offline state and session drawer
actions/navigation. Early test-only capture selection and peer cleanup failures
were corrected; the full regression run then passed.

Release build, lint, APK signature/release/profile/ABI/16KB-alignment checks and
whitespace validation passed. Installed release 66 on S22+ RFCT21390SD via USB;
installed checksum matches validation/apk.json, settings preserved, app not
launched. No live user session/pane was modified during tests.
See validation/activity-completion-drawers-2026-11-05.json.


### Eye badge reference correction — release 67

The reference badges the eye glyph itself, rather than the outer touch target.
The badge now overlaps the upper-right eye contour with a small background
cutout; the eye has a pointed outline and shares the unread accent color.
The 41dp background and button geometry remain unchanged. Two focused Android
checks pass for glyph-relative badge bounds, unread semantics, stable dimensions
when cleared, and button behavior. Light/dark icon captures were visually compared
with the supplied reference. Release build, lint, APK packaging/signature checks
and whitespace validation pass. Release 67 installed on the S22+ with matching
checksum, settings preserved and app not launched.


### Activity status alignment and neutral eye — release 68

Activity rows place status at a shared trailing edge with Mark seen below,
keeping Finished aligned with Seen and the title baseline. The eye outline
remains neutral for unread activity; only the dot uses the accent color. Five
focused Android checks pass, including finished/seen text bounds, action placement,
acknowledgment without navigation, exact target selection, offline rows and
indicator bounds/accessibility. Light/dark captures were visually reviewed.
Release build, lint, signature/packaging and whitespace checks pass. Release 68
installed on S22+ RFCT21390SD; checksum matches validation/apk.json, settings
preserved and app not launched.


### Restore Activity title centering — release 69

Removed the baseline alignment coupling between the whole title/context column
and the trailing status/action column introduced in release 68. Each column is
independently vertically centered again. Status text retains its common trailing
edge, Mark seen stays below it, and the eye stays neutral with only its dot
accented. Five focused Android checks pass, now including the title/context
block center relative to the card and identical bounds before/after marking
seen. Dark and light previews were reviewed. Release/lint/static packaging and
whitespace checks pass. Installed release 69 on S22+ RFCT21390SD with matching
checksum, settings preserved and app not launched.


### Activity state bars, matching icons and clean descriptions — release 70

Removed per-row Finished/Seen/Working status words. The left rail is solid blue
for new completions, a looping blue segment for working, amber for attention,
red for failures and muted when acknowledged/idle. Exact status/progress remains
in filters and accessibility. Only working rows animate; phase is observed during
drawing. A pixel comparison advances the clock and verifies the working segment
moves while a finished bar stays unchanged and bounds stay fixed.

Each row has a two-line title/description block. Its actual text column is measured
first in the same Layout pass, and the vector program tile and rail receive that
exact height. This avoids nominal line-height/font-trimming mismatch and delayed
size changes. A reserved action slot keeps title bounds stable on acknowledgment.
Descriptions combine report message and useful session/window context; generated
Window hexadecimal labels are hidden without altering routing identity.

162 JVM tests passed (one optional benchmark skipped), including generated-label
filtering and retained meaningful names/targets. Six focused Android checks pass
for exact icon/text height and center, stable acknowledgment layout, accessible
state, exact navigation, offline behavior, button/dot behavior and moving-vs-static
bar pixels. Light/dark full-window previews were reviewed. Release build, lint,
APK signature/packaging and whitespace checks pass. Installed release 70 on
S22+ RFCT21390SD with matching checksum, settings preserved and app not launched.
No live user sessions or server configuration were changed by tests.


### Solid working pulse — release 71

Replaced the traveling snake segment with a filled blue rail whose brightness
cycles smoothly from 45% to 100% on the shared tab highlight’s 1.2-second cadence.
The phase is still observed only during drawing, and Finished remains steady.
Six focused Android checks pass, including pixel comparisons proving the whole
rail changes brightness uniformly, stays blue/visible, preserves its bounds and
leaves finished pixels unchanged. Existing exact icon/text heights, centered
rows, acknowledgment stability, navigation, offline and eye/dot checks pass.
Release build, lint, APK signature/packaging and whitespace checks pass. Installed
release 71 on S22+ RFCT21390SD with matching checksum, settings preserved and
app not launched. No live user session/pane was changed by tests.


### Shared tab highlight, attention pulse and quiet refresh — release 72 (2026-11-06)

Working uses the same drawing helper as the actual tab indicator: solid blue with
a translucent white highlight traveling along the long axis. The helper retains
the tab’s original gradient/phase proportions; Activity applies it vertically.
Unseen Needs you reports pulse amber uniformly. Finished stays solid blue, and
acknowledged reports remain muted. The refresh line below All/Needs you/etc. was
Activity’s own LinearProgressIndicator, not the tab. Removed it while retaining
the layout gap; empty first loads still show Checking activity.

17 unique Android checks passed across focused runs (8 Activity, 9 adaptive tab).
Coverage includes working-vs-attention-vs-finished pixel behavior; tab/Activity
highlight comparison along rotated axes with two-level per-channel GPU rounding
tolerance; no second progress indicator or row movement during metadata refresh;
empty loading feedback; and existing icon/text, acknowledgment, navigation and
tab layout checks. Release/lint/static packaging/signature and whitespace checks
pass. No live sessions were changed. Installed release 72 via wireless ADB at
100.97.157.23:36789 on the S22+; installed checksum matches validation/apk.json,
settings preserved and app not launched. APK’s UTC date version is 2026.11.05.


### Larger joystick neutral zone — release 73 (2026-11-06)

The keyboard-open large joystick has a 14dp neutral radius instead of 10dp;
its visible center grows from 12dp to 14dp. The small joystick is unchanged.
Twelve joystick instrumentation tests pass, including Up followed by a 12dp
center overshoot (no Down/repeat), deliberate 20dp Down, repeat cancellation,
release, pane/control changes, swipe capture and popup preferences/toggling.
Release/lint/APK signature/packaging and whitespace checks pass. Installed
release 73 over wireless ADB at 100.97.157.23:36789; checksum matches
validation/apk.json, settings preserved and app not launched.


## Watched-pane suppression and experimental Codex attention — release 74 (2026-11-06)

165 JVM tests passed (one optional benchmark skipped), including exact focus
identity, background/lock/dialog/navigation exclusions and watched completion
acknowledgment while other panes keep their badge. 60 Rust checks passed (15 live
opt-in ignored), including strict Codex marker/owner/root guards, resume clearing,
other-program rejection and shared hidden activity snapshots without recopying.
12 Android checks passed for the clearly labeled default-off preference, policy
versions across reconnect/UI-service overlap/repeated questions, Activity layout
and an isolated loopback service test. The latter verifies actual background and
screen-off notifications, default-off suppression, live title-only Codex Needs
you alerts, no animation reposts, clearing on resume, a second question alert and
restoring Working when disabled. No user panes were mutated for tests.

Release/lint/signature/ABI/profiles/16KB packaging and whitespace checks pass.
Installed build 74 on S22+ RFCT21390SD via USB; checksum matches validation/apk.json,
settings preserved, app not launched and experiment left off by default.

A separate read-only comparison found both MBP and Ubuntu on Claude 2.1.318 and
Rex 0.0.1793741575-g1b6654/API 10. MBP had a structured claude-code Done record;
Ubuntu had no records while its terminal title began with the working circle.
This confirms different signals, but not which configuration/runtime path causes
them. No Claude or Rex configuration was changed. The protocol stores program
reports and permits mapping legacy progress; the API alone does not identify
which mechanism produced the MBP record.


## Server status comparison and launcher artwork — release 75 (2026-11-06)

Known OSC 7501 Working (progress 37), Blocked (question), Done, Error and Clear
signals passed on both MBP and Ubuntu in isolated temporary sessions. Both also
returned identical OSC 7501 capability and primary device-attribute replies.
All temporary sessions were removed; existing user panes and configuration were
unchanged. Evidence: validation/server-status-probe-2026-11-06.json. These tests
rule out a basic parser/detection failure for these signals, but do not identify
why the observed Claude sessions publish different signals.

The supplied blue-tile dinosaur artwork replaces the launcher source. A
reproducible 10% crop removes the image's outer black rounded frame; the adaptive
foreground retains square proportions and bleeds past the mask over a matching
blue background. Two launcher instrumentation checks pass, and the actual
Android mask render was visually checked for filled edges and no bottom gap.
Release build, lint, signature and packaging checks passed. Build 75 installed
on S22+ via USB with matching installed APK checksum; settings preserved and app
not launched. Notification monochrome artwork is unchanged.


## Launcher head clearance — release 76 (2026-11-06)

Reduced foreground artwork from 72% to 64% of its adaptive layer (approximately
11% smaller), preserving its downward offset. Insets are now 18/20/18/16 percent
(left/top/right/bottom). The actual Android launcher render shows the full head
with top clearance and filled bottom edges. Both focused launcher instrumentation
checks passed; release build, lint, static signature/packaging and whitespace
checks passed. Installed build 76 over USB with matching APK checksum, preserving
settings without launching the app.


## Breathing Activity Working and smaller launcher — release 77 (2026-11-06)

Activity Working now breathes uniformly blue with the same cosine brightness
curve and 1.2-second cycle used for unseen Needs you amber. Finished remains
steady, and the tab's traveling highlight is unchanged. The focused animation
check verifies changing, vertically uniform blue/amber brightness, steady Done
and stable layout bounds. Both launcher checks also passed. The launcher art is
another 6.25% smaller with 20/24/20/16 percent insets (left/top/right/bottom),
anchored toward the bottom. Actual Android mask render was visually reviewed.
Release build, lint and static signature/packaging checks passed. Build 77
installed over USB with matching checksum, settings preserved, app not launched.


## Original transparent artwork — release 78 (2026-11-06)

Restored the original transparent dinosaur and white adaptive background. The
new user attachment exactly matches the tracked original source SHA-256, so its
existing clean alpha asset is reused without regenerating the artwork. Insets
16.5/21.5/16.5/11.5 percent account for the original canvas margins, maintaining
similar visible scale to build 77 with bottom anchoring. Actual Android mask
render visually reviewed: full head, no checkerboard and bottom fill. Both
launcher instrumentation checks, release/lint and static APK checks passed.
Build 78 installed via USB with checksum verified; settings preserved, app not
launched. Activity breathing indicators are unchanged from build 77.


## Native Rex image uploads — release 79 (2026-11-07)

The actual upload implementation sent a 1,813,958-byte PNG to both Mac and Ubuntu
servers, verified automatic path insertion and server-side SHA-256 in isolated
fixture panes, and removed all temporary sessions. User panes/configuration were
unchanged. Evidence: validation/native-uploads-2026-11-07.json; API schema:
validation/rex-upload-schema-r11.json; protocol: ../docs/REX_UPLOADS.md.

23 Rust checks passed (one opt-in live pipeline check skipped): includes six
loopback upload tests for real binary framing/chunks/EOF, target identity,
old-server and missing-method fallback, quota rejection, cancellation and lost
confirmation. 165 JVM checks passed (one optional benchmark skipped). Twelve
distinct Android checks passed for the native JNI/image flow, waiting for server
confirmation, no duplicate paste or Enter, lost-confirmation retry suppression,
cancellation, legacy upload behavior/target guards, saved fallback settings,
IME input, clipboard images and preferences. The final native/UI subset of six
also passed against the final debug build.

Release build and lint passed. Static APK signature, ABI, alignment, baseline
profile and all nine JNI exports (including the three upload entry points) were
verified. Build 79 and checksum/verification sidecars are in dist. Phone is
currently disconnected; this release has not been installed there. The tracked
validation/apk.json continues to describe the previously installed build.


## Native uploads only — release 80 (2026-11-07)

Removed the Portside client/configuration/preferences and app-launch package
query, the legacy path-quoting/insertion handoff, its integration fixture/script
and its production OkHttp dependency. Preferences and image progress UI now live
in dedicated files. Existing native picker/clipboard/IME entry points remain;
unsupported servers show a Rex update message. README and build/protocol docs
now describe native uploads only. Historical reports remain as evidence.

157 JVM tests passed (one optional benchmark skipped). Ten focused Android tests
passed: native content/confirmation, no duplicate paste, cancellation, uncertain
completion, unsupported-server upgrade, keyboard/clipboard and preference
controls in both themes. Release build, lint and static APK signature/packaging/
JNI checks passed. Release DEX contains no Portside classes or OkHttp runtime.
Build 80 APK, checksum and verification sidecars are in dist. The phone is not
connected, so this build is not installed there. See
validation/native-only-uploads-2026-11-07.json.

Build 80 subsequently installed on S22+ RFCT21390SD over USB. Installed APK
checksum matches validation/apk.json; settings preserved, app not launched.


## Upload success auto-dismiss — release 81 (2026-11-07)

After successful native completion the image state and staged image are cleared
immediately, without a completed overlay, Dismiss action, notice or dialog.
Progress/finishing and error handling remain. Seven Android checks passed,
including waiting for actual server confirmation, clearing without a notice,
accepting the next image, cancellation, uncertain completion, unsupported servers
and clipboard/IME behavior. Release/lint/static signature/packaging checks pass.
Build 81 is ready in dist; the phone is disconnected and not updated yet.

Separately investigated cross-client seen state. Rexling stores seen versions in
per-endpoint local preferences. Fresh server help exposes program_status but no
shared acknowledgment method or seen/read field. The installed Mac app also has
a ProgramActivity SeenLedger. No supported path to mirror its acknowledgments
was found, so no heuristic based on Mac ownership/focus was introduced. Shared
seen-state synchronization remains unimplemented pending an upstream API.

Build 81 subsequently installed on S22+ RFCT21390SD over USB. Installed APK
checksum matches validation/apk.json; settings preserved, app not launched.


## Bottom-right upload pill — release 82 (2026-11-07)

Upload progress is now a compact, outlined capsule anchored above the bottom
controls with a 16dp right inset. A blue ring animates determinate progress or
rotates during preparing/finishing; percentage digits roll individually with
fixed-width slots. Content-size changes expand toward the left/up from the fixed
bottom-right anchor. Typography and colors use the shared app settings styles.
The close control retains a 48dp touch target. Errors expand the same surface,
keep the close button aligned to the right, and expose Retry only when safe.
Successful uploads still clear automatically without a completion dialog.

Five Android checks passed: the visual/action test covers both themes, progress
and phase changes, stable pill/action right edges, cancel/dismiss/retry and
uncertain-error retry suppression; four native flow checks cover completion,
cancellation, lost confirmation and unsupported servers. Light/dark pill/error
captures were visually reviewed. Release/lint/static packaging and JNI checks
passed. Installed build 82 on S22+ RFCT21390SD over USB with matching checksum;
settings preserved, app not launched. Evidence: validation/upload-pill-2026-11-07.json.


## Compact blue upload pill and brief green success — release 83 (2026-11-07)

The pill uses a light-blue #e5edff fill without a border or shadow, with the
existing app-primary progress ring. Visual height shrinks from 52dp to 40dp,
leading padding from 16dp to 12dp, and gaps from 10dp to 8dp; percentage layout
no longer reserves blank leading digits. Labels and changed digits use short
vertical motion plus soft blur/fade, replacing the previous full-height slide.
Size remains anchored at the bottom right. On confirmed success, the container,
label and completed-ring check animate green for 750ms, then clear without any
dismissal action. A new attachment can replace that transient success immediately.

Five Android checks passed, covering right-edge/action anchoring, both themes,
progress and phase transitions, success display/no cancel action, error safety,
and real native confirmation/success/auto-dismiss, cancellation, lost confirmation
and unsupported servers. Fifty-eight animation frames and static light/dark/
success captures were reviewed; a GIF preview is in dist. Release/lint/static
packaging checks passed. Installed build 83 via USB on S22+ RFCT21390SD, verified
installed checksum, preserved settings and did not launch the app. Evidence:
validation/upload-pill-refinement-2026-11-07.json.


## Lighter success mark — release 84 (2026-11-07)

Success ring stroke transitions from 2.5dp to 1.4dp over 180ms; the check
stroke is reduced to 1.2dp. Blue progress remains 2.5dp. Release, lint, static
APK and whitespace checks passed. Installed build 84 via USB with matching
checksum, settings preserved and app not launched.


## Tab-sized upload pill — release 85 (2026-11-07)

Pill text now matches tab labels at 12sp/18sp with zero letter spacing.
Height shrinks from 40dp to 32dp; leading padding 12dp to 8dp; gaps 8dp to 6dp;
ring diameter 18dp to 14dp. Digit slots/blur, close glyph and ring strokes scale
proportionally; the existing 32dp close control is retained. Error content caps
at 280dp instead of 320dp. The bottom-right anchor, blue fill/ring and brief green
success remain. The existing both-theme layout/action check passed and compact
progress/success captures were visually reviewed. Release, lint, static APK and
whitespace checks passed. Installed build 85 on S22+ RFCT21390SD via USB with
matching checksum; settings preserved and app not launched. Evidence:
validation/upload-pill-tab-sizing-2026-11-07.json.


## Network-confirmed upload progress — release 86 (2026-11-07)

Fixed setup incorrectly mapping to Uploading 0%: staging/connection/handshake now
remain Preparing, and Uploading shows an indeterminate ring until its first
confirmed checkpoint. Socket-buffer writes no longer advance the displayed byte
count. Standard WebSocket Ping/Pong checkpoints follow every 64 KiB (and the final
partial chunk); a matching emitted checkpoint advances the transport-received
counter. Checkpoints are pipelined with a 4 MiB in-flight bound, not awaited per
chunk. Finishing starts only after all file bytes are transport-confirmed. This
is not a disk-commit acknowledgment; the final server close still confirms path
insertion. Kotlin polls progress every 40ms instead of 80ms. No fake time-based
percentage or minimum upload duration was introduced.

Seven Rust upload tests passed, including a throttled 512 KiB receiver with
multiple monotonic intermediate updates and an assertion that reported progress
never precedes bytes observed by the peer. Six Android tests passed, including
a throttled native/JNI/Kotlin receiver with at least three intermediate values,
Preparing during a held handshake, zero-percent suppression, confirmation,
cancellation, failure handling and the existing pill/action checks. Actual 1.8MB
uploads to Mac and Ubuntu each produced 28 checkpoints, automatic path insertion
and matching server-side SHA-256; all disposable sessions were removed. Existing
user panes were untouched. Evidence: validation/upload-progress-live-2026-11-07.json.

Release/lint/static packaging/whitespace checks passed. Installed build 86 on
S22+ RFCT21390SD via USB with matching checksum; settings preserved, app not
launched. See validation/upload-progress-2026-11-07.json. Small files may still
finish too quickly for many percentage frames; connection delay is shown as
Preparing rather than stalled zero-percent progress.


## Upload text baseline and semibold weight — release 87 (2026-11-07)

Animated digit slots no longer use a fixed height. Both the slots and percent
sign align by the first text baseline. Labels and percentages use semibold (600)
at the existing 12sp/18sp size. The existing both-theme layout/action check and
visual review passed, along with release/lint/static APK and whitespace checks.
Installed build 87 via USB with matching checksum; settings preserved and app
not launched. Evidence: validation/upload-text-baseline-2026-11-07.json.


## Discoverable session creation — release 88 (2026-11-07)

Added New session to the terminal three-dot menu. The session drawer's small New
text action is now an explicit New session tonal button, and connected servers
with no sessions have a centered creation action. The empty header says Sessions
instead of Connect. A shared dialog identifies the current server and accepts
an optional name. The connection creation method reports whether it accepted the
command; rejection keeps the dialog/name available instead of silently closing.

Five Android checks passed: drawer selection/search/actions, creation from an
empty drawer, optional-name/rejected-request behavior and empty-server entry.
157 JVM checks passed (one optional benchmark skipped). Release/lint/static APK
and whitespace checks passed. Built the shareable build 88 APK with checksum
and verification sidecars. Installed via wireless ADB at 100.97.157.23:37229 on
SM-S906E and verified installed checksum; settings preserved, app not launched.
Evidence: validation/session-creation-2026-11-07.json.

### 2026-11-10 — Floating-pane corner dock (build 89)

- Added the saved, default-off **Preferences → Panes → Minimize floating panes** option. Floating panes use a compact bottom-right program-icon dock, stay in the management drawer, and are excluded from carousel and shortcut swipes. The server layout is unchanged.
- Opening a dock icon shows that terminal alone. The active icon, down arrow, or Back minimizes it and returns to the last tiled pane in that session/tab; closed tiled panes fall back safely. Floating-only tabs can minimize to an empty terminal area with the dock still available. Hidden floating terminals do not count as watched Activity panes.
- Kept the dock above the pane indicator and separated it from Latest, upload progress, and the default Neovim shortcut position.
- JVM checks: 160 passed, 1 optional skipped. Three new policy cases cover swipe exclusion/wrap, per-session/tab return history, closed-pane and missing-geometry/floating-only fallbacks.
- API 37 emulator: 6 instrumented checks passed (dock open/switch/minimize, preference on/off, pane indicator, saved workspace). Reviewed the light-theme dock capture. Release lint, APK signature, baseline profiles, arm64 JNI exports and 16 KB alignment passed.
- Installed the release over USB on S22+ `RFCT21390SD`. Verified the installed base APK matches SHA-256 `35e06b42b5749e96ecd5125cb213517bf5543297b947fb3480d7dc1f4c38485c`. Settings preserved; did not launch the app or modify existing Rex sessions for testing. Physical-device gesture behavior awaits user testing.
- Evidence: `validation/floating-panes-2026-11-10.json`, `validation/apk.json`; APK and verification/installation records in `dist/`.

### 2026-11-10 — Compact floating stack and bottom bar (build 90)

- Replaced the floating dock with a title-free 40×36 dp program stack, using the same sharp icons as tabs. Multiple panes expand upward into a straight icon fan; tapping the group again collapses it. Selecting an item collapses the fan. A single pane opens directly, and the active item minimizes it.
- Reduced tab rows from 49 to 45 dp and keyboard rows from 53 to 45 dp (vertical padding 8 to 4 dp; key faces retain their height). Increased the fixed pane-indicator strip from 14 to 40 dp and matched terminal bottom padding; the floating stack occupies its bottom-right corner. Moved swipe labels/Latest clear of the strip and kept the expanded fan above Neovim controls.
- JVM: 160 passed, 1 optional skipped. Release lint and static APK checks passed. Emulator: all 39 checks passed across FloatingPaneDockTest, PaneChromeTest, KeyboardBarTest and AdaptiveTabsTest. Updated keyboard fixtures to respect system insets (matching the real workspace) and the tab test to assert the new compact height. Reviewed collapsed and expanded light-theme captures.
- Installed build 90 over USB on S22+ `RFCT21390SD`, settings preserved, app not launched. Installed SHA-256 matches `0652eb7c3027e81ea2eadac84cbca2635413410faef26940211b781aa438cf2f`. Server icon picker deferred until the user's UI review.
- Evidence: `validation/floating-panes-compact-2026-11-10.json`; APK, checksum, verification and installation records in `dist/`.

### 2026-11-10 — Reviewed and merged PR #1

- Reviewed contributor commit `8c299d1d13a7e25bb52489a93b22767cd211152f` (Shift+Enter) in an isolated checkout. It preserves plain Enter and paste handling while translating the dedicated Shift+Enter sequence into a Rex key event.
- PR checkout JVM suite: 157 passed, 1 optional skipped. JNI suite: 44 passed, 14 ignored, including the new Shift+Enter event test. No GitHub CI runs/statuses were attached.
- Merged with the expected PR head pinned; GitHub merge commit `4fefbf46c3c1170839d07740f8f18343b7d71bef`. Fast-forwarded local main and preserved all pending UI/session changes. Installed build 90 predates this merge; the Shift+Enter fix will be included in the next APK.

### 2026-11-10 — Floating-pane titles, active highlight and spacing (build 91)

- Expanded floating items now show content-sized title pills with their sharp program icons at the right. Labels use 13 sp medium text, cap at 260 dp and ellipsize. The collapsed stack stays icon-only and keeps its position when the menu expands. Both the stack and selected menu item use the primary selection container while viewing a floating pane.
- Reduced the pane-indicator strip and matching terminal inset from 40 to 36 dp, the collapsed button from 36 to 32 dp high, and the terminal-to-tab gap from 7 to 3 dp. This APK includes merged PR #1's Shift+Enter fix.
- Emulator checks: all 12 tests passed across FloatingPaneDockTest and PaneChromeTest, covering visible titles, stable anchor position, active semantics, open/minimize/group toggle and swipe chrome. JVM: 160 passed, 1 optional skipped. Release lint, signature, JNI exports, baseline profiles and 16 KB alignment passed. Reviewed the expanded title-pill/active-stack screenshot.
- Shareable APK: `dist/Rexling-2026.11.10-build91-4fefbf46c3-modified-arm64-release.apk`; SHA-256 `a43762ac04d72d5e40c9dddd1e5875ed00093e687e42de9728d27f0d448cbe41`. Phone disconnected; installation pending. `validation/apk.json` continues to describe installed build 90.
- Evidence: `validation/floating-pane-titles-2026-11-10.json` and the APK's `.verification.json` / `.apk.sha256` records.

Build 91 installation follow-up: installed over USB on S22+ `RFCT21390SD`; verified installed base APK SHA-256 matches the build 91 report. Settings preserved; app not launched. `validation/apk.json` now describes installed build 91.

### 2026-11-10 — Floating surfaces (build 92)

- Added rounded opaque surfaces and soft 4 dp drop shadows to the collapsed icon-only floating stack and expanded icon/title pills. Preserved blue active highlights. Reserved scroll padding and allowed the fan animation to draw its shadows outside its bounds.
- Three existing floating-dock interaction tests passed; reviewed the light-theme screenshot. Release lint, APK signature, JNI exports, baseline profiles and 16 KB alignment passed.
- Installed over USB on S22+ `RFCT21390SD`, preserving settings without launching the app. Verified the installed base APK matches SHA-256 `49c82b466ba97d30ae051a700461b6d1d114501bda2bfd078fdd49e77b629dd5`. Evidence: `validation/floating-pane-surfaces-2026-11-10.json` and the build 92 APK verification/installation records in `dist/`.

### 2026-11-10 — Server icons and bare floating toggle (build 93)

- Added per-server icons via Servers → server actions → Edit → Icon: Globe, Server, Cloud, MacBook, Mac mini, Mac Studio and Desktop. Icons are saved with the existing profile identity/endpoint and displayed in the server list, session header and drawer, and Activity group headings. Old/unknown values safely fall back to Globe. Icon-only saves preserve existing connections.
- Removed the pill and rectangular touch indication around the floating-menu toggle. Shadows apply to the individual program tiles; a primary-color outline marks the active front icon. Expanded icon/title pills retain their surfaces and shadows.
- JVM: 162 passed, 1 optional skipped, including all-icon round-trip persistence and old/unknown-icon migration. Emulator: 13 checks passed across ServerIconPickerTest, FloatingPaneDockTest, HeaderLineLayoutTest and SessionHeaderLoopTest. Header checks now include a MacBook glyph; corrected the older test expectation for the already-connected empty-session label (Sessions). Reviewed the picker and bare-toggle light screenshots. Release lint and static APK checks passed.
- Installed build 93 over USB on S22+ RFCT21390SD; settings preserved, app not launched. Installed SHA-256 verified: `12bf3f5bc869818dea7e43af45be63e79e17f5ef762f8d7ac0192c2a93f2a894`. Evidence: `validation/server-icons-2026-11-10.json`, `validation/apk.json`, and build 93 artifacts in `dist/`.

### 2026-11-10 — Drawer terminal edits (build 94)

- Row actions replace the close-only trailing button: Rename tab/pane, Float/Dock pane, and Close. Rename supports automatic-title reset with an empty value; explicit pane labels override animated program titles. Actions do not select or open the edited terminal. Busy states disable mutations, and stale server targets/layouts/names are validated before RPC calls. Docking chooses an existing tiled pane in the same tab and a right/below split; floating keeps one tiled anchor per tab.
- Rex protocol verified against the installed server: `session.set_window_label`, `session.set_block_label`, `session.float_block`, and `session.move_block`. Native operations are sent once and refresh the server view; layout operations are not replayed after an uncertain reply.
- JVM: 164 passed, 1 optional skipped. JNI: 45 passed, 15 optional ignored; the new live test was run separately and passed on a dedicated loopback Rex server. It verified rename/float/dock metadata, selected pane and ownership preservation, and another client’s session view. Temporary sessions were destroyed and the isolated server stopped; existing user sessions were untouched.
- Emulator: 27 checks passed across TerminalEditActionsTest, TabPaneSwitcherTest, PanelReorderTest and FloatingPaneDockTest. Covers rename save/reset/rejection, dock target/side, float-last-anchor guard, menu close actions, local selection, busy states, layout and reorder regressions. Release lint and static APK checks passed.
- Installed build 94 on S22+ RFCT21390SD over USB, settings preserved, app not launched. Verified installed SHA-256 `bfa788dd8f7380084aeeeabf3bad3ec94bba6112a6e11e85831804d63a5f30a1`. Evidence: `validation/terminal-edits-2026-11-10.json`, `validation/apk.json`, and build 94 artifacts in `dist/`.

### 2026-11-10 — Pane-switch minimap (build 95)

- Added a compact 56×36 dp read-only layout map beside the existing pane-switch program icon/title. It uses the actual server geometry, follows the swipe destination, and shares the existing post-switch dismissal timing. Only swipeable panes are included; incomplete layouts keep the original icon/title overlay. The minimap does not resize terminal content or add another fixed pane indicator.
- All 10 emulator checks passed across PaneChromeTest and PaneSwitchMinimapTest: existing wrap/cancel/handoff and fixed-indicator behavior, overlay/Latest separation, compact content bounds, and correct highlighted geometry in both app themes. Reviewed the combined overlay and light/dark minimap captures. Release lint, signature, JNI exports, baseline profiles and 16 KB alignment passed.
- Installed build 95 over USB on S22+ RFCT21390SD with settings preserved and without launching the app. Installed SHA-256 verified: `42e157af6dda601aaf0c0dc2553ccef73cab7182325fd3be60fe73e8d68f5db0`. Evidence: `validation/pane-switch-minimap-2026-11-10.json`, `validation/apk.json`, and build 95 artifacts in `dist/`.

### 2026-11-10 — Stacked pane-switch overlay (build 96)

- Placed the minimap above a centered program icon/title row inside the same compact overlay. Raised Latest clear of the taller badge; terminal geometry and fixed pane indicator stay unchanged.
- All 10 PaneChromeTest/PaneSwitchMinimapTest checks passed, including explicit caption-below-map placement, swipe handoff/wrap, compact sizing and Latest separation. Reviewed the stacked overlay screenshot. Release lint and static APK checks passed.
- Installed over USB on S22+ RFCT21390SD; settings preserved, app not launched. Installed SHA-256 verified: `88a7e70ee0227d3e39cf4b5ce12d0146ed3afea3dcd9a1ad2ec5bf1aa767fe70`. Evidence: `validation/stacked-pane-minimap-2026-11-10.json` and build 96 artifacts in `dist/`.

### 2026-11-10 — Pane title/directory captions (build 97)

- Minimap now sits separately above the caption without a shared surrounding card. The caption uses the same pane title and directory metadata as the header, on two lines. Expanded floating-pane menu items show title and directory on one line; their icon-only toggle is unchanged.
- All 14 PaneChromeTest/PaneSwitchMinimapTest/FloatingPaneDockTest checks passed, including title/directory vertical placement, one-line floating descriptions, map highlight, swipe regressions and Latest separation. Reviewed both caption captures. Release lint and static APK checks passed.
- Installed build 97 over USB on S22+ RFCT21390SD; settings preserved, app not launched. Installed SHA-256 verified: `c9705025aeb372ef331a5841b445d694edfca3d5e9de0f46a46c2c0b5acba08a`. Evidence: `validation/pane-captions-2026-11-10.json` and build 97 artifacts in `dist/`.

### 2026-11-10 — Caption typography (build 98)

- Matched the switch-caption first line to the tab’s 12 sp title size and the same fixed icon slot plus 2 dp icon-to-text spacing. All 14 pane chrome/minimap/floating-menu checks passed. Release lint and static APK checks passed.
- Installed over USB on S22+ RFCT21390SD, preserving settings without launching the app. Installed SHA-256 verified: `30d0c03c5593b6c03d7d74c690a053fc375ccc33b856523e9b4529f66034b674`. No fork code was applied; fork investigation remains read-only. Evidence: `validation/pane-caption-typography-2026-11-10.json` and build 98 artifacts in `dist/`.

### 2026-11-10 — Tighter swipe-caption padding (build 99)

- Reduced caption padding from 12 to 6 dp horizontally and 6 to 4 dp vertically; tab-matched typography and icon/text spacing unchanged. Release build, lint, static APK verification and whitespace checks passed. Existing interaction tests were not repeated for this padding-only adjustment.
- Installed wirelessly on S22+ at 100.97.157.23:36045, preserving settings without launching the app. Installed SHA-256 verified: `283014b609a6c8768c7124459dccd3cbd52acdd36aac29813e4e9582329ea00c`. Evidence: `validation/compact-pane-caption-2026-11-10.json` and build 99 artifacts in `dist/`.

### 2026-11-10 — Matching typography and frosted pane surfaces (build 100)

- Caption text previously inherited Material letter spacing while tabs explicitly set 0 sp. Matched active-tab typography: bold 12 sp, 0 sp letter spacing and no font padding; directory and floating menu text also use explicit 0 sp spacing.
- Added Haze 1.5.4 backdrop capture/blur with a 14 dp blur radius, subtle noise/tint and glass edge for the swipe caption and expanded floating-pane pills. Foreground text/icons stay crisp; selected floating items retain a stronger primary-container tint. Unsupported blur rendering uses a tinted fallback. The collapsed toggle remains bare icon tiles. Shared terminal-source capture is enabled only during the swipe overlay or expanded menu, and remains through the close animation. See https://chrisbanes.github.io/haze/1.5.4/usage/ for the source/effect model; dependency attribution is in THIRD_PARTY_NOTICES.md.
- All 16 emulator checks passed across PaneChromeTest, PaneSwitchMinimapTest, FloatingPaneDockTest and PaneCompositingTest. New pixel test proves actual red/blue native terminal rows are sampled and blurred behind the caption; TextLayoutResult verifies 0 sp letter spacing/12 sp size. Floating-menu lifecycle checks verify capture stops after closing. Reviewed the hardware blur capture. Release lint, signature, JNI exports, baseline profiles and 16 KB alignment passed. No physical-device frame-pacing profile was run.
- Installed wirelessly on S22+ at 100.97.157.23:37191, preserving settings without launching the app. Installed SHA-256 verified: `c103a9b374e680f4f62e8f5fd6a1d0a8d5c7e5515c6f5ccd89fb22c02336eef4`. Evidence: `validation/pane-frost-2026-11-10.json` and build 100 artifacts in `dist/`.

### 2026-11-10 — Lighter haze across terminal overlays (build 101)

- Reduced neutral tint to 32% (selected 46%), increased blur to 20 dp, and added frosted minimap, Neovim wheel sectors/hub, sizing notice and upload pill. Wheel uses 60% dark tint for readable white labels; upload uses 60% blue/green tint. Kept the wheel center gap, animation geometry, compact sizes and upload success transition. Capture runs while these surfaces are visible.
- Fixed cross-window wheel backdrop refresh using Haze’s public experimental effect node API: host pre-draw invalidates the visible popup effects, with listener cleanup on close/dispose. New pixel test confirms live red-to-green backdrop updates, transparent neutral gap and capture release. Reviewed wheel and upload screenshots. All 43 relevant UI checks passed: 16 pane chrome/minimap/floating/compositing checks and 27 final wheel/connection/upload checks. The ADB instrumentation stream disconnected when USB reconnected, but emulator TestRunner logcat confirms all 27 final tests completed with zero failures. Release lint and static packaging/signature checks passed; no physical frame-pacing profile.
- Installed via USB on S22+ RFCT21390SD, preserving settings without launching the app. Installed SHA-256 verified: `6f20c32040cb35266d809e737104f265de1e55c17964fe7de5b930a0ad6f0feb`. Evidence: `validation/pane-frost-2026-11-10.json`, build 101 artifacts in `dist/`, and `/tmp/rexling-wheel-frost-final-runner.log`.

### 2026-11-10 — Even swipe-caption icon padding (build 102)

- Removed the icon’s reserved tab slot in the swipe caption and sized its tile to the two-line text block, giving icon and text the same outer padding. Reviewed the rendered caption; existing native-backdrop/typography UI check passed. Release build, lint and APK verification passed. Phone was disconnected, so the shareable APK is prepared but not installed. Evidence: `validation/caption-icon-padding-2026-11-10.json`.

- Build 102 subsequently installed via USB on S22+ RFCT21390SD. Installed APK SHA-256 verified: `40642a6853d8d21875625b9ae01801840232cf46747f6a5365787f3fb8ac52ef`. Settings preserved; app not launched.

### 2026-11-10 — Tab-sized swipe caption icon (build 103)

- Restored the tab’s painted 22×18 dp icon size and matched its visible 9 dp icon-to-text gap (tab fixed slot trailing inset 7 dp plus 2 dp row spacing), retaining even outer horizontal padding. Vertically centered beside two-line text. Release build, lint and static APK verification passed; no new interaction tests for this size/spacing-only adjustment. Phone absent from ADB, so APK prepared but not installed. Evidence: `validation/caption-small-icon-2026-11-10.json`.

- Build 103 subsequently installed via USB on S22+ RFCT21390SD. Installed SHA-256 verified: `eaa5272da007bacf1a9571a3f6dbfa389c833d8fce8ba400733b906edfc2c864`. Settings preserved; app not launched.

### 2026-11-10 — Expanded program recognition and vector tiles (build 104)

- Selectively adapted the fork’s program catalog and SVG compiler support, with provenance retained at `ed05beb791122a005695ecbd5fbd51d9c12f2dfe`. Ships 155 tiles (144 additions); CUA’s embedded raster-logo tile is excluded rather than displaying a blank logo. Uses executable paths/aliases, not terminal titles. Generic Node/Python and shell wrappers do not hide a recognized program. Existing custom-label behavior and program kinds are retained. Native vector decoding supports stroke width, caps and joins. No fork terminal clipping, QR, native-build shortcuts or localization changes applied.
- JVM: 166 passed, 1 optional skipped. Four emulator rendering checks passed, covering all 155 tiles, original 11 reference comparisons, cache/stack sizing and small-size rendering. Reviewed the catalog atlas. Verified all 155 source SVG hashes and unchanged drawing commands for the original 11. Release lint, signature, JNI exports, baseline profiles and 16 KB alignment passed.
- Installed via USB on S22+ RFCT21390SD, preserving settings without launching the app. Installed SHA-256 verified: `b7a483dbf66138f0c4e5ffc7b9048ffef4e89c25099a6d51a0706d31d954f09b`. Evidence: `validation/program-catalog-2026-11-10.json`, `/tmp/rexling-program-catalog-final-ui.log`, `/tmp/rexling-expanded-program-catalog.png`, and build 104 artifacts in `dist/`.

### 2026-11-10 — Frosted drawers, overflow menu and Ready notice (build 105)

- Added 24 dp backdrop blur with 72% surface tint to Tabs/Panes, Activity, Sessions and the main terminal overflow menu. Existing row/selected-state backgrounds remain mostly solid. Ready to type uses the terminal backdrop with primary-container tint. Workspace capture includes header and terminal content, runs only while needed and remains for 220 ms after dismissal; separate-window effects follow host redraws.
- Kept drawer haze inside the moving sheet content (including handle and bottom safe inset), preventing clipping from a modifier outside the sheet offset. Explicit themed foreground colors preserve dark-mode heading/row contrast over transparent containers.
- All 30 targeted drawer/connection checks passed after the clipping fix. After the foreground styling fix, reran the shared light/dark regression: all three real drawers and the menu render visible foregrounds, blur red/blue stripes and update for live green backdrop changes; dark headings retain light foregrounds. Reviewed screenshots. Release build, lint and APK checks passed. No device frame-pacing profile was run.
- Installed wirelessly on S22+ at 100.97.157.23:35963. Installed SHA-256 verified: `99d258b4a484c9d36fc0fd3a0c7bf5e6860015d0b1d51702154b8356cb4692be`. Settings preserved; app not launched. Evidence: `validation/drawer-frost-2026-11-10.json`, build 105 artifacts in `dist/`, `/tmp/rexling-drawer-frost-final-tests.log` and `/tmp/rexling-drawer-frost-contrast-test.log`.
