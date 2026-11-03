package dev.distillpod.android

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileImportTest {
    @get:Rule val compose = createAndroidComposeRule<DiagnosticsActivity>()
    @Test fun systemPickerImportsValidatedWaveAndCancellationLeavesItIntact() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = compose.activity.contentResolver
        val name = "distillpod-fixture-${System.currentTimeMillis()}.wav"
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
        })!!
        try {
            instrumentation.context.assets.open("jfk.wav").use { input -> resolver.openOutputStream(uri)!!.use(input::copyTo) }
            compose.onNodeWithText("Import audio clip").performScrollTo().performClick()
            val device = UiDevice.getInstance(instrumentation)
            var file = device.wait(Until.findObject(By.text(name)), 10000)
            if(file == null) {
                DocumentPickerUi.location(device, By.text("Downloads"))
                file = device.wait(Until.findObject(By.text(name)), 10000)
            }
            assertNotNull("The Android document picker must show the fixture", file)
            device.waitForIdle()
            device.findObject(UiSelector().text(name)).click()
            compose.waitUntil(10000) { compose.onAllNodes(hasText("Imported clip.wav", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            val imported = compose.activity.getFileStreamPath("clip.wav")
            assertTrue(imported.length() > 0)
            val original = imported.readBytes()
            compose.onNodeWithText("Import audio clip").performScrollTo().performClick()
            device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 5000)
            device.pressBack()
            compose.waitForIdle()
            assertArrayEquals(original, imported.readBytes())
        } finally { resolver.delete(uri, null, null) }
    }
}
