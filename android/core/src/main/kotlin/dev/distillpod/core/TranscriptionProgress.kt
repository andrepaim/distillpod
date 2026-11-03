package dev.distillpod.core

/** Live work is separate from saved coverage, including overlap and partially saved retries. */
fun transcribingAudioMs(savedMs: Long, savedInChunkMs: Long, chunkStartMs: Long, chunkEndMs: Long,
    clipStartMs: Long, clipEndMs: Long, percent: Int): Long {
    val position = clipStartMs + (clipEndMs - clipStartMs).coerceAtLeast(0) * percent.coerceIn(0, 100) / 100
    val worked = (position - chunkStartMs).coerceIn(0, (chunkEndMs - chunkStartMs).coerceAtLeast(0))
    return savedMs + (worked - savedInChunkMs).coerceAtLeast(0)
}
