package dev.shulhi.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class AppBuildInfo(val version: String,val number: Int,val type: String,val revision: String,val modified: Boolean) {
    val commit: String get()=revision+if(modified) " (local changes)" else ""
    val description: String get()="Rexling $version (build $number)\n$type · $commit"
    companion object {
        val current get()=AppBuildInfo(BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE,BuildConfig.BUILD_TYPE,BuildConfig.GIT_REVISION,BuildConfig.LOCAL_CHANGES)
        const val repository="https://github.com/shulhi/rexling"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AboutSheet(onClose: ()->Unit) {
    val context=LocalContext.current
    val uri=LocalUriHandler.current
    RexSheet(onDismissRequest=onClose) {
        AboutContent(AppBuildInfo.current,onClose,onCopy={text->
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rexling build",text))
        },onOpen={url->
            runCatching {uri.openUri(url)}.onFailure {Toast.makeText(context,"No browser available",Toast.LENGTH_SHORT).show()}
        })
    }
}

@Composable internal fun AboutContent(info: AppBuildInfo,onClose: ()->Unit,onCopy: (String)->Unit,onOpen: (String)->Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max=620.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SettingsHeader("About","Close about",onClose)
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Image(painterResource(R.drawable.rexlink_dinosaur),contentDescription=null,
                modifier=Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(4.dp))
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.app_name),style=SettingsType.title.copy(fontSize=20.sp))
                Text(stringResource(R.string.app_subtitle),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SettingsSection("Build") {
            Column(Modifier.padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                BuildInfoRow("Version",info.version)
                BuildInfoRow("Build","${info.number} · ${info.type}")
                BuildInfoRow("Commit",info.commit)
            }
        }
        RexButton("Copy build info",{onCopy(info.description)})
        SettingsSection("Links") {
            for((label,url) in listOf("Source code on GitHub" to AppBuildInfo.repository,"Apache License 2.0" to "${AppBuildInfo.repository}/blob/main/LICENSE",
                "Open-source notices" to "${AppBuildInfo.repository}/blob/main/THIRD_PARTY_NOTICES.md")) {
                Row(Modifier.fillMaxWidth().clickable(role=Role.Button) {onOpen(url)}.padding(vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("↗",style=SettingsType.row,color=MaterialTheme.colorScheme.primary)
                    Text(label,style=SettingsType.row)
                }
            }
        }
        Text("Built with Kotlin, Jetpack Compose, Rust and libghostty-vt.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Provided on a best-effort basis, without warranty.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun BuildInfoRow(label: String,value: String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.Top) {
        Text(label,Modifier.width(72.dp),style=SettingsType.body,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,style=SettingsType.body.copy(fontWeight=FontWeight.Medium))
    }
}
