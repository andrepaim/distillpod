package dev.distillpod.android

import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Android 13+ routes Back through OnBackInvokedCallback (and Android 14+ animates it) only for apps
 * that opt in. Compose BackHandler, dialogs, sheets and popups all register such callbacks;
 * PredictiveBackDeviceTest checks that behaviour on an emulator.
 */
class PredictiveBackManifestTest {
    @Test fun `the merged application manifest opts in to predictive back`() {
        // AGP hands Robolectric the merged manifest through this generated test configuration.
        val config = Properties().apply {
            PredictiveBackManifestTest::class.java.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")!!.use(::load)
        }
        val manifest = File(config.getProperty("android_merged_manifest"))
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val application = document.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        assertEquals("true", application.getAttributeNS("http://schemas.android.com/apk/res/android", "enableOnBackInvokedCallback"))
    }
}
