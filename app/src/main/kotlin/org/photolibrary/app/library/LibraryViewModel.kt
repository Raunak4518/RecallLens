package org.photolibrary.app.library

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.photolibrary.app.PhotoLibraryApplication
import org.photolibrary.app.R
import org.photolibrary.app.index.IndexPhoto
import org.photolibrary.app.index.PhotoIndexUnavailable
import org.photolibrary.app.index.PhotoProcessingException
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.semantic.PhotoEmbedding
import org.photolibrary.app.subjects.planPhotoQuery
import org.photolibrary.app.subjects.DatedSubjectAlias
import org.photolibrary.app.subjects.SubjectGroupChange
import org.photolibrary.app.subjects.SubjectGroupEdit
import org.photolibrary.app.subjects.SubjectGroupResult
import org.photolibrary.app.subjects.Confirmation
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.NamedSearch
import org.photolibrary.app.subjects.NamedSubject
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectSelection
import org.photolibrary.app.subjects.searchNamed
import org.photolibrary.app.subjects.subjectForReview
import org.photolibrary.app.subjects.QueryRequirement
import org.photolibrary.app.subjects.PhotoResultGroups
import org.photolibrary.app.subjects.groupPhotoResults
import org.photolibrary.app.index.IndexingStage
import org.photolibrary.app.index.LocalPhotoIndexer

internal enum class SearchMode { CONTENT, TEXT, NAMED }

internal data class LibraryState(
    val galleryAccess: GalleryAccess = GalleryAccess.NONE,
    val folders: List<PhotoFolder> = emptyList(),
    val unavailableVolumes: Set<String> = emptySet(),
    val photos: List<SelectedPhoto> = emptyList(),
    val indexedPhotos: List<SelectedPhoto> = emptyList(),
    val indexedUris: Set<Uri> = emptySet(),
    val visiblePhotos: List<SelectedPhoto> = emptyList(),
    val query: String = "",
    val visualSeed: Uri? = null,
    val visualSeedMode: VisualSeedMode = VisualSeedMode.SIMILAR,
    val eventQuery: EventPhotoQuery? = null,
    val searchingMarksheets: Boolean = false,
    val searchMode: SearchMode = SearchMode.TEXT,
    val visualAvailable: Boolean = false,
    val named: NamedLibrary = NamedLibrary(),
    val namedSearch: NamedSearch? = null,
    val queryRequirements: Set<QueryRequirement> = emptySet(),
    val queryLocation: NamedSearch? = null,
    val captureYearFilter: CaptureYearFilter? = null,
    val captureYearOnly: Boolean = false,
    val resultGroups: PhotoResultGroups = PhotoResultGroups(),
    val visualScores: Map<Uri, Float> = emptyMap(),
    val suggestionsAvailable: Boolean = false,
    val review: SuggestionReview = SuggestionReview(),
    val identity: IdentityReview? = null,
    val matchedIdentity: NamedSubject? = null,
    val naming: Boolean = false,
    val savingSubject: Boolean = false,
    @get:StringRes val namingError: Int? = null,
    val opened: Uri? = null,
    val loading: Boolean = true,
    val restoringIndex: Boolean = false,
    val indexing: Boolean = false,
    val visualIndexing: Boolean = false,
    val searching: Boolean = false,
    val clothingAnalyzing: Boolean = false,
    val clothingChecked: Int = 0,
    val clothingTotal: Int = 0,
    val clothingUncertain: Int? = null,
    val indexingPaused: Boolean = false,
    val indexingChecked: Int = 0,
    val indexingTotal: Int = 0,
    val indexingFailed: Int = 0,
    val indexingWaiting: Boolean = false,
    val textIndexed: Int = 0,
    val visualIndexed: Int = 0,
    val imageRevision: Long = 0,
    @get:StringRes val notice: Int? = null,
)

internal class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application as PhotoLibraryApplication
    private val library = context.library
    private val index = context.textIndex
    private val searchableText = mutableMapOf<Uri, PhotoText>()
    private val cloudText = mutableMapOf<Uri, PhotoText>()
    private val embeddings = mutableMapOf<Uri, PhotoEmbedding>()
    @Volatile private var lexicalPostings = LexicalPostings()
    private val encodedQueries = android.util.LruCache<String, PhotoEmbedding>(32)
    private var textWarmupJob: Job? = null
    private var inputSearchJob: Job? = null
    private var indexJob: Job? = null
    private var visualJob: Job? = null
    private var suggestionJob: Job? = null
    private var identityJob: Job? = null
    private var identityMaintenanceJob: Job? = null
    private var textCacheGeneration = 0L
    private var visualCacheGeneration = 0L
    private var clothingJob: Job? = null
    private var sourceObserver: PhotoSourceObserver? = null
    private val operations = Mutex()
    private val mutableState = MutableStateFlow(
        LibraryState(
            searchMode = SearchMode.CONTENT,
            visualAvailable = context.clip.available,
            suggestionsAvailable = context.regionModels.available,
        ),
    )
    val state = mutableState.asStateFlow()
    private val queries = PhotoQueryExecution(viewModelScope) { PhotoQuerySnapshot.capture(state.value) }
    private val cacheRestoration = PhotoCacheRestoration { state.value }
    private val locationPublication = PhotoLocationPublication(context.photoLocations::permissionGranted, library::galleryAccess)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.indexing.restoreProgress()
            } catch (_: PhotoIndexUnavailable) {
                context.indexing.unavailable()
            }
        }
        viewModelScope.launch {
            context.indexScheduler.states().collectLatest { work ->
                mutableState.update { current ->
                    current.copy(indexingWaiting = !current.indexingPaused &&
                        work.none { it.state == WorkInfo.State.RUNNING } &&
                        work.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED })
                }
            }
        }
        viewModelScope.launch {
            context.indexing.progress.collect { progress ->
                val checkpoint = progress.checkpoint
                mutableState.update { it.copy(
                    indexingPaused = checkpoint.paused,
                    indexingChecked = checkpoint.cursor,
                    indexingTotal = checkpoint.total,
                    indexingFailed = checkpoint.failed,
                    indexing = !checkpoint.paused && !checkpoint.finished && checkpoint.stage == IndexingStage.TEXT,
                    visualIndexing = !checkpoint.paused && !checkpoint.finished && checkpoint.stage == IndexingStage.VISUAL && it.visualAvailable,
                    textIndexed = searchableText.size,
                    visualIndexed = embeddings.size,
                    notice = if (progress.unavailable) R.string.library_index_unavailable else it.notice,
                ) }
                if (progress.generation > 0 && !state.value.loading) {
                    val snapshot = PhotoSourceSnapshot.capture(state.value)
                    try {
                        val sources = withContext(Dispatchers.IO) { library.list() }
                        if (!snapshot.matches(state.value)) return@collect
                        val fresh = sources.photos
                        if (sources.scope != state.value.galleryAccess || fresh != state.value.photos || sources.folders != state.value.folders ||
                            sources.unavailableVolumes != state.value.unavailableVolumes) {
                            reload(scheduleIndexing = false)
                            return@collect
                        }
                        val inputs = fresh.map { IndexPhoto(it.uri, it.sourceRevision) }
                        val unavailable = sources.unavailableSources
                        if (context.clip.available && !restoreChanges(snapshot, context.visualIndex, visualJob, embeddings,
                                inputs, unavailable, progress.visualUpdated.filterValues { it > visualCacheGeneration }.keys)) return@collect
                        visualCacheGeneration = progress.generation
                        if (!restoreChanges(snapshot, index, indexJob, searchableText, inputs, unavailable,
                                progress.textUpdated.filterValues { it > textCacheGeneration }.keys)) return@collect
                        textCacheGeneration = progress.generation
                    } catch (_: PhotoIndexUnavailable) {
                        if (snapshot.matches(state.value)) mutableState.update { it.copy(notice = R.string.library_index_unavailable) }
                    }
                }
            }
        }
    }

    private suspend fun <Output : Any> restoreChanges(snapshot: PhotoSourceSnapshot, source: LocalPhotoIndexer<Output>,
        restoreJob: Job?, cache: MutableMap<Uri, Output>, inputs: List<IndexPhoto>, unavailable: UnavailableSources,
        changed: Set<Uri>): Boolean {
        if (changed.isEmpty()) return true
        return cacheRestoration.restore(snapshot, read = {
            restoreJob?.join()
            withContext(Dispatchers.IO) { source.readCached(inputs, changed, unavailable) }
        }, apply = { saved ->
            changed.forEach(cache::remove)
            cache.putAll(saved)
            mutableState.update { withIndexedPhotos(it.copy(textIndexed = searchableText.size, visualIndexed = embeddings.size)) }
            prepareTextSearch()
            refreshSearchResults()
        })
    }

    fun pauseIndexing() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                context.indexing.pause(true)
                context.indexScheduler.cancel()
            }
        }
    }

    fun resumeIndexing() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                context.indexing.pause(false)
                context.indexScheduler.request()
            }
        }
    }

    fun refresh() = reload()

    fun recheckPhotos() = reload(recheckIndex = true)

    fun startObserving() {
        if (sourceObserver == null) {
            sourceObserver = PhotoSourceObserver(context, { reload(recheckIndex = state.value.photos.any { it.sourceRevision == null }) }).also { observer ->
                observer.folders(state.value.folders.map { it.uri })
            }
        }
    }

    fun stopObserving() {
        sourceObserver?.close()
        sourceObserver = null
        if (textWarmupJob?.isActive == true) {
            textWarmupJob?.cancel()
            textWarmupJob = null
        }
    }

    private fun prepareTextSearch() {
        val current = state.value
        if (sourceObserver == null || textWarmupJob != null || embeddings.isEmpty() ||
            current.searchMode != SearchMode.CONTENT || current.query.isNotBlank() || current.visualSeed != null) return
        textWarmupJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                context.clip.prepareTextSearch()
            } catch (_: PhotoProcessingException) {
                Unit
            }
        }
    }

    override fun onCleared() {
        stopObserving()
        queries.cancel()
        super.onCleared()
    }

    fun addFolder(uri: Uri) = reload {
        if (library.addFolder(uri)) null else R.string.library_folder_failed
    }

    fun remove(uri: Uri) = reload {
        if (library.remove(uri)) null else R.string.library_remove_failed
    }

    fun search(query: String) {
        updateSearch(query, state.value.searchMode)
    }

    fun selectSelf(subjectId: Long?) = reload {
        try {
            if (context.photoSubjects.setSelf(subjectId)) null else R.string.self_identity_error
        } catch (_: PhotoIndexUnavailable) {
            R.string.subject_index_unavailable
        }
    }

    fun analyzeClothing() {
        if (state.value.loading || state.value.clothingAnalyzing || !context.clothingSearch.available) return
        val current = state.value
        val photos = context.clothingSearch.confirmedPhotos(current.named, current.photos)
        mutableState.update { it.copy(clothingAnalyzing = true, clothingChecked = 0, clothingTotal = photos.size, clothingUncertain = null) }
        clothingJob = viewModelScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    context.clothingSearch.analyze(photos) { checked, total ->
                        withContext(Dispatchers.Main.immediate) {
                            mutableState.update { it.copy(clothingChecked = checked, clothingTotal = total) }
                        }
                    }
                }
                val uncertain = summary.evidence.values.count { records ->
                    records.isEmpty() || records.any { it is org.photolibrary.app.detection.PersonGarmentEvidence.Missing }
                } + summary.unavailablePhotos
                mutableState.update { it.copy(clothingAnalyzing = false, clothingUncertain = uncertain) }
                scheduleSearch()
            } catch (_: PhotoIndexUnavailable) {
                mutableState.update { it.copy(clothingAnalyzing = false, notice = R.string.library_index_unavailable) }
            } catch (_: PhotoProcessingException) {
                mutableState.update { it.copy(clothingAnalyzing = false, notice = R.string.identity_failed) }
            }
        }
    }

    fun searchMode(mode: SearchMode) {
        updateSearch(state.value.query, mode)
    }

    private fun updateSearch(query: String, mode: SearchMode) {
        inputSearchJob?.cancel()
        queries.cancel()
        mutableState.update { current ->
            clearSearchResults(current.copy(query = query, visualSeed = null, searchMode = mode, notice = null))
        }
        if (query.isBlank()) return
        refreshSearchResults(debounceMillis = 110)
    }

    private fun refreshSearchResults(debounceMillis: Long = 0) {
        inputSearchJob?.cancel()
        val submitted = state.value
        if (submitted.loading) return
        if (submitted.visualSeed != null) {
            scheduleVisualSearch(ifIdle = true)
            return
        }
        if (submitted.query.isBlank()) return
        inputSearchJob = viewModelScope.launch {
            if (debounceMillis > 0) delay(debounceMillis)
            val current = state.value
            if (current.query != submitted.query || current.searchMode != submitted.searchMode ||
                current.visualSeed != null || current.loading) return@launch
            val snapshot = PhotoQuerySnapshot.capture(current)
            val text = searchableText.toMap()
            val cloud = cloudText.toMap()
            val results = withContext(Dispatchers.Default) { withResults(current, text, cloud) }
            if (snapshot != PhotoQuerySnapshot.capture(state.value)) return@launch
            if (results.searchingMarksheets) queries.cancel()
            mutableState.update { live -> live.copy(visiblePhotos = results.visiblePhotos, resultGroups = results.resultGroups, visualScores = emptyMap(),
                namedSearch = results.namedSearch, queryRequirements = results.queryRequirements, queryLocation = results.queryLocation,
                matchedIdentity = results.matchedIdentity, eventQuery = results.eventQuery, searchingMarksheets = results.searchingMarksheets,
                captureYearFilter = results.captureYearFilter,
                captureYearOnly = results.captureYearOnly, searching = false) }
            scheduleSearch()
        }
    }

    fun open(uri: Uri) {
        mutableState.update { if (uri in it.indexedUris) it.copy(opened = uri, naming = false, namingError = null) else it }
    }

    fun findSimilar(uri: Uri) = findFromPhoto(uri, VisualSeedMode.SIMILAR)

    fun findCopies(uri: Uri) = findFromPhoto(uri, VisualSeedMode.COPIES)

    private fun findFromPhoto(uri: Uri, mode: VisualSeedMode) {
        val current = state.value
        if (current.loading || !current.visualAvailable || current.photos.none { it.uri == uri }) return
        val embedding = embeddings[uri]
        if (embedding == null || mode == VisualSeedMode.COPIES && embedding.copySignature == null) {
            mutableState.update { it.copy(notice = R.string.library_similar_wait_for_index) }
            return
        }
        queries.cancel()
        mutableState.update { clearSearchResults(it.copy(query = "", searchMode = SearchMode.CONTENT, visualSeed = uri,
            visualSeedMode = mode, opened = null, naming = false, notice = null)) }
        scheduleVisualSearch()
    }

    fun clearSimilar() = updateSearch("", SearchMode.CONTENT)

    fun close() {
        mutableState.update { it.copy(opened = null, naming = false) }
    }

    fun openReview() {
        mutableState.update { it.copy(review = it.review.copy(open = true)) }
        if (state.value.review.scanned.isEmpty()) scanMoreSuggestions()
    }

    fun closeReview() {
        if (state.value.savingSubject) return
        suggestionJob?.cancel()
        mutableState.update { it.copy(review = it.review.copy(open = false, scanning = false, selected = null), namingError = null) }
    }

    fun chooseSuggestion(suggestion: NameSuggestion) {
        mutableState.update { current ->
            if (!current.review.open || current.loading || current.savingSubject ||
                suggestion !in current.review.candidates || suggestion.isConfirmed(current.named)
            ) current else current.copy(review = current.review.copy(selected = suggestion), namingError = null)
        }
    }

    fun skipSuggestion(suggestion: NameSuggestion) {
        mutableState.update { it.copy(review = it.review.copy(candidates = it.review.candidates - suggestion)) }
    }

    fun scanMoreSuggestions() {
        val current = state.value
        if (!current.review.open || !current.suggestionsAvailable || current.loading || current.review.scanning) return
        val photos = current.indexedPhotos.asSequence().filterNot { it.uri in current.review.scanned }.take(24).toList()
        if (photos.isEmpty()) return
        mutableState.update { it.copy(review = it.review.copy(scanning = true, failed = false)) }
        suggestionJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.photoDetection.scan(photos) { uri, candidates ->
                        withContext(Dispatchers.Main.immediate) {
                            mutableState.update {
                                it.copy(review = it.review.copy(scanned = it.review.scanned + uri, candidates = it.review.candidates + candidates))
                            }
                        }
                    }
                }
                mutableState.update { it.copy(review = it.review.copy(scanning = false)) }
            } catch (_: PhotoProcessingException) {
                mutableState.update { it.copy(review = it.review.copy(scanning = false, failed = true)) }
            }
        }
    }

    fun editSubjects() {
        mutableState.update { it.copy(naming = true, namingError = null) }
    }

    fun dismissSubjects() {
        mutableState.update { if (it.savingSubject) it else it.copy(naming = false, review = it.review.copy(selected = null)) }
    }

    fun openMatchingQuery() {
        state.value.matchedIdentity?.let(::openIdentity)
    }

    private var lastSubjectEdit: SubjectGroupEdit? = null

    fun editIdentityGroup(change: SubjectGroupChange) = changeIdentityGroups { review, snapshot ->
        val result = context.photoSubjects.editGroups(snapshot.named, review.subject.id, change)
        when (result) {
            is SubjectGroupResult.Applied -> IdentityGroupUpdate(result.edit.focusId, result.edit)
            is SubjectGroupResult.Rejected -> IdentityGroupUpdate(review.subject.id, lastSubjectEdit, result.message())
        }
    }

    fun undoIdentityGroupEdit() {
        val edit = lastSubjectEdit ?: return
        changeIdentityGroups { review, _ ->
            if (context.photoSubjects.undoGroupEdit(edit)) IdentityGroupUpdate(edit.sourceId, null)
            else IdentityGroupUpdate(review.subject.id, null, R.string.identity_group_changed)
        }
    }

    private data class IdentityGroupUpdate(val subjectId: Long, val undo: SubjectGroupEdit?, val error: Int? = null)

    private fun SubjectGroupResult.Rejected.message() = when (this) {
        SubjectGroupResult.Rejected.CHANGED -> R.string.identity_group_changed
        SubjectGroupResult.Rejected.CONFLICTING_REGIONS -> R.string.identity_group_conflict
        SubjectGroupResult.Rejected.DUPLICATE_NAME -> R.string.identity_group_name_used
        SubjectGroupResult.Rejected.INCOMPATIBLE_GROUP -> R.string.identity_group_incompatible
    }

    private fun changeIdentityGroups(change: suspend (IdentityReview, LibraryState) -> IdentityGroupUpdate) {
        val snapshot = state.value
        val review = snapshot.identity ?: return
        if (snapshot.loading || snapshot.savingSubject || review.editingRejection) return
        identityJob?.cancel()
        mutableState.update { live ->
            live.copy(savingSubject = true, namingError = null, identity = live.identity?.copy(scanning = false))
        }
        viewModelScope.launch {
            operations.withLock {
                try {
                    val (result, named) = withContext(Dispatchers.IO) {
                        val current = state.value
                        val result = change(review, snapshot)
                        result to context.photoSubjects.refresh(current.photos,
                            UnavailableSources.from(current.folders, current.unavailableVolumes))
                    }
                    lastSubjectEdit = result.undo
                    queries.cancel()
                    mutableState.update { live ->
                        val subject = named.subjects.firstOrNull { it.id == result.subjectId }
                        live.copy(named = named, savingSubject = false, namingError = result.error,
                            identity = subject?.let { IdentityReview(it, canUndoGroupEdit = result.undo != null) })
                    }
                    refreshSearchResults()
                    if (result.error == null) scanIdentity()
                } catch (_: PhotoIndexUnavailable) {
                    mutableState.update { it.copy(savingSubject = false, namingError = R.string.library_index_unavailable) }
                }
            }
        }
    }

    private fun openIdentity(subject: NamedSubject) {
        if (!context.identityMatcher.available(subject.kind)) return
        suggestionJob?.cancel()
        lastSubjectEdit = null
        mutableState.update { it.copy(identity = IdentityReview(subject), review = it.review.copy(scanning = false)) }
        scanIdentity()
    }

    fun closeIdentity() {
        if (state.value.savingSubject || state.value.identity?.editingRejection == true) return
        identityJob?.cancel()
        lastSubjectEdit = null
        mutableState.update { it.copy(identity = null, namingError = null) }
    }

    fun scanIdentity() {
        val current = state.value
        val review = current.identity ?: return
        if (review.scanning || review.editingRejection || current.loading || current.savingSubject) return
        val subject = review.subject
        val references = current.named.references.filter { it.subjectId == subject.id }
        mutableState.update { it.copy(identity = review.copy(scanning = true, checked = 0, total = 0, error = null, result = null)) }
        identityJob = viewModelScope.launch {
            try {
                val (result, rejected) = withContext(Dispatchers.IO) {
                    val result = context.identityMatcher.rank(subject.kind, references, current.photos,
                        UnavailableSources.from(current.folders, current.unavailableVolumes)) { checked, total ->
                        withContext(Dispatchers.Main.immediate) {
                            updateIdentity { it.copy(checked = checked, total = total) }
                        }
                    }
                    result to context.photoSubjects.identityRejections(subject.id)
                }
                updateIdentity { it.copy(scanning = false, result = result, rejected = rejected) }
            } catch (_: PhotoProcessingException) {
                updateIdentity { it.copy(scanning = false, error = R.string.identity_failed) }
            } catch (_: PhotoIndexUnavailable) {
                updateIdentity { it.copy(scanning = false, error = R.string.library_index_unavailable) }
            }
        }
    }

    fun limitIdentity(limit: Int) {
        if (limit in suggestionLimits) updateIdentity { it.copy(limit = limit) }
    }

    fun skipIdentity(photo: RankedPhoto) {
        updateIdentity { it.copy(skipped = it.skipped + photo.photo.uri) }
    }

    fun rejectIdentity(photo: RankedPhoto) {
        val current = state.value
        val review = current.identity ?: return
        if (photo !in review.visibleSuggestions(current.named.photosOf(setOf(review.subject.id)))) return
        val rejection = photo.rejection()
        editIdentityRejection(review) {
            if (!context.photoSubjects.rejectIdentity(review.subject.id, rejection)) return@editIdentityRejection null
            review.copy(rejected = review.rejected + rejection, lastRejection = rejection)
        }
    }

    fun undoIdentityRejection() {
        val review = state.value.identity ?: return
        val rejection = review.lastRejection ?: return
        editIdentityRejection(review) {
            context.photoSubjects.undoIdentityRejection(review.subject.id, rejection)
            review.copy(rejected = review.rejected - rejection, lastRejection = null)
        }
    }

    private fun editIdentityRejection(review: IdentityReview, change: suspend () -> IdentityReview?) {
        if (review.editingRejection || review.scanning || state.value.savingSubject) return
        updateIdentity { it.copy(editingRejection = true, error = null) }
        viewModelScope.launch {
            operations.withLock {
                try {
                    val result = withContext(Dispatchers.IO) { change() }
                    updateIdentity { live ->
                        if (live.subject.id != review.subject.id) live
                        else if (result == null) live.copy(editingRejection = false, error = R.string.identity_failed)
                        else live.copy(editingRejection = false, rejected = result.rejected, lastRejection = result.lastRejection)
                    }
                } catch (_: PhotoIndexUnavailable) {
                    updateIdentity { it.copy(editingRejection = false, error = R.string.library_index_unavailable) }
                }
            }
        }
    }

    fun confirmIdentity(photo: RankedPhoto) {
        val current = state.value
        val review = current.identity ?: return
        if (review.editingRejection || photo !in review.visibleSuggestions(current.named.photosOf(setOf(review.subject.id)))) return
        changeSubject(ranked = photo) { source, _ ->
            context.photoSubjects.confirm(source, SubjectSelection.Existing(review.subject.id), photo.region, photo.revision)
        }
    }

    private fun updateIdentity(change: (IdentityReview) -> IdentityReview) {
        mutableState.update { state -> state.identity?.let { state.copy(identity = change(it)) } ?: state }
    }

    fun confirmSubject(choice: SubjectSelection, region: PhotoRegion?) = changeSubject(newName = choice as? SubjectSelection.New) { photo, suggestion ->
        context.photoSubjects.confirm(photo, choice, region, suggestion?.revision)
    }

    fun removeDatedAlias(alias: DatedSubjectAlias) = changeSubject { _, _ ->
        context.photoSubjects.removeDatedAlias(alias)
        Confirmation.SAVED
    }

    fun removeSubject(id: Long) = changeSubject { photo, _ ->
        context.photoSubjects.remove(photo.uri, id)
        Confirmation.SAVED
    }

    private fun changeSubject(
        ranked: RankedPhoto? = null,
        newName: SubjectSelection.New? = null,
        action: suspend (SelectedPhoto, NameSuggestion?) -> Confirmation,
    ) {
        val current = state.value
        val suggestion = current.review.selected
        val uri = ranked?.photo?.uri ?: suggestion?.photo?.uri ?: current.opened
        val photo = current.photos.firstOrNull { it.uri == uri } ?: return
        if (current.loading || current.savingSubject) return
        mutableState.update { it.copy(savingSubject = true, namingError = null) }
        viewModelScope.launch {
            operations.withLock {
                try {
                    val (result, named) = withContext(Dispatchers.IO) {
                        val result = action(photo, suggestion)
                        val current = state.value
                        result to context.photoSubjects.refresh(current.photos,
                            UnavailableSources.from(current.folders, current.unavailableVolumes))
                    }
                    queries.cancel()
                    mutableState.update {
                        it.copy(
                            named = named,
                            naming = it.naming && result != Confirmation.SAVED,
                            review = if (suggestion != null && result == Confirmation.SAVED) {
                                it.review.copy(selected = null, candidates = it.review.candidates - suggestion)
                            } else it.review,
                            savingSubject = false,
                            searching = false,
                            namingError = when (result) {
                                Confirmation.SAVED -> null
                                Confirmation.DUPLICATE_NAME -> R.string.subject_name_conflict
                                Confirmation.MISSING_SUBJECT -> R.string.subject_missing
                                Confirmation.PHOTO_CHANGED -> R.string.subject_photo_changed
                                Confirmation.INVALID_COLLEGE -> R.string.subject_invalid_college
                                Confirmation.INVALID_DATED_ALIAS -> R.string.subject_dated_alias_conflict
                            },
                        )
                    }
                    refreshSearchResults()
                    if (result == Confirmation.SAVED && newName != null) {
                        subjectForReview(newName.name, named)?.let(::openIdentity)
                    } else if (result == Confirmation.PHOTO_CHANGED && ranked != null) {
                        skipIdentity(ranked)
                    }
                } catch (_: PhotoIndexUnavailable) {
                    mutableState.update { it.copy(savingSubject = false, namingError = R.string.subject_index_unavailable) }
                }
            }
        }
    }

    suspend fun loadImage(uri: Uri, maxEdge: Int): Bitmap? = withContext(Dispatchers.IO) {
        library.loadImage(uri, maxEdge)
    }

    private fun reload(scheduleIndexing: Boolean = true, recheckIndex: Boolean = false, change: (() -> Int?)? = null) {
        viewModelScope.launch {
            operations.withLock {
                indexJob?.cancel()
                visualJob?.cancel()
                inputSearchJob?.cancel()
                queries.cancel()
                suggestionJob?.cancel()
                identityJob?.cancel()
                clothingJob?.cancel()
                lexicalPostings = LexicalPostings()
                searchableText.clear()
                cloudText.clear()
                embeddings.clear()
                val access = GalleryAccess.current(context)
                mutableState.update { current ->
                    val photos = if (access == GalleryAccess.ALL) current.photos else current.photos.filter { it.origin != PhotoOrigin.GALLERY }
                    val updated = current.copy(
                        galleryAccess = access,
                        loading = true,
                        restoringIndex = false,
                        textIndexed = 0,
                        visualIndexed = 0,
                        indexing = false,
                        visualIndexing = false,
                        searching = false,
                        clothingAnalyzing = false,
                        clothingUncertain = null,
                        photos = photos,
                        indexedPhotos = emptyList(),
                        indexedUris = emptySet(),
                        named = NamedLibrary(),
                        review = SuggestionReview(open = current.review.open),
                        identity = current.identity?.copy(scanning = false, result = null),
                    )
                    clearSearchResults(updated)
                }
                identityMaintenanceJob?.cancelAndJoin()
                val (sources, named, notice) = withContext(Dispatchers.IO) {
                    val notice = change?.invoke()
                    val sources = library.list()
                    try {
                        val references = context.cloudText.references()
                        val revisions = sources.photos.filter { it.uri in references }.mapNotNull { photo ->
                            val revision = photo.sourceRevision?.let { "source:$it" } ?: library.referenceRevision(photo.uri)
                            revision?.let { photo.uri to it }
                        }.toMap()
                        val saved = context.cloudText.reconcile(revisions, sources.unavailableSources)
                        withContext(Dispatchers.Main.immediate) {
                            cloudText.putAll(saved.mapValues { (_, entry) ->
                                PhotoText(entry.text.lineSequence().map { org.photolibrary.app.index.TextLine(it, "und", null) }.toList())
                            })
                        }
                    } catch (_: PhotoIndexUnavailable) {
                        cloudText.clear()
                    }
                    try {
                        Triple(sources, context.photoSubjects.refresh(sources.photos, sources.unavailableSources), notice)
                    } catch (_: PhotoIndexUnavailable) {
                        Triple(sources, NamedLibrary(), notice ?: R.string.subject_index_unavailable)
                    }
                }
                val photos = sources.photos
                sourceObserver?.folders(sources.folders.map { it.uri })
                val unavailable = sources.unavailableSources
                mutableState.update { current ->
                    val updated = current.copy(
                        galleryAccess = sources.scope,
                        folders = sources.folders,
                        unavailableVolumes = sources.unavailableVolumes,
                        photos = photos,
                        named = named,
                        opened = current.opened?.takeIf { uri -> photos.any { it.uri == uri } },
                        loading = false,
                        restoringIndex = photos.isNotEmpty(),
                        visualIndexing = current.visualAvailable && photos.isNotEmpty(),
                        imageRevision = current.imageRevision + 1,
                        notice = notice,
                    )
                    clearSearchResults(withIndexedPhotos(updated))
                }
                val textRestored = CompletableDeferred<Unit>()
                val visualRestored = CompletableDeferred<Unit>()
                indexPhotos(photos, unavailable, textRestored)
                if (context.clip.available) indexVisualContents(photos, unavailable, visualRestored)
                else visualRestored.complete(Unit)
                refreshSearchResults()
                identityMaintenanceJob = viewModelScope.launch {
                    textRestored.await()
                    visualRestored.await()
                    try {
                        withContext(Dispatchers.IO) {
                            context.identityMatcher.reconcile(photos, unavailable)
                            context.photoLocations.reconcile(photos, unavailable)
                            context.garmentEvidence.reconcile(photos.mapNotNull { photo ->
                                photo.sourceRevision?.let { photo.uri to "source:$it" }
                            }.toMap(), unavailable)
                        }
                    } catch (_: PhotoIndexUnavailable) {
                        mutableState.update { it.copy(notice = it.notice ?: R.string.library_index_unavailable) }
                    }
                    if (state.value.identity == null) scanMoreSuggestions() else scanIdentity()
                }
                if (scheduleIndexing) viewModelScope.launch(Dispatchers.IO) {
                    try {
                        if (recheckIndex) context.indexing.request()
                        context.indexScheduler.request()
                    } catch (_: PhotoIndexUnavailable) {
                        context.indexing.unavailable()
                    }
                }
            }
        }
    }

    private fun indexPhotos(photos: List<SelectedPhoto>, unavailable: UnavailableSources, restored: CompletableDeferred<Unit>) {
        val snapshot = PhotoSourceSnapshot.capture(state.value)
        indexJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    index.refresh(
                        photos = photos.map { IndexPhoto(it.uri, it.sourceRevision) },
                        restore = { saved, pending -> withContext(Dispatchers.Main.immediate) restoreText@{
                            if (!snapshot.matches(state.value)) return@restoreText
                            searchableText.putAll(saved)
                            mutableState.update { withIndexedPhotos(it.copy(restoringIndex = false, indexing = pending > 0, textIndexed = saved.size)) }
                            if (state.value.searchMode != SearchMode.NAMED) refreshSearchResults()
                            restored.complete(Unit)
                        } },
                        processUris = emptySet(),
                        unavailable = unavailable,
                    ) { uri, text ->
                        if (text != null) withContext(Dispatchers.Main.immediate) publishText@{
                            if (!snapshot.matches(state.value)) return@publishText
                            searchableText[uri] = text
                            mutableState.update { withIndexedPhotos(it.copy(textIndexed = searchableText.size)) }
                            if (state.value.searchMode != SearchMode.NAMED) refreshSearchResults()
                        }
                    }
                }
                if (snapshot.matches(state.value)) mutableState.update { it.copy(restoringIndex = false, indexing = false) }
            } catch (_: PhotoIndexUnavailable) {
                if (snapshot.matches(state.value)) mutableState.update {
                    it.copy(restoringIndex = false, indexing = false, notice = R.string.library_index_unavailable)
                }
            }
        }.also { job -> job.invokeOnCompletion { restored.complete(Unit) } }
    }

    private fun indexVisualContents(photos: List<SelectedPhoto>, unavailable: UnavailableSources, restored: CompletableDeferred<Unit>) {
        val snapshot = PhotoSourceSnapshot.capture(state.value)
        visualJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.visualIndex.refresh(
                        photos = photos.map { IndexPhoto(it.uri, it.sourceRevision) },
                        restore = { saved, pending -> withContext(Dispatchers.Main.immediate) restoreVisual@{
                            if (!snapshot.matches(state.value)) return@restoreVisual
                            embeddings.putAll(saved)
                            mutableState.update { withIndexedPhotos(it.copy(visualIndexing = pending > 0, visualIndexed = saved.size)) }
                            prepareTextSearch()
                            refreshSearchResults()
                            restored.complete(Unit)
                        } },
                        processUris = emptySet(),
                        unavailable = unavailable,
                    ) { uri, vector ->
                        if (vector != null) withContext(Dispatchers.Main.immediate) publishVisual@{
                            if (!snapshot.matches(state.value)) return@publishVisual
                            embeddings[uri] = vector
                            mutableState.update { withIndexedPhotos(it.copy(visualIndexed = embeddings.size)) }
                            refreshSearchResults()
                        }
                    }
                }
                if (snapshot.matches(state.value)) {
                    mutableState.update { it.copy(visualIndexing = false) }
                    scheduleVisualSearch()
                }
            } catch (_: PhotoIndexUnavailable) {
                if (snapshot.matches(state.value)) mutableState.update {
                    it.copy(visualIndexing = false, notice = R.string.library_visual_unavailable)
                }
            }
        }.also { job -> job.invokeOnCompletion { restored.complete(Unit) } }
    }

    private fun scheduleVisualSearch(ifIdle: Boolean = false) {
        val current = state.value
        val seed = current.visualSeed
        if (seed != null) {
            if (!current.loading) rankSimilarPhotos(current, seed, ifIdle)
            return
        }
        if (current.loading || current.searchMode != SearchMode.CONTENT || current.searchingMarksheets || embeddings.isEmpty()) return
        val event = current.eventQuery
        val capture = captureYearQuery(current.query, current.named, current.searchMode)
        val query = event?.event?.visualQuery ?: capture.phrase.trim()
        if (query.isEmpty()) return
        val photos = if (event != null) current.indexedPhotos else capture.photos(current.indexedPhotos)
        val plan = if (event != null) org.photolibrary.app.subjects.PhotoQueryPlan(null, relatedScope = event.photoScope)
            else capture.plan(current.named, current.searchMode, photos)
        if (!plan.canRankSemantically) return
        val snapshot = PhotoQuerySnapshot.capture(current)
        val available = photos.associateBy { it.uri }
        val embeddingsSnapshot = embeddings.toMap()
        val index = embeddingsSnapshot.filterKeys { it in available }
        val literal = LiteralFilter.parse(capture.phrase)
        val request = SearchQuery.parse(query, rewrite = event == null && plan.canRankContents,
            protectedWords = current.named.labels.keys.flatten().toSet(),
            enabledExpansions = queryExpansionModes)
        val hasColdVector = request.visualVariants.any { encodedQueries.get(it) == null }
        mutableState.update { it.copy(searching = hasColdVector || plan.nearby != null, queryLocation = plan.nearby) }
        queries.launch(snapshot, whenIdle = ifIdle) { publication ->
            try {
                val allowedIndex = if (literal == null) index else {
                    val textSnapshot = searchableText.toMap()
                    val importedSnapshot = cloudText.toMap()
                    withContext(Dispatchers.Default) {
                        index.filterKeys { uri -> available[uri]?.let { literal.accepts(it, textSnapshot[uri], importedSnapshot[uri]) } == true }
                    }
                }
                suspend fun queryVectors(): List<PhotoEmbedding> = request.visualVariants.map { variant ->
                    encodedQueries.get(variant) ?: withContext(Dispatchers.Default) {
                        context.clip.encodeQuery(variant)
                    }.also { encodedQueries.put(variant, it) }
                }
                val unavailable = UnavailableSources.from(current.folders, current.unavailableVolumes)
                val results = context.contentSearch.searchVariants(plan, current.photos, allowedIndex, unavailable, ::queryVectors)
                val text = searchableText.toMap()
                val cloud = cloudText.toMap()
                val lexical = if (event != null || !plan.canRankContents) LexicalCandidates(emptyList(), emptyList())
                else withContext(Dispatchers.Default) {
                    val eligible = literal?.let { rule -> photos.filter { rule.accepts(it, text[it.uri], cloud[it.uri]) } } ?: photos
                    lexicalPostings.search(request, eligible, text, cloud)
                }
                val uncertain = if (event == null && plan.canRankContents && capture.filter != null) {
                    val candidates = capture.uncertainPhotos(current.indexedPhotos).filter { photo ->
                        literal == null || literal.accepts(photo, text[photo.uri], cloud[photo.uri])
                    }.associateBy { it.uri }
                    if (candidates.isEmpty()) emptyList() else {
                        val vectors = queryVectors()
                        withContext(Dispatchers.Default) {
                            val scored = rankVisualVariants(vectors, embeddingsSnapshot, 6) { it in candidates }
                            scored.map { candidates.getValue(it.uri) }
                        }
                    }
                } else emptyList()
                val textMatches = lexical.exact.map { it.uri }
                val diversified = if (plan.canRankContents && event == null && capture.filter == null && literal == null &&
                    !preservesRequestedVersions(current.query)) {
                    val textIds = textMatches.toHashSet()
                    withContext(Dispatchers.Default) {
                        val ranked = fuseRelated(results.related, lexical.related).filterNot { it in textIds }.take(100)
                        diversifySearchVersions(ranked, embeddingsSnapshot)
                    }
                } else null
                val eventGroups = if (event != null) withContext(Dispatchers.Default) { event.groups(photos, results.related.take(24)) } else null
                publication.publish {
                    val location = results.location?.let { locationPublication.current(it, current.galleryAccess) }
                    val relatedLexical = if (plan.nearby == null) lexical.related else {
                        val permitted = (location as? NamedSearch.NearbyMatches)?.photos.orEmpty()
                        lexical.related.filter { it in permitted }
                    }
                    val related = if (location == results.location) {
                        diversified?.ranked ?: fuseRelated(results.related, relatedLexical).take(24)
                    } else emptyList()
                    val remaining = if (location is NamedSearch.NearbyMatches) {
                        plan.requirements - QueryRequirement.LOCATION_VERIFICATION
                    } else plan.requirements
                    val resolved = plan.copy(requirements = remaining)
                    mutableState.update {
                        val dateCandidates = (it.resultGroups.uncertainDate + uncertain.map { candidate -> candidate.uri }).distinct()
                        val groups = eventGroups ?: groupPhotoResults(resolved, available.keys, textMatches, related)
                            .copy(uncertainDate = dateCandidates, versionFamilies = diversified?.families.orEmpty())
                        val ordered = groups.confirmed + groups.rememberedText + groups.nearEventDate + groups.related + groups.uncertainDate
                        val dateIds = dateCandidates.toHashSet()
                        val display = if (dateIds.isEmpty()) available else available +
                            current.indexedPhotos.filter { photo -> photo.uri in dateIds }.associateBy { photo -> photo.uri }
                        it.copy(visiblePhotos = ordered.distinct().mapNotNull(display::get),
                            resultGroups = groups, searching = false,
                            visualScores = if (location == results.location) results.visualScores else emptyMap(),
                            queryRequirements = resolved.visibleRequirements, queryLocation = location,
                            namedSearch = if (plan.partialContents && remaining.isEmpty()) null else plan.named)
                    }
                }
            } catch (_: PhotoProcessingException) {
                publication.publish { mutableState.update { it.copy(searching = false, notice = R.string.library_visual_unavailable,
                    queryLocation = if (plan.nearby != null) NamedSearch.Unresolved else null) } }
            } catch (_: PhotoIndexUnavailable) {
                publication.publish { mutableState.update { it.copy(searching = false, notice = R.string.library_index_unavailable,
                    queryLocation = if (plan.nearby != null) NamedSearch.Unresolved else null) } }
            }
        }
    }

    private fun rankSimilarPhotos(state: LibraryState, seed: Uri, ifIdle: Boolean) {
        val vector = embeddings[seed] ?: return
        val available = state.indexedPhotos.associateBy { it.uri }
        if (seed !in available) return
        val scope = embeddings.filterKeys { it != seed && it in available }
        val snapshot = PhotoQuerySnapshot.capture(state)
        queries.launch(snapshot, whenIdle = ifIdle) { publication ->
            val related = withContext(Dispatchers.Default) {
                if (state.visualSeedMode == VisualSeedMode.COPIES) rankPossibleCopies(vector, scope, 100)
                else org.photolibrary.app.semantic.closestPhotos(vector, scope, 24)
            }
            publication.publish {
                mutableState.update { it.copy(visiblePhotos = related.mapNotNull(available::get),
                    resultGroups = PhotoResultGroups(related = related), searching = false) }
            }
        }
    }

    private fun scheduleSearch() {
        val current = state.value
        if (current.loading || current.searchMode == SearchMode.TEXT || current.searchingMarksheets) return
        val capture = captureYearQuery(current.query, current.named, current.searchMode)
        val clothing = if (capture.unsupported) null else org.photolibrary.app.subjects.clothingQuery(capture.phrase, capture.names(current.named))
        val nearby = current.namedSearch as? NamedSearch.Nearby
        if (clothing == null && nearby == null) {
            scheduleVisualSearch()
            return
        }
        val snapshot = PhotoQuerySnapshot.capture(current)
        val photos = capture.photos(current.indexedPhotos)
        val allowed = photos.mapTo(hashSetOf()) { it.uri }
        if (clothing != null) {
            val scoped = clothing.copy(named = clothing.named.inCaptureScope(allowed))
            queries.launch(snapshot) { publication ->
                try {
                    val result = withContext(Dispatchers.IO) {
                        context.clothingSearch.search(scoped, current.named, current.photos,
                            UnavailableSources.from(current.folders, current.unavailableVolumes))
                    }
                    publication.publish {
                        val checked = locationPublication.current(result.matches, current.galleryAccess)
                        mutableState.update {
                            val ranked = groupPhotoResults(org.photolibrary.app.subjects.PhotoQueryPlan(checked),
                                allowed,
                                identitySuggestions = if (checked == result.matches) result.related else emptyList(),
                                namedOnly = current.searchMode == SearchMode.NAMED)
                            val groups = ranked.copy(confirmed = emptyList(), estimatedColor = ranked.confirmed)
                            val available = photos.associateBy { photo -> photo.uri }
                            it.copy(namedSearch = checked, queryRequirements = emptySet(), resultGroups = groups,
                                visiblePhotos = (groups.estimatedColor + groups.related).mapNotNull(available::get), searching = false)
                        }
                    }
                } catch (_: PhotoIndexUnavailable) {
                    publication.publish { mutableState.update { it.copy(searching = false, notice = R.string.library_index_unavailable) } }
                }
            }
            return
        }
        if (nearby == null) return
        queries.launch(snapshot) { publication ->
            try {
                delay(220)
                val result = withContext(Dispatchers.IO) {
                    context.photoLocations.search(nearby.inCaptureScope(allowed), current.photos,
                        UnavailableSources.from(current.folders, current.unavailableVolumes))
                }
                publication.publish {
                    val checked = locationPublication.current(result, current.galleryAccess)
                    mutableState.update {
                        val groups = groupPhotoResults(org.photolibrary.app.subjects.PhotoQueryPlan(checked),
                            allowed, namedOnly = current.searchMode == SearchMode.NAMED)
                        it.copy(namedSearch = checked, resultGroups = groups, visiblePhotos = namedPhotos(photos, checked), searching = false)
                    }
                }
            } catch (_: PhotoIndexUnavailable) {
                publication.publish { mutableState.update { it.copy(searching = false, notice = R.string.library_index_unavailable) } }
            }
        }
    }

    private fun clearSearchResults(state: LibraryState): LibraryState {
        if (state.visualSeed != null && state.photos.any { it.uri == state.visualSeed }) {
            return state.copy(visiblePhotos = emptyList(), namedSearch = null, queryRequirements = emptySet(),
                queryLocation = null, eventQuery = null, searchingMarksheets = false, captureYearFilter = null, captureYearOnly = false, matchedIdentity = null,
                resultGroups = PhotoResultGroups(), visualScores = emptyMap(), searching = true)
        }
        return state.copy(visualSeed = null, eventQuery = null, searchingMarksheets = false, searching = !state.loading && state.query.isNotBlank(),
            visiblePhotos = if (state.query.isBlank()) state.indexedPhotos else emptyList(), resultGroups = PhotoResultGroups(), visualScores = emptyMap(), namedSearch = null,
            queryRequirements = emptySet(), queryLocation = null, matchedIdentity = null,
            captureYearFilter = null, captureYearOnly = false)
    }

    private fun withIndexedPhotos(state: LibraryState): LibraryState {
        val indexed = state.photos.filter { it.uri in searchableText || it.uri in embeddings || it.uri in cloudText }
        if (indexed == state.indexedPhotos) return state
        val uris = indexed.mapTo(hashSetOf()) { it.uri }
        val visible = if (state.query.isBlank() && state.visualSeed == null) indexed else state.visiblePhotos.filter { it.uri in uris }
        return state.copy(indexedPhotos = indexed, indexedUris = uris, visiblePhotos = visible)
    }

    private fun withResults(state: LibraryState, text: Map<Uri, PhotoText>, cloud: Map<Uri, PhotoText>): LibraryState {
        val capture = captureYearQuery(state.query, state.named, state.searchMode)
        val literal = if (state.searchMode != SearchMode.NAMED) LiteralFilter.parse(state.query) else null
        if (literal?.onlyLiterals == true) {
            val matches = state.indexedPhotos.filter { literal.accepts(it, text[it.uri], cloud[it.uri]) }
            return state.copy(visualSeed = null, eventQuery = null, searchingMarksheets = true, namedSearch = null,
                queryLocation = null, captureYearFilter = null, captureYearOnly = false, matchedIdentity = null,
                queryRequirements = emptySet(), resultGroups = PhotoResultGroups(rememberedText = matches.map { it.uri }),
                visiblePhotos = matches, searching = false)
        }
        val document = if (state.searchMode == SearchMode.CONTENT && !capture.unsupported) {
            documentPhotoQuery(capture.phrase, state.named)
        } else null
        if (document != null) {
            val scope = capture.photos(state.indexedPhotos)
            val groups = document.search(scope, text, cloud)
            val photos = scope.associateBy { it.uri }
            val matches = (groups.documents + groups.possibleDocuments).mapNotNull(photos::get)
            return state.copy(visualSeed = null, eventQuery = null, searchingMarksheets = true, namedSearch = null,
                queryLocation = null, captureYearFilter = capture.filter, captureYearOnly = false, matchedIdentity = null,
                queryRequirements = emptySet(), resultGroups = groups, visiblePhotos = matches)
        }
        val event = if (literal == null && state.searchMode == SearchMode.CONTENT && state.query.isNotBlank() &&
            searchNamed(state.query, state.named, namedOnly = false) !is NamedSearch.Matches) context.captureEvents.resolve(state.query, state.named) else null
        if (event != null) {
            val groups = event.groups(state.indexedPhotos)
            val photos = state.indexedPhotos.associateBy { it.uri }
            return state.copy(visualSeed = null, eventQuery = event, searchingMarksheets = false, namedSearch = null, queryLocation = null,
                captureYearFilter = null, captureYearOnly = false, matchedIdentity = null, queryRequirements = emptySet(),
                resultGroups = groups, visiblePhotos = groups.nearEventDate.mapNotNull(photos::get))
        }
        val scope = capture.photos(state.indexedPhotos)
        val photos = literal?.let { filter -> scope.filter { filter.accepts(it, text[it.uri], cloud[it.uri]) } } ?: scope
        val plan = capture.plan(state.named, state.searchMode, photos)
        val namedSearch = plan.named
        val dateOnly = capture.filter != null && capture.phrase.isBlank() && state.searchMode == SearchMode.CONTENT
        val lexical = if (!dateOnly && state.searchMode != SearchMode.NAMED && plan.canRankContents) {
            lexicalPostings.search(SearchQuery.parse(capture.phrase, rewrite = state.searchMode == SearchMode.CONTENT), photos, text, cloud)
        } else LexicalCandidates(emptyList(), emptyList())
        val textPhotos = lexical.exact
        val uncertain = if (capture.filter == null || capture.unsupported) emptyList() else {
            val candidates = capture.uncertainPhotos(state.indexedPhotos).filter { photo ->
                literal == null || literal.accepts(photo, text[photo.uri], cloud[photo.uri])
            }
            val unfiltered = if (dateOnly) null else planPhotoQuery(capture.phrase, capture.names(state.named),
                namedOnly = state.searchMode == SearchMode.NAMED)
            val confirmed = unfiltered?.named as? NamedSearch.Matches
            val matched = when {
                dateOnly -> candidates
                confirmed != null && unfiltered.requirements.isEmpty() ->
                    candidates.filter { it.uri in confirmed.photos }
                unfiltered?.canRankContents == true ->
                    lexicalPostings.search(SearchQuery.parse(capture.phrase, rewrite = true), candidates, text, cloud).exact
                else -> emptyList()
            }
            matched.take(24)
        }
        val groups = (if (dateOnly) PhotoResultGroups() else groupPhotoResults(plan, photos.mapTo(hashSetOf()) { it.uri },
            textMatches = textPhotos.map { it.uri }, rankedContents = lexical.related.take(100),
            namedOnly = state.searchMode == SearchMode.NAMED)).copy(uncertainDate = uncertain.map { it.uri })
        val visible = when {
            dateOnly -> photos + uncertain
            state.searchMode == SearchMode.TEXT -> textPhotos
            else -> {
                val available = photos.associateBy { it.uri }
                (groups.confirmed + groups.rememberedText + groups.related).mapNotNull(available::get) + uncertain
            }
        }
        val identity = if (namedSearch is NamedSearch.Matches && capture.filter == null) subjectForReview(state.query, state.named) else null
        return state.copy(visualSeed = null, eventQuery = null, searchingMarksheets = false, namedSearch = namedSearch, visiblePhotos = visible, resultGroups = groups,
            queryRequirements = plan.visibleRequirements,
            queryLocation = null,
            captureYearFilter = capture.filter,
            captureYearOnly = dateOnly,
            matchedIdentity = identity?.takeIf { context.identityMatcher.available(it.kind) })
    }

    private fun namedPhotos(photos: List<SelectedPhoto>, result: NamedSearch?): List<SelectedPhoto> {
        val matches = when (result) {
            is NamedSearch.Matches -> result.photos
            is NamedSearch.NearbyMatches -> {
                val accessible = photos.associateBy { it.uri }
                return result.photos.mapNotNull(accessible::get)
            }
            else -> return emptyList()
        }
        return photos.filter { it.uri in matches }
    }

}
