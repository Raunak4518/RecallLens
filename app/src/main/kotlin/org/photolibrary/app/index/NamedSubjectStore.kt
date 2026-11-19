package org.photolibrary.app.index

import android.net.Uri
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.photolibrary.app.library.UnavailableSources
import org.photolibrary.app.library.SelectedPhoto
import org.photolibrary.app.subjects.Confirmation
import org.photolibrary.app.subjects.DatedAliasDraft
import org.photolibrary.app.subjects.DatedSubjectAlias
import org.photolibrary.app.subjects.IdentityRejection
import org.photolibrary.app.subjects.identityKey
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSubject
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectReference
import org.photolibrary.app.subjects.SubjectGroup
import org.photolibrary.app.subjects.SubjectGroupChange
import org.photolibrary.app.subjects.SubjectGroupEdit
import org.photolibrary.app.subjects.SubjectGroupResult
import org.photolibrary.app.subjects.SubjectGroupResult.Rejected
import org.photolibrary.app.subjects.editableIdentityKinds
import org.photolibrary.app.subjects.SubjectSelection
import org.photolibrary.app.subjects.SelfIdentity
import org.photolibrary.app.subjects.subjectWords
import org.photolibrary.app.subjects.validSubjectLabel

internal data class NamedContentProof(val revision: String, val digest: String)
internal data class NamedReferenceDraft(val uri: Uri, val revision: String, val region: PhotoRegion?, val contentDigest: String?)

internal class NamedSubjectStore(private val index: EncryptedPhotoIndex) {
    fun setSelf(subjectId: Long?): Boolean = index.withDatabase { db -> db.transaction {
        if (subjectId != null && subjectKind(db, subjectId) != SubjectKind.PERSON) return@transaction false
        db.execSQL("INSERT OR REPLACE INTO self_identity (id, subject_id) VALUES (1, ?)", arrayOf<Any?>(subjectId))
        true
    } }

    private fun readSelf(db: SQLiteDatabase): SelfIdentity? =
        db.rawQuery("SELECT subject_id FROM self_identity WHERE id = 1", emptyArray<String>()).use {
            if (it.moveToFirst()) SelfIdentity(if (it.isNull(0)) null else it.getLong(0)) else null
        }

    fun contentProofs(): Map<Uri, NamedContentProof> = index.withDatabase { db ->
        db.rawQuery("SELECT uri, revision, digest FROM named_photo_content", emptyArray<String>()).use { rows ->
            buildMap {
                while (rows.moveToNext()) put(Uri.parse(rows.getString(0)), NamedContentProof(rows.getString(1), rows.getString(2)))
            }
        }
    }

    fun rebindUnchanged(uri: Uri, previous: NamedContentProof, revision: String, digest: String): Boolean {
        if (digest != previous.digest) return false
        return index.withDatabase { db -> db.transaction {
            val present = db.rawQuery("""
                SELECT 1 FROM named_photo_content p JOIN named_references r
                ON r.uri = p.uri AND r.revision = p.revision
                WHERE p.uri = ? AND p.revision = ? AND p.digest = ? LIMIT 1
            """.trimIndent(), arrayOf(uri.toString(), previous.revision, previous.digest)).use { it.moveToFirst() }
            if (!present) return@transaction false
            db.execSQL("UPDATE named_references SET revision = ? WHERE uri = ? AND revision = ?",
                arrayOf(revision, uri.toString(), previous.revision))
            db.execSQL("UPDATE named_photo_content SET revision = ? WHERE uri = ?", arrayOf(revision, uri.toString()))
            true
        } }
    }

    fun revisions(): Set<Uri> = index.withDatabase { db ->
        db.rawQuery("SELECT DISTINCT uri FROM named_references", emptyArray<String>()).use { rows ->
            buildSet { while (rows.moveToNext()) add(Uri.parse(rows.getString(0))) }
        }
    }

    fun reconcile(accessible: Map<Uri, String>, unavailable: UnavailableSources = UnavailableSources.NONE): NamedLibrary = index.withDatabase { db ->
        db.transaction {
            val references = readReferences(db)
            val remaining = references.filter { accessible[it.uri] == it.revision }
            (references - remaining.toSet()).map { it.uri }.distinct()
                .filter { it in accessible || it !in unavailable }.forEach { uri ->
                db.execSQL("DELETE FROM named_references WHERE uri = ? AND revision <> ?",
                    arrayOf(uri.toString(), accessible[uri].orEmpty()))
            }
            pruneContentProofs(db)
            NamedLibrary(readSubjects(db), remaining, readCollegeHistory(db), readSelf(db), readDatedAliases(db))
        }
    }

    fun confirm(uri: Uri, revision: String, choice: SubjectSelection, region: PhotoRegion?, contentDigest: String? = null): Confirmation =
        confirmAll(choice, listOf(NamedReferenceDraft(uri, revision, region, contentDigest)))

    fun confirmAll(choice: SubjectSelection, references: List<NamedReferenceDraft>): Confirmation {
        require(references.isNotEmpty() && references.map { it.uri }.distinct().size == references.size)
        return index.withDatabase { db ->
            db.transaction {
                if (choice is SubjectSelection.New && choice.currentCollege && choice.kind != SubjectKind.ORGANIZATION) {
                    return@transaction Confirmation.INVALID_COLLEGE
                }
                val datedAlias = choice.datedAlias?.let { it.copy(label = it.label.trim()) }
                if (datedAlias != null && !availableDatedAlias(db, datedAlias, choice)) {
                    return@transaction Confirmation.INVALID_DATED_ALIAS
                }
                val id = when (choice) {
                    is SubjectSelection.Existing -> {
                        val kind = subjectKind(db, choice.id) ?: return@transaction Confirmation.MISSING_SUBJECT
                        if (choice.currentCollege && kind != SubjectKind.ORGANIZATION) return@transaction Confirmation.INVALID_COLLEGE
                        choice.id
                    }
                    is SubjectSelection.New -> insertSubject(db, choice)
                        ?: return@transaction Confirmation.DUPLICATE_NAME
                }
                for ((uri, revision, region, contentDigest) in references) {
                    db.execSQL("DELETE FROM named_references WHERE uri = ? AND revision <> ?", arrayOf(uri.toString(), revision))
                    db.execSQL(
                        "INSERT OR REPLACE INTO named_references VALUES (?, ?, ?, ?, ?, ?, ?)",
                        arrayOf<Any?>(id, uri.toString(), revision, region?.left, region?.top, region?.right, region?.bottom),
                    )
                    removeRejection(db, id, IdentityRejection(uri, revision, region.identityKey()))
                    if (contentDigest != null) db.execSQL("INSERT OR REPLACE INTO named_photo_content VALUES (?, ?, ?)",
                        arrayOf(uri.toString(), revision, contentDigest))
                }
                pruneContentProofs(db)
                if (datedAlias != null) insertDatedAlias(db, DatedSubjectAlias(id, datedAlias))
                if (choice.currentCollege) setCurrentCollege(db, id)
                Confirmation.SAVED
            }
        }
    }

    fun identityRejections(subjectId: Long): Set<IdentityRejection> = index.withDatabase { db -> readRejections(db, subjectId) }

    private fun readRejections(db: SQLiteDatabase, subjectId: Long): Set<IdentityRejection> =
        db.rawQuery("SELECT uri, revision, region_key FROM identity_rejections WHERE subject_id = ?",
            arrayOf(subjectId.toString())).use { rows ->
            buildSet { while (rows.moveToNext()) add(IdentityRejection(Uri.parse(rows.getString(0)), rows.getString(1), rows.getString(2))) }
        }

    fun rejectIdentity(subjectId: Long, rejection: IdentityRejection): Boolean = index.withDatabase { db ->
        db.transaction {
            if (subjectKind(db, subjectId) !in setOf(SubjectKind.PERSON, SubjectKind.OBJECT)) return@transaction false
            db.execSQL("INSERT OR IGNORE INTO identity_rejections (subject_id, uri, revision, region_key) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(subjectId, rejection.uri.toString(), rejection.revision, rejection.regionKey))
            true
        }
    }

    fun undoIdentityRejection(subjectId: Long, rejection: IdentityRejection) = index.withDatabase { db ->
        removeRejection(db, subjectId, rejection)
    }

    fun pruneIdentityRejections(photos: List<SelectedPhoto>, unavailable: UnavailableSources) = index.withDatabase { db ->
        db.transaction {
            val revisions = photos.associate { it.uri to it.sourceRevision }
            val stale = db.rawQuery("SELECT DISTINCT uri, revision FROM identity_rejections", emptyArray<String>()).use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        val uri = Uri.parse(rows.getString(0))
                        val revision = rows.getString(1)
                        val current = revisions[uri]
                        if (uri !in revisions && uri !in unavailable || current != null && revision != "source:$current") {
                            add(uri to revision)
                        }
                    }
                }
            }
            for ((uri, revision) in stale) db.execSQL("DELETE FROM identity_rejections WHERE uri = ? AND revision = ?",
                arrayOf(uri.toString(), revision))
        }
    }

    private fun removeRejection(db: SQLiteDatabase, subjectId: Long, rejection: IdentityRejection) {
        db.execSQL("DELETE FROM identity_rejections WHERE subject_id = ? AND uri = ? AND revision = ? AND region_key = ?",
            arrayOf<Any>(subjectId, rejection.uri.toString(), rejection.revision, rejection.regionKey))
    }

    fun editGroups(
        current: NamedLibrary,
        sourceId: Long,
        change: SubjectGroupChange,
    ): SubjectGroupResult = index.withDatabase { db ->
        db.transaction {
            val source = readGroup(db, sourceId) ?: return@transaction Rejected.CHANGED
            if (source.subject.kind !in editableIdentityKinds) return@transaction Rejected.INCOMPATIBLE_GROUP
            val savedSource = current.subjects.firstOrNull { it.id == sourceId }
            val shownSource = current.references.filter { it.subjectId == sourceId }.toSet()
            if (source.subject != savedSource || source.references != shownSource || readSelf(db) != current.selfIdentity)
                return@transaction Rejected.CHANGED
            val edit = when (change) {
                is SubjectGroupChange.Split -> {
                    val moving = source.references.filter { it.uri in change.photos }.toSet()
                    if (moving.isEmpty() || moving.size >= source.references.size || moving.size != change.photos.size)
                        return@transaction Rejected.INCOMPATIBLE_GROUP
                    val created = insertSubject(db, SubjectSelection.New(change.name, source.subject.kind, emptyList()))
                        ?: return@transaction Rejected.DUPLICATE_NAME
                    val newGroup = readGroup(db, created) ?: error("Inserted subject missing")
                    val relocated = moving.mapTo(linkedSetOf()) { it.copy(subjectId = created) }
                    SubjectGroupEdit(
                        before = mapOf(sourceId to source, created to null),
                        after = mapOf(sourceId to source.copy(references = source.references - moving),
                            created to newGroup.copy(references = relocated)),
                        selfBefore = current.selfIdentity, selfAfter = current.selfIdentity,
                        sourceId = sourceId, focusId = sourceId,
                    )
                }
                is SubjectGroupChange.Merge -> {
                    val targetId = change.destination
                    val target = readGroup(db, targetId) ?: return@transaction Rejected.CHANGED
                    if (sourceId == targetId || source.subject.kind != target.subject.kind)
                        return@transaction Rejected.INCOMPATIBLE_GROUP
                    if (target.subject != current.subjects.firstOrNull { it.id == targetId } ||
                        target.references != current.references.filter { it.subjectId == targetId }.toSet())
                        return@transaction Rejected.CHANGED
                    val combined = target.references.associateBy { it.uri }.toMutableMap()
                    val negatives = target.rejections + source.rejections
                    for (reference in source.references) {
                        val existing = combined[reference.uri]
                        val moved = reference.copy(subjectId = targetId)
                        if (existing != null && existing != moved) return@transaction Rejected.CONFLICTING_REGIONS
                        combined[reference.uri] = moved
                    }
                    if (combined.values.any { IdentityRejection(it.uri, it.revision, it.region.identityKey()) in negatives })
                        return@transaction Rejected.CONFLICTING_REGIONS
                    val merged = target.copy(
                        subject = target.subject.copy(aliases = target.subject.aliases + source.subject.name + source.subject.aliases),
                        references = combined.values.toSet(),
                        rejections = negatives,
                        datedAliases = target.datedAliases + source.datedAliases.map { it.copy(subjectId = targetId) },
                    )
                    val self = if (current.selfIdentity?.subjectId == sourceId) SelfIdentity(targetId) else current.selfIdentity
                    SubjectGroupEdit(mapOf(sourceId to source, targetId to target), mapOf(sourceId to null, targetId to merged),
                        current.selfIdentity, self, sourceId, targetId)
                }
            }
            writeGroups(db, edit.after, edit.selfAfter)
            SubjectGroupResult.Applied(edit.copy(after = edit.after.keys.associateWith { readGroup(db, it) }))
        }
    }

    fun undoGroupEdit(edit: SubjectGroupEdit): Boolean = index.withDatabase { db ->
        db.transaction {
            if (readSelf(db) != edit.selfAfter || edit.after.keys.associateWith { readGroup(db, it) } != edit.after)
                return@transaction false
            val labels = edit.before.values.filterNotNull().flatMap { listOf(it.subject.name) + it.subject.aliases }
            for (label in labels) {
                val owner = db.rawQuery("SELECT subject_id FROM named_labels WHERE normalized = ?",
                    arrayOf(subjectWords(label).joinToString(" "))).use { if (it.moveToFirst()) it.getLong(0) else null }
                if (owner != null && owner !in edit.before) return@transaction false
            }
            val outsidePeriods = readDatedAliases(db).filterNot { it.subjectId in edit.before }
            val restoredPeriods = edit.before.values.filterNotNull().flatMap { it.datedAliases }
            if (restoredPeriods.any { prior -> outsidePeriods.any { it.period.overlaps(prior.period) } }) return@transaction false
            for (label in labels) {
                if (outsidePeriods.any { subjectWords(it.period.label) == subjectWords(label) }) return@transaction false
            }
            if (restoredPeriods.any { labelExists(db, subjectWords(it.period.label).joinToString(" ")) }) return@transaction false
            writeGroups(db, edit.before, edit.selfBefore)
            true
        }
    }

    private fun readGroup(db: SQLiteDatabase, id: Long): SubjectGroup? {
        val subject = readSubjects(db, id).singleOrNull() ?: return null
        return SubjectGroup(subject, readReferences(db, id).toSet(), readRejections(db, id), readDatedAliases(db, id).toSet())
    }

    private fun writeGroups(db: SQLiteDatabase, groups: Map<Long, SubjectGroup?>, self: SelfIdentity?) {
        for (id in groups.keys) {
            for (table in listOf("named_labels", "named_references", "identity_rejections", "named_dated_aliases")) {
                db.execSQL("DELETE FROM $table WHERE subject_id = ?", arrayOf(id))
            }
            db.execSQL("DELETE FROM named_subjects WHERE id = ?", arrayOf(id))
        }
        for ((id, state) in groups) {
            if (state == null) continue
            val subject = state.subject
            db.execSQL("INSERT INTO named_subjects (id, name, kind) VALUES (?, ?, ?)", arrayOf<Any>(id, subject.name, subject.kind.name))
            for (label in listOf(subject.name) + subject.aliases) {
                db.execSQL("INSERT INTO named_labels VALUES (?, ?, ?)", arrayOf<Any>(subjectWords(label).joinToString(" "), id, label))
            }
            for (reference in state.references) {
                val region = reference.region
                db.execSQL("INSERT INTO named_references VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(id, reference.uri.toString(), reference.revision, region?.left, region?.top, region?.right, region?.bottom))
            }
            state.datedAliases.forEach { insertDatedAlias(db, it) }
            for (negative in state.rejections) {
                db.execSQL("INSERT INTO identity_rejections VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(id, negative.uri.toString(), negative.revision, negative.regionKey))
            }
        }
        if (self == null) db.execSQL("DELETE FROM self_identity WHERE id = 1")
        else db.execSQL("INSERT OR REPLACE INTO self_identity (id, subject_id) VALUES (1, ?)", arrayOf<Any?>(self.subjectId))
    }

    fun remove(uri: Uri, subjectId: Long) = index.withDatabase { db -> db.transaction {
        db.execSQL("DELETE FROM named_references WHERE uri = ? AND subject_id = ?", arrayOf<Any>(uri.toString(), subjectId))
        pruneContentProofs(db)
    } }

    private fun pruneContentProofs(db: SQLiteDatabase) {
        db.execSQL("""
            DELETE FROM named_photo_content WHERE NOT EXISTS (
                SELECT 1 FROM named_references r WHERE r.uri = named_photo_content.uri
                AND r.revision = named_photo_content.revision
            )
        """.trimIndent())
    }

    private fun insertSubject(db: SQLiteDatabase, choice: SubjectSelection.New): Long? {
        val name = choice.name.trim()
        val aliases = choice.aliases.map(String::trim).filter(String::isNotEmpty)
        if (!validSubjectLabel(name) || aliases.size > 12 || !aliases.all(::validSubjectLabel)) return null
        val labels = (listOf(name) + aliases).associateBy { subjectWords(it).joinToString(" ") }.toMutableMap()
        labels[subjectWords(name).joinToString(" ")] = name
        if (labels.keys.any { labelExists(db, it) || datedLabelExists(db, it) }) return null
        val id = db.compileStatement("INSERT INTO named_subjects (name, kind) VALUES (?, ?)").use {
            it.bindString(1, name)
            it.bindString(2, choice.kind.name)
            it.executeInsert()
        }
        labels.forEach { (normalized, label) ->
            db.execSQL("INSERT INTO named_labels VALUES (?, ?, ?)", arrayOf<Any>(normalized, id, label))
        }
        return id
    }

    private fun availableDatedAlias(db: SQLiteDatabase, draft: DatedAliasDraft, choice: SubjectSelection): Boolean {
        val words = subjectWords(draft.label)
        if (!draft.valid || words in setOf(listOf("me"), listOf("myself"))) return false
        val key = words.joinToString(" ")
        if (labelExists(db, key)) return false
        if (choice is SubjectSelection.New && (listOf(choice.name) + choice.aliases).any { subjectWords(it) == words }) return false
        val existing = (choice as? SubjectSelection.Existing)?.id
        return db.rawQuery(
            "SELECT subject_id, first_year, last_year FROM named_dated_aliases WHERE normalized = ? AND first_year <= ? AND last_year >= ?",
            arrayOf(key, draft.lastYear.toString(), draft.firstYear.toString()),
        ).use { rows ->
            while (rows.moveToNext()) {
                if (existing != rows.getLong(0) || draft.firstYear != rows.getInt(1) || draft.lastYear != rows.getInt(2))
                    return@use false
            }
            true
        }
    }

    fun removeDatedAlias(alias: DatedSubjectAlias) = index.withDatabase { db ->
        val period = alias.period
        db.execSQL("DELETE FROM named_dated_aliases WHERE normalized = ? AND first_year = ? AND last_year = ? AND subject_id = ?",
            arrayOf<Any>(subjectWords(period.label).joinToString(" "), period.firstYear, period.lastYear, alias.subjectId))
    }

    private fun datedLabelExists(db: SQLiteDatabase, normalized: String): Boolean =
        db.rawQuery("SELECT 1 FROM named_dated_aliases WHERE normalized = ? LIMIT 1", arrayOf(normalized)).use { it.moveToFirst() }

    private fun insertDatedAlias(db: SQLiteDatabase, alias: DatedSubjectAlias) {
        val draft = alias.period
        db.execSQL("INSERT OR IGNORE INTO named_dated_aliases VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any>(subjectWords(draft.label).joinToString(" "), draft.firstYear, draft.lastYear, alias.subjectId, draft.label))
    }

    private fun readDatedAliases(db: SQLiteDatabase, onlyId: Long? = null): List<DatedSubjectAlias> {
        val scope = if (onlyId == null) "" else " WHERE subject_id = ?"
        val args = onlyId?.let { arrayOf(it.toString()) } ?: emptyArray()
        return db.rawQuery("SELECT subject_id, label, first_year, last_year FROM named_dated_aliases$scope ORDER BY normalized, first_year",
            args).use { rows ->
            buildList {
                while (rows.moveToNext()) add(DatedSubjectAlias(rows.getLong(0),
                    DatedAliasDraft(rows.getString(1), rows.getInt(2), rows.getInt(3))))
            }
        }
    }

    private fun labelExists(db: SQLiteDatabase, label: String): Boolean =
        db.rawQuery("SELECT 1 FROM named_labels WHERE normalized = ?", arrayOf(label)).use { it.moveToFirst() }

    private fun subjectKind(db: SQLiteDatabase, id: Long): SubjectKind? =
        db.rawQuery("SELECT kind FROM named_subjects WHERE id = ?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) SubjectKind.valueOf(it.getString(0)) else null
        }

    private fun setCurrentCollege(db: SQLiteDatabase, id: Long) {
        val latest = db.rawQuery("SELECT subject_id FROM college_history ORDER BY sequence DESC LIMIT 1", emptyArray<String>()).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
        if (latest != id) db.execSQL("INSERT INTO college_history (subject_id) VALUES (?)", arrayOf(id))
    }

    private fun readCollegeHistory(db: SQLiteDatabase): List<Long> =
        db.rawQuery("SELECT subject_id FROM college_history ORDER BY sequence DESC", emptyArray<String>()).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getLong(0)) }
        }

    private fun readSubjects(db: SQLiteDatabase, onlyId: Long? = null): List<NamedSubject> {
        val labels = mutableMapOf<Long, MutableList<String>>()
        val args = onlyId?.let { arrayOf(it.toString()) } ?: emptyArray()
        val labelScope = if (onlyId == null) "" else " WHERE subject_id = ?"
        val subjectScope = if (onlyId == null) "" else " WHERE id = ?"
        db.rawQuery("SELECT subject_id, label FROM named_labels$labelScope ORDER BY normalized", args).use { rows ->
            while (rows.moveToNext()) labels.getOrPut(rows.getLong(0), ::mutableListOf).add(rows.getString(1))
        }
        return db.rawQuery("SELECT id, name, kind FROM named_subjects$subjectScope ORDER BY name COLLATE NOCASE", args).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val id = rows.getLong(0)
                    val name = rows.getString(1)
                    add(NamedSubject(id, name, SubjectKind.valueOf(rows.getString(2)), labels[id].orEmpty().filter { it != name }))
                }
            }
        }
    }

    private fun readReferences(db: SQLiteDatabase, onlyId: Long? = null): List<SubjectReference> {
        val scope = if (onlyId == null) "" else " WHERE subject_id = ?"
        val args = onlyId?.let { arrayOf(it.toString()) } ?: emptyArray()
        return db.rawQuery("SELECT subject_id, uri, revision, left_edge, top_edge, right_edge, bottom_edge FROM named_references$scope", args).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val region = if (rows.isNull(3)) null else PhotoRegion(rows.getFloat(3), rows.getFloat(4), rows.getFloat(5), rows.getFloat(6))
                    add(SubjectReference(rows.getLong(0), Uri.parse(rows.getString(1)), rows.getString(2), region))
                }
            }
        }
    }

    companion object {
        fun createDatedAliases(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE named_dated_aliases (
                    normalized TEXT NOT NULL, first_year INTEGER NOT NULL, last_year INTEGER NOT NULL,
                    subject_id INTEGER NOT NULL, label TEXT NOT NULL,
                    PRIMARY KEY (normalized, first_year, last_year)
                ) WITHOUT ROWID
            """.trimIndent())
        }

        fun createIdentityRejections(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE identity_rejections (
                    subject_id INTEGER NOT NULL, uri TEXT NOT NULL, revision TEXT NOT NULL, region_key TEXT NOT NULL,
                    PRIMARY KEY (subject_id, uri, revision, region_key)
                ) WITHOUT ROWID
            """.trimIndent())
        }

        fun createSelfIdentity(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE self_identity (id INTEGER PRIMARY KEY CHECK (id = 1), subject_id INTEGER)")
        }

        fun createContentProofs(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE named_photo_content (uri TEXT PRIMARY KEY, revision TEXT NOT NULL, digest TEXT NOT NULL) WITHOUT ROWID")
        }

        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE named_subjects (id INTEGER PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL)")
            db.execSQL("CREATE TABLE named_labels (normalized TEXT PRIMARY KEY, subject_id INTEGER NOT NULL, label TEXT NOT NULL) WITHOUT ROWID")
            db.execSQL("""
                CREATE TABLE named_references (
                    subject_id INTEGER NOT NULL, uri TEXT NOT NULL, revision TEXT NOT NULL,
                    left_edge REAL, top_edge REAL, right_edge REAL, bottom_edge REAL,
                    PRIMARY KEY (uri, subject_id)
                ) WITHOUT ROWID
            """.trimIndent())
        }

        fun createCollegeHistory(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE college_history (sequence INTEGER PRIMARY KEY, subject_id INTEGER NOT NULL)")
        }
    }
}
