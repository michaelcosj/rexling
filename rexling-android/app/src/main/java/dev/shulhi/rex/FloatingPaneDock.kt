package dev.shulhi.rex

import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable internal fun FloatingPaneDock(panes: List<BlockItem>,active: String?,programs: Map<String,ProgramIdentity>,
    titles: Map<String,String>,enabled: Boolean,onOpen: (String)->Unit,onMinimize: ()->Unit,modifier: Modifier=Modifier,directories: Map<String,String> = emptyMap(),hazeState: HazeState?=null,onExpandedChange: (Boolean)->Unit={}) {
    if(panes.isEmpty()) return
    var expanded by remember(panes.map {it.id}) {mutableStateOf(false)}
    val grouped=panes.size>1
    val localHaze=remember {HazeState()}
    val haze=hazeState ?: localHaze
    val notifyBackdrop by rememberUpdatedState(onExpandedChange)
    LaunchedEffect(expanded,grouped) {if(!expanded || !grouped) delay(140);notifyBackdrop(expanded && grouped)}
    DisposableEffect(Unit) {onDispose {notifyBackdrop(false)}}
    LaunchedEffect(grouped) {if(!grouped) expanded=false}
    fun title(pane: BlockItem)=(programs[pane.id] ?: ProgramIdentity.Loading).paneTitle(pane.label,titles[pane.id].orEmpty())
    fun choose(pane: BlockItem) {if(pane.id==active) onMinimize() else onOpen(pane.id);expanded=false}
    Column(modifier.widthIn(max=276.dp),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(2.dp)) {
        AnimatedVisibility(grouped && expanded,
            enter=expandVertically(tween(150),expandFrom=Alignment.Bottom,clip=false)+fadeIn(tween(120)),
            exit=shrinkVertically(tween(120),shrinkTowards=Alignment.Bottom,clip=false)+fadeOut(tween(90))) {
            Column(Modifier.heightIn(max=256.dp).verticalScroll(rememberScrollState()).padding(horizontal=8.dp,vertical=12.dp),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                panes.forEach {pane->
                    val isActive=pane.id==active
                    Row(Modifier.widthIn(max=260.dp).height(32.dp).shadow(4.dp,RoundedCornerShape(16.dp),clip=false).paneFrost(haze,RoundedCornerShape(16.dp),active=isActive)
                        .clickable(enabled=enabled,role=Role.Button) {choose(pane)}
                        .semantics {contentDescription=if(isActive) "Minimize floating pane: ${title(pane)}" else "Open floating pane: ${title(pane)}";selected=isActive},
                        verticalAlignment=Alignment.CenterVertically) {
                        Text(listOf(title(pane),directories[pane.id].orEmpty()).filter {it.isNotBlank()}.joinToString(" · "),Modifier.weight(1f,fill=false).padding(start=12.dp,end=8.dp),
                            fontFamily=MonoUi,fontSize=12.sp,lineHeight=18.sp,letterSpacing=0.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis,
                            color=if(isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                        PaneProgramTile(programs[pane.id] ?: ProgramIdentity.Loading,Modifier.padding(end=8.dp).size(22.dp,18.dp),false,0f)
                    }
                }
            }
        }
        Box(Modifier.padding(end=8.dp).size(40.dp,32.dp)
            .clickable(interactionSource=remember {MutableInteractionSource()},indication=null,enabled=enabled,role=Role.Button) {if(grouped) expanded=!expanded else choose(panes.single())}
            .semantics {
                contentDescription=if(grouped) "Floating panes" else if(active!=null) "Minimize floating pane: ${title(panes.single())}" else "Open floating pane: ${title(panes.single())}"
                selected=active!=null
                if(grouped) stateDescription=if(expanded) "Expanded" else "Collapsed"
            },contentAlignment=Alignment.Center) {
            val index=panes.indexOfFirst {it.id==active}.coerceAtLeast(0)
            ProgramStack(panes.map {programs[it.id] ?: ProgramIdentity.Loading},index,fixedSlot=true,floatingShadow=true,highlighted=active!=null)
        }
    }
}
