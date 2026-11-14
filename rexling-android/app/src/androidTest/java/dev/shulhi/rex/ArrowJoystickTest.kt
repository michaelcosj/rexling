package dev.shulhi.rex

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArrowJoystickTest {
    @get:Rule val ui=createComposeRule()
    private val sent=mutableListOf<String>()
    private var holdDelay=0L
    private fun fixture(enabled: State<Boolean> = mutableStateOf(true),target: State<String> = mutableStateOf("one"),parentDrag: (Float)->Unit={},joystick: State<Boolean> = mutableStateOf(true)) {
        ui.setContent {val pane=target.value;val configuration=LocalViewConfiguration.current;SideEffect {holdDelay=configuration.longPressTimeoutMillis};MaterialTheme {
            Box(Modifier.size(320.dp,280.dp).draggable(rememberDraggableState(parentDrag),Orientation.Horizontal)) {
                ArrowJoystickDockKey(enabled.value,Modifier.align(Alignment.Center).size(40.dp,37.dp),{sent+="$pane:$it"},inputKey=pane,useJoystick=joystick.value)
            }
        }}
    }
    @Test fun commandNavigationWorksWithoutControlAndDoesNotSendTerminalKeys() {
        val prompts=mutableListOf<Int>()
        ui.setContent {MaterialTheme {
            Box(Modifier.size(320.dp,280.dp)) {
                ArrowJoystickDockKey(false,Modifier.align(Alignment.Center).size(40.dp,37.dp),{sent+=it},
                    onPrompt={prompts+=it})
            }
        }}
        ui.onNodeWithContentDescription("Arrow keys").performTouchInput {click()}
        ui.onNodeWithContentDescription("Arrow key popup").assertIsDisplayed()
        ui.onNodeWithText("Prev command").performClick();ui.onNodeWithText("Next command").performClick()
        ui.onNodeWithContentDescription("Arrow key popup").assertIsDisplayed()
        ui.runOnIdle {assertEquals(listOf(-1,1),prompts);assertTrue(sent.isEmpty())}
        ui.onNodeWithContentDescription("Arrow keys").performTouchInput {click()}
        ui.onNodeWithContentDescription("Arrow key popup").assertDoesNotExist()
    }
    @Test fun neutralHoldAndTapNeverSendAKey() {
        fixture();ui.mainClock.autoAdvance=false
        val key=ui.onNodeWithContentDescription("Arrow keys")
        key.performTouchInput {down(center)};ui.mainClock.advanceTimeBy(800)
        ui.onNodeWithContentDescription("Arrow joystick").assertIsDisplayed()
        ui.runOnIdle {assertTrue(sent.isEmpty())}
        key.performTouchInput {up()};ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription("Arrow joystick").assertDoesNotExist()
        key.performTouchInput {click()};ui.mainClock.advanceTimeByFrame()
        ui.runOnIdle {assertTrue(sent.isEmpty())}
    }
    @Test fun directionSendsImmediatelyRepeatsPausesInNeutralAndStopsOnRelease() {
        fixture();ui.mainClock.autoAdvance=false
        val key=ui.onNodeWithContentDescription("Arrow keys")
        key.performTouchInput {down(center);moveTo(center+Offset(-40f,0f))}
        ui.mainClock.advanceTimeByFrame();ui.runOnIdle {assertEquals(listOf("one:left"),sent)}
        ui.mainClock.advanceTimeBy(400);ui.runOnIdle {assertEquals(listOf("one:left"),sent)}
        ui.mainClock.advanceTimeBy(160);ui.runOnIdle {assertEquals(2,sent.size)}
        ui.mainClock.advanceTimeBy(160);ui.runOnIdle {assertEquals(3,sent.size);assertTrue(sent.all {it=="one:left"})}
        key.performTouchInput {moveTo(center)};ui.mainClock.advanceTimeByFrame()
        val paused=sent.size;ui.mainClock.advanceTimeBy(500);ui.runOnIdle {assertEquals(paused,sent.size)}
        key.performTouchInput {moveTo(center+Offset(40f,0f))};ui.mainClock.advanceTimeByFrame()
        ui.runOnIdle {assertEquals("one:right",sent.last())}
        key.performTouchInput {up()};ui.mainClock.advanceTimeByFrame()
        val ended=sent.size;ui.mainClock.advanceTimeBy(500);ui.runOnIdle {assertEquals(ended,sent.size)}
        ui.onNodeWithContentDescription("Arrow joystick").assertDoesNotExist()
    }
    @Test fun returningFromUpWithSmallOvershootStaysNeutral() {
        fixture();ui.mainClock.autoAdvance=false
        val key=ui.onNodeWithContentDescription("Arrow keys")
        key.performTouchInput {down(center);moveTo(center+Offset(0f,-40.dp.toPx()))}
        ui.mainClock.advanceTimeByFrame()
        ui.runOnIdle {assertEquals(listOf("one:up"),sent)}
        key.performTouchInput {moveTo(center+Offset(0f,12.dp.toPx()))}
        ui.mainClock.advanceTimeBy(600)
        ui.runOnIdle {assertEquals("A small overshoot must pause without sending Down",listOf("one:up"),sent)}
        key.performTouchInput {moveTo(center+Offset(0f,20.dp.toPx()))}
        ui.mainClock.advanceTimeByFrame()
        ui.runOnIdle {assertEquals(listOf("one:up","one:down"),sent)}
        key.performTouchInput {up()}
    }
    @Test fun changingDirectionCancelsThePreviousRepeatAndCancellationStopsEverything() {
        fixture();ui.mainClock.autoAdvance=false
        val key=ui.onNodeWithContentDescription("Arrow keys")
        key.performTouchInput {down(center);moveTo(center+Offset(0f,-40f))}
        ui.mainClock.advanceTimeBy(400)
        key.performTouchInput {moveTo(center+Offset(0f,40f))};ui.mainClock.advanceTimeByFrame()
        val boundary=sent.size-1
        ui.mainClock.advanceTimeBy(500);ui.runOnIdle {assertTrue(sent.drop(boundary).all {it=="one:down"})}
        key.performTouchInput {cancel()};ui.mainClock.advanceTimeByFrame()
        val ended=sent.size;ui.mainClock.advanceTimeBy(500);ui.runOnIdle {assertEquals(ended,sent.size)}
        ui.onNodeWithContentDescription("Arrow joystick").assertDoesNotExist()
    }
    @Test fun rightDragCannotStartAnAncestorSwipe() {
        var parentMotion=0f;fixture(parentDrag={parentMotion+=it})
        ui.onNodeWithContentDescription("Arrow keys").performTouchInput {down(center);moveTo(center+Offset(130f,0f));up()}
        ui.runOnIdle {assertEquals(0f,parentMotion,0f);assertEquals(listOf("one:right"),sent)}
    }
    @Test fun losingControlCancelsHeldRepeat() {
        val enabled=mutableStateOf(true);fixture(enabled);ui.mainClock.autoAdvance=false
        val key=ui.onNodeWithContentDescription("Arrow keys")
        key.performTouchInput {down(center);moveTo(center+Offset(40f,0f))}
        ui.mainClock.advanceTimeBy(400);ui.runOnIdle {enabled.value=false};ui.mainClock.advanceTimeByFrame()
        val stopped=sent.size;ui.mainClock.advanceTimeBy(500);ui.runOnIdle {assertEquals(stopped,sent.size)}
        ui.onNodeWithContentDescription("Arrow joystick").assertDoesNotExist()
        key.assertIsNotEnabled().performTouchInput {up()}
    }
    @Test fun doubleTapOpensAndSingleTapClosesTheOriginalPopup() {
        fixture()
        val key=ui.onNodeWithContentDescription("Arrow keys")
        key.performTouchInput {doubleClick()}
        ui.onNodeWithContentDescription("Arrow key popup").assertIsDisplayed()
        ui.onNodeWithContentDescription("Arrow joystick").assertDoesNotExist()
        repeat(2) {ui.onNodeWithContentDescription("Left arrow").performTouchInput {click()}}
        ui.runOnIdle {assertEquals(listOf("one:left","one:left"),sent)}
        ui.onNodeWithContentDescription("Arrow key popup").assertIsDisplayed()
        key.performTouchInput {click()}
        ui.onNodeWithContentDescription("Arrow key popup").assertDoesNotExist()
        ui.runOnIdle {assertEquals(2,sent.size)}
    }
    @Test fun draggingWithThePopupOpenReturnsToJoystickWithoutAnAccidentalTap() {
        fixture();val key=ui.onNodeWithContentDescription("Arrow keys")
        key.performTouchInput {doubleClick()}
        key.performTouchInput {down(center);moveTo(center+Offset(40f,0f))}
        ui.onNodeWithContentDescription("Arrow key popup").assertDoesNotExist()
        ui.onNodeWithContentDescription("Arrow joystick").assertIsDisplayed()
        key.performTouchInput {up();advanceEventTime(100);click()}
        ui.onNodeWithContentDescription("Arrow key popup").assertDoesNotExist()
        ui.runOnIdle {assertEquals(listOf("one:right"),sent)}
    }
    @Test fun classicModeUsesSingleTapAndNeverSteers() {
        fixture(joystick=mutableStateOf(false));val key=ui.onNodeWithContentDescription("Arrow keys")
        key.performTouchInput {click()}
        ui.onNodeWithContentDescription("Arrow key popup").assertIsDisplayed()
        ui.onNodeWithContentDescription("Down arrow").performTouchInput {click()}
        ui.runOnIdle {assertEquals(listOf("one:down"),sent)}
        key.performTouchInput {click()}
        ui.onNodeWithContentDescription("Arrow key popup").assertDoesNotExist()
        key.performTouchInput {down(center);moveTo(center+Offset(80f,0f))}
        ui.onNodeWithContentDescription("Arrow joystick").assertDoesNotExist()
        key.performTouchInput {up()}
        ui.runOnIdle {assertEquals(listOf("one:down"),sent)}
    }
    @Test fun targetModeAndOwnershipChangesCloseThePopupAndStopRepeat() {
        val target=mutableStateOf("one");val enabled=mutableStateOf(true);val joystick=mutableStateOf(true)
        fixture(enabled,target,joystick=joystick)
        val key=ui.onNodeWithContentDescription("Arrow keys")
        key.performTouchInput {doubleClick()}
        ui.runOnIdle {target.value="two"}
        ui.onNodeWithContentDescription("Arrow key popup").assertDoesNotExist()
        key.performTouchInput {doubleClick()}
        ui.runOnIdle {joystick.value=false}
        ui.onNodeWithContentDescription("Arrow key popup").assertDoesNotExist()
        key.performTouchInput {click()}
        ui.onNodeWithContentDescription("Arrow key popup").assertIsDisplayed()
        ui.mainClock.autoAdvance=false
        ui.onNodeWithContentDescription("Right arrow").performTouchInput {down(center)}
        ui.mainClock.advanceTimeBy(650)
        ui.runOnIdle {assertTrue(sent.isNotEmpty());enabled.value=false}
        ui.mainClock.advanceTimeByFrame();val stopped=sent.size
        ui.mainClock.advanceTimeBy(600)
        ui.onNodeWithContentDescription("Arrow key popup").assertDoesNotExist()
        ui.runOnIdle {assertEquals(stopped,sent.size);assertTrue(sent.all {it=="two:right"})}
    }
    @Test fun heldPageDownUsesSlowerRepeatAndStopsOnPaneChange() {
        val target=mutableStateOf("one");fixture(target=target)
        ui.onNodeWithContentDescription("Arrow keys").performTouchInput {doubleClick()}
        ui.onNodeWithContentDescription("Arrow key popup").assertIsDisplayed()
        ui.mainClock.autoAdvance=false
        ui.onNodeWithContentDescription("Page down").performTouchInput {down(center)}
        ui.mainClock.advanceTimeBy((holdDelay-32).coerceAtLeast(1));ui.runOnIdle {assertTrue(sent.isEmpty())}
        ui.mainClock.advanceTimeBy(64);ui.runOnIdle {assertEquals(listOf("one:pagedown"),sent)}
        ui.mainClock.advanceTimeBy(160);ui.runOnIdle {assertEquals(2,sent.size);target.value="two"}
        ui.mainClock.advanceTimeByFrame();val stopped=sent.size
        ui.mainClock.advanceTimeBy(500)
        ui.onNodeWithContentDescription("Arrow key popup").assertDoesNotExist()
        ui.runOnIdle {assertEquals(stopped,sent.size);assertTrue(sent.all {it=="one:pagedown"})}
    }
    @Test fun eachDirectionIsAvailableAsAnAccessibilityAction() {
        fixture()
        val actions=ui.onNodeWithContentDescription("Arrow keys").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(setOf("Left arrow","Right arrow","Up arrow","Down arrow"),actions.map {it.label}.toSet())
        ui.runOnIdle {actions.forEach {assertTrue(it.action())};assertEquals(setOf("one:left","one:right","one:up","one:down"),sent.toSet())}
    }
}
