package org.photolibrary.app.subjects

internal data class DatedAliasDraft(val label: String, val firstYear: Int, val lastYear: Int) {
    val valid get() = validSubjectLabel(label.trim()) && firstYear in 1..9999 && lastYear in firstYear..9999

    fun overlaps(other: DatedAliasDraft): Boolean =
        subjectWords(label) == subjectWords(other.label) && firstYear <= other.lastYear && other.firstYear <= lastYear
}

internal data class DatedSubjectAlias(val subjectId: Long, val period: DatedAliasDraft)
