package dev.shulhi.rex

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
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
class PaneSwitchMinimapTest {
    @get:Rule val ui=createComposeRule()
    @Test fun splitGeometryAndHighlightedPaneMatchTheCurrentSwipeTarget() {
        val active=mutableStateOf("b");val dark=mutableStateOf(false);var primary=Color.Unspecified
        val layout=listOf(PanePlacement("a",0f,0f,.5f,1f),PanePlacement("b",.5f,0f,.5f,.5f),PanePlacement("c",.5f,.5f,.5f,.5f))
        ui.setContent {RexTheme(dark.value,ThemeCatalog.defaults[if(dark.value) 0 else 1]) {
            primary=MaterialTheme.colorScheme.primary
            PaneSwitchMinimap(layout,active.value,Modifier.size(112.dp,72.dp))
        }}
        val node=ui.onNodeWithContentDescription("Pane switch layout")
        for(isDark in listOf(false,true)) {
            ui.runOnIdle {dark.value=isDark;active.value=if(isDark) "c" else "b"}
            node.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,active.value))
            val image=node.captureToImage();val pixels=image.toPixelMap()
            fun sample(x: Float,y: Float)=pixels[(image.width*x).toInt(),(image.height*y).toInt()].toArgb()
            assertNotEquals(primary.toArgb(),sample(.25f,.5f))
            assertEquals(primary.toArgb(),sample(.75f,if(isDark) .75f else .25f))
            assertNotEquals(primary.toArgb(),sample(.75f,if(isDark) .25f else .75f))
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"pane-switch-minimap-${if(isDark) "dark" else "light"}.png").outputStream().use {image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
}
