package dev.distillpod.android.features

/** Presentation only: never changes account scope or treats a provider write as a cloud upload. */
internal object DataProtectionGuidance {
    const val preferences = "local transcription model selection, transcript language and AI model selection"
    const val excluded = "Neither transfers audio downloads, model binaries or account secrets. Set up sound, headphone shortcuts, automatic preparation, network/charging policy, storage and voice rewind again on each phone."
    const val backup = "File backup is a manually updated, independent copy of library text, transcripts, finished analyses, chats and research, saved moments, progress and queue. Its only preferences are " + preferences + ". It complements sync."
    const val binding = "Connecting binds this library to its original Google account. Sign-out pauses sync but keeps that binding and local data; it does not delete cloud records or let this library switch accounts."
    const val expanded = "Queue and preference sync is an account-wide upgrade. There is no separate off switch afterward: Pause sync stops all sync on this phone, without deleting cloud records or undoing the upgrade."

    fun sync(configured: Boolean, contentConfigured: Boolean, expandedScope: Boolean): String {
        if (!configured) return "Library sync is unavailable in this build. Use a backup file to transfer your library."
        val base = "Library sync uses Google sign-in and Firebase, not a Drive backup file. It automatically syncs approved public subscriptions, listening progress, completion and saved moments."
        val scope = if (expandedScope) " Queue from shared sources and these preferences are included: " else " Queue from shared sources and these preferences are optional: "
        val text = when {
            contentConfigured && expandedScope -> " Eligible finished transcripts and their completed analyses, chats and research can also sync for approved public sources. Other text still needs file backup."
            contentConfigured -> " This build also supports eligible finished transcript content after the account-wide upgrade. Until then, use file backup for text."
            else -> " Transcripts and other finished text do not sync in this build; use file backup to recover them."
        }
        return base + scope + preferences + "." + text
    }

    fun backupAge(at: Long?, now: Long): String {
        if (at == null) return "No backup history loaded."
        if (at <= 0) return "Backup date unavailable."
        if (at > now) return "Backup date is ahead of this phone's clock."
        val minutes = (now - at) / 60_000
        if (minutes == 0L) return "Latest available backup: less than a minute old."
        val (count, unit) = when {
            minutes >= 1_440 -> minutes / 1_440 to "day"
            minutes >= 60 -> minutes / 60 to "hour"
            else -> minutes to "minute"
        }
        return "Latest available backup: $count $unit" + (if (count == 1L) "" else "s") + " old."
    }
}
