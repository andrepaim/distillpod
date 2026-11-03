package dev.distillpod.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmWaveWriterTest {
    private fun pcm(vararg samples: Int) = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        samples.forEach { putShort(it.toShort()) }; flip()
    }

    @Test fun `stereo downmix and resampling are independent of decoder buffer boundaries`() {
        val one = File.createTempFile("pcm-one", ".wav")
        val chunks = File.createTempFile("pcm-chunks", ".wav")
        try {
            PcmWaveWriter(one, 48000, 2).use { writer ->
                writer.append(pcm(12000, 6000, 6000, 0, 0, -6000, -12000, -6000, -6000, 0, 0, 6000))
                writer.finish()
            }
            PcmWaveWriter(chunks, 48000, 2).use { writer ->
                writer.append(pcm(12000, 6000, 6000, 0))
                writer.append(pcm(0, -6000, -12000, -6000, -6000, 0, 0, 6000))
                writer.finish()
            }
            assertArrayEquals(one.readBytes(), chunks.readBytes())
            assertArrayEquals(floatArrayOf(3000 / 32768f, -3000 / 32768f), readWhisperWave(one.readBytes()), 0.00001f)
        } finally { one.delete(); chunks.delete() }
    }

    @Test fun `fractional rate conversion preserves duration without accumulating rounding error`() {
        val file = File.createTempFile("pcm-fractional", ".wav")
        try {
            PcmWaveWriter(file, 44100, 1).use { writer ->
                repeat(100) { writer.append(pcm(*IntArray(441) { 1234 })) }
                writer.finish()
            }
            val samples = readWhisperWave(file.readBytes())
            assertEquals(16000, samples.size)
            assertEquals(1234 / 32768f, samples.last(), 0.00001f)
        } finally { file.delete() }
    }

    @Test fun `decoded duration limit counts input frames and rejects overflow`() {
        val file = File.createTempFile("pcm-limit", ".wav")
        try {
            PcmWaveWriter(file, 16000, 1, maxDurationMs = 1).use { writer ->
                writer.append(pcm(*IntArray(16)))
                assertThrows(IllegalArgumentException::class.java) { writer.append(pcm(0)) }
            }
        } finally { file.delete() }
    }

    @Test fun `empty audio and partial sample frames cannot become valid clips`() {
        val file = File.createTempFile("pcm-invalid", ".wav")
        try {
            PcmWaveWriter(file, 16000, 2).use { writer ->
                assertThrows(IllegalArgumentException::class.java) { writer.append(pcm(1)) }
                assertThrows(IllegalArgumentException::class.java) { writer.finish() }
            }
        } finally { file.delete() }
    }
}
