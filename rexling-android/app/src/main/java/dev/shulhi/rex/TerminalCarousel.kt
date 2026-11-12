package dev.shulhi.rex

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

@Composable internal fun TerminalCarousel(blocks: List<BlockItem>,selectedBlock: String,liveBlock: String,
    liveScreen: TerminalScreen?,frames: Map<String,TerminalScreen>,programs: Map<String,ProgramIdentity>,controlled: Boolean,fontSize: Float,
    onInput: (String)->Unit,onScroll: (Int)->Unit,onWheel: (Int,Int,Int,Int,Int)->Unit,onActivate: ()->Unit,onBottom: ()->Unit,onDimensions: (Int,Int)->Unit,onSelect: (String)->Unit,onPanes: ()->Unit,
    onView: (TerminalView)->Unit,modifier: Modifier=Modifier,bindLiveView: ((String,TerminalView)->Boolean)?=null,
    onMouseClick: (Int,Int,Int,Int)->Unit={_,_,_,_->},mouseTarget: ()->String={""},onImage: (android.net.Uri,()->Unit)->Boolean={_,release->release();false},onPaste: ()->Boolean={false},imageTarget: ()->String=mouseTarget,bottomControlsInset: androidx.compose.ui.unit.Dp=0.dp,paneLayout: List<PanePlacement> = emptyList(),titles: Map<String,String> = emptyMap(),directories: Map<String,String> = emptyMap(),hazeState: HazeState?=null,captureBackdrop: Boolean=false,
    padding: TerminalPadding=TerminalPadding.NORMAL,extendPadding: Boolean=true) {
    val switchLayout=remember(paneLayout,blocks) {paneLayout.filter {p->blocks.any {it.id==p.id}}.sortedBy {it.layer}}
    val hasMinimap=blocks.all {b->switchLayout.any {it.id==b.id}}
    val localHaze=remember {HazeState()}
    val haze=hazeState ?: localHaze
    // Our padding preference sets the pager strip; a floating dock needs room of its own.
    val pagerHeight=padding.pagedBottom.coerceAtLeast(10)
    val bottomPadding=maxOf(if(blocks.size>1) pagerHeight else padding.bottom,if(bottomControlsInset>0.dp) 28 else 0)
    val palette=LocalTerminalPalette.current
    val backdrop=MaterialTheme.colorScheme.background
    val scope=rememberCoroutineScope()
    var visualId by remember(blocks.map {it.id}) {mutableStateOf(selectedBlock)}
    var drag by remember {mutableFloatStateOf(0f)}
    var dragging by remember {mutableStateOf(false)}
    var settling by remember {mutableStateOf(false)}
    var toast by remember {mutableStateOf(false)}
    var settlingTarget by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(dragging || settling) {
        if(android.os.Build.VERSION.SDK_INT>=29) android.os.Trace.setCounter("Rexlink.paneMotion",if(dragging || settling) 1L else 0L)
    }
    DisposableEffect(Unit) {onDispose {
        if(android.os.Build.VERSION.SDK_INT>=29) android.os.Trace.setCounter("Rexlink.paneMotion",0L)
    }}
    val latestSelect by rememberUpdatedState(onSelect)
    val index=blocks.indexOfFirst {it.id==visualId}.coerceAtLeast(0)
    LaunchedEffect(selectedBlock,settling,dragging) {
        if(!settling && !dragging && visualId!=selectedBlock) {
            Snapshot.withMutableSnapshot {visualId=selectedBlock;settlingTarget=selectedBlock;drag=0f;toast=true}
        }
    }
    // Start the dismissal timer only after motion ends, without hiding the badge
    // between the animation and its post-switch display.
    LaunchedEffect(visualId,toast,dragging,settling) {
        if(toast && !dragging && !settling) {delay(1000);toast=false}
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        val cardWidth=maxWidth.coerceAtLeast(80.dp)
        val cardLeft=0.dp
        val stride=with(LocalDensity.current){(cardWidth+8.dp).toPx()}
        val leftPx=with(LocalDensity.current){cardLeft.toPx()}
        val candidate by remember(stride,blocks) {derivedStateOf {
            if(settling || toast) blocks.indexOfFirst {it.id==(settlingTarget ?: visualId)}.coerceAtLeast(0) else {
                val current=blocks.indexOfFirst {it.id==visualId}.coerceAtLeast(0)
                if(blocks.isEmpty()) 0 else Math.floorMod(current+if(drag< -stride*.12f) 1 else if(drag>stride*.12f) -1 else 0,blocks.size)
            }
        }}
        val flingSpeed=with(LocalDensity.current){450.dp.toPx()}
        fun finish(dx: Float,velocity: Float) {
            val step=if(dx < -stride*.18f || (dx< -20f && velocity < -flingSpeed)) 1 else if(dx > stride*.18f || (dx>20f && velocity>flingSpeed)) -1 else 0
            val target=if(blocks.isEmpty()) 0 else Math.floorMod(index+step,blocks.size)
            Snapshot.withMutableSnapshot {
                settlingTarget=blocks.getOrNull(target)?.id;toast=target!=index;settling=true;dragging=false
            }
            if(target!=index) blocks.getOrNull(target)?.let {latestSelect(it.id)}
            scope.launch {
                val animation=Animatable(drag)
                animation.animateTo(if(target==index) 0f else if(step>0) -stride else stride,tween(190)) {drag=value}
                Snapshot.withMutableSnapshot {
                    if(target!=index) visualId=blocks[target].id
                    drag=0f;settling=false
                }
            }
        }
        // Two-pane rings share one neighbor: render it on the gesture side so
        // each real pane keeps one AndroidView and one stable key.
        val offsets=when(blocks.size) {0->emptyList();1->listOf(0);2->listOf(0,if(drag>0) -1 else 1);else->listOf(-1,0,1)}
        val showSwitch=(dragging || settling || toast) && blocks.size>1 && blocks.getOrNull(candidate)!=null
        Box(Modifier.fillMaxSize().then(if(showSwitch || captureBackdrop) Modifier.hazeSource(haze) else Modifier)) {
        for(offset in offsets) {
            val i=Math.floorMod(index+offset,blocks.size)
            val block=blocks[i]
            val center=offset==0
            val frame=if(block.id==liveBlock && liveScreen!=null) liveScreen else frames[block.id]
            val pageOffset=offset*stride
            key(block.id) {
                Box(Modifier.width(cardWidth).fillMaxHeight().graphicsLayer {
                    val position=pageOffset+drag
                    translationX=kotlin.math.round(leftPx+position)
                }.clip(RoundedCornerShape(21.dp)).background(Color(palette.background))
                    .paneScrim(backdrop) {(abs(pageOffset+drag)/stride).coerceIn(0f,1f)*.48f}
                    .then(if(center) Modifier else Modifier.clickable(role=Role.Button){latestSelect(block.id)})) {
                    Box(Modifier.fillMaxSize()) {
                        if(frame!=null) AndroidView(
                            factory={TerminalView(it)},modifier=Modifier.fillMaxSize().clipToBounds(),
                            update={view ->
                                view.onDimensions=if(center && block.id==liveBlock) onDimensions else {_,_->}
                                view.setTerminalPaddingDp(padding.horizontal,padding.top,bottomPadding)
                                view.extendPadding=extendPadding
                                view.palette=palette;view.textSp=fontSize
                                if(block.id!=liveBlock || bindLiveView?.invoke(block.id,view)!=true) view.screen=frame
                                view.interactive=center
                                view.controlled=center && controlled && block.id==liveBlock
                                view.imageInputTarget=imageTarget
                                view.onImage=if(center && block.id==liveBlock) onImage else {_,release->release();false}
                                view.onPaste=if(center && block.id==liveBlock) onPaste else ({false})
                                view.onInput=onInput
                                view.onMouseClick=if(center && block.id==liveBlock) onMouseClick else ({_,_,_,_->})
                                view.mouseTarget=mouseTarget
                                view.onActivate=if(center && block.id==liveBlock) onActivate else ({})
                                view.onWheel=if(center && block.id==liveBlock) onWheel else ({_,_,_,_,_->})
                                view.onScroll=if(center && block.id==liveBlock) onScroll else {_->}
                                view.onPaneDrag=if(center) {dx,active ->
                                    if(!settling) {
                                        dragging=active;toast=false
                                        drag=if(blocks.size<2) dx*.2f else dx
                                        if(!active) drag=0f
                                    }
                                } else null
                                view.onPaneDragEnd=if(center) {dx,velocity->if(!settling) finish(dx,velocity)} else null
                                view.importantForAccessibility=if(center) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                if(center) onView(view)
                            })
                        else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                            Text(if(center) "Opening terminal…" else block.label,fontFamily=MonoUi,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if(center && (frame?.scroll?.below ?: 0)>0) {
                        Text("↓ Latest",Modifier.align(Alignment.BottomEnd).padding(end=12.dp,bottom=(if(blocks.size>1) if(hasMinimap) 128.dp else 88.dp else 10.dp)+bottomControlsInset)
                            .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
                            .clickable(role=Role.Button,onClick=onBottom).padding(horizontal=12.dp,vertical=7.dp),
                            fontSize=11.sp,color=MaterialTheme.colorScheme.primary)
                    }

                }
            }
        }
        }
        if(blocks.size>1) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(pagerHeight.dp)
                .clickable(role=Role.Button,onClick=onPanes)
                .semantics {contentDescription="Pane position indicator";stateDescription="Pane ${candidate+1} of ${blocks.size}"},contentAlignment=Alignment.Center) {
                PanePositionIndicator(index,blocks.size,offset={-drag/stride})
            }
        }
        if(showSwitch) {
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom=(pagerHeight+6).dp)
                .widthIn(max=(maxWidth-32.dp).coerceIn(80.dp,240.dp))
                .semantics {contentDescription="Pane switch indicator";stateDescription=blocks[candidate].id},
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    if(hasMinimap) PaneSwitchMinimap(switchLayout,blocks[candidate].id,Modifier.size(56.dp,36.dp),hazeState=haze)
                    Row(Modifier.paneFrost(haze,RoundedCornerShape(13.dp)).semantics {contentDescription="Pane switch caption"}.padding(horizontal=6.dp,vertical=4.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                        val block=blocks[candidate]
                        val program=programs[block.id] ?: ProgramIdentity.Loading
                        PaneProgramTile(program,Modifier.size(22.dp,18.dp),false,0f)
                        Column(Modifier.weight(1f,fill=false)) {
                            Text(program.paneTitle(block.label,titles[block.id].orEmpty()),
                                fontFamily=MonoUi,fontWeight=FontWeight.Bold,fontSize=12.sp,lineHeight=18.sp,letterSpacing=0.sp,
                                style=androidx.compose.ui.text.TextStyle(platformStyle=androidx.compose.ui.text.PlatformTextStyle(includeFontPadding=false)),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(directories[block.id].orEmpty(),fontFamily=MonoUi,fontSize=10.sp,lineHeight=14.sp,letterSpacing=0.sp,maxLines=1,
                                overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
            }
        }
    }
}
