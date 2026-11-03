package dev.distillpod.android

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.distillpod.core.PcmWaveWriter
import java.io.File
import java.util.concurrent.CancellationException

/** Decode into a separate partial WAV; callers publish only after successful EOS and validation. */
class AudioClipDecoder {
    fun decode(source: File, destination: File, isCancelled: () -> Boolean = { false }) {
        decodeRange(source, destination, 0, 600_000, true, isCancelled)
    }
    data class DecodedChunk(val startMs: Long, val endMs: Long, val sourceDurationMs: Long, val sourceEnded: Boolean = false)
    fun decodeChunk(source: File, destination: File, startMs: Long, lengthMs: Long = 120_000, isCancelled: () -> Boolean = { false }): DecodedChunk =
        decodeRange(source, destination, startMs, lengthMs, false, isCancelled)
    private fun decodeRange(source: File, destination: File, startMs: Long, lengthMs: Long, strictClip: Boolean, isCancelled: () -> Boolean): DecodedChunk {
        require(startMs >= 0 && lengthMs in 1..600_000 && startMs <= 7 * 86400000L)
        val startUs = startMs * 1000
        val endUs = (startMs + lengthMs) * 1000
        fun checkCancelled() { if (isCancelled()) throw CancellationException("Audio import cancelled") }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var writer: PcmWaveWriter? = null
        var started = false
        try {
            checkCancelled()
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No supported audio track found")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val sourceDurationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            if (strictClip && sourceDurationMs > 600_000) {
                throw IllegalArgumentException("Audio exceeds the ten-minute decoded clip limit")
            }
            if (startMs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start(); started = true
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var lastProgress = System.nanoTime()
            var outputFormat: Pair<Int, Int>? = null
            var selectedFrames = 0L
            var firstWrittenUs: Long? = null
            var lastWrittenUs = startUs
            var sourceEnded = false
            var outputEnded = false
            while (true) {
                checkCancelled()
                check(System.nanoTime() - lastProgress < 30_000_000_000L) { "Audio decoder stopped making progress" }
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(index))
                        buffer.clear()
                        val boundedEnd = extractor.sampleTime >= endUs + 1_000_000
                        val count = if (boundedEnd) -1 else extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            if (!boundedEnd) sourceEnded = true
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, count, extractor.sampleTime.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                        lastProgress = System.nanoTime()
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val decoded = decoder.outputFormat
                    val encoding = if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    require(encoding == AudioFormat.ENCODING_PCM_16BIT) { "Decoder must output PCM16" }
                    val next = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE) to decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    require(outputFormat == null || next == outputFormat) { "Audio format changes within this clip" }
                    if (writer == null) writer = PcmWaveWriter(destination, next.first, next.second, lengthMs)
                    outputFormat = next
                    lastProgress = System.nanoTime()
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buffer = requireNotNull(decoder.getOutputBuffer(index))
                            val decodedFormat = checkNotNull(outputFormat) { "Missing decoded audio format" }
                            val frameSize = decodedFormat.second * 2
                            val frameCount = info.size / frameSize
                            val first = (((startUs - info.presentationTimeUs).coerceAtLeast(0) * decodedFormat.first + 999999) / 1_000_000).coerceAtMost(frameCount.toLong()).toInt()
                            val timestampLast = (((endUs - info.presentationTimeUs).coerceAtLeast(0) * decodedFormat.first + 999999) / 1_000_000).coerceAtMost(frameCount.toLong()).toInt()
                            // Codec timestamps are rounded to microseconds. Clamp selected frames
                            // as well as timestamps so that an extra boundary sample cannot exceed
                            // the requested PCM duration (notably with 44.1 kHz MP3).
                            val remaining = (lengthMs * decodedFormat.first / 1000 - selectedFrames).coerceAtLeast(0)
                            val last = minOf(timestampLast.toLong(), first + remaining).toInt()
                            if (last > first) {
                                buffer.position(info.offset + first * frameSize); buffer.limit(info.offset + last * frameSize)
                                checkNotNull(writer) { "Missing decoded audio format" }.append(buffer)
                                selectedFrames += last - first
                                if (firstWrittenUs == null) firstWrittenUs = info.presentationTimeUs + first * 1_000_000L / decodedFormat.first
                                lastWrittenUs = maxOf(lastWrittenUs, info.presentationTimeUs + last * 1_000_000L / decodedFormat.first)
                            }
                        }
                    } finally { decoder.releaseOutputBuffer(index, false) }
                    lastProgress = System.nanoTime()
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outputEnded = true; break }
                    if (info.presentationTimeUs >= endUs) break
                }
            }
            checkCancelled()
            checkNotNull(writer) { "Audio contains no decoded samples" }.finish()
            val decodedStartMs = checkNotNull(firstWrittenUs) / 1000
            val decodedEndMs = minOf(lastWrittenUs / 1000, startMs + lengthMs)
            val completeSource = sourceEnded && outputEnded
            if (strictClip) require(sourceDurationMs > 0 || completeSource) { "The complete audio duration could not be verified" }
            return DecodedChunk(decodedStartMs, decodedEndMs, sourceDurationMs.takeIf { it > 0 } ?: if (completeSource) decodedEndMs else 0, completeSource)
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally {
            // Each resource is released even if another platform cleanup call fails.
            try { writer?.close() } finally {
                try { if (started) codec?.stop() } finally {
                    try { codec?.release() } finally { extractor.release() }
                }
            }
        }
    }
}
