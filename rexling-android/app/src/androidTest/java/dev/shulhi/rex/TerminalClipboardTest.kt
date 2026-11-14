package dev.shulhi.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalClipboardTest {
    @get:Rule val ui=createComposeRule()
    @Test fun remoteTextCanBePastedWithoutLosingUnicodeOrLineBreaks() {
        ui.setContent {Text("Clipboard test")};ui.waitForIdle()
        ui.runOnIdle {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val manager=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val previous=manager.primaryClip
            try {
                val delivery=TerminalClipboardDelivery {manager.setPrimaryClip(ClipData.newPlainText("Terminal",it))}
                val text="copied from Neovim\nλ 🦀\n"
                val state=AppState(phase="connected",selectedBlock="pane",connectionEpoch=1,navigationId=2,
                    clipboardWrite=TerminalClipboardWrite(1,text,"pane",1,2))
                delivery.deliver(state,true)
                assertEquals(text,manager.primaryClip!!.getItemAt(0).text.toString())
                var pasted=""
                assertTrue(pasteTerminalClipboard(context,{false}) {pasted=it})
                assertEquals(text,pasted)
                manager.setPrimaryClip(ClipData.newPlainText("Local","local copy"))
                delivery.deliver(state,true)
                assertEquals("local copy",manager.primaryClip!!.getItemAt(0).text.toString())
            } finally {
                if(previous!=null) manager.setPrimaryClip(previous) else manager.clearPrimaryClip()
            }
        }
    }
}
