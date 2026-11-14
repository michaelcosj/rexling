package dev.shulhi.rex

import android.app.NotificationManager
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PortForwardTest {
    @get:Rule val compose=createComposeRule()
    @Test fun addFormValidatesPortsAndKeepsTheSelectedServer() {
        val chosen=AtomicReference<Triple<String,Int,Int>>()
        compose.setContent {MaterialTheme {
            PortForwardSheet(ServerProfile.create("Test server","http://127.0.0.1:19701"),"session:test",true,null,{}) {host,port,local->chosen.set(Triple(host,port,local))}
        }}
        compose.onNodeWithContentDescription("Add port forward").performClick()
        compose.onNodeWithText("Remote host").assertDoesNotExist()
        compose.onNodeWithText("Phone port").assertDoesNotExist()
        compose.onNodeWithText("Forward",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Enter valid ports from 1 to 65535.").assertIsDisplayed()
        compose.onNodeWithText("Remote port").performTextInput("3000")
        compose.onNodeWithText("Forward",useUnmergedTree=true).performClick()
        compose.runOnIdle {assertEquals(Triple("127.0.0.1",3000,0),chosen.get())}
    }
    @Test fun serviceKeepsARealForwardAliveAndStopsIt() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val server=args.getString("forward_server")
        val session=args.getString("forward_session")
        assumeTrue("Requires an owned disposable Rex session",server!=null && session!=null)
        val context=instrumentation.targetContext
        instrumentation.runOnMainSync {PortForwards.start(context,ServerProfile.create("Forward test",server!!),session!!,"127.0.0.1",19702,0)}
        var id=""
        try {
            val deadline=SystemClock.uptimeMillis()+15000
            var active: PortForward?=null
            while(SystemClock.uptimeMillis()<deadline) {
                val item=AtomicReference<PortForward>()
                instrumentation.runOnMainSync {item.set(PortForwards.items.firstOrNull())}
                val current=item.get()
                if(current!=null) {id=current.id;assertNotEquals(current.error,"failed",current.phase);if(current.phase=="active") {active=current;break}}
                SystemClock.sleep(100)
            }
            assertNotNull("Forward did not activate",active)
            val connection=URL(active!!.url).openConnection() as HttpURLConnection
            connection.connectTimeout=5000;connection.readTimeout=5000
            try {assertEquals(200,connection.responseCode);assertTrue(connection.inputStream.bufferedReader().readText().contains("Directory listing"))}
            finally {connection.disconnect()}
            val notifications=context.getSystemService(NotificationManager::class.java).activeNotifications
            assertTrue(notifications.any {it.notification.channelId=="port_forwards"})
        } finally {
            instrumentation.runOnMainSync {if(id.isNotEmpty()) PortForwards.stop(context,id) else context.stopService(android.content.Intent(context,PortForwardService::class.java))}
        }
    }
}
