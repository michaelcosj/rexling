package dev.shulhi.rex

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun NewSessionDialog(serverLabel: String,enabled: Boolean,onCreate: (String)->Boolean,onDismiss: ()->Unit) {
    var name by rememberSaveable {mutableStateOf("")}
    RexAlertDialog(onDismissRequest=onDismiss,
        title={Text("New session")},text={
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("On $serverLabel",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                SettingsTextField("Name (optional)",name,{name=it},supporting="Leave blank for a name from Rex.")
            }
        },confirmButton={RexButton("Create",{if(onCreate(name)) onDismiss()},primary=true,enabled=enabled)},
        dismissButton={RexButton("Cancel",onDismiss)})
}

@Composable internal fun EmptySessionContent(serverLabel: String,enabled: Boolean,onNew: ()->Unit,modifier: Modifier=Modifier) {
    Column(modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(10.dp,Alignment.CenterVertically),horizontalAlignment=Alignment.CenterHorizontally) {
        Text("No sessions yet",style=SettingsType.title)
        Text("Start a session on $serverLabel.",style=SettingsType.body,color=MaterialTheme.colorScheme.onSurfaceVariant)
        RexButton("+ New session",onNew,primary=true,enabled=enabled)
    }
}
