package org.photolibrary.app.library

import org.photolibrary.app.R

internal fun emptySearchMessage(state: LibraryState): Int {
    val noIndexedPhotos = state.indexedPhotos.isEmpty()
    val searchesText = state.query.isNotBlank() && !state.captureYearOnly && state.eventQuery == null &&
        state.namedSearch == null && state.queryRequirements.isEmpty() && state.searchMode != SearchMode.NAMED
    val textIncomplete = searchesText && state.textIndexed < state.photos.size
    val processing = state.indexing || state.visualIndexing || state.indexingChecked < state.indexingTotal
    if (noIndexedPhotos || textIncomplete) {
        if (state.indexingPaused) return R.string.library_index_paused
        if (state.indexingWaiting) return R.string.index_waiting
        if (!processing && state.indexingFailed > 0) return R.string.library_index_retry_needed
    }
    return when {
        noIndexedPhotos -> R.string.library_wait_for_first_index
        textIncomplete && processing -> R.string.library_wait_for_text
        textIncomplete -> R.string.library_no_indexed_matches
        else -> R.string.library_no_matches
    }
}
