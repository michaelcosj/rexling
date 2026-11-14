package dev.shulhi.rex

import android.content.SharedPreferences
import org.json.JSONObject

/** Shared UI/service policy, called on the main thread. Episodes survive reconnects. */
internal object CodexAttentionPolicy {
    const val ENABLED="experimental_codex_title_attention"
    fun apply(prefs: SharedPreferences,endpoint: String,records: List<ActivityRecord>,authoritative: Boolean): List<ActivityRecord> {
        val enabled=prefs.getBoolean(ENABLED,false)
        val key="codex_attention_episodes:$endpoint"
        val before=prefs.getString(key,"{}").orEmpty()
        if(!enabled && before=="{}") return records
        val episodes=runCatching {JSONObject(before)}.getOrDefault(JSONObject())
        val result=records.map {record->
            val signal=record.state==ActivityState.WORKING && record.codexTitleEligible && record.codexTitleAttention
            var entry=episodes.optJSONObject(record.key)
            if(authoritative && (enabled && record.codexTitleEligible || entry!=null)) {
                entry=entry ?: JSONObject().put("epoch",0).put("active",false)
                if(signal && !entry.optBoolean("active")) entry.put("epoch",entry.optLong("epoch")+1)
                entry.put("active",signal);episodes.put(record.key,entry)
            }
            val epoch=if(!authoritative && entry?.optBoolean("active")!=true) (entry?.optLong("epoch") ?: 0)+1 else entry?.optLong("epoch",1) ?: 1
            if(enabled && signal) record.copy(state=ActivityState.BLOCKED,kind="",
                attentionVersion="codex-title:${record.attentionVersion}:${epoch.coerceAtLeast(1)}") else record
        }
        if(authoritative) {
            while(episodes.length()>2048) episodes.remove(episodes.keys().next())
            val after=episodes.toString()
            if(after!=before) prefs.edit().putString(key,after).apply()
        }
        return result
    }
}

internal data class WatchedPane(val server: String,val endpoint: String,val session: String,val block: String) {
    fun contains(profile: ServerProfile,record: ActivityRecord)=profile.id==server && profile.endpoint==endpoint && record.session==session && record.block==block
}


internal object ActivityFocusPolicy {
    fun acknowledge(profile: ServerProfile,records: List<ActivityRecord>,watched: WatchedPane?,seen: (ActivityRecord)->Boolean)=
        records.filter {it.notifiable && watched?.contains(profile,it)==true && !seen(it)}
    fun unread(records: List<ActivityRecord>,seen: (ActivityRecord)->Boolean)=records.count {it.notifiable && !seen(it)}
    fun watched(profile: ServerProfile,state: AppState,foreground: Boolean,obscured: Boolean,hasScreen: Boolean,navigating: Boolean,
        interactive: Boolean,locked: Boolean): WatchedPane? = if(foreground && !obscured && hasScreen && !navigating && interactive && !locked &&
            state.connected && state.selectedSession.isNotBlank() && state.selectedBlock.isNotBlank())
        WatchedPane(profile.id,profile.endpoint,state.selectedSession,state.selectedBlock) else null
}
