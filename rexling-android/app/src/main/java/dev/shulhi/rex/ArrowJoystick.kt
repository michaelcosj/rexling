package dev.shulhi.rex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

private val arrowIcons=mapOf("left" to ChromeIcon.LEFT,"down" to ChromeIcon.DOWN,"up" to ChromeIcon.UP,"right" to ChromeIcon.RIGHT)

/** Claims the whole press/slide gesture; a neutral center pauses repeat without releasing. */
@Composable internal fun ArrowJoystickDockKey(enabled: Boolean,modifier: Modifier,onKey: (String)->Unit,readable: Boolean=enabled,inputKey: String="",keyboardVisible: Boolean=true,useJoystick: Boolean=true,onPrompt: ((Int)->Unit)?=null) {
    var active by remember(inputKey) {mutableStateOf(false)}
    var popupOpen by remember(inputKey) {mutableStateOf(false)}
    val available=enabled || onPrompt!=null
    LaunchedEffect(available,useJoystick) {popupOpen=false}
    var direction by remember(inputKey) {mutableStateOf<String?>(null)}
    var displacement by remember(inputKey) {mutableStateOf(Offset.Zero)}
    val latest by rememberUpdatedState(onKey)
    val haptics=LocalHapticFeedback.current
    val configuration=LocalViewConfiguration.current
    val deadZone=maxOf(with(LocalDensity.current) {(if(keyboardVisible) 14.dp else 10.dp).toPx()},configuration.touchSlop)
    val popupProvider=rememberSpeechBubblePositioner()
    val provider=remember {object: PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect,windowSize: IntSize,layoutDirection: LayoutDirection,popupContentSize: IntSize)=
            IntOffset(anchorBounds.center.x-popupContentSize.width/2,anchorBounds.center.y-popupContentSize.height/2)
    }}
    Box(modifier.fillMaxHeight().systemGestureExclusion().clip(RoundedCornerShape(11.dp))
        .background(if(active || popupOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
        .semantics {
            contentDescription="Arrow keys";role=Role.Button;selected=active || popupOpen
            stateDescription=if(popupOpen) "Arrow popup expanded" else if(active) direction?.let {"${it.replaceFirstChar(Char::uppercaseChar)} arrow"} ?: "Neutral" else if(useJoystick && enabled) "Hold and slide or double tap for arrows" else "Tap for arrows"
            if(available) {
                onClick(label="Toggle arrow popup") {popupOpen=!popupOpen;true}
                customActions=(if(enabled) arrowIcons.keys.toList() else emptyList()).map {key->CustomAccessibilityAction("${key.replaceFirstChar(Char::uppercaseChar)} arrow") {latest(key);true}}
            } else disabled()
        }.pointerInput(enabled,available,inputKey,deadZone,useJoystick) {
            if(!available) return@pointerInput
            coroutineScope {
                var lastTap: Long?=null
                awaitEachGesture {
                    // Consuming in Initial prevents ancestor swipe/back handlers taking the drag.
                    val down=awaitFirstDown(pass=PointerEventPass.Initial);down.consume()
                    val send=latest // Freeze the original pane's guarded callback for this gesture.
                    val closingPopup=popupOpen
                    val doubleTap=lastTap?.let {down.uptimeMillis-it in configuration.doubleTapMinTimeMillis..configuration.doubleTapTimeoutMillis}==true
                    lastTap=null
                    active=true;direction=null;displacement=Offset.Zero
                    var repeating: Job?=null
                    var steering=false
                    val center=Offset(size.width/2f,size.height/2f)
                    try {
                        while(true) {
                            val change=awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull {it.id==down.id} ?: break
                            if(change.isConsumed) break
                            change.consume()
                            if(!change.pressed) {
                                if(!steering && change.position.x in 0f..size.width.toFloat() && change.position.y in 0f..size.height.toFloat()) {
                                    if(closingPopup) popupOpen=false
                                    else if(!useJoystick || !enabled) popupOpen=true
                                    else if(change.uptimeMillis-down.uptimeMillis<configuration.longPressTimeoutMillis) {
                                        if(doubleTap) popupOpen=!popupOpen else lastTap=change.uptimeMillis
                                    }
                                }
                                break
                            }
                            // Use the same center as the visible overlay. An off-center
                            // press stays neutral until the finger deliberately moves.
                            if(!steering && (change.position-down.position).getDistance()<configuration.touchSlop) continue
                            steering=true
                            if(!useJoystick || !enabled) continue
                            popupOpen=false
                            val delta=change.position-center
                            displacement=delta
                            val next=joystickDirection(delta,deadZone,direction)
                            if(next!=direction) {
                                repeating?.cancel();direction=next
                                if(next!=null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    send(next)
                                    repeating=launch {
                                        delay(500)
                                        while(isActive) {send(next);delay(160)}
                                    }
                                }
                            }
                        }
                    } finally {repeating?.cancel();direction=null;displacement=Offset.Zero;active=false}
                }
            }
        },contentAlignment=Alignment.Center) {
        ChromeSymbol(ChromeIcon.ARROWS,Modifier.size(20.dp),
            (if(active || popupOpen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface).copy(alpha=if(readable) 1f else .38f))
        if(active && enabled && useJoystick && !popupOpen) Popup(popupPositionProvider=provider,properties=PopupProperties(focusable=false,dismissOnClickOutside=false,dismissOnBackPress=false,clippingEnabled=false)) {
            ArrowJoystickFace(direction,displacement,if(keyboardVisible) 88.dp else 52.dp,if(keyboardVisible) 14.dp else 12.dp)
        }
        if(popupOpen && available) Popup(popupPositionProvider=popupProvider,onDismissRequest={popupOpen=false},
            properties=PopupProperties(focusable=false,dismissOnClickOutside=false,dismissOnBackPress=false)) {
            SpeechBubble(popupProvider,Modifier.width(200.dp).semantics {contentDescription="Arrow key popup"},shadowElevation=0.dp) {
                Column(Modifier.padding(8.dp)) {
                    ArrowKeyPad(enabled,Modifier.fillMaxWidth().height(100.dp),latest,readable,inputKey)
                    if(onPrompt!=null) Row(Modifier.fillMaxWidth().padding(top=4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        listOf(-1 to "Prev command",1 to "Next command").forEach {(direction,label)->
                            Box(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(8.dp))
                                .clickable(role=Role.Button) {onPrompt(direction)},contentAlignment=Alignment.Center) {
                                Text(label,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun joystickDirection(delta: Offset,deadZone: Float,previous: String?): String? {
    if(delta.getDistance()<deadZone) return null
    val x=abs(delta.x);val y=abs(delta.y)
    // A little hysteresis avoids alternating directions near a diagonal.
    if((previous=="left" || previous=="right") && x>=y*.85f) return if(delta.x<0) "left" else "right"
    if((previous=="up" || previous=="down") && y>=x*.85f) return if(delta.y<0) "up" else "down"
    return if(x>y) {if(delta.x<0) "left" else "right"} else {if(delta.y<0) "up" else "down"}
}

@Composable private fun ArrowJoystickFace(direction: String?,displacement: Offset,diameter: Dp,neutralRadius: Dp) {
    val accent=MaterialTheme.colorScheme.primary
    Box(Modifier.size(diameter).semantics {contentDescription="Arrow joystick";stateDescription=direction ?: "Neutral"}) {
        Canvas(Modifier.fillMaxSize()) {
            val r=size.minDimension/2
            drawCircle(Color(0xFF303846),r)
            direction?.let {
                val angle=when(it) {"right"->0f;"down"->90f;"left"->180f;else->270f}
                drawArc(accent,startAngle=angle-45f,sweepAngle=90f,useCenter=true)
            }
            drawCircle(Color.White.copy(alpha=.4f),r-1.dp.toPx(),style=Stroke(1.dp.toPx()))
            drawCircle(Color(0xFF171C25),minOf(neutralRadius.toPx(),r*.36f))
            val distance=displacement.getDistance()
            val knob=if(distance>0) displacement*(minOf(r*.16f,distance*.18f)/distance) else Offset.Zero
            drawCircle(Color.White.copy(alpha=.55f),minOf(4.dp.toPx(),r*.12f),center+knob)
        }

    }
}
