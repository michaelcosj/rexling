package dev.shulhi.rex

import org.json.JSONObject
import java.time.Instant

enum class ActivityState(val wire: String,val label: String,val priority: Int) {
    BLOCKED("blocked","Needs you",0),ERROR("error","Failed",1),DONE("done","Finished",3),WORKING("working","Working",2),IDLE("idle","Idle",4)
}
internal val ActivityState.notifiable: Boolean get()=this==ActivityState.BLOCKED || this==ActivityState.ERROR || this==ActivityState.DONE

data class ActivityRecord(val session: String,val sessionLabel: String,val window: String,val windowLabel: String,
    val block: String,val blockLabel: String,val id: String,val title: String,val message: String,val state: ActivityState,
    val kind: String,val progress: Int?,val updated: String,val program: ProgramIdentity,val attentionKey: String="",val attentionVersion: String="",val codexTitleEligible: Boolean=false,val codexTitleAttention: Boolean=false,val terminalProgress: TerminalProgress?=null,val progressOnly: Boolean=false) {
    val notifiable: Boolean get()=state.notifiable && !progressOnly
    val key: String =listOf(session,block,id).joinToString("") {"${it.length}:$it"}
    val label: String get()=program.paneTitle(blockLabel,title)
    val context: String get()=listOf(sessionLabel,windowLabel.takeUnless {generatedWindow.matches(it)}.orEmpty(),if(progressOnly) "" else id).filter {it.isNotBlank()}.joinToString(" · ")
    val timestamp: Long =runCatching {Instant.parse(updated).toEpochMilli()}.getOrDefault(0)
    val stateLabel: String get()=if(progressOnly && terminalProgress!=null) terminalProgress.label else if(state==ActivityState.BLOCKED) when(kind) {"permission"->"Approval";"question"->"Question";"auth"->"Sign in";else->state.label} else state.label
    companion object {
        private val generatedWindow=Regex("(?i)window(?:[ \t_-]+)[0-9a-f]{6,}(?:-[0-9a-f]+)*")
        private fun JSONObject.clean(name: String,max: Int)=optString(name).filterNot {it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt()}.take(max)
        fun parse(value: JSONObject): ActivityRecord? {
            val session=value.clean("session",256);val block=value.clean("block",256)
            val state=ActivityState.entries.firstOrNull {it.wire==value.optString("state")} ?: return null
            if(session.isBlank() || block.isBlank()) return null
            val progress=if(value.isNull("progress") || (!value.optBoolean("progressOnly") && state !in listOf(ActivityState.WORKING,ActivityState.BLOCKED))) null else value.optInt("progress",-1).takeIf {it in 0..100}
            return ActivityRecord(session,value.clean("sessionLabel",256),value.clean("window",256),value.clean("windowLabel",256),
                block,value.clean("blockLabel",256),value.clean("id",128),value.clean("title",256),value.clean("message",2048),state,
                value.clean("kind",32),progress,value.clean("updatedAt",64),ProgramIdentity.resolve(listOf(value.clean("app",64),value.clean("owner",64))),value.clean("attentionKey",1024),value.clean("attentionVersion",64),value.optBoolean("codexTitleEligible"),value.optBoolean("codexTitleAttention"),TerminalProgress.parse(value.optJSONObject("terminalProgress")),value.optBoolean("progressOnly"))
        }
    }
}
internal data class ActivitySource(val server: ServerProfile,val connected: Boolean,val loading: Boolean,val error: String,val partial: Boolean,val items: List<ActivityRecord>,val seen: Set<String> = emptySet())
internal data class ActivityTarget(val server: ServerProfile,val record: ActivityRecord)
internal enum class ActivityFilter(val label: String) {ALL("All"),ATTENTION("Needs you"),WORKING("Working"),FINISHED("Finished")}
internal object ActivityCatalog {
    fun records(source: ActivitySource,filter: ActivityFilter): List<ActivityRecord> = source.items.filter {record->when(filter) {
        ActivityFilter.ALL->true;ActivityFilter.ATTENTION->record.state in listOf(ActivityState.BLOCKED,ActivityState.ERROR)
        ActivityFilter.WORKING->record.state==ActivityState.WORKING;ActivityFilter.FINISHED->record.state==ActivityState.DONE
    }}.distinctBy {it.key}.sortedWith(compareBy<ActivityRecord> {if(it.key in source.seen) it.state.priority+5 else it.state.priority}.thenByDescending {it.timestamp}.thenBy {it.key})
}
