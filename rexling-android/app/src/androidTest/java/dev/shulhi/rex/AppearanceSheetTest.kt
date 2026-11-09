package dev.shulhi.rex

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppearanceSheetTest {
    @get:Rule val ui=createComposeRule()
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val themes=ThemeCatalog.load(app)
    @Test fun defaultRemainsFirstAndDarkThemeSelectionDoesNotChangeAppearanceMode() {
        var chosen="";var mode=Appearance.SYSTEM
        ui.setContent {MaterialTheme {AppearanceSheet(mode,themes,ThemeCatalog.defaults[1],ThemeCatalog.defaults[0],{mode=it},{chosen=it},{})}}
        ui.onNodeWithText("Dark theme").performClick()
        ui.onNodeWithContentDescription("Theme Rexling Default").assertIsSelected()
        ui.onNodeWithContentDescription("Theme Abyssal Trench").performClick()
        ui.runOnIdle {assertEquals("abyssal-trench",chosen);assertEquals(Appearance.SYSTEM,mode)}
    }
    @Test fun fontSizeChangesOneSpAtATime() {
        val size=mutableIntStateOf(10)
        ui.setContent {MaterialTheme {FontSizeDialog(size.intValue,{size.intValue=it},{})}}
        ui.onNodeWithContentDescription("Increase font size").performClick();ui.onNodeWithText("11 sp").assertIsDisplayed()
        ui.onNodeWithContentDescription("Decrease font size").performClick();ui.onNodeWithText("10 sp").assertIsDisplayed()
        ui.onNodeWithContentDescription("Font size").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) {it(9f)}
        ui.onNodeWithText("9 sp").assertIsDisplayed()
    }
    @Test fun lightAndDarkChoicesAndFontSizeSurviveModelRecreation() {
        val name="rexlink-theme-test-${System.nanoTime()}";val store=ViewModelStore()
        fun main(block: ()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        try {
            main {
                val first=RexModel(app,name);store.put("theme",first)
                first.chooseTheme("merino-dark");first.chooseTheme("merino-light");first.changeFontSize(11)
                first.changeAppearance(Appearance.DARK)
                assertTrue(first.arrowJoystickEnabled)
                first.changeArrowJoystick(false)
                assertEquals(ControlShortcutGesture.HOLD,first.shortcutGesture)
                first.changeShortcutGesture(ControlShortcutGesture.DOUBLE_TAP)
                val neovim=first.shortcutProfiles.first {it.id==ShortcutProfiles.NEOVIM}
                assertNull(first.saveShortcutProfile(neovim.id,neovim.name,neovim.matchText,neovim.prefix,neovim.enter,":w\n[Git]\n"+(1..40).joinToString("\n") {"Action $it = <Space>lg"}))
                assertEquals(NeovimShortcutLayout.CIRCULAR,first.neovimShortcutLayout)
                first.changeNeovimShortcutLayout(NeovimShortcutLayout.GRID)
                assertEquals(NeovimShortcutEffect.FAN_OUT,first.neovimShortcutEffect)
                first.changeNeovimShortcutEffect(NeovimShortcutEffect.SPIN)
                assertTrue(first.neovimShortcutHaptics)
                first.changeNeovimShortcutHaptics(false)
                assertTrue(first.shortcutProfiles.first {it.id==ShortcutProfiles.NEOVIM}.enabled)
                first.enableShortcutProfile(ShortcutProfiles.NEOVIM,false)
                first.moveNeovimShortcut(androidx.compose.ui.geometry.Offset(.7f,.2f))
                val second=RexModel(app,name);store.put("theme2",second)
                assertEquals("merino-dark",second.theme(true).id);assertEquals("merino-light",second.theme(false).id);assertEquals(11,second.fontSize)
                assertEquals(Appearance.DARK,second.appearance)
                assertFalse(second.arrowJoystickEnabled)
                assertEquals(ControlShortcutGesture.DOUBLE_TAP,second.shortcutGesture)
                assertEquals(NeovimShortcuts.parse(":w\n[Git]\n"+(1..40).joinToString("\n") {"Action $it = <Space>lg"}),second.shortcutProfiles.first {it.id==ShortcutProfiles.NEOVIM}.items)
                assertEquals(NeovimShortcutLayout.GRID,second.neovimShortcutLayout)
                assertEquals(NeovimShortcutEffect.SPIN,second.neovimShortcutEffect)
                assertFalse(second.neovimShortcutHaptics)
                second.changeNeovimShortcutEffect(NeovimShortcutEffect.NONE)
                val third=RexModel(app,name);store.put("theme3",third)
                assertEquals(NeovimShortcutEffect.NONE,third.neovimShortcutEffect)
                app.getSharedPreferences(name,0).edit().putString("neovim_shortcut_effect","SHUTTER").apply()
                val fourth=RexModel(app,name);store.put("theme4",fourth)
                assertEquals(NeovimShortcutEffect.EXPAND,fourth.neovimShortcutEffect)
                assertFalse(second.shortcutProfiles.first {it.id==ShortcutProfiles.NEOVIM}.enabled)
                assertEquals(androidx.compose.ui.geometry.Offset(.7f,.2f),second.neovimShortcutPosition)
                second.chooseTheme("rexlink-default-dark");assertSame(TerminalPalette.Dark,second.theme(true).terminal)
            }
        } finally {main {store.clear()};app.deleteSharedPreferences(name)}
    }
    @Test fun quickMenuSwitchUsesCurrentAppearanceAndSupportsBothDirections() {
        val dark=mutableStateOf(false)
        ui.setContent {MaterialTheme {QuickThemeMenuItem(dark.value) {dark.value=it}}}
        ui.onNodeWithText("Toggle Dark theme").performClick()
        ui.runOnIdle {assertTrue(dark.value)}
        ui.onNodeWithText("Toggle Light theme").performClick()
        ui.runOnIdle {assertFalse(dark.value)}
    }
    @Test fun preferencesChooseTheCtrlShortcutGesture() {
        val gesture=mutableStateOf(ControlShortcutGesture.HOLD)
        val joystick=mutableStateOf(true)
        ui.setContent {MaterialTheme {PreferencesSheet({},{},{},{},{},gesture.value,{gesture.value=it},
            arrowJoystickEnabled=joystick.value,onArrowJoystick={joystick.value=it})}}
        ui.onNodeWithText("Hold").performScrollTo().assertIsSelected()
        ui.onNodeWithText("Double tap").performClick().assertIsSelected()
        ui.runOnIdle {assertEquals(ControlShortcutGesture.DOUBLE_TAP,gesture.value)}
        ui.onNodeWithText("Hold").performClick().assertIsSelected()
        ui.runOnIdle {assertEquals(ControlShortcutGesture.HOLD,gesture.value)}
        ui.onNodeWithText("Use joystick for arrows").performScrollTo().assertIsOn().performClick().assertIsOff()
        ui.onNodeWithText("Tap for arrow buttons").assertIsDisplayed()
        ui.runOnIdle {assertFalse(joystick.value)}
    }
    @Test fun themedSheetsRenderInLightAndDark() {
        val selected=mutableStateOf(themes.first {it.id=="abyssal-trench"})
        ui.setContent {val theme=selected.value;MaterialTheme(colorScheme=(if(theme.terminal.dark) darkColorScheme() else lightColorScheme()).withPreset(theme)) {
            Surface(Modifier.fillMaxSize()) {AppearanceSheet(Appearance.SYSTEM,themes,themes.first {it.id=="alpine-milk"},themes.first {it.id=="abyssal-trench"},{},{},{})}
        }}
        for(id in listOf("abyssal-trench","alpine-milk")) {
            ui.runOnIdle {selected.value=themes.first {it.id==id}};ui.waitForIdle()
            ui.onNodeWithText("Appearance").assertIsDisplayed()
            File(app.cacheDir,"theme-$id.png").outputStream().use {InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
}
