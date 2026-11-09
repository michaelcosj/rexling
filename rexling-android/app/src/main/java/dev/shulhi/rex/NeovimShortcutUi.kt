package dev.shulhi.rex

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

@Composable internal fun NeovimShortcutButton(items: List<NeovimShortcut>,target: String,enabled: Boolean,onChoose: (NeovimShortcut,String)->Unit,onCustomize: ()->Unit,modifier: Modifier=Modifier,
    position: Offset=Offset(0f,1f),onPosition: (Offset)->Unit={},bottomInset: Dp=10.dp,layout: NeovimShortcutLayout=NeovimShortcutLayout.GRID,effect: NeovimShortcutEffect=NeovimShortcutEffect.FAN_OUT,hapticsEnabled: Boolean=true,name: String="Neovim") {
    if(layout==NeovimShortcutLayout.CIRCULAR) {
        NeovimRadialShortcutButton(items,target,enabled,onChoose,modifier,position,onPosition,bottomInset,effect,hapticsEnabled,name)
        return
    }
    var open by remember(target) {mutableStateOf(false)}
    BackHandler(open) {}
    var dragged by remember {mutableStateOf<Offset?>(null)}
    val latestPosition by rememberUpdatedState(position)
    val savePosition by rememberUpdatedState(onPosition)
    LaunchedEffect(enabled,target) {if(!enabled) open=false;dragged=null}
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density=LocalDensity.current
        val edge=with(density) {12.dp.toPx()}
        val maxX=with(density) {(maxWidth-24.dp-48.dp).coerceAtLeast(0.dp).toPx()}
        val maxY=with(density) {(maxHeight-12.dp-bottomInset-48.dp).coerceAtLeast(0.dp).toPx()}
        val popupWidth=(maxWidth-24.dp).coerceIn(140.dp,216.dp)

        LaunchedEffect(position,maxX,maxY) {dragged=null}
        fun resting()=Offset(latestPosition.x*maxX,latestPosition.y*maxY)
        fun displayed(at: Offset): Offset {
            val x=edge+at.x.coerceIn(0f,maxX);val y=edge+at.y.coerceIn(0f,maxY)
            return Offset(x,y)
        }
        Box(Modifier.offset {
            val shown=displayed(dragged ?: resting())
            IntOffset(shown.x.roundToInt(),shown.y.roundToInt())
        }.size(48.dp).pointerInput(enabled,target,maxX,maxY) {
            if(enabled) detectDragGestures(
                onDragStart={dragged=resting()},
                onDrag={change,delta->change.consume();val at=(dragged ?: resting())+delta;dragged=Offset(at.x.coerceIn(0f,maxX),at.y.coerceIn(0f,maxY))},
                onDragCancel={dragged=null},
                onDragEnd={dragged?.let {savePosition(Offset(if(maxX>0) it.x/maxX else 0f,if(maxY>0) it.y/maxY else 1f))}})
        }) {
        Box(Modifier.fillMaxSize().semantics {contentDescription="$name shortcuts"}
            .clickable(enabled=enabled,role=Role.Button) {open=!open},contentAlignment=Alignment.Center) {
            NeovimShortcutButtonFace(open,enabled,close=open)
        }
        if(open && enabled) {
            NeovimShortcutPopup(items,{onChoose(it,target)},{open=false;onCustomize()},{open=false},
                width=popupWidth,name=name)
        }
        }
    }
}

/** Small visible floating button; its parent retains the full 48 dp touch/drag target. */
@Composable internal fun NeovimShortcutButtonFace(active: Boolean,enabled: Boolean,modifier: Modifier=Modifier,close: Boolean=false) {
    Box(modifier.size(40.dp).alpha(if(active) 1f else .5f).clip(CircleShape)
        .background(Color.Black.copy(alpha=if(!enabled) .22f else if(active) .78f else .54f))
        .border(1.dp,Color.White.copy(alpha=.14f),CircleShape),contentAlignment=Alignment.Center) {
        if(close) ChromeSymbol(ChromeIcon.CLOSE,Modifier.size(18.dp),Color.White.copy(alpha=.8f))
        else Canvas(Modifier.size(28.dp)) {
            drawCircle(Color.White.copy(alpha=if(enabled) .18f else .1f),radius=size.minDimension*.46f)
            drawCircle(Color.White.copy(alpha=if(enabled) .32f else .15f),radius=size.minDimension*.36f)
            drawCircle(Color.White.copy(alpha=if(enabled) .95f else .35f),radius=size.minDimension*.27f)
        }
    }
}

/** Six cells at a time, using the same compact treatment as the Ctrl shortcut grid. */
@Composable private fun NeovimShortcutPopup(items: List<NeovimShortcut>,onChoose: (NeovimShortcut)->Unit,onCustomize: ()->Unit,onDismiss: ()->Unit,width: Dp,name: String) {
    val provider=rememberSpeechBubblePositioner()
    var group by remember(items) {mutableStateOf("")}
    var rootPage by remember(items) {mutableIntStateOf(0)}
    val root=remember(items) {NeovimShortcuts.entries(items)}
    val hasGroups=root.any {it is NeovimShortcutEntry.Group}
    val slotCount=remember(items) {maxOf(root.size,items.groupBy {it.group}.filterKeys {it.isNotEmpty()}.values.maxOfOrNull {it.size} ?: 0)}
    val rows=((minOf(slotCount,6)+1)/2).coerceAtLeast(1)
    Popup(popupPositionProvider=provider,onDismissRequest={},properties=PopupProperties(focusable=false,dismissOnClickOutside=false,dismissOnBackPress=false)) {
        key(group) {
        val entries=NeovimShortcuts.entries(items,group)
        val pages=(entries.size+5)/6
        // Start in the middle of repeated logical pages so either swipe direction wraps.
        val repeatBase=if(pages>1) Int.MAX_VALUE/2-(Int.MAX_VALUE/2)%pages else 0
        val initialPage=if(group.isEmpty()) rootPage.coerceAtMost((pages-1).coerceAtLeast(0)) else 0
        val pager=rememberPagerState(initialPage=repeatBase+initialPage,pageCount={if(pages>1) Int.MAX_VALUE else 1})
        SpeechBubble(provider,Modifier.width(width).semantics {contentDescription="$name shortcut menu";stateDescription=if(group.isEmpty()) "Shortcuts" else "Group $group"}) {
        Column(Modifier.padding(5.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            if(hasGroups) Row(Modifier.fillMaxWidth().height(28.dp),verticalAlignment=Alignment.CenterVertically) {
                if(group.isNotEmpty()) ChromeButton(ChromeIcon.LEFT,"Back to all shortcuts",modifier=Modifier.size(28.dp)) {group=""}
                Text(group.ifEmpty {"Shortcuts"},Modifier.weight(1f).padding(horizontal=8.dp),fontSize=12.sp,fontWeight=FontWeight.Medium,
                    maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            VerticalPager(pager,Modifier.fillMaxWidth().height((rows*40+(rows-1)*2).dp)
                .semantics {contentDescription="$name shortcut pages"},userScrollEnabled=pages>1) {page->
                Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    repeat(rows) {row->
                        Row(Modifier.fillMaxWidth().height(40.dp),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                            repeat(2) {column->
                                val item=entries.getOrNull((page%pages.coerceAtLeast(1))*6+row*2+column)
                                if(item==null) Spacer(Modifier.weight(1f).fillMaxHeight())
                                else Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal=if(item is NeovimShortcutEntry.Group) 3.dp else 0.dp,vertical=if(item is NeovimShortcutEntry.Group) 3.dp else 0.dp)
                                    .clip(RoundedCornerShape(if(item is NeovimShortcutEntry.Group) 24.dp else 11.dp))
                                    .then(if(item is NeovimShortcutEntry.Group) Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha=.065f))
                                        .semantics {contentDescription="Shortcut group ${item.label}"} else Modifier)
                                    .clickable(role=Role.Button) {when(item) {
                                        is NeovimShortcutEntry.Action->onChoose(item.shortcut)
                                        is NeovimShortcutEntry.Group->{rootPage=pager.currentPage%pages.coerceAtLeast(1);group=item.label}
                                    }},contentAlignment=Alignment.Center) {
                                    Text(item.label,Modifier.padding(horizontal=4.dp),fontFamily=MonoUi,fontSize=13.sp,lineHeight=16.sp,fontWeight=FontWeight.Bold,
                                        maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(28.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(11.dp)).clickable(role=Role.Button,onClick=onCustomize),contentAlignment=Alignment.CenterStart) {
                    Text("Customize shortcuts",Modifier.padding(horizontal=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
                }
                if(pages>1) Text("${pager.currentPage%pages+1}/$pages",Modifier.padding(horizontal=8.dp),fontFamily=MonoUi,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                ChromeButton(ChromeIcon.CLOSE,"Close $name shortcuts",modifier=Modifier.size(28.dp),onClick=onDismiss)
            }
        }
        }
        }
    }
}
