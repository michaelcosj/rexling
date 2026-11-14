package dev.shulhi.rex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkTest {
    @Test fun embeddedTailscaleStartsWithoutVpnPermissionAndKeepsControlServerState() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        // A non-listening local control server exercises Android startup without enrolling a node.
        try {
            assertNull(NetworkSettings.save(true,"http://127.0.0.1:9"))
            val deadline=System.currentTimeMillis()+15000
            var result=JSONObject()
            while(System.currentTimeMillis()<deadline) {
                result=JSONObject(NativeBridge.networkCommand("{\"type\":\"status\"}"))
                if(result.has("tailscale")) break
                Thread.sleep(200)
            }
            assertFalse(result.toString(),result.has("error"))
            assertTrue(result.toString(),result.has("tailscale"))
            assertEquals("NeedsLogin",result.getJSONObject("tailscale").getString("BackendState"))
            assertTrue(context.filesDir.resolve("tailscale").listFiles().orEmpty().any {it.isDirectory})
        } finally {NetworkSettings.save(false,"")}
    }
    @Test fun sshCarriesTheRealRexControlProtocol() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val fingerprint=args.getString("ssh_fingerprint")
        org.junit.Assume.assumeTrue("Requires disposable SSH/Rex fixture",fingerprint!=null)
        val endpoint="ssh://tester@10.0.2.2:19703/tmp/rexling-ssh-test.sock"
        NetworkSettings.save(false,"")
        NetworkSettings.trust("10.0.2.2:19703",fingerprint!!)
        val handle=NativeBridge.create()
        try {
            assertEquals("",NativeBridge.command(handle,JSONObject().put("type","connect").put("server",endpoint).toString()))
            val deadline=System.currentTimeMillis()+15000
            var value=JSONObject()
            while(System.currentTimeMillis()<deadline) {
                val raw=NativeBridge.poll(handle,0)
                if(raw.isBlank()) {Thread.sleep(100);continue}
                value=JSONObject(raw)
                if(value.optString("phase")=="connected") break
                Thread.sleep(100)
            }
            assertEquals(value.toString(),"connected",value.optString("phase"))
        } finally {NativeBridge.destroy(handle)}
    }
    @Test fun privateKeysAreEncryptedInPreferences() = runBlocking {
        val endpoint="ssh://key-test@127.0.0.1:2222"
        val pair=java.security.KeyPairGenerator.getInstance("RSA").apply {initialize(2048)}.generateKeyPair()
        val text="-----BEGIN PRIVATE KEY-----\n"+android.util.Base64.encodeToString(pair.private.encoded,android.util.Base64.NO_WRAP)+"\n-----END PRIVATE KEY-----\n"
        try {
            assertNull(NetworkSettings.importKey(endpoint,text,""))
            assertTrue(NetworkSettings.hasKey(endpoint))
            val stored=InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("network",0).getString("keys","")!!
            assertFalse(stored.contains("PRIVATE KEY"));assertFalse(stored.contains(text))
        } finally {NetworkSettings.removeKey(endpoint)}
    }
    @Test fun invalidControlServerAndMalformedKeyAreRejected() = runBlocking {
        assertNotNull(NetworkSettings.save(true,"https://user:secret@host"))
        val endpoint="ssh://tester@127.0.0.1:2222"
        assertNotNull(NetworkSettings.importKey(endpoint,"not a private key",""))
        assertFalse(NetworkSettings.hasKey(endpoint))
    }
}
