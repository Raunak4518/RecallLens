package org.photolibrary.app.library

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.photolibrary.app.R
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectGroupChange

internal data class IdentityActions(
    val open: () -> Unit,
    val close: () -> Unit,
    val scan: () -> Unit,
    val limit: (Int) -> Unit,
    val confirm: (RankedPhoto) -> Unit,
    val skip: (RankedPhoto) -> Unit,
    val reject: (RankedPhoto) -> Unit = {},
    val undoRejection: () -> Unit = {},
    val editGroup: (SubjectGroupChange) -> Unit = {},
    val undoGroupEdit: () -> Unit = {},
    val confirmBatch: (List<RankedPhoto>) -> Unit,
)

@Composable
internal fun IdentityReviewScreen(
    state: LibraryState,
    review: IdentityReview,
    actions: IdentityActions,
    loadImage: suspend (Uri, Int) -> Bitmap?,
) {
    val confirmedIds = state.named.photosOf(setOf(review.subject.id))
    val confirmed = state.indexedPhotos.filter { it.uri in confirmedIds }
    val candidates = review.visibleSuggestions(confirmedIds).filter { it.photo.uri in state.indexedUris }
    var selected by remember(review.subject.id, candidates) { mutableStateOf(emptySet<RankedPhoto>()) }
    val ready = !state.loading && !state.savingSubject && !review.editingRejection && !review.scanning
    TextButton(onClick = actions.close) { Text(stringResource(R.string.identity_back)) }
    Text(review.subject.name, style = MaterialTheme.typography.headlineSmall)
    if (review.scanning) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(if (review.total == 0) R.string.identity_preparing else R.string.identity_scan_progress,
            review.checked, review.total))
    }
    if (review.lastRejection != null) {
        TextButton(onClick = actions.undoRejection, enabled = !review.editingRejection) {
            Text(stringResource(R.string.identity_undo_rejection))
        }
    }
    IdentityGroupControls(state, review, confirmed, actions, loadImage)
    state.namingError?.let {
        Text(stringResource(it), color = MaterialTheme.colorScheme.error)
        if (it == R.string.subject_photo_changed) {
            TextButton(onClick = actions.scan, enabled = ready) { Text(stringResource(R.string.suggestions_retry)) }
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.identity_confirmed_count, confirmed.size), style = MaterialTheme.typography.titleMedium) }
        items(confirmed, key = { "confirmed${it.uri}" }) { photo ->
            val region = state.named.referencesIn(photo.uri).firstOrNull { it.subjectId == review.subject.id }?.region
            IdentityPhoto(photo, region, state.imageRevision, loadImage)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.identity_possible), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.identity_hint), style = MaterialTheme.typography.bodyMedium)
                if (selected.isNotEmpty()) {
                    Button(onClick = { actions.confirmBatch(candidates.filter { it in selected }) }, enabled = ready) {
                        Text(stringResource(R.string.identity_confirm_selected, selected.size))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (limit in suggestionLimits) {
                        FilterChip(review.limit == limit, onClick = { actions.limit(limit) },
                            label = { Text(stringResource(R.string.identity_top, limit)) })
                    }
                }
                when {
                    review.error != null -> {
                        Text(stringResource(review.error))
                        TextButton(onClick = actions.scan) { Text(stringResource(R.string.suggestions_retry)) }
                    }
                    review.result == IdentityRanking.NoReference -> Text(stringResource(R.string.identity_no_reference))
                    review.result is IdentityRanking.Candidates && candidates.isEmpty() -> Text(stringResource(R.string.identity_empty))
                }
            }
        }
        items(candidates, key = { "candidate${it.photo.uri}" }) { match ->
            IdentityPhoto(match.photo, match.region, state.imageRevision, loadImage) {
                val selectionLabel = stringResource(R.string.identity_select_candidate,
                    match.photo.name ?: stringResource(R.string.library_unnamed))
                Checkbox(checked = match in selected, enabled = ready,
                    onCheckedChange = { checked -> selected = if (checked) selected + match else selected - match },
                    modifier = Modifier.semantics { contentDescription = selectionLabel })
                Button(onClick = { actions.confirm(match) }, enabled = ready) {
                    Text(stringResource(R.string.identity_confirm))
                }
                TextButton(onClick = { actions.reject(match) }, enabled = !state.savingSubject && !review.editingRejection) {
                    Text(stringResource(R.string.identity_reject))
                }
                TextButton(onClick = { actions.skip(match) }, enabled = !state.savingSubject && !review.editingRejection) {
                    Text(stringResource(R.string.suggestions_skip))
                }
            }
        }
    }
}

@Composable
private fun IdentityPhoto(
    photo: SelectedPhoto,
    region: PhotoRegion?,
    revision: Long,
    loadImage: suspend (Uri, Int) -> Bitmap?,
    buttons: @Composable () -> Unit = {},
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PhotoImage(photo.uri, 512, revision, loadImage, ContentScale.Fit,
                Modifier.width(110.dp).height(90.dp), regionPicker = { RegionPicker(it, region) })
            Column(Modifier.weight(1f)) {
                Text(photo.name ?: stringResource(R.string.library_unnamed), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Column { buttons() }
            }
        }
    }
}
