package org.photolibrary.app.subjects

import android.net.Uri

internal val editableIdentityKinds = setOf(SubjectKind.PERSON, SubjectKind.OBJECT)

internal sealed interface SubjectGroupChange {
    data class Split(val photos: Set<Uri>, val name: String) : SubjectGroupChange
    data class Merge(val destination: Long) : SubjectGroupChange
}

internal data class SubjectGroup(
    val subject: NamedSubject,
    val references: Set<SubjectReference>,
    val rejections: Set<IdentityRejection>,
    val datedAliases: Set<DatedSubjectAlias>,
)

internal data class SubjectGroupEdit(
    val before: Map<Long, SubjectGroup?>,
    val after: Map<Long, SubjectGroup?>,
    val selfBefore: SelfIdentity?,
    val selfAfter: SelfIdentity?,
    val sourceId: Long,
    val focusId: Long,
)

internal sealed interface SubjectGroupResult {
    data class Applied(val edit: SubjectGroupEdit) : SubjectGroupResult
    enum class Rejected : SubjectGroupResult { CHANGED, DUPLICATE_NAME, CONFLICTING_REGIONS, INCOMPATIBLE_GROUP }
}
