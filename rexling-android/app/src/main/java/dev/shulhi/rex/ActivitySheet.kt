package dev.shulhi.rex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.PI
import kotlin.math.cos
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ActivitySheet(sources: List<ActivitySource>,onRefresh: ()->Unit,onSelect: (ActivityTarget)->Unit,onClose: ()->Unit,onSeen: (ActivityTarget)->Unit={},onMarkAll: ()->Unit={}) {
    var filter by remember {mutableStateOf(ActivityFilter.ALL)}
    val filtered=remember(sources,filter) {sources.map {it to ActivityCatalog.records(it,filter)}}
    val panelHeight=remember(sources) {
        val count=sources.sumOf {it.items.distinctBy {record->record.key}.size}
        val groups=sources.count {it.items.isNotEmpty() || it.error.isNotBlank() || it.partial}
        (112+count.coerceIn(1,7)*72+groups.coerceAtMost(5)*24+sources.count {it.error.isNotBlank() || it.partial}*32).dp.coerceIn(208.dp,620.dp)
    }
    val scheme=MaterialTheme.colorScheme
    RexSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=scheme.surface,
        dragHandle={Box(Modifier.fillMaxWidth().height(16.dp),contentAlignment=Alignment.Center) {
            Box(Modifier.width(28.dp).height(3.dp).background(scheme.outlineVariant,RoundedCornerShape(3.dp)))
        }}) {
        Column(Modifier.fillMaxWidth().height(panelHeight).padding(bottom=4.dp).semantics {paneTitle="Activity"}) {
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("Activity",Modifier.weight(1f),style=SettingsType.row,fontWeight=FontWeight.Bold)
                TextButton(onClick=onMarkAll,contentPadding=PaddingValues(horizontal=8.dp),enabled=sources.any {source->source.connected && source.items.any {it.state.notifiable && it.key !in source.seen}}) {Text("Mark seen",style=SettingsType.detail)}
                ChromeButton(ChromeIcon.REFRESH,"Refresh activity",sources.any {it.connected},onClick=onRefresh)
                ChromeButton(ChromeIcon.CLOSE,"Close activity",onClick=onClose)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp).height(40.dp).clip(RoundedCornerShape(12.dp))
                .background(scheme.surfaceVariant.copy(alpha=.45f)).padding(4.dp).selectableGroup()) {
                ActivityFilter.entries.forEach {entry->
                    val active=filter==entry
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(9.dp))
                        .background(if(active) scheme.primaryContainer else Color.Transparent)
                        .selectable(active,role=Role.Tab,onClick={filter=entry}),contentAlignment=Alignment.Center) {
                        Text(entry.label,style=SettingsType.section,color=if(active) scheme.onPrimaryContainer else scheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
            // Routine metadata refreshes keep existing rows quiet. The empty
            // state already says Checking activity during the first load.
            Spacer(Modifier.height(2.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f),contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                filtered.forEach {(source,records)->
                    if(records.isNotEmpty() || source.error.isNotBlank() || source.partial) {
                        item(key="server:${source.server.id}") {
                            Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(source.server.label,Modifier.weight(1f),style=SettingsType.section,color=scheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text(if(source.connected) "${records.size}" else "Offline",style=SettingsType.detail,color=scheme.onSurfaceVariant)
                            }
                            if(source.error.isNotBlank()) Text(source.error,Modifier.padding(bottom=4.dp),style=SettingsType.detail,color=scheme.error,maxLines=2,overflow=TextOverflow.Ellipsis)
                            else if(source.partial) Text("Some activity could not be refreshed.",style=SettingsType.detail,color=scheme.onSurfaceVariant)
                        }
                        items(records,key={"${source.server.id}:${it.key}"}) {record->
                            val seen=record.state.notifiable && record.key in source.seen
                            Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surfaceVariant.copy(alpha=.45f))
                                .semantics {contentDescription="${record.label}, ${record.stateLabel}, ${record.sessionLabel}, ${source.server.label}"
                                    stateDescription=listOf(record.stateLabel,if(seen) "Seen" else "",record.progress?.let {"$it%"}.orEmpty()).filter {it.isNotBlank()}.joinToString(" · ")}
                                .clickable(enabled=source.connected,role=Role.Button){onSelect(ActivityTarget(source.server,record))}.padding(start=8.dp,end=4.dp,top=4.dp,bottom=4.dp),
                                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                ActivityIdentity(record,seen,Modifier.weight(1f))
                                Box(Modifier.width(68.dp),contentAlignment=Alignment.CenterEnd) {
                                    if(record.state.notifiable && !seen) TextButton(
                                        onClick={onSeen(ActivityTarget(source.server,record))},enabled=source.connected,
                                        modifier=Modifier.height(36.dp).semantics {contentDescription="Mark ${record.label} seen"},
                                        shape=RoundedCornerShape(8.dp),contentPadding=PaddingValues(0.dp),
                                        colors=ButtonDefaults.textButtonColors(contentColor=scheme.onSurfaceVariant)
                                    ) {Text("Mark seen",style=SettingsType.detail)}
                                }
                            }
                        }
                    }
                }
                if(filtered.all {it.second.isEmpty()} && sources.none {it.error.isNotBlank() || it.partial}) item {
                    Column(Modifier.fillMaxWidth().padding(vertical=20.dp,horizontal=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(if(sources.any {it.loading}) "Checking activity…" else "No activity to show",style=SettingsType.row)
                        Text("Programs appear here when they report a status.",style=SettingsType.detail,color=scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}


/** Measure the real text column first; the icon and rail share that exact height. */
@Composable private fun ActivityIdentity(record: ActivityRecord,seen: Boolean,modifier: Modifier) {
    val scheme=MaterialTheme.colorScheme
    val iconSlot=with(LocalDensity.current) {39.sp.toDp()}*(61f/48f)
    Layout(modifier=modifier,content={
        ActivityStateBar(record.state,seen,Modifier.semantics {testTag="Activity state ${record.key}"})
        PaneProgramTile(record.program,Modifier.semantics {testTag="Activity program ${record.key}"},large=true,rotation=0f)
        Column(verticalArrangement=Arrangement.spacedBy(1.dp)) {
            Text(record.label,style=SettingsType.row,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(listOf(record.message,record.context).filter {it.isNotBlank()}.distinct().joinToString(" · "),style=SettingsType.detail,
                color=scheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            record.progress?.let {progress->LinearProgressIndicator(progress={progress/100f},modifier=Modifier.fillMaxWidth().height(2.dp))}
        }
    }) {children,constraints->
        val railWidth=3.dp.roundToPx();val gap=8.dp.roundToPx()
        val slot=iconSlot.roundToPx().coerceAtMost((constraints.maxWidth-railWidth-2*gap).coerceAtLeast(0))
        val text=children[2].measure(Constraints(maxWidth=(constraints.maxWidth-railWidth-slot-2*gap).coerceAtLeast(0)))
        val height=text.height
        val iconWidth=(height*61f/48f).roundToInt().coerceAtMost(slot)
        val rail=children[0].measure(Constraints.fixed(railWidth,height))
        val icon=children[1].measure(Constraints.fixed(iconWidth,height))
        layout(constraints.maxWidth,height) {
            rail.placeRelative(0,0)
            icon.placeRelative(railWidth+gap+(slot-iconWidth)/2,0)
            text.placeRelative(railWidth+gap+slot+gap,0)
        }
    }
}

/** Working and unseen attention animate; phase is read only while drawing. */
@Composable internal fun ActivityStateBar(state: ActivityState,seen: Boolean,modifier: Modifier) {
    val scheme=MaterialTheme.colorScheme
    val working=state==ActivityState.WORKING
    val needsYou=state==ActivityState.BLOCKED && !seen
    val phase=rememberWorkingPhase(working || needsYou)
    val color=when {
        seen->scheme.onSurfaceVariant.copy(alpha=.22f)
        state==ActivityState.BLOCKED->if(LocalTerminalPalette.current.dark) Color(0xffe6a947) else Color(0xffb77710)
        state==ActivityState.ERROR->scheme.error
        working || state==ActivityState.DONE->scheme.primary
        else->scheme.onSurfaceVariant.copy(alpha=.22f)
    }
    Canvas(modifier) {
        val radius=CornerRadius(size.width/2)
        val brightness=if(working || needsYou) (.725-.275*cos(phase.value*2*PI)).toFloat() else 1f
        drawWorkingBar(color.copy(alpha=color.alpha*brightness),Offset.Zero,size,
            vertical=true,radius=radius)
    }
}
