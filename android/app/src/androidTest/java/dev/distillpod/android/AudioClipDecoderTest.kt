package dev.distillpod.android

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.core.readWhisperWave
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class AudioClipDecoderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun fixture() = File.createTempFile("source-", ".wav", context.cacheDir).also { file ->
        instrumentation.context.assets.open("jfk.wav").use { input -> file.outputStream().use(input::copyTo) }
    }

    @Test fun waveDecoderProducesWhisperCompatibleAudio() {
        val source = fixture()
        val output = File.createTempFile("decoded-", ".wav", context.cacheDir)
        try {
            AudioClipDecoder().decode(source, output)
            val expected = readWhisperWave(source.readBytes())
            val actual = readWhisperWave(output.readBytes())
            assertArrayEquals(expected, actual, 0.0001f)
        } finally { source.delete(); output.delete() }
    }

    @Test fun malformedAndOversizedAudioAreRejectedWithoutPublishingOutput() {
        val source = fixture()
        val output = File.createTempFile("decoded-", ".wav", context.cacheDir)
        try {
            // A valid 601-second WAV exceeds the harness limit before PCM accumulation.
            RandomAccessFile(source, "rw").use { file ->
                val size = 601 * 16000 * 2
                file.setLength((size + 44).toLong())
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray()); putInt(size + 36); put("WAVEfmt ".toByteArray())
                    putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000)
                    putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
                }
                file.seek(0); file.write(header.array())
            }
            assertThrows(IllegalArgumentException::class.java) { AudioClipDecoder().decode(source, output) }
            assertFalse(output.exists())
            source.writeText("invalid audio")
            assertThrows(Exception::class.java) { AudioClipDecoder().decode(source, output) }
            assertFalse(output.exists())
        } finally { source.delete(); output.delete() }
    }

    @Test fun cancellationDuringDecodeDiscardsPartialAudio() {
        val source = fixture()
        val output = File.createTempFile("decoded-", ".wav", context.cacheDir)
        var checks = 0
        try {
            assertThrows(CancellationException::class.java) {
                AudioClipDecoder().decode(source, output) { ++checks > 4 }
            }
            assertFalse(output.exists())
        } finally { source.delete(); output.delete() }
    }
    @Test fun boundedDecodePreservesTheSelectedOriginalWindow() {
        val source = fixture()
        val output = File.createTempFile("chunk-", ".wav", context.cacheDir)
        try {
            val result = AudioClipDecoder().decodeChunk(source, output, 2000, 3000)
            val original = readWhisperWave(source.readBytes())
            val window = readWhisperWave(output.readBytes())
            assertArrayEquals(original.copyOfRange(32000, 80000), window, 0.0001f)
            assertEquals(2000L, result.startMs); assertEquals(5000L, result.endMs)
            assertEquals(11000L, result.sourceDurationMs)
        } finally { source.delete(); output.delete() }
    }

    @Test fun stereoAacAt44100HzDecodesAndTranscribesRealSpeech() {
        val source = fixture()
        val compressed = File.createTempFile("compressed-", ".m4a", context.cacheDir)
        val output = File.createTempFile("decoded-", ".wav", context.cacheDir)
        val model = File.createTempFile("model-", ".bin", context.cacheDir)
        try {
            val original = readWhisperWave(source.readBytes())
            encodeStereoAac(original, compressed)
            AudioClipDecoder().decode(compressed, output)
            val samples = readWhisperWave(output.readBytes())
            assertTrue(kotlin.math.abs(samples.size - original.size) < 1600)
            instrumentation.context.assets.open("ggml-tiny.en.bin").use { input -> model.outputStream().use(input::copyTo) }
            val result = NativeWhisper().transcribe(model.absolutePath, samples, "en")
            assertTrue(result.lowercase().contains("country"))
            assertTrue(result.contains("startMs"))
        } finally { source.delete(); compressed.delete(); output.delete(); model.delete() }
    }
    @Test fun rounded44100HzTimestampsCannotOverflowABoundedChunk() {
        val source = fixture()
        val compressed = File.createTempFile("rounded-", ".m4a", context.cacheDir)
        val output = File.createTempFile("bounded-", ".wav", context.cacheDir)
        try {
            encodeStereoAac(readWhisperWave(source.readBytes()), compressed)
            for ((start, length) in listOf(17L to 1997L, 1111L to 3333L, 4777L to 2009L)) {
                val result = AudioClipDecoder().decodeChunk(compressed, output, start, length)
                val samples = readWhisperWave(output.readBytes())
                val expected = length * 16
                assertTrue("Timestamp rounding must not add an out-of-window sample", samples.size.toLong() in (expected - 2)..expected)
                assertTrue(result.endMs <= start + length)
            }
        } finally { source.delete(); compressed.delete(); output.delete() }
    }

    @Test fun mp3DecodesAndTranscribesRealSpeech() {
        val source = File.createTempFile("compressed-", ".mp3", context.cacheDir)
        val output = File.createTempFile("decoded-", ".wav", context.cacheDir)
        val model = File.createTempFile("model-", ".bin", context.cacheDir)
        try {
            instrumentation.context.assets.open("jfk.mp3").use { input -> source.outputStream().use(input::copyTo) }
            instrumentation.context.assets.open("ggml-tiny.en.bin").use { input -> model.outputStream().use(input::copyTo) }
            AudioClipDecoder().decode(source, output)
            val result = NativeWhisper().transcribe(model.absolutePath, readWhisperWave(output.readBytes()), "en")
            assertTrue(result.lowercase().contains("country"))
            assertTrue(result.contains("startMs"))
        } finally { source.delete(); output.delete(); model.delete() }
    }

    // Generate a public-sample fixture through Android's encoder, independent of the decoder.
    private fun encodeStereoAac(samples: FloatArray, output: File) {
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96000)
        }
        val encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val info = MediaCodec.BufferInfo()
            val frames = samples.size.toLong() * 44100 / 16000
            var frame = 0L
            var inputDone = false
            var track = -1
            val deadline = System.nanoTime() + 30_000_000_000L
            while (true) {
                check(System.nanoTime() < deadline) { "Fixture encoder timed out" }
                if (!inputDone) {
                    val index = encoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val input = requireNotNull(encoder.getInputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                        input.clear()
                        val start = frame
                        while (frame < frames && input.remaining() >= 4) {
                            val sample = (samples[(frame * 16000 / 44100).toInt()] * 32768).toInt().coerceIn(-32768, 32767).toShort()
                            input.putShort(sample); input.putShort(sample); frame++
                        }
                        inputDone = frame == frames
                        encoder.queueInputBuffer(index, 0, input.position(), start * 1_000_000 / 44100,
                            if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val index = encoder.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.outputFormat); muxer.start(); muxerStarted = true
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            muxer.writeSampleData(track, requireNotNull(encoder.getOutputBuffer(index)), info)
                        }
                    } finally { encoder.releaseOutputBuffer(index, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            try { encoder.release() } finally {
                try { if (muxerStarted) muxer.stop() } finally { muxer.release() }
            }
        }
    }
}
