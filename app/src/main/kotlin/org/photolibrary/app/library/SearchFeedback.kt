package org.photolibrary.app.library

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.photolibrary.app.R
import org.photolibrary.app.index.PhotoIndexUnavailable

internal class SearchFeedback(private val store: FeedbackStore, private val scope: CoroutineScope) {
    var history by mutableStateOf(FeedbackHistory(false))
        private set
    var session by mutableStateOf(emptyList<ResultJudgment>())
        private set
    var previous by mutableStateOf<ResultJudgment?>(null)
        private set
    var ready by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private val writes = Mutex()
    private var sessionGeneration = 0L

    suspend fun refresh(photos: List<SelectedPhoto>?) = writes.withLock {
        try {
            history = withContext(Dispatchers.IO) { store.update(accessible = photos) }
            ready = true
            failed = false
        } catch (error: PhotoIndexUnavailable) {
            ready = false
            failed = true
        }
    }

    fun resetSession() {
        sessionGeneration++
        session = emptyList()
        previous = null
    }

    fun setLearning(enabled: Boolean) {
        if (busy) return
        write({ store.update(enabled = enabled) }) { resetSession() }
    }

    fun mark(query: String, photo: SelectedPhoto, positive: Boolean, features: FeedbackFeatures) {
        if (busy) return
        val record = ResultJudgment.create(query, photo, positive, features)
        val generation = sessionGeneration
        fun applyJudgment() {
            if (generation != sessionGeneration) return
            session = (session + record).takeLast(512)
            previous = record
        }
        if (ready && history.enabled && record.revision.isNotEmpty()) {
            write({ store.update(record = record) }, ::applyJudgment)
        } else {
            applyJudgment()
        }
    }

    fun rating(query: String, photo: SelectedPhoto): Boolean? {
        val key = feedbackQueryKey(query)
        return (history.judgments + session).lastOrNull {
            it.query == key && it.source == photo.uri.toString() && it.revision == photo.sourceRevision.orEmpty()
        }?.positive
    }

    fun reason(reason: FeedbackReason) {
        val record = previous ?: return
        if (busy || record.positive) return
        val revised = record.copy(reason = reason)
        fun applyReason() {
            if (previous?.id != record.id) return
            session = session.map { if (it.id == record.id) revised else it }
            previous = revised
        }
        if (history.enabled && history.judgments.any { it.id == record.id }) {
            write({ store.update(reason = record.id to reason) }, ::applyReason)
        } else applyReason()
    }

    fun undo() {
        if (busy) return
        val record = previous ?: return
        fun restore() {
            session = session.filterNot { it.id == record.id }
            previous = null
        }
        if (history.judgments.any { it.id == record.id }) write({ store.update(undo = record.id) }, ::restore)
        else restore()
    }

    private fun write(action: () -> FeedbackHistory, after: () -> Unit) {
        busy = true
        scope.launch {
            try {
                writes.withLock {
                    history = withContext(Dispatchers.IO) { action() }
                    failed = false
                    ready = true
                    after()
                }
            } catch (error: PhotoIndexUnavailable) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
}

@Composable
internal fun rememberSearchFeedback(store: FeedbackStore, state: LibraryState): SearchFeedback {
    val scope = rememberCoroutineScope()
    val feedback = remember(store, scope) { SearchFeedback(store, scope) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, feedback) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) feedback.resetSession()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(feedback, state.photos, state.loading) {
        if (!state.loading) feedback.refresh(state.photos)
    }
    LaunchedEffect(feedback, state.query, state.visualSeed) { feedback.resetSession() }
    return feedback
}

@Composable
internal fun LearningOptions(feedback: SearchFeedback) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.feedback_learning)) }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.feedback_learning)) },
        text = { Text(stringResource(if (feedback.history.enabled) R.string.feedback_learning_on else R.string.feedback_learning_off)) },
        confirmButton = {
            TextButton(enabled = feedback.ready && !feedback.busy, onClick = {
                feedback.setLearning(!feedback.history.enabled)
                open = false
            }) {
                Text(stringResource(if (feedback.history.enabled) R.string.feedback_disable else R.string.feedback_enable))
            }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.library_done)) } },
    )
}

@Composable
internal fun FeedbackReceipt(feedback: SearchFeedback, query: String, onReview: (android.net.Uri) -> Unit) {
    if (feedback.failed) Text(stringResource(R.string.feedback_unavailable))
    feedback.previous?.takeIf { it.query == feedbackQueryKey(query) }?.let { judgment ->
        Row {
            Text(stringResource(if (judgment.positive) R.string.feedback_found_recorded else R.string.feedback_wrong_recorded))
            TextButton(onClick = feedback::undo, enabled = !feedback.busy) { Text(stringResource(R.string.feedback_undo)) }
        }
        if (!judgment.positive) {
            var reasonMenu by remember(judgment.id) { mutableStateOf(false) }
            Row {
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { reasonMenu = true }, enabled = !feedback.busy) {
                        Text(stringResource(judgment.reason?.label() ?: R.string.feedback_add_reason))
                    }
                    DropdownMenu(expanded = reasonMenu, onDismissRequest = { reasonMenu = false }) {
                        FeedbackReason.entries.forEach { reason ->
                            DropdownMenuItem(text = { Text(stringResource(reason.label())) }, onClick = {
                                reasonMenu = false
                                feedback.reason(reason)
                            })
                        }
                    }
                }
                if (judgment.reason == FeedbackReason.PERSON || judgment.reason == FeedbackReason.OBJECT) {
                    TextButton(onClick = { onReview(android.net.Uri.parse(judgment.source)) }) {
                        Text(stringResource(R.string.feedback_review_regions))
                    }
                }
            }
        }
    }
}

private fun FeedbackReason.label() = when (this) {
    FeedbackReason.PERSON -> R.string.feedback_reason_person
    FeedbackReason.OBJECT -> R.string.feedback_reason_object
    FeedbackReason.ATTRIBUTE -> R.string.feedback_reason_attribute
    FeedbackReason.TEXT -> R.string.feedback_reason_text
    FeedbackReason.TIME_PLACE -> R.string.feedback_reason_time_place
    FeedbackReason.DUPLICATE -> R.string.feedback_reason_duplicate
    FeedbackReason.OTHER -> R.string.feedback_reason_other
}

@Composable
internal fun ResultFeedbackControls(feedback: SearchFeedback, query: String, photo: SelectedPhoto, features: () -> FeedbackFeatures) {
    val rating = feedback.rating(query, photo)
    val foundDescription = stringResource(R.string.feedback_found)
    val wrongDescription = stringResource(R.string.feedback_wrong)
    Row {
        IconButton(
            onClick = { feedback.mark(query, photo, true, features()) },
            enabled = !feedback.busy,
            modifier = androidx.compose.ui.Modifier.semantics { contentDescription = foundDescription },
        ) {
            androidx.compose.material3.Icon(
                painterResource(R.drawable.ic_feedback_positive),
                contentDescription = foundDescription,
                tint = if (rating == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = { feedback.mark(query, photo, false, features()) },
            enabled = !feedback.busy,
            modifier = androidx.compose.ui.Modifier.semantics { contentDescription = wrongDescription },
        ) {
            androidx.compose.material3.Icon(
                painterResource(R.drawable.ic_feedback_negative),
                contentDescription = wrongDescription,
                tint = if (rating == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
