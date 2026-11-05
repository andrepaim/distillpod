package dev.distillpod.android

import android.content.Intent
import android.view.KeyEvent
import dev.distillpod.android.playback.HeadphoneDistillButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeadphoneDistillButtonTest {
    private fun event(action: Int = KeyEvent.ACTION_DOWN, downTime: Long = 10, repeat: Int = 0,
                      code: Int = KeyEvent.KEYCODE_MEDIA_PREVIOUS) =
        Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT,
            KeyEvent(downTime, downTime, action, code, repeat))

    @Test fun `disabled option and unrelated or malformed intents are delegated`() {
        val button = HeadphoneDistillButton()
        val unexpected = { fail("Unexpected capture") }
        assertFalse(button.handle(event(), false, unexpected))
        assertFalse(button.handle(event(KeyEvent.ACTION_UP), false, unexpected))
        for (code in listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_VOLUME_UP)) {
            assertFalse(button.handle(event(code = code), true, unexpected))
        }
        assertFalse(button.handle(Intent(Intent.ACTION_MEDIA_BUTTON), true, unexpected))
        assertFalse(button.handle(Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, "invalid"), true, unexpected))
        assertFalse(button.handle(event().setAction(Intent.ACTION_VIEW), true, unexpected))
        assertFalse(button.handle(event(KeyEvent.ACTION_MULTIPLE), true, unexpected))
    }

    @Test fun `down up repeated down and duplicate delivery capture once`() {
        var now = 1000L; var captures = 0
        val button = HeadphoneDistillButton { now }
        assertTrue(button.handle(event(), true) { captures++ })
        assertTrue(button.handle(event(), true) { captures++ })
        now += 1000
        assertTrue(button.handle(event(repeat = 1), true) { captures++ })
        assertTrue(button.handle(event(KeyEvent.ACTION_UP), true) { captures++ })
        assertTrue(button.handle(event(), true) { captures++ })
        assertEquals(1, captures)
    }

    @Test fun `short repeat bursts are consumed but later distinct presses capture`() {
        var now = 1000L; var captures = 0
        val button = HeadphoneDistillButton { now }
        button.handle(event(), true) { captures++ }
        now += 100
        assertTrue(button.handle(event(downTime = 20), true) { captures++ })
        now += 1000
        button.handle(event(downTime = 30), true) { captures++ }
        assertEquals(2, captures)
    }

    @Test fun `disabling during a press still consumes its repeats and release`() {
        val button = HeadphoneDistillButton(); var captures = 0
        button.handle(event(), true) { captures++ }
        assertTrue(button.handle(event(repeat = 1), false) { captures++ })
        assertTrue(button.handle(event(KeyEvent.ACTION_UP), false) { captures++ })
        assertFalse(button.handle(event(downTime = 20), false) { captures++ })
        assertEquals(1, captures)
    }

    @Test fun `orphan release and repeat cannot create a moment`() {
        val button = HeadphoneDistillButton()
        assertTrue(button.handle(event(KeyEvent.ACTION_UP), true) { fail("Captured on release") })
        assertTrue(button.handle(event(repeat = 2), true) { fail("Captured on repeat") })
    }
}
