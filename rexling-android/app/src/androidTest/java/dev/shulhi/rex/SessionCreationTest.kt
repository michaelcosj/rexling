package dev.shulhi.rex

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionCreationTest {
    @get:Rule val ui=createComposeRule()
    @Test fun creationKeepsTheNameWhenRejectedAndAcceptsAnAutomaticName() {
        val visible=mutableStateOf(true);val enabled=mutableStateOf(true)
        var accepted=false;val names=mutableListOf<String>();var dismissed=0
        ui.setContent {RexTheme(false,ThemeCatalog.defaults[1]) {
            if(visible.value) NewSessionDialog("Shulhi MBP",enabled.value,{name->names+=name;accepted}) {dismissed++;visible.value=false}
        }}
        ui.onNodeWithText("On Shulhi MBP").assertIsDisplayed()
        ui.onNode(hasSetTextAction()).performTextInput("New project")
        ui.onNodeWithText("Create").performClick()
        ui.onNode(hasSetTextAction()).assertTextContains("New project")
        ui.runOnIdle {assertEquals(listOf("New project"),names);assertEquals(0,dismissed);enabled.value=false}
        ui.onNodeWithText("Create").assertIsNotEnabled()
        ui.runOnIdle {enabled.value=true;accepted=true}
        ui.onNode(hasSetTextAction()).performTextClearance()
        ui.onNodeWithText("Create").performClick()
        ui.runOnIdle {assertEquals(listOf("New project",""),names);assertEquals(1,dismissed)}
        ui.onNodeWithText("New session").assertDoesNotExist()
    }
    @Test fun emptyConnectedServerHasAnExplicitCreationAction() {
        var requested=0
        ui.setContent {RexTheme(true,ThemeCatalog.defaults[0]) {
            EmptySessionContent("Ubuntu",true,{requested++})
        }}
        ui.onNodeWithText("Start a session on Ubuntu.").assertIsDisplayed()
        ui.onNodeWithText("New session").assertIsEnabled().performClick()
        ui.runOnIdle {assertEquals(1,requested)}
    }
}
