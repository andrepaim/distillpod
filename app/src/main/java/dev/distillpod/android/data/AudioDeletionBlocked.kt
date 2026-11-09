package dev.distillpod.android.data

/** Expected refusal, separate from invalid data and file-system failures. No paths or IDs. */
enum class AudioDeletionBlockReason(val feedback: String) {
    ACTIVE("Stop playback or processing before deleting this audio."),
    QUEUED("Remove this episode from the playback queue before deleting its audio."),
    TRANSCRIBING("Pause transcript preparation before deleting this audio."),
    DOWNLOADING("Pause the download before deleting this audio."),
}

class AudioDeletionBlocked(val reason: AudioDeletionBlockReason) : Exception(reason.feedback)

internal fun audioDeletionBlockReason(
    active: Boolean,
    queued: Boolean,
    transcribing: Boolean,
    downloading: Boolean,
): AudioDeletionBlockReason? = when {
    queued -> AudioDeletionBlockReason.QUEUED
    transcribing -> AudioDeletionBlockReason.TRANSCRIBING
    downloading -> AudioDeletionBlockReason.DOWNLOADING
    active -> AudioDeletionBlockReason.ACTIVE
    else -> null
}
