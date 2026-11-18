package dev.distillpod.core

enum class SetupModelStatus { MISSING, QUEUED, DOWNLOADING, PAUSED, FAILED, READY }

fun setupModelStatus(databaseState: String?, fileReady: Boolean): SetupModelStatus = when (databaseState) {
    "queued" -> SetupModelStatus.QUEUED
    "downloading" -> SetupModelStatus.DOWNLOADING
    "paused" -> SetupModelStatus.PAUSED
    "failed" -> SetupModelStatus.FAILED
    "ready" -> if (fileReady) SetupModelStatus.READY else SetupModelStatus.MISSING
    else -> SetupModelStatus.MISSING
}

data class SetupChecklist(
    val followedShow: Boolean,
    val modelStatus: SetupModelStatus,
    val chatGptConnected: Boolean,
    val savedMoment: Boolean,
    val googleBinding: Boolean,
    val backupConnected: Boolean,
) {
    val syncConnected: Boolean get() = googleBinding || backupConnected
    val takeawayReady: Boolean get() = chatGptConnected && modelStatus == SetupModelStatus.READY

    fun showInLibrary(setupDone: Boolean, cardDismissed: Boolean): Boolean =
        setupDone && !cardDismissed && (!followedShow || !savedMoment)
}

fun setupPlaybackStarted(playing: Boolean, livePositionMs: Long, hasSavedPosition: Boolean): Boolean =
    playing || livePositionMs > 0 || hasSavedPosition

sealed interface SetupMomentTarget {
    data object Player : SetupMomentTarget
    data class Episode(val id: String) : SetupMomentTarget
    data object Search : SetupMomentTarget
}

fun setupMomentTarget(loadedEpisodeId: String?, playableLibraryEpisodeId: String?): SetupMomentTarget = when {
    loadedEpisodeId != null -> SetupMomentTarget.Player
    playableLibraryEpisodeId != null -> SetupMomentTarget.Episode(playableLibraryEpisodeId)
    else -> SetupMomentTarget.Search
}
