package org.photolibrary.app.index

import net.zetetic.database.sqlcipher.SQLiteDatabase

internal enum class IndexingStage { VISUAL, TEXT }

internal data class IndexingCheckpoint(
    val signature: String = "",
    val cursor: Int = 0,
    val total: Int = 0,
    val paused: Boolean = false,
    val failed: Int = 0,
    val stage: IndexingStage = IndexingStage.VISUAL,
    val otherCursor: Int = 0,
    val sliceMillis: Long = 0,
) {
    val finished get() = total == 0 || stage == IndexingStage.TEXT && cursor >= total
}

internal class IndexingControl(private val storage: EncryptedPhotoIndex) {
    fun read(): IndexingCheckpoint = storage.withDatabase { db ->
        ensureTable(db)
        readRow(db)
    }

    fun begin(signature: String, total: Int, firstStage: IndexingStage = IndexingStage.VISUAL,
        fair: Boolean = false): IndexingCheckpoint =
        update { previous ->
            if (previous.signature == signature && previous.total == total) null
            else IndexingCheckpoint(signature, total = total, paused = previous.paused, stage = firstStage,
                otherCursor = if (fair && firstStage == IndexingStage.TEXT) total else 0)
        }

    fun checkpoint(expected: IndexingCheckpoint, cursor: Int, failed: Int, elapsedMillis: Long? = null): IndexingCheckpoint = update { current ->
        when {
            current != expected || current.paused -> null
            elapsedMillis != null -> advanceSlice(current, cursor, failed, elapsedMillis)
            current.stage == IndexingStage.VISUAL && cursor >= current.total ->
                current.copy(stage = IndexingStage.TEXT, cursor = 0, failed = failed)
            else -> current.copy(cursor = cursor.coerceIn(0, current.total), failed = failed)
        }
    }

    private fun advanceSlice(current: IndexingCheckpoint, cursor: Int, failed: Int, elapsedMillis: Long): IndexingCheckpoint {
        val next = cursor.coerceIn(0, current.total)
        val spent = current.sliceMillis + elapsedMillis.coerceAtLeast(0)
        val visual = current.stage == IndexingStage.VISUAL
        val budget = if (visual) 8_000L else 2_000L
        if (next == current.total && current.otherCursor == current.total) {
            return current.copy(cursor = next, failed = failed, stage = IndexingStage.TEXT, sliceMillis = 0)
        }
        if (current.otherCursor < current.total && (next == current.total || spent >= budget)) {
            return current.copy(stage = if (visual) IndexingStage.TEXT else IndexingStage.VISUAL, failed = failed,
                cursor = current.otherCursor, otherCursor = next, sliceMillis = 0)
        }
        return current.copy(cursor = next, failed = failed, sliceMillis = minOf(spent, budget))
    }

    fun pause(paused: Boolean): IndexingCheckpoint = update { it.copy(paused = paused) }

    fun request(): IndexingCheckpoint = update { current ->
        if (current.finished) current.copy(signature = "", cursor = 0, failed = 0) else null
    }

    private fun update(change: (IndexingCheckpoint) -> IndexingCheckpoint?): IndexingCheckpoint =
        storage.withDatabase { db ->
            db.transaction {
                ensureTable(db)
                val previous = readRow(db)
                change(previous)?.also { writeRow(db, it) } ?: previous
            }
        }

    private fun ensureTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS indexing_control (
            id INTEGER PRIMARY KEY CHECK (id = 1), signature TEXT NOT NULL,
            cursor INTEGER NOT NULL, total INTEGER NOT NULL, paused INTEGER NOT NULL, failed INTEGER NOT NULL DEFAULT 0
        )""".trimIndent())
        val columns = db.rawQuery("PRAGMA table_info(indexing_control)", emptyArray<String>()).use {
            buildSet { while (it.moveToNext()) add(it.getString(1)) }
        }
        if ("failed" !in columns) db.execSQL("ALTER TABLE indexing_control ADD COLUMN failed INTEGER NOT NULL DEFAULT 0")
        if ("other_cursor" !in columns) db.execSQL("ALTER TABLE indexing_control ADD COLUMN other_cursor INTEGER NOT NULL DEFAULT 0")
        if ("slice_ms" !in columns) db.execSQL("ALTER TABLE indexing_control ADD COLUMN slice_ms INTEGER NOT NULL DEFAULT 0")
        if ("stage" !in columns) db.execSQL("ALTER TABLE indexing_control ADD COLUMN stage TEXT NOT NULL DEFAULT 'TEXT'")
    }

    private fun readRow(db: SQLiteDatabase): IndexingCheckpoint =
        db.rawQuery("SELECT signature, cursor, total, paused, failed, stage, other_cursor, slice_ms FROM indexing_control WHERE id = 1", emptyArray<String>()).use {
            if (it.moveToFirst()) IndexingCheckpoint(it.getString(0), it.getInt(1), it.getInt(2), it.getInt(3) != 0, it.getInt(4),
                IndexingStage.valueOf(it.getString(5)), it.getInt(6), it.getLong(7))
            else IndexingCheckpoint()
        }

    private fun writeRow(db: SQLiteDatabase, checkpoint: IndexingCheckpoint) {
        db.execSQL("INSERT OR REPLACE INTO indexing_control (id, signature, cursor, total, paused, failed, stage, other_cursor, slice_ms) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(checkpoint.signature, checkpoint.cursor, checkpoint.total, if (checkpoint.paused) 1 else 0,
                checkpoint.failed, checkpoint.stage.name, checkpoint.otherCursor, checkpoint.sliceMillis))
    }
}
