package dev.shulhi.rex

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class ChromeIcon { SPLIT, SEARCH, CHEVRON, SESSIONS, COPY, MORE, ADD, KEYBOARD, HIDE_KEYBOARD, LEFT, RIGHT, UP, DOWN, TAB, CLOSE, ESCAPE, CONTROL, ALT, SHIFT, POINTER, EYE, BACKSPACE, ARROWS, ENTER, SETTINGS, PASTE, IMAGE, SERVER, REFRESH, SUN, MOON, EDIT, UNPLUG, DELETE, FLOAT }

@Composable internal fun ChromeSymbol(icon: ChromeIcon, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier.size(19.dp)) {
        val u = size.minDimension / 24f
        val width = 1.65f * u
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1*u,y1*u), Offset(x2*u,y2*u), width, StrokeCap.Round)
        fun box(x: Float, y: Float, w: Float, h: Float, radius: Float = 2f) = drawRoundRect(color,
            Offset(x*u,y*u), Size(w*u,h*u), CornerRadius(radius*u), style = Stroke(width))
        fun arrow(dx: Float, dy: Float) {
            val x=12f; val y=12f
            line(x-dx*6,y-dy*6,x+dx*6,y+dy*6)
            line(x+dx*6,y+dy*6,x+dx*2-dy*4,y+dy*2+dx*4)
            line(x+dx*6,y+dy*6,x+dx*2+dy*4,y+dy*2-dx*4)
        }
        when(icon) {
            ChromeIcon.FLOAT -> {box(3f,7f,14f,14f);box(10f,3f,11f,10f)}
            ChromeIcon.SPLIT -> { box(3f,4f,18f,16f);line(12f,4f,12f,20f) }
            ChromeIcon.SEARCH -> { drawCircle(color,6f*u,Offset(10f*u,10f*u),style=Stroke(width));line(15f,15f,21f,21f) }
            ChromeIcon.CHEVRON -> {line(7f,10f,12f,15f);line(12f,15f,17f,10f)}
            ChromeIcon.SESSIONS -> { box(4f,8f,13f,12f); line(8f,4f,20f,4f); line(20f,4f,20f,16f); line(7f,12f,11f,12f); line(7f,16f,13f,16f) }
            ChromeIcon.COPY -> { box(8f,8f,12f,12f); line(15f,4f,4f,4f); line(4f,4f,4f,15f) }
            ChromeIcon.PASTE -> {box(6f,5f,14f,16f);box(9f,3f,6f,4f,1f);line(10f,12f,16f,12f);line(10f,16f,15f,16f)}
            ChromeIcon.EDIT -> {
                val pencil=Path().apply {moveTo(4f*u,16f*u);lineTo(16f*u,4f*u);lineTo(20f*u,8f*u);lineTo(8f*u,20f*u);lineTo(4f*u,20f*u);close()}
                drawPath(pencil,color,style=Stroke(width));line(14f,6f,18f,10f)
            }
            ChromeIcon.UNPLUG -> {box(7f,7f,10f,7f);line(9f,3f,9f,7f);line(15f,3f,15f,7f);line(12f,14f,12f,20f);line(12f,20f,18f,20f);line(3f,3f,21f,21f)}
            ChromeIcon.DELETE -> {box(7f,8f,10f,13f,1f);line(5f,6f,19f,6f);box(9f,3f,6f,3f,1f);line(10f,11f,10f,17f);line(14f,11f,14f,17f)}
            ChromeIcon.IMAGE -> {box(3f,4f,18f,16f);drawCircle(color,1.4f*u,Offset(16f*u,8f*u));line(3f,17f,8f,12f);line(8f,12f,12f,16f);line(12f,16f,15f,13f);line(15f,13f,21f,18f)}
            ChromeIcon.SERVER -> {
                for(y in listOf(4f,14f)) {box(4f,y,16f,6f,1.5f);drawCircle(color,.8f*u,Offset(7f*u,(y+3f)*u));line(11f,y+3f,17f,y+3f)}
            }
            ChromeIcon.REFRESH -> {drawArc(color,50f,270f,false,Offset(4f*u,4f*u),Size(16f*u,16f*u),style=Stroke(width));line(18f,7f,13f,7f);line(18f,7f,18f,2f)}
            ChromeIcon.SETTINGS -> {
                val gear=Path().apply {
                    repeat(32) {i->
                        val angle=-PI/2+i*PI/16
                        val r=if(i%4==0 || i%4==3) 9.5f else 7.8f
                        val x=(12f+cos(angle).toFloat()*r)*u;val y=(12f+sin(angle).toFloat()*r)*u
                        if(i==0) moveTo(x,y) else lineTo(x,y)
                    };close()
                }
                drawPath(gear,color,style=Stroke(width));drawCircle(color,3f*u,Offset(12f*u,12f*u),style=Stroke(width))
            }
            ChromeIcon.SUN -> {
                drawCircle(color,4f*u,Offset(12f*u,12f*u),style=Stroke(width))
                repeat(8) {i->val angle=i*PI/4;val x=cos(angle).toFloat();val y=sin(angle).toFloat();line(12f+x*7.5f,12f+y*7.5f,12f+x*10f,12f+y*10f)}
            }
            ChromeIcon.MOON -> {
                val moon=Path().apply {
                    moveTo(20.5f*u,13.8f*u)
                    cubicTo(18.8f*u,20.8f*u,10.5f*u,23f*u,5.6f*u,17.9f*u)
                    cubicTo(.7f*u,12.8f*u,3.2f*u,4.6f*u,10.3f*u,3f*u)
                    cubicTo(7.1f*u,9.3f*u,13.7f*u,16.4f*u,20.5f*u,13.8f*u);close()
                }
                drawPath(moon,color,style=Stroke(width))
            }
            ChromeIcon.MORE -> { for(y in listOf(5f,12f,19f)) drawCircle(color,1.4f*u,Offset(12f*u,y*u)) }
            ChromeIcon.CLOSE -> {line(6f,6f,18f,18f);line(6f,18f,18f,6f)}
            ChromeIcon.ADD -> { line(5f,12f,19f,12f); line(12f,5f,12f,19f) }
            ChromeIcon.LEFT -> arrow(-1f,0f)
            ChromeIcon.RIGHT -> arrow(1f,0f)
            ChromeIcon.UP -> arrow(0f,-1f)
            ChromeIcon.DOWN -> arrow(0f,1f)
            ChromeIcon.ARROWS -> {
                val center=Offset(12f*u,12f*u)
                drawCircle(color,10.5f*u,center,style=Stroke(width))
                drawCircle(color,4.25f*u,center)
                val directions=Path().apply {
                    moveTo(12f*u,3.2f*u);lineTo(10f*u,6.2f*u);lineTo(14f*u,6.2f*u);close()
                    moveTo(20.8f*u,12f*u);lineTo(17.8f*u,10f*u);lineTo(17.8f*u,14f*u);close()
                    moveTo(12f*u,20.8f*u);lineTo(10f*u,17.8f*u);lineTo(14f*u,17.8f*u);close()
                    moveTo(3.2f*u,12f*u);lineTo(6.2f*u,10f*u);lineTo(6.2f*u,14f*u);close()
                }
                drawPath(directions,color)
            }
            ChromeIcon.ENTER -> {line(20f,5f,20f,12f);line(20f,12f,4f,12f);line(4f,12f,9f,7f);line(4f,12f,9f,17f)}
            ChromeIcon.ESCAPE -> {drawArc(color,-90f,270f,false,Offset(4f*u,4f*u),Size(16f*u,16f*u),style=Stroke(width));line(4f,4f,12f,12f);line(4f,4f,4f,9f);line(4f,4f,9f,4f)}
            ChromeIcon.EYE -> {
                val eye=Path().apply {
                    moveTo(2f*u,12f*u)
                    cubicTo(7f*u,4f*u,17f*u,4f*u,22f*u,12f*u)
                    cubicTo(17f*u,20f*u,7f*u,20f*u,2f*u,12f*u)
                    close()
                }
                drawPath(eye,color,style=Stroke(width))
                drawCircle(color,3f*u,Offset(12f*u,12f*u),style=Stroke(width))
            }
            ChromeIcon.BACKSPACE -> {line(9f,5f,21f,5f);line(21f,5f,21f,19f);line(21f,19f,9f,19f);line(9f,19f,2f,12f);line(2f,12f,9f,5f);line(12f,9f,17f,15f);line(17f,9f,12f,15f)}
            ChromeIcon.POINTER -> {line(5f,3f,5f,20f);line(5f,20f,10f,15f);line(10f,15f,14f,22f);line(14f,22f,17f,20f);line(17f,20f,13f,13f);line(13f,13f,20f,13f);line(20f,13f,5f,3f)}
            ChromeIcon.CONTROL -> {line(6f,15f,12f,9f);line(12f,9f,18f,15f)}
            ChromeIcon.ALT -> {line(3f,6f,8f,6f);line(8f,6f,15f,18f);line(15f,18f,21f,18f);line(14f,6f,21f,6f)}
            ChromeIcon.SHIFT -> {line(12f,3f,3f,12f);line(3f,12f,8f,12f);line(8f,12f,8f,21f);line(8f,21f,16f,21f);line(16f,21f,16f,12f);line(16f,12f,21f,12f);line(21f,12f,12f,3f)}
            ChromeIcon.TAB -> { line(4f,12f,17f,12f); line(12f,7f,17f,12f); line(12f,17f,17f,12f); line(20f,6f,20f,18f) }
            ChromeIcon.KEYBOARD, ChromeIcon.HIDE_KEYBOARD -> {
                box(2f,4f,20f,14f)
                for(x in listOf(6f,10f,14f,18f)) { line(x,8f,x+.2f,8f); line(x,11f,x+.2f,11f) }
                line(8f,14f,16f,14f)
                if(icon==ChromeIcon.HIDE_KEYBOARD) { line(9f,21f,12f,23f); line(12f,23f,15f,21f) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun ChromeButton(icon: ChromeIcon, description: String, enabled: Boolean = true,
    modifier: Modifier = Modifier, readable: Boolean = enabled, onLongClick: (() -> Unit)?=null, onClick: () -> Unit) {
    Box(modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
        .combinedClickable(enabled=enabled,role=Role.Button,onLongClickLabel=if(onLongClick!=null) "Paste clipboard" else null,onLongClick=onLongClick,onClick=onClick)
        .semantics { contentDescription=description }, contentAlignment=Alignment.Center) {
        ChromeSymbol(icon, color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(readable) .85f else .3f))
    }
}

internal val MonoUi = FontFamily(Font(R.font.jetbrains_mono_regular),Font(R.font.jetbrains_mono_bold,FontWeight.Bold))

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun SessionHeader(sessions: List<SessionItem>, selectedId: String,
    connected: Boolean, enabled: Boolean,
    programs: List<ProgramIdentity>, paneIndex: Int, directory: String,
    onSessions: ()->Unit, onSelect: (String)->Unit, onLast: ()->Unit, title: String="", working: Boolean=false, expanded: Boolean=false, serverIcons: Map<String,ServerIcon> = emptyMap(), menu: @Composable ()->Unit) {
    val program=programs.getOrNull(paneIndex) ?: ProgramIdentity.Terminal
    val titleHeight=with(LocalDensity.current) {16.sp.toDp()}
    val directoryHeight=with(LocalDensity.current) {12.sp.toDp()}
    val sessionHeight=maxOf(17.dp,directoryHeight)
    val lineStyle=androidx.compose.ui.text.TextStyle(
        platformStyle=androidx.compose.ui.text.PlatformTextStyle(includeFontPadding=false),
        lineHeightStyle=LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None))
    val titleBaseline=rememberTextMeasurer().measure("Ag",
        style=androidx.compose.material3.LocalTextStyle.current.merge(lineStyle).copy(fontWeight=FontWeight.Bold,fontSize=13.sp,lineHeight=16.sp,letterSpacing=0.sp),
        maxLines=1).firstBaseline.roundToInt()
    val selectedIndex=sessions.indexOfFirst { it.id==selectedId }.coerceAtLeast(0)
    val looping=sessions.size>1
    val sessionIds=sessions.map {it.id}
    val pager=key(sessionIds) {rememberPagerState(initialPage=selectedIndex+if(looping) 1 else 0,
        pageCount={if(looping) sessions.size+2 else 1})}
    fun logicalPage(page: Int)=if(looping) Math.floorMod(page-1,sessions.size) else 0
    val latestSelected by rememberUpdatedState(selectedId)
    val latestSelect by rememberUpdatedState(onSelect)
    val iconSwipeDistance=with(LocalDensity.current) {18.dp.toPx()}
    val iconTravel=with(LocalDensity.current) {12.dp.toPx()}
    val iconScope=rememberCoroutineScope()
    val iconSettle=remember {Animatable(0f)}
    var iconDragging by remember {mutableStateOf(false)}
    var iconDragY by remember {mutableFloatStateOf(0f)}
    var iconAnimation by remember {mutableStateOf<Job?>(null)}
    fun settleIcon() {
        val start=iconDragY
        iconAnimation?.cancel()
        iconAnimation=iconScope.launch {
            iconSettle.snapTo(start);iconDragging=false
            iconSettle.animateTo(0f,spring(dampingRatio=.82f,stiffness=500f))
        }
    }
    LaunchedEffect(enabled) {if(!enabled) {iconAnimation?.cancel();iconDragging=false;iconDragY=0f;iconSettle.snapTo(0f)}}
    fun selectNeighbour(step: Int): Boolean {
        val current=sessions.indexOfFirst {it.id==latestSelected}
        if(!enabled || sessions.size<2 || current<0) return false
        latestSelect(sessions[Math.floorMod(current+step,sessions.size)].id)
        return true
    }
    LaunchedEffect(selectedId,sessionIds) {
        if(sessions.isNotEmpty() && !pager.isScrollInProgress) {
            val destination=selectedIndex+if(looping) 1 else 0
            if(looping && (pager.currentPage==0 || pager.currentPage==sessions.size+1)) pager.scrollToPage(destination)
            else if(logicalPage(pager.currentPage)!=selectedIndex) pager.animateScrollToPage(destination)
        }
    }
    LaunchedEffect(pager,sessions,enabled) {
        // Sentinel pages let either edge wrap naturally. Rebase invisibly after
        // settling; distinct logical indices prevent a duplicate navigation.
        snapshotFlow {pager.settledPage}.map {logicalPage(it)}.distinctUntilChanged().drop(1).collect {index->
            if(looping && (pager.settledPage==0 || pager.settledPage==sessions.size+1)) pager.scrollToPage(index+1)
            if(enabled && sessions.getOrNull(index)?.id!=latestSelected) sessions.getOrNull(index)?.let {latestSelect(it.id)}
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min=50.dp).padding(horizontal=9.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
        if(!expanded) {
            Box(Modifier.height(45.dp).clipToBounds().semantics(mergeDescendants=true) {
                contentDescription="Session program icon";stateDescription=if(working) "Working" else "Idle"
                if(enabled && looping) customActions=listOf(CustomAccessibilityAction("Next session") {selectNeighbour(1)},CustomAccessibilityAction("Previous session") {selectNeighbour(-1)})
            }.pointerInput(enabled,sessionIds,selectedId) {
                if(enabled && looping) {
                    var distance=Offset.Zero
                    detectDragGestures(onDragStart={distance=Offset.Zero;iconAnimation?.cancel();iconDragY=iconSettle.value;iconDragging=true},
                        onDragCancel={distance=Offset.Zero;settleIcon()},
                        onDrag={change,amount->change.consume();distance+=amount;iconDragY=distance.y.coerceIn(-iconTravel,iconTravel)},
                        onDragEnd={settleIcon();if(abs(distance.y)>=iconSwipeDistance && abs(distance.y)>abs(distance.x)*1.5f) selectNeighbour(if(distance.y<0) 1 else -1)})
                }
            },contentAlignment=Alignment.Center) {
                Box(Modifier.graphicsLayer {
                    val y=if(iconDragging) iconDragY else iconSettle.value
                    val progress=(abs(y)/iconTravel).coerceIn(0f,1f)
                    translationY=y;scaleX=1f-.06f*progress;scaleY=scaleX;alpha=1f-.25f*progress
                }) {ProgramStack(programs,paneIndex,framed=true,fixedSlot=true)}
            }
            Spacer(Modifier.width(7.dp))
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.Center) {
            if(!expanded) {
                Text(title.ifBlank {program.title},fontWeight=FontWeight.Bold,fontSize=13.sp,lineHeight=16.sp,style=lineStyle,letterSpacing=0.sp,maxLines=1,
                    overflow=TextOverflow.Ellipsis,modifier=Modifier.fillMaxWidth().height(titleHeight).layout {measurable,constraints->
                        // Fallback/emoji metrics must not move the ordinary title text.
                        val text=measurable.measure(constraints.copy(minHeight=0,maxHeight=Constraints.Infinity))
                        layout(text.width,constraints.maxHeight,mapOf(FirstBaseline to titleBaseline)) {
                            text.placeRelative(0,titleBaseline-text[FirstBaseline])
                        }
                    })
                Text(directory,Modifier.fillMaxWidth().height(directoryHeight),
                    fontFamily=MonoUi,fontSize=9.sp,lineHeight=12.sp,style=lineStyle,letterSpacing=0.sp,maxLines=1,overflow=TextOverflow.Ellipsis,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(if(expanded) 42.dp else sessionHeight)) {
                val pageWidth=if(expanded) 160.dp else maxWidth.coerceAtLeast(40.dp)
                val pagePadding=if(expanded) ((maxWidth-pageWidth)/2).coerceAtLeast(0.dp) else 0.dp
                Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                    HorizontalPager(pager,Modifier.fillMaxWidth().height(if(expanded) 32.dp else sessionHeight).semantics {contentDescription="Session strip"},userScrollEnabled=enabled && sessions.size>1,
                        pageSize=PageSize.Fixed(pageWidth),contentPadding=PaddingValues(horizontal=pagePadding),pageSpacing=6.dp,key={"$it:${sessions.getOrNull(logicalPage(it))?.id.orEmpty()}"}) { page ->
                        val item=sessions.getOrNull(logicalPage(page))
                        val active=item?.id==selectedId
                        Row(Modifier.requiredWidth(pageWidth).fillMaxHeight().clip(RoundedCornerShape(19.dp))
                            .border(1.dp,if(expanded && active) MaterialTheme.colorScheme.primary.copy(alpha=.5f) else Color.Transparent,RoundedCornerShape(19.dp))
                            .padding(horizontal=if(expanded) 9.dp else 0.dp).graphicsLayer { alpha=if(active || sessions.isEmpty()) 1f else .38f }
                            .combinedClickable(onClick={if(active || item==null) onSessions() else onSelect(item.id)},onLongClick=onLast)
                            .semantics { contentDescription=if(active || item==null) "Switch session" else "Session ${item.label}" },
                            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(if(expanded) 6.dp else 4.dp)) {
                            Box(Modifier.size(if(expanded) 7.dp else 5.dp).background(if(connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,RoundedCornerShape(4.dp)))
                            serverIcons[item?.id]?.let {ServerGlyph(it,Modifier.size(if(expanded) 13.dp else 11.dp))}
                            Text(item?.label ?: if(connected) "Sessions" else "Connect",fontFamily=MonoUi,fontWeight=if(expanded) FontWeight.Bold else FontWeight.Normal,
                                fontSize=if(expanded) 12.sp else 9.sp,lineHeight=if(expanded) 16.sp else 12.sp,
                                style=androidx.compose.ui.text.TextStyle(platformStyle=androidx.compose.ui.text.PlatformTextStyle(includeFontPadding=false)),letterSpacing=0.sp,maxLines=1,overflow=TextOverflow.Ellipsis,
                                color=if(expanded) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier=Modifier.widthIn(max=(pageWidth-(if(expanded) 50.dp else 24.dp)-(if(serverIcons[item?.id]!=null) 15.dp else 0.dp)).coerceAtLeast(10.dp)))
                            ChromeSymbol(ChromeIcon.CHEVRON,Modifier.size(if(expanded) 13.dp else 11.dp),MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if(expanded && sessions.size>1) PositionDots(logicalPage(pager.currentPage),sessions.size,Modifier.height(5.dp))
                }
            }
        }
        if(!expanded) {
            menu()
        }
    }
}

/** Call only for visible busy programs; idle chrome owns no animation clock. */
@Composable internal fun rememberWorkingPhase(working: Boolean): State<Float> {
    if(!working) return remember {mutableFloatStateOf(0f)}
    val transition=androidx.compose.animation.core.rememberInfiniteTransition(label="Program working")
    return transition.animateFloat(0f,1f,animationSpec=androidx.compose.animation.core.infiniteRepeatable(
        animation=androidx.compose.animation.core.tween(1200,easing=androidx.compose.animation.core.LinearEasing)),label="Working highlight")
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable internal fun TerminalControlButton(controlled: Boolean,enabled: Boolean,onClick: ()->Unit) {
    val label=if(controlled) "Release terminal control" else "Take terminal control"
    androidx.compose.material3.TooltipBox(
        positionProvider=androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip={PlainTooltip {Text(if(controlled) "Release control" else "Take control")}},
        state=androidx.compose.material3.rememberTooltipState()) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp))
            .border(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=if(enabled) .45f else .15f),RoundedCornerShape(16.dp))
            .background(if(controlled) MaterialTheme.colorScheme.primary else Color.Transparent)
            .semantics {contentDescription=label;selected=controlled}
            .clickable(enabled=enabled,role=Role.Button,onClick=onClick),contentAlignment=Alignment.Center) {
            ChromeSymbol(ChromeIcon.POINTER,Modifier.size(16.dp),
                (if(controlled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary).copy(alpha=if(enabled) 1f else .4f))
        }
    }
}

@Composable internal fun PositionDots(index: Int,count: Int,modifier: Modifier=Modifier) {
    val first=(index-3).coerceIn(0,(count-7).coerceAtLeast(0))
    Row(modifier,horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
        repeat(minOf(count,7)) { offset ->
            val active=first+offset==index
            Box(Modifier.size(if(active) 15.dp else 5.dp,5.dp).background(
                if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.35f),RoundedCornerShape(3.dp)))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun TerminalTabs(windows: List<WindowItem>,selectedId: String,enabled: Boolean,
    onSelect: (String)->Unit,panePrograms: (WindowItem)->List<ProgramIdentity>,selectedPane: (WindowItem)->Int,paneTitle: (WindowItem,Int)->String={_,_->""},paneWorking: (WindowItem)->Boolean={false},onClose: (WindowItem)->Unit={},onMove: (String,String?)->Boolean={_,_->false},reorderKey: String="",animateWorking: Boolean=true,contentReady: Boolean=true,actions: @Composable ()->Unit) {
    var revealed by remember(reorderKey) {mutableStateOf(false)}
    LaunchedEffect(reorderKey,contentReady) {if(contentReady) revealed=true}
    if(!contentReady && !revealed) {
        // Reserve the dock's normal bounds so first-load metadata cannot resize the terminal.
        Row(Modifier.fillMaxWidth().height(45.dp).padding(start=11.dp,end=6.dp),verticalAlignment=Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f).fillMaxHeight())
            actions()
        }
        return
    }
    val list=rememberLazyListState()
    val drag=remember(reorderKey) {TabDrag()}
    var menuId by remember(reorderKey) {mutableStateOf<String?>(null)}
    val ids=windows.map {it.id}
    val currentWindows by rememberUpdatedState(windows)
    val move by rememberUpdatedState(onMove)
    val haptic=LocalHapticFeedback.current
    val sign=if(LocalLayoutDirection.current==LayoutDirection.Rtl) -1f else 1f
    val edge=with(LocalDensity.current) {36.dp.toPx()}
    val speed=with(LocalDensity.current) {420.dp.toPx()}
    val index=windows.indexOfFirst {it.id==selectedId}
    val measurer=rememberTextMeasurer()
    val textDensity=LocalDensity.current
    val labels=windows.mapIndexed {i,w->
        val pane=selectedPane(w)
        val program=panePrograms(w).getOrNull(pane) ?: ProgramIdentity.Loading
        program.tabTitle(w.label,w.blocks.getOrNull(pane)?.label.orEmpty(),i+1,paneTitle(w,pane))
    }
    val contentWidths=remember(labels,index,measurer,textDensity) {
        val chrome=with(textDensity) {36.dp.roundToPx()+2*6.dp.roundToPx()+2.dp.roundToPx()}
        labels.mapIndexed {i,title->
            val style=androidx.compose.ui.text.TextStyle(fontSize=12.sp,lineHeight=18.sp,letterSpacing=0.sp,
                fontWeight=if(i==index) FontWeight.Bold else FontWeight.Normal,
                platformStyle=androidx.compose.ui.text.PlatformTextStyle(includeFontPadding=false))
            (chrome+measurer.measure(title,style,maxLines=1,softWrap=false).size.width)/textDensity.density
        }
    }
    // Remote changes cancel a local gesture; animated titles do not.
    LaunchedEffect(ids,enabled) {drag.cancel();menuId=null}
    LaunchedEffect(drag.id) {
        var previous=withFrameNanos {it}
        while(drag.id!=null) {
            val now=withFrameNanos {it}
            val dt=((now-previous)/1_000_000_000f).coerceAtMost(.05f);previous=now
            if(!drag.moved) continue
            val end=list.layoutInfo.viewportEndOffset.toFloat()
            val velocity=when {
                drag.pointer<edge -> -((edge-drag.pointer)/edge).coerceIn(0f,1f)
                drag.pointer>end-edge -> ((drag.pointer-end+edge)/edge).coerceIn(0f,1f)
                else -> 0f
            }
            if(velocity!=0f) {list.scrollBy(velocity*speed*dt);drag.cross(velocity,list)}
        }
    }
    Row(Modifier.fillMaxWidth().height(45.dp).padding(start=11.dp,end=6.dp),verticalAlignment=Alignment.CenterVertically) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val density=LocalDensity.current.density
            val widths=remember(maxWidth,contentWidths,index,density) {tabWidths(maxWidth.value,contentWidths,index,density)}
            LaunchedEffect(selectedId,reorderKey,index,widths,maxWidth) {
                if(index>=0 && drag.id==null) list.animateScrollToItem(index)
            }
            LazyRow(Modifier.fillMaxSize().clipToBounds().semantics {contentDescription="Tabs"}
                .pointerInput(enabled,reorderKey,ids,sign) {
                    if(enabled) detectTabDrag(
                        start={point->
                            val x=if(sign>0) point.x else size.width-point.x
                            val started=drag.start(currentWindows.map {it.id},list,x)
                            if(started) {menuId=null;haptic.performHapticFeedback(HapticFeedbackType.LongPress)}
                            started
                        },
                        move={amount->drag.move(amount.x*sign,viewConfiguration.touchSlop,list)},
                        cancel={drag.cancel()},
                        end={
                            val id=drag.id
                            if(id!=null) {
                                if(drag.changed()) {val i=drag.order.indexOf(id);move(id,drag.order.getOrNull(i+1))}
                                else if(!drag.moved) menuId=id
                            }
                            drag.cancel()
                        })
                },state=list,userScrollEnabled=drag.id==null,verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(TAB_GAP_DP.dp)) {
                val displayed=if(drag.id==null) windows else drag.order.mapNotNull {id->windows.firstOrNull {it.id==id}}
                itemsIndexed(displayed,key={_,w->w.id}) {i,w ->
                    val windowIndex=windows.indexOfFirst {it.id==w.id}
                    fun moveBy(delta: Int): Boolean {
                        val target=windowIndex+delta
                        if(target !in windows.indices) return false
                        val reordered=ids.toMutableList().apply {removeAt(windowIndex);add(target,w.id)}
                        return onMove(w.id,reordered.getOrNull(target+1))
                    }
                    val leftLabel=if(sign>0) "Move left" else "Move right"
                    val rightLabel=if(sign>0) "Move right" else "Move left"
                    val active=w.id==selectedId
                    val allocation=widths[windowIndex]
                    val width=allocation.width.dp
                    val showTitle=allocation.showTitle
                    val indicatorColor=MaterialTheme.colorScheme.primary
                    val busy=paneWorking(w)
                    val workPhase=rememberWorkingPhase(active && busy && animateWorking)
                    val programs=panePrograms(w)
                    val pane=selectedPane(w)
                    val program=programs.getOrNull(pane) ?: ProgramIdentity.Loading
                    val title=program.tabTitle(w.label,w.blocks.getOrNull(pane)?.label.orEmpty(),i+1,paneTitle(w,pane))
                    Box(Modifier.animateItem(fadeInSpec=null,fadeOutSpec=null,placementSpec=if(drag.id==w.id) null else androidx.compose.animation.core.spring())
                        .zIndex(if(drag.id==w.id) 1f else 0f)
                        .graphicsLayer {translationX=drag.translation(w.id,list)*sign}) {
                    Column(Modifier.height(45.dp).width(width)
                        .drawWithContent {
                            drawContent()
                            if(active) {
                                val origin=Offset(0f,size.height-2.dp.toPx());val bar=Size(size.width,2.dp.toPx())
                                drawWorkingBar(indicatorColor,origin,bar,phase=if(busy && animateWorking) workPhase.value else null)
                            }
                        }
                        .clip(RoundedCornerShape(16.dp))
                        .background(if(drag.id==w.id && drag.moved) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .semantics(mergeDescendants=true) {contentDescription="Tab $title, ${program.label}"+(if(busy) ", working" else "")}
                        .semantics {
                            selected=active
                            if(enabled) {
                                onLongClick("Tab options") {menuId=w.id;true}
                                customActions=buildList {
                                    if(windowIndex>0) add(CustomAccessibilityAction(leftLabel) {moveBy(-1)})
                                    if(windowIndex<windows.lastIndex) add(CustomAccessibilityAction(rightLabel) {moveBy(1)})
                                }
                            }
                        }
                        .clickable(enabled=enabled,role=Role.Tab,onClick={onSelect(w.id)}),horizontalAlignment=Alignment.CenterHorizontally) {
                        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal=6.dp),
                            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=if(showTitle) Arrangement.spacedBy(2.dp) else Arrangement.Center) {
                            ProgramStack(programs,pane,fixedSlot=true)
                            if(showTitle) Text(title,Modifier.weight(1f),fontSize=12.sp,lineHeight=18.sp,
                                style=androidx.compose.ui.text.TextStyle(platformStyle=androidx.compose.ui.text.PlatformTextStyle(includeFontPadding=false)),fontWeight=if(active) FontWeight.Bold else FontWeight.Normal,
                                color=if(active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines=1,overflow=TextOverflow.Ellipsis,letterSpacing=0.sp)
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                    androidx.compose.material3.DropdownMenu(menuId==w.id,{menuId=null},containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
                        TerminalMenuItem(leftLabel,ChromeIcon.LEFT,enabled=enabled && windowIndex>0,onClick={menuId=null;moveBy(-1)})
                        TerminalMenuItem(rightLabel,ChromeIcon.RIGHT,enabled=enabled && windowIndex<windows.lastIndex,onClick={menuId=null;moveBy(1)})
                        TerminalMenuItem("Close tab",ChromeIcon.CLOSE,enabled=enabled,onClick={menuId=null;onClose(w)})
                    }
                    }
                }
            }
        }
        actions()
    }
}

@Composable internal fun TerminalActions(enabled: Boolean,activityEnabled: Boolean,
    onActivity: ()->Unit,onSwitcher: ()->Unit,attentionCount: Int=0) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        ChromeButton(ChromeIcon.SPLIT,"Tabs and panes",enabled,readable=activityEnabled,onClick=onSwitcher)
        Box(Modifier.size(41.dp),contentAlignment=Alignment.Center) {
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(23.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled=activityEnabled,role=Role.Button,onClick=onActivity)
                .semantics {contentDescription="Activity";stateDescription=if(attentionCount>0) "$attentionCount new activity items" else "All activity seen"},contentAlignment=Alignment.Center) {
                ChromeSymbol(ChromeIcon.EYE,color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(activityEnabled) .85f else .3f))
            }
            // Badge the eye glyph, rather than the corner of its larger touch target.
            if(attentionCount>0) Box(Modifier.align(Alignment.Center).offset(x=7.dp,y=(-4).dp).size(9.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(5.dp)).padding(1.dp)
                .background(MaterialTheme.colorScheme.primary,RoundedCornerShape(4.dp)).semantics {testTag="Activity unread dot"})
        }
    }
}

@Composable internal fun KeyboardBar(controlled: Boolean,enabled: Boolean,control: Boolean,alt: Boolean,shift: Boolean,
    keyboardVisible: Boolean,onControl: ()->Unit,onAlt: ()->Unit,onShift: ()->Unit,onShiftPress: ()->Unit,onShiftRelease: (Boolean)->Unit,onKey: (String)->Unit,readable: Boolean=enabled,onControlShortcut: (String)->Unit={},inputKey: String="",shortcutGesture: ControlShortcutGesture=ControlShortcutGesture.HOLD,arrowJoystickEnabled: Boolean=true,onPaste: (() ->Unit)?=null,onKeyboard: ()->Unit) {
    var expanded by remember {mutableStateOf(false)}
    // The extra-key trigger lives just before Esc, outside the initial viewport.
    val keyScroll=rememberScrollState(initial=Int.MAX_VALUE)
    LaunchedEffect(keyboardVisible) {if(!keyboardVisible) {expanded=false;keyScroll.scrollTo(keyScroll.maxValue)}}
    LaunchedEffect(expanded) {if(!expanded) keyScroll.scrollTo(keyScroll.maxValue)}
    Column(Modifier.fillMaxWidth().padding(horizontal=8.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha=.4f)))
        Row(Modifier.fillMaxWidth().height(45.dp).padding(vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val visibleWidth=maxWidth
                val keyWidth=(visibleWidth-24.dp)/7
                Row(Modifier.fillMaxSize().systemGestureExclusion().horizontalScroll(keyScroll)
                    .semantics {contentDescription="Terminal keys"},horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.width(keyWidth).fillMaxHeight().clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .semantics {contentDescription=if(expanded) "Hide extra keys" else "Show extra keys";stateDescription=if(expanded) "Expanded" else "Collapsed"}
                        .clickable(enabled=enabled,role=Role.Button) {expanded=!expanded},contentAlignment=Alignment.Center) {
                        ChromeSymbol(ChromeIcon.CHEVRON,Modifier.size(16.dp).graphicsLayer {rotationZ=if(expanded) 180f else 0f})
                    }
                    // Give the visible group an exact viewport width so pixel rounding
                    // cannot clip Esc when the hidden trigger is scrolled away.
                    Row(Modifier.width(visibleWidth).fillMaxHeight(),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
                        DockKey("esc",controlled,description="Escape",modifier=Modifier.weight(1f),icon=ChromeIcon.ESCAPE,readable=readable) {onKey("esc")}
                        ControlDockKey(controlled,control,readable,Modifier.weight(1f),onControl,onControlShortcut,shortcutGesture)
                        DockKey("alt",controlled,alt,description="Alt",modifier=Modifier.weight(1f),icon=ChromeIcon.ALT,readable=readable,onClick=onAlt)
                        ShiftDockKey(controlled,shift,Modifier.weight(1f),onShift,onShiftPress,onShiftRelease,readable=readable)
                        DockKey("tab",controlled,description="Tab key",modifier=Modifier.weight(1f),icon=ChromeIcon.TAB,readable=readable) {onKey("tab")}
                        ArrowJoystickDockKey(controlled,Modifier.weight(1f),onKey,readable,inputKey,keyboardVisible,arrowJoystickEnabled)
                        DockKey("enter",controlled,description="Enter",modifier=Modifier.weight(1f),icon=ChromeIcon.ENTER,readable=readable) {onKey("enter")}
                    }
                }
            }
            key(inputKey) {ChromeButton(if(keyboardVisible) ChromeIcon.HIDE_KEYBOARD else ChromeIcon.KEYBOARD,
                if(!controlled) "Take control and show keyboard" else if(keyboardVisible) "Hide keyboard" else "Show keyboard",enabled,readable=readable,onLongClick=if(controlled && enabled) onPaste else null,onClick=onKeyboard)}
        }
        if(expanded) {
            val extra=listOf((1..6).map {"F$it" to "f$it"},(7..12).map {"F$it" to "f$it"},
                listOf("Home" to "home","PgUp" to "pageup","PgDn" to "pagedown","End" to "end","Ins" to "insert","Del" to "delete","Backspace" to "backspace"))
            for(row in extra) Row(Modifier.fillMaxWidth().height(43.dp).padding(bottom=6.dp),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                for((label,key) in row) DockKey(label,controlled,description=if(key.startsWith("f")) "Function key $label" else label,modifier=Modifier.weight(1f),readable=readable,icon=if(key=="backspace") ChromeIcon.BACKSPACE else null,repeat=key in listOf("delete","backspace"),inputKey=inputKey) {onKey(key)}
            }
        }
    }
}

@Composable private fun DockKey(label: String,enabled: Boolean,active: Boolean=false,description: String=label,modifier: Modifier=Modifier.width(if(label=="ctrl") 47.dp else 40.dp),icon: ChromeIcon?=null,readable: Boolean=enabled,repeat: Boolean=false,inputKey: String="",onClick: ()->Unit) {
    Box(modifier.fillMaxHeight().clip(RoundedCornerShape(11.dp))
        .background(if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
        .semantics(mergeDescendants=true) {contentDescription=description;selected=active}
        .then(if(repeat) Modifier.repeatKey(enabled,inputKey,onKey=onClick) else Modifier.clickable(enabled=enabled,role=Role.Button,onClick=onClick)),contentAlignment=Alignment.Center) {
        val color=(if(active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface).copy(alpha=if(readable) 1f else .38f)
        if(icon!=null) ChromeSymbol(icon,Modifier.size(20.dp),color)
        else Text(label,fontFamily=MonoUi,fontSize=11.sp,fontWeight=if(active) FontWeight.Bold else FontWeight.Normal,color=color,letterSpacing=0.sp)
    }
}


@Composable private fun ShiftDockKey(enabled: Boolean,active: Boolean,modifier: Modifier,
    onToggle: ()->Unit,onPress: ()->Unit,onRelease: (Boolean)->Unit,readable: Boolean=enabled) {
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    Box(modifier.fillMaxHeight().clip(RoundedCornerShape(11.dp))
        .background(if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
        .semantics {
            contentDescription="Shift";selected=active;role=Role.Button
            stateDescription=if(active) "On" else "Off"
            if(enabled) onClick {onToggle();true} else disabled()
        }.pointerInput(enabled) {
            if(!enabled) return@pointerInput
            awaitEachGesture {
                val down=awaitFirstDown();down.consume();press()
                var cancelled=true
                try {
                    while(true) {
                        val change=awaitPointerEvent().changes.firstOrNull {it.id==down.id} ?: break
                        if(change.isConsumed) break
                        change.consume()
                        if(!change.pressed) {
                            cancelled=change.position.x !in 0f..size.width.toFloat() || change.position.y !in 0f..size.height.toFloat()
                            break
                        }
                    }
                } finally {release(cancelled)}
            }
        },contentAlignment=Alignment.Center) {
        ChromeSymbol(ChromeIcon.SHIFT,Modifier.size(20.dp),
            (if(active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface).copy(alpha=if(readable) 1f else .38f))
    }
}
