package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TabPaneSwitcher(
    windows: List<WindowItem>,selectedWindow: String,selectedBlock: String,
    programs: Map<String,ProgramIdentity>,titles: Map<String,String>,directories: Map<String,String>,
    selectedPane: (WindowItem)->Int,enabled: Boolean,canCreateTab: Boolean,canCreatePane: Boolean,
    onTab: (String)->Unit,onPane: (String)->Unit,onNewTab: ()->Unit,onNewPane: (String,PaneSplit)->Unit,
    onCloseTab: (WindowItem)->Unit,onClosePane: (BlockItem)->Unit,onClose: ()->Unit,initialPage: Int=0,
    onMoveTab: (String,String?)->Boolean={_,_->false},onMovePane: (String,String,String?)->Boolean={_,_,_->false},reorderKey: String="",
) {
    val pager=rememberPagerState(initialPage=initialPage.coerceIn(0,1),pageCount={2})
    val scope=rememberCoroutineScope()
    var targetWindow by remember {mutableStateOf(selectedWindow)}
    val current=windows.firstOrNull {it.id==targetWindow}
    val panes=current?.blocks.orEmpty()
    var targetPane by remember(targetWindow) {mutableStateOf(if(targetWindow==selectedWindow) selectedBlock else current?.blocks?.getOrNull(selectedPane(current))?.id.orEmpty())}
    val targetNumber=panes.indexOfFirst {it.id==targetPane}.takeIf {it>=0}?.plus(1)
    val canOpenPane=enabled && targetNumber!=null
    val canSplit=canCreatePane && targetNumber!=null
    var splitMenu by remember(targetWindow,targetPane,canSplit,enabled,pager.currentPage) {mutableStateOf(false)}
    val hasLayout=panes.isNotEmpty() && current!=null && panes.all {pane->current.layout.any {it.id==pane.id}}
    val layoutAllowance=if(current?.layout?.any {it.floating}==true) 128 else 104
    val panelHeight=(128+maxOf(windows.size,panes.size,1).coerceAtMost(7)*60+if(hasLayout) layoutAllowance else 0).dp.coerceAtMost(620.dp)
    fun tabTitle(tab: WindowItem): String {
        val block=tab.blocks.getOrNull(selectedPane(tab))
        val program=programs[block?.id] ?: ProgramIdentity.Loading
        return program.tabTitle(tab.label,block?.label.orEmpty(),windows.indexOf(tab)+1,titles[block?.id].orEmpty())
    }
    RexSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface,
        dragHandle={Box(Modifier.fillMaxWidth().height(16.dp),contentAlignment=Alignment.Center) {
            Box(Modifier.width(28.dp).height(3.dp).background(MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(3.dp)))
        }}) {
        Column(Modifier.fillMaxWidth().height(panelHeight).padding(bottom=4.dp)) {
            Row(Modifier.fillMaxWidth().padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                Row(Modifier.weight(1f).height(48.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f),RoundedCornerShape(12.dp)).padding(4.dp).selectableGroup()) {
                listOf("Tabs" to windows.size,"Panes" to panes.size).forEachIndexed {page,(label,count)->
                    val active=pager.currentPage==page
                    Row(Modifier.weight(1f).fillMaxHeight().background(if(active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,RoundedCornerShape(9.dp))
                        .selectable(active,role=Role.Tab,onClick={scope.launch {pager.animateScrollToPage(page)}}),
                        horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                            Text(label,style=SettingsType.row,color=if(active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(count.toString(),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                }
                IconButton(onClick=onClose,modifier=Modifier.semantics {contentDescription="Close tab and pane switcher"}) {ChromeSymbol(ChromeIcon.CLOSE,Modifier.size(18.dp))}
            }
            HorizontalPager(pager,Modifier.fillMaxWidth().weight(1f).semantics {contentDescription="Tab and pane pages"},verticalAlignment=Alignment.Top) {page->
                val ids=if(page==0) windows.map {it.id} else panes.map {it.id}
                val list=rememberLazyListState()
                val drag=remember(page,reorderKey,if(page==1) targetWindow else "") {TabDrag()}
                val haptic=LocalHapticFeedback.current
                val edge=with(LocalDensity.current) {32.dp.toPx()}
                val speed=with(LocalDensity.current) {420.dp.toPx()}
                fun move(id: String,before: String?)=if(page==0) onMoveTab(id,before) else onMovePane(targetWindow,id,before)
                fun moveBy(id: String,delta: Int): Boolean {
                    val index=ids.indexOf(id);val to=index+delta
                    if(index<0 || to !in ids.indices) return false
                    val order=ids.toMutableList().apply {removeAt(index);add(to,id)}
                    return move(id,order.getOrNull(to+1))
                }
                fun rowModifier(id: String)=Modifier
                    .zIndex(if(drag.id==id) 1f else 0f).graphicsLayer {translationY=drag.translation(id,list)}
                fun rowActions(id: String): List<CustomAccessibilityAction> =
                        if(enabled) buildList {
                            val index=ids.indexOf(id)
                            if(index>0) add(CustomAccessibilityAction("Move up") {moveBy(id,-1)})
                            if(index>=0 && index<ids.lastIndex) add(CustomAccessibilityAction("Move down") {moveBy(id,1)})
                        } else emptyList()
                LaunchedEffect(ids,enabled) {drag.cancel()}
                LaunchedEffect(drag.id) {
                    var previous=withFrameNanos {it}
                    while(drag.id!=null) {
                        val now=withFrameNanos {it};val dt=((now-previous)/1_000_000_000f).coerceAtMost(.05f);previous=now
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
                Column(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).selectableGroup().semantics {contentDescription=if(page==0) "Reorder tabs" else "Reorder panes"}
                        .pointerInput(enabled,ids,reorderKey,targetWindow,page) {
                            if(enabled) detectTabDrag(
                                start={point->val started=drag.start(ids,list,point.y);if(started) haptic.performHapticFeedback(HapticFeedbackType.LongPress);started},
                                move={amount->drag.move(amount.y,viewConfiguration.touchSlop,list)},
                                cancel={drag.cancel()},end={
                                    val id=drag.id
                                    if(id!=null && drag.changed()) move(id,drag.order.getOrNull(drag.order.indexOf(id)+1))
                                    drag.cancel()
                                })
                        },state=list,userScrollEnabled=drag.id==null,
                        contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        if(page==0) {
                            if(windows.isEmpty()) item {SwitcherEmpty("No tabs yet","Create a tab to start a terminal.")}
                            val displayed=if(drag.id==null) windows else drag.order.mapNotNull {id->windows.firstOrNull {it.id==id}}
                            items(displayed,key={it.id}) {tab->
                                val focus=selectedPane(tab)
                                val block=tab.blocks.getOrNull(focus)
                                val icons=tab.blocks.map {programs[it.id] ?: ProgramIdentity.Loading}
                                val title=tabTitle(tab)
                                val count="${tab.blocks.size} pane${if(tab.blocks.size==1) "" else "s"}"
                                val directory=directories[block?.id].orEmpty()
                                val subtitle=listOfNotNull(if(tab.id==selectedWindow) "Viewing" else null,count,directory.takeIf {it.isNotBlank()}).joinToString(" · ")
                                SwitcherItem(title,subtitle,icons,focus,tab.id==targetWindow,
                                    "Tab $title",enabled && tab.blocks.isNotEmpty(),{targetWindow=tab.id},enabled,"Close tab $title",{onCloseTab(tab)},
                                    Modifier.animateItem(fadeInSpec=null,fadeOutSpec=null,placementSpec=if(drag.id==tab.id) null else androidx.compose.animation.core.spring()).then(rowModifier(tab.id)),rowActions(tab.id))
                            }
                        } else {
                            if(panes.isEmpty()) item {SwitcherEmpty("No panes yet","Choose a tab or create a new one.")}
                            else if(hasLayout) item(key="layout") {
                                PaneLayoutMap(current!!,targetPane,programs,titles,enabled) {targetPane=it}
                                Spacer(Modifier.height(4.dp))
                            } else item(key="layout-unavailable") {Text("Layout unavailable",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            val displayed=if(drag.id==null) panes else drag.order.mapNotNull {id->panes.firstOrNull {it.id==id}}
                            items(displayed,key={it.id}) {block->
                                val program=programs[block.id] ?: ProgramIdentity.Loading
                                val title=program.paneTitle(block.label,titles[block.id].orEmpty())
                                val subtitle=listOfNotNull(if(block.id==selectedBlock) "Viewing" else null,if(current?.layout?.any {it.id==block.id && it.floating}==true) "Floating" else null,directories[block.id].orEmpty().ifBlank {program.label}).joinToString(" · ")
                                SwitcherItem(title,subtitle,listOf(program),0,block.id==targetPane,
                                    "Pane $title",enabled,{targetPane=block.id},enabled,"Close pane $title",{onClosePane(block)},
                                    Modifier.animateItem(fadeInSpec=null,fadeOutSpec=null,placementSpec=if(drag.id==block.id) null else androidx.compose.animation.core.spring()).then(rowModifier(block.id)),rowActions(block.id))
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                    if(page==0) Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Button(onClick={onTab(targetWindow)},enabled=enabled && current?.blocks?.isNotEmpty()==true && !pager.isScrollInProgress,
                            modifier=Modifier.weight(1f).heightIn(min=48.dp)) {Text("Open tab",style=SettingsType.row)}
                        FilledTonalButton(onClick=onNewTab,enabled=canCreateTab && !pager.isScrollInProgress,
                            modifier=Modifier.weight(1f).heightIn(min=48.dp)) {
                        ChromeSymbol(ChromeIcon.ADD,Modifier.size(18.dp),MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Text("New tab",style=SettingsType.row)
                        }
                    } else Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Button(onClick={onPane(targetPane)},enabled=canOpenPane && !pager.isScrollInProgress,
                            modifier=Modifier.weight(1f).heightIn(min=48.dp)) {Text("Open pane",style=SettingsType.row)}
                        Box(Modifier.weight(1f)) {
                            FilledTonalButton(onClick={splitMenu=true},enabled=canSplit && !pager.isScrollInProgress,
                                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                                ChromeSymbol(ChromeIcon.ADD,Modifier.size(18.dp),LocalContentColor.current)
                                Spacer(Modifier.width(8.dp))
                                Text("New pane",style=SettingsType.row)
                            }
                            DropdownMenu(expanded=splitMenu,onDismissRequest={splitMenu=false},containerColor=MaterialTheme.colorScheme.surface,
                                shape=RoundedCornerShape(14.dp),tonalElevation=0.dp,shadowElevation=0.dp,
                                modifier=Modifier.width(188.dp).border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(14.dp))) {
                                PaneSplit.entries.forEach {split->
                                    DropdownMenuItem(text={Text(split.label,style=SettingsType.row)},
                                        leadingIcon={SplitDiagram(split,Modifier.size(24.dp),MaterialTheme.colorScheme.onSurfaceVariant)},
                                        enabled=canSplit && !pager.isScrollInProgress,onClick={splitMenu=false;onNewPane(targetPane,split)})
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SplitDiagram(split: PaneSplit,modifier: Modifier,color: Color) {
    Canvas(modifier) {
        val u=size.minDimension/24f
        val right=split==PaneSplit.RIGHT
        drawRoundRect(color.copy(alpha=.22f),Offset((if(right) 12f else 2f)*u,(if(right) 4f else 12f)*u),
            Size((if(right) 10f else 20f)*u,(if(right) 16f else 8f)*u),CornerRadius(1.5f*u))
        drawRoundRect(color,Offset(2f*u,4f*u),Size(20f*u,16f*u),CornerRadius(2f*u),style=Stroke(1.5f*u))
        drawLine(color,Offset((if(right) 12f else 2f)*u,(if(right) 4f else 12f)*u),
            Offset((if(right) 12f else 22f)*u,(if(right) 20f else 12f)*u),1.5f*u,StrokeCap.Round)
    }
}

@Composable private fun SwitcherEmpty(title: String,description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(title,style=SettingsType.row)
        Text(description,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SwitcherItem(title: String,subtitle: String,programs: List<ProgramIdentity>,focus: Int,active: Boolean,
    description: String,enabled: Boolean,onSelect: ()->Unit,canClose: Boolean,closeDescription: String,onClose: ()->Unit,modifier: Modifier=Modifier,reorderActions: List<CustomAccessibilityAction> = emptyList()) {
    val primary=MaterialTheme.colorScheme.primary
    Surface(modifier=modifier,shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f)) {
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).selectable(active,enabled=enabled,role=Role.RadioButton,onClick=onSelect)
            .semantics {contentDescription=description;stateDescription=if(active) "Selected" else "";customActions=reorderActions}.padding(start=8.dp,end=0.dp,top=4.dp,bottom=4.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(3.dp).height(22.dp).then(if(active) Modifier.background(primary,RoundedCornerShape(2.dp)) else Modifier))
            Box(Modifier.width(36.dp),contentAlignment=Alignment.Center) {ProgramStack(programs,focus,fixedSlot=true)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)) {
                Text(title,style=SettingsType.row,color=if(active) primary else MaterialTheme.colorScheme.onSurface,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(subtitle,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            IconButton(onClick=onClose,enabled=canClose,modifier=Modifier.semantics {contentDescription=closeDescription}) {
                ChromeSymbol(ChromeIcon.CLOSE,Modifier.size(17.dp),if(canClose) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.38f))
            }
        }
    }
}
