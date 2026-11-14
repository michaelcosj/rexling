package dev.shulhi.rex

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlin.math.ceil

/** Only the visible window shrinks for the IME; the PTY keeps its full-height grid. */
internal fun Modifier.terminalImeViewport(ime: WindowInsets,navigation: WindowInsets,cursorBottom: ()->Float?): Modifier = layout {measurable,constraints->
    if(!constraints.hasBoundedHeight) {
        val child=measurable.measure(constraints)
        layout(child.width,child.height) {child.placeRelative(0,0)}
    } else {
        val covered=(ime.getBottom(this)-navigation.getBottom(this)).coerceAtLeast(0)
        val fullHeight=constraints.maxHeight+covered
        val child=measurable.measure(constraints.copy(minHeight=fullHeight,maxHeight=fullHeight))
        layout(child.width,constraints.maxHeight) {
            // Read cursor state during placement, not composition or measurement.
            child.placeRelative(0,-terminalImePan(cursorBottom(),constraints.maxHeight,covered))
        }
    }
}

internal fun terminalImePan(cursorBottom: Float?,visibleHeight: Int,covered: Int): Int =
    if(cursorBottom==null || !cursorBottom.isFinite() || covered<=0) 0
    else ceil((cursorBottom-visibleHeight).coerceIn(0f,covered.toFloat())).toInt()
