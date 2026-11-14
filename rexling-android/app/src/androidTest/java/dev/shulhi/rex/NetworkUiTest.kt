package dev.shulhi.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
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
    @Test fun pastedPrivateKeyUsesEncryptedStorageAndCanBeCancelled() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val endpoint="ssh://paste-key-test@127.0.0.1:2222"
        val pair=java.security.KeyPairGenerator.getInstance("RSA").apply {initialize(2048)}.generateKeyPair()
        val key="-----BEGIN PRIVATE KEY-----\n"+android.util.Base64.encodeToString(pair.private.encoded,android.util.Base64.NO_WRAP)+"\n-----END PRIVATE KEY-----\n"
        runBlocking {NetworkSettings.removeKey(endpoint)}
        compose.setContent {MaterialTheme {Column {SshKeyEditor(endpoint)}}}
        val previous=clipboard.primaryClip
        try {
            compose.runOnIdle {clipboard.setPrimaryClip(ClipData.newPlainText("Test SSH key",key))}
            compose.onNodeWithText("Paste key").performClick()
            compose.onNodeWithText("Private key").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            assertFalse(NetworkSettings.hasKey(endpoint))
            compose.onNodeWithText("Paste key").performClick()
            compose.onNodeWithText("Import").assertIsEnabled().performClick()
            compose.waitUntil(timeoutMillis=10000) {NetworkSettings.hasKey(endpoint)}
            compose.onNodeWithText("Saved securely on this phone").assertIsDisplayed()
            val stored=context.getSharedPreferences("network",0).getString("keys","").orEmpty()
            assertFalse(stored.contains(key));assertFalse(stored.contains("PRIVATE KEY"))
        } finally {
            runBlocking {NetworkSettings.removeKey(endpoint)}
            compose.runOnIdle {if(previous!=null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()}
        }
    }
    @Test fun emptyClipboardAllowsManualPasteAndRejectsInvalidKeys() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previous=clipboard.primaryClip
        compose.setContent {MaterialTheme {Column {SshKeyEditor("ssh://invalid-paste@127.0.0.1:2222")}}}
        try {
            compose.runOnIdle {clipboard.clearPrimaryClip()}
            compose.onNodeWithText("Paste key").performClick()
            compose.onNodeWithText("Import").assertIsNotEnabled()
            compose.onNodeWithText("Private key").performTextInput("not a private key")
            compose.onNodeWithText("Import").performClick()
            compose.waitUntil(timeoutMillis=10000) {compose.onAllNodesWithText("ssh: no key found").fetchSemanticsNodes().isNotEmpty()}
            assertFalse(NetworkSettings.hasKey("ssh://invalid-paste@127.0.0.1:2222"))
            compose.onNodeWithText("Cancel").performClick()
        } finally {compose.runOnIdle {if(previous!=null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()}}
    }
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
        compose.onNodeWithText("Import file").assertIsDisplayed()
        compose.onNodeWithText("Optional for Tailscale SSH.").assertIsDisplayed()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {bitmap->
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"ssh-settings.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
    }
}
