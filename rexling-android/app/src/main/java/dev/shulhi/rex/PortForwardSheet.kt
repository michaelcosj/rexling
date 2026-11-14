package dev.shulhi.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PortForwardSheet(server: ServerProfile,session: String,canAdd: Boolean,hazeState: HazeState?,onClose: ()->Unit,onAdd: (String,Int,Int)->Unit) {
    val context=LocalContext.current
    var editing by remember {mutableStateOf(false)}
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface,tonalElevation=0.dp,dragHandle=null) {
        Column(Modifier.drawerFrost(hazeState,BottomSheetDefaults.ExpandedShape).fillMaxWidth().padding(bottom=16.dp)) {
            DrawerHandle()
            Row(Modifier.fillMaxWidth().padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("Port forwarding",Modifier.weight(1f),style=SettingsType.row,fontWeight=FontWeight.Bold)
                ChromeButton(ChromeIcon.ADD,"Add port forward",enabled=canAdd && session.isNotBlank() && PortForwards.items.size<8) {editing=true}
                ChromeButton(ChromeIcon.CLOSE,"Close port forwarding",onClick=onClose)
            }
            PortForwards.notice?.let {notice->
                Text(notice,Modifier.padding(horizontal=16.dp,vertical=8.dp),style=SettingsType.detail,color=MaterialTheme.colorScheme.error)
                TextButton(onClick={PortForwards.notice=null}) {Text("Dismiss")}
            }
            if(PortForwards.items.isEmpty()) {
                Text("Open a remote service on this phone.",Modifier.padding(16.dp),style=SettingsType.detail)
                TextButton(onClick={editing=true},enabled=canAdd && session.isNotBlank(),modifier=Modifier.padding(horizontal=8.dp)) {Text("Add port forward")}
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=440.dp),contentPadding=PaddingValues(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(PortForwards.items,key={it.id}) {item->
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.6f),RoundedCornerShape(14.dp)).padding(start=12.dp,end=4.dp,top=8.dp,bottom=2.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.target,style=SettingsType.row,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text("${item.server} · ${item.phase.replaceFirstChar {it.uppercase()}}",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            ChromeButton(ChromeIcon.CLOSE,"Stop forward to ${item.target}") {PortForwards.stop(context,item.id)}
                        }
                        if(item.error.isNotBlank()) Text(item.error,style=SettingsType.detail,color=MaterialTheme.colorScheme.error)
                        if(item.address.isNotBlank()) Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(item.address,Modifier.weight(1f),style=SettingsType.detail)
                            TextButton(onClick={
                                runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(item.url)))}
                                    .onFailure {PortForwards.notice="No browser is available to open this forward."}
                            },enabled=item.phase=="active",contentPadding=PaddingValues(horizontal=8.dp)) {Text("Open")}
                            ChromeButton(ChromeIcon.COPY,"Copy forwarded URL") {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Forwarded URL",item.url))
                            }
                        }
                    }
                }
            }
        }
    }
    if(editing) ForwardEditor(server.label,onClose={editing=false}) {host,port,local->editing=false;onAdd(host,port,local)}
}
@Composable private fun ForwardEditor(server: String,onClose: ()->Unit,onSave: (String,Int,Int)->Unit) {
    var host by remember {mutableStateOf("127.0.0.1")}
    var port by remember {mutableStateOf("")}
    var local by remember {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    var advanced by remember {mutableStateOf(false)}
    AlertDialog(onDismissRequest=onClose,title={Text("Add port forward")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Service running on $server",style=SettingsType.detail)
            OutlinedTextField(port,{port=it;error=null},label={Text("Remote port")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            TextButton(onClick={advanced=!advanced},contentPadding=PaddingValues(horizontal=0.dp)) {Text(if(advanced) "Hide advanced" else "Advanced")}
            if(advanced) {
                OutlinedTextField(host,{host=it;error=null},label={Text("Remote host")},singleLine=true)
                OutlinedTextField(local,{local=it;error=null},label={Text("Phone port")},placeholder={Text("Automatic")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            }
            error?.let {Text(it,style=SettingsType.detail,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton(onClick={
        val remotePort=port.toIntOrNull()
        val localPort=if(local.isBlank()) 0 else local.toIntOrNull()
        val name=host.trim().removeSurrounding("[","]")
        if(remotePort==null || remotePort !in 1..65535 || localPort==null || localPort !in 0..65535) error="Enter valid ports from 1 to 65535."
        else if(name.isBlank() || name.length>253 || name.any {it.isWhitespace() || it=='/' || it.isISOControl()}) error="Enter a hostname or IP address."
        else onSave(name,remotePort,localPort)
    }) {Text("Forward")}},dismissButton={TextButton(onClick=onClose) {Text("Cancel")}})
}
