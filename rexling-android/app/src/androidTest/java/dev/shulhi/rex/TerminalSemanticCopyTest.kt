package dev.shulhi.rex

import android.view.MotionEvent
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalSemanticCopyTest {
    @get:Rule val ui=createComposeRule()
    @Test fun semanticActionsUseTheFrozenSelectionAndRejectChangedPaneTargets() {
        lateinit var view: TerminalView
        var target="pane-a";val calls=mutableListOf<Pair<String,Boolean>>()
        ui.setContent {
            AndroidView(factory={TerminalView(it).also {v->
                view=v;v.imageInputTarget={target}
                v.onSemanticCopy={frame,_,_,output->calls+=frame.plainText() to output;true}
                v.screen=TerminalScreen(30,4,null,null,-1,0,"original".mapIndexed {x,c->TerminalCell(x,0,c.toString(),-1,0,0,1)},inputFlags=32)
            }},modifier=Modifier.size(320.dp,200.dp))
        }
        ui.runOnIdle {
            fun select() {
                val event=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,5f,5f,0);view.onTouchEvent(event);event.recycle()
                assertTrue(view.performLongClick())
            }
            select();val frozen=view.screen!!;view.screen=frozen.copy(cells=emptyList(),lines=List(4){emptyList()})
            assertTrue(view.copySemanticSelection(true));assertEquals(listOf("original" to true),calls);assertNull(view.selectedText)
            view.screen=frozen;select();target="pane-b"
            assertFalse(view.copySemanticSelection(false));assertEquals(1,calls.size)
            target="pane-a";assertTrue(view.copySemanticSelection(false));assertEquals("original" to false,calls.last())
            view.screen=frozen.copy(inputFlags=0);select();assertFalse(view.copySemanticSelection(true));assertEquals(2,calls.size)
        }
    }
}
