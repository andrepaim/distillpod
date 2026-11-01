package dev.distillpod.core
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class WaveTest {
    private fun wave(rate: Int = 16000, channels: Short = 1): ByteArray {
        val b = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(40).put("WAVEfmt ".toByteArray()).putInt(16)
        b.putShort(1).putShort(channels).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(4).putShort(16384).putShort(-16384)
        return b.array()
    }
    @Test fun `decodes normalized mono 16k PCM`() {
        val samples = readWhisperWave(wave())
        assertArrayEquals(floatArrayOf(0.5f, -0.5f), samples, 0.00001f)
    }
    @Test fun `rejects incompatible format before native inference`() {
        assertThrows(IllegalArgumentException::class.java) { readWhisperWave(wave(44100)) }
        assertThrows(IllegalArgumentException::class.java) { readWhisperWave(wave(channels = 2)) }
    }
    @Test fun `rejects truncated audio and non wave`() {
        assertThrows(IllegalArgumentException::class.java) { readWhisperWave(wave().copyOf(46)) }
        assertThrows(IllegalArgumentException::class.java) { readWhisperWave(ByteArray(12)) }
    }
}
