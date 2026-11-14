package dev.shulhi.rex

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

internal data class PortForward(
    val id: String, val server: String, val host: String, val port: Int,
    val phase: String="connecting", val address: String="", val error: String=""
) {
    val url get()="http://$address"
    val target get()=if(':' in host) "[$host]:$port" else "$host:$port"
}
internal object PortForwards {
    val items=mutableStateListOf<PortForward>()
    var notice by androidx.compose.runtime.mutableStateOf<String?>(null)
    fun start(context: Context,server: ServerProfile,session: String,host: String,port: Int,localPort: Int) {
        val intent=Intent(context,PortForwardService::class.java).setAction(PortForwardService.ADD)
            .putExtra("id",UUID.randomUUID().toString()).putExtra("server",server.label).putExtra("endpoint",server.endpoint)
            .putExtra("session",session).putExtra("host",host).putExtra("port",port).putExtra("local_port",localPort)
        try {context.startForegroundService(intent)} catch(e: Exception) {notice=e.message ?: "Could not start port forwarding"}
    }
    fun stop(context: Context,id: String) {context.startService(Intent(context,PortForwardService::class.java).setAction(PortForwardService.CLOSE).putExtra("id",id))}
}
/** Owns the tunnel handles independently of the terminal view while the browser is open. */
class PortForwardService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private data class Worker(val handle: Long,val job: Job)
    private val workers=mutableMapOf<String,Worker>()
    private val notifications by lazy {getSystemService(NotificationManager::class.java)}
    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,"Port forwarding",NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false);setSound(null,null)
        })
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action==STOP) {stopSelf();return START_NOT_STICKY}
        if(intent?.action==CLOSE) {
            val id=intent.getStringExtra("id")
            workers.remove(id)?.let {it.job.cancel();NativeBridge.forwardDestroy(it.handle)}
            PortForwards.items.removeAll {it.id==id}
            if(workers.isEmpty()) stopSelf() else ongoing()
            return START_NOT_STICKY
        }
        if(intent?.action!=ADD) {stopSelf();return START_NOT_STICKY}
        try {
            val notification=notification()
            if(Build.VERSION.SDK_INT>=34) startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION,notification)
            require(workers.size<8) {"Stop a forward before adding another (maximum 8)"}
            val id=requireNotNull(intent.getStringExtra("id"))
            if(id in workers) return START_NOT_STICKY
            val host=requireNotNull(intent.getStringExtra("host"))
            val request=JSONObject().put("id",id).put("endpoint",intent.getStringExtra("endpoint"))
                .put("session",intent.getStringExtra("session")).put("host",host)
                .put("port",intent.getIntExtra("port",0)).put("local_port",intent.getIntExtra("local_port",0))
            val handle=NativeBridge.forwardCreate(request.toString())
            check(handle!=0L) {"Could not start forward"}
            PortForwards.items.add(PortForward(id,intent.getStringExtra("server").orEmpty(),host,intent.getIntExtra("port",0)))
            val job=scope.launch {
                while(isActive) {
                    val state=withContext(Dispatchers.IO) {JSONObject(NativeBridge.forwardPoll(handle))}
                    val index=PortForwards.items.indexOfFirst {it.id==id}
                    if(index<0) break
                    val item=PortForwards.items[index]
                    val next=item.copy(phase=state.optString("phase","failed"),address=state.optString("address"),error=state.optString("error"))
                    if(next!=item) {PortForwards.items[index]=next;ongoing()}
                    delay(400)
                }
            }
            workers[id]=Worker(handle,job)
            ongoing()
        } catch(e: Exception) {
            PortForwards.notice=e.message ?: "Could not start port forwarding"
            if(workers.isEmpty()) stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,PortForwardService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Rexling port forwarding")
            .setContentText("${workers.size} forwards · tap Rexling to manage").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"Stop all",stop).build()).build()
    }
    private fun ongoing() {notifications.notify(NOTIFICATION,notification())}
    override fun onTaskRemoved(rootIntent: Intent?) {stopSelf()}
    override fun onDestroy() {
        workers.values.forEach {it.job.cancel();NativeBridge.forwardDestroy(it.handle)}
        workers.clear();PortForwards.items.clear();scope.cancel();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder?=null
    companion object {
        const val ADD="dev.shulhi.rex.forward.ADD"
        const val CLOSE="dev.shulhi.rex.forward.CLOSE"
        const val STOP="dev.shulhi.rex.forward.STOP"
        private const val CHANNEL="port_forwards"
        private const val NOTIFICATION=4401
    }
}
