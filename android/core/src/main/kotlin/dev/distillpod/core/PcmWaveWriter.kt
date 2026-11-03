package dev.distillpod.core

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

/** Bounded, streaming PCM16 downmix and area-average resampling for the feasibility harness. */
class PcmWaveWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val maxDurationMs: Long = 600_000,
) : Closeable {
    init {
        require(sampleRate in 8000..192000 && channels in 1..8) { "Unsupported decoded audio format" }
        require(maxDurationMs in 1..600_000)
    }
    private val output = BufferedOutputStream(file.outputStream()).apply { write(ByteArray(44)) }
    private var inputFrames = 0L
    private var outputFrames = 0
    val samplesWritten: Int get() = outputFrames
    private var remaining = sampleRate
    private var weighted = 0.0
    private var finished = false

    fun append(pcm: ByteBuffer) {
        check(!finished)
        pcm.order(ByteOrder.LITTLE_ENDIAN)
        require(pcm.remaining() % (channels * 2) == 0) { "Incomplete PCM sample frame" }
        while (pcm.hasRemaining()) {
            inputFrames++
            require(inputFrames * 1000 <= maxDurationMs * sampleRate) { "Audio exceeds the ten-minute decoded clip limit" }
            var sum = 0L
            repeat(channels) { sum += pcm.short }
            val mono = sum.toDouble() / channels
            var units = 16000
            while (units > 0) {
                val take = min(units, remaining)
                weighted += mono * take
                units -= take
                remaining -= take
                if (remaining == 0) {
                    val sample = (weighted / sampleRate).roundToInt().coerceIn(-32768, 32767)
                    output.write(sample and 255); output.write((sample shr 8) and 255)
                    outputFrames++
                    weighted = 0.0; remaining = sampleRate
                }
            }
        }
    }

    /** Only completed decoding may finalize the interchange file. Caller discards failures. */
    fun finish() {
        check(!finished)
        require(outputFrames > 0) { "Audio contains no decoded samples" }
        output.flush()
        val size = outputFrames * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
        }.array()
        RandomAccessFile(file, "rw").use { it.write(header) }
        finished = true
    }

    override fun close() { output.close() }
}
