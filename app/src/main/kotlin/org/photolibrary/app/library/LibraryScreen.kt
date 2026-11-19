package org.photolibrary.app.library

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.photolibrary.app.R
import org.photolibrary.app.subjects.DatedSubjectAlias
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.NEARBY_KM
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectSelection
import org.photolibrary.app.subjects.QueryRequirement

@Composable
internal fun LibraryScreen(
    state: LibraryState,
    onAllowPhotos: () -> Unit,
    onAllowPhotoLocations: () -> Unit,
    onChooseFolder: () -> Unit,
    onRefresh: () -> Unit,
    onPermissionSettings: () -> Unit,
    onSearch: (String) -> Unit,
    onPauseIndexing: () -> Unit,
    onResumeIndexing: () -> Unit,
    onAnalyzeClothing: () -> Unit,
    onFindSimilar: (Uri) -> Unit,
    onFindCopies: (Uri) -> Unit = {},
    onClearSimilar: () -> Unit,
    onOpen: (Uri) -> Unit,
    onClose: () -> Unit,
    onRemove: (Uri) -> Unit,
    onEditSubjects: () -> Unit,
    onDismissSubjects: () -> Unit,
    onSaveSubject: (SubjectSelection, PhotoRegion?) -> Unit,
    onSelectSelf: (Long?) -> Unit,
    onRemoveSubject: (Long) -> Unit,
    onRemoveDatedAlias: (DatedSubjectAlias) -> Unit = {},
    suggestions: SuggestionActions,
    identity: IdentityActions,
    loadImage: suspend (Uri, Int) -> Bitmap?,
) {
    val context = LocalContext.current
    val app = context.applicationContext as org.photolibrary.app.PhotoLibraryApplication
    var modelCapabilityVersion by remember(app) { mutableIntStateOf(0) }
    val modelCapabilities = remember(app, modelCapabilityVersion) { app.modelCapabilities }
    val feedback = rememberSearchFeedback(remember(app) { FeedbackStore(app.storage) }, state)
    val hasQuery = state.query.isNotBlank() && state.visualSeed == null
    val rankedPhotos = remember(state.visiblePhotos, state.resultGroups, state.visualScores, state.query, hasQuery, feedback.history, feedback.session) {
        if (!hasQuery || feedback.history.judgments.isEmpty() && feedback.session.isEmpty()) state.visiblePhotos
        else {
            val groups = state.resultGroups
            val tiers = buildMap {
                groups.uncertainDate.forEach { put(it, 8) }
                groups.related.forEach { put(it, 7) }
                groups.nearEventDate.forEach { put(it, 6) }
                groups.possibleDocuments.forEach { put(it, 5) }
                groups.documents.forEach { put(it, 4) }
                groups.rememberedText.forEach { put(it, 3) }
                groups.estimatedColor.forEach { put(it, 2) }
                groups.confirmed.forEach { put(it, 1) }
            }
            FeedbackRanking(feedback.history.judgments + feedback.session, state.query, feedback.history.head).ranked(state.visiblePhotos, state.visualScores) { tiers[it.uri] ?: 0 }
        }
    }
    val opened = state.photos.firstOrNull { it.uri == state.opened }
    BackHandler(enabled = opened != null || state.review.open || state.identity != null || state.visualSeed != null) {
        when {
            state.identity != null -> identity.close()
            state.review.open -> suggestions.close()
            opened != null -> onClose()
            else -> onClearSimilar()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.identity != null) {
                IdentityReviewScreen(state, state.identity, identity, loadImage)
            } else if (state.review.open) {
                SuggestionReviewScreen(state, suggestions, loadImage)
                state.review.selected?.let { selected ->
                    SubjectEditor(selected.photo, state, loadImage, onDismissSubjects, onSaveSubject, onRemoveSubject, selected, onRemoveDatedAlias)
                }
            } else if (opened == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    LearningOptions(feedback)
                    LibrarySourcesMenu(
                        state = state,
                        onAllowPhotos = onAllowPhotos,
                        onChooseFolder = onChooseFolder,
                        onPermissionSettings = onPermissionSettings,
                        onRemoveFolder = onRemove,
                        onRefresh = onRefresh,
                        onSelectSelf = onSelectSelf,
                        onPauseIndexing = onPauseIndexing,
                        onResumeIndexing = onResumeIndexing,
                        onNameSuggestions = suggestions.open,
                        onAnalyzeClothing = onAnalyzeClothing,
                        capabilities = modelCapabilities,
                        modelPackStored = app.modelPackStored,
                        onInstallModelPack = { uri ->
                            val stream = context.contentResolver.openInputStream(uri)
                                ?: error("Could not open the selected model pack")
                            stream.use { app.modelPackInstaller.install(it) }
                        },
                        onRemoveModelPack = { app.modelPackInstaller.remove() },
                        onCapabilitiesChanged = { modelCapabilityVersion++ },
                    )
                }
                FeedbackReceipt(feedback, state.query) { uri -> if (state.photos.any { it.uri == uri }) onOpen(uri) }
                PhotoGrid(state.copy(visiblePhotos = rankedPhotos), feedback, state.visiblePhotos, onAllowPhotos, onChooseFolder, onSearch, onOpen, onAllowPhotoLocations, identity.open,
                    onClearSimilar, onResumeIndexing, onRefresh, loadImage)
            } else {
                TextButton(onClick = onClose) { Text(stringResource(R.string.library_back)) }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(opened.title(), style = MaterialTheme.typography.titleLarge)
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.notice?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    PhotoImage(
                        uri = opened.uri,
                        maxEdge = 1600,
                        revision = state.imageRevision,
                        loadImage = loadImage,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).aspectRatio(4f / 3f),
                        description = opened.title(),
                    )
                    val names = state.named.subjectsIn(opened.uri)
                    Text(stringResource(R.string.subject_detail_heading), style = MaterialTheme.typography.titleMedium)
                    if (names.isNotEmpty()) {
                        Text(names.joinToString(", ") { it.name })
                    }
                    Button(onClick = onEditSubjects, enabled = !state.loading && !state.savingSubject) {
                        Text(stringResource(R.string.subject_open))
                    }
                    if (state.visualAvailable) {
                        TextButton(onClick = { onFindSimilar(opened.uri) }, enabled = !state.loading) {
                            Text(stringResource(R.string.library_find_similar))
                        }
                        TextButton(onClick = { onFindCopies(opened.uri) }, enabled = !state.loading) {
                            Text(stringResource(R.string.library_find_copies))
                        }
                    }
                    if (opened.origin == PhotoOrigin.INDIVIDUAL) {
                        TextButton(onClick = { onRemove(opened.uri) }, enabled = !state.loading) {
                            Text(stringResource(R.string.library_remove))
                        }
                    }
                }
                if (state.naming) {
                    SubjectEditor(opened, state, loadImage, onDismissSubjects, onSaveSubject, onRemoveSubject, onRemoveDatedAlias = onRemoveDatedAlias)
                }
            }
        }
    }
}

@Composable
private fun PhotoGrid(
    state: LibraryState,
    feedback: SearchFeedback,
    baseline: List<SelectedPhoto>,
    onAllowPhotos: () -> Unit,
    onChooseFolder: () -> Unit,
    onSearch: (String) -> Unit,
    onOpen: (Uri) -> Unit,
    onAllowPhotoLocations: () -> Unit,
    onReviewMatches: () -> Unit,
    onClearSimilar: () -> Unit,
    onResumeIndexing: () -> Unit,
    onRetryIndexing: () -> Unit,
    loadImage: suspend (Uri, Int) -> Bitmap?,
) {
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.notice?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    if (state.photos.isNotEmpty()) {
        Text(stringResource(R.string.library_indexed_count, state.indexedPhotos.size, state.photos.size),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
    }

    if (state.photos.isEmpty()) {
        if (!state.loading) {
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.galleryAccess == GalleryAccess.NONE && state.folders.isEmpty()) {
                    Text(stringResource(R.string.library_setup_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.library_setup_hint))
                    Button(onClick = onAllowPhotos) { Text(stringResource(R.string.library_allow_photos)) }
                    TextButton(onClick = onChooseFolder) { Text(stringResource(R.string.library_choose_folder)) }
                } else {
                    Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleLarge)
                    if (state.galleryAccess == GalleryAccess.SELECTED) {
                        Button(onClick = onAllowPhotos) { Text(stringResource(R.string.library_manage_selection)) }
                    }
                }
            }
        }
        return
    }

    val seed = state.visualSeed
    if (seed != null) {
        val title = state.photos.firstOrNull { it.uri == seed }?.title() ?: stringResource(R.string.library_unnamed)
        val titleId = if (state.visualSeedMode == VisualSeedMode.COPIES) R.string.library_copies_title else R.string.library_similar_title
        Text(stringResource(titleId, title), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onClearSimilar) { Text(stringResource(R.string.library_similar_clear)) }
        if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.visiblePhotos.isEmpty() && !state.searching) Text(stringResource(R.string.library_no_matches))
        PhotoResultsGrid(state, feedback, baseline, onOpen, loadImage)
        return
    }

    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onSearch,
            label = { Text(stringResource(R.string.library_prompt_label)) },
            placeholder = { Text(stringResource(R.string.library_prompt_example)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        if (state.query.isNotEmpty()) {
            TextButton(onClick = { onSearch("") }) { Text(stringResource(R.string.library_clear_search)) }
        }
    }

    if (state.galleryAccess == GalleryAccess.SELECTED) {
        Text(stringResource(R.string.library_limited_notice), style = MaterialTheme.typography.bodyMedium)
    }

    val namedStatus = when (state.namedSearch) {
        is NamedSearch.Matches -> if (state.resultGroups.estimatedColor.isNotEmpty() || state.resultGroups.related.isNotEmpty()) {
            R.string.subject_estimated_color_matches
        } else R.string.subject_confirmed_matches
        is NamedSearch.Nearby -> R.string.subject_location_checking
        is NamedSearch.NearbyMatches -> null
        NamedSearch.LocationPermission -> R.string.subject_location_permission
        NamedSearch.NoPlaceCoordinates -> R.string.subject_location_missing
        NamedSearch.Unknown -> R.string.subject_unknown
        NamedSearch.Unresolved -> R.string.subject_search_unresolved
        null -> null
    }
    val busy = state.searching || state.restoringIndex
    val partial = state.queryRequirements.isNotEmpty()
    val locationStatus = when (state.queryLocation) {
        is NamedSearch.Nearby -> R.string.subject_location_checking
        NamedSearch.LocationPermission -> R.string.subject_location_permission
        NamedSearch.NoPlaceCoordinates -> R.string.subject_location_missing
        NamedSearch.Unresolved -> R.string.subject_search_unresolved
        else -> null
    }
    val status = when {
        !state.loading && locationStatus != null -> locationStatus
        partial && state.searching -> R.string.library_visual_searching
        partial -> R.string.query_partial_guidance
        !state.loading && namedStatus != null -> namedStatus
        state.searching -> R.string.library_visual_searching
        else -> null
    }
    val missingEvidence = buildSet {
        for (requirement in state.queryRequirements) {
            add(when (requirement) {
                QueryRequirement.SELF_IDENTITY -> R.string.query_self_required
                QueryRequirement.CONFIRMED_NAME -> R.string.subject_unknown
                QueryRequirement.PERSON_CLOTHING_RELATION -> R.string.query_clothing_required
                QueryRequirement.SPATIAL_RELATION -> R.string.query_spatial_required
                QueryRequirement.LOGICAL_RELATION -> R.string.query_logic_required
                QueryRequirement.PLACE_REFERENCE -> R.string.query_place_required
                QueryRequirement.LOCATION_VERIFICATION -> R.string.query_location_unverified
                QueryRequirement.CAPTURE_YEAR -> R.string.capture_year_unsupported
                QueryRequirement.SUPPORTED_QUERY -> R.string.subject_search_unresolved
            })
        }
    }
    status?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
    var showDetails by remember(state.query) { mutableStateOf(false) }
    if (missingEvidence.any { it != status } && !state.searching) {
        TextButton(onClick = { showDetails = true }) { Text(stringResource(R.string.library_match_details)) }
    }
    if (showDetails && missingEvidence.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(stringResource(R.string.library_match_details)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    missingEvidence.forEach { Text(stringResource(it)) }
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text(stringResource(R.string.library_done)) } },
        )
    }
    state.captureYearFilter?.let { filter ->
        val label = when (filter.relation) {
            CaptureYearRelation.IN -> R.string.capture_year_in
            CaptureYearRelation.BEFORE -> R.string.capture_year_before
            CaptureYearRelation.AFTER -> R.string.capture_year_after
        }
        Text(filter.relativeLabel?.let { stringResource(R.string.feedback_captured_relative, it) } ?: stringResource(label, filter.year), style = MaterialTheme.typography.bodyMedium)
    }
    state.eventQuery?.let { query ->
        val message = if (query.hasCalendarDates) R.string.library_event_search_dates else R.string.library_event_search_visual
        Text(stringResource(message, query.event.name), style = MaterialTheme.typography.bodyMedium)
    }
    if (state.matchedIdentity != null) {
        TextButton(onClick = onReviewMatches) { Text(stringResource(R.string.identity_open)) }
    }
    if (state.namedSearch is NamedSearch.NearbyMatches || state.queryLocation is NamedSearch.NearbyMatches) {
        Text(stringResource(R.string.subject_location_radius, NEARBY_KM), style = MaterialTheme.typography.bodyMedium)
    }
    if (!state.loading && (state.namedSearch == NamedSearch.LocationPermission || state.queryLocation == NamedSearch.LocationPermission)) {
        Button(onClick = onAllowPhotoLocations) { Text(stringResource(R.string.subject_location_allow)) }
    }
    if (state.visiblePhotos.isEmpty()) {
        if (!state.loading && !busy && (namedStatus == null || state.namedSearch is NamedSearch.Matches)) {
            val message = emptySearchMessage(state)
            Text(stringResource(message), modifier = Modifier.padding(top = 12.dp))
            if (message == R.string.library_index_paused) {
                TextButton(onClick = onResumeIndexing) { Text(stringResource(R.string.index_resume)) }
            }
            if (message == R.string.library_index_retry_needed) {
                TextButton(onClick = onRetryIndexing) { Text(stringResource(R.string.library_index_retry)) }
            }
        }
        return
    }

    var showVersions by remember(state.query) { mutableStateOf(false) }
    val hidden = remember(state.resultGroups.versionFamilies, state.visiblePhotos) {
        hiddenVersions(state.resultGroups.versionFamilies, state.visiblePhotos.map { it.uri })
    }
    if (hidden.isNotEmpty()) {
        TextButton(onClick = { showVersions = !showVersions }) {
            Text(if (showVersions) stringResource(R.string.library_hide_versions) else
                stringResource(R.string.library_show_versions, hidden.size))
        }
    }
    val shown = if (showVersions || hidden.isEmpty()) state else state.copy(visiblePhotos = state.visiblePhotos.filterNot { it.uri in hidden })
    PhotoResultsGrid(shown, feedback, baseline, onOpen, loadImage)
}

@Composable
private fun PhotoResultsGrid(state: LibraryState, feedback: SearchFeedback, baseline: List<SelectedPhoto>, onOpen: (Uri) -> Unit, loadImage: suspend (Uri, Int) -> Bitmap?) {
    val baselineRanks = remember(baseline) { baseline.mapIndexed { index, photo -> photo.uri to index }.toMap() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val groups = state.resultGroups
        val labeled = state.query.isNotBlank() || state.visualSeed != null
        val possibleCopy = state.visualSeed != null && state.visualSeedMode == VisualSeedMode.COPIES
        fun resultLabel(uri: Uri): Int? = when {
            possibleCopy -> R.string.results_possible_copy
            uri in groups.uncertainDate -> R.string.results_uncertain_date
            uri in groups.confirmed -> R.string.results_confirmed
            uri in groups.estimatedColor -> R.string.results_estimated_color
            uri in groups.rememberedText -> R.string.results_text
            uri in groups.documents -> R.string.results_document
            uri in groups.possibleDocuments -> R.string.results_document_possible
            uri in groups.nearEventDate -> R.string.results_event_date
            uri in groups.related && state.eventQuery != null -> R.string.results_event_visual
            uri in groups.related -> if (state.queryRequirements.isNotEmpty()) R.string.results_partial else R.string.results_related
            else -> null
        }
        items(state.visiblePhotos, key = { it.uri.toString() }) { photo ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(photo.uri) },
            ) {
                PhotoImage(
                    uri = photo.uri,
                    maxEdge = 360,
                    revision = state.imageRevision,
                    loadImage = loadImage,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
                Column(Modifier.padding(10.dp)) {
                    Text(photo.title(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.query.isNotBlank() && state.visualSeed == null && state.namedSearch == null) ResultFeedbackControls(feedback, state.query, photo) {
                        FeedbackFeatures.from(normalizedFeedbackQuery(state.query), photo,
                            baselineRanks[photo.uri] ?: 0, resultLabel(photo.uri) ?: 0, state.visualScores[photo.uri])
                    }
                    if (labeled) resultLabel(photo.uri)?.let { label ->
                        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedPhoto.title(): String = name ?: stringResource(R.string.library_unnamed)
