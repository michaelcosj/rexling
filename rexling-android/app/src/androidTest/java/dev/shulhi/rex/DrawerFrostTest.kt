package dev.shulhi.rex

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DrawerFrostTest {
    @get:Rule val ui=createComposeRule()
    @Test fun drawersAndMenuBlurTheBackdropAndFollowLiveChangesInBothThemes() {
        val choice=mutableIntStateOf(-1);val dark=mutableStateOf(false);val green=mutableStateOf(false)
        val server=ServerProfile.create("Test Mac","https://fixture.example","mac")
        val target=SessionTarget(server,SessionItem("s","Development"),SessionDetail("this device",1,1))
        val window=WindowItem("w","Project",listOf(BlockItem("b","shell")))
        val record=ActivityRecord.parse(JSONObject().put("session","s").put("sessionLabel","Development").put("windowLabel","Project")
            .put("block","b").put("state","blocked").put("kind","permission").put("title","Review changes").put("app","codex"))!!
        ui.setContent {RexTheme(dark.value,ThemeCatalog.defaults[if(dark.value) 0 else 1]) {
            val haze=remember {HazeState()}
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize().hazeSource(haze)) {
                    if(green.value) drawRect(Color.Green) else {
                        val stripe=2.dp.toPx()
                        repeat((size.width/stripe).toInt()+1) {x->drawRect(if(x%2==0) Color.Red else Color.Blue,
                            androidx.compose.ui.geometry.Offset(x*stripe,0f),androidx.compose.ui.geometry.Size(stripe,size.height))}
                    }
                }
                when(choice.intValue) {
                    0->ActivitySheet(listOf(ActivitySource(server,true,false,"",false,listOf(record))),{},{},{},hazeState=haze)
                    1->SessionSwitcher(listOf(target),target.key,"",true,"","",{},{_,_->true},{},{},{},hazeState=haze)
                    2->TabPaneSwitcher(listOf(window),"w","b",mapOf("b" to ProgramIdentity.resolve(listOf("codex"))),mapOf("b" to "Review changes"),mapOf("b" to "~/Dev/project"),{0},true,true,true,{},{},{},{_,_->},{},{},{},hazeState=haze)
                    3->Box(Modifier.align(Alignment.TopEnd).padding(top=60.dp,end=12.dp)) {
                        DropdownMenu(true,{},modifier=Modifier.drawerFrost(haze,RoundedCornerShape(16.dp)),shape=RoundedCornerShape(16.dp),containerColor=Color.Transparent,tonalElevation=0.dp) {
                            TerminalMenuItem("Preferences",ChromeIcon.SETTINGS,onClick={})
                            TerminalMenuItem("Attach image",ChromeIcon.IMAGE,onClick={})
                        }
                    }
                }
            }
        }}
        for(isDark in listOf(false,true)) for(kind in 0..3) {
            ui.runOnIdle {choice.intValue=-1};ui.waitForIdle()
            ui.runOnIdle {dark.value=isDark;green.value=false;choice.intValue=kind};ui.waitForIdle()
            val overlay=ui.onNodeWithTag("Frosted overlay")
            overlay.assertIsDisplayed()
            // The content must move with the sheet, outside any pre-offset clip.
            when(kind) {
                0->{ui.onNodeWithText("Activity").assertIsDisplayed();ui.onNodeWithText("Review changes").assertIsDisplayed()}
                1->ui.onNodeWithText("Development").assertIsDisplayed()
                2->ui.onNodeWithContentDescription("Tab Project").assertIsDisplayed()
                3->ui.onNodeWithText("Preferences").assertIsDisplayed()
            }
            if(isDark && kind in 0..1) {
                var text: androidx.compose.ui.text.TextLayoutResult?=null
                ui.onNodeWithText(if(kind==0) "Activity" else "Sessions").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {get->
                    val results=mutableListOf<androidx.compose.ui.text.TextLayoutResult>();get(results);text=results.single()
                }
                assertTrue("Dark drawer headings must retain light foregrounds",text!!.layoutInput.style.color.luminance()>.3f)
            }
            fun sample(): Color {
                val image=overlay.captureToImage();return image.toPixelMap()[(image.width*.25f).toInt(),6]
            }
            val striped=sample()
            assertTrue("Overlay $kind must blur red/blue stripes in dark=$isDark: $striped",minOf(striped.red,striped.blue)>striped.green+.04f)
            val image=overlay.captureToImage()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"drawer-frost-$kind-${if(isDark) "dark" else "light"}.png")
                .outputStream().use {image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
            ui.runOnIdle {green.value=true};ui.waitForIdle();Thread.sleep(80)
            val updated=sample()
            assertTrue("Overlay $kind must update with its host: $updated",updated.green>maxOf(updated.red,updated.blue)+.08f)
        }
        ui.runOnIdle {choice.intValue=-1}
    }
}
