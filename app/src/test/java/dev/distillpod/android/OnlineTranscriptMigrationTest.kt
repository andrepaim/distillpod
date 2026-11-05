package dev.distillpod.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnlineTranscriptMigrationTest {
    @Test fun `all published schemas upgrade with data and safe lookup defaults`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (version in 1..7) {
            val name = "lookup-upgrade-${UUID.randomUUID()}.db"
            val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
            try {
                val schema = JSONObject(javaClass.classLoader!!.getResourceAsStream("dev.distillpod.android.data.AppDatabase/$version.json")!!
                    .bufferedReader().use { it.readText() }).getJSONObject("database")
                val entities = schema.getJSONArray("entities")
                SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                    repeat(entities.length()) { i ->
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        repeat(indices.length()) { j -> old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    repeat(setup.length()) { old.execSQL(setup.getString(it)) }
                    fun insert(table: String, values: Map<String, Any>) {
                        val entity = (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == table }
                        val fields = entity.getJSONArray("fields")
                        val content = ContentValues()
                        repeat(fields.length()) { i -> val field = fields.getJSONObject(i)
                            if (field.optBoolean("notNull")) {
                                if (field.getString("affinity") == "TEXT") content.put(field.getString("columnName"), "")
                                else content.put(field.getString("columnName"), 0)
                            }
                        }
                        values.forEach { (key, value) -> if (value is String) content.put(key, value) else content.put(key, (value as Number).toLong()) }
                        old.insertOrThrow(table, null, content)
                    }
                    insert("podcasts", mapOf("id" to "p", "feedUrl" to "https://example.test/feed", "title" to "Show"))
                    insert("episodes", mapOf("id" to "e", "podcastId" to "p", "guid" to "guid", "title" to "Episode", "audioUrl" to "https://example.test/audio", "positionMs" to 23000))
                    insert("transcripts", mapOf("episodeId" to "e", "revision" to "r", "state" to "ready"))
                    insert("distillations", mapOf("id" to "d", "episodeId" to "e", "excerpt" to "Saved quote", "state" to "succeeded"))
                    old.version = version
                }
                val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2,
                    AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
                    AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8).build()
                try {
                    assertEquals(23000L, db.library().episode("e")!!.positionMs)
                    assertEquals("Saved quote", db.library().distillation("d")!!.excerpt)
                    assertFalse(db.library().transcript("e")!!.lookupOnline)
                    assertFalse(db.library().transcript("e")!!.replaceOnline)
                    assertNull(db.library().onlineTranscript("e"))
                    assertNull(db.library().transcriptLookup("e"))
                    assertNull(db.library().post("none"))
                } finally { db.close() }
            } finally { context.deleteDatabase(name) }
        }
    }
}
