package dev.distillpod.android.voice

import android.media.AudioDeviceInfo
import org.junit.Assert.*
import org.junit.Test

class HeadphoneVoiceShortcutTest {
    @Test fun `car and unknown connection never fall back to headset or phone microphone`() {
        assertEquals(HeadphoneNextAction.UNAVAILABLE, headphoneNextAction(VoiceState(), true, false, true))
        assertEquals(HeadphoneNextAction.UNAVAILABLE, headphoneNextAction(VoiceState(), null, false, true))
        assertEquals(HeadphoneNextAction.START_CAR, headphoneNextAction(VoiceState(), true, true, true))
    }
    @Test fun `phone shortcut needs an armed service`() {
        assertEquals(HeadphoneNextAction.UNAVAILABLE, headphoneNextAction(VoiceState(), false, false, false))
        assertEquals(HeadphoneNextAction.START_PHONE, headphoneNextAction(VoiceState(), false, false, true))
    }
    @Test fun `next finishes listening or cancels preparation and answer`() {
        assertEquals(HeadphoneNextAction.DONE, headphoneNextAction(VoiceState("Listening"), false, false, true))
        for (phase in listOf("Preparing", "Answering"))
            assertEquals(HeadphoneNextAction.CANCEL, headphoneNextAction(VoiceState(phase), true, true, false))
        assertEquals(HeadphoneNextAction.WAIT, headphoneNextAction(VoiceState("Finishing"), false, false, true))
    }
    @Test fun `only a live service binding can start a headset question`() {
        val shortcut = HeadphoneVoiceShortcut(); var calls = 0
        assertFalse(shortcut.ask()); assertFalse(shortcut.ready.value)
        val old = shortcut.attach { fail("Old service") }
        val current = shortcut.attach { calls++ }
        old.close()
        assertTrue(shortcut.ready.value); assertTrue(shortcut.ask()); assertEquals(1, calls)
        current.close(); assertFalse(shortcut.ready.value); assertFalse(shortcut.ask())
    }
    @Test fun `headphone capture excludes handset speaker and bluetooth media only endpoints`() {
        for (type in listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)) assertFalse(headphoneInputType(type))
        for (type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)) assertTrue(headphoneInputType(type))
    }
}
