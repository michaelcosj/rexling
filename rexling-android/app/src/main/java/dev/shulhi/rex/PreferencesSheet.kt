package dev.shulhi.rex

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PreferencesSheet(onAppearance: ()->Unit,onFont: ()->Unit,onAlerts: ()->Unit,onAbout: ()->Unit,onClose: ()->Unit,
    shortcutGesture: ControlShortcutGesture=ControlShortcutGesture.HOLD,onShortcutGesture: (ControlShortcutGesture)->Unit={},onShortcuts: ()->Unit={},
    arrowJoystickEnabled: Boolean=true,onArrowJoystick: (Boolean)->Unit={},codexTitleAttention: Boolean=false,onCodexTitleAttention: (Boolean)->Unit={},
    terminalPadding: TerminalPadding=TerminalPadding.NORMAL,onTerminalPadding: (TerminalPadding)->Unit={},extendPadding: Boolean=true,onExtendPadding: (Boolean)->Unit={}) {
    RexSheet(onDismissRequest=onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max=680.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            SettingsHeader("Preferences","Close preferences",onClose)
            SettingsSection("Display") {
                SettingsRow("Appearance","Themes and light or dark mode",SettingsGlyph.APPEARANCE,onAppearance)
                SettingsDivider()
                SettingsRow("Font size","Terminal text size",SettingsGlyph.FONT,onFont)
                SettingsDivider()
                SettingsChoiceRow("Terminal padding",TerminalPadding.entries,terminalPadding,{it.label},onTerminalPadding,subtitle="Space around the terminal")
                SettingsDivider()
                SettingsToggleRow("Extend colors into padding",extendPadding,onExtendPadding,
                    subtitle=if(extendPadding) "Edge cell colors fill the padding, like Neovim's background" else "Padding keeps the theme background")
            }
            SettingsSection("Keyboard") {
                SettingsToggleRow("Use joystick for arrows",arrowJoystickEnabled,onArrowJoystick,
                    subtitle=if(arrowJoystickEnabled) "Hold and slide; double-tap for arrow buttons" else "Tap for arrow buttons")
                SettingsDivider()
                SettingsChoiceRow("Ctrl shortcuts",ControlShortcutGesture.entries,shortcutGesture,{if(it==ControlShortcutGesture.HOLD) "Hold" else "Double tap"},onShortcutGesture,
                    subtitle="Open the ^ shortcut list with")
                SettingsDivider()
                SettingsRow("Shortcuts","Floating key menus for Neovim, the shell and other programs",SettingsGlyph.COMMAND,onShortcuts)
            }
            SettingsSection("Connections") {
                SettingsRow("Background alerts","Task completions and attention alerts",SettingsGlyph.ALERTS,onAlerts)
            }
            SettingsSection("Experimental") {
                SettingsToggleRow("Codex title attention",codexTitleAttention,onCodexTitleAttention,
                    subtitle="Use Codex’s explicit Action Required title when its status stays Working. Depends on Codex’s title format.")
            }
            SettingsSection("App") {
                SettingsRow("About Rexling","Version, build and project information",SettingsGlyph.INFO,onAbout)
            }
        }
    }
}
