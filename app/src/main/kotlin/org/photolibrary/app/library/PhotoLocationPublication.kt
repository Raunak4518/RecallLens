package org.photolibrary.app.library

import org.photolibrary.app.subjects.NamedSearch

internal class PhotoLocationPublication(
    private val allowed: () -> Boolean,
    private val galleryAccess: () -> GalleryAccess,
) {
    fun current(result: NamedSearch, expectedAccess: GalleryAccess): NamedSearch {
        if (result !is NamedSearch.NearbyMatches) return result
        if (!allowed()) return NamedSearch.LocationPermission
        if (galleryAccess() != expectedAccess) return NamedSearch.Unresolved
        return result
    }
}
