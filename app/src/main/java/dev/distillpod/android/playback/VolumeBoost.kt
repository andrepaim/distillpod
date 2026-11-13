package dev.distillpod.android.playback

import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer

/** Volume boost target gain: +6 dB, moderate enough to keep speech clear. */
internal const val VOLUME_BOOST_GAIN_MB = 600

/** A loudness effect attached to one audio session. */
internal interface LoudnessEffect { fun enable(gainMb: Int); fun release() }

/** Android's [LoudnessEnhancer] on the player's audio session. */
internal class AndroidLoudnessEffect(session: Int) : LoudnessEffect {
    private val enhancer = LoudnessEnhancer(session)
    override fun enable(gainMb: Int) {
        try {
            enhancer.setTargetGain(gainMb)
            val status = enhancer.setEnabled(true)
            check(status == AudioEffect.SUCCESS) { "Volume boost could not be enabled ($status)" }
        } catch (failure: Throwable) { enhancer.release(); throw failure }
    }
    override fun release() = enhancer.release()
}

/** Published for the player UI and device tests; [session] is set while the effect is attached. */
data class VolumeBoostStatus(val requested: Boolean = false, val session: Int? = null, val unavailable: Boolean = false)

/**
 * Owns the volume boost effect for the playback service (#183): attached to the player's current audio
 * session while enabled, moved when the session changes and released when disabled or the service stops.
 * The boost is a session effect; the sleep timer's fade uses the player volume, so the two compose.
 * An effect the device cannot provide is reported and leaves playback unboosted.
 */
internal class VolumeBoost(private val create: (session: Int) -> LoudnessEffect,
    private val onFailure: (Throwable) -> Unit, private val publish: (VolumeBoostStatus) -> Unit) {
    private var session = 0
    private var enabled = false
    private var released = false
    private var effect: LoudnessEffect? = null

    /** [session] 0 is unset; an effect there would apply to the global output mix, so none is attached. */
    fun sessionChanged(session: Int) {
        if (released || session == this.session) return
        this.session = session
        update()
    }

    fun enabledChanged(enabled: Boolean) {
        if (released || enabled == this.enabled) return
        this.enabled = enabled
        update()
    }

    fun release() {
        if (released) return
        released = true
        detach()
    }

    private fun detach() {
        val current = effect ?: return
        effect = null
        runCatching { current.release() }.onFailure(onFailure)
    }

    private fun update() {
        detach()
        if (!enabled) { publish(VolumeBoostStatus()); return }
        if (session == 0) { publish(VolumeBoostStatus(requested = true)); return }
        try {
            effect = create(session).also { it.enable(VOLUME_BOOST_GAIN_MB) }
            publish(VolumeBoostStatus(requested = true, session = session))
        } catch (failure: RuntimeException) {
            onFailure(failure)
            publish(VolumeBoostStatus(requested = true, unavailable = true))
        }
    }
}
