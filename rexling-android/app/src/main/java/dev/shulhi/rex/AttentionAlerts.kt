package dev.shulhi.rex

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.app.NotificationManager
import org.json.JSONObject
import java.util.UUID

/** Consume foreground updates and baseline old completions without replaying them. */
internal class AttentionLedger(saved: String?=null) {
    private val stamps=linkedMapOf<String,String>()
    private val baselinedServers=mutableSetOf<String>()
    private val observedThisRun=mutableSetOf<String>()
    init {runCatching {JSONObject(saved ?: "{}").let {o->o.keys().forEach {key->stamps[key]=o.getString(key)}}}}
    fun key(server: ServerProfile,record: ActivityRecord)=listOf(server.id,server.endpoint,record.key).joinToString("") {"${it.length}:$it"}
    fun observe(server: ServerProfile,records: List<ActivityRecord>,background: Boolean,complete: Boolean=true,seen: (ActivityRecord)->Boolean): List<ActivityRecord> {
        val alerts=mutableListOf<ActivityRecord>()
        val source=listOf(server.id,server.endpoint).joinToString("") {"${it.length}:$it"}
        records.forEach {record->
            val version=record.attentionVersion.ifBlank {listOf(record.state.wire,record.kind,record.updated,record.message).joinToString("\u0000")}
            val stamp=when {record.state==ActivityState.DONE->"done:$version";record.notifiable->"attention:$version";else->"state:${record.state.wire}"}
            val key=key(server,record)
            if(stamps[key]!=stamp) {
                stamps.remove(key);stamps[key]=stamp
                val freshCompletion=record.state!=ActivityState.DONE || source in baselinedServers || key in observedThisRun
                if(record.notifiable && freshCompletion && background && !seen(record)) alerts+=record
            }
            observedThisRun+=key
        }
        if(complete) baselinedServers+=source
        while(stamps.size>2048) {val oldest=stamps.keys.first();stamps.remove(oldest);observedThisRun.remove(oldest)}
        return alerts
    }
    fun encode()=JSONObject(stamps.toMap()).toString()
}

internal data class AlertDestination(val serverId: String,val endpoint: String,val session: String,val block: String,val attentionKey: String,val attentionVersion: String) {
    fun json()=JSONObject().put("server",serverId).put("endpoint",endpoint).put("session",session).put("block",block).put("key",attentionKey).put("version",attentionVersion)
    companion object {
        fun parse(o: JSONObject)=AlertDestination(o.getString("server"),o.getString("endpoint"),o.getString("session"),o.getString("block"),o.optString("key"),o.optString("version"))
    }
}

/** An exported launcher accepts only opaque, one-use tokens issued by this app. */
internal class AlertDestinations(private val preferences: SharedPreferences) {
    private fun entries()=runCatching {JSONObject(preferences.getString("alert_destinations","{}")!!)}.getOrDefault(JSONObject())
    fun issue(target: AlertDestination): Uri {
        val entries=entries();while(entries.length()>=128) entries.remove(entries.keys().next())
        val token=UUID.randomUUID().toString();entries.put(token,target.json())
        preferences.edit().putString("alert_destinations",entries.toString()).apply()
        return Uri.Builder().scheme("rexlink-alert").authority("open").appendPath(token).build()
    }
    fun consume(uri: Uri?): AlertDestination? {
        if(uri?.scheme!="rexlink-alert" || uri.authority!="open" || uri.pathSegments.size!=1) return null
        val entries=entries();val target=entries.optJSONObject(uri.lastPathSegment ?: return null) ?: return null
        entries.remove(uri.lastPathSegment);preferences.edit().putString("alert_destinations",entries.toString()).apply()
        return runCatching {AlertDestination.parse(target)}.getOrNull()
    }
}

internal object BackgroundAlerts {
    const val ENABLED="background_alerts"
    const val MONITOR_CHANNEL="background_monitor"
    const val ATTENTION_CHANNEL="attention"
    val foreground=kotlinx.coroutines.flow.MutableStateFlow(false)
    fun preferences(context: Context)=context.getSharedPreferences("rex",0)
    fun allowed(context: Context): Boolean {
        val manager=context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && (manager.getNotificationChannel(ATTENTION_CHANNEL)?.importance ?: 1)!=NotificationManager.IMPORTANCE_NONE
    }
    fun servers(preferences: SharedPreferences): List<ServerProfile> {
        val desired=preferences.getStringSet("connected_servers",emptySet()).orEmpty()
        return SavedServers.decode(preferences.getString("servers",null),preferences.getString("endpoint","").orEmpty()).filter {it.id in desired}
    }
    /** Called only from visible UI, never from a receiver or a background restart. */
    fun reconcile(context: Context) {
        val prefs=preferences(context)
        if(prefs.getBoolean(ENABLED,false) && allowed(context) && servers(prefs).isNotEmpty()) {
            context.startForegroundService(Intent(context,BackgroundMonitorService::class.java))
        } else context.stopService(Intent(context,BackgroundMonitorService::class.java))
    }
}
