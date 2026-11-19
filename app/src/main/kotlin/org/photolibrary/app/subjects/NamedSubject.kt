package org.photolibrary.app.subjects

import android.net.Uri
import org.photolibrary.app.index.normalizePhotoText

internal enum class SubjectKind { OBJECT, PERSON, GROUP, DOCUMENT, CLOTHING, PLACE, ORGANIZATION, MEME, OTHER }

internal data class PhotoRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f && left < right && top < bottom)
    }
}

internal data class NamedSubject(val id: Long, val name: String, val kind: SubjectKind, val aliases: List<String>)

internal sealed interface SubjectSelection {
    val currentCollege: Boolean
    val datedAlias: DatedAliasDraft?

    data class New(
        val name: String, val kind: SubjectKind, val aliases: List<String>,
        override val currentCollege: Boolean = false, override val datedAlias: DatedAliasDraft? = null,
    ) : SubjectSelection
    data class Existing(
        val id: Long, override val currentCollege: Boolean = false, override val datedAlias: DatedAliasDraft? = null,
    ) : SubjectSelection
}

internal data class SubjectReference(val subjectId: Long, val uri: Uri, val revision: String, val region: PhotoRegion?)

internal enum class Confirmation { SAVED, DUPLICATE_NAME, MISSING_SUBJECT, PHOTO_CHANGED, INVALID_COLLEGE, INVALID_DATED_ALIAS }

internal data class SelfIdentity(val subjectId: Long?)

internal data class NamedLibrary(
    val subjects: List<NamedSubject> = emptyList(),
    val references: List<SubjectReference> = emptyList(),
    val collegeHistory: List<Long> = emptyList(),
    val selfIdentity: SelfIdentity? = null,
    val datedAliases: List<DatedSubjectAlias> = emptyList(),
    private val aliasYear: Int? = null,
) {
    private val referencesByPhoto = references.groupBy { it.uri }
    private val subjectsById = subjects.associateBy { it.id }
    private val photosBySubject = references.groupBy({ it.subjectId }, { it.uri }).mapValues { it.value.toSet() }
    private val labelEntries = subjects.flatMap { subject -> (listOf(subject.name) + subject.aliases).map { it to subject.id } }
    val selfSubjectId: Long? = if (selfIdentity != null) {
        selfIdentity.subjectId?.takeIf { subjectsById[it]?.kind == SubjectKind.PERSON }
    } else subjects.filter { subject ->
        subject.kind == SubjectKind.PERSON &&
            (listOf(subject.name) + subject.aliases).any { subjectWords(it) in selfLabels }
    }.singleOrNull()?.id
    internal val labels = buildMap {
        labelEntries.forEach { (label, id) -> put(subjectWords(label), id) }
        if (selfIdentity != null) selfLabels.forEach { remove(it) }
        selfSubjectId?.let { self ->
            for (label in selfLabels) {
                if (selfIdentity != null) put(label, self) else putIfAbsent(label, self)
            }
        }
        collegeHistory.firstOrNull()?.let { current ->
            for (label in listOf("my college", "my current college", "my new college")) put(subjectWords(label), current)
            for (label in listOf("my previous college", "my old college", "my former college")) {
                val previous = collegeHistory.firstOrNull { it != current }
                if (previous != null) put(subjectWords(label), previous) else remove(subjectWords(label))
            }
        }
        if (aliasYear != null) {
            for ((label, periods) in datedAliases.groupBy { subjectWords(it.period.label) }) {
                remove(label)
                val ids = periods.filter { aliasYear in it.period.firstYear..it.period.lastYear }
                    .map { it.subjectId }.distinct()
                ids.singleOrNull()?.takeIf { it in subjectsById }?.let { put(label, it) }
            }
        }
    }

    fun atAliasYear(year: Int): NamedLibrary = if (datedAliases.isEmpty() || aliasYear == year) this else copy(aliasYear = year)

    fun hasInactiveDatedAlias(words: List<String>): Boolean =
        aliasYear != null && words !in labels && datedAliases.any { subjectWords(it.period.label) == words }

    fun place(words: List<String>): Long? = labels[words]?.takeIf { subjectsById[it]?.kind == SubjectKind.PLACE }

    fun subjectsIn(uri: Uri): List<NamedSubject> =
        referencesByPhoto[uri].orEmpty().mapNotNull { subjectsById[it.subjectId] }

    fun referencesIn(uri: Uri): List<SubjectReference> = referencesByPhoto[uri].orEmpty()

    fun kindOf(id: Long): SubjectKind? = subjectsById[id]?.kind

    fun subjectFor(words: List<String>): NamedSubject? = labels[words]?.let(subjectsById::get)

    fun photosOf(ids: Set<Long>): Set<Uri> =
        ids.map { photosBySubject[it].orEmpty() }.reduceOrNull(Set<Uri>::intersect).orEmpty()
}

private val selfLabels = setOf(listOf("me"), listOf("myself"))

internal fun subjectWords(text: String): List<String> =
    normalizePhotoText(text).takeIf(String::isNotEmpty)?.split(' ').orEmpty()

internal fun validSubjectLabel(label: String): Boolean =
    label.length in 1..80 && subjectWords(label).any { word -> word.any(Char::isLetterOrDigit) }
