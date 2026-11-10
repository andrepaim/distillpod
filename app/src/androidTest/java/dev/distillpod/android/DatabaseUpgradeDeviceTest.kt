package dev.distillpod.android

import android.content.ContentValues
import android.os.Build
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DatabaseUpgradeDeviceTest {
    @Test fun schemaOneUpgradesWithoutLosingProgressSavedMomentsOrMessages() = upgradeFixture(1)
    @Test fun schemaTwoUpgradesWithoutLosingProgressSavedMomentsOrMessages() = upgradeFixture(2)
    @Test fun schemaThreeUpgradesWithoutLosingProgressSavedMomentsOrMessages() = upgradeFixture(3)
    @Test fun schemaFourUpgradesWithoutLosingProgressSavedMomentsOrMessages() = upgradeFixture(4)
    @Test fun schemaFiveUpgradesWithoutLosingProgressSavedMomentsOrMessages() = upgradeFixture(5)
    @Test fun schemaSixUpgradesWithoutLosingProgressSavedMomentsOrMessages() = upgradeFixture(6)
    @Test fun schemaSevenUpgradesWithoutLosingProgressSavedMomentsOrMessages() = upgradeFixture(7)
    @Test fun schemaEightUpgradesWithoutLosingSavedMoments() = upgradeFixture(8)
    @Test fun schemaNineUpgradesWithoutActivatingAutomation() = upgradeFixture(9)
    @Test fun schemaTenUpgradesWithoutChangingExistingLibrary() = upgradeFixture(10)
    @Test fun schemaElevenUpgradesWithUnknownLocalAges() = upgradeFixture(11)
    private fun upgradeFixture(from: Int) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "dev.distillpod.android.validation.retention60" ||
            Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) { "Use the isolated retention validation app" }
        val name = "migration-test-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        try {
            val schema = JSONObject(instrumentation.context.assets.open("dev.distillpod.android.data.AppDatabase/$from.json").bufferedReader().use { it.readText() }).getJSONObject("database")
            val entities = schema.getJSONArray("entities")
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                repeat(entities.length()) { i -> val entity = entities.getJSONObject(i)
                    old.execSQL(entity.getString("createSql").replace("$" + "{TABLE_NAME}", entity.getString("tableName")))
                    entity.optJSONArray("indices")?.let { indices -> repeat(indices.length()) { j -> old.execSQL(indices.getJSONObject(j).getString("createSql").replace("$" + "{TABLE_NAME}", entity.getString("tableName"))) } }
                }
                val setup = schema.getJSONArray("setupQueries")
                repeat(setup.length()) { old.execSQL(setup.getString(it)) }
                fun insert(table: String, values: Map<String, Any>) {
                    val entity = (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == table }
                    val fields = entity.getJSONArray("fields")
                    val content = ContentValues()
                    repeat(fields.length()) { i -> val f = fields.getJSONObject(i)
                        if (f.optBoolean("notNull")) if (f.getString("affinity") == "TEXT") content.put(f.getString("columnName"), "") else content.put(f.getString("columnName"), 0)
                    }
                    values.forEach { (key, value) -> if (value is String) content.put(key, value) else content.put(key, (value as Number).toLong()) }
                    old.insertOrThrow(table, null, content)
                }
                insert("podcasts", mapOf("id" to "p", "feedUrl" to "https://example.test/feed", "title" to "Upgrade fixture", "subscribed" to 1))
                insert("episodes", mapOf("id" to "e", "podcastId" to "p", "guid" to "guid", "title" to "Episode", "audioUrl" to "https://example.test/audio", "positionMs" to 23000, "localFile" to "/synthetic/legacy.audio", "downloadState" to "downloaded", "downloadedBytes" to 123, "completed" to 1, "lastPlayedAt" to 9000))
                insert("queue", mapOf("episodeId" to "e", "sortOrder" to 0))
                insert("player_session", mapOf("id" to 0, "episodeId" to "e", "speed" to 1))
                insert("transcripts", mapOf("episodeId" to "e", "revision" to "r", "state" to "ready"))
                insert("distillations", mapOf("id" to "d", "episodeId" to "e", "excerpt" to "Saved source", "state" to "succeeded"))
                insert("messages", mapOf("id" to "m", "episodeId" to "e", "role" to "assistant", "text" to "Saved reply", "state" to "succeeded") + if (from >= 2) mapOf("sourcesJson" to "[]") else emptyMap())
                if(from>=3)insert("sync_outbox",mapOf("operationId" to "old-operation","uid" to "alice","generation" to "g1","entityKey" to "subscription_old","baseVersion" to 6,"payload" to "{}"))
                if(from>=4) {
                    insert("sync_binding",mapOf("id" to 0,"uid" to "alice","generation" to "g1","enabled" to 1,"lease" to "old-lease"))
                    insert("sync_remote",mapOf("uid" to "alice","generation" to "g1","entityKey" to "subscription_old","version" to 7,"payload" to "{}","sequence" to 7))
                }
                if(from>=6)insert("sync_views",mapOf("entityKey" to "subscription_old","version" to 7,"sessionId" to "retained-session"))
                old.version = from
            }
            val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12).build()
            try {
                assertEquals(if(from>=3)listOf("old-operation") else emptyList<String>(),upgraded.sync().pending("alice", "g1"))
                if(from>=4)assertEquals(7L,upgraded.sync().view("subscription_old")!!.version)
                assertEquals("/synthetic/legacy.audio", upgraded.library().episode("e")!!.localFile)
                assertEquals("downloaded", upgraded.library().episode("e")!!.downloadState)
                assertEquals(123L, upgraded.library().episode("e")!!.downloadedBytes)
                assertTrue(upgraded.library().episode("e")!!.completed)
                assertEquals(9000L, upgraded.library().episode("e")!!.lastPlayedAt)
                assertEquals(listOf(dev.distillpod.android.data.QueueEntryEntity("e", 0)), upgraded.library().queue())
                assertEquals("e", upgraded.library().playerSession()!!.episodeId)
                assertEquals(0L, upgraded.library().episode("e")!!.localDownloadedAt)
                assertEquals(0L, upgraded.library().transcript("e")!!.localCreatedAt)
                assertEquals("r", upgraded.library().transcript("e")!!.revision)
                assertEquals(23000L, upgraded.library().episode("e")!!.positionMs)
                assertNull(upgraded.library().episode("e")!!.sourceOrder)
                assertEquals("Saved source", upgraded.library().distillation("d")!!.excerpt)
                assertEquals("Saved reply", upgraded.library().message("m")!!.text)
                assertEquals("[]", upgraded.library().message("m")!!.sourcesJson)
                assertEquals("", upgraded.library().distillation("d")!!.attempt)
            } finally { upgraded.close() }
        } finally { context.deleteDatabase(name) }
    }
}
