package dev.distillpod.core

/** Local cleanup eligibility only. Callers must separately confirm policies and recheck protection.
 * Null means Off. Origins are explicit local timestamps, never publication/last-played estimates.
 * A missing/invalid/future origin is ineligible; callers establish migration grace separately.
 */
data class RetentionPolicy(
    val audioDays: Int? = null,
    val completedAudioDays: Int? = null,
    val transcriptDays: Int? = null,
) {
    init {
        require(listOf(audioDays, completedAudioDays, transcriptDays).all {
            it == null || it in setOf(1, 7, 30, 90)
        }) { "Choose Off, 1, 7, 30 or 90 days" }
    }

    /** Either enabled audio rule can expire a copy, including never-played downloads. */
    fun audioExpired(downloadedAt: Long?, playbackEndedAt: Long?, now: Long): Boolean =
        expired(downloadedAt, audioDays, now) || expired(playbackEndedAt, completedAudioDays, now)

    fun transcriptExpired(revisionCreatedAt: Long?, now: Long): Boolean =
        expired(revisionCreatedAt, transcriptDays, now)

    private fun expired(origin: Long?, days: Int?, now: Long): Boolean {
        if (days == null || origin == null || origin <= 0 || now < origin) return false
        // Subtract only non-negative ordered timestamps; adding a deadline could overflow.
        return now - origin >= days * 86_400_000L
    }
}
