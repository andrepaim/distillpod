package dev.distillpod.core
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Strict interchange format for the native feasibility harness: 16 kHz mono PCM16. */
fun readWhisperWave(bytes: ByteArray): FloatArray {
    require(bytes.size in 44..(16_000 * 2 * 60 * 10 + 4096)) { "WAV must be a bounded clip of at most ten minutes" }
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
    require(tag(0) == "RIFF" && tag(8) == "WAVE") { "Not a WAV file" }
    require(b.getInt(4).toLong() + 8 <= bytes.size) { "Truncated RIFF" }
    var cursor = 12; var formatOk = false
    while (cursor + 8 <= bytes.size) {
        val size = b.getInt(cursor + 4)
        require(size >= 0 && size <= bytes.size - cursor - 8) { "Truncated WAV chunk" }
        when (tag(cursor)) {
            "fmt " -> {
                require(size >= 16)
                require(b.getShort(cursor + 8).toInt() == 1 && b.getShort(cursor + 10).toInt() == 1 && b.getInt(cursor + 12) == 16000 && b.getShort(cursor + 22).toInt() == 16) { "Expected mono 16 kHz PCM16" }
                formatOk = true
            }
            "data" -> {
                require(formatOk && size % 2 == 0)
                return FloatArray(size / 2) { b.getShort(cursor + 8 + it * 2) / 32768f }
            }
        }
        cursor += 8 + size + size % 2
    }
    error("No PCM data")
}
