package dev.shulhi.rex

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Exercise the installed Gboard, rather than only calling InputConnection directly. */
class GboardSuggestionsTest {
    @get:Rule val ui=createComposeRule()
    @Test fun realKeyboardOffersAndCommitsAWordSuggestion() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val keyboard=Settings.Secure.getString(instrumentation.targetContext.contentResolver,Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        assumeTrue("Requires the user's installed Gboard",keyboard.startsWith("com.google.android.inputmethod.latin/"))
        val automation=instrumentation.uiAutomation
        val original=automation.serviceInfo
        automation.serviceInfo=automation.serviceInfo.apply {flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        val sent=mutableListOf<String>();lateinit var view: TerminalView
        fun root()=automation.windows.firstOrNull {it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD}?.root
        fun find(node: AccessibilityNodeInfo?,word: String): AccessibilityNodeInfo? {
            if(node==null) return null
            if(node.text?.toString()?.equals(word,ignoreCase=true)==true || node.contentDescription?.toString()?.equals(word,ignoreCase=true)==true) {
                val bounds=Rect();node.getBoundsInScreen(bounds);if(!bounds.isEmpty) return node
            }
            for(index in 0 until node.childCount) find(node.getChild(index),word)?.let {return it}
            return null
        }
        fun tap(node: AccessibilityNodeInfo) {
            val bounds=Rect();node.getBoundsInScreen(bounds);val now=SystemClock.uptimeMillis()
            for((action,time) in listOf(MotionEvent.ACTION_DOWN to now,MotionEvent.ACTION_UP to now+40)) {
                val event=MotionEvent.obtain(now,time,action,bounds.exactCenterX(),bounds.exactCenterY(),0)
                event.source=InputDevice.SOURCE_TOUCHSCREEN;assertTrue(automation.injectInputEvent(event,true));event.recycle()
            }
        }
        val clipboard=instrumentation.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var previous: ClipData?=null
        try {
            ui.setContent {AndroidView(factory={TerminalView(it).also {v->view=v;v.controlled=true;v.keyboardSuggestions=true;v.onInput={sent+=it}
                v.screen=TerminalScreen(40,12,0,0,-1,0,emptyList())}},modifier=Modifier.fillMaxSize())}
            ui.runOnIdle {previous=clipboard.primaryClip;clipboard.clearPrimaryClip();view.keyboard()}
            ui.waitUntil(timeoutMillis=10000) {root()!=null}
            SystemClock.sleep(1200) // Wait for the IME slide before reading key bounds.
            for(letter in listOf("h","e","l")) {
                ui.waitUntil(timeoutMillis=5000) {find(root(),letter)!=null}
                tap(find(root(),letter)!!);SystemClock.sleep(150)
            }
            fun candidate(node: AccessibilityNodeInfo?): Pair<AccessibilityNodeInfo,String>? {
                if(node==null) return null
                val text=node.text?.toString().orEmpty().ifEmpty {node.contentDescription?.toString().orEmpty()}
                if(text.length>3 && text.startsWith("hel",ignoreCase=true) && text.all(Char::isLetter)) return node to text
                for(index in 0 until node.childCount) candidate(node.getChild(index))?.let {return it}
                return null
            }
            try {ui.waitUntil(timeoutMillis=10000) {candidate(root())!=null}} catch(error: Throwable) {
                instrumentation.waitForIdleSync();SystemClock.sleep(300)
                File(instrumentation.targetContext.cacheDir,"gboard-suggestions-debug.png").outputStream().use {automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
                File(instrumentation.targetContext.cacheDir,"gboard-debug.txt").writeText("committed_chunks=${sent.size};committed_lengths=${sent.map {it.length}}")
                throw error
            }
            val suggestion=candidate(root())!!;tap(suggestion.first)
            ui.waitUntil(timeoutMillis=5000) {sent.joinToString("").trim().equals(suggestion.second,ignoreCase=true)}
        } finally {ui.runOnIdle {view.hideKeyboard();if(previous!=null) clipboard.setPrimaryClip(previous!!) else clipboard.clearPrimaryClip()};automation.serviceInfo=original}
    }
}
