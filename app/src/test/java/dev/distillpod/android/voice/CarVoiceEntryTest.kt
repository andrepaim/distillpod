package dev.distillpod.android.voice

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@androidx.annotation.OptIn(androidx.car.app.annotations.ExperimentalCarApi::class)
@RunWith(RobolectricTestRunner::class)
class CarVoiceEntryTest {
    @Test fun `templated player exposes Ask in its header`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        var asked = false
        val template = carPlaybackTemplate(context, { asked = true }, {})
        val ask = template.header!!.endHeaderActions.single { it.title.toString() == "Ask" }
        assertNotNull(ask.icon)
        ask.onClickDelegate!!.sendClick(object : androidx.car.app.OnDoneCallback {})
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(asked)
    }
    @Test fun `templated player retains access to podcast library`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val template = carPlaybackTemplate(context, {}, {})
        assertTrue(template.header!!.endHeaderActions.any { it.title.toString() == "Library" })
    }
    @Test fun `unbound host cannot silently fall back to phone microphone`() {
        assertFalse(CarVoiceEntry().open())
    }
    @Test fun `destroying an old car session cannot unregister the replacement`() {
        val entry = CarVoiceEntry()
        var first = 0; var second = 0
        val old = entry.attach { first++ }
        val current = entry.attach { second++ }
        old.close()
        assertTrue(entry.open()); assertEquals(0,first); assertEquals(1,second)
        current.close(); assertFalse(entry.open())
    }
    @Test fun `host launch failure returns unsupported`() {
        val entry = CarVoiceEntry()
        entry.attach { error("Host rejected app") }
        assertFalse(entry.open())
    }
    @Test fun `projected microphone implementation is packaged`() {
        Class.forName("androidx.car.app.media.ProjectedCarAudioRecord")
    }
}
