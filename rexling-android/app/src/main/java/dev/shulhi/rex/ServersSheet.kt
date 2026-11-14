package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ServersSheet(model: RexModel,onDismiss: ()->Unit) {
    var editing by remember {mutableStateOf<ServerProfile?>(null)}
    var editor by remember {mutableStateOf(false)}
    ModalBottomSheet(onDismissRequest=onDismiss,containerColor=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState()).padding(horizontal=18.dp).padding(bottom=25.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom=14.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Servers",fontSize=20.sp,fontWeight=FontWeight.Bold)
                    Text("${model.connectedCount} connected",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick={editing=null;editor=true}) {Text("+ Add server")}
            }
            if(model.servers.isEmpty()) Text("Add a Rex server to get started.",Modifier.padding(vertical=16.dp))
            for(server in model.servers) {
                val selected=server.id==model.activeServerId
                val status=model.serverStatuses[server.id] ?: ServerStatus()
                var menu by remember(server.id) {mutableStateOf(false)}
                Row(Modifier.fillMaxWidth().padding(bottom=8.dp).clip(RoundedCornerShape(16.dp))
                    .background(if(selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .border(1.dp,if(selected) MaterialTheme.colorScheme.primary.copy(alpha=.25f) else Color.Transparent,RoundedCornerShape(16.dp))
                    .clickable {onDismiss();model.connectServer(server.id)}.padding(start=13.dp,top=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if(status.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(11.dp))
                    ServerGlyph(server.icon,Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(server.label,fontWeight=FontWeight.SemiBold,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(server.endpoint,fontFamily=MonoUi,fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(when(status.phase) {
                            "connected"->if(selected) "Connected · viewing" else "Connected"
                            "connecting"->"Connecting…";"reconnecting"->"Reconnecting…";"paused"->"Paused"
                            "error"->"Connection failed";else->"Disconnected"
                        },fontSize=11.sp,color=if(status.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        if(status.error.isNotBlank()) Text(status.error,fontSize=10.sp,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.error)
                    }
                    Box {
                        ChromeButton(ChromeIcon.MORE,"Server actions for ${server.label}") {menu=true}
                        DropdownMenu(menu,{menu=false},containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
                            TerminalMenuItem("Edit",ChromeIcon.EDIT,onClick={menu=false;editing=server;editor=true})
                            if(status.maintained) TerminalMenuItem("Disconnect",ChromeIcon.UNPLUG,onClick={menu=false;model.disconnectServer(server.id)})
                            TerminalMenuItem("Forget server",ChromeIcon.DELETE,onClick={menu=false;model.forgetServer(server.id)})
                        }
                    }
                }
            }
            Text("Switch servers without closing their connections.",Modifier.padding(top=9.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(editor) ServerEditor(editing,onClose={editor=false},onSave={label,endpoint,icon->model.saveServer(editing?.id,label,endpoint,icon)})
}

@Composable internal fun ServerEditor(server: ServerProfile?,onClose: ()->Unit,onSave: (String,String,ServerIcon)->String?) {
    var name by remember(server?.id) {mutableStateOf(server?.label.orEmpty())}
    var endpoint by remember(server?.id) {mutableStateOf(server?.endpoint.orEmpty())}
    var icon by remember(server?.id) {mutableStateOf(server?.icon ?: ServerIcon.GLOBE)}
    var error by remember {mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=onClose,title={Text(if(server==null) "Add server" else "Edit server",style=SettingsType.title)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                ServerIconPicker(icon,compact=true) {icon=it}
                OutlinedTextField(name,{name=it;error=null},Modifier.weight(1f),label={Text("Name")},singleLine=true,textStyle=SettingsType.body)
            }
            OutlinedTextField(endpoint,{endpoint=it;error=null},Modifier.fillMaxWidth(),label={Text("Server URL")},singleLine=true,textStyle=SettingsType.body,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),placeholder={Text("https://host.tailnet.ts.net",fontSize=11.sp)})
            Text(if(endpoint.startsWith("ssh://")) "SSH to a host running Rex." else "Use an HTTPS address, or ssh://user@host.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(endpoint.startsWith("ssh://")) {
                val normalized=runCatching {ServerProfile.normalizeEndpoint(endpoint)}.getOrNull()
                if(normalized!=null) SshKeyEditor(normalized)
            }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
        }
    },confirmButton={Button(onClick={error=onSave(name,endpoint,icon);if(error==null) onClose()},enabled=endpoint.isNotBlank()) {Text("Save & connect")}},
        dismissButton={TextButton(onClick=onClose) {Text("Cancel")}})
}
