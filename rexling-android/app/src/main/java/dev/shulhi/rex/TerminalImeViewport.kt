package dev.shulhi.rex

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlin.math.ceil
import kotlin.math.floor

internal data class TerminalKeyboardFocus(val top: Float,val cursorBottom: Float,val contentBottom: Float)

/** Keep the viewport anchored while the cursor is hidden during a repaint or scrollback. */
internal class TerminalImePanState {
    private var lastFocus: TerminalKeyboardFocus?=null
    fun position(focus: TerminalKeyboardFocus?,visibleHeight: Int,covered: Int): Int {
        if(focus!=null) lastFocus=focus
        return terminalImePan(lastFocus,visibleHeight,covered)
    }
}

/** Only the visible window shrinks for the IME; the PTY keeps its full-height grid. */
@Composable
internal fun Modifier.terminalImeViewport(ime: WindowInsets,navigation: WindowInsets,focus: ()->TerminalKeyboardFocus?): Modifier {
    val pan=remember {TerminalImePanState()}
    return layout {measurable,constraints->
        if(!constraints.hasBoundedHeight) {
            val child=measurable.measure(constraints)
            layout(child.width,child.height) {child.placeRelative(0,0)}
        } else {
            val covered=(ime.getBottom(this)-navigation.getBottom(this)).coerceAtLeast(0)
            val fullHeight=constraints.maxHeight+covered
            val child=measurable.measure(constraints.copy(minHeight=fullHeight,maxHeight=fullHeight))
            layout(child.width,constraints.maxHeight) {
                // Read cursor state during placement, not composition or measurement.
                child.placeRelative(0,-pan.position(focus(),constraints.maxHeight,covered))
            }
        }
    }
}

internal fun terminalImePan(focus: TerminalKeyboardFocus?,visibleHeight: Int,covered: Int): Int {
    if(focus==null || covered<=0) return 0
    // Include status/help lines below the prompt when they fit alongside it.
    // A distant status bar must not push a top-of-screen cursor out of view.
    val bottom=if(focus.contentBottom-focus.top<=visibleHeight) focus.contentBottom else focus.cursorBottom
    val limit=minOf(covered,floor(focus.top).toInt().coerceAtLeast(0))
    return ceil((bottom-visibleHeight).coerceAtLeast(0f)).toInt().coerceAtMost(limit)
}
