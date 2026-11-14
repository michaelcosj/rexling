package dev.shulhi.rex

import org.json.JSONObject

data class TerminalProgress(val state: String,val percent: Int?) {
    val busy: Boolean get()=state=="set" || state=="indeterminate"
    val label: String get()=when(state) {"pause"->"Paused";"error"->"Failed";else->"Working"}
    val description: String get()=listOf(label,percent?.let {"$it%"}.orEmpty()).filter {it.isNotBlank()}.joinToString(" · ")
    companion object {
        fun parse(value: JSONObject?): TerminalProgress? {
            val state=value?.optString("state") ?: return null
            if(state !in setOf("set","indeterminate","pause","error")) return null
            val percent=if(state=="indeterminate" || value.isNull("progress")) null else value.optInt("progress",-1).takeIf {it in 0..100}
            return TerminalProgress(state,percent)
        }
    }
}
