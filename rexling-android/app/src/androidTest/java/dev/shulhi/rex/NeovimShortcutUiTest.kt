package dev.shulhi.rex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeovimShortcutUiTest {
    @get:Rule val ui=createComposeRule()
    @Test fun menuSelectsNamedKeysForItsCapturedPaneAndCanOpenCustomization() {
        val sent=mutableListOf<Pair<NeovimShortcut,String>>()
        var customized=false
        val items=NeovimShortcuts.parse(":w\nLazyGit = <Space>lg")
        ui.setContent {MaterialTheme {NeovimShortcutButton(items,"pane-one",true,{item,target->sent+=item to target},{customized=true})}}
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        ui.onNodeWithText("LazyGit").performClick()
        ui.runOnIdle {assertEquals(listOf(items[1] to "pane-one"),sent)}
        ui.onNodeWithText("LazyGit").assertIsDisplayed().performClick()
        ui.onNodeWithText(":w").performClick()
        ui.runOnIdle {assertEquals(listOf(items[1] to "pane-one",items[1] to "pane-one",items[0] to "pane-one"),sent)}
        ui.onNodeWithText("Customize shortcuts").performClick()
        ui.runOnIdle {assertTrue(customized)}
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertDoesNotExist()
    }
    @Test fun gridMenuIgnoresOutsideTapsAndClosesFromItsCloseButton()=checkExplicitClose(NeovimShortcutLayout.GRID)
    @Test fun circularMenuIgnoresOutsideTapsAndClosesFromItsCloseButton()=checkExplicitClose(NeovimShortcutLayout.CIRCULAR)
    private fun checkExplicitClose(layout: NeovimShortcutLayout) {
        var sent=0
        ui.setContent {MaterialTheme {Box(Modifier.size(320.dp,500.dp).semantics {contentDescription="Vim menu area"}) {
            NeovimShortcutButton(NeovimShortcuts.defaults,"one",true,{_,_->sent++},{},layout=layout)
        }}}
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val automation=instrumentation.uiAutomation
        val info=automation.serviceInfo;info.flags=info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.serviceInfo=info
        fun find(node: AccessibilityNodeInfo?): Rect? {
            if(node==null) return null
            if(node.contentDescription?.toString()=="Vim menu area") return Rect().also {node.getBoundsInScreen(it)}.takeUnless {it.isEmpty}
            for(i in 0 until node.childCount) find(node.getChild(i))?.let {return it}
            return null
        }
        var area: Rect?=null
        val deadline=SystemClock.uptimeMillis()+4000
        while(area==null && SystemClock.uptimeMillis()<deadline) {
            area=automation.windows.firstNotNullOfOrNull {find(it.root)}
            if(area==null) SystemClock.sleep(20)
        }
        val bounds=area ?: error("No native terminal area")
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        repeat(2) {ui.onNodeWithText(":w").performTouchInput {click()}}
        ui.runOnIdle {assertEquals(2,sent)}
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);ui.waitForIdle()
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertIsDisplayed()
        val down=SystemClock.uptimeMillis()
        val y=bounds.top+80*instrumentation.targetContext.resources.displayMetrics.density
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),y,0)
            instrumentation.sendPointerSync(event);event.recycle()
        }
        ui.waitForIdle()
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertIsDisplayed()
        if(layout==NeovimShortcutLayout.CIRCULAR) {
            val menu=nativeBounds("Neovim shortcut menu")
            nativeTap(menu.left+3f,menu.top+3f)
            ui.onNodeWithContentDescription("Neovim shortcut menu").assertIsDisplayed()
        }
        ui.onNodeWithContentDescription("Close Neovim shortcuts").performClick()
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertDoesNotExist()
        ui.runOnIdle {assertEquals(2,sent)}
    }
    @Test fun switchingPanesOrLosingControlClosesTheOpenMenu() {
        val target=mutableStateOf("one");val enabled=mutableStateOf(true)
        var sent=0
        ui.setContent {MaterialTheme {NeovimShortcutButton(NeovimShortcuts.defaults,target.value,enabled.value,{_,_->sent++},{})}}
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick();ui.onNodeWithText(":q").assertIsDisplayed()
        ui.runOnIdle {target.value="two"};ui.onNodeWithText(":q").assertDoesNotExist()
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        ui.runOnIdle {enabled.value=false};ui.onNodeWithText(":q").assertDoesNotExist()
        ui.onNodeWithContentDescription("Neovim shortcuts").assertIsNotEnabled()
        ui.runOnIdle {assertEquals(0,sent)}
    }
    @Test fun profilesSheetChangesSharedMenuSettingsAndProfileSwitches() {
        val profiles=mutableStateOf(ShortcutProfiles.defaults())
        val layout=mutableStateOf(NeovimShortcutLayout.GRID)
        val effect=mutableStateOf(NeovimShortcutEffect.FAN_OUT)
        val haptics=mutableStateOf(true)
        var edited="";var created=false
        ui.setContent {MaterialTheme {Box(Modifier.fillMaxSize()) {
            ShortcutProfilesSheet(profiles.value,{edited=it},{created=true},{id,on->profiles.value=profiles.value.map {if(it.id==id) it.copy(enabled=on) else it}},{},
                layout.value,{layout.value=it},effect.value,{effect.value=it},haptics.value,{haptics.value=it})
        }}}
        ui.onNodeWithText("Neovim").assertIsDisplayed()
        ui.onNodeWithText("fish, zsh, bash, sh").assertIsDisplayed()
        ui.onNodeWithContentDescription("Show Neovim shortcuts").assertIsOn().performClick()
        ui.runOnIdle {assertFalse(profiles.value.first {it.id==ShortcutProfiles.NEOVIM}.enabled)}
        ui.onNodeWithContentDescription("Edit Shell profile").performClick()
        ui.runOnIdle {assertEquals(ShortcutProfiles.SHELL,edited)}
        ui.onNodeWithText("New profile").performScrollTo().performClick()
        ui.runOnIdle {assertTrue(created)}
        ui.onNodeWithText("Grid").performScrollTo().assertIsSelected()
        ui.onNodeWithText("Circular").performClick().assertIsSelected()
        ui.onNodeWithText("Fan out").performScrollTo().assertIsSelected()
        ui.onNodeWithText("Shutter").assertDoesNotExist()
        ui.onNodeWithText("Spin").performScrollTo().performClick().assertIsSelected()
        ui.runOnIdle {assertEquals(NeovimShortcutEffect.SPIN,effect.value)}
        ui.onNodeWithText("None").performScrollTo().performClick().assertIsSelected()
        ui.runOnIdle {assertEquals(NeovimShortcutEffect.NONE,effect.value)}
        ui.onNodeWithText("Selection haptics").performScrollTo().assertIsOn().performClick().assertIsOff()
        ui.runOnIdle {assertFalse(haptics.value)}
    }
    @Test fun editorValidatesAndSavesCustomKeysWithoutAddingEnter() {
        var saved: ShortcutProfile?=null;var closed=false
        ui.setContent {MaterialTheme {Box(Modifier.fillMaxSize()) {
            ShortcutProfileSheet(ShortcutProfiles.neovim(),{name,match,prefix,enter,text->
                runCatching {saved=ShortcutProfiles.build(ShortcutProfiles.NEOVIM,name,match,prefix,enter,true,text,emptyList())}.exceptionOrNull()?.message
            },{closed=true},defaults=NeovimShortcuts.defaults)
        }}}
        ui.onNodeWithText(": commands").performScrollTo().assertIsSelected()
        ui.onNodeWithText("Shortcuts").performScrollTo().performTextReplacement("LazyGit = <Leader>lg")
        ui.onNodeWithText("Save").performScrollTo().performClick()
        ui.waitUntil(4000) {ui.onAllNodesWithText("Use your actual leader key, such as <Space>, instead of <Leader>.").fetchSemanticsNodes().isNotEmpty()}
        ui.runOnIdle {assertFalse(closed);assertNull(saved)}
        ui.onNodeWithText("Shortcuts").performTextReplacement(":w\nLazyGit = <Space>lg\n!Quit all = :qa!")
        ui.onNodeWithText("Save").performScrollTo().performClick()
        ui.runOnIdle {
            assertTrue(closed)
            assertEquals(NeovimShortcuts.parse(":w\nLazyGit = <Space>lg\n!Quit all = :qa!"),saved?.items)
            assertTrue(saved!!.items.last().confirm)
            assertEquals(listOf("nvim","neovim"),saved!!.match)
        }
    }
    @Test fun draggingMovesTheButtonWithoutOpeningTheMenuAndStaysInsideAResizedTerminal()=checkClosedDragging(NeovimShortcutLayout.GRID)
    @Test fun closedWheelCanStillBeDraggedWithoutOpeningOrSending()=checkClosedDragging(NeovimShortcutLayout.CIRCULAR)
    private fun checkClosedDragging(layout: NeovimShortcutLayout) {
        val position=mutableStateOf(Offset(.2f,.6f));val height=mutableFloatStateOf(400f)
        var moved=0;var sent=0;var terminalTaps=0
        ui.setContent {MaterialTheme {Box(Modifier.size(320.dp,height.floatValue.dp).testTag("Terminal area")) {
            Box(Modifier.fillMaxSize().clickable {terminalTaps++})
            NeovimShortcutButton(NeovimShortcuts.defaults,"one",true,{_,_->sent++},{},position=position.value,onPosition={position.value=it;moved++},layout=layout)
        }}}
        val button=ui.onNodeWithContentDescription("Neovim shortcuts")
        val before=button.fetchSemanticsNode().boundsInRoot
        captureMenu("closed-command-button","Neovim shortcuts")
        button.performTouchInput {swipe(center,center+Offset(100f,-90f),300)}
        val after=button.fetchSemanticsNode().boundsInRoot
        assertTrue(after.left>before.left);assertTrue(after.top<before.top)
        ui.onNodeWithText(":q").assertDoesNotExist()
        ui.onNodeWithTag("Terminal area").performTouchInput {click(Offset(center.x,20f))}
        ui.runOnIdle {assertEquals(1,terminalTaps)}
        ui.runOnIdle {assertEquals(1,moved);assertEquals(0,sent);height.floatValue=200f}
        val resized=button.fetchSemanticsNode().boundsInRoot
        val area=ui.onNodeWithTag("Terminal area").fetchSemanticsNode().boundsInRoot
        assertTrue(resized.left>=area.left && resized.right<=area.right)
        assertTrue(resized.top>=area.top && resized.bottom<=area.bottom)
        button.performClick();ui.onNodeWithText(":q").assertIsDisplayed()
    }
    @Test fun compactGridPagesVerticallyWithoutSelectingAndKeepsTheSameBounds() {
        val dark=mutableStateOf(false)
        val items=NeovimShortcuts.defaults+(7..36).map {NeovimShortcut("Action $it",":echo $it")}
        val selected=mutableListOf<NeovimShortcut>()
        ui.setContent {RexTheme(dark.value,ThemeCatalog.defaults[if(dark.value) 0 else 1]) {
            NeovimShortcutButton(items,"one",true,{item,_->selected+=item},{})
        }}
        captureMenu("button","Neovim shortcuts")
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        val grid=ui.onNodeWithContentDescription("Neovim shortcut pages")
        val before=grid.fetchSemanticsNode().boundsInRoot
        NeovimShortcuts.defaults.forEach {ui.onNodeWithText(it.label).assertIsDisplayed()}
        ui.onNodeWithText("Action 7").assertIsNotDisplayed()
        captureMenu("first-light")
        grid.performTouchInput {swipeUp()};ui.waitForIdle()
        ui.onNodeWithText("Action 7").assertIsDisplayed()
        ui.onNodeWithText(":w").assertIsNotDisplayed()
        assertEquals(before,grid.fetchSemanticsNode().boundsInRoot)
        grid.performTouchInput {swipeDown()};ui.waitForIdle()
        ui.onNodeWithText(":w").assertIsDisplayed()
        repeat(5) {grid.performTouchInput {swipeUp()};ui.waitForIdle()}
        ui.onNodeWithText("Action 31").assertIsDisplayed()
        ui.onNodeWithText("6/6").assertIsDisplayed()
        assertEquals(before,grid.fetchSemanticsNode().boundsInRoot)
        ui.runOnIdle {assertTrue(selected.isEmpty());dark.value=true}
        captureMenu("last-dark")
        grid.performTouchInput {swipeUp()};ui.waitForIdle()
        ui.onNodeWithText("1/6").assertIsDisplayed()
        ui.onNodeWithText(":w").assertIsDisplayed()
        grid.performTouchInput {swipeDown()};ui.waitForIdle()
        ui.onNodeWithText("6/6").assertIsDisplayed()
        ui.onNodeWithText("Action 31").performClick()
        ui.runOnIdle {assertEquals(listOf(items[30]),selected)}
    }
    @Test fun groupPillReplacesTheGridWithPagedChildrenAndBackRestoresIt() {
        val dark=mutableStateOf(false)
        val items=NeovimShortcuts.parse(":w\n[Git]\n"+(1..19).joinToString("\n") {"Action $it = :echo $it"}+"\n[]\n:q")
        val selected=mutableListOf<NeovimShortcut>()
        ui.setContent {RexTheme(dark.value,ThemeCatalog.defaults[if(dark.value) 0 else 1]) {
            NeovimShortcutButton(items,"one",true,{item,_->selected+=item},{})
        }}
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        val bounds=ui.onNodeWithContentDescription("Neovim shortcut menu").fetchSemanticsNode().boundsInRoot
        captureMenu("groups-root-light")
        ui.onNodeWithContentDescription("Shortcut group Git").performClick()
        ui.onNodeWithText("Action 1").assertIsDisplayed()
        ui.onNodeWithText(":w").assertDoesNotExist()
        ui.onNodeWithContentDescription("Shortcut group Git").assertDoesNotExist()
        assertEquals(bounds,ui.onNodeWithContentDescription("Neovim shortcut menu").fetchSemanticsNode().boundsInRoot)
        ui.runOnIdle {assertTrue(selected.isEmpty())}
        captureMenu("groups-child-light")
        ui.onNodeWithContentDescription("Back to all shortcuts").performClick()
        ui.onNodeWithText(":w").assertIsDisplayed()
        ui.onNodeWithContentDescription("Shortcut group Git").assertIsDisplayed()
        ui.runOnIdle {dark.value=true}
        captureMenu("groups-root-dark")
        ui.onNodeWithContentDescription("Shortcut group Git").performClick()
        repeat(3) {ui.onNodeWithContentDescription("Neovim shortcut pages").performTouchInput {swipeUp()};ui.waitForIdle()}
        ui.onNodeWithText("Action 19").assertIsDisplayed().performClick()
        ui.onNodeWithText("Action 19").assertIsDisplayed().performClick()
        ui.runOnIdle {assertEquals(listOf(items[19],items[19]),selected)}
    }
    @Test fun backFromAGroupRemembersTheRootPage() {
        val items=NeovimShortcuts.parse((1..7).joinToString("\n") {"Root $it = :echo $it"}+"\n[Git]\nLazyGit = <Space>lg")
        ui.setContent {MaterialTheme {NeovimShortcutButton(items,"one",true,{_,_->error("Group navigation must not send keys")},{})}}
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        ui.onNodeWithContentDescription("Neovim shortcut pages").performTouchInput {swipeUp()};ui.waitForIdle()
        ui.onNodeWithContentDescription("Shortcut group Git").performClick()
        ui.onNodeWithText("LazyGit").assertIsDisplayed()
        ui.onNodeWithContentDescription("Back to all shortcuts").performClick()
        ui.onNodeWithText("2/2").assertIsDisplayed()
        ui.onNodeWithText("Root 7").assertIsDisplayed()
        val hiddenRoots=ui.onAllNodesWithText("Root 1")
        hiddenRoots.fetchSemanticsNodes().indices.forEach {hiddenRoots[it].assertIsNotDisplayed()}
    }
    @Test fun circularWheelPagesGroupsAndKeepsActionsOpen() {
        val dark=mutableStateOf(false);var customized=false
        val items=NeovimShortcuts.parse(":w\n[Git]\n"+(1..19).joinToString("\n") {"Action $it = :echo $it"}+"\n[]\n:q")
        val sent=mutableListOf<NeovimShortcut>()
        ui.setContent {RexTheme(dark.value,ThemeCatalog.defaults[if(dark.value) 0 else 1]) {
            NeovimShortcutButton(items,"one",true,{item,_->sent+=item},{customized=true},layout=NeovimShortcutLayout.CIRCULAR)
        }}
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        val bounds=ui.onNodeWithContentDescription("Neovim shortcut menu").fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.width,bounds.height,1f)
        captureMenu("circular-root-light")
        ui.onNodeWithText(":w").performTouchInput {click()}
        ui.onNodeWithContentDescription("Shortcut group Git").performClick()
        ui.runOnIdle {assertEquals(listOf(items[0]),sent)}
        val cells=(1..8).map {ui.onNodeWithText("Action $it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot}
        val center=ui.onNodeWithContentDescription("Back to all shortcuts").fetchSemanticsNode().boundsInRoot
        cells.forEach {assertTrue("Labels must stay outside the neutral hub",(it.center-center.center).getDistance()>center.width/2)}
        captureMenu("circular-child-light")
        ui.runOnIdle {dark.value=true}
        captureMenu("circular-child-dark")
        repeat(2) {ui.onNodeWithContentDescription("Neovim shortcut pages").performTouchInput {swipeUp()};ui.waitForIdle()}
        ui.onNodeWithText("3/3").assertIsDisplayed()
        ui.onNodeWithContentDescription("Neovim shortcut pages").performTouchInput {swipeUp()};ui.waitForIdle()
        ui.onNodeWithText("1/3").assertIsDisplayed()
        ui.onNodeWithText("Action 1").assertIsDisplayed()
        ui.onNodeWithContentDescription("Neovim shortcut pages").performTouchInput {swipeDown()};ui.waitForIdle()
        ui.onNodeWithText("3/3").assertIsDisplayed()
        repeat(2) {ui.onNodeWithText("Action 19").performTouchInput {click()}}
        ui.runOnIdle {assertEquals(listOf(items[0],items[19],items[19]),sent)}
        assertEquals(bounds,ui.onNodeWithContentDescription("Neovim shortcut menu").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithContentDescription("Back to all shortcuts").performClick()
        ui.onNodeWithContentDescription("Shortcut group Git").assertIsDisplayed()
        ui.onNodeWithContentDescription("Close Neovim shortcuts").performTouchInput {click()}
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertDoesNotExist()
        ui.onNodeWithContentDescription("Neovim shortcuts").performTouchInput {click()}
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertIsDisplayed()
        ui.onNodeWithContentDescription("Close Neovim shortcuts").performTouchInput {click()}
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertDoesNotExist()
        ui.runOnIdle {assertFalse(customized);assertEquals(listOf(items[0],items[19],items[19]),sent)}
    }
    @Test fun openingTheWheelKeepsItsButtonFixedAndCanOverlapThePane() {
        val position=mutableStateOf(Offset(0f,1f));var saved=0
        ui.setContent {MaterialTheme {Box(Modifier.fillMaxSize().semantics {contentDescription="Wheel app area"}.padding(top=80.dp,bottom=100.dp)) {
            Box(Modifier.size(320.dp,350.dp).semantics {contentDescription="Radial terminal area"}) {
                NeovimShortcutButton(NeovimShortcuts.defaults,"one",true,{_,_->},{},position=position.value,
                    onPosition={position.value=it;saved++},layout=NeovimShortcutLayout.CIRCULAR)
            }
        }}}
        ui.waitForIdle()
        val area=nativeBounds("Radial terminal area")
        val app=nativeBounds("Wheel app area")
        val logicalArea=ui.onNodeWithContentDescription("Radial terminal area").fetchSemanticsNode().boundsInWindow
        val windowOffset=Offset(area.left-logicalArea.left,area.top-logicalArea.top)
        val rest=nativeBounds("Neovim shortcuts")
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick();ui.waitForIdle()
        fun checkCentered(resting: Rect) {
            assertEquals("Opening the wheel must not move its button",resting,nativeBounds("Neovim shortcuts"))
            // Translate the Compose anchor into native screen coordinates.
            val button=ui.onNodeWithContentDescription("Neovim shortcuts").fetchSemanticsNode().boundsInWindow
            val buttonCenter=button.center+windowOffset
            val wheel=nativeBounds("Neovim shortcut menu")
            val center=nativeBounds("Close Neovim shortcuts")
            assertEquals(buttonCenter.x,wheel.exactCenterX(),2f)
            assertEquals(buttonCenter.y,wheel.exactCenterY(),2f)
            assertEquals(buttonCenter.x,center.exactCenterX(),2f)
            assertEquals(buttonCenter.y,center.exactCenterY(),2f)
            assertTrue("The wheel must fit the app's visible area",app.contains(wheel))
            assertFalse("The wheel may overlap the pane's surrounding chrome",area.contains(wheel))
        }
        checkCentered(rest)
        captureMenu("anchored-wheel-bottom-left")
        nativeTap(area.exactCenterX(),area.top+40f)
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertIsDisplayed()
        ui.onNodeWithContentDescription("Close Neovim shortcuts").performClick()
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertDoesNotExist()
        assertEquals(rest,nativeBounds("Neovim shortcuts"))
        ui.runOnIdle {assertEquals(0,saved);position.value=Offset(1f,0f)}
        ui.waitUntil(2500) {nativeBounds("Neovim shortcuts").let {it.left>rest.left && it.top<rest.top}}
        val topRight=nativeBounds("Neovim shortcuts")
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick();ui.waitForIdle()
        checkCentered(topRight)
        captureMenu("anchored-wheel-top-right")
        nativeTap(area.left+20f,area.bottom-30f)
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertIsDisplayed()
        ui.onNodeWithContentDescription("Close Neovim shortcuts").performClick()
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertDoesNotExist()
        ui.runOnIdle {assertEquals(Offset(1f,0f),position.value);assertEquals(0,saved)}
    }
    @Test fun steeringAnOpenWheelSelectsOnceWithoutMovingItOrChangingPages() {
        val position=mutableStateOf(Offset(.5f,.5f));var saved=0
        val sent=mutableListOf<NeovimShortcut>()
        val items=NeovimShortcuts.parse("[Git]\n"+(1..9).joinToString("\n") {"Action $it = :echo $it"})
        ui.setContent {MaterialTheme {Box(Modifier.size(360.dp,560.dp).padding(top=40.dp)) {
            NeovimShortcutButton(items,"one",true,{item,_->sent+=item},{error("Steering must not open settings")},position=position.value,
                onPosition={position.value=it;saved++},layout=NeovimShortcutLayout.CIRCULAR)
        }}}
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        ui.onNodeWithContentDescription("Shortcut group Git").performClick()
        ui.onNodeWithContentDescription("Neovim shortcut pages").performTouchInput {swipeUp()}
        ui.onNodeWithText("2/2").assertIsDisplayed()
        val center=nativeBounds("Back to all shortcuts")
        val action=nativeBounds("Neovim shortcut Action 9")
        val before=nativeBounds("Neovim shortcut menu")
        nativeDrag(center.exactCenterX(),center.exactCenterY(),action.exactCenterX(),action.exactCenterY())
        ui.runOnIdle {assertEquals(listOf(items[8]),sent);assertEquals(0,saved)}
        assertEquals(before,nativeBounds("Neovim shortcut menu"))
        ui.onNodeWithText("2/2").assertIsDisplayed()
        nativeDrag(center.exactCenterX(),center.exactCenterY(),action.exactCenterX(),action.exactCenterY(),cancel=true)
        ui.runOnIdle {assertEquals(1,sent.size);assertEquals(0,saved);assertEquals(Offset(.5f,.5f),position.value)}
        ui.onNodeWithContentDescription("Back to all shortcuts").performClick()
        ui.onNodeWithContentDescription("Shortcut group Git").assertIsDisplayed()
        ui.onNodeWithContentDescription("Close Neovim shortcuts").performClick()
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertDoesNotExist()
    }
    @Test fun holdingTheClosedButtonSteersOnReleaseAndReturningToCenterCancels() {
        val sent=mutableListOf<Pair<NeovimShortcut,String>>()
        ui.setContent {MaterialTheme {Box(Modifier.size(360.dp,560.dp).padding(top=40.dp)) {
            NeovimShortcutButton(NeovimShortcuts.defaults,"captured-pane",true,{item,target->sent+=item to target},{},
                position=Offset(.5f,.5f),layout=NeovimShortcutLayout.CIRCULAR)
        }}}
        val button=nativeBounds("Neovim shortcuts");val down=SystemClock.uptimeMillis()
        ui.mainClock.autoAdvance=false
        nativeWheelEvent(MotionEvent.ACTION_DOWN,button.exactCenterX(),button.exactCenterY(),down)
        ui.mainClock.advanceTimeBy(650);repeat(3) {ui.mainClock.advanceTimeByFrame()};ui.waitForIdle()
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertIsDisplayed()
        val action=nativeBounds("Neovim shortcut :w")
        nativeWheelEvent(MotionEvent.ACTION_MOVE,action.exactCenterX(),action.exactCenterY(),down)
        ui.onNodeWithContentDescription("Neovim shortcut :w").assertIsSelected()
        captureMenu("steering-selected")
        ui.mainClock.advanceTimeBy(900)
        ui.runOnIdle {assertTrue("Hovering never repeats commands",sent.isEmpty())}
        nativeWheelEvent(MotionEvent.ACTION_UP,action.exactCenterX(),action.exactCenterY(),down)
        ui.runOnIdle {assertEquals(listOf(NeovimShortcuts.defaults[0] to "captured-pane"),sent)}
        val center=nativeBounds("Close Neovim shortcuts");val second=SystemClock.uptimeMillis()
        nativeWheelEvent(MotionEvent.ACTION_DOWN,center.exactCenterX(),center.exactCenterY(),second)
        nativeWheelEvent(MotionEvent.ACTION_MOVE,action.exactCenterX(),action.exactCenterY(),second)
        nativeWheelEvent(MotionEvent.ACTION_MOVE,center.exactCenterX(),center.exactCenterY(),second)
        nativeWheelEvent(MotionEvent.ACTION_UP,center.exactCenterX(),center.exactCenterY(),second)
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertIsDisplayed()
        ui.runOnIdle {assertEquals(1,sent.size)}
    }
    @Test fun losingControlDuringWheelSteeringCancelsTheCommand() {
        val enabled=mutableStateOf(true);var sent=0
        ui.setContent {MaterialTheme {Box(Modifier.size(360.dp,560.dp)) {
            NeovimShortcutButton(NeovimShortcuts.defaults,"one",enabled.value,{_,_->sent++},{},position=Offset(.5f,.5f),layout=NeovimShortcutLayout.CIRCULAR)
        }}}
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        val center=nativeBounds("Close Neovim shortcuts");val action=nativeBounds("Neovim shortcut :w");val down=SystemClock.uptimeMillis()
        nativeWheelEvent(MotionEvent.ACTION_DOWN,center.exactCenterX(),center.exactCenterY(),down)
        nativeWheelEvent(MotionEvent.ACTION_MOVE,action.exactCenterX(),action.exactCenterY(),down)
        ui.runOnIdle {enabled.value=false}
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertDoesNotExist()
        nativeWheelEvent(MotionEvent.ACTION_UP,action.exactCenterX(),action.exactCenterY(),down)
        ui.runOnIdle {assertEquals(0,sent)}
    }
    @Test fun fanOutAcceptsInputBeforeSettlingAndCanReverseAClosingWheel()=checkAnimationLifecycle(NeovimShortcutEffect.FAN_OUT)
    @Test fun selectionHapticsTickOnlyForNewValidSegmentsAndRespectTheToggle() {
        val enabled=mutableStateOf(true);val feedback=mutableListOf<HapticFeedbackType>();var sent=0
        val haptic=object: HapticFeedback {override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {feedback+=hapticFeedbackType}}
        ui.setContent {CompositionLocalProvider(LocalHapticFeedback provides haptic) {MaterialTheme {Box(Modifier.size(360.dp,560.dp)) {
            NeovimShortcutButton(NeovimShortcuts.defaults,"one",true,{_,_->sent++},{},position=Offset(.5f,.5f),layout=NeovimShortcutLayout.CIRCULAR,effect=NeovimShortcutEffect.NONE,hapticsEnabled=enabled.value)
        }}}}
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        val center=nativeBounds("Close Neovim shortcuts");val first=nativeBounds("Neovim shortcut :w");val second=nativeBounds("Neovim shortcut :q")
        ui.runOnIdle {assertTrue(feedback.isEmpty())}
        val down=SystemClock.uptimeMillis()
        nativeWheelEvent(MotionEvent.ACTION_DOWN,center.exactCenterX(),center.exactCenterY(),down)
        nativeWheelEvent(MotionEvent.ACTION_MOVE,first.exactCenterX(),first.exactCenterY(),down)
        nativeWheelEvent(MotionEvent.ACTION_MOVE,first.exactCenterX()+1,first.exactCenterY(),down)
        ui.runOnIdle {assertEquals(listOf(HapticFeedbackType.SegmentTick),feedback)}
        nativeWheelEvent(MotionEvent.ACTION_MOVE,second.exactCenterX(),second.exactCenterY(),down)
        ui.runOnIdle {assertEquals(2,feedback.size)}
        nativeWheelEvent(MotionEvent.ACTION_MOVE,center.exactCenterX(),center.exactCenterY(),down)
        ui.runOnIdle {assertEquals(2,feedback.size);enabled.value=false}
        ui.waitForIdle()
        nativeWheelEvent(MotionEvent.ACTION_MOVE,first.exactCenterX(),first.exactCenterY(),down)
        ui.runOnIdle {assertEquals(2,feedback.size);enabled.value=true}
        ui.waitForIdle()
        nativeWheelEvent(MotionEvent.ACTION_MOVE,first.exactCenterX()+1,first.exactCenterY(),down)
        ui.runOnIdle {assertEquals(2,feedback.size)}
        nativeWheelEvent(MotionEvent.ACTION_MOVE,second.exactCenterX(),second.exactCenterY(),down)
        ui.runOnIdle {assertEquals(3,feedback.size)}
        nativeWheelEvent(MotionEvent.ACTION_MOVE,center.exactCenterX(),center.exactCenterY(),down)
        nativeWheelEvent(MotionEvent.ACTION_UP,center.exactCenterX(),center.exactCenterY(),down)
        ui.runOnIdle {assertEquals(3,feedback.size);assertEquals(0,sent)}
    }
    @Test fun spinAcceptsInputBeforeSettlingAndCanReverseAClosingWheel()=checkAnimationLifecycle(NeovimShortcutEffect.SPIN)
    @Test fun expandAcceptsInputBeforeSettlingAndCanReverseAClosingWheel()=checkAnimationLifecycle(NeovimShortcutEffect.EXPAND)
    @Test fun noAnimationOpensFullyAndClosesWithoutAnExitPopup() {
        var sent=0
        ui.setContent {MaterialTheme {Box(Modifier.size(360.dp,560.dp)) {
            NeovimShortcutButton(NeovimShortcuts.defaults,"one",true,{_,_->sent++},{},position=Offset(.5f,.5f),layout=NeovimShortcutLayout.CIRCULAR,effect=NeovimShortcutEffect.NONE)
        }}}
        ui.waitForIdle();ui.mainClock.autoAdvance=false
        repeat(2) {
            ui.onNodeWithContentDescription("Neovim shortcuts").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
            repeat(3) {ui.mainClock.advanceTimeByFrame()};ui.waitForIdle()
            val action=ui.onNodeWithContentDescription("Neovim shortcut :w")
            val staleAction=action.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
            action.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
            if(it==0) captureMenu("none-open")
            ui.onNodeWithContentDescription("Close Neovim shortcuts").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
            ui.mainClock.advanceTimeByFrame();ui.waitForIdle()
            ui.onNodeWithContentDescription("Neovim shortcut menu").assertDoesNotExist()
            ui.runOnIdle {staleAction();assertEquals(it+1,sent)}
        }
    }
    private fun checkAnimationLifecycle(effect: NeovimShortcutEffect) {
        val sent=mutableListOf<NeovimShortcut>()
        ui.setContent {MaterialTheme {Box(Modifier.size(360.dp,560.dp)) {
            NeovimShortcutButton(NeovimShortcuts.defaults,"one",true,{item,_->sent+=item},{},position=Offset(.5f,.5f),layout=NeovimShortcutLayout.CIRCULAR,effect=effect)
        }}}
        ui.waitForIdle();ui.mainClock.autoAdvance=false
        ui.onNodeWithContentDescription("Neovim shortcuts").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
        repeat(3) {ui.mainClock.advanceTimeByFrame()};ui.waitForIdle()
        val menu=ui.onNodeWithContentDescription("Neovim shortcut menu")
        menu.assertExists()
        val bounds=menu.fetchSemanticsNode().boundsInRoot
        val action=ui.onNodeWithContentDescription("Neovim shortcut :w")
        val staleAction=action.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        action.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
        ui.runOnIdle {assertEquals(listOf(NeovimShortcuts.defaults[0]),sent)}
        captureMenu("${effect.name.lowercase()}-opening-early")
        ui.mainClock.advanceTimeBy(effect.openMillis/2L);ui.waitForIdle()
        captureMenu("${effect.name.lowercase()}-opening")
        ui.mainClock.advanceTimeBy(effect.openMillis+32L);ui.waitForIdle()
        ui.onNodeWithContentDescription("Close Neovim shortcuts").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
        ui.mainClock.advanceTimeByFrame();ui.waitForIdle()
        menu.assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Closing"))
        action.assertIsNotEnabled()
        ui.runOnIdle {staleAction();assertEquals(1,sent.size)}
        ui.mainClock.advanceTimeBy(32);ui.waitForIdle()
        assertEquals(bounds,menu.fetchSemanticsNode().boundsInRoot)
        captureMenu("${effect.name.lowercase()}-closing")
        ui.onNodeWithContentDescription("Reopen Neovim shortcuts").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
        ui.mainClock.advanceTimeBy(effect.openMillis+32L);ui.waitForIdle()
        menu.assertExists();assertEquals(bounds,menu.fetchSemanticsNode().boundsInRoot)
        val pages=ui.onNodeWithContentDescription("Neovim shortcut pages")
        pages.performTouchInput {down(center);moveTo(Offset(center.x,height*.145f));up()}
        ui.runOnIdle {assertEquals(listOf(NeovimShortcuts.defaults[0],NeovimShortcuts.defaults[0]),sent)}
        ui.onNodeWithContentDescription("Close Neovim shortcuts").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
        ui.mainClock.advanceTimeBy(effect.closeMillis+32L);ui.waitForIdle()
        menu.assertDoesNotExist()
    }
    @Test fun paneAndOwnershipChangesDiscardAnAnimatingPopupImmediately() {
        val target=mutableStateOf("one");val enabled=mutableStateOf(true);var sent=0
        val effect=mutableStateOf(NeovimShortcutEffect.FAN_OUT)
        ui.setContent {MaterialTheme {Box(Modifier.size(360.dp,560.dp)) {
            NeovimShortcutButton(NeovimShortcuts.defaults,target.value,enabled.value,{_,_->sent++},{},position=Offset(.5f,.5f),layout=NeovimShortcutLayout.CIRCULAR,effect=effect.value)
        }}}
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        ui.onNodeWithContentDescription("Close Neovim shortcuts").assertIsDisplayed()
        ui.mainClock.autoAdvance=false
        ui.onNodeWithContentDescription("Close Neovim shortcuts").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
        ui.mainClock.advanceTimeByFrame();ui.waitForIdle()
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertExists()
        ui.runOnIdle {target.value="two"};ui.mainClock.advanceTimeByFrame();ui.waitForIdle()
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertDoesNotExist()
        ui.onNodeWithContentDescription("Neovim shortcuts").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
        repeat(3) {ui.mainClock.advanceTimeByFrame()};ui.waitForIdle()
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertExists()
        ui.runOnIdle {effect.value=NeovimShortcutEffect.SPIN};ui.mainClock.advanceTimeByFrame();ui.waitForIdle()
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertDoesNotExist()
        ui.onNodeWithContentDescription("Neovim shortcuts").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
        repeat(3) {ui.mainClock.advanceTimeByFrame()};ui.waitForIdle()
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertExists()
        ui.runOnIdle {enabled.value=false};ui.mainClock.advanceTimeByFrame();ui.waitForIdle()
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertDoesNotExist()
        ui.runOnIdle {assertEquals(0,sent)}
    }
    private fun nativeWheelEvent(action: Int,x: Float,y: Float,down: Long) {
        val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
        InstrumentationRegistry.getInstrumentation().sendPointerSync(event);event.recycle()
        repeat(3) {ui.mainClock.advanceTimeByFrame()};ui.waitForIdle()
    }
    private fun nativeDrag(x: Float,y: Float,toX: Float,toY: Float,cancel: Boolean=false) {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val down=SystemClock.uptimeMillis()
        fun send(action: Int,px: Float,py: Float) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,px,py,0)
            instrumentation.sendPointerSync(event);event.recycle();ui.waitForIdle()
        }
        send(MotionEvent.ACTION_DOWN,x,y)
        repeat(12) {step->val t=(step+1)/12f;send(MotionEvent.ACTION_MOVE,x+(toX-x)*t,y+(toY-y)*t)}
        send(if(cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,toX,toY)
    }
    private fun nativeBounds(description: String): Rect {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val info=automation.serviceInfo;info.flags=info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.serviceInfo=info
        fun find(node: AccessibilityNodeInfo?): Rect? {
            if(node==null) return null
            if(node.contentDescription?.toString()==description) return Rect().also {node.getBoundsInScreen(it)}.takeUnless {it.isEmpty}
            for(i in 0 until node.childCount) find(node.getChild(i))?.let {return it}
            return null
        }
        val deadline=SystemClock.uptimeMillis()+4000
        while(SystemClock.uptimeMillis()<deadline) {
            automation.windows.firstNotNullOfOrNull {find(it.root)}?.let {return it}
            ui.mainClock.advanceTimeByFrame();ui.waitForIdle()
            SystemClock.sleep(20)
        }
        error("No native node $description")
    }
    private fun nativeTap(x: Float,y: Float) {
        val down=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
            InstrumentationRegistry.getInstrumentation().sendPointerSync(event);event.recycle()
        }
        ui.waitForIdle()
    }
    private fun captureMenu(name: String,description: String="Neovim shortcut menu") {
        ui.waitForIdle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir,"neovim-menu-$name.png").outputStream().use {
            ui.onNodeWithContentDescription(description).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
}
