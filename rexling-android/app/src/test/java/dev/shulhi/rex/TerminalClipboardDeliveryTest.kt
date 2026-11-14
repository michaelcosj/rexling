package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class TerminalClipboardDeliveryTest {
    private fun state(serial: Long=1,text: String="hello\n🦀")=AppState(phase="connected",selectedBlock="pane-a",connectionEpoch=2,navigationId=3,
        clipboardWrite=TerminalClipboardWrite(serial,text,"pane-a",2,3))
    @Test fun copiesOnceAndAllowsIdenticalTextFromANewRequest() {
        val copies=mutableListOf<String>();val delivery=TerminalClipboardDelivery {copies+=it}
        delivery.deliver(state(),true);delivery.deliver(state(),true);delivery.deliver(state(2),true)
        assertEquals(listOf("hello\n🦀","hello\n🦀"),copies)
    }
    @Test fun explicitCopyCanUseRetainedHistoryWhileOffline() {
        val copies=mutableListOf<String>();val delivery=TerminalClipboardDelivery {copies+=it}
        val original=state()
        delivery.deliver(original.copy(phase="reconnecting",clipboardWrite=original.clipboardWrite!!.copy(local=true)),true)
        assertEquals(listOf("hello\n🦀"),copies)
    }
    @Test fun backgroundAndStalePaneRequestsNeverReplayLater() {
        val copies=mutableListOf<String>();val delivery=TerminalClipboardDelivery {copies+=it}
        val requests=listOf(state(1),state(2).copy(selectedBlock="pane-b"),state(3).copy(connectionEpoch=4),
            state(4).copy(navigationId=5),state(5).copy(phase="disconnected"),state(6,"x".repeat(262145)))
        requests.forEachIndexed {index,state->delivery.deliver(state,index!=0);delivery.deliver(state(),true)}
        assertTrue(copies.isEmpty())
        delivery.deliver(state(7,"valid"),true);assertEquals(listOf("valid"),copies)
    }
}
