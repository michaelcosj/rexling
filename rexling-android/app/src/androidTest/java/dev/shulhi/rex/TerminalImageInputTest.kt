package dev.shulhi.rex

import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TerminalImageInputTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    @Test fun ordinaryTextInputAcceptsSuggestionsAndDictationWithoutDuplicateCommits() {
        instrumentation.runOnMainSync {
            val sent=mutableListOf<String>()
            val view=TerminalView(instrumentation.targetContext).apply {controlled=true;onInput={sent+=it}}
            val attrs=EditorInfo();val input=view.onCreateInputConnection(attrs)!!
            assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_NORMAL,attrs.inputType and android.text.InputType.TYPE_MASK_VARIATION)
            assertEquals(0,attrs.inputType and android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            assertTrue(attrs.inputType and android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT!=0)
            input.setComposingText("helo",1)
            input.setComposingText("hello",1)
            assertTrue(sent.isEmpty())
            input.commitText("hello ",1)
            input.finishComposingText()
            assertEquals(listOf("hello "),sent)
            input.commitText("This is dictated text.",1)
            input.finishComposingText()
            assertEquals(listOf("hello ","This is dictated text."),sent)
            input.setComposingText("pending",1)
            view.flushComposingText()
            input.finishComposingText()
            assertEquals(listOf("hello ","This is dictated text.","pending"),sent)
        }
    }
    @Test fun editBatchesReportTheirRemainingNestingToAndroid() {
        instrumentation.runOnMainSync {
            val view=TerminalView(instrumentation.targetContext).apply {controlled=true}
            val input=view.onCreateInputConnection(EditorInfo())!!
            assertTrue(input.beginBatchEdit());assertTrue(input.beginBatchEdit())
            input.setComposingText("hello",1)
            assertTrue(input.endBatchEdit())
            assertFalse("Gboard must be told that the final batch has ended",input.endBatchEdit())
            assertFalse(input.endBatchEdit())
        }
    }
    @Test fun gboardCanReplaceTheCurrentWordFromAMidwordCursor() {
        instrumentation.runOnMainSync {
            val sent=mutableListOf<String>();val view=TerminalView(instrumentation.targetContext).apply {controlled=true;onInput={sent+=it}}
            val input=view.onCreateInputConnection(EditorInfo())!!
            input.setComposingText("helo",1)
            assertEquals("helo",input.getTextBeforeCursor(20,0).toString())
            assertTrue(input.setSelection(2,2));assertEquals("he",input.getTextBeforeCursor(20,0).toString());assertEquals("lo",input.getTextAfterCursor(20,0).toString())
            input.beginBatchEdit();input.setComposingRegion(0,4);input.commitText("hello ",1)
            assertTrue("A correction batch must remain local until finalized",sent.isEmpty())
            input.endBatchEdit();assertEquals(listOf("hello "),sent)
            view.flushComposingText(restartIme=false)
            input.setComposingText("helxo",1);input.setSelection(3,3)
            input.beginBatchEdit();input.finishComposingText();input.deleteSurroundingText(3,2);input.commitText("hello",1);input.endBatchEdit()
            assertEquals(listOf("hello ","hello"),sent)
        }
    }
    @Test fun compositionBatchesKeepTheNextWordLocalAndDiscardStaleConnections() {
        instrumentation.runOnMainSync {
            val sent=mutableListOf<String>();var target="a"
            val view=TerminalView(instrumentation.targetContext).apply {controlled=true;imageInputTarget={target};onInput={sent+=it}}
            val input=view.onCreateInputConnection(EditorInfo())!!
            input.setComposingText("helo",1);input.beginBatchEdit();input.commitText("hello ",1);input.setComposingText("world",1);input.endBatchEdit()
            assertEquals(listOf("hello "),sent);assertEquals("hello world",input.getTextBeforeCursor(20,0).toString())
            input.finishComposingText();assertEquals(listOf("hello ","world"),sent)
            view.flushComposingText(restartIme=false);assertTrue(input.commitText("!",1));assertEquals("!",sent.last())
            input.setComposingText("private",1);target="b"
            assertFalse(input.commitText("wrong pane",1));assertFalse(input.deleteSurroundingText(1,0));assertEquals(listOf("hello ","world","!"),sent)
        }
    }
    @Test fun compositionBackspaceAndCursorKeysStayLocalAndRespectUnicode() {
        instrumentation.runOnMainSync {
            val sent=mutableListOf<String>();val view=TerminalView(instrumentation.targetContext).apply {controlled=true;onInput={sent+=it}}
            val input=view.onCreateInputConnection(EditorInfo())!!
            input.setComposingText("a🦀b",1)
            input.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_LEFT))
            assertEquals("a🦀",input.getTextBeforeCursor(20,0).toString())
            input.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DEL))
            assertEquals("a",input.getTextBeforeCursor(20,0).toString());assertEquals("b",input.getTextAfterCursor(20,0).toString())
            assertTrue(sent.isEmpty());input.finishComposingText();assertEquals(listOf("ab\u001b[D"),sent)
        }
    }
    @Test fun committedWordRemainsAvailableForGboardCorrectionWithoutResendingIt() {
        instrumentation.runOnMainSync {
            val sent=mutableListOf<String>()
            lateinit var view: TerminalView
            view=TerminalView(instrumentation.targetContext).apply {
                controlled=true;keyboardSuggestions=true
                onInput={view.flushComposingText();sent+=it}
            }
            val input=view.onCreateInputConnection(EditorInfo())!!
            input.commitText("h",1);input.commitText("e",1);input.commitText("l",1)
            assertEquals("hel",input.getTextBeforeCursor(20,0).toString())
            input.setComposingRegion(0,3);input.commitText("hello ",1)
            assertEquals(listOf("h","e","l","lo "),sent)
            input.finishComposingText();assertEquals(4,sent.size)
            input.setComposingRegion(0,6);input.commitText("help ",1)
            assertEquals("\u007f\u007f\u007fp ",sent.last())
            view.flushComposingText(restartIme=false)
            assertEquals(5,sent.size)
            assertEquals("",input.getTextBeforeCursor(20,0).toString())
        }
    }
    @Test fun keyboardAdvertisesImagesAndAcceptsThemWithoutSendingTextOrEnter() {
        instrumentation.runOnMainSync {
            var images=0;var text="";var releases=0;var pasted=0
            val view=TerminalView(instrumentation.targetContext).apply {controlled=true;onInput={text+=it};onImage={uri,release->assertEquals(TestImageProvider.uri,uri);images++;release();true};onPaste={pasted++;true}}
            val attrs=EditorInfo();val input=view.onCreateInputConnection(attrs)!!
            assertTrue(attrs.contentMimeTypes!!.contains("image/png"))
            assertTrue(input.commitContent(InputContentInfo(TestImageProvider.uri,ClipDescription("image",arrayOf("image/png")),null),0,null))
            assertEquals(1,images);assertEquals("",text)
            assertTrue(input.performContextMenuAction(android.R.id.paste));assertEquals(1,pasted)
            input.commitText("hello",1);assertEquals("hello",text)
            view.onImage={_,release->releases++;release();false}
            assertFalse(input.commitContent(InputContentInfo(TestImageProvider.uri,ClipDescription("image",arrayOf("image/png")),null),0,null));assertEquals(1,releases)
        }
    }
    @Test fun staleReadonlyAndNonImageCommitsCannotReachAnotherPane() {
        instrumentation.runOnMainSync {
            var images=0;var token="pane-a"
            val view=TerminalView(instrumentation.targetContext).apply {controlled=true;mouseTarget={token};onImage={_,release->images++;release();true};onPaste={images++;true}}
            val input=view.onCreateInputConnection(EditorInfo())!!
            val image=InputContentInfo(TestImageProvider.uri,ClipDescription("image",arrayOf("image/png")),null)
            assertFalse(input.commitContent(InputContentInfo(TestImageProvider.uri,ClipDescription("text",arrayOf("text/plain")),null),0,null))
            token="pane-b";assertFalse(input.commitContent(image,0,null));assertFalse(input.performContextMenuAction(android.R.id.paste))
            token="pane-a";view.controlled=false;assertFalse(input.commitContent(image,0,null));assertEquals(0,images)
        }
    }
}

@RunWith(AndroidJUnit4::class)
class ClipboardImagePasteTest {
    @get:Rule val ui=createComposeRule()
    @Test fun imageClipboardUsesUploadAndPlainTextKeepsOrdinaryPaste() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ui.setContent {MaterialTheme {androidx.compose.material3.Text("Clipboard test")}}
        ui.runOnIdle {
            val old=clipboard.primaryClip
            try {
                var images=0;var text=""
                clipboard.setPrimaryClip(ClipData("Image",arrayOf("image/png"),ClipData.Item(TestImageProvider.uri)))
                assertTrue(pasteTerminalClipboard(context,{assertEquals(TestImageProvider.uri,it);images++;true},{text=it}))
                assertEquals(1,images);assertEquals("",text)
                clipboard.setPrimaryClip(ClipData.newPlainText("Text","hello\nworld"))
                assertTrue(pasteTerminalClipboard(context,{images++;true},{text=it}))
                assertEquals(1,images);assertEquals("hello\nworld",text)
            } finally {if(old!=null) clipboard.setPrimaryClip(old) else clipboard.clearPrimaryClip()}
        }
    }
}
