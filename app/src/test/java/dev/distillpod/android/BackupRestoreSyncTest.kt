package dev.distillpod.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.*
import dev.distillpod.core.sync.SyncOwner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records a backup restore adds reach the sync outbox like any other local change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreSyncTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    private val show = PodcastEntity("show", "https://example.test/feed", "A show")
    private val moment = DistillationEntity("moment", "episode", 0, 10000, "A useful source excerpt.", "A useful source excerpt.",
        "The speaker explains a useful idea.", "rev", "sha", "succeeded")

    private class Fixture(val source: AppDatabase, val target: AppDatabase, val cloud: SubscriptionCoordinatorTest.Cloud) {
        var kicks = 0
        val backup = LibraryBackup(target, portable = PortableLibrarySync(target) { kicks++ })
        suspend fun restore() = backup.merge(backup.preview(LibraryBackup(source).export().inputStream()))
        suspend fun keys() = target.sync().operations("alice", "g1").map { it.entityKey }.toSet()
        suspend fun run() = SubscriptionSyncCoordinator(target, cloud).run()
    }
    private fun fixture(approved: Boolean = true, block: suspend Fixture.() -> Unit) = runBlocking {
        val source = database(); val target = database()
        try {
            source.library().putPodcast(show)
            source.library().putEpisode(EpisodeEntity("episode", "show", "guid", "Episode", "https://example.test/audio?token=secret",
                durationMs = 60000, positionMs = 23000, completed = true, lastPlayedAt = 5, mediaFingerprint = "sha"))
            source.library().putDistillation(moment)
            SubscriptionSyncStore(target).bind(SyncOwner("alice", "g1"))
            target.library().putPodcast(show)
            if (approved) target.sync().approve(SyncPublicFeedEntity("show"))
            Fixture(source, target, SubscriptionCoordinatorTest.Cloud()).block()
        } finally { source.close(); target.close() }
    }

    @Test fun `restored progress, completion and moments are queued once and reach the cloud`() = fixture {
        assertTrue(restore() > 0)
        assertEquals(setOf("progress_episode", "completion_episode", "moment_moment"), keys())
        assertEquals(1, kicks)
        assertTrue(target.sync().operations("alice", "g1").none { "secret" in it.payload })
        assertTrue(run())
        assertEquals("23000", cloud.rows["progress_episode"]!!.payload["positionMs"])
        assertEquals("true", cloud.rows["completion_episode"]!!.payload["completed"])
        assertEquals("The speaker explains a useful idea.", cloud.rows["moment_moment"]!!.payload["insight"])
        // Restoring the same file again inserts nothing and queues nothing.
        val commits = cloud.commits
        assertEquals(0, restore())
        assertTrue(keys().isEmpty()); assertEquals(1, kicks)
        assertTrue(run()); assertEquals(commits, cloud.commits)
    }
    @Test fun `existing local records win and are not sent again`() = fixture {
        target.library().putEpisode(EpisodeEntity("local", "show", "guid", "Episode", "https://example.test/audio", positionMs = 40000, mediaFingerprint = "sha"))
        source.library().putDistillation(moment.copy(id = "other", episodeId = "episode"))
        target.library().putDistillation(moment.copy(id = "other", episodeId = "local"))
        restore()
        // Only the moment this phone did not have is new; the existing episode keeps its own progress.
        assertEquals(setOf("moment_moment"), keys())
        assertEquals(40000L, target.library().episode("local")!!.positionMs)
        assertEquals("local", SubscriptionSyncStore.decode(target.sync().operations("alice", "g1").single().payload)["episodeId"])
    }
    @Test fun `a restored moment deleted in the cloud is restored there without a conflict`() = fixture {
        target.library().putEpisode(EpisodeEntity("episode", "show", "guid", "Episode", "https://example.test/audio", mediaFingerprint = "sha"))
        val sync = PortableLibrarySync(target)
        sync.putMoment(moment); assertTrue(run())
        sync.deleteMoment("moment"); assertTrue(run())
        assertTrue(cloud.rows["moment_moment"]!!.deleted)
        restore()
        assertEquals(setOf("moment_moment"), keys())
        assertTrue(run())
        assertFalse(cloud.rows["moment_moment"]!!.deleted); assertEquals(3L, cloud.rows["moment_moment"]!!.version)
        assertNotNull(target.library().distillation("moment"))
    }
    @Test fun `a newer restored record replaces a rejected change of the same record`() = fixture {
        target.library().putEpisode(EpisodeEntity("episode", "show", "guid", "Episode", "https://example.test/audio", mediaFingerprint = "sha"))
        PortableLibrarySync(target).putMoment(moment.copy(insight = "Refused earlier"))
        target.library().deleteDistillation("moment")
        val refused = target.sync().operations("alice", "g1").single()
        target.sync().state(refused.operationId, "rejected")
        restore()
        val queued = target.sync().operations("alice", "g1").single()
        assertEquals("queued", queued.state); assertNotEquals(refused.operationId, queued.operationId)
        assertEquals("The speaker explains a useful idea.", SubscriptionSyncStore.decode(queued.payload)["insight"])
    }
    @Test fun `restored records of feeds not shared with sync stay local until the feed is approved`() = fixture(approved = false) {
        restore()
        assertTrue(keys().isEmpty()); assertEquals(0, kicks)
        SubscriptionSyncStore(target).bind(SyncOwner("alice", "g1"), setOf("show"))
        assertEquals(setOf("progress_episode", "completion_episode", "moment_moment"), keys().filterNot { it.startsWith("subscription_") }.toSet())
    }
}
