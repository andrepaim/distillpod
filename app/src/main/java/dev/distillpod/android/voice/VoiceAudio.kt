package dev.distillpod.android.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import kotlinx.coroutines.*

/** Car PCM is 16 kHz mono little-endian; interpolate to Realtime's 24 kHz. */
class PcmResampler {
    private var previous: Short? = null
    private var inputIndex = 0L
    private var outputIndex = 0L
    fun convert(bytes: ByteArray): ByteArray {
        require(bytes.size % 2 == 0)
        val out = java.io.ByteArrayOutputStream()
        for (i in bytes.indices step 2) {
            val sample = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort()
            val before = previous
            while (outputIndex * 2 <= inputIndex * 3) {
                val value = if (before == null) sample.toInt() else {
                    val fraction = (outputIndex * 2 - (inputIndex - 1) * 3).toDouble() / 3
                    (before + (sample - before) * fraction).toInt().coerceIn(-32768, 32767)
                }
                out.write(value and 255); out.write((value shr 8) and 255); outputIndex++
            }
            previous = sample; inputIndex++
        }
        return out.toByteArray()
    }
}
@SuppressLint("MissingPermission")
class PhoneVoiceInput : VoiceInput {
    private val recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
        .setBufferSizeInBytes(9600).build()
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    override fun start() { check(recorder.state == AudioRecord.STATE_INITIALIZED); recorder.startRecording() }
    override fun read(): ByteArray? {
        val bytes = ByteArray(960)
        val count = recorder.read(bytes, 0, bytes.size)
        check(count >= 0) { "Microphone read failed" }
        return if (count == 0) null else bytes.copyOf(count)
    }
    override fun close() { if (closed.compareAndSet(false, true)) { runCatching { recorder.stop() }; recorder.release() } }
}
class AndroidVoiceOutput(context: Context, private val interrupted: () -> Unit) : VoiceOutput {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) interrupted() }.build()
    private val track: AudioTrack
    private var frames = 0L
    init {
        check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio focus unavailable" }
        try {
            track = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(24000).setTransferMode(AudioTrack.MODE_STREAM).build()
            check(track.state == AudioTrack.STATE_INITIALIZED); track.play()
        } catch (e: Exception) { manager.abandonAudioFocusRequest(focus); throw e }
    }
    override suspend fun play(pcm: ByteArray) = withContext(Dispatchers.IO) {
        require(pcm.size % 2 == 0)
        var offset = 0
        while (offset < pcm.size) {
            currentCoroutineContext().ensureActive()
            check(frames < 24000 * 45L) { "Answer exceeded duration limit" }
            val written = track.write(pcm, offset, minOf(960, pcm.size - offset), AudioTrack.WRITE_NON_BLOCKING)
            check(written >= 0)
            if (written == 0) delay(5) else { offset += written; frames += written / 2 }
        }
    }
    override suspend fun drain() { withTimeout(46_000) { while ((track.playbackHeadPosition.toLong() and 0xffffffffL) < frames) delay(10) } }
    override fun close() { runCatching { track.pause(); track.flush(); track.release() }; manager.abandonAudioFocusRequest(focus) }
}

/** A local cue finishes before microphone capture, so it is not sent to the model. */
suspend fun voiceReadyTone() {
    val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 40)
    try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120); delay(160) }
    finally { tone.release() }
}
