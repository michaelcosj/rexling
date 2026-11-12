@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package dev.shulhi.rex

import androidx.compose.ui.node.invalidateDraw
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** Blur the terminal backdrop, keeping foreground icons and labels crisp. */
@Composable internal fun Modifier.paneFrost(haze: HazeState,shape: Shape,active: Boolean=false,darkSurface: Boolean=false,edge: Boolean=true,tint: Color?=null,invalidator: PopupFrostInvalidator?=null,opacity: Float?=null,blur: androidx.compose.ui.unit.Dp=20.dp): Modifier {
    val scheme=MaterialTheme.colorScheme
    val style=remember(scheme,active,darkSurface,tint,opacity,blur) {
        val surface=if(darkSurface) Color(0xff171a20) else scheme.surface
        val resolvedTint=tint ?: if(active) scheme.primaryContainer else surface
        HazeStyle(backgroundColor=surface,tints=listOf(HazeTint(resolvedTint.copy(alpha=opacity ?: if(darkSurface || tint!=null) .60f else if(active) .46f else .32f))),
            blurRadius=blur,noiseFactor=.02f,fallbackTint=HazeTint(resolvedTint.copy(alpha=.92f)))
    }
    return clip(shape).then(if(invalidator==null) Modifier.hazeEffect(haze,style=style) else Modifier.then(PopupFrostElement(haze,style,invalidator))).then(if(edge) Modifier.border(.5.dp,scheme.onSurface.copy(alpha=.10f),shape) else Modifier)
}

/** A Popup has a separate draw tree: redraw its effects when the terminal host draws. */
internal class PopupFrostInvalidator(private val host: android.view.View) {
    private val effects=mutableSetOf<dev.chrisbanes.haze.HazeEffectNode>()
    private val listener=android.view.ViewTreeObserver.OnPreDrawListener {
        effects.forEach {if(it.isAttached) it.invalidateDraw()};true
    }
    fun start() {host.viewTreeObserver.addOnPreDrawListener(listener)}
    fun stop() {host.viewTreeObserver.takeIf {it.isAlive}?.removeOnPreDrawListener(listener)}
    fun add(effect: dev.chrisbanes.haze.HazeEffectNode) {effects+=effect}
    fun remove(effect: dev.chrisbanes.haze.HazeEffectNode) {effects-=effect}
}

@OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)
private data class PopupFrostElement(val haze: HazeState,val style: HazeStyle,val invalidator: PopupFrostInvalidator): androidx.compose.ui.node.ModifierNodeElement<PopupFrostNode>() {
    override fun create()=PopupFrostNode(haze,style,invalidator)
    override fun update(node: PopupFrostNode) {node.update(haze,style,invalidator)}
    override fun androidx.compose.ui.platform.InspectorInfo.inspectableProperties() {name="livePopupFrost"}
}

@OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)
private class PopupFrostNode(haze: HazeState,style: HazeStyle,var invalidator: PopupFrostInvalidator): androidx.compose.ui.node.DelegatingNode() {
    private val effect=delegate(dev.chrisbanes.haze.HazeEffectNode(haze,style))
    override fun onAttach() {invalidator.add(effect)}
    override fun onDetach() {invalidator.remove(effect)}
    fun update(haze: HazeState,style: HazeStyle,next: PopupFrostInvalidator) {
        if(invalidator!==next) {invalidator.remove(effect);invalidator=next;if(isAttached) invalidator.add(effect)}
        effect.state=haze;effect.style=style
        if(effect.isAttached) effect.onObservedReadsChanged()
    }
}

/** Stronger frosted surfaces share the workspace backdrop across dialog/popup windows. */
@Composable internal fun Modifier.drawerFrost(hazeState: HazeState?,shape: Shape,visible: Boolean=true): Modifier {
    val localHaze=remember {HazeState()}
    val host=androidx.compose.ui.platform.LocalView.current
    val invalidator=remember(host) {PopupFrostInvalidator(host)}
    androidx.compose.runtime.DisposableEffect(invalidator,visible) {
        if(visible) invalidator.start()
        onDispose {invalidator.stop()}
    }
    return testTag("Frosted overlay")
        .paneFrost(hazeState ?: localHaze,shape,edge=false,invalidator=invalidator,opacity=.72f,blur=24.dp)
}

/** Retain the captured backdrop through sheet/menu exit animations. */
@Composable internal fun rememberBackdropCapture(visible: Boolean): Boolean {
    var capture by androidx.compose.runtime.remember {androidx.compose.runtime.mutableStateOf(visible)}
    androidx.compose.runtime.LaunchedEffect(visible) {
        if(!visible) kotlinx.coroutines.delay(220)
        capture=visible
    }
    return visible || capture
}

@Composable internal fun DrawerHandle() {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(16.dp),contentAlignment=androidx.compose.ui.Alignment.Center) {
        androidx.compose.foundation.layout.Box(Modifier.width(28.dp).height(3.dp)
            .background(MaterialTheme.colorScheme.outlineVariant,androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
    }
}
