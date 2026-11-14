package dev.shulhi.rex

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.io.File

class TerminalProgressUiTest {
    @get:Rule val ui=createComposeRule()
    private fun capture(name: String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation();ui.waitForIdle()
        instrumentation.waitForIdleSync();Thread.sleep(300)
        File(instrumentation.targetContext.cacheDir,name).outputStream().use {instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun tabProgressUpdatesStateAndClearsWithoutChangingTheTitle() {
        var report by mutableStateOf<TerminalProgress?>(TerminalProgress("set",40))
        ui.setContent {RexTheme(false,ThemeCatalog.defaults[1]) {
            Box(Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface)) {
                TerminalTabs(listOf(WindowItem("w","Cargo build",listOf(BlockItem("b","cargo")))),"w",true,{},
                    panePrograms={listOf(ProgramIdentity.resolve(listOf("cargo")))},selectedPane={0},paneProgress={report},actions={})
            }
        }}
        ui.onNodeWithContentDescription("Working · 40%",substring=true).assertIsDisplayed()
        capture("progress-tab-light.png")
        ui.runOnIdle {report=TerminalProgress("pause",40)}
        ui.onNodeWithContentDescription("Paused · 40%",substring=true).assertIsDisplayed()
        ui.runOnIdle {report=TerminalProgress("error",40)}
        ui.onNodeWithContentDescription("Failed · 40%",substring=true).assertIsDisplayed()
        ui.runOnIdle {report=TerminalProgress("indeterminate",null)}
        ui.onNodeWithContentDescription("Working",substring=true).assertIsDisplayed()
        ui.runOnIdle {report=null}
        ui.onNodeWithContentDescription("40%",substring=true).assertDoesNotExist()
        ui.onNodeWithText("Cargo build").assertIsDisplayed()
    }
    @Test fun activityShowsPercentageWithoutMarkSeenForProgressOnlyRows() {
        val record=ActivityRecord.parse(JSONObject("""{"session":"s","sessionLabel":"Build session","windowLabel":"Build","block":"b","id":"osc9-progress","title":"Cargo build","app":"cargo","state":"working","progressOnly":true,"progress":40,"terminalProgress":{"state":"set","progress":40}}"""))!!
        val server=ServerProfile.create("Mac","https://mac.example","mac")
        ui.setContent {RexTheme(true,ThemeCatalog.defaults[0]) {
            ActivitySheet(listOf(ActivitySource(server,true,false,"",false,listOf(record))),{},{},{})
        }}
        ui.onNodeWithText("40%",substring=true).assertIsDisplayed()
        ui.onNodeWithText("Mark seen").assertIsNotEnabled()
        capture("progress-activity-dark.png")
    }
}
