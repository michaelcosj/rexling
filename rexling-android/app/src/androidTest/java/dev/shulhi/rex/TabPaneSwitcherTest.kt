package dev.shulhi.rex

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TabPaneSwitcherTest {
    @get:Rule val ui=createComposeRule()
    private val windows=mutableStateOf(listOf(WindowItem("w1","Project",listOf(BlockItem("a","Terminal"),BlockItem("b","Claude"),BlockItem("d","nvim")),
        listOf(PanePlacement("a",0f,0f,.4f,1f),PanePlacement("b",.4f,0f,.6f,.5f),PanePlacement("d",.4f,.5f,.6f,.5f))),
        WindowItem("w2","Terminal 2",listOf(BlockItem("c","Codex")))))
    private val programs=mapOf("a" to ProgramIdentity.resolve(listOf("fish")),"b" to ProgramIdentity.resolve(listOf("claude")),"c" to ProgramIdentity.resolve(listOf("codex")),"d" to ProgramIdentity.resolve(listOf("nvim")))
    private val calls=mutableListOf<String>()
    private fun show(enabled: Boolean=true,initialPage: Int=0,empty: Boolean=false,dark: Boolean=false,selectedBlock: String="b",withoutLayout: Boolean=false) {
        ui.setContent {RexTheme(dark,ThemeCatalog.defaults[if(dark) 0 else 1]) {
            TabPaneSwitcher(if(empty) emptyList() else if(withoutLayout) windows.value.map {it.copy(layout=emptyList())} else windows.value,"w1",selectedBlock,programs,
                mapOf("b" to "Plan next steps","c" to "Review changes"),mapOf("a" to "~/Dev/rexling","b" to "~/Dev/portside"),
                {if(it.id=="w1") 1 else 0},enabled,enabled,enabled && !empty,
                {calls+="tab:$it"},{calls+="pane:$it"},{calls+="new-tab"},{anchor,split->calls+="new-pane:$anchor:${split.direction}"},
                {calls+="close-tab:${it.id}"},{calls+="close-pane:${it.id}"},{calls+="dismiss"},initialPage)
        }}
    }
    @Test fun tabsSelectLocallyUntilOpenIsPressed() {
        show()
        ui.onNodeWithContentDescription("Tab Project").assertIsSelected()
        ui.onNodeWithContentDescription("Tab Review changes").assertIsNotSelected()
        capture("tabs-light")
        ui.onNodeWithContentDescription("Tab Review changes").performClick().assertIsSelected()
        ui.runOnIdle {assertTrue(calls.isEmpty())}
        ui.onNodeWithText("Open tab").performClick()
        ui.runOnIdle {assertEquals(listOf("tab:w2"),calls)}
    }
    @Test fun swipingChangesSectionsAndPaneSelectionUsesTheCorrectId() {
        show(dark=true)
        ui.onNodeWithContentDescription("Tab and pane pages").performTouchInput {swipeLeft()}
        ui.onNodeWithText("Open pane").assertIsDisplayed()
        ui.onNodeWithContentDescription("Pane Plan next steps").assertIsSelected()
        ui.onNodeWithContentDescription("Pane fish").assertIsNotSelected()
        ui.onNodeWithText("~/Dev/rexling",substring=true).assertIsDisplayed()
        ui.onNodeWithContentDescription("Pane Review changes").assertDoesNotExist()
        capture("panes-dark")
        ui.runOnIdle {assertTrue(calls.isEmpty())}
        ui.onNodeWithContentDescription("Pane fish").performClick().assertIsSelected()
        ui.runOnIdle {assertTrue(calls.isEmpty())}
        ui.onNodeWithText("Open pane").performClick()
        ui.onNodeWithContentDescription("Tab and pane pages").performTouchInput {swipeRight()}
        ui.onNodeWithText("New tab").assertIsDisplayed()
        ui.runOnIdle {assertEquals(listOf("pane:a"),calls)}
    }
    @Test fun addAndCloseActionsBelongToTheirOwnSection() {
        show()
        ui.onNodeWithText("New tab").performClick()
        ui.onNodeWithContentDescription("Actions for tab Project").performClick()
        ui.onNodeWithText("Close tab").performClick()
        ui.onNodeWithText("Panes").performClick()
        chooseSplit("Split right")
        chooseSplit("Split below")
        ui.onNodeWithContentDescription("Actions for pane fish").performClick()
        ui.onNodeWithText("Close pane").performClick()
        ui.runOnIdle {assertEquals(listOf("new-tab","close-tab:w1","new-pane:b:horizontal","new-pane:b:vertical","close-pane:a"),calls)}
    }
    @Test fun busyStateDisablesTerminalActionsButAllowsBrowsingSections() {
        show(enabled=false)
        ui.onNodeWithContentDescription("Tab Project").assertIsNotEnabled()
        ui.onNodeWithText("New tab").assertIsNotEnabled()
        ui.onNodeWithContentDescription("Actions for tab Project").assertIsNotEnabled()
        ui.onNodeWithText("Panes").performClick()
        ui.onNodeWithContentDescription("Pane fish").assertIsNotEnabled()
        ui.onNodeWithText("New pane").assertIsNotEnabled()
        ui.onNodeWithContentDescription("Actions for pane fish").assertIsNotEnabled()
        ui.runOnIdle {assertTrue(calls.isEmpty())}
    }
    @Test fun emptySessionOffersANewTabButCannotAddAnUnanchoredPane() {
        show(empty=true)
        ui.onNodeWithText("No tabs yet").assertIsDisplayed()
        ui.onNodeWithText("New tab").performClick()
        ui.onNodeWithText("Panes").performClick()
        ui.onNodeWithText("No panes yet").assertIsDisplayed()
        ui.onNodeWithText("New pane").assertIsNotEnabled()
        ui.runOnIdle {assertEquals(listOf("new-tab"),calls)}
    }
    @Test fun paneIndicatorCanOpenDirectlyOnThePaneSection() {
        show(initialPage=1)
        ui.onNodeWithText("New pane").assertIsDisplayed()
        ui.onNodeWithContentDescription("Pane Plan next steps").assertIsSelected()
        capture("panes-light")
        ui.onNodeWithContentDescription("Close tab and pane switcher").performClick()
        ui.runOnIdle {assertEquals(listOf("dismiss"),calls)}
    }
    @Test fun aMissingSelectedPaneCannotCreateASplit() {
        show(initialPage=1,selectedBlock="stale-pane")
        ui.onNodeWithText("New pane").assertIsNotEnabled().performClick()
        ui.runOnIdle {assertTrue(calls.isEmpty())}
    }
    @Test fun unavailableGeometryKeepsThePaneListAndSplitActions() {
        show(initialPage=1,withoutLayout=true)
        ui.onNodeWithText("Layout unavailable").assertIsDisplayed()
        ui.onNodeWithContentDescription("Pane layout").assertDoesNotExist()
        ui.onNodeWithContentDescription("Pane fish").performClick()
        chooseSplit("Split below")
        ui.runOnIdle {assertEquals(listOf("new-pane:a:vertical"),calls)}
    }
    @Test fun splitPaneThreeWhileViewingPaneOneWithoutNavigating() {
        show(initialPage=1,selectedBlock="a")
        ui.onNodeWithContentDescription("Layout pane nvim").performClick().assertIsSelected()
        ui.onNodeWithContentDescription("Pane nvim").assertIsSelected()
        ui.onNodeWithContentDescription("Pane fish").assertIsNotSelected()
        ui.runOnIdle {assertTrue(calls.isEmpty())}
        capture("selected-three-light")
        chooseSplit("Split below")
        ui.runOnIdle {assertEquals(listOf("new-pane:d:vertical"),calls)}
    }
    @Test fun selectingATabLetsYouManageItsPanesWithoutOpeningIt() {
        show()
        ui.onNodeWithContentDescription("Tab Review changes").performClick()
        ui.onNodeWithText("Panes").performClick()
        ui.onNodeWithContentDescription("Pane Review changes").assertIsSelected()
        ui.onNodeWithContentDescription("Pane fish").assertDoesNotExist()
        ui.runOnIdle {assertTrue(calls.isEmpty())}
        chooseSplit("Split right")
        ui.runOnIdle {assertEquals(listOf("new-pane:c:horizontal"),calls)}
    }
    @Test fun fivePanesFitWithTheLayoutAndActionsVisible() {
        val blocks=windows.value.first().blocks+listOf(BlockItem("e","Build"),BlockItem("f","Logs"))
        windows.value=windows.value.mapIndexed {index,w->if(index==0) w.copy(blocks=blocks,layout=blocks.mapIndexed {i,b->PanePlacement(b.id,i*.2f,0f,.2f,1f)}) else w}
        show(initialPage=1)
        ui.onNodeWithContentDescription("Pane layout").assertIsDisplayed()
        for(title in listOf("fish","Plan next steps","nvim","Build","Logs")) ui.onNodeWithContentDescription("Pane $title").assertIsDisplayed()
        ui.onNodeWithText("Open pane").assertIsDisplayed()
        ui.onNodeWithText("New pane").assertIsDisplayed()
        capture("five-panes")
        ui.runOnIdle {assertTrue(calls.isEmpty())}
    }
    @Test fun removingTheSelectedTargetDisablesOpenAndSplitInsteadOfUsingAnotherPane() {
        show(initialPage=1,selectedBlock="a")
        ui.onNodeWithContentDescription("Pane nvim").performClick()
        ui.runOnIdle {windows.value=windows.value.map {it.copy(blocks=it.blocks.filterNot {b->b.id=="d"},layout=it.layout.filterNot {p->p.id=="d"})}}
        ui.onNodeWithText("Open pane").assertIsNotEnabled().performClick()
        ui.onNodeWithText("New pane").assertIsNotEnabled().performClick()
        ui.runOnIdle {assertTrue(calls.isEmpty())}
    }
    @Test fun directionMenuCanBeDismissedWithoutCreatingOrNavigating() {
        show(initialPage=1,dark=true)
        ui.onNodeWithText("Split right").assertDoesNotExist()
        ui.onNodeWithText("Split below").assertDoesNotExist()
        ui.onNodeWithText("New pane").performClick()
        ui.onNodeWithText("Split right").assertIsDisplayed()
        ui.onNodeWithText("Split below").assertIsDisplayed()
        capture("direction-menu-dark")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        ui.onNodeWithText("Split right").assertDoesNotExist()
        ui.onNodeWithText("New pane").assertIsDisplayed()
        ui.runOnIdle {assertTrue(calls.isEmpty())}
    }
    @Test fun removingTheAnchorWhileTheDirectionMenuIsOpenDismissesIt() {
        show(initialPage=1,selectedBlock="a")
        ui.onNodeWithText("New pane").performClick()
        ui.onNodeWithText("Split right").assertIsDisplayed()
        capture("direction-menu-light")
        ui.runOnIdle {windows.value=windows.value.map {it.copy(blocks=it.blocks.filterNot {b->b.id=="a"},layout=it.layout.filterNot {p->p.id=="a"})}}
        ui.onNodeWithText("Split right").assertDoesNotExist()
        ui.onNodeWithText("New pane").assertIsNotEnabled()
        ui.runOnIdle {assertTrue(calls.isEmpty())}
    }
    private fun chooseSplit(label: String) {
        ui.onNodeWithText("New pane").performClick()
        ui.onNodeWithText(label).performClick()
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync();Thread.sleep(300)
        File(instrumentation.targetContext.cacheDir,"switcher-$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
}
