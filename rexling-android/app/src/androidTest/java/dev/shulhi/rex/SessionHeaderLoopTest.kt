package dev.shulhi.rex

import android.graphics.Bitmap
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SessionHeaderLoopTest {
    @get:Rule val ui=createComposeRule()
    private val sessions=listOf(SessionItem("linux:a","alpha · Linux"),SessionItem("mac:b","beta · Mac"),SessionItem("mac:c","gamma · Mac"))
    private val selections=mutableListOf<String>()
    private fun header(items: List<SessionItem>,selected: String,enabled: Boolean=true,onSelect:(String)->Unit) {
        ui.setContent {var current by remember {mutableStateOf(selected)};MaterialTheme {
            androidx.compose.foundation.layout.Box(Modifier.width(340.dp)) {
                SessionHeader(items,current,true,enabled,listOf(ProgramIdentity(ProgramKind.TERMINAL,"fish"),ProgramIdentity(ProgramKind.CLAUDE,"Claude"),ProgramIdentity(ProgramKind.NEOVIM,"Neovim")),0,"~/Dev/rexlink",{},{selections.add(it);current=it;onSelect(it)},{},title="Investigate Rex protocol",menu={})
            }
        }}
    }
    @Test fun sessionStripWrapsBothWaysAndSendsOneSelectionPerSwipe() {
        header(sessions,"mac:c",onSelect={})
        val strip=ui.onNodeWithContentDescription("Session strip")
        strip.performTouchInput {swipeLeft()};ui.waitForIdle()
        ui.runOnIdle {assertEquals(listOf("linux:a"),selections)}
        strip.performTouchInput {swipeRight()};ui.waitForIdle()
        strip.performTouchInput {swipeLeft()};ui.waitForIdle()
        strip.performTouchInput {swipeLeft()};ui.waitForIdle()
        ui.runOnIdle {assertEquals(listOf("linux:a","mac:c","linux:a","mac:b"),selections)}
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        File(app.cacheDir,"loop-header.png").outputStream().use {ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun twoSessionsWrapBothWays() {
        header(sessions.take(2),"linux:a",onSelect={})
        val strip=ui.onNodeWithContentDescription("Session strip")
        repeat(4) {strip.performTouchInput {if(it%2==0) swipeRight() else swipeLeft()};ui.waitForIdle()}
        ui.runOnIdle {assertEquals(listOf("mac:b","linux:a","mac:b","linux:a"),selections)}
    }
    @Test fun changingTheCatalogDoesNotCauseAnUnrequestedSessionSwitch() {
        val items=mutableStateOf(sessions);val current=mutableStateOf("mac:c")
        ui.setContent {MaterialTheme {SessionHeader(items.value,current.value,true,true,emptyList(),0,"",{},{selections.add(it);current.value=it},{},menu={})}}
        ui.runOnIdle {items.value=sessions.take(1);current.value="linux:a"};ui.waitForIdle()
        ui.onNodeWithContentDescription("Session strip").performTouchInput {swipeRight()};ui.waitForIdle()
        ui.runOnIdle {assertTrue(selections.isEmpty());items.value=emptyList();current.value=""};ui.waitForIdle()
        ui.onNodeWithText("Sessions").assertIsDisplayed()
    }
    @Test fun iconSwipesSwitchSessionsVerticallyAndLoopAcrossServers() {
        header(sessions,"mac:c",onSelect={})
        val icon=ui.onNodeWithContentDescription("Session program icon")
        icon.performTouchInput {swipeUp()};ui.waitForIdle()
        icon.performTouchInput {swipeDown()};ui.waitForIdle()
        icon.performTouchInput {swipeUp()};ui.waitForIdle()
        icon.performTouchInput {swipeUp()};ui.waitForIdle()
        ui.runOnIdle {assertEquals(listOf("linux:a","mac:c","linux:a","mac:b"),selections)}
    }
    @Test fun iconSwipesDoNothingWhenSessionNavigationIsDisabled() {
        header(sessions,"mac:c",enabled=false,onSelect={})
        ui.onNodeWithContentDescription("Session program icon").performTouchInput {swipeUp()}
        ui.runOnIdle {assertTrue(selections.isEmpty())}
    }
    @Test fun iconFollowsTheFingerAndSpringsBackOnCancelWithoutMovingTheTitle() {
        header(sessions,"mac:c",onSelect={})
        val icon=ui.onNodeWithContentDescription("Session program icon")
        val graphic=ui.onNodeWithContentDescription("fish, 3 panes",useUnmergedTree=true)
        val before=graphic.fetchSemanticsNode().boundsInRoot
        val title=ui.onNodeWithText("Investigate Rex protocol").fetchSemanticsNode().boundsInRoot
        val anchor=icon.fetchSemanticsNode().boundsInRoot
        ui.mainClock.autoAdvance=false
        icon.performTouchInput {down(center);moveBy(androidx.compose.ui.geometry.Offset(0f,-height*.7f))}
        ui.mainClock.advanceTimeByFrame()
        val moved=graphic.fetchSemanticsNode().boundsInRoot
        assertTrue("Graphic did not follow swipe: $before -> $moved",moved.top<before.top)
        assertEquals(anchor,icon.fetchSemanticsNode().boundsInRoot)
        assertEquals(title,ui.onNodeWithText("Investigate Rex protocol").fetchSemanticsNode().boundsInRoot)
        icon.performTouchInput {cancel()}
        ui.mainClock.advanceTimeBy(1000)
        val after=graphic.fetchSemanticsNode().boundsInRoot
        assertEquals(before.top,after.top,1f);assertEquals(before.bottom,after.bottom,1f)
        ui.runOnIdle {assertTrue(selections.isEmpty())}
    }
}
