package dev.shulhi.rex

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject

/** Metadata-only native handles: no selected pane, terminal stream, control, or rendering. */
class BackgroundMonitorService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val preferences by lazy {BackgroundAlerts.preferences(this)}
    private val notifications by lazy {getSystemService(NotificationManager::class.java)}
    private val ledger by lazy {AttentionLedger(preferences.getString("attention_ledger",null))}
    private val destinations by lazy {AlertDestinations(preferences)}
    private class Monitor(var profile: ServerProfile) {var job: Job?=null;@Volatile var handle=0L;var phase="connecting";var records: List<ActivityRecord> = emptyList();var complete=false}
    private val monitors=mutableMapOf<String,Monitor>()
    private val posted=linkedMapOf<String,Pair<ServerProfile,ActivityRecord>>()
    private var running=false
    private var lastOngoing=""
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->scope.launch {
        if(key in setOf(BackgroundAlerts.ENABLED,"servers","connected_servers")) refresh()
        else if(key?.startsWith("activity_seen:")==true) cancelSeen()
        else if(key==CodexAttentionPolicy.ENABLED) monitors.values.forEach {accept(it,it.records,it.complete)}
    }}
    private val screenReceiver=object:BroadcastReceiver() {override fun onReceive(context: Context,intent: Intent) {if(!background()) clearAlerts()}}
    private val network by lazy {NetworkMonitor(this) {change->scope.launch {
        monitors.values.forEach {monitor->if(monitor.handle!=0L) NativeBridge.command(monitor.handle,JSONObject().put("type","network").put("online",change.available).put("force",change.reroute).toString())}
    }}}
    override fun onCreate() {
        super.onCreate()
        scope.launch {NetworkSettings.revision.drop(1).collectLatest {monitors.values.forEach {monitor->if(monitor.handle!=0L) NativeBridge.command(monitor.handle,"{\"type\":\"retry\"}")}}}
        notifications.createNotificationChannel(NotificationChannel(BackgroundAlerts.MONITOR_CHANNEL,"Background monitoring",NotificationManager.IMPORTANCE_LOW).apply {
            description="Keeps your connected servers available for attention alerts";setShowBadge(false);setSound(null,null)
        })
        notifications.createNotificationChannel(NotificationChannel(BackgroundAlerts.ATTENTION_CHANNEL,"Task updates",NotificationManager.IMPORTANCE_HIGH).apply {
            description="A task finishes, needs input, approval, sign-in, or fails"
        })
        preferences.registerOnSharedPreferenceChangeListener(listener)
        val filter=IntentFilter().apply {addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_USER_PRESENT)}
        if(Build.VERSION.SDK_INT>=33) registerReceiver(screenReceiver,filter,RECEIVER_NOT_EXPORTED) else registerReceiver(screenReceiver,filter)
        scope.launch {BackgroundAlerts.foreground.collectLatest {if(!background()) clearAlerts()}}
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action==STOP) {
            preferences.edit().putBoolean(BackgroundAlerts.ENABLED,false).apply();stopSelf();return START_NOT_STICKY
        }
        if(!preferences.getBoolean(BackgroundAlerts.ENABLED,false) || !BackgroundAlerts.allowed(this) || BackgroundAlerts.servers(preferences).isEmpty()) {
            stopSelf();return START_NOT_STICKY
        }
        if(!running) {
            val notification=ongoing("Connecting to your servers…")
            if(Build.VERSION.SDK_INT>=34) startForeground(ONGOING_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(ONGOING_ID,notification)
            running=true;network.start()
        }
        refresh();return START_NOT_STICKY
    }
    private fun refresh() {
        if(!running) return
        val profiles=BackgroundAlerts.servers(preferences)
        if(!preferences.getBoolean(BackgroundAlerts.ENABLED,false) || !BackgroundAlerts.allowed(this) || profiles.isEmpty()) {stopSelf();return}
        monitors.keys.toList().forEach {id->
            val monitor=monitors.getValue(id)
            if(profiles.none {it.id==id && it.endpoint==monitor.profile.endpoint}) {
                monitors.remove(id);monitor.job?.cancel();cancelServer(monitor.profile)
            }
        }
        profiles.forEach {profile->if(profile.id in monitors) monitors.getValue(profile.id).profile=profile else {
            val monitor=Monitor(profile);monitors[profile.id]=monitor
            monitor.job=scope.launch(Dispatchers.IO) {
                while(isActive) {
                var handle=0L
                try {
                    handle=NativeBridge.create();check(handle!=0L);monitor.handle=handle
                    NativeBridge.command(handle,"{\"type\":\"monitor\"}")
                    NativeBridge.command(handle,JSONObject().put("type","connect").put("server",profile.endpoint).toString())
                    var revision=0L
                    while(isActive) {
                        val next=NativeBridge.awaitUpdate(handle,revision,250)
                        check(next>=0) {"Monitor closed"}
                        if(next<=revision) continue
                        val raw=NativeBridge.poll(handle,revision);if(raw.isBlank()) continue
                        val value=JSONObject(raw);revision=value.getLong("revision")
                        val array=value.optJSONArray("activity")
                        val records=if(array==null) null else (0 until array.length()).mapNotNull {ActivityRecord.parse(array.getJSONObject(it))}
                        withContext(Dispatchers.Main.immediate) {
                            if(monitors[profile.id]===monitor) {
                                monitor.phase=value.optString("phase")
                                if(monitor.phase=="connected" && records!=null && !value.optBoolean("activityLoading")) accept(monitor,records,!value.optBoolean("activityPartial") && value.optString("activityError").isBlank())
                                updateOngoing()
                            }
                        }
                    }
                } catch(cancelled: CancellationException) {throw cancelled}
                catch(_: Exception) {
                    withContext(Dispatchers.Main.immediate) {if(monitors[profile.id]===monitor) {monitor.phase="reconnecting";updateOngoing()}}
                } finally {monitor.handle=0;if(handle!=0L) NativeBridge.destroy(handle)}
                delay(5000)
                }
            }
        }}
        updateOngoing()
    }
    private fun background()=!BackgroundAlerts.foreground.value || !getSystemService(PowerManager::class.java).isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun seen(profile: ServerProfile,record: ActivityRecord): Boolean = record.attentionKey.isNotBlank() && runCatching {
        JSONObject(preferences.getString("activity_seen:${profile.endpoint}","{}")!!).optString(record.attentionKey)==record.attentionVersion
    }.getOrDefault(false)
    private fun accept(monitor: Monitor,raw: List<ActivityRecord>,complete: Boolean) {
        monitor.records=raw;monitor.complete=complete
        val records=CodexAttentionPolicy.apply(preferences,monitor.profile.endpoint,raw,background())
        val before=ledger.encode()
        val alerts=ledger.observe(monitor.profile,records,background(),complete=complete) {seen(monitor.profile,it)}
        val after=ledger.encode();if(before!=after) preferences.edit().putString("attention_ledger",after).apply()
        // Reconnection/partial results must not dismiss an outstanding request.
        posted.toMap().forEach {(key,target)->if(target.first.id==monitor.profile.id && target.first.endpoint==monitor.profile.endpoint) {
            val current=records.firstOrNull {it.key==target.second.key}
            // A finished report may disappear immediately; keep its notification until seen.
            if(seen(monitor.profile,target.second) || (target.second.state!=ActivityState.DONE &&
                ((current==null && complete) || (current!=null && (!current.notifiable || seen(monitor.profile,current)))))) cancel(key)
        }}
        if(!background()) {clearAlerts();return}
        alerts.forEach {post(monitor.profile,it)}
    }
    private fun post(profile: ServerProfile,record: ActivityRecord) {
        if(!BackgroundAlerts.allowed(this)) return
        val key=ledger.key(profile,record)
        if(posted[key]?.second?.attentionVersion==record.attentionVersion && record.attentionVersion.isNotBlank()) return
        if(key !in posted && posted.size>=32) cancel(posted.keys.first())
        val uri=destinations.issue(AlertDestination(profile.id,profile.endpoint,record.session,record.block,record.attentionKey,record.attentionVersion))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).setData(uri).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title="${record.stateLabel} · ${record.label.ifBlank {record.program.label}}"
        val text=listOf(profile.label,record.sessionLabel.ifBlank {record.session}).joinToString(" · ")
        val public=Notification.Builder(this,BackgroundAlerts.ATTENTION_CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(if(record.state==ActivityState.DONE) "Rexling task finished" else "Rexling needs your attention").build()
        val notification=Notification.Builder(this,BackgroundAlerts.ATTENTION_CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(listOf(text,record.message).filter {it.isNotBlank()}.joinToString("\n")))
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public).build()
        notifications.notify(key,ATTENTION_ID,notification);posted[key]=profile to record
    }
    private fun cancel(key: String) {notifications.cancel(key,ATTENTION_ID);posted.remove(key)}
    private fun clearAlerts() {posted.keys.toList().forEach(::cancel)}
    private fun cancelServer(profile: ServerProfile) {posted.filterValues {it.first.id==profile.id}.keys.toList().forEach(::cancel)}
    private fun cancelSeen() {posted.toMap().forEach {(key,target)->if(seen(target.first,target.second)) cancel(key)}}
    private fun updateOngoing() {
        if(!running) return
        val connected=monitors.values.count {it.phase=="connected"};val total=monitors.size
        val title=if(connected==total && total>0) "Monitoring $total ${if(total==1) "server" else "servers"}" else "Connected to $connected of $total servers · reconnecting"
        if(lastOngoing!=title) {lastOngoing=title;notifications.notify(ONGOING_ID,ongoing(title))}
    }
    private fun ongoing(title: String): Notification {
        val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java).setAction("dev.shulhi.rex.OPEN_MONITOR").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,2,Intent(this,BackgroundMonitorService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,BackgroundAlerts.MONITOR_CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Rexling · $title")
            .setContentText("Task completions and attention alerts").setContentIntent(open).setOngoing(true).setShowWhen(false).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null,"Stop",stop).build()).build()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {stopSelf()}
    override fun onDestroy() {
        running=false;preferences.unregisterOnSharedPreferenceChangeListener(listener);unregisterReceiver(screenReceiver);network.stop()
        monitors.clear();scope.cancel();clearAlerts();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder?=null
    companion object {const val STOP="dev.shulhi.rex.STOP_MONITOR";const val ONGOING_ID=7100;const val ATTENTION_ID=7101}
}
