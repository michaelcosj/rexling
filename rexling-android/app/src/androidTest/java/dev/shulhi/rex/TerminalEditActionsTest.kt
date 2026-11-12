package dev.shulhi.rex

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalEditActionsTest {
    @get:Rule val ui=createComposeRule()
    private val window=mutableStateOf(WindowItem("w","Project",listOf(BlockItem("a","shell"),BlockItem("b","shell"),BlockItem("f","shell")),
        listOf(PanePlacement("a",0f,0f,.5f,1f),PanePlacement("b",.5f,0f,.5f,1f),PanePlacement("f",.1f,.1f,.7f,.7f,floating=true))))
    private val calls=mutableListOf<String>()
    private fun show(page: Int) {
        ui.setContent {RexTheme(false,ThemeCatalog.defaults[1]) {
            TabPaneSwitcher(listOf(window.value),"w","a",mapOf("a" to ProgramIdentity.resolve(listOf("fish")),"b" to ProgramIdentity.resolve(listOf("claude")),"f" to ProgramIdentity.resolve(listOf("codex"))),
                mapOf("b" to "Agent task","f" to "Floating task"),emptyMap(),{0},true,true,true,
                {calls+="open-tab"},{calls+="open-pane"},{},{_,_->},{},{},{},initialPage=page,
                onRenameTab={id,label->calls+="rename-tab:$id:$label";true},onRenamePane={tab,id,label->calls+="rename-pane:$tab:$id:$label";true},
                onFloatPane={tab,id->calls+="float:$tab:$id";window.value=window.value.copy(layout=window.value.layout.map {if(it.id==id) it.copy(floating=true) else it});true},
                onDockPane={tab,id,anchor,split->calls+="dock:$tab:$id:$anchor:${split.name}";true})
        }}
    }
    @Test fun tabAndPaneRenameActionsNeverOpenTheTerminal() {
        show(0)
        ui.onNodeWithContentDescription("Actions for tab Project").performClick()
        ui.onNodeWithText("Rename tab").performClick()
        ui.onNode(hasSetTextAction()).performTextReplacement("Release")
        ui.onNodeWithText("Save").performClick()
        ui.onNodeWithText("Panes").performClick()
        ui.onNodeWithContentDescription("Actions for pane fish").performClick()
        ui.onNodeWithText("Rename pane").performClick()
        ui.onNode(hasSetTextAction()).performTextReplacement("Console")
        ui.onNodeWithText("Save").performClick()
        ui.runOnIdle {assertEquals(listOf("rename-tab:w:Release","rename-pane:w:a:Console"),calls)}
    }
    @Test fun dockingChoosesTheTargetAndSideWhileFloatingKeepsOneTiledPane() {
        show(1)
        ui.onNodeWithContentDescription("Actions for pane Floating task").performClick()
        ui.onNodeWithText("Dock pane").performClick()
        ui.onNodeWithText("Split below").performClick()
        ui.onNodeWithText("Dock").performClick()
        ui.onNodeWithContentDescription("Actions for pane Agent task").performClick()
        ui.onNodeWithText("Float pane").performClick()
        ui.onNodeWithContentDescription("Actions for pane fish").performClick()
        ui.onNodeWithText("Float pane").assertIsNotEnabled()
        ui.onNodeWithText("Keep one docked pane in this tab").assertIsDisplayed()
        ui.runOnIdle {assertEquals(listOf("dock:w:f:a:BELOW","float:w:b"),calls)}
    }
    @Test fun rejectedRenameKeepsTheInputAndSupportsResetToAutomatic() {
        val target=TerminalRenameTarget("w","a",false,"My pane");var accept=false;var closed=0;var value=""
        ui.setContent {RexTheme(true,ThemeCatalog.defaults[0]) {
            TerminalRenameDialog(target,true,{value=it;accept}) {closed++}
        }}
        ui.onNodeWithText("Save").performClick()
        ui.onNode(hasSetTextAction()).assertTextContains("My pane")
        ui.runOnIdle {assertEquals(0,closed);accept=true}
        ui.onNode(hasSetTextAction()).performTextClearance()
        ui.onNodeWithText("Save").performClick()
        ui.runOnIdle {assertEquals("",value);assertEquals(1,closed)}
    }
}
