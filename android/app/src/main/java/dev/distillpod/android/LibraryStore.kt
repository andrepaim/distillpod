package dev.distillpod.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class StoredEpisode(val id: String, val feedUrl: String, val title: String, val audioUrl: String)
data class StoredDistillation(val id: String, val episodeId: String, val positionMs: Long, val excerpt: String, val insight: String?)
/** Feasibility-build store. Full Room migration is tracked separately in the plan. */
class LibraryStore(context: Context) : SQLiteOpenHelper(context, "distillpod.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE episodes(id TEXT PRIMARY KEY, feed TEXT NOT NULL, title TEXT NOT NULL, audio TEXT NOT NULL, position INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE distillations(id TEXT PRIMARY KEY, episode TEXT NOT NULL, position INTEGER NOT NULL, excerpt TEXT NOT NULL, insight TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { error("Explicit migration required") }
    fun saveEpisode(ep: StoredEpisode) {
        val values = ContentValues().apply { put("id", ep.id); put("feed", ep.feedUrl); put("title", ep.title); put("audio", ep.audioUrl) }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (db.update("episodes", values, "id = ?", arrayOf(ep.id)) == 0) db.insertOrThrow("episodes", null, values)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun episodes(): List<StoredEpisode> = readableDatabase.rawQuery("SELECT id,feed,title,audio FROM episodes ORDER BY rowid DESC", null).use { c ->
        buildList { while(c.moveToNext()) add(StoredEpisode(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) }
    }
    fun saveProgress(id: String, position: Long) {
        require(position >= 0)
        writableDatabase.update("episodes", ContentValues().apply { put("position", position) }, "id = ?", arrayOf(id))
    }
    fun progress(id: String): Long = readableDatabase.rawQuery("SELECT position FROM episodes WHERE id = ?", arrayOf(id)).use { if(it.moveToFirst()) it.getLong(0) else 0 }
    fun saveDistillation(id: String, episode: String, position: Long, excerpt: String, insight: String?) {
        require(position >= 0)
        writableDatabase.insertWithOnConflict("distillations", null, ContentValues().apply {
            put("id", id); put("episode", episode); put("position", position); put("excerpt", excerpt); put("insight", insight)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun distillations(): List<StoredDistillation> = readableDatabase.rawQuery("SELECT id,episode,position,excerpt,insight FROM distillations ORDER BY rowid DESC", null).use { c ->
        buildList { while(c.moveToNext()) add(StoredDistillation(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), if(c.isNull(4)) null else c.getString(4))) }
    }
}
