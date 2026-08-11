package io.tapper.firetv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Guide storage.
 *
 * Plain SQLiteOpenHelper rather than Room: this is one table and four queries,
 * and Room would add a KSP codegen step plus three dependencies for no benefit
 * here. The catalogue stays in memory; only the EPG needs a database, because a
 * full guide is hundreds of thousands of rows and cannot be held in RAM on a
 * Fire TV stick.
 */
class EpgDatabase(context: Context) : SQLiteOpenHelper(context, "tapper_epg.db", null, 1) {

    data class Programme(
        val channelId: String,
        val startUtc: Long,
        val endUtc: Long,
        val title: String,
        val description: String?,
    ) {
        fun progressAt(now: Long): Float {
            val span = (endUtc - startUtc).coerceAtLeast(1L)
            return ((now - startUtc).toFloat() / span).coerceIn(0f, 1f)
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE epg (
              source_id TEXT NOT NULL,
              ch        TEXT NOT NULL,
              start_utc INTEGER NOT NULL,
              end_utc   INTEGER NOT NULL,
              title     TEXT NOT NULL,
              descr     TEXT
            )
            """.trimIndent()
        )
        // Every guide read is a time-range scan grouped by channel, so the index
        // must lead with (source_id, ch) and then start_utc. Without it the
        // "what's on now" query for a visible page becomes a full table scan.
        db.execSQL("CREATE INDEX epg_lookup ON epg(source_id, ch, start_utc)")
        db.execSQL("CREATE INDEX epg_window ON epg(source_id, start_utc, end_utc)")
        db.execSQL("CREATE TABLE epg_meta (source_id TEXT PRIMARY KEY, fetched_utc INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS epg")
        db.execSQL("DROP TABLE IF EXISTS epg_meta")
        onCreate(db)
    }

    /**
     * Replaces a source's guide wholesale. One transaction: committing per row
     * would take minutes for a real guide, and a half-written guide is worse
     * than none.
     */
    fun replaceAll(sourceId: String, rows: List<Programme>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("epg", "source_id = ?", arrayOf(sourceId))
            val stmt = db.compileStatement(
                "INSERT INTO epg(source_id, ch, start_utc, end_utc, title, descr) VALUES (?,?,?,?,?,?)"
            )
            for (r in rows) {
                stmt.clearBindings()
                stmt.bindString(1, sourceId)
                stmt.bindString(2, r.channelId)
                stmt.bindLong(3, r.startUtc)
                stmt.bindLong(4, r.endUtc)
                stmt.bindString(5, r.title)
                r.description?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
                stmt.executeInsert()
            }
            db.replace("epg_meta", null, ContentValues().apply {
                put("source_id", sourceId)
                put("fetched_utc", System.currentTimeMillis())
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun fetchedAt(sourceId: String): Long =
        readableDatabase.rawQuery(
            "SELECT fetched_utc FROM epg_meta WHERE source_id = ?", arrayOf(sourceId)
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun countFor(sourceId: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM epg WHERE source_id = ?", arrayOf(sourceId)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * What is on right now, for every channel at once. One query for the whole
     * list rather than one per visible row - per-row queries are what make
     * these apps stutter while scrolling a long channel list.
     */
    fun nowPlaying(sourceId: String, now: Long): Map<String, Programme> {
        val out = HashMap<String, Programme>()
        readableDatabase.rawQuery(
            "SELECT ch, start_utc, end_utc, title, descr FROM epg " +
                "WHERE source_id = ? AND start_utc <= ? AND end_utc > ?",
            arrayOf(sourceId, now.toString(), now.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = Programme(
                    c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4)
                )
            }
        }
        return out
    }

    fun upcoming(sourceId: String, channelId: String, now: Long, limit: Int = 8): List<Programme> {
        val out = ArrayList<Programme>()
        readableDatabase.rawQuery(
            "SELECT ch, start_utc, end_utc, title, descr FROM epg " +
                "WHERE source_id = ? AND ch = ? AND end_utc > ? ORDER BY start_utc LIMIT ?",
            arrayOf(sourceId, channelId, now.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Programme(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4)))
            }
        }
        return out
    }

    /** Programme title search, restricted to the future and the current show. */
    fun search(sourceId: String, query: String, now: Long, limit: Int = 60): List<Programme> {
        val out = ArrayList<Programme>()
        readableDatabase.rawQuery(
            "SELECT ch, start_utc, end_utc, title, descr FROM epg " +
                "WHERE source_id = ? AND end_utc > ? AND title LIKE ? " +
                "ORDER BY start_utc LIMIT ?",
            arrayOf(sourceId, now.toString(), "%" + query + "%", limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Programme(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4)))
            }
        }
        return out
    }

    fun prune(cutoffUtc: Long) {
        writableDatabase.delete("epg", "end_utc < ?", arrayOf(cutoffUtc.toString()))
    }
}
