package dev.shulhi.rex

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingPaneDockTest {
    @get:Rule val ui=createComposeRule()
    @Test fun dockOpensSwitchesAndMinimizesWithoutRemovingOtherIcons() {
        val active=mutableStateOf<String?>(null)
        val panes=listOf(BlockItem("vim","Editor"),BlockItem("shell","Logs"))
        val programs=mapOf("vim" to ProgramIdentity(ProgramKind.NEOVIM,"nvim"),"shell" to ProgramIdentity.Terminal)
        var minimized=0;var capturing=false
        ui.setContent {RexTheme(false,ThemeCatalog.defaults[1]) {
            Box(Modifier.fillMaxSize()) {
                FloatingPaneDock(panes,active.value,programs,emptyMap(),true,{active.value=it},{active.value=null;minimized++},Modifier.align(Alignment.BottomEnd),directories=mapOf("vim" to "~/Dev/rexling","shell" to "~/Dev/logs"),onExpandedChange={capturing=it})
            }
        }}
        ui.onRoot().captureToImage().asAndroidBitmap().let {bitmap->
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"floating-stack-light.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        ui.onNodeWithContentDescription("Open floating pane: Editor").assertDoesNotExist()
        ui.onNodeWithText("Editor · ~/Dev/rexling").assertDoesNotExist()
        ui.onNodeWithContentDescription("Floating panes").assertIsNotSelected()
        val anchor=ui.onNodeWithContentDescription("Floating panes").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithContentDescription("Floating panes").performClick()
        ui.onNodeWithText("Editor · ~/Dev/rexling").assertIsDisplayed()
        ui.runOnIdle {assertTrue(capturing)}
        ui.onNodeWithText("Logs · ~/Dev/logs").assertIsDisplayed()
        assertEquals(anchor,ui.onNodeWithContentDescription("Floating panes").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithContentDescription("Open floating pane: Editor").performClick()
        ui.onNodeWithContentDescription("Minimize floating pane: Editor").assertDoesNotExist()
        ui.onNodeWithContentDescription("Floating panes").assertIsSelected()
        ui.onNodeWithContentDescription("Floating panes").performClick()
        ui.onNodeWithContentDescription("Minimize floating pane: Editor").assertIsSelected()
        ui.onRoot().captureToImage().asAndroidBitmap().let {bitmap->
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"floating-dock-light.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        ui.onNodeWithContentDescription("Open floating pane: Logs").performClick()
        ui.onNodeWithContentDescription("Floating panes").performClick()
        ui.onNodeWithContentDescription("Minimize floating pane: Logs").assertIsSelected().performClick()
        ui.onNodeWithContentDescription("Floating panes").performClick()
        ui.onNodeWithContentDescription("Open floating pane: Editor").assertIsDisplayed()
        ui.onNodeWithContentDescription("Open floating pane: Logs").assertIsDisplayed()
        ui.runOnIdle {assertNull(active.value);assertEquals(1,minimized)}
        ui.onNodeWithContentDescription("Floating panes").performClick()
        ui.onNodeWithContentDescription("Open floating pane: Editor").assertDoesNotExist()
        ui.mainClock.advanceTimeBy(200)
        ui.runOnIdle {assertFalse(capturing)}
    }
    @Test fun singleFloatingPaneOpensDirectlyAndMinimizesWithoutAMenu() {
        val active=mutableStateOf<String?>(null);var minimized=0
        ui.setContent {RexTheme(true,ThemeCatalog.defaults[0]) {
            Box(Modifier.fillMaxSize()) {
                FloatingPaneDock(listOf(BlockItem("one","Editor")),active.value,mapOf("one" to ProgramIdentity(ProgramKind.NEOVIM,"nvim")),emptyMap(),true,
                    {active.value=it},{active.value=null;minimized++},Modifier.align(Alignment.BottomEnd))
            }
        }}
        ui.onNodeWithContentDescription("Floating panes").assertDoesNotExist()
        ui.onNodeWithContentDescription("Open floating pane: Editor").performClick()
        ui.onNodeWithContentDescription("Minimize floating pane: Editor").performClick()
        ui.onNodeWithContentDescription("Open floating pane: Editor").assertIsDisplayed()
        ui.runOnIdle {assertNull(active.value);assertEquals(1,minimized)}
    }
    @Test fun preferenceCanBeEnabledAndDisabled() {
        val enabled=mutableStateOf(false)
        ui.setContent {RexTheme(true,ThemeCatalog.defaults[0]) {
            PreferencesSheet({},{},{},{},{},minimizeFloatingPanes=enabled.value,onMinimizeFloatingPanes={enabled.value=it})
        }}
        val toggle=ui.onNode(hasText("Minimize floating panes") and isToggleable())
        toggle.performScrollTo().assertIsOff().performClick().assertIsOn().performClick().assertIsOff()
    }
}
