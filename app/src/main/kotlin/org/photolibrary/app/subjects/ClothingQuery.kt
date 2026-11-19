package org.photolibrary.app.subjects

import org.photolibrary.app.detection.GarmentColor

internal data class ClothingQuery(val personId: Long, val color: GarmentColor, val named: NamedSearch)

internal fun clothingQuery(query: String, library: NamedLibrary): ClothingQuery? {
    val words = subjectWords(query)
    val wearing = words.indexOf("wearing")
    if (wearing <= 0 || wearing + 1 >= words.size) return null
    val person = library.subjectFor(words.take(wearing))?.takeIf { it.kind == SubjectKind.PERSON } ?: return null
    val color = GarmentColor.entries.firstOrNull { it.name.equals(words[wearing + 1], ignoreCase = true) } ?: return null
    val tail = words.drop(wearing + 2)
    if (tail.isNotEmpty() && tail.first() !in setOf("and", "with", "near", "around", "in")) return null
    val named = searchNamed((words.take(wearing) + tail).joinToString(" "), library, true) ?: return null
    if (named !is NamedSearch.Matches && named !is NamedSearch.Nearby) return null
    return ClothingQuery(person.id, color, named)
}
