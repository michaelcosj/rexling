package dev.shulhi.rex

import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.MotionEvent
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalLinkMenuTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var view: TerminalView
    private val intents=mutableListOf<Intent>()
    private var activations=0;private var input=0
    private val url="https://example.com/actual-target"
    private fun fixture(link: Boolean=true) {
        ui.setContent {
            val parent=LocalContext.current
            val context=remember {object: ContextWrapper(parent) {override fun startActivity(intent: Intent) {intents+=intent}}}
            AndroidView(factory={TerminalView(context).also {
                view=it;it.onActivate={activations++};it.onInput={input++}
                it.screen=TerminalScreen(60,4,null,null,-1,0,"Docs".mapIndexed {i,c->TerminalCell(i,0,c.toString(),-1,0,0,1,if(link) url else null)})
            }},modifier=Modifier.size(320.dp,200.dp))
        }
        ui.runOnIdle {
            val event=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,5f,5f,0);view.onTouchEvent(event);event.recycle()
            assertTrue(view.performLongClick());assertEquals("Docs",view.selectedText)
        }
    }
    @Test fun selectionPasteUsesTheClipboardRouteAndRejectsChangedTargetsOrLostControl() {
        fixture(link=false)
        ui.runOnIdle {
            val clipboard=view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val previous=clipboard.primaryClip
            var pasted=0;var target="pane-a"
            view.controlled=true;view.imageInputTarget={target};view.onPaste={pasted++;true}
            try {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Paste test","hello"))
                view.performLongClick()
                target="pane-b";assertFalse(view.pasteSelection());assertEquals(0,pasted)
                target="pane-a";view.controlled=false;assertFalse(view.pasteSelection())
                view.controlled=true;assertTrue(view.pasteSelection());assertEquals(1,pasted);assertNull(view.selectedText)
                assertFalse(view.pasteSelection());assertEquals(1,pasted)
            } finally {if(previous==null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(previous)}
        }
    }
    @Test fun copyLinkCopiesTheDestinationAndKeepsTextCopyAvailable() {
        fixture()
        ui.runOnIdle {
            val clipboard=view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val previous=clipboard.primaryClip
            try {
                assertTrue(view.selectedLinkAction(TerminalLinkAction.COPY_ADDRESS))
                assertEquals(url,clipboard.primaryClip!!.getItemAt(0).coerceToText(view.context).toString())
                assertNull(view.selectedText);assertEquals(0,input);assertEquals(0,activations)
            } finally {if(previous==null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(previous)}
        }
    }
    @Test fun openAndShareUseTheFrozenDestinationWithoutTyping() {
        fixture()
        ui.runOnIdle {
            view.screen=view.screen!!.copy(cells=emptyList(),lines=List(4){emptyList()})
            assertTrue(view.selectedLinkAction(TerminalLinkAction.OPEN))
            assertEquals(Intent.ACTION_VIEW,intents.single().action);assertEquals(url,intents.single().dataString)
        }
    }
    @Test fun shareUsesTheSystemChooserAndExactAddress() {
        fixture()
        ui.runOnIdle {
            assertTrue(view.selectedLinkAction(TerminalLinkAction.SHARE))
            val chooser=intents.single();assertEquals(Intent.ACTION_CHOOSER,chooser.action)
            @Suppress("DEPRECATION") val send=chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND,send.action);assertEquals("text/plain",send.type)
            assertEquals(url,send.getStringExtra(Intent.EXTRA_TEXT));assertEquals(0,input);assertEquals(0,activations)
        }
    }
    @Test fun ordinaryTextKeepsSelectionWithoutLinkActions() {
        fixture(link=false)
        ui.runOnIdle {
            assertFalse(view.selectedLinkAction(TerminalLinkAction.OPEN));assertEquals("Docs",view.selectedText)
            view.interactive=false
        }
    }
}
