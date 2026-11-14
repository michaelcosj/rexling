package dev.shulhi.rex

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NetworkSheet(onClose: ()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val status by NetworkSettings.status.collectAsState()
    var enabled by remember {mutableStateOf(NetworkSettings.builtIn)}
    var control by remember {mutableStateOf(NetworkSettings.controlUrl)}
    var error by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    val tailscale=status.optJSONObject("tailscale")
    val auth=tailscale?.optString("AuthURL").orEmpty()
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            SettingsHeader("Network","Close network settings",onClose)
            SettingsSection("Tailscale") {
                Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {Text("Built-in Tailscale",style=SettingsType.row);Text("Connect without the Tailscale VPN app",style=SettingsType.detail)}
                    Switch(enabled,{enabled=it})
                }
            }
            Text("When off, Rexling uses your phone’s existing network and VPN.",style=SettingsType.detail)
            OutlinedTextField(control,{control=it},Modifier.fillMaxWidth(),label={Text("Control server (optional)")},placeholder={Text("https://headscale.example.com")},singleLine=true)
            Text("Leave blank for Tailscale. Each control server keeps its own sign-in.",style=SettingsType.detail)
            Button(enabled=!busy,onClick={busy=true;scope.launch {error=NetworkSettings.save(enabled,control);busy=false}}) {Text(if(busy) "Applying…" else "Apply")}
            if(NetworkSettings.builtIn) {
                Text(tailscale?.optString("BackendState")?.takeIf {it.isNotBlank()} ?: "Starting…",style=SettingsType.row)
                TextButton(onClick={scope.launch {error=NetworkSettings.login()}}) {Text("Sign in")}
                if(auth.isNotBlank()) Button(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(auth)))}) {Text("Open sign-in page")}
                val peers=tailscale?.optJSONObject("Peer")
                peers?.keys()?.forEach {id->val peer=peers.optJSONObject(id);if(peer?.optBoolean("Active")==true) {
                    val name=peer.optString("HostName")
                    val route=if(peer.optString("CurAddr").isNotEmpty()) "Direct" else if(peer.optString("PeerRelay").isNotEmpty()) "Peer relay" else "Tailscale relay"
                    Text("$name · $route",style=SettingsType.detail)
                }}
                status.optString("error").takeIf {it.isNotBlank()}?.let {Text(it,color=MaterialTheme.colorScheme.error,style=SettingsType.detail)}
            }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=SettingsType.detail)}
        }
    }
}

@Composable internal fun SshKeyEditor(endpoint: String) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var source by remember(endpoint) {mutableStateOf<String?>(null)}
    var pasted by remember(endpoint) {mutableStateOf(false)}
    var passphrase by remember(endpoint) {mutableStateOf("")}
    var error by remember(endpoint) {mutableStateOf<String?>(null)}
    var busy by remember(endpoint) {mutableStateOf(false)}
    var imported by remember(endpoint) {mutableStateOf(NetworkSettings.hasKey(endpoint))}
    fun dismiss() {if(!busy) {source=null;passphrase="";error=null;pasted=false}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null) runCatching {context.contentResolver.openInputStream(uri)?.use {stream->
            val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
            while(true) {val count=stream.read(buffer);if(count<0) break;require(output.size()+count<=131072) {"SSH key is too large"};output.write(buffer,0,count)}
            source=String(output.toByteArray());pasted=false;passphrase="";error=null
        }}.onFailure {error=it.message}
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.5f)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text("SSH key",style=SettingsType.row)
        Text(if(imported) "Saved securely on this phone" else "Optional for Tailscale SSH.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
            TextButton(contentPadding=PaddingValues(horizontal=6.dp,vertical=4.dp),onClick={picker.launch(arrayOf("*/*"))}) {Text("Import file",style=SettingsType.detail)}
        TextButton(contentPadding=PaddingValues(horizontal=6.dp,vertical=4.dp),onClick={
            pasted=true;source="";passphrase="";error=null
            runCatching {
                val clip=(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                val text=if(clip!=null && clip.itemCount>0) clip.getItemAt(0).text?.toString().orEmpty() else ""
                require(text.toByteArray().size<=131072) {"SSH key is too large"}
                source=text
            }.onFailure {error=it.message}
        }) {Text("Paste key",style=SettingsType.detail)}

            Spacer(Modifier.weight(1f))
            if(imported) TextButton(contentPadding=PaddingValues(horizontal=6.dp,vertical=4.dp),onClick={scope.launch {NetworkSettings.removeKey(endpoint);imported=false}}) {Text("Remove",style=SettingsType.detail,color=MaterialTheme.colorScheme.error)}
        }
        if(source==null) error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=SettingsType.detail)}
    }
    if(source!=null) AlertDialog(onDismissRequest={dismiss()},title={Text(if(pasted) "Paste SSH key" else "Import SSH key",style=SettingsType.title)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("The key stays encrypted on this phone. Enter its passphrase if required.",style=SettingsType.detail)
            if(pasted) OutlinedTextField(source.orEmpty(),{text->
                if(text.toByteArray().size<=131072) {source=text;error=null} else error="SSH key is too large"
            },Modifier.fillMaxWidth(),label={Text("Private key")},supportingText={Text("Include the BEGIN and END lines.",style=SettingsType.detail)},
                minLines=3,maxLines=5,enabled=!busy,textStyle=SettingsType.detail,
                visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false))
            OutlinedTextField(passphrase,{passphrase=it;error=null},label={Text("Passphrase (optional)")},visualTransformation=PasswordVisualTransformation(),singleLine=true,textStyle=SettingsType.body,
                enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false))
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=SettingsType.detail)}
        }
    },confirmButton={TextButton(enabled=!busy && !source.isNullOrBlank(),onClick={
        val key=source.orEmpty();val phrase=passphrase;busy=true
        scope.launch {
            error=runCatching {NetworkSettings.importKey(endpoint,key,phrase)}.getOrElse {"Could not import SSH key"}
            busy=false
            if(error==null) {source=null;passphrase="";imported=true;pasted=false}
        }
    }) {Text(if(busy) "Importing…" else "Import")}},dismissButton={TextButton(enabled=!busy,onClick={dismiss()}) {Text("Cancel")}})

}

@Composable internal fun NetworkPrompts() {
    val status by NetworkSettings.status.collectAsState();val scope=rememberCoroutineScope();val context=LocalContext.current
    val host=status.optJSONArray("hosts")?.optJSONObject(0)
    var hidden by remember {mutableStateOf("")}
    val signature=host?.toString().orEmpty()
    if(host!=null && hidden!=signature) AlertDialog(onDismissRequest={hidden=signature},title={Text(if(host.optString("previous").isEmpty()) "Trust SSH host?" else "SSH host key changed")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(host.optString("host"));Text(host.optString("type"),style=SettingsType.detail);Text(host.optString("fingerprint"),style=SettingsType.detail)
            if(host.optString("previous").isNotEmpty()) Text("Previous: ${host.optString("previous")}",style=SettingsType.detail)
            Text("Verify this fingerprint with the host before trusting it.",style=SettingsType.detail)
        }
    },confirmButton={TextButton(onClick={scope.launch {NetworkSettings.trust(host.getString("host"),host.getString("fingerprint"))}}) {Text("Trust host")}},dismissButton={TextButton(onClick={hidden=signature}) {Text("Cancel")}})
    else {
        val banners=status.optJSONObject("banners");val endpoint=banners?.keys()?.asSequence()?.firstOrNull();val message=endpoint?.let {banners.optString(it)}.orEmpty()
        if(message.isNotBlank() && hidden!=message) AlertDialog(onDismissRequest={hidden=message},title={Text("SSH sign-in")},text={Text(message)},confirmButton={
            TextButton(onClick={(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SSH sign-in",message))}) {Text("Copy message")}
        },dismissButton={
            val link=Regex("https?://[^\\s<>]+").find(message)?.value
            if(link!=null) TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(link)))}) {Text("Open browser")}
            else TextButton(onClick={hidden=message}) {Text("Close")}
        })
    }
}
