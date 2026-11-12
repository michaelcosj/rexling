package dev.shulhi.rex

import dev.chrisbanes.haze.HazeState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asAndroidPath

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

/** Fan out is staggered; Spin and Expand keep the whole wheel synchronized. */
internal fun neovimWheelEffectProgress(effect: NeovimShortcutEffect,progress: Float,index: Int,slots: Int): Float {
    if(effect==NeovimShortcutEffect.NONE) return 1f
    val value=progress.coerceIn(0f,1f)
    val stagger=if(slots>1) index.toFloat()/(slots-1) else 0f
    val local=if(effect==NeovimShortcutEffect.FAN_OUT) (value*2.2f-stagger*1.2f).coerceIn(0f,1f) else value
    return local*local*(3f-2f*local)
}

private fun wheelTurn(effect: NeovimShortcutEffect,progress: Float)=when(effect) {
    NeovimShortcutEffect.FAN_OUT->-16f*(1-progress)
    NeovimShortcutEffect.SPIN->-120f*(1-progress)
    else->0f
}
private fun wheelScale(effect: NeovimShortcutEffect,progress: Float)=when(effect) {
    NeovimShortcutEffect.SPIN->.72f+.28f*progress
    NeovimShortcutEffect.EXPAND->{val t=1f-progress;.3f+.7f*(1f-t*t*t)}
    else->1f
}

/** Clockwise sectors from the top. The hub and distant releases cancel. */
internal fun neovimWheelSector(point: Offset,diameter: Float,steering: Boolean=false,slots: Int=8): Int {
    val delta=point-Offset(diameter/2,diameter/2)
    val distance=delta.getDistance()
    if(diameter<=0 || distance<=diameter*.23f || distance>diameter*(if(steering) .75f else .5f)) return -1
    val step=360f/slots.coerceIn(1,8)
    return Math.floorMod(floor((atan2(delta.y,delta.x)*180/PI+90+step/2)/step).toInt(),slots.coerceIn(1,8))
}

/** Resting top-left positions that fit the button in the pane and the wheel in the window. */
internal fun neovimWheelAnchorRange(pane: Rect,window: Rect,diameter: Float,edge: Float,button: Float): Rect {
    val half=button/2
    fun range(paneStart: Float,paneEnd: Float,screenStart: Float,screenEnd: Float): Pair<Float,Float> {
        val minimum=paneStart+edge
        val maximum=maxOf(minimum,paneEnd-edge-button)
        val low=maxOf(minimum,screenStart+edge+diameter/2-half)
        val high=minOf(maximum,screenEnd-edge-diameter/2-half)
        // An unusually small pane may have no intersection; keep its button inside.
        return if(low<=high) low to high else minimum to maximum
    }
    val x=range(pane.left,pane.right,window.left,window.right)
    val y=range(pane.top,pane.bottom,window.top,window.bottom)
    return Rect(x.first-pane.left,y.first-pane.top,x.second-pane.left,y.second-pane.top)
}

@Stable internal class NeovimWheelState(items: List<NeovimShortcut>) {
    private val root=NeovimShortcuts.entries(items)
    private val groups=items.filter {it.group.isNotEmpty()}.groupBy {it.group}.mapValues {(_,items)->items.map {NeovimShortcutEntry.Action(it)}}
    var open by mutableStateOf(false);private set
    var group by mutableStateOf("");private set
    var page by mutableIntStateOf(0);private set
    private var rootPage=0
    var highlighted by mutableIntStateOf(-1)
    var bounds by mutableStateOf(Rect.Zero)
    val entries: List<NeovimShortcutEntry> get()=if(group.isEmpty()) root else groups[group].orEmpty()
    val slots: Int get()=entries.size.coerceIn(1,8)
    val pages: Int get()=((entries.size+7)/8).coerceAtLeast(1)
    val visible: List<NeovimShortcutEntry> get() {val items=entries;val start=(page*8).coerceAtMost(items.size);return items.subList(start,minOf(start+8,items.size))}
    // Retain the valid anchor while a closing popup is reversed into an opening one.
    fun show() {if(!open) {group="";page=0;rootPage=0};highlighted=-1;open=true}
    fun close() {open=false;highlighted=-1}
    fun center() {highlighted=-1;if(group.isEmpty()) close() else {group="";page=rootPage.coerceIn(0,pages-1)}}
    fun next(delta: Int) {highlighted=-1;page=Math.floorMod(page+delta,pages)}
    fun highlight(index: Int): Boolean {
        val next=index.takeIf {open && it in visible.indices} ?: -1
        if(next==highlighted) return false
        highlighted=next
        return next>=0
    }
    fun steer(screen: Offset): Boolean {
        return highlight(if(bounds.width<=0) -1 else neovimWheelSector(screen-bounds.topLeft,bounds.width,true,slots))
    }
    fun choose(index: Int): NeovimShortcut? {
        highlighted=-1
        if(!open) return null
        return when(val item=visible.getOrNull(index)) {
            is NeovimShortcutEntry.Action->item.shortcut
            is NeovimShortcutEntry.Group->{rootPage=page;group=item.label;page=0;null}
            null->null
        }
    }
}

@Composable internal fun NeovimRadialShortcutButton(items: List<NeovimShortcut>,target: String,enabled: Boolean,
    onChoose: (NeovimShortcut,String)->Unit,modifier: Modifier,position: Offset,onPosition: (Offset)->Unit,bottomInset: Dp,effect: NeovimShortcutEffect,hapticsEnabled: Boolean,name: String="Neovim",hazeState: HazeState?=null,onExpandedChange: (Boolean)->Unit={}) {
    val notifyBackdrop by rememberUpdatedState(onExpandedChange)
    val localHaze=remember {HazeState()}
    val haze=hazeState ?: localHaze
    val host=LocalView.current
    val invalidator=remember(host) {PopupFrostInvalidator(host)}
    val wheel=remember(target,items,effect) {NeovimWheelState(items)}
    DisposableEffect(wheel) {onDispose {wheel.close();notifyBackdrop(false)}}
    val transition=key(wheel) {updateTransition(wheel.open && enabled,label="Neovim wheel visibility")}
    val animatedReveal=transition.animateFloat(transitionSpec={tween(if(targetState) effect.openMillis else effect.closeMillis,easing=LinearEasing)},label="Radial opening effect") {if(it) 1f else 0f}
    val reveal=if(effect==NeovimShortcutEffect.NONE) remember {mutableStateOf(1f)} else animatedReveal
    val showing=enabled && (if(effect==NeovimShortcutEffect.NONE) wheel.open else transition.currentState || transition.targetState || transition.isRunning)
    LaunchedEffect(showing) {notifyBackdrop(showing)}
    DisposableEffect(invalidator,showing) {if(showing) invalidator.start();onDispose {invalidator.stop()}}
    // The non-focusable popup preserves a held gesture; consume Back as before.
    BackHandler(showing) {}
    val scope=rememberCoroutineScope()
    val config=LocalViewConfiguration.current
    val latestChoose by rememberUpdatedState(onChoose)
    val latestPosition by rememberUpdatedState(position)
    val savePosition by rememberUpdatedState(onPosition)
    var dragged by remember {mutableStateOf<Offset?>(null)}
    var pressed by remember {mutableStateOf(false)}
    var coordinates by remember {mutableStateOf<LayoutCoordinates?>(null)}
    var paneBounds by remember {mutableStateOf(Rect.Zero)}
    var windowBounds by remember {mutableStateOf(Rect.Zero)}
    val hostView=LocalView.current
    val haptics=LocalHapticFeedback.current
    val latestHapticsEnabled by rememberUpdatedState(hapticsEnabled)
    val tick=remember(haptics) {{if(latestHapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)}}
    LaunchedEffect(enabled,target) {if(!enabled) wheel.close();dragged=null}
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned {
        val origin=it.localToScreen(Offset.Zero)
        paneBounds=Rect(origin,Size(it.size.width.toFloat(),it.size.height.toFloat()))
        val visible=android.graphics.Rect()
        hostView.getWindowVisibleDisplayFrame(visible)
        windowBounds=Rect(visible.left.toFloat(),visible.top.toFloat(),visible.right.toFloat(),visible.bottom.toFloat())
    }) {
        val density=LocalDensity.current
        val edge=with(density) {12.dp.toPx()}
        val pane=if(paneBounds.width>0) paneBounds else Rect(Offset.Zero,with(density) {Size(maxWidth.toPx(),maxHeight.toPx())})
        val window=if(windowBounds.width>0) windowBounds else pane
        val width=with(density) {minOf(196.dp,(window.width.toDp()-24.dp).coerceAtLeast(48.dp),(window.height.toDp()-24.dp).coerceAtLeast(48.dp))}
        val range=with(density) {neovimWheelAnchorRange(pane.copy(bottom=(pane.bottom-bottomInset.toPx()).coerceAtLeast(pane.top)),window,width.toPx(),edge,48.dp.toPx())}
        val maxX=range.width;val maxY=range.height
        LaunchedEffect(position,range) {dragged=null}
        fun resting()=Offset(range.left+latestPosition.x*maxX,range.top+latestPosition.y*maxY)
        Box(Modifier.offset {
            val at=dragged ?: resting()
            IntOffset(at.x.roundToInt(),at.y.roundToInt())
        }.size(48.dp).onGloballyPositioned {coordinates=it}.systemGestureExclusion()
            .semantics {
                contentDescription="$name shortcuts";role=Role.Button
                if(enabled) {
                    onClick {if(wheel.open) wheel.center() else wheel.show();true}
                    onLongClick(label="Steer $name shortcuts") {wheel.show();true}
                } else disabled()
            }.pointerInput(enabled,wheel,range) {
                if(!enabled) return@pointerInput
                awaitEachGesture {
                    val down=awaitFirstDown(pass=PointerEventPass.Initial);down.consume()
                    val send=latestChoose
                    val origin=coordinates?.localToScreen(down.position) ?: return@awaitEachGesture
                    val original=resting()
                    var opened=false;var moving=false;var steering=false;var released=false
                    pressed=true
                    val timer=scope.launch {delay(config.longPressTimeoutMillis);opened=true;wheel.show()}
                    try {
                        while(true) {
                            val change=awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull {it.id==down.id} ?: break
                            if(change.isConsumed) break
                            change.consume()
                            val screen=coordinates?.takeIf {it.isAttached}?.localToScreen(change.position) ?: break
                            val delta=screen-origin
                            if(!change.pressed) {
                                released=true;timer.cancel()
                                if(opened) {if(steering) wheel.choose(wheel.highlighted)?.let {send(it,target)}}
                                else if(moving) dragged?.let {savePosition(Offset(if(maxX>0) (it.x-range.left)/maxX else 0f,if(maxY>0) (it.y-range.top)/maxY else 1f))}
                                else wheel.show()
                                break
                            }
                            if(opened) {
                                if(delta.getDistance()>config.touchSlop) steering=true
                                if(steering && wheel.steer(screen)) tick()
                            } else if(moving || delta.getDistance()>config.touchSlop) {
                                if(!moving) wheel.bounds=Rect.Zero
                                timer.cancel();moving=true
                                dragged=Offset((original.x+delta.x).coerceIn(range.left,range.right),(original.y+delta.y).coerceIn(range.top,range.bottom))
                            }
                        }
                    } finally {timer.cancel();pressed=false;wheel.highlighted=-1;if(!released) dragged=null}
                }
            }) {
            NeovimShortcutButtonFace(pressed || wheel.open,enabled,
                Modifier.align(Alignment.Center).drawWithContent {if(!wheel.open) drawContent()})
            if(showing) {
                val provider=remember {object: PopupPositionProvider {
                    override fun calculatePosition(anchorBounds: IntRect,windowSize: IntSize,layoutDirection: LayoutDirection,popupContentSize: IntSize)=
                        IntOffset(anchorBounds.center.x-popupContentSize.width/2,anchorBounds.center.y-popupContentSize.height/2)
                }}
                Popup(popupPositionProvider=provider,onDismissRequest={},
                    properties=PopupProperties(focusable=false,dismissOnClickOutside=false,dismissOnBackPress=false,clippingEnabled=false)) {
                    NeovimShortcutWheel(wheel,width,effect,reveal,tick,name,haze,invalidator) {item->latestChoose(item,target)}
                }
            }
        }
    }
}

@Composable private fun NeovimShortcutWheel(wheel: NeovimWheelState,width: Dp,effect: NeovimShortcutEffect,reveal: State<Float>,onTick: ()->Unit,name: String,haze: HazeState,invalidator: PopupFrostInvalidator,onChoose: (NeovimShortcut)->Unit) {
    val choose by rememberUpdatedState(onChoose)
    val config=LocalViewConfiguration.current
    val pageThreshold=with(LocalDensity.current) {28.dp.toPx()}
    val tick by rememberUpdatedState(onTick)
    val fanDistance=with(LocalDensity.current) {10.dp.toPx()}
    val labelRadius=with(LocalDensity.current) {width.toPx()*.355f}
    val accent=MaterialTheme.colorScheme.primary
    val visible=wheel.visible
    val centerSize=width*.42f
    Box(Modifier.size(width).onGloballyPositioned {wheel.bounds=Rect(it.localToScreen(Offset.Zero),Size(it.size.width.toFloat(),it.size.height.toFloat()))}
        .semantics {contentDescription="$name shortcut menu";stateDescription=if(!wheel.open) "Closing" else if(wheel.group.isEmpty()) "Shortcuts" else "Group ${wheel.group}"}) {
        Box(Modifier.fillMaxSize().systemGestureExclusion().semantics {
            contentDescription="$name shortcut pages"
            if(wheel.open && wheel.pages>1) customActions=listOf(
                CustomAccessibilityAction("Next shortcut page") {wheel.next(1);true},
                CustomAccessibilityAction("Previous shortcut page") {wheel.next(-1);true})
        }.pointerInput(wheel,wheel.open) {
            awaitEachGesture {
                val down=awaitFirstDown(pass=PointerEventPass.Initial);down.consume()
                val accepting=wheel.open
                val diameter=size.width.toFloat()
                val startRadius=(down.position-Offset(diameter/2,diameter/2)).getDistance()
                val hub=startRadius<=diameter*.23f
                var moved=false;var paging=false;var delta=Offset.Zero
                if(accepting && !hub && wheel.highlight(neovimWheelSector(down.position,diameter,slots=wheel.slots))) tick()
                try {
                    while(true) {
                        val change=awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull {it.id==down.id} ?: break
                        if(change.isConsumed) break
                        change.consume();delta=change.position-down.position
                        if(delta.getDistance()>config.touchSlop) moved=true
                        if(accepting && hub && moved) {if(wheel.highlight(neovimWheelSector(change.position,diameter,true,wheel.slots))) tick()}
                        else if(accepting && !hub && moved) {paging=abs(delta.y)>abs(delta.x);wheel.highlighted=-1}
                        if(!change.pressed) {
                            if(!accepting) {if(!moved && startRadius<=diameter*.21f) wheel.show()}
                            else if(hub) {
                                if(moved) wheel.choose(wheel.highlighted)?.let(choose) else if(startRadius<=diameter*.21f) wheel.center()
                            } else if(paging && abs(delta.y)>=pageThreshold) wheel.next(if(delta.y<0) 1 else -1)
                            else if(!moved) {
                                val sector=neovimWheelSector(change.position,diameter,slots=wheel.slots)
                                if(sector>=0) wheel.choose(sector)?.let(choose)
                            }
                            break
                        }
                    }
                } finally {wheel.highlighted=-1}
            }
        }) {
            repeat(wheel.slots) {index->
                val progress=neovimWheelEffectProgress(effect,reveal.value,index,wheel.slots)
                if(progress>0f) {
                    val shape=remember(index,wheel.slots,effect,progress) {WheelFrostSector(index,wheel.slots,effect,progress)}
                    Box(Modifier.fillMaxSize().graphicsLayer {alpha=(progress*3f).coerceIn(0f,1f)}
                        .paneFrost(haze,shape,darkSurface=true,edge=false,invalidator=invalidator))
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val outer=size.minDimension/2-1.dp.toPx()
                val inner=size.minDimension*.23f
                val innerRect=Rect(center,inner)
                val outerRect=Rect(center,outer)
                // Keep the outer silhouette and the neutral hub gap fixed throughout both effects.
                val ringClip=Path().apply {fillType=PathFillType.EvenOdd;addOval(Rect(center,outer+1.dp.toPx()));addOval(innerRect)}
                fun polar(radius: Float,degrees: Float): Offset {val radians=degrees*PI/180;return center+Offset(cos(radians).toFloat(),sin(radians).toFloat())*radius}
                val step=360f/wheel.slots
                clipPath(ringClip) {
                    repeat(wheel.slots) {index->
                        val start=-90f-step/2+index*step
                        val progress=neovimWheelEffectProgress(effect,reveal.value,index,wheel.slots)
                        if(progress<=0f) return@repeat
                        val sweep=step
                        val path=Path().apply {
                            val point=polar(inner,start);moveTo(point.x,point.y)
                            val edge=polar(outer,start);lineTo(edge.x,edge.y)
                            arcTo(outerRect,start,sweep,false)
                            val end=polar(inner,start+step);lineTo(end.x,end.y)
                            arcTo(innerRect,start+step,-sweep,false);close()
                        }
                        val turn=wheelTurn(effect,progress)
                        val zoom=wheelScale(effect,progress)
                        val offset=if(effect==NeovimShortcutEffect.FAN_OUT) polar(-fanDistance*(1-progress),start+step/2)-center else Offset.Zero
                        val opacity=(progress*3f).coerceIn(0f,1f)
                        translate(offset.x,offset.y) {
                            rotate(turn,center) {
                              scale(zoom,zoom,center) {
                                drawPath(path,(if(visible.getOrNull(index) is NeovimShortcutEntry.Group) Color(0xFF30343D) else Color(0xFF25282F)).copy(alpha=(if(visible.getOrNull(index) is NeovimShortcutEntry.Group) .13f else .08f)*opacity))
                                if(index==wheel.highlighted) drawPath(path,accent.copy(alpha=.45f*opacity))
                                drawPath(path,Color.White.copy(alpha=.14f*opacity),style=Stroke(1.dp.toPx()))
                                if(index==wheel.highlighted) drawArc(accent.copy(alpha=opacity),start+1,step-2,false,outerRect.topLeft,outerRect.size,style=Stroke(3.dp.toPx()))
                              }
                            }
                        }
                    }
                }
            }
            visible.forEachIndexed {index,item->
                val rotation=index*360f/wheel.slots
                val angle=(-90+rotation)*PI/180
                val labelWidth=if(wheel.slots==1) 76.dp else (width*(.71f*sin(PI/wheel.slots).toFloat())-4.dp).coerceIn(36.dp,76.dp)
                Box(Modifier.offset(width*(.5f+.355f*cos(angle).toFloat())-labelWidth/2,width*(.5f+.355f*sin(angle).toFloat())-13.dp)
                    .width(labelWidth).height(26.dp).semantics(mergeDescendants=true) {
                        contentDescription=if(item is NeovimShortcutEntry.Group) "Shortcut group ${item.label}" else "$name shortcut ${item.label}"
                        role=Role.Button;selected=wheel.highlighted==index
                        if(wheel.open) onClick {wheel.choose(index)?.let(choose);true} else disabled()
                    },contentAlignment=Alignment.Center) {
                    Text(item.label,Modifier.graphicsLayer {
                        val progress=neovimWheelEffectProgress(effect,reveal.value,index,wheel.slots)
                        val turn=wheelTurn(effect,progress)
                        val zoom=wheelScale(effect,progress)
                        val label=Offset(cos(angle).toFloat(),sin(angle).toFloat())*labelRadius
                        val radians=turn*PI/180
                        val rotated=Offset(label.x*cos(radians).toFloat()-label.y*sin(radians).toFloat(),label.x*sin(radians).toFloat()+label.y*cos(radians).toFloat())*zoom
                        val offset=if(effect==NeovimShortcutEffect.FAN_OUT) Offset(cos(angle).toFloat(),sin(angle).toFloat())*(-fanDistance*(1-progress)) else Offset.Zero
                        rotationZ=(if(rotation>90 && rotation<270) rotation-180 else rotation)+turn
                        translationX=rotated.x+offset.x-label.x
                        translationY=rotated.y+offset.y-label.y
                        scaleX=zoom;scaleY=zoom
                        alpha=((progress-.1f)/.9f).coerceIn(0f,1f)
                    },fontFamily=MonoUi,fontSize=10.sp,lineHeight=12.sp,letterSpacing=0.sp,fontWeight=FontWeight.Medium,
                        maxLines=1,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center,color=Color.White)
                }
            }
            Box(Modifier.align(Alignment.Center).size(centerSize).graphicsLayer {alpha=(reveal.value*6f).coerceIn(0f,1f)}
                .paneFrost(haze,CircleShape,darkSurface=true,edge=false,invalidator=invalidator))
            Canvas(Modifier.fillMaxSize()) {
                val hubRadius=size.minDimension*.21f
                val hubOpacity=(reveal.value*6f).coerceIn(0f,1f)
                drawCircle(Color.White.copy(alpha=.18f*hubOpacity),hubRadius,style=Stroke(1.dp.toPx()))
            }
            Column(Modifier.align(Alignment.Center).size(centerSize).padding(7.dp).graphicsLayer {
                alpha=(reveal.value*6f).coerceIn(0f,1f)
            }.semantics {
                contentDescription=if(!wheel.open) "Reopen $name shortcuts" else if(wheel.group.isEmpty()) "Close $name shortcuts" else "Back to all shortcuts"
                role=Role.Button;stateDescription=wheel.visible.getOrNull(wheel.highlighted)?.label ?: "No command selected"
                onClick {if(wheel.open) wheel.center() else wheel.show();true}
            },horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                val selected=visible.getOrNull(wheel.highlighted)
                if(selected!=null) Text(selected.label,Modifier.clearAndSetSemantics {},fontSize=10.sp,lineHeight=12.sp,maxLines=3,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center,color=Color.White)
                else if(wheel.group.isEmpty()) ChromeSymbol(ChromeIcon.CLOSE,Modifier.size(18.dp),Color.White.copy(alpha=.8f))
                else {ChromeSymbol(ChromeIcon.LEFT,Modifier.size(18.dp),Color.White);Text(wheel.group,fontSize=9.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=Color.White)}
                if(wheel.pages>1) {
                    Text("${wheel.page+1}/${wheel.pages}",fontFamily=MonoUi,fontSize=9.sp,lineHeight=11.sp,color=Color.White.copy(alpha=.75f))
                    Canvas(Modifier.width(30.dp).height(4.dp)) {
                        val count=wheel.pages.coerceAtMost(7)
                        repeat(count) {index->drawCircle(if(index==wheel.page%count) accent else Color.White.copy(alpha=.3f),radius=1.dp.toPx(),center=Offset(size.width*(index+.5f)/count,size.height/2))}
                    }
                }
            }
        }
    }
}

private class WheelFrostSector(val index: Int,val slots: Int,val effect: NeovimShortcutEffect,val progress: Float): Shape {
    override fun createOutline(size: Size,layoutDirection: LayoutDirection,density: Density): Outline {
        val center=Offset(size.width/2,size.height/2)
        val outer=size.minDimension/2-with(density) {1.dp.toPx()};val inner=size.minDimension*.23f
        val step=360f/slots;val start=-90f-step/2+index*step
        fun polar(radius: Float,degrees: Float): Offset {val rad=degrees*PI/180;return center+Offset(cos(rad).toFloat(),sin(rad).toFloat())*radius}
        val sector=Path().apply {
            val point=polar(inner,start);moveTo(point.x,point.y)
            val edge=polar(outer,start);lineTo(edge.x,edge.y)
            arcTo(Rect(center,outer),start,step,false)
            val end=polar(inner,start+step);lineTo(end.x,end.y)
            arcTo(Rect(center,inner),start+step,-step,false);close()
        }
        val offset=if(effect==NeovimShortcutEffect.FAN_OUT) polar(-with(density) {10.dp.toPx()}*(1-progress),start+step/2)-center else Offset.Zero
        sector.asAndroidPath().transform(android.graphics.Matrix().apply {
            setScale(wheelScale(effect,progress),wheelScale(effect,progress),center.x,center.y)
            postRotate(wheelTurn(effect,progress),center.x,center.y);postTranslate(offset.x,offset.y)
        })
        val ring=Path().apply {fillType=PathFillType.EvenOdd;addOval(Rect(center,outer+with(density) {1.dp.toPx()}));addOval(Rect(center,inner))}
        return Outline.Generic(Path.combine(PathOperation.Intersect,sector,ring))
    }
}
