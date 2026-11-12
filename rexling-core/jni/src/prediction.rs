//! Mosh-style predictive local echo for high-latency panes.
//!
//! Typed characters are drawn at the cursor before the server echoes them, then
//! checked against every server frame. A prediction is confirmed when the server
//! shows the same text and has moved its cursor past it; it is dropped when the
//! server disagrees for too long. After a key whose effect cannot be predicted
//! (Enter, Tab, Ctrl keys, escape sequences) a new epoch starts, and predictions
//! in an epoch stay hidden until the server confirms one of them. That keeps
//! input at prompts that do not echo, such as passwords, off the screen.
use rex_core::ghostty::{Cell, Frame};
use std::sync::Arc;
use std::time::{Duration, Instant};

/// Cell attribute bit for a prediction the app should underline.
pub(crate) const PREDICTED: u32 = 1 << 30;
const INVERSE: u32 = 1 << 3;
const ALTERNATE_SCREEN: u8 = 2;

/// Show predictions in Slow mode once the smoothed round trip passes HIGH; hide again below LOW.
const SHOW_HIGH: Duration = Duration::from_millis(40);
const SHOW_LOW: Duration = Duration::from_millis(25);
/// Underline predictions while the round trip is above FLAG_HIGH, until it falls below FLAG_LOW.
const FLAG_HIGH: Duration = Duration::from_millis(80);
const FLAG_LOW: Duration = Duration::from_millis(50);
const MIN_TIMEOUT: Duration = Duration::from_millis(1000);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Mode {
    Off,
    Slow,
    Always,
}
impl Mode {
    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "off" => Some(Self::Off),
            "slow" => Some(Self::Slow),
            "always" => Some(Self::Always),
            _ => None,
        }
    }
}

/// What runs in the pane, as far as prediction cares.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Program {
    /// Anything that redraws its input itself; never predicted.
    Other,
    /// A shell at its prompt.
    Shell,
    /// pi, which never asks for passwords, so its next message is shown at once after Enter.
    Pi,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Kind {
    /// A typed character; confirmed once the server cursor has moved past it.
    Char,
    /// A backspace at the end of a line, drawn as a blank; confirmed once the cursor is back.
    Erase,
    /// A backspace before other text, such as a fish autosuggestion: only the cursor moves.
    /// The shell redraws the rest, so nothing is drawn; confirmed once the cursor is back.
    Move,
    /// Moves a TUI's reverse-video cursor; never confirmed or failed on its own.
    FakeCursor,
}

#[derive(Clone, Debug)]
struct Prediction {
    x: u16,
    y: u16,
    cell: Cell,
    kind: Kind,
    epoch: u64,
    /// The Char or Erase prediction a FakeCursor belongs to.
    owner: u64,
    id: u64,
    at: Instant,
    /// The server's cursor when this was predicted. Once the server cursor has moved from here
    /// past the prediction, the server has handled the key and a mismatch is a wrong guess.
    origin: (u16, u16),
}

pub(crate) struct Predictor {
    mode: Mode,
    srtt: Option<Duration>,
    showing: bool,
    flagging: bool,
    epoch: u64,
    confirmed: u64,
    next_id: u64,
    predictions: Vec<Prediction>,
    /// Where the next character goes; None means start from the server's cursor.
    cursor: Option<(u16, u16)>,
    /// Where typing started on the current line. Backspace is only guessed over text typed here,
    /// never over the prompt.
    floor: Option<(u16, u16)>,
    /// After Enter in pi: the server cursor at that moment. Prediction resumes, still visible,
    /// once a frame shows pi's cursor somewhere else.
    awaiting: Option<((u16, u16), Instant)>,
    base: Option<Arc<Frame>>,
    target: String,
}

fn blank(text: &str) -> bool {
    text.chars().all(|c| c == ' ')
}

fn cell_at(frame: &Frame, x: u16, y: u16) -> Option<&Cell> {
    frame.cells.iter().find(|c| c.x == x && c.y == y)
}

impl Predictor {
    pub fn new() -> Self {
        Self {
            mode: Mode::Slow,
            srtt: None,
            showing: false,
            flagging: false,
            // Start tentative: nothing typed is shown until the server confirms an echo.
            epoch: 1,
            confirmed: 0,
            next_id: 0,
            predictions: Vec::new(),
            cursor: None,
            floor: None,
            awaiting: None,
            base: None,
            target: String::new(),
        }
    }

    pub fn set_mode(&mut self, mode: Mode) {
        if mode != self.mode {
            self.mode = mode;
            self.predictions.clear();
            self.cursor = None;
        }
    }

    /// Forget predictions, for example after switching panes. The round-trip estimate is kept.
    pub fn reset(&mut self) {
        self.predictions.clear();
        self.cursor = None;
        self.floor = None;
        self.awaiting = None;
        self.epoch += 1;
    }

    fn tentative(&mut self) {
        self.epoch += 1;
        self.cursor = None;
        self.floor = None;
        self.awaiting = None;
    }

    fn timeout(&self) -> Duration {
        self.srtt.map_or(MIN_TIMEOUT, |rtt| MIN_TIMEOUT.max(rtt * 4))
    }

    fn sample(&mut self, rtt: Duration) {
        let next = match self.srtt {
            None => rtt,
            Some(old) => old.mul_f64(0.875) + rtt.mul_f64(0.125),
        };
        self.srtt = Some(next);
        if next > SHOW_HIGH {
            self.showing = true;
        } else if next < SHOW_LOW {
            self.showing = false;
        }
        if next > FLAG_HIGH {
            self.flagging = true;
        } else if next < FLAG_LOW {
            self.flagging = false;
        }
    }

    #[cfg(test)]
    pub fn srtt(&self) -> Option<Duration> {
        self.srtt
    }

    fn eligible(frame: &Frame) -> bool {
        frame.input_flags & ALTERNATE_SCREEN == 0
            && frame.cursor_at.is_some()
            && frame.scroll_offset + frame.scroll_visible >= frame.scroll_total
    }

    /// The displayed cell at a position: the newest prediction there, else the server's.
    fn shown(&self, frame: &Frame, x: u16, y: u16) -> Option<Cell> {
        self.predictions
            .iter()
            .rev()
            .find(|p| p.x == x && p.y == y)
            .map(|p| p.cell.clone())
            .or_else(|| cell_at(frame, x, y).cloned())
    }

    fn rest_of_row_blank(&self, frame: &Frame, x: u16, y: u16) -> bool {
        (x..frame.columns).all(|column| {
            self.shown(frame, column, y)
                .is_none_or(|c| blank(&c.text) && c.attributes & INVERSE == 0)
        })
    }

    #[allow(clippy::too_many_arguments)]
    fn push(&mut self, x: u16, y: u16, cell: Cell, kind: Kind, owner: u64, origin: (u16, u16), now: Instant) -> u64 {
        if kind != Kind::FakeCursor {
            // A newer keystroke at the same cell supersedes the older guess.
            self.predictions
                .retain(|p| !(p.x == x && p.y == y && p.kind != Kind::FakeCursor));
        }
        self.next_id += 1;
        let id = self.next_id;
        self.predictions.push(Prediction {
            x,
            y,
            cell,
            kind,
            epoch: self.epoch,
            owner: if kind == Kind::FakeCursor { owner } else { id },
            id,
            at: now,
            origin,
        });
        id
    }

    /// Forgets predictions made for another pane or connection.
    pub fn retarget(&mut self, target: &str) {
        if target != self.target {
            self.target = target.to_owned();
            self.reset();
        }
    }

    /// Records predictions for input about to be sent to `target`.
    pub fn input(&mut self, target: &str, text: &str, program: Program, now: Instant) {
        self.retarget(target);
        let Some(frame) = self.base.clone() else { return };
        if self.mode == Mode::Off || program == Program::Other || !Self::eligible(&frame) {
            self.reset();
            return;
        }
        // Escape sequences (arrows, function keys, Alt) move the cursor in ways we cannot follow.
        if text.starts_with('\x1b') {
            self.tentative();
            return;
        }
        let hidden_cursor = frame.cursor.is_none();
        let Some(origin) = frame.cursor_at else { return };
        for c in text.chars() {
            if c == '\r' && program == Program::Pi && self.awaiting.is_none() {
                // pi submits the message and clears its editor; wait for that frame, staying visible.
                self.cursor = None;
                self.floor = None;
                self.awaiting = Some((origin, now));
                continue;
            }
            if self.awaiting.is_some() {
                // Typed before pi redrew: we cannot tell where it lands, so fall back to hidden guesses.
                self.tentative();
            }
            let Some((x, y)) = self.cursor.or(frame.cursor_at) else { return };
            match c {
                ' '..='~' => {
                    if x + 1 >= frame.columns {
                        self.tentative();
                        continue;
                    }
                    let under = self.shown(&frame, x, y);
                    // Take colors from the text to the left, like the shell would.
                    let style = x
                        .checked_sub(1)
                        .and_then(|left| self.shown(&frame, left, y))
                        .filter(|left| !blank(&left.text))
                        .or_else(|| under.clone());
                    let Some(mut cell) = style else {
                        self.tentative();
                        continue;
                    };
                    cell.x = x;
                    cell.y = y;
                    cell.text = c.to_string();
                    cell.width = 1;
                    cell.hyperlink = None;
                    cell.attributes &= !(INVERSE | PREDICTED);
                    let id = self.push(x, y, cell, Kind::Char, 0, origin, now);
                    self.floor = match self.floor {
                        Some((fx, fy)) if fy == y => Some((fx.min(x), y)),
                        _ => Some((x, y)),
                    };
                    // A TUI that hides the terminal cursor draws its own in reverse video; move it too.
                    if hidden_cursor && under.is_some_and(|u| u.attributes & INVERSE != 0)
                        && let Some(mut next) = self.shown(&frame, x + 1, y)
                    {
                        next.attributes |= INVERSE;
                        self.push(x + 1, y, next, Kind::FakeCursor, id, origin, now);
                    }
                    self.cursor = Some((x + 1, y));
                }
                '\x7f' | '\x08' => {
                    // Only over text typed on this line; at the prompt, backspace does nothing.
                    let typed = self.floor.is_some_and(|(fx, fy)| fy == y && x > fx);
                    let Some(left) = x.checked_sub(1).and_then(|left| self.shown(&frame, left, y)).filter(|_| typed) else {
                        self.tentative();
                        continue;
                    };
                    let mut cell = left;
                    cell.text = " ".into();
                    cell.width = 1;
                    cell.hyperlink = None;
                    cell.attributes &= !(INVERSE | PREDICTED);
                    if hidden_cursor {
                        // pi: at the end of its input, the reverse-video cursor steps back over a blank.
                        let at_end = self.shown(&frame, x, y).is_some_and(|u| u.attributes & INVERSE != 0 && blank(&u.text))
                            && self.rest_of_row_blank(&frame, x + 1, y);
                        let Some(mut old) = self.shown(&frame, x, y).filter(|_| at_end) else {
                            self.tentative();
                            continue;
                        };
                        cell.attributes |= INVERSE;
                        let id = self.push(x - 1, y, cell, Kind::Erase, 0, origin, now);
                        old.attributes &= !INVERSE;
                        self.push(x, y, old, Kind::FakeCursor, id, origin, now);
                    } else if self.rest_of_row_blank(&frame, x, y) {
                        self.push(x - 1, y, cell, Kind::Erase, 0, origin, now);
                    } else {
                        // Text to the right (often a fish suggestion) is redrawn by the shell.
                        self.push(x - 1, y, cell, Kind::Move, 0, origin, now);
                    }
                    self.cursor = Some((x - 1, y));
                }
                _ => self.tentative(),
            }
        }
    }

    /// Checks predictions against a new server frame.
    pub fn observe(&mut self, frame: Arc<Frame>, now: Instant) {
        let previous = self.base.replace(frame.clone());
        if let Some((origin, _)) = self.awaiting
            && frame.cursor_at.is_some_and(|at| at != origin)
        {
            // pi has handled Enter, so every earlier key too; leftovers would cover its cleared editor.
            self.awaiting = None;
            self.predictions.clear();
        }
        if self.predictions.is_empty() {
            return;
        }
        if self.mode == Mode::Off || !Self::eligible(&frame)
            || previous.as_ref().is_some_and(|p| p.columns != frame.columns || p.rows != frame.rows)
        {
            self.reset();
            return;
        }
        // Lines scrolled into history move pending guesses up with them.
        if let Some(previous) = &previous
            && frame.scroll_total > previous.scroll_total
        {
            let shift = (frame.scroll_total - previous.scroll_total).min(u64::from(frame.rows)) as u16;
            self.predictions.retain_mut(|p| {
                if p.y < shift {
                    return false;
                }
                p.y -= shift;
                p.cell.y = p.y;
                true
            });
            if let Some((x, y)) = self.cursor {
                self.cursor = y.checked_sub(shift).map(|y| (x, y));
            }
        }
        let Some((cx, cy)) = frame.cursor_at else {
            self.reset();
            return;
        };
        let timeout = self.timeout();
        let mut confirmed = Vec::new();
        let mut failed = false;
        for p in self.predictions.iter().filter(|p| p.kind != Kind::FakeCursor) {
            let real = cell_at(&frame, p.x, p.y).map_or("", |c| c.text.as_str());
            let real = if real.is_empty() { " " } else { real };
            let past = cy > p.y || (cy == p.y && cx > p.x);
            let ok = match p.kind {
                Kind::Char => real == p.cell.text && past,
                Kind::Erase | Kind::Move => cy == p.y && cx <= p.x,
                Kind::FakeCursor => false,
            };
            // The server has moved its cursor beyond this keystroke yet shows something else there,
            // for example a fish abbreviation expanding on space: drop the guess now, not at the timeout.
            let overtaken = p.kind == Kind::Char && past && (cx, cy) != p.origin;
            if ok {
                confirmed.push((p.id, p.epoch, p.kind, now.saturating_duration_since(p.at)));
            } else if overtaken || now.saturating_duration_since(p.at) > timeout {
                failed = true;
                break;
            }
        }
        for (id, epoch, kind, rtt) in confirmed {
            self.confirmed = self.confirmed.max(epoch);
            if kind == Kind::Char {
                self.sample(rtt);
            }
            self.predictions.retain(|p| p.owner != id);
        }
        if failed {
            // A guess was wrong: start over, hidden until the server confirms again.
            self.predictions.clear();
            self.tentative();
        }
        if !self.predictions.iter().any(|p| p.kind != Kind::FakeCursor) {
            self.predictions.clear();
            self.cursor = None;
        }
    }

    /// Drops guesses the server never answered, even when no frames arrive. Returns true if any went.
    pub fn expire(&mut self, now: Instant) -> bool {
        let timeout = self.timeout();
        if self.awaiting.is_some_and(|(_, at)| now.saturating_duration_since(at) > timeout) {
            // pi's cursor never moved (an empty Enter); predict from the current frame again.
            self.awaiting = None;
        }
        if self.predictions.iter().any(|p| now.saturating_duration_since(p.at) > timeout) {
            self.predictions.clear();
            self.tentative();
            return true;
        }
        false
    }

    #[cfg(test)]
    pub fn pending(&self) -> bool {
        !self.predictions.is_empty()
    }

    fn visible(&self) -> bool {
        match self.mode {
            Mode::Off => false,
            Mode::Always => true,
            Mode::Slow => self.showing,
        }
    }

    /// The frame to display: the server frame with confirmed-epoch predictions drawn over it.
    pub fn display(&self, frame: Arc<Frame>) -> Arc<Frame> {
        if !self.visible() {
            return frame;
        }
        let shown: Vec<&Prediction> = self.predictions.iter().filter(|p| p.epoch <= self.confirmed).collect();
        if shown.is_empty() {
            return frame;
        }
        let mut cells = frame.cells.clone();
        for p in shown.iter().filter(|p| p.kind != Kind::Move) {
            let mut cell = p.cell.clone();
            if self.flagging && p.kind != Kind::FakeCursor {
                cell.attributes |= PREDICTED;
            }
            match cells.binary_search_by(|c| (c.y, c.x).cmp(&(p.y, p.x))) {
                Ok(index) => cells[index] = cell,
                Err(index) => cells.insert(index, cell),
            }
        }
        // Move the drawn cursor only if every guess up to it is on screen.
        let cursor = match (frame.cursor, self.cursor) {
            (Some(_), Some(next)) if shown.len() == self.predictions.len() => Some(next),
            (cursor, _) => cursor,
        };
        Arc::new(Frame {
            columns: frame.columns,
            rows: frame.rows,
            cursor,
            cursor_at: frame.cursor_at,
            foreground: frame.foreground,
            background: frame.background,
            cells,
            scroll_total: frame.scroll_total,
            scroll_offset: frame.scroll_offset,
            scroll_visible: frame.scroll_visible,
            input_flags: frame.input_flags,
            images: frame.images.clone(),
            image_generation: frame.image_generation,
            cell_px: frame.cell_px,
            animation_delay_ms: frame.animation_delay_ms,
            image_pixels: frame.image_pixels.clone(),
        })
    }
}

/// Shells at their prompt and pi echo typing like a line editor; anything else is left alone.
pub(crate) fn program(candidates: &serde_json::Value) -> Program {
    const SHELLS: [&str; 9] = ["fish", "zsh", "bash", "sh", "dash", "ash", "ksh", "mksh", "nu"];
    let mut found = Program::Other;
    for candidate in candidates.as_array().into_iter().flatten().filter_map(|c| c.as_str()) {
        let path = candidate.replace('\\', "/").to_lowercase();
        let name = path.rsplit('/').next().unwrap_or("").trim_start_matches('-');
        if name == "pi" || path.contains("/pi-coding-agent/") {
            return Program::Pi;
        }
        if SHELLS.contains(&name) {
            found = Program::Shell;
        }
    }
    found
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const COLS: u16 = 20;
    const ROWS: u16 = 4;

    /// A frame from text rows; `^` marks a reverse-video cell (a TUI's fake cursor).
    fn frame(lines: &[&str], cursor: (u16, u16), visible: bool, scroll: u64) -> Arc<Frame> {
        let mut cells = Vec::new();
        for y in 0..ROWS {
            let line: Vec<char> = lines.get(y as usize).copied().unwrap_or("").chars().collect();
            for x in 0..COLS {
                let c = line.get(x as usize).copied().unwrap_or(' ');
                cells.push(Cell {
                    x,
                    y,
                    text: if c == '^' { " ".into() } else if c == ' ' { String::new() } else { c.to_string() },
                    foreground: [200; 3],
                    background: [0; 3],
                    attributes: if c == '^' { INVERSE } else { 0 },
                    width: 1,
                    hyperlink: None,
                });
            }
        }
        Arc::new(Frame {
            columns: COLS,
            rows: ROWS,
            cursor: visible.then_some(cursor),
            cursor_at: Some(cursor),
            foreground: [200; 3],
            background: [0; 3],
            cells,
            scroll_total: ROWS as u64 + scroll,
            scroll_offset: scroll,
            scroll_visible: ROWS as u64,
            input_flags: 0,
            images: Arc::from([]),
            image_generation: 0,
            cell_px: (0, 0),
            animation_delay_ms: None,
            image_pixels: Arc::from([]),
        })
    }

    fn row(frame: &Frame, y: u16) -> String {
        frame
            .cells
            .iter()
            .filter(|c| c.y == y)
            .map(|c| if c.text.is_empty() { " " } else { c.text.as_str() })
            .collect::<String>()
            .trim_end()
            .to_owned()
    }

    /// A predictor already past the tentative first epoch, on a slow link.
    fn warmed(start: Instant) -> (Predictor, Arc<Frame>) {
        let mut p = Predictor::new();
        p.set_mode(Mode::Always);
        let f = frame(&["$"], (2, 0), true, 0);
        p.observe(f.clone(), start);
        p.input("t", "e", Program::Shell, start);
        let echoed = frame(&["$ e"], (3, 0), true, 0);
        p.observe(echoed.clone(), start + Duration::from_millis(200));
        (p, echoed)
    }

    #[test]
    fn first_input_stays_hidden_until_the_server_confirms_an_echo() {
        let now = Instant::now();
        let mut p = Predictor::new();
        p.set_mode(Mode::Always);
        let f = frame(&["$"], (2, 0), true, 0);
        p.observe(f.clone(), now);
        p.input("t", "e", Program::Shell, now);
        assert_eq!(row(&p.display(f.clone()), 0), "$");
        let echoed = frame(&["$ e"], (3, 0), true, 0);
        p.observe(echoed.clone(), now + Duration::from_millis(200));
        assert!(!p.pending());
        p.input("t", "cho", Program::Shell, now);
        let shown = p.display(echoed);
        assert_eq!(row(&shown, 0), "$ echo");
        assert_eq!(shown.cursor, Some((6, 0)));
    }

    #[test]
    fn echo_confirms_predictions_and_measures_the_round_trip() {
        let now = Instant::now();
        let (mut p, f) = warmed(now);
        assert_eq!(p.srtt(), Some(Duration::from_millis(200)));
        p.input("t", "ls", Program::Shell, now);
        assert!(p.pending());
        // A frame from before the server saw the keys confirms nothing.
        p.observe(f.clone(), now + Duration::from_millis(50));
        assert!(p.pending());
        p.observe(frame(&["$ els"], (5, 0), true, 0), now + Duration::from_millis(300));
        assert!(!p.pending());
        assert_eq!(p.display(frame(&["$ els"], (5, 0), true, 0)).cursor, Some((5, 0)));
    }

    #[test]
    fn slow_mode_shows_predictions_only_on_a_slow_link() {
        let now = Instant::now();
        let mut p = Predictor::new();
        let f = frame(&["$"], (2, 0), true, 0);
        p.observe(f.clone(), now);
        p.input("t", "a", Program::Shell, now);
        p.observe(frame(&["$ a"], (3, 0), true, 0), now + Duration::from_millis(5));
        p.input("t", "b", Program::Shell, now);
        assert_eq!(row(&p.display(frame(&["$ a"], (3, 0), true, 0)), 0), "$ a", "fast link: no overlay");
        let (mut slow, echoed) = warmed(now);
        slow.set_mode(Mode::Slow);
        slow.input("t", "x", Program::Shell, now);
        assert_eq!(row(&slow.display(echoed), 0), "$ ex");
    }

    #[test]
    fn predictions_are_underlined_on_very_slow_links() {
        let now = Instant::now();
        let (mut p, f) = warmed(now);
        p.input("t", "x", Program::Shell, now);
        let shown = p.display(f);
        let cell = cell_at(&shown, 3, 0).unwrap();
        assert_ne!(cell.attributes & PREDICTED, 0);
    }

    #[test]
    fn enter_starts_a_hidden_epoch_so_unechoed_passwords_never_show() {
        let now = Instant::now();
        let (mut p, _) = warmed(now);
        p.input("t", "\r", Program::Shell, now);
        let prompt = frame(&["$ e", "Password:"], (10, 1), true, 0);
        p.observe(prompt.clone(), now + Duration::from_millis(250));
        p.input("t", "hunter2", Program::Shell, now + Duration::from_millis(260));
        assert_eq!(row(&p.display(prompt.clone()), 1), "Password:");
        // The prompt never echoes; the guesses expire without ever being drawn.
        p.observe(prompt.clone(), now + Duration::from_secs(5));
        assert!(!p.pending());
        assert_eq!(row(&p.display(prompt), 1), "Password:");
    }

    #[test]
    fn a_wrong_guess_is_removed_and_later_input_waits_for_confirmation() {
        let now = Instant::now();
        let (mut p, f) = warmed(now);
        p.input("t", "q", Program::Shell, now);
        assert_eq!(row(&p.display(f.clone()), 0), "$ eq");
        // The program does something else with q; after the timeout the guess goes away.
        let other = frame(&["$ e", "quit?"], (5, 1), true, 0);
        p.observe(other.clone(), now + Duration::from_secs(3));
        assert!(!p.pending());
        assert_eq!(row(&p.display(other.clone()), 0), "$ e");
        p.input("t", "y", Program::Shell, now + Duration::from_secs(3));
        assert_eq!(row(&p.display(other), 1), "quit?", "hidden until confirmed");
    }

    #[test]
    fn backspace_at_the_end_of_a_line_is_predicted() {
        let now = Instant::now();
        let (mut p, f) = warmed(now);
        p.input("t", "ab\x7f", Program::Shell, now);
        let shown = p.display(f.clone());
        assert_eq!(row(&shown, 0), "$ ea");
        assert_eq!(shown.cursor, Some((4, 0)));
        p.observe(frame(&["$ ea"], (4, 0), true, 0), now + Duration::from_millis(300));
        assert!(!p.pending());
    }

    #[test]
    fn backspace_before_a_suggestion_moves_only_the_cursor_and_typing_stays_instant() {
        let now = Instant::now();
        let (mut p, _) = warmed(now);
        p.input("t", "c", Program::Shell, now);
        // fish echoes c and shows a suggestion after it.
        let suggestion = frame(&["$ echo hi"], (4, 0), true, 0);
        p.observe(suggestion.clone(), now + Duration::from_millis(200));
        assert!(!p.pending());
        p.input("t", "\x7f", Program::Shell, now);
        let shown = p.display(suggestion.clone());
        assert_eq!(row(&shown, 0), "$ echo hi", "the shell redraws the suggestion itself");
        assert_eq!(shown.cursor, Some((3, 0)));
        p.input("t", "x", Program::Shell, now);
        let shown = p.display(suggestion);
        assert_eq!(row(&shown, 0), "$ exho hi", "typing after a correction is not held back");
        assert_eq!(shown.cursor, Some((4, 0)));
        p.observe(frame(&["$ ex"], (4, 0), true, 0), now + Duration::from_millis(400));
        assert!(!p.pending());
    }

    #[test]
    fn backspace_is_never_guessed_over_the_prompt() {
        let now = Instant::now();
        let (mut p, f) = warmed(now);
        p.input("t", "\x7f", Program::Shell, now);
        assert_eq!(row(&p.display(f.clone()), 0), "$");
        let before = p.predictions.len();
        p.input("t", "\x7f", Program::Shell, now);
        assert_eq!(p.predictions.len(), before, "the second backspace would reach the prompt");
        assert_eq!(row(&p.display(f), 0), "$");
    }

    #[test]
    fn a_guess_the_server_has_moved_past_is_dropped_at_once() {
        let now = Instant::now();
        let (mut p, _) = warmed(now);
        p.input("t", "gs ", Program::Shell, now);
        // A fish abbreviation expands on space; the server cursor ends up far to the right.
        let expanded = frame(&["$ egit status"], (14, 0), true, 0);
        p.observe(expanded.clone(), now + Duration::from_millis(300));
        assert!(!p.pending(), "no waiting for the timeout");
        assert_eq!(row(&p.display(expanded), 0), "$ egit status");
    }

    #[test]
    fn pi_backspace_steps_its_reverse_video_cursor_back() {
        let now = Instant::now();
        let mut p = Predictor::new();
        p.set_mode(Mode::Always);
        p.observe(frame(&["> ^"], (2, 0), false, 0), now);
        p.input("t", "hi", Program::Shell, now);
        let typed = frame(&["> hi^"], (4, 0), false, 0);
        p.observe(typed.clone(), now + Duration::from_millis(200));
        assert!(!p.pending());
        p.input("t", "\x7f", Program::Shell, now);
        let shown = p.display(typed);
        assert_eq!(row(&shown, 0), "> h");
        assert_ne!(cell_at(&shown, 3, 0).unwrap().attributes & INVERSE, 0);
        assert_eq!(cell_at(&shown, 4, 0).unwrap().attributes & INVERSE, 0);
        p.observe(frame(&["> h^"], (3, 0), false, 0), now + Duration::from_millis(400));
        assert!(!p.pending());
    }

    #[test]
    fn full_screen_programs_scrollback_and_other_programs_are_left_alone() {
        let now = Instant::now();
        let (mut p, f) = warmed(now);
        let mut alt = (*f).clone_frame();
        alt.input_flags = ALTERNATE_SCREEN;
        p.observe(Arc::new(alt), now);
        p.input("t", "j", Program::Shell, now);
        assert!(!p.pending());
        let (mut p, f) = warmed(now);
        p.input("t", "j", Program::Other, now);
        assert!(!p.pending());
        assert_eq!(row(&p.display(f), 0), "$ e");
        let (mut p, _) = warmed(now);
        let scrolled = frame(&["$ e"], (3, 0), true, 10);
        let mut scrolled = (*scrolled).clone_frame();
        scrolled.scroll_offset = 2;
        p.observe(Arc::new(scrolled), now);
        p.input("t", "j", Program::Shell, now);
        assert!(!p.pending());
    }

    #[test]
    fn switching_panes_forgets_predictions() {
        let now = Instant::now();
        let (mut p, f) = warmed(now);
        p.input("t", "x", Program::Shell, now);
        p.input("other", "y", Program::Shell, now);
        let shown = p.display(f);
        assert_eq!(row(&shown, 0), "$ e", "a new pane starts hidden again");
    }

    #[test]
    fn scrolling_output_carries_pending_guesses_with_it() {
        let now = Instant::now();
        let mut p = Predictor::new();
        p.set_mode(Mode::Always);
        let f = frame(&["a", "b", "c", "$"], (2, 3), true, 0);
        p.observe(f, now);
        p.input("t", "e", Program::Shell, now);
        p.observe(frame(&["a", "b", "c", "$ e"], (3, 3), true, 0), now + Duration::from_millis(200));
        p.input("t", "x", Program::Shell, now);
        // Output scrolls the screen by one line before the echo arrives.
        let scrolled = frame(&["b", "c", "$ e", "!"], (3, 2), true, 1);
        p.observe(scrolled.clone(), now + Duration::from_millis(250));
        assert_eq!(row(&p.display(scrolled), 2), "$ ex");
    }

    #[test]
    fn pi_moves_its_reverse_video_cursor_along_with_the_text() {
        let now = Instant::now();
        let mut p = Predictor::new();
        p.set_mode(Mode::Always);
        let f = frame(&["> ^"], (2, 0), false, 0);
        p.observe(f, now);
        p.input("t", "h", Program::Shell, now);
        p.observe(frame(&["> h^"], (3, 0), false, 0), now + Duration::from_millis(200));
        assert!(!p.pending());
        p.input("t", "i", Program::Shell, now);
        let shown = p.display(frame(&["> h^"], (3, 0), false, 0));
        assert_eq!(row(&shown, 0), "> hi");
        assert_eq!(cell_at(&shown, 3, 0).unwrap().attributes & INVERSE, 0);
        assert_ne!(cell_at(&shown, 4, 0).unwrap().attributes & INVERSE, 0);
        assert_eq!(shown.cursor, None, "pi keeps the terminal cursor hidden");
        p.observe(frame(&["> hi^"], (4, 0), false, 0), now + Duration::from_millis(400));
        assert!(!p.pending());
    }

    /// pi with "hi" typed and confirmed in its editor on row 1.
    fn pi_with_text(now: Instant) -> Predictor {
        let mut p = Predictor::new();
        p.set_mode(Mode::Always);
        p.observe(frame(&["", "> ^"], (2, 1), false, 0), now);
        p.input("t", "hi", Program::Pi, now);
        p.observe(frame(&["", "> hi^"], (4, 1), false, 0), now + Duration::from_millis(200));
        assert!(!p.pending());
        p
    }

    #[test]
    fn pi_shows_the_next_message_at_once_after_enter() {
        let now = Instant::now();
        let mut p = pi_with_text(now);
        p.input("t", "\r", Program::Pi, now);
        // pi prints the message above and clears its editor.
        let cleared = frame(&["hi", "> ^"], (2, 1), false, 0);
        p.observe(cleared.clone(), now + Duration::from_millis(400));
        p.input("t", "ok", Program::Pi, now + Duration::from_secs(5));
        assert_eq!(row(&p.display(cleared), 1), "> ok", "no wait for an echo after Enter");
    }

    #[test]
    fn unconfirmed_text_does_not_linger_over_pi_cleared_editor() {
        let now = Instant::now();
        let mut p = pi_with_text(now);
        p.input("t", "!\r", Program::Pi, now);
        let cleared = frame(&["hi!", "> ^"], (2, 1), false, 0);
        p.observe(cleared.clone(), now + Duration::from_millis(400));
        assert!(!p.pending());
        assert_eq!(row(&p.display(cleared), 1), ">");
    }

    #[test]
    fn typing_before_pi_redraws_falls_back_to_hidden_guesses() {
        let now = Instant::now();
        let mut p = pi_with_text(now);
        let stale = frame(&["", "> hi^"], (4, 1), false, 0);
        p.input("t", "\ro", Program::Pi, now);
        assert_eq!(row(&p.display(stale), 1), "> hi");
    }

    #[test]
    fn enter_in_a_shell_still_hides_the_next_command() {
        let now = Instant::now();
        let (mut p, _) = warmed(now);
        p.input("t", "\r", Program::Shell, now);
        let prompt = frame(&["$ e", "$"], (2, 1), true, 0);
        p.observe(prompt.clone(), now + Duration::from_millis(300));
        p.input("t", "ls", Program::Shell, now + Duration::from_secs(5));
        assert_eq!(row(&p.display(prompt), 1), "$");
    }

    #[test]
    fn expiry_clears_guesses_when_the_server_goes_quiet() {
        let now = Instant::now();
        let (mut p, _) = warmed(now);
        p.input("t", "x", Program::Shell, now);
        assert!(!p.expire(now + Duration::from_millis(500)));
        assert!(p.expire(now + Duration::from_secs(3)));
        assert!(!p.pending());
    }

    #[test]
    fn shells_and_pi_are_predictable_other_programs_are_not() {
        assert_eq!(program(&json!(["-fish", "fish"])), Program::Shell);
        assert_eq!(program(&json!(["/bin/zsh"])), Program::Shell);
        assert_eq!(program(&json!(["pi", "/x/@earendil-works/pi-coding-agent/dist/cli.js", "bun"])), Program::Pi);
        assert_eq!(program(&json!(["nvim"])), Program::Other);
        assert_eq!(program(&json!(["claude-code", "node"])), Program::Other);
        assert_eq!(program(&json!(null)), Program::Other);
    }

    trait CloneFrame {
        fn clone_frame(&self) -> Frame;
    }
    impl CloneFrame for Frame {
        fn clone_frame(&self) -> Frame {
            Frame {
                columns: self.columns,
                rows: self.rows,
                cursor: self.cursor,
                cursor_at: self.cursor_at,
                foreground: self.foreground,
                background: self.background,
                cells: self.cells.clone(),
                scroll_total: self.scroll_total,
                scroll_offset: self.scroll_offset,
                scroll_visible: self.scroll_visible,
                input_flags: self.input_flags,
                images: self.images.clone(),
                image_generation: self.image_generation,
                cell_px: self.cell_px,
                animation_delay_ms: self.animation_delay_ms,
                image_pixels: self.image_pixels.clone(),
            }
        }
    }
}
