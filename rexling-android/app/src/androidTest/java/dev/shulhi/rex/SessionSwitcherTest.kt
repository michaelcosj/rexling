package dev.shulhi.rex

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SessionSwitcherTest {
    @get:Rule val ui=createComposeRule()
    private val mac=ServerProfile.create("Shulhi MBP","https://fixture.example","mac")
    private val linux=ServerProfile.create("Ubuntu","https://linux.example","linux")
    private val targets=listOf(SessionTarget(mac,SessionItem("s","Rexling development"),SessionDetail("this device",2,4)),
        SessionTarget(mac,SessionItem("other","ShipPDF"),SessionDetail("attached on macOS",2,3)),
        SessionTarget(linux,SessionItem("s","Ubuntu server"),SessionDetail("this device",1,1)))
    private fun capture(name: String) {
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync();Thread.sleep(300)
        File(instrumentation.targetContext.cacheDir,name).outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
    @Test fun compactDrawerFiltersAcrossServersAndNavigatesWithoutDuplicateStrip() {
        var selected="";var closed=0
        ui.setContent {RexTheme(true,ThemeCatalog.defaults[0]) {
            SessionSwitcher(targets,targets[0].key,targets[1].key,true,"","",{},{_,_->true},{selected=it},{closed++},{})
        }}
        ui.onNodeWithContentDescription("Session strip").assertDoesNotExist()
        ui.onNodeWithText("Rexling development").assertIsDisplayed()
        ui.onNodeWithText("Ubuntu server").assertIsDisplayed()
        capture("sessions-compact-dark.png")
        val before=ui.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        ui.onNode(hasSetTextAction()).performTextInput("Ubuntu")
        ui.onNodeWithText("Rexling development").assertDoesNotExist()
        assertEquals(before.width,ui.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.width)
        ui.onNodeWithText("Ubuntu server").performClick()
        ui.runOnIdle {assertEquals(targets[2].key,selected);assertEquals(0,closed)}
        ui.onNodeWithContentDescription("Close sessions").performClick()
        ui.runOnIdle {assertEquals(1,closed)}
    }
    @Test fun newSessionAndSessionActionsRemainSeparateFromSelection() {
        var created=0;var selected=0;var renamed=""
        ui.setContent {RexTheme(false,ThemeCatalog.defaults[1]) {
            SessionSwitcher(targets,targets[0].key,targets[1].key,true,"","",{},{_,name->renamed=name;true},{selected++},{},{created++})
        }}
        capture("sessions-compact-light.png")
        ui.onNodeWithText("New session").performClick()
        ui.onNodeWithContentDescription("Session actions for ShipPDF on Shulhi MBP").performClick()
        ui.onNodeWithText("Rename session").performClick()
        ui.onNode(hasSetTextAction() and hasText("ShipPDF")).performTextReplacement("New project")
        ui.onNodeWithText("Save").performClick()
        ui.runOnIdle {assertEquals(1,created);assertEquals(0,selected);assertEquals("New project",renamed)}
    }
    @Test fun emptyDrawerStillOffersNewSession() {
        var created=0
        ui.setContent {RexTheme(false,ThemeCatalog.defaults[1]) {
            SessionSwitcher(emptyList(),"","",true,"","",{},{_,_->false},{},{},{created++})
        }}
        ui.onNodeWithText("No sessions yet").assertIsDisplayed()
        ui.onNodeWithText("New session").assertIsEnabled().performClick()
        ui.runOnIdle {assertEquals(1,created)}
    }

}
