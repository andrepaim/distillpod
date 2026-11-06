package dev.distillpod.android.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal fun headphoneInputType(type: Int) = type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)

/** Open after pausing the podcast; restore the media route before resuming. No handset fallback. */
internal class HeadphoneAudioRoute(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var chosen: AudioDeviceInfo? = null
    private var previousMode: Int? = null
    var input: AudioDeviceInfo? = null
        private set

    suspend fun open(): HeadphoneAudioRoute {
        try {
            if (manager.mode != AudioManager.MODE_NORMAL) throw VoiceSetupFailure("Finish your call before asking a question.")
            if (Build.VERSION.SDK_INT >= 31) {
                val device = manager.availableCommunicationDevices.firstOrNull { headphoneInputType(it.type) }
                    ?: throw VoiceSetupFailure("Connect a headset with a microphone before asking.")
                previousMode = manager.mode
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                chosen = device
                check(manager.setCommunicationDevice(device)) { "Headset audio unavailable" }
                withTimeout(4000) { while (manager.communicationDevice?.id != device.id) delay(25) }
            }
            withTimeout(4000) {
                while (input == null) {
                    input = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                        headphoneInputType(it.type) && (chosen == null || it.type == chosen!!.type)
                    }
                    if (input == null) delay(25)
                }
            }
            // Older Android versions support wired/USB input here; SCO needs the API 31 route.
            if (Build.VERSION.SDK_INT < 31 && input!!.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET))
                throw VoiceSetupFailure("Bluetooth headset voice requires Android 12 or later. Connect a wired or USB headset.")
            return this
        } catch (error: Exception) {
            close()
            if (error is SecurityException) throw VoiceSetupFailure("Headset audio is unavailable. Check microphone permission and reconnect your headset.")
            throw error
        }
    }
    override fun close() {
        val savedMode = previousMode
        previousMode = null; chosen = null; input = null
        if (Build.VERSION.SDK_INT >= 31 && savedMode != null) {
            // MODE_NORMAL removes this process's mode request, including underneath an active call.
            try { manager.clearCommunicationDevice() } finally { manager.mode = savedMode }
        }
    }
}
