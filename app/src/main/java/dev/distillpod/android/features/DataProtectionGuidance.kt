package dev.distillpod.android.features

import dev.distillpod.android.data.ManagedBackups

/** Presentation only: never changes account scope or treats a provider write as a cloud upload. */
internal object DataProtectionGuidance {
    const val summary = "Sync keeps subscriptions, progress and saved moments current across phones. Automatic backup keeps a daily copy of everything else, like transcripts, insights, chats and posts, in a file you choose."
    const val preferences = "local transcription model selection, transcript language and AI model selection"
    const val excluded = "Neither transfers audio downloads, model binaries or account secrets. Set up sound, headphone shortcuts, automatic preparation, network/charging policy, storage and voice rewind again on each phone."
    const val backup = "Automatic backup adds a dated copy to your backup file once a day, when the library has changed and the phone is online. " +
        "It holds library text, transcripts, finished analyses, chats and research, posts, saved moments, progress and queue. Its only preferences are " + preferences + ". " +
        "The file keeps the five newest backups plus one from each earlier week, up to ten. Your file provider, such as Google Drive, handles the cloud upload."
    const val binding = "Connecting binds this library to its original Google account. Sign-out pauses sync but keeps that binding and local data; it does not delete cloud records or let this library switch accounts."
    const val expanded = "Queue and preference sync is an account-wide upgrade. There is no separate off switch afterward: Pause sync stops all sync on this phone, without deleting cloud records or undoing the upgrade."
    const val newPhone = "Your library works offline. On a new phone, sign in to sync with the same Google account, then use Restore from a backup to bring back transcripts and other text."
    /** A build without the sync switch (the Play preset) has backup as the only copy. */
    const val excludedBackupOnly = "Backup doesn't hold audio downloads, model binaries or account secrets. Set up sound, headphone shortcuts, automatic preparation, network/charging policy, storage and voice rewind again on each phone."
    const val newPhoneBackupOnly = "Your library works offline. On a new phone, use Restore from a backup to bring back your library."

    /**
     * The complete "What's covered" sheet. A build with sync shows every disclosure, configured or not; a build
     * without the sync switch (the Play preset) omits the account binding and sign-in text, since it has neither.
     */
    fun coverage(configured: Boolean, contentConfigured: Boolean, expandedScope: Boolean,
        syncBuild: Boolean = dev.distillpod.android.sync.SyncBuild.ENABLED): List<String> =
        if (syncBuild) listOf(sync(configured, contentConfigured, expandedScope), backup, excluded, newPhone, binding)
        else listOf(sync(false, false, false), backup, excludedBackupOnly, newPhoneBackupOnly)

    fun sync(configured: Boolean, contentConfigured: Boolean, expandedScope: Boolean): String {
        if (!configured) return "Cloud sync is unavailable in this build. Automatic backup still keeps your whole library in a file you choose."
        val base = "Sync uses Google sign-in and Firebase. It automatically syncs approved public subscriptions, listening progress, completion and saved moments."
        val scope = if (expandedScope) " Queue from shared sources and these preferences are included: " else " Queue from shared sources and these preferences are optional: "
        val text = when {
            contentConfigured && expandedScope -> " Eligible finished transcripts and their completed analyses, chats and research can also sync for approved public sources. Automatic backup keeps the rest."
            contentConfigured -> " This build also supports eligible finished transcript content after the account-wide upgrade. Until then, automatic backup keeps that text."
            else -> " Transcripts and other finished text do not sync in this build; automatic backup keeps them."
        }
        return base + scope + preferences + "." + text
    }

    /** "just now", "5 min ago", "3 h ago", "2 d ago"; null for a missing or future time. */
    fun ago(at: Long, now: Long): String? {
        if (at <= 0 || at > now) return null
        val minutes = (now - at) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 1_440 -> "${minutes / 60} h ago"
            else -> "${minutes / 1_440} d ago"
        }
    }

    /** The automatic backup's state in a few words, for the Sync page and the Settings row. */
    fun backupLine(connected: Boolean, status: ManagedBackups.Status, now: Long): String = when {
        !connected -> "Off"
        status.error.isNotBlank() -> "Last backup failed"
        else -> ago(status.savedAt, now)?.let { "Backed up $it" } ?: "On"
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
