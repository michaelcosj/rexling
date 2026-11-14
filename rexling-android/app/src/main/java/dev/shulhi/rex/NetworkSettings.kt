package dev.shulhi.rex

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class RexApplication: Application() {
    override fun onCreate() {super.onCreate(); NetworkSettings.initialize(this)}
}

/** Keys are encrypted individually with an app-only, non-exportable Android Keystore key. */
internal object NetworkSettings {
    private lateinit var context: Application
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutex=Mutex()
    val status=MutableStateFlow(JSONObject())
    val revision=MutableStateFlow(0L)
    private val preferences get()=context.getSharedPreferences("network",Context.MODE_PRIVATE)
    val builtIn get()=preferences.getBoolean("built_in",false)
    val controlUrl get()=preferences.getString("control_url","").orEmpty()
    private fun vaultKey(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey("rexling-ssh",null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("rexling-ssh",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(text: String): String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,vaultKey())}
        return Base64.encodeToString(cipher.iv+cipher.doFinal(text.toByteArray()),Base64.NO_WRAP)
    }
    private fun decrypt(text: String): String {
        val bytes=Base64.decode(text,Base64.NO_WRAP)
        return String(Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,vaultKey(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))}.doFinal(bytes.copyOfRange(12,bytes.size)))
    }
    private fun keys()=JSONObject(preferences.getString("keys","{}")!!)
    fun hasKey(endpoint: String)=keys().has(endpoint)
    private fun command(type: String,body: JSONObject=JSONObject()): JSONObject = JSONObject(NativeBridge.networkCommand(body.put("type",type).toString()))
    fun initialize(application: Context) {
        context=application.applicationContext as Application
        // Install configuration before any terminal or monitoring service can connect.
        interfaces()
        configure()
        scope.launch {while(isActive) {runCatching {status.value=command("status")};delay(1500)}}
        val manager=context.getSystemService(android.net.ConnectivityManager::class.java)
        runCatching {manager.registerDefaultNetworkCallback(object: android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {scope.launch {interfaces()}}
            override fun onLost(network: android.net.Network) {scope.launch {interfaces()}}
            override fun onLinkPropertiesChanged(network: android.net.Network,properties: android.net.LinkProperties) {scope.launch {interfaces()}}
        })}
    }
    private fun interfaces() {
        val list=JSONArray()
        runCatching {NetworkInterface.getNetworkInterfaces()?.toList()?.forEach {item->
            var flags=0
            if(item.isUp) flags=flags or 1
            if(item.interfaceAddresses.any {it.broadcast!=null}) flags=flags or 2
            if(item.isLoopback) flags=flags or 4
            if(item.isPointToPoint) flags=flags or 8
            if(item.supportsMulticast()) flags=flags or 16
            list.put(JSONObject().put("name",item.name).put("index",item.index).put("mtu",item.mtu).put("flags",flags)
                .put("addresses",JSONArray(item.interfaceAddresses.map {"${it.address.hostAddress?.substringBefore('%')}/${it.networkPrefixLength}"})))
        }}
        command("interfaces",JSONObject().put("interfaces",list))
    }
    private fun configure(): String? {
        val ssh=JSONObject();val saved=keys()
        saved.keys().forEach {endpoint->runCatching {ssh.put(endpoint,JSONObject().put("key",decrypt(saved.getString(endpoint))))}}
        val config=JSONObject().put("built_in",builtIn).put("control_url",controlUrl).put("directory",context.filesDir.resolve("tailscale").absolutePath)
            .put("ssh",ssh).put("trusted",JSONObject(preferences.getString("trusted","{}")!!))
        val error=command("configure",JSONObject().put("config",config)).optString("error").takeIf {it.isNotEmpty()}
        if(error==null) revision.value=revision.value+1
        return error
    }
    suspend fun save(enabled: Boolean,url: String): String? = withContext(Dispatchers.IO) {mutex.withLock {
        val trimmed=url.trim().trimEnd('/')
        if(trimmed.isNotEmpty()) {
            val uri=runCatching {java.net.URI(trimmed)}.getOrNull()
            if(uri==null || uri.scheme !in listOf("https","http") || uri.host.isNullOrEmpty() || uri.userInfo!=null || uri.query!=null || uri.fragment!=null) return@withLock "Enter a complete control server HTTP(S) URL"
        }
        preferences.edit().putBoolean("built_in",enabled).putString("control_url",trimmed).commit()
        configure()
    }}
    suspend fun importKey(endpoint: String,text: String,passphrase: String): String? = withContext(Dispatchers.IO) {mutex.withLock {
        if(text.toByteArray().size>131072) return@withLock "SSH key is too large"
        val response=command("import_key",JSONObject().put("key",text).put("passphrase",passphrase))
        if(response.has("error")) return@withLock response.getString("error")
        val saved=keys().put(endpoint,encrypt(response.getString("key")))
        preferences.edit().putString("keys",saved.toString()).commit();configure()
    }}
    suspend fun removeKey(endpoint: String)=withContext(Dispatchers.IO) {mutex.withLock {val saved=keys();saved.remove(endpoint);preferences.edit().putString("keys",saved.toString()).commit();configure()}}
    suspend fun trust(host: String,fingerprint: String)=withContext(Dispatchers.IO) {mutex.withLock {
        val trusted=JSONObject(preferences.getString("trusted","{}")!!).put(host,fingerprint)
        preferences.edit().putString("trusted",trusted.toString()).commit();configure()
    }}
    fun forget(endpoint: String,remaining: List<String>) {scope.launch {mutex.withLock {
        val saved=keys();saved.remove(endpoint)
        val trusted=JSONObject(preferences.getString("trusted","{}")!!)
        fun authority(value: String): String? = runCatching {val uri=java.net.URI(value);val host=uri.host.removePrefix("[").removeSuffix("]");if(uri.scheme!="ssh") null else "${if(host.contains(':')) "[$host]" else host}:${if(uri.port<0) 22 else uri.port}"}.getOrNull()
        authority(endpoint)?.let {host->if(remaining.none {authority(it)==host}) trusted.remove(host)}
        preferences.edit().putString("keys",saved.toString()).putString("trusted",trusted.toString()).commit();configure()
    }}}
    suspend fun login(): String? = withContext(Dispatchers.IO) {command("login").optString("error").takeIf {it.isNotEmpty()}}
}
