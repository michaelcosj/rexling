package dev.shulhi.rex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Every profile, plus the menu settings they share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ShortcutProfilesSheet(profiles: List<ShortcutProfile>,onEdit: (String)->Unit,onNew: ()->Unit,onEnabled: (String,Boolean)->Unit,onClose: ()->Unit,
    layout: NeovimShortcutLayout=NeovimShortcutLayout.CIRCULAR,onLayout: (NeovimShortcutLayout)->Unit={},
    effect: NeovimShortcutEffect=NeovimShortcutEffect.FAN_OUT,onEffect: (NeovimShortcutEffect)->Unit={},
    hapticsEnabled: Boolean=true,onHaptics: (Boolean)->Unit={}) {
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max=680.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            SettingsHeader("Shortcuts","Close shortcuts",onClose,subtitle="The floating menu shows the profile that matches the program in the pane.")
            SettingsSection("Profiles") {
                profiles.forEachIndexed {index,profile->
                    if(index>0) SettingsDivider()
                    ShortcutProfileRow(profile,{onEdit(profile.id)},{onEnabled(profile.id,it)})
                }
                if(profiles.isNotEmpty()) SettingsDivider()
                SettingsRow("New profile","Shortcuts for another program, such as lazygit",SettingsGlyph.COMMAND,onNew)
            }
            SettingsSection("Menu") {
                SettingsChoiceRow("Layout",NeovimShortcutLayout.entries,layout,{if(it==NeovimShortcutLayout.GRID) "Grid" else "Circular"},onLayout)
                if(layout==NeovimShortcutLayout.CIRCULAR) {
                    SettingsDivider()
                    SettingsChoiceRow("Opening effect",NeovimShortcutEffect.entries,effect,{it.label},onEffect,subtitle=when(effect) {
                        NeovimShortcutEffect.FAN_OUT->"Segments open clockwise in a wave."
                        NeovimShortcutEffect.SPIN->"The wheel spins into place."
                        NeovimShortcutEffect.EXPAND->"The wheel grows outward from the center."
                        NeovimShortcutEffect.NONE->"Opens and closes immediately."
                    })
                    SettingsDivider()
                    SettingsToggleRow("Selection haptics",hapticsEnabled,onHaptics,subtitle="Hold and slide to a segment; release to run.")
                }
            }
            Text("Drag the floating button to move it. A more specific program name wins over a path pattern, and both win over *.",
                style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ShortcutProfileRow(profile: ShortcutProfile,onClick: ()->Unit,onEnabled: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().clickable(role=Role.Button,onClick=onClick).padding(start=16.dp,end=8.dp,top=12.dp,bottom=12.dp)
        .semantics {contentDescription="Edit ${profile.name} profile"},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(profile.name,style=SettingsType.row)
            Text(profile.matchText,style=SettingsType.detail.copy(fontFamily=MonoUi),color=MaterialTheme.colorScheme.primary,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(shortcutCount(profile.items),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked=profile.enabled,onCheckedChange=onEnabled,modifier=Modifier.semantics {
            contentDescription="Show ${profile.name} shortcuts";stateDescription=if(profile.enabled) "On" else "Off"})
    }
}

internal fun shortcutCount(items: List<NeovimShortcut>): String {
    val groups=items.map {it.group}.filter {it.isNotEmpty()}.distinct().size
    val count=if(items.size==1) "1 shortcut" else "${items.size} shortcuts"
    return when(groups) {0->count;1->"$count · 1 group";else->"$count · $groups groups"}
}

/** Edits one profile, or creates one when [profile] is null. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ShortcutProfileSheet(profile: ShortcutProfile?,onSave: (name: String,match: String,prefix: String,enter: ShortcutEnter,shortcuts: String)->String?,
    onClose: ()->Unit,onDelete: (()->Unit)?=null,defaults: List<NeovimShortcut>?=null) {
    var name by rememberSaveable {mutableStateOf(profile?.name.orEmpty())}
    var match by rememberSaveable {mutableStateOf(profile?.matchText.orEmpty())}
    var prefix by rememberSaveable {mutableStateOf(profile?.prefix.orEmpty())}
    var enter by rememberSaveable {mutableStateOf(profile?.enter ?: ShortcutEnter.TEXT)}
    var draft by rememberSaveable {mutableStateOf(NeovimShortcuts.editorText(profile?.items.orEmpty()))}
    var error by remember {mutableStateOf<String?>(null)}
    var deleting by remember {mutableStateOf(false)}
    val title=profile?.name ?: "New profile"
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max=680.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            SettingsHeader(title,"Close $title",onClose)
            SettingsTextField("Name",name,{name=it;error=null})
            SettingsTextField("Programs",match,{match=it;error=null},mono=true,
                supporting="Names such as lazygit, separated by commas. Use * in a path pattern, or * alone for any program. Shell names match only at the prompt.")
            SettingsTextField("Keys sent first",prefix,{prefix=it;error=null},mono=true,
                supporting="Optional. For example <Esc>, or <C-\\><C-n> to leave any Neovim mode.")
            SettingsSection("Enter") {
                SettingsChoiceRow("Press Enter after",ShortcutEnter.entries,enter,{it.label},{enter=it},subtitle=enter.detail)
            }
            SettingsTextField("Shortcuts",draft,{draft=it;error=null},mono=true,singleLine=false,minLines=6,maxLines=12,isError=error!=null)
            error?.let {Text(it,style=SettingsType.detail,color=MaterialTheme.colorScheme.error)}
            Text("One shortcut per line, optionally named: Name = keys. [Group] headers put the following shortcuts in a submenu; [] ends the group. " +
                "Start a line with ! to ask before sending it. Key notation: <CR>, <Esc>, <Tab>, <Space>, <C-x>, <M-x>, arrows and F1–F12.",
                style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                if(onDelete!=null) TextButton(onClick={deleting=true}) {Text("Delete",color=MaterialTheme.colorScheme.error)}
                if(defaults!=null) TextButton(onClick={draft=NeovimShortcuts.editorText(defaults);error=null}) {Text("Reset shortcuts")}
                Spacer(Modifier.weight(1f))
                Button(onClick={error=onSave(name,match,prefix,enter,draft);if(error==null) onClose()}) {Text("Save")}
            }
        }
    }
    if(deleting && onDelete!=null) SettingsConfirmDialog("Delete $title?","Its shortcuts will be removed from this phone.","Delete",
        onConfirm={deleting=false;onDelete();onClose()},onDismiss={deleting=false})
}

/** Asks before a shortcut marked with ! is sent. */
@Composable internal fun ShortcutConfirmDialog(profile: ShortcutProfile,item: NeovimShortcut,onConfirm: ()->Unit,onDismiss: ()->Unit) {
    SettingsConfirmDialog("Run ${item.label}?","This sends the following to the ${profile.name} pane:","Run",onConfirm,onDismiss,detail=item.keys)
}
