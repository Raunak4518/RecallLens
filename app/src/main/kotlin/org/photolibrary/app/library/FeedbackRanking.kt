package org.photolibrary.app.library

import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.transaction
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

internal data class ResultJudgment(
    val id: String,
    val query: String,
    val source: String,
    val revision: String,
    val positive: Boolean,
    val features: FeedbackFeatures? = null,
    val reason: FeedbackReason? = null,
) {
    companion object {
        fun create(query: String, photo: SelectedPhoto, positive: Boolean, features: FeedbackFeatures) = ResultJudgment(
            UUID.randomUUID().toString(), feedbackQueryKey(query), photo.uri.toString(),
            photo.sourceRevision.orEmpty(), positive, features,
        )
    }
}

internal fun normalizedFeedbackQuery(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC)
    .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

internal fun feedbackQueryKey(text: String): String = "v1:" + android.util.Base64.encodeToString(
    java.security.MessageDigest.getInstance("SHA-256").digest(normalizedFeedbackQuery(text).toByteArray(Charsets.UTF_8)),
    android.util.Base64.NO_WRAP,
)

internal data class FeedbackHistory(val enabled: Boolean, val judgments: List<ResultJudgment> = emptyList(), val head: FeedbackHead? = null)

internal class FeedbackStore(private val index: EncryptedPhotoIndex) {
    fun update(enabled: Boolean? = null, record: ResultJudgment? = null, undo: String? = null, reason: Pair<String, FeedbackReason>? = null, accessible: List<SelectedPhoto>? = null): FeedbackHistory =
        index.withDatabase { db ->
            db.transaction {
                db.execSQL("CREATE TABLE IF NOT EXISTS result_learning (id INTEGER PRIMARY KEY CHECK(id = 1))")
                db.execSQL("CREATE TABLE IF NOT EXISTS result_judgments (id TEXT UNIQUE NOT NULL, query TEXT NOT NULL, source TEXT NOT NULL, revision TEXT NOT NULL, positive INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS feedback_details (id TEXT PRIMARY KEY, features INTEGER NOT NULL, base_rank INTEGER NOT NULL, tier INTEGER NOT NULL, reason TEXT)")
                if (enabled == false) {
                    db.execSQL("DELETE FROM result_learning")
                    db.execSQL("DELETE FROM result_judgments")
                } else if (enabled == true) {
                    db.execSQL("INSERT OR IGNORE INTO result_learning VALUES (1)")
                }
                val learning = db.rawQuery("SELECT id FROM result_learning LIMIT 1", emptyArray<String>()).use { it.moveToFirst() }
                if (learning && record != null && record.revision.isNotEmpty()) {
                    db.execSQL("INSERT OR IGNORE INTO result_judgments VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>(
                        record.id, record.query, record.source, record.revision, if (record.positive) 1 else 0,
                    ))
                    record.features?.let { f ->
                        db.execSQL("INSERT OR IGNORE INTO feedback_details (id, features, base_rank, tier) VALUES (?, ?, ?, ?)",
                            arrayOf<Any?>(record.id, f.packed, f.baseRank, f.tier))
                    }
                    db.execSQL("DELETE FROM result_judgments WHERE rowid IN (SELECT rowid FROM result_judgments ORDER BY rowid DESC LIMIT -1 OFFSET 512)")
                }
                if (undo != null) db.execSQL("DELETE FROM result_judgments WHERE id = ?", arrayOf(undo))
                if (learning && reason != null) db.execSQL("UPDATE feedback_details SET reason = ? WHERE id = ?", arrayOf(reason.second.name, reason.first))
                val rows = db.rawQuery("SELECT j.id, j.query, j.source, j.revision, j.positive, d.features, d.base_rank, d.tier, d.reason FROM result_judgments j LEFT JOIN feedback_details d ON d.id = j.id ORDER BY j.rowid", emptyArray<String>()).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(ResultJudgment(cursor.getString(0), cursor.getString(1),
                            cursor.getString(2), cursor.getString(3), cursor.getInt(4) != 0,
                            if (cursor.isNull(5)) null else FeedbackFeatures(cursor.getLong(5), cursor.getInt(6), cursor.getInt(7)),
                            if (cursor.isNull(8)) null else FeedbackReason.entries.find { it.name == cursor.getString(8) }))
                    }
                }
                val valid = if (accessible == null || rows.isEmpty()) rows else {
                    val revisions = accessible.associate { it.uri.toString() to it.sourceRevision }
                    rows.filter { it.revision == revisions[it.source] }
                }
                val retained = valid.mapTo(hashSetOf()) { it.id }
                rows.filterNot { it.id in retained }.forEach {
                    db.execSQL("DELETE FROM result_judgments WHERE id = ?", arrayOf(it.id))
                }
                db.execSQL("DELETE FROM feedback_details WHERE id NOT IN (SELECT id FROM result_judgments)")
                FeedbackHistory(learning, valid)
            }
        }.let { snapshot -> snapshot.copy(head = if (snapshot.enabled) FeedbackHead.fit(snapshot.judgments) else null) }
}

internal class FeedbackRanking(history: List<ResultJudgment>, query: String, private val head: FeedbackHead?) {
    private val queryKey = normalizedFeedbackQuery(query)
    private val direct = history.filter { it.query == feedbackQueryKey(query) }.associateBy { it.source to it.revision }

    fun ranked(photos: List<SelectedPhoto>, visual: Map<android.net.Uri, Float>, tier: (SelectedPhoto) -> Int): List<SelectedPhoto> {
        if (direct.isEmpty() && head == null) return photos
        val eligible = photos.filter { direct[it.uri.toString() to it.sourceRevision.orEmpty()]?.positive != false }
        return buildList {
            var start = 0
            while (start < eligible.size) {
                val group = tier(eligible[start])
                var end = start + 1
                while (end < eligible.size && tier(eligible[end]) == group) end++
                val windowEnd = minOf(end, start + 100)
                addAll(eligible.subList(start, windowEnd).mapIndexed { rank, photo ->
                    val key = photo.uri.toString() to photo.sourceRevision.orEmpty()
                    val boost = if (direct[key]?.positive == true) 2.0 else 0.0
                    val learned = head?.shift(FeedbackFeatures.from(queryKey, photo, rank, group, visual[photo.uri])) ?: 0.0
                    (rank - boost - learned) to photo
                }.sortedBy { it.first }.map { it.second })
                if (windowEnd < end) addAll(eligible.subList(windowEnd, end))
                start = end
            }
        }
    }
}
