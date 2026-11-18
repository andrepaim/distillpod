package dev.distillpod.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupChecklistTest {
    private val empty = SetupChecklist(
        followedShow = false,
        modelStatus = SetupModelStatus.MISSING,
        chatGptConnected = false,
        savedMoment = false,
        googleBinding = false,
        backupConnected = false,
    )

    @Test fun `only an incomplete listening action keeps the Library card visible`() {
        assertTrue(empty.showInLibrary(setupDone = true, cardDismissed = false))
        assertTrue(empty.copy(followedShow = true).showInLibrary(setupDone = true, cardDismissed = false))
        assertTrue(empty.copy(savedMoment = true).showInLibrary(setupDone = true, cardDismissed = false))
        assertFalse(empty.copy(followedShow = true, savedMoment = true).showInLibrary(setupDone = true, cardDismissed = false))
        assertFalse(empty.showInLibrary(setupDone = false, cardDismissed = false))
        assertFalse(empty.showInLibrary(setupDone = true, cardDismissed = true))
    }

    @Test fun `ChatGPT and model readiness stay independent during a download`() {
        val connected = empty.copy(modelStatus = SetupModelStatus.DOWNLOADING, chatGptConnected = true)
        assertTrue(connected.chatGptConnected)
        assertFalse(connected.takeawayReady)
        assertTrue(connected.copy(modelStatus = SetupModelStatus.READY).takeawayReady)

        val onDeviceOnly = empty.copy(modelStatus = SetupModelStatus.READY)
        assertFalse(onDeviceOnly.chatGptConnected)
        assertFalse(onDeviceOnly.takeawayReady)
    }

    @Test fun `the remembered Google binding or a connected file satisfies optional sync`() {
        assertFalse(empty.syncConnected)
        assertTrue(empty.copy(googleBinding = true).syncConnected)
        assertTrue(empty.copy(backupConnected = true).syncConnected)
    }

    @Test fun `only a ready row with a model file is usable`() {
        assertEquals(SetupModelStatus.MISSING, setupModelStatus("ready", fileReady = false))
        assertEquals(SetupModelStatus.READY, setupModelStatus("ready", fileReady = true))
        assertEquals(SetupModelStatus.MISSING, setupModelStatus(null, fileReady = true))
        assertEquals(SetupModelStatus.QUEUED, setupModelStatus("queued", fileReady = false))
        assertEquals(SetupModelStatus.DOWNLOADING, setupModelStatus("downloading", fileReady = false))
        assertEquals(SetupModelStatus.PAUSED, setupModelStatus("paused", fileReady = false))
        assertEquals(SetupModelStatus.FAILED, setupModelStatus("failed", fileReady = false))
    }

    @Test fun `active playback or live or saved progress establishes listening`() {
        assertFalse(setupPlaybackStarted(playing = false, livePositionMs = 0, hasSavedPosition = false))
        assertTrue(setupPlaybackStarted(playing = true, livePositionMs = 0, hasSavedPosition = false))
        assertTrue(setupPlaybackStarted(playing = false, livePositionMs = 100, hasSavedPosition = false))
        assertTrue(setupPlaybackStarted(playing = false, livePositionMs = 0, hasSavedPosition = true))
    }

    @Test fun `save moment opens the player then a playable episode then Search`() {
        assertEquals(SetupMomentTarget.Player, setupMomentTarget("loaded", "available"))
        assertEquals(SetupMomentTarget.Episode("available"), setupMomentTarget(null, "available"))
        assertEquals(SetupMomentTarget.Search, setupMomentTarget(null, null))
    }
}
