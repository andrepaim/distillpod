package dev.distillpod.core

enum class FirstRunStatus { PENDING, DONE }

data class FirstRunSetup(val status: FirstRunStatus, val cardDismissed: Boolean) {
    companion object {
        val ExistingInstall = FirstRunSetup(FirstRunStatus.DONE, cardDismissed = true)
    }
}

data class FirstRunEvidence(
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val hasFollowedShows: Boolean = false,
    val hasEpisodes: Boolean = false,
    val hasSavedMoments: Boolean = false,
    val chatGptConnected: Boolean = false,
    val hasSyncBinding: Boolean = false,
    val hasBackupFile: Boolean = false,
) {
    val neverUpdated: Boolean get() = firstInstallTime > 0 && firstInstallTime == lastUpdateTime
}

fun firstRunSetup(evidence: FirstRunEvidence): FirstRunSetup = with(evidence) {
    if (neverUpdated && !hasFollowedShows && !hasEpisodes && !hasSavedMoments &&
        !chatGptConnected && !hasSyncBinding && !hasBackupFile
    ) FirstRunSetup(FirstRunStatus.PENDING, cardDismissed = false)
    else FirstRunSetup.ExistingInstall
}
