package dev.distillpod.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstRunSetupTest {
    private val newInstall = FirstRunEvidence(firstInstallTime = 1000, lastUpdateTime = 1000)

    @Test fun emptyNeverUpdatedInstallStartsPendingWithCardAvailable() {
        assertEquals(FirstRunSetup(FirstRunStatus.PENDING, cardDismissed = false), firstRunSetup(newInstall))
    }

    @Test fun oneExistingConnectionOrLibraryRecordSuppressesSetupAndCard() {
        val evidence = listOf(
            newInstall.copy(hasFollowedShows = true),
            newInstall.copy(hasEpisodes = true),
            newInstall.copy(hasSavedMoments = true),
            newInstall.copy(chatGptConnected = true),
            newInstall.copy(hasSyncBinding = true),
            newInstall.copy(hasBackupFile = true),
        )
        evidence.forEach {
            assertEquals(it.toString(), FirstRunSetup(FirstRunStatus.DONE, cardDismissed = true), firstRunSetup(it))
        }
    }

    @Test fun anEmptyUpdatedInstallOrUnknownInstallTimeSuppressesSetupAndCard() {
        val evidence = listOf(
            newInstall.copy(lastUpdateTime = 2000),
            newInstall.copy(firstInstallTime = 0, lastUpdateTime = 0),
            newInstall.copy(firstInstallTime = 2000),
        )
        evidence.forEach {
            assertEquals(it.toString(), FirstRunSetup(FirstRunStatus.DONE, cardDismissed = true), firstRunSetup(it))
        }
    }
}
