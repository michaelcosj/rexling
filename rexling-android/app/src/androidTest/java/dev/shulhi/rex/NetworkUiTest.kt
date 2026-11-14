package dev.shulhi.rex

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class NetworkUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun networkSettingsExposeOptionalTailscaleAndControlServer() {
        compose.setContent {MaterialTheme {NetworkSheet {}}}
        compose.onNodeWithText("Built-in Tailscale").assertIsDisplayed()
        compose.onNodeWithText("Control server (optional)").assertIsDisplayed()
        compose.onNodeWithText("Apply").assertIsDisplayed()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {bitmap->
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"network-settings.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun sshEditorOffersKeyImportAndKeylessConnection() {
        compose.setContent {MaterialTheme {ServerEditor(ServerProfile.create("Test SSH","ssh://tester@example.com"),{}, {_,_,_->null})}}
        compose.onNodeWithText("Import SSH key").assertIsDisplayed()
        compose.onNodeWithText("Connect without a key for Tailscale SSH, or import a private key.").assertIsDisplayed()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {bitmap->
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"ssh-settings.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
    }
}
