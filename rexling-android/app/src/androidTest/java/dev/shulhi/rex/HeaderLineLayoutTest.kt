package dev.shulhi.rex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeaderLineLayoutTest {
    @get:Rule val ui=createComposeRule()
    private val titles=listOf("Task title","* Task title","✳ Task title","✻ Task title","✽ Task title","✢ Task title","✳️ Task title","⠋ Task title","🚀 Task title")
    private fun header(title: State<String>,directory: State<String>,scale: State<Float>) {
        ui.setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density,scale.value)) {MaterialTheme {
                Box(Modifier.width(340.dp).testTag("header")) {
                    SessionHeader(listOf(SessionItem("a","alpha · Mac")),"a",true,true,
                        listOf(ProgramIdentity(ProgramKind.CLAUDE,"Claude Code")),0,directory.value,{},{},{},title=title.value,serverIcons=mapOf("a" to ServerIcon.MACBOOK),menu={})
                }
            }}
        }
    }
    private fun bounds(node: SemanticsNodeInteraction)=node.fetchSemanticsNode().boundsInRoot
    private fun baseline(node: SemanticsNodeInteraction): Float {
        val coordinates=node.fetchSemanticsNode().layoutInfo.coordinates
        return coordinates.positionInRoot().y+coordinates[FirstBaseline]
    }
    @Test fun claudeSpinnerChangesKeepAllThreeRowsAndTheirBaselinesInPlace() {
        val title=mutableStateOf(titles.first());val directory=mutableStateOf("~/Dev/rexlink");val scale=mutableFloatStateOf(1f)
        header(title,directory,scale)
        for(fontScale in listOf(1f,1.5f,2f)) {
            ui.runOnIdle {scale.floatValue=fontScale;title.value=titles.first()}
            val initial=ui.onNodeWithText(title.value).fetchSemanticsNode().layoutInfo
            val original=initial.width to initial.height;val base=baseline(ui.onNodeWithText(title.value))
            val cwd=bounds(ui.onNodeWithText(directory.value));val strip=bounds(ui.onNodeWithContentDescription("Session strip"));val root=bounds(ui.onNodeWithTag("header"))
            for(value in titles.drop(1)) {
                ui.runOnIdle {title.value=value}
                // Glyph bounds can vary; the allocated line and placed baseline must not.
                val line=ui.onNodeWithText(value).fetchSemanticsNode().layoutInfo
                assertEquals(value,original,line.width to line.height)
                assertEquals(value,base,baseline(ui.onNodeWithText(value)),.6f)
                assertEquals(value,cwd,bounds(ui.onNodeWithText(directory.value)))
                assertEquals(value,strip,bounds(ui.onNodeWithContentDescription("Session strip")))
                assertEquals(value,root,bounds(ui.onNodeWithTag("header")))
            }
        }
    }
    @Test fun aTemporarilyMissingDirectoryKeepsItsLineReserved() {
        val title=mutableStateOf("Task title");val directory=mutableStateOf("~/Dev/rexlink");val scale=mutableFloatStateOf(1f)
        header(title,directory,scale)
        val original=bounds(ui.onNodeWithText(title.value));val strip=bounds(ui.onNodeWithContentDescription("Session strip"));val root=bounds(ui.onNodeWithTag("header"))
        ui.runOnIdle {directory.value=""}
        assertEquals(original,bounds(ui.onNodeWithText(title.value)));assertEquals(strip,bounds(ui.onNodeWithContentDescription("Session strip")));assertEquals(root,bounds(ui.onNodeWithTag("header")))
        ui.runOnIdle {directory.value="~/Another/directory"}
        assertEquals(original,bounds(ui.onNodeWithText(title.value)));assertEquals(strip,bounds(ui.onNodeWithContentDescription("Session strip")));assertEquals(root,bounds(ui.onNodeWithTag("header")))
    }
}
