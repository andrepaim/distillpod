package dev.distillpod.android

import android.content.ContentValues
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
    @Test fun schemaOneUpgradesWithoutLosingProgressSavedMomentsOrMessages() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-test-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        try {
            val schema = JSONObject(instrumentation.context.assets.open("dev.distillpod.android.data.AppDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
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
                insert("episodes", mapOf("id" to "e", "podcastId" to "p", "guid" to "guid", "title" to "Episode", "audioUrl" to "https://example.test/audio", "positionMs" to 23000))
                insert("distillations", mapOf("id" to "d", "episodeId" to "e", "excerpt" to "Saved source", "state" to "succeeded"))
                insert("messages", mapOf("id" to "m", "episodeId" to "e", "role" to "assistant", "text" to "Saved reply", "state" to "succeeded"))
                old.version = 1
            }
            val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2).build()
            try {
                assertEquals(23000L, upgraded.library().episode("e")!!.positionMs)
                assertEquals("Saved source", upgraded.library().distillation("d")!!.excerpt)
                assertEquals("Saved reply", upgraded.library().message("m")!!.text)
                assertEquals("[]", upgraded.library().message("m")!!.sourcesJson)
                assertEquals("", upgraded.library().distillation("d")!!.attempt)
            } finally { upgraded.close() }
        } finally { context.deleteDatabase(name) }
    }
}
