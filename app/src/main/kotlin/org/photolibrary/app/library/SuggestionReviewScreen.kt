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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.photolibrary.app.R

internal data class SuggestionActions(
    val open: () -> Unit,
    val close: () -> Unit,
    val scanMore: () -> Unit,
    val choose: (NameSuggestion) -> Unit,
    val chooseGroup: (List<NameSuggestion>) -> Unit,
    val skip: (NameSuggestion) -> Unit,
)

@Composable
internal fun SuggestionReviewScreen(
    state: LibraryState,
    actions: SuggestionActions,
    loadImage: suspend (Uri, Int) -> Bitmap?,
) {
    val review = state.review
    val candidates = remember(review.candidates, state.named) { review.candidates.filterNot { it.isConfirmed(state.named) } }
    val grouped by produceState<List<List<NameSuggestion>>>(emptyList(), candidates) {
        value = withContext(Dispatchers.Default) { groupReviewRegions(candidates) }
    }
    TextButton(onClick = actions.close) { Text(stringResource(R.string.library_back)) }
    Text(stringResource(R.string.suggestions_title), style = MaterialTheme.typography.headlineSmall)
    if (!state.suggestionsAvailable) {
        Text(stringResource(R.string.suggestions_unavailable))
        return
    }
    Text(stringResource(R.string.suggestions_hint), style = MaterialTheme.typography.bodyMedium)
    if (review.scanning || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (review.scanning) Text(stringResource(R.string.suggestions_progress, review.scanned.size, state.photos.size))
    if (review.failed) Text(stringResource(R.string.suggestions_failed), color = MaterialTheme.colorScheme.error)
    if (!state.loading && !review.scanning && !review.failed && candidates.isEmpty()) {
        Text(stringResource(if (state.photos.isEmpty()) R.string.suggestions_no_photos else R.string.suggestions_empty))
    }
    if (!state.loading && !review.scanning && (review.failed || review.scanned.size < state.photos.size)) {
        TextButton(onClick = actions.scanMore) {
            Text(stringResource(if (review.failed) R.string.suggestions_retry else R.string.suggestions_more))
        }
    }
    val candidateSet = remember(candidates) { candidates.toHashSet() }
    val excluded = remember { mutableStateMapOf<NameSuggestion, Boolean>() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (group in grouped) {
            val current = group.filter { it in candidateSet }
            if (current.size > 1) item {
                val selected = current.filterNot { excluded[it] == true }
                Column {
                    Text(stringResource(R.string.similar_regions, current.size), style = MaterialTheme.typography.titleSmall)
                    Button(onClick = { actions.chooseGroup(selected) },
                        enabled = selected.size >= 2 && !state.loading && !state.savingSubject) {
                        Text(stringResource(R.string.suggestions_name_selected, selected.size))
                    }
                }
            }
            items(current) { suggestion ->
                val region = suggestion.detection
                val title = region.label.replaceFirstChar(Char::titlecase)
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        PhotoImage(suggestion.photo.uri, 512, state.imageRevision, loadImage, ContentScale.Fit,
                            Modifier.width(124.dp).height(100.dp), regionPicker = { RegionPicker(it, region.region) })
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(suggestion.photo.name ?: stringResource(R.string.library_unnamed),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            Row {
                                Button(onClick = { actions.choose(suggestion) }) { Text(stringResource(R.string.suggestions_name)) }
                                TextButton(onClick = { actions.skip(suggestion) }) { Text(stringResource(R.string.suggestions_skip)) }
                            }
                        }
                        if (current.size > 1) {
                            Checkbox(checked = excluded[suggestion] != true, enabled = !state.savingSubject,
                                onCheckedChange = { checked -> if (checked) excluded.remove(suggestion) else excluded[suggestion] = true })
                        }
                    }
                }
            }
        }
    }
}
