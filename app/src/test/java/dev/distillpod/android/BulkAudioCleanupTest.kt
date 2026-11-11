package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BulkAudioCleanupTest {
    private suspend fun fixture(block: suspend (AppDatabase, MediaRepository) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val media = MediaRepository(context, db, AppSettings(context), ResumableDownloader(OkHttpClient()), ActiveFiles())
        media.directory.listFiles()?.forEach { it.deleteRecursively() }
        try { db.library().putPodcast(PodcastEntity("show", "https://example.test/feed", "Show")); block(db, media) }
        finally { db.close(); media.directory.listFiles()?.forEach { it.deleteRecursively() } }
    }
    private fun episode(id: String = "one", file: File? = null) = EpisodeEntity(id, "show", id, "Episode $id",
        "https://example.test/$id", localFile = file?.absolutePath,
        downloadState = if (file == null) "paused" else "downloaded", downloadedBytes = file?.length() ?: 3,
        localDownloadedAt = if (file == null) 0 else 123, mediaFingerprint = "original", positionMs = 4321,
        etag = "validator", lastModified = "modified")

    @Test fun previewThenClearPreservesTextProgressModelsAndCountsActualRepresentations() = runBlocking {
        fixture { db, media ->
            val audio = File(media.directory, "one-uuid.audio").apply { writeText("audio") }
            val partial = File(media.directory, "two.partial").apply { writeText("part") }
            val cached = File(media.directory, "orphan.audio").apply { writeText("cache") }
            val model = File(media.modelsDirectory, "bulk-model.bin").apply { writeText("keep") }
            val one = episode(file = audio); val two = episode("two")
            db.library().putEpisodes(listOf(one, two))
            db.library().putTranscript(TranscriptEntity(one.id, "revision", "original", "tiny", "en", state = "ready"))
            val transcript = db.library().transcript(one.id)
            val segment = SegmentEntity(one.id, "revision", 0, 1000, "Saved transcript text")
            val coverage = CoverageEntity(one.id, "revision", 0, 1000)
            val saved = DistillationEntity("saved", one.id, 0, 1000, insight = "Saved insight", state = "succeeded")
            val analysis = AnalysisEntity(one.id, "revision", "succeeded", "Saved analysis")
            val message = MessageEntity("message", one.id, "assistant", "Saved answer")
            val research = ResearchEntity("research", saved.id, "succeeded", "Saved research")
            val modelRow = ModelEntity("bulk-model", "ready", 4)
            val session = PlayerSessionEntity(episodeId = one.id, speed = 1.3f)
            db.library().putSegments(listOf(segment)); db.library().putCoverage(coverage)
            db.library().putDistillation(saved); db.library().putAnalysis(analysis)
            db.library().putMessage(message); db.library().putResearch(research)
            db.library().putModel(modelRow); db.library().putPlayerSession(session)
            val before = listOf(audio.readText(), partial.readText(), cached.readText())
            val preview = media.previewAudioCleanup()
            assertEquals(3, preview.totals.files); assertEquals(14L, preview.totals.bytes)
            assertEquals(1, preview.totals.downloaded.files); assertEquals(1, preview.totals.unfinished.files)
            assertEquals(before, listOf(audio.readText(), partial.readText(), cached.readText()))
            assertEquals(one, db.library().episode(one.id))
            val result = media.clearAudio(preview)
            assertEquals(preview.totals, result.removed); assertTrue(result.terminal)
            assertEquals(0, result.failedUnits); assertFalse(audio.exists()); assertFalse(partial.exists()); assertFalse(cached.exists())
            assertEquals(transcript, db.library().transcript(one.id)); assertEquals(4321L, db.library().episode(one.id)!!.positionMs)
            assertEquals(listOf(segment), db.library().segments(one.id, "revision"))
            assertEquals(listOf(coverage), db.library().coverage(one.id, "revision"))
            assertEquals(saved, db.library().distillation(saved.id)); assertEquals(analysis, db.library().analysis(one.id))
            assertEquals(listOf(message), db.library().messages()); assertEquals(research, db.library().research(research.id))
            assertEquals(modelRow, db.library().model(modelRow.id)); assertEquals(session, db.library().playerSession())
            assertEquals(one.copy(localFile = null, localDownloadedAt = 0, downloadState = "none", downloadedBytes = 0,
                totalBytes = 0, etag = null, lastModified = null, error = null), db.library().episode(one.id))
            assertEquals("keep", model.readText()); model.delete()
        }
    }

    @Test fun oldTicketCannotDeleteReplacementOrNewCompanionAndProgressOnlyChangesAreAllowed() = runBlocking {
        fixture { db, media ->
            val old = File(media.directory, "old.audio").apply { writeText("old") }
            val row = episode(file = old); db.library().putEpisode(row)
            val preview = media.previewAudioCleanup()
            val partial = File(media.directory, "one.partial").apply { writeText("new partial") }
            val result = media.clearAudio(preview)
            assertEquals(1, result.changedUnits); assertTrue(old.isFile); assertTrue(partial.isFile)
            partial.delete()
            val replacementPreview = media.previewAudioCleanup()
            val newer = File(media.directory, "new-uuid.audio").apply { writeText("new") }
            db.library().downloadComplete(row.id, newer.path, "new", 3, 456)
            assertEquals(1, media.clearAudio(replacementPreview).changedUnits)
            assertTrue(newer.isFile); assertEquals(newer.path, db.library().episode(row.id)!!.localFile)
            val finalPreview = media.previewAudioCleanup()
            db.library().progress(row.id, 9876, 0, false, 100)
            media.clearAudio(finalPreview)
            assertFalse(newer.exists()); assertEquals(9876L, db.library().episode(row.id)!!.positionMs)
        }
    }

    @Test fun sharedCanonicalFileCountsOnceAndSecondaryQueueOrAutomationProtectsWholeUnit() = runBlocking {
        fixture { db, media ->
            val file = File(media.directory, "shared.audio").apply { writeText("shared") }
            val first = episode(file = file)
            val second = episode("two", File(media.directory, "../media/shared.audio"))
            db.library().putEpisodes(listOf(first, second))
            val preview = media.previewAudioCleanup(); assertEquals(1, preview.totals.files)
            db.library().insertQueue(listOf(QueueEntryEntity(second.id, 0)))
            assertEquals(1, media.clearAudio(preview).protectedUnits); assertTrue(file.isFile)
            db.library().clearQueue()
            db.automation().putShow(ShowAutomationEntity("show", 1, true))
            db.automation().putJob(EpisodeAutomationEntity(second.id, "show", "waiting_auth"))
            val protected = media.previewAudioCleanup(); assertFalse(protected.canApply)
            db.automation().putJob(EpisodeAutomationEntity(second.id, "show", "ready"))
            assertEquals(1, media.clearAudio(media.previewAudioCleanup()).removed.files)
            assertNull(db.library().episode(first.id)!!.localFile); assertNull(db.library().episode(second.id)!!.localFile)
        }
    }

    @Test fun orphanAdoptionAndNewOrphansDoNotExpandOldConfirmation() = runBlocking {
        fixture { db, media ->
            val file = File(media.directory, "orphan.audio").apply { writeText("old") }
            val preview = media.previewAudioCleanup()
            val fresh = File(media.directory, "new-orphan.audio").apply { writeText("new") }
            db.library().putEpisode(episode(file = file))
            assertEquals(1, media.clearAudio(preview).changedUnits)
            assertTrue(file.isFile); assertTrue(fresh.isFile)
        }
    }

    @Test fun missingFilesReconcileStatusButOutsideFilesAndDirectoriesStayUntouched() = runBlocking {
        fixture { db, media ->
            val missing = episode(file = File(media.directory, "missing.audio"))
            db.library().putEpisode(missing)
            val directory = File(media.directory, "directory.partial").apply { mkdir() }
            File(directory, "keep").writeText("keep")
            val outside = File(media.directory.parentFile, "outside.audio").apply { writeText("keep") }
            try {
                db.library().putEpisode(episode("outside", outside))
                val result = media.clearAudio(media.previewAudioCleanup())
                assertEquals(0, result.removed.files); assertEquals(1, result.reconciledEntries)
                assertNull(db.library().episode(missing.id)!!.localFile)
                assertTrue(directory.isDirectory); assertEquals("keep", outside.readText())
                assertTrue(result.unavailableUnits >= 1)
            } finally { outside.delete() }
        }
    }

    @Test fun ticketsAreRepositoryBoundSingleUseAndCancellationKeepsLatestCounts() = runBlocking {
        fixture { db, media ->
            File(media.directory, "a.audio").writeText("one")
            File(media.directory, "b.audio").writeText("two")
            val preview = media.previewAudioCleanup()
            val other = MediaRepository(ApplicationProvider.getApplicationContext(), db,
                AppSettings(ApplicationProvider.getApplicationContext()), ResumableDownloader(OkHttpClient()), ActiveFiles())
            assertTrue(runCatching { other.clearAudio(preview) }.exceptionOrNull() is IllegalArgumentException)
            val job = launch {
                val ownJob = currentCoroutineContext()[Job]!!
                media.clearAudio(preview) { if (it.removed.files == 1) ownJob.cancel() }
            }
            job.join()
            assertTrue(job.isCancelled); assertEquals(1, preview.latestResult.removed.files)
            assertTrue(preview.latestResult.cancelled); assertTrue(preview.latestResult.terminal)
            assertTrue(runCatching { media.clearAudio(preview) }.exceptionOrNull() is IllegalStateException)
        }
    }

    @Test fun resumedSameSizePartialAndResolutionFailureInvalidateEarlierTicket() = runBlocking {
        fixture { db, media ->
            MockWebServer().use { server ->
                val partial = File(media.directory, "one.partial").apply { writeText("old") }
                db.library().putEpisode(episode().copy(audioUrl = server.url("/audio").toString()))
                val preview = media.previewAudioCleanup()
                server.enqueue(MockResponse().setResponseCode(500))
                assertTrue(runCatching { media.ensureDownloaded("one") }.isFailure)
                assertEquals(1, media.clearAudio(preview).changedUnits)
                assertEquals("old", partial.readText())
            }
        }
    }

    @Test fun partialSuccessSurvivesLaterFileFailureAndFreshReviewDoesNotDoubleCount() = runBlocking {
        fixture { db, media ->
            val audio = File(media.directory, "one.audio").apply { writeText("audio") }
            val partial = File(media.directory, "one.partial").apply { writeText("part") }
            val row = episode(file = audio); db.library().putEpisode(row)
            media.cleanupFiles = object : AudioCleanupFiles by AudioCleanupFiles.SystemFiles {
                override fun delete(file: File) = if (file.name == audio.name) false else file.delete()
            }
            val failed = media.clearAudio(media.previewAudioCleanup())
            assertEquals(1, failed.failedUnits); assertEquals(1, failed.removed.unfinished.files)
            assertEquals(4L, failed.removed.bytes); assertFalse(partial.exists()); assertTrue(audio.exists())
            assertEquals(row, db.library().episode(row.id))
            assertEquals(1, failed.errors.size)
            media.cleanupFiles = AudioCleanupFiles.SystemFiles
            val retried = media.clearAudio(media.previewAudioCleanup())
            assertEquals(1, retried.removed.downloaded.files); assertEquals(5L, retried.removed.bytes)
            assertEquals(0, retried.removed.unfinished.files)
        }
    }

    @Test fun roomFailureKeepsPhysicalLedgerAndMissingStatusCanBeReconciledWithFreshTicket() = runBlocking {
        fixture { db, media ->
            val audio = File(media.directory, "one.audio").apply { writeText("audio") }
            val row = episode(file = audio); db.library().putEpisode(row)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_cleanup BEFORE UPDATE OF localFile ON episodes BEGIN SELECT RAISE(ABORT,'Synthetic cleanup failure'); END")
            val failed = media.clearAudio(media.previewAudioCleanup())
            assertEquals(1, failed.failedUnits); assertEquals(1, failed.removed.files); assertEquals(5L, failed.removed.bytes)
            assertEquals(0, failed.reconciledEntries); assertFalse(audio.exists())
            assertEquals(row, db.library().episode(row.id))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_cleanup")
            val retried = media.clearAudio(media.previewAudioCleanup())
            assertEquals(0, retried.removed.files); assertEquals(1, retried.reconciledEntries)
            assertEquals(0, retried.failedUnits); assertNull(db.library().episode(row.id)!!.localFile)
        }
    }

    @Test fun failureReportingDoesNotLaterClearAReplacementAndErrorsAreBounded() = runBlocking {
        fixture { db, media ->
            val audio = File(media.directory, "one.audio").apply { writeText("audio") }
            db.library().putEpisode(episode(file = audio))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_cleanup BEFORE UPDATE OF localFile ON episodes BEGIN SELECT RAISE(ABORT,'Synthetic cleanup failure'); END")
            val replacement = File(media.directory, "replacement.audio")
            var replaced = false
            val result = media.clearAudio(media.previewAudioCleanup()) { value ->
                if (value.failedUnits == 1 && !replaced) {
                    replaced = true
                    db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_cleanup")
                    replacement.writeText("new")
                    runBlocking { db.library().downloadComplete("one", replacement.path, "replacement", 3, 999) }
                }
            }
            assertEquals(1, result.removed.files); assertTrue(replacement.isFile)
            assertEquals(replacement.path, db.library().episode("one")!!.localFile)
            media.cleanupFiles = object : AudioCleanupFiles by AudioCleanupFiles.SystemFiles {
                override fun delete(file: File): Boolean = throw IOException("Synthetic file failure")
            }
            repeat(10) { File(media.directory, "failed-$it.audio").writeText("keep") }
            val failures = media.clearAudio(media.previewAudioCleanup())
            assertEquals(11, failures.failedUnits); assertEquals(8, failures.errors.size)
            assertEquals(0, failures.removed.files)
        }
    }

    @Test fun heldRealWriterMakesReviewUnstableAndCancellingLockWaitLeavesDownloadAlone() = runBlocking {
        fixture { db, media ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
                val partial = File(media.directory, "one.partial").apply { writeText("old") }
                val orphan = File(media.directory, "orphan.audio").apply { writeText("cache") }
                db.library().putEpisode(episode().copy(audioUrl = server.url("/audio").toString()))
                val before = media.previewAudioCleanup()
                val transfer = async(Dispatchers.IO) { runCatching { media.ensureDownloaded("one") } }
                try {
                    assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
                    assertTrue(media.active.audioOwnership.busy(partial.canonicalPath))
                    val during = media.previewAudioCleanup()
                    assertEquals(1, during.protectedUnits)
                    assertEquals(1, during.rows.filter { it.status == AudioCleanupStatus.ELIGIBLE }.sumOf { it.files })
                    val cleanup = launch { media.clearAudio(before) }
                    delay(100); assertTrue(cleanup.isActive)
                    cleanup.cancelAndJoin()
                    assertTrue(before.latestResult.cancelled); assertEquals(0, before.latestResult.removed.files)
                    assertTrue(transfer.isActive); assertTrue(orphan.isFile); assertTrue(partial.isFile)
                } finally { transfer.cancelAndJoin() }
                assertFalse(media.active.audioOwnership.busy(partial.canonicalPath))
            }
        }
    }

    @Test fun resetToSamePartialSizeAndSameValidatorsStillChangesGeneration() = runBlocking {
        fixture { db, media ->
            MockWebServer().use { server ->
                val partial = File(media.directory, "one.partial").apply { writeText("old") }
                val row = episode().copy(audioUrl = server.url("/audio").toString())
                db.library().putEpisode(row)
                val before = media.previewAudioCleanup()
                server.enqueue(MockResponse().setBody("new").setHeader("Content-Length", "6")
                    .setHeader("ETag", row.etag!!).setHeader("Last-Modified", row.lastModified!!)
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END))
                assertTrue(runCatching { media.ensureDownloaded(row.id) }.isFailure)
                assertEquals(3L, partial.length()); assertEquals("new", partial.readText())
                assertEquals(row.etag, db.library().episode(row.id)!!.etag)
                assertEquals(1, media.clearAudio(before).changedUnits); assertTrue(partial.isFile)
            }
        }
    }

    @Test fun resolverFailureBeforeAnyBytesStillRevokesThePartialReview() = runBlocking {
        fixture { db, _ ->
            val expected = IOException("Synthetic resolver failure")
            val context = ApplicationProvider.getApplicationContext<Context>()
            val media = MediaRepository(context, db, AppSettings(context), ResumableDownloader(OkHttpClient()), ActiveFiles(),
                resolveAudio = { throw expected })
            val partial = File(media.directory, "one.partial").apply { writeText("old") }
            db.library().putEpisode(episode())
            val before = media.previewAudioCleanup()
            val failure = runCatching { media.ensureDownloaded("one") }.exceptionOrNull()
            // Coroutine stack recovery may copy an IOException across the existing IO boundary;
            // its original failure must remain in the causal chain, without a new domain wrapper.
            assertTrue(generateSequence(failure) { it.cause }.any { it === expected })
            assertEquals("old", partial.readText()); assertEquals(1, media.clearAudio(before).changedUnits)
        }
    }

    @Test fun protectedReviewNeverEscalatesAfterReleaseAndAbsentGuardsAreNotDisplayFiles() = runBlocking {
        fixture { db, media ->
            val audio = File(media.directory, "one.audio").apply { writeText("audio") }
            val row = episode(file = audio); db.library().putEpisode(row)
            db.library().insertQueue(listOf(QueueEntryEntity(row.id, 0)))
            val before = media.previewAudioCleanup()
            assertFalse(before.canApply); assertEquals(1, before.protectedUnits)
            assertTrue("Absent partials are guards, not extra reviewed content", before.rows.none { it.kind == AudioCleanupKind.UNFINISHED })
            db.library().clearQueue()
            assertEquals(0, media.clearAudio(before).removed.files); assertTrue(audio.isFile)
            assertEquals(1, media.clearAudio(media.previewAudioCleanup()).removed.files)
        }
    }

    @Test fun newSharedReferenceAndNewAutomaticIntentAreKeptAndReadyDisabledIntentIsEligible() = runBlocking {
        fixture { db, media ->
            val audio = File(media.directory, "one.audio").apply { writeText("audio") }
            val first = episode(file = audio); db.library().putEpisode(first)
            val before = media.previewAudioCleanup()
            val second = episode("two", audio); db.library().putEpisode(second)
            assertEquals(1, media.clearAudio(before).changedUnits); assertTrue(audio.isFile)
            val automatic = media.previewAudioCleanup()
            db.automation().putShow(ShowAutomationEntity("show", 1, true))
            val job = EpisodeAutomationEntity(second.id, "show", "waiting_auth")
            db.automation().putJob(job)
            assertEquals(1, media.clearAudio(automatic).protectedUnits)
            val podcast = db.library().podcast("show")!!
            db.library().putPodcast(podcast.copy(subscribed = false))
            assertEquals(1, media.clearAudio(media.previewAudioCleanup()).removed.files)
            assertEquals(job, db.automation().jobs().single())
        }
    }

    @Test fun leafSymlinksAreNeverFollowedOrDeletedAndFakeTicketsCannotExecute() = runBlocking {
        fixture { _, media ->
            val outside = File(media.directory.parentFile, "bulk-symlink-target").apply { writeText("keep") }
            val link = File(media.directory, "link.audio")
            try {
                Files.createSymbolicLink(link.toPath(), outside.toPath())
                val preview = media.previewAudioCleanup()
                assertFalse(preview.canApply)
                assertEquals(0, media.clearAudio(preview).removed.files)
                assertTrue(Files.isSymbolicLink(link.toPath())); assertEquals("keep", outside.readText())
                val fake = AudioCleanupPreview(AudioCleanupTotals(), emptyList(), 1)
                assertTrue(runCatching { media.clearAudio(fake) }.exceptionOrNull() is IllegalArgumentException)
            } finally { link.delete(); outside.delete() }
        }
    }

    @Test fun disabledOrUninitializedAutomationDoesNotAcquireDeletionPolicy() = runBlocking {
        fixture { db, media ->
            val audio = File(media.directory, "one.audio").apply { writeText("audio") }
            db.library().putEpisode(episode(file = audio))
            val job = EpisodeAutomationEntity("one", "show", "waiting_auth")
            db.automation().putJob(job)
            db.automation().putShow(ShowAutomationEntity("show", 1, false))
            assertEquals(1, media.clearAudio(media.previewAudioCleanup()).removed.files)
            audio.writeText("audio")
            db.library().downloadComplete("one", audio.path, "original", 5, 123)
            db.openHelper.writableDatabase.execSQL("DELETE FROM show_automation WHERE podcastId='show'")
            assertEquals(1, media.clearAudio(media.previewAudioCleanup()).removed.files)
            assertEquals(job, db.automation().jobs().single())
        }
    }

    @Test fun cancelledPreviewLeavesNoOwnershipOrContentSideEffects() = runBlocking {
        fixture { db, media ->
            val audio = File(media.directory, "one.audio").apply { writeText("audio") }
            val row = episode(file = audio); db.library().putEpisode(row)
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val first = AtomicBoolean(true)
            media.cleanupFiles = object : AudioCleanupFiles by AudioCleanupFiles.SystemFiles {
                override fun kind(file: File): AudioCleanupPathKind {
                    if (file.name == audio.name && first.compareAndSet(true, false)) {
                        entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS))
                    }
                    return AudioCleanupFiles.SystemFiles.kind(file)
                }
            }
            val review = async(Dispatchers.IO) { media.previewAudioCleanup() }
            try {
                withTimeout(5000) { entered.await() }
                review.cancel(); release.countDown(); review.join()
                assertTrue(review.isCancelled)
                assertFalse(media.active.audioOwnership.busy(audio.canonicalPath))
                assertEquals(row, db.library().episode(row.id)); assertEquals("audio", audio.readText())
            } finally { release.countDown(); review.cancelAndJoin() }
        }
    }

    @Test fun unresolvedCanonicalOwnershipReportsTheOriginalPreviewFailure() = runBlocking {
        fixture { db, media ->
            val row = episode().copy(localFile = media.directory.path + "/invalid\u0000.audio", downloadState = "downloaded")
            db.library().putEpisode(row)
            val failure = runCatching { media.previewAudioCleanup() }.exceptionOrNull()
            assertTrue("An incomplete ownership index must report its canonical IO failure", failure is IOException)
            assertEquals(row, db.library().episode(row.id))
        }
    }
}
