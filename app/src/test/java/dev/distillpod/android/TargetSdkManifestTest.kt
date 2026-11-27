package dev.distillpod.android

import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Google Play accepts only builds that target Android 16 (API 36) (#321). Targeting 36 also removes
 * opt-outs this app must not depend on: on Android 16 the platform ignores orientation, resizability
 * and aspect-ratio restrictions on displays at least 600dp wide, and the edge-to-edge opt-out no
 * longer applies. The merged manifest (including every library's components) declares none of them,
 * so the large-screen and edge-to-edge behaviour is the same on Android 15 and 16.
 */
class TargetSdkManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val manifest by lazy {
        // AGP hands Robolectric the merged manifest through this generated test configuration.
        val config = Properties().apply {
            TargetSdkManifestTest::class.java.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")!!.use(::load)
        }
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File(config.getProperty("android_merged_manifest")))
    }
    private fun elements(tag: String) = manifest.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    @Test fun `the build targets Android 16`() {
        assertEquals("36", elements("uses-sdk").single().getAttributeNS(android, "targetSdkVersion"))
    }

    @Test fun `no component relies on restrictions Android 16 ignores on large screens`() {
        for (component in elements("application") + elements("activity") + elements("activity-alias")) {
            val name = component.getAttributeNS(android, "name").ifEmpty { component.tagName }
            for (attribute in listOf("screenOrientation", "minAspectRatio", "maxAspectRatio"))
                assertEquals("$name sets $attribute", "", component.getAttributeNS(android, attribute))
            assertTrue("$name is not resizeable", component.getAttributeNS(android, "resizeableActivity") != "false")
        }
        val compatProperties = elements("property").map { it.getAttributeNS(android, "name") }
        assertTrue("restricted-resizability opt-out: $compatProperties",
            "android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY" !in compatProperties)
    }

    @Test fun `no app resource opts out of edge-to-edge`() {
        // Gradle runs unit tests in the module directory.
        val resources = File("src/main/res").walkTopDown().filter { it.isFile && it.extension == "xml" }.toList()
        assertTrue(resources.isNotEmpty())
        assertEquals(emptyList<File>(), resources.filter { "windowOptOutEdgeToEdgeEnforcement" in it.readText() })
    }

    @Test fun `predictive back stays enabled rather than opted out`() {
        for (component in elements("application") + elements("activity"))
            assertTrue(component.getAttributeNS(android, "name"), component.getAttributeNS(android, "enableOnBackInvokedCallback") != "false")
    }
}
