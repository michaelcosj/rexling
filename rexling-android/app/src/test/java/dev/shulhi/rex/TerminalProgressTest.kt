package dev.shulhi.rex

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TerminalProgressTest {
    @Test fun parsesPercentPausedErrorAndIndeterminateWithoutInventingCompletion() {
        assertEquals(40,TerminalProgress.parse(JSONObject("""{"state":"set","progress":40}"""))!!.percent)
        assertTrue(TerminalProgress.parse(JSONObject("""{"state":"set","progress":100}"""))!!.busy)
        assertNull(TerminalProgress.parse(JSONObject("""{"state":"indeterminate","progress":40}"""))!!.percent)
        assertFalse(TerminalProgress.parse(JSONObject("""{"state":"pause","progress":40}"""))!!.busy)
        assertEquals("Failed",TerminalProgress.parse(JSONObject("""{"state":"error"}"""))!!.label)
        assertNull(TerminalProgress.parse(JSONObject("""{"state":"remove"}""")))
        assertNull(TerminalProgress.parse(JSONObject("""{"state":"set","progress":255}"""))!!.percent)
    }
    @Test fun progressOnlyErrorsDoNotBecomeAttentionNotificationsOrUnreadDots() {
        val record=ActivityRecord.parse(JSONObject("""{"session":"s","block":"b","id":"osc9-progress","state":"error","progressOnly":true,"kind":"progress","terminalProgress":{"state":"error","progress":60}}"""))!!
        assertFalse(record.notifiable);assertEquals("Failed",record.stateLabel);assertFalse(record.context.contains("osc9-progress"))
        assertEquals(0,ActivityFocusPolicy.unread(listOf(record)) {false})
        assertTrue(record.copy(progressOnly=false).notifiable)
    }
    @Test fun selectedPaneProgressMetadataSurvivesStateDecoding() {
        val state=AppState.parse("""{"terminalProgress":{"a":{"state":"set","progress":75},"b":{"state":"remove"}}}""")
        assertEquals(75,state.terminalProgress["a"]!!.percent);assertFalse(state.terminalProgress.containsKey("b"))
    }
}
