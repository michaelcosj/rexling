package dev.shulhi.rex

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.chrisbanes.haze.HazeState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Read-only map of the same server geometry shown in the pane drawer. */
@Composable internal fun PaneSwitchMinimap(layout: List<PanePlacement>,active: String,modifier: Modifier=Modifier,hazeState: HazeState?=null) {
    val localHaze=remember {HazeState()}
    val haze=hazeState ?: localHaze
    val scheme=MaterialTheme.colorScheme
    Canvas(modifier.paneFrost(haze,RoundedCornerShape(5.dp),edge=false).semantics {contentDescription="Pane switch layout";stateDescription=active}) {
        val inset=2.dp.toPx();val gap=1.dp.toPx()
        val width=(size.width-2*inset).coerceAtLeast(0f);val height=(size.height-2*inset).coerceAtLeast(0f)
        layout.forEach {pane->
            val w=(pane.width*width-gap).coerceAtLeast(0f);val h=(pane.height*height-gap).coerceAtLeast(0f)
            val radius=minOf(2.dp.toPx(),w/2,h/2)
            drawRoundRect(if(pane.id==active) scheme.primary else scheme.onSurface.copy(alpha=if(pane.floating) .28f else .16f),
                Offset(inset+pane.x*width+gap/2,inset+pane.y*height+gap/2),Size(w,h),CornerRadius(radius))
        }
        drawRoundRect(scheme.outlineVariant,cornerRadius=CornerRadius(5.dp.toPx()),style=Stroke(.7.dp.toPx()))
    }
}
