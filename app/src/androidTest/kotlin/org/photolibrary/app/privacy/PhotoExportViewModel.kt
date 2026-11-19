package org.photolibrary.app.privacy

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.photolibrary.app.PhotoLibraryApplication
import org.photolibrary.app.R
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.index.PhotoIndexUnavailable
import org.photolibrary.app.index.CloudTextEvidence

internal data class ExportState(
    val uri: Uri,
    val mode: ExportMode? = null,
    val artifact: ExportArtifact? = null,
    val approved: ApprovedExport? = null,
    val busy: Boolean = false,
    val error: Int? = null,
    val connection: CloudConnection? = null,
    val settingsOpen: Boolean = false,
    val cloudText: String? = null,
    val cleanupPending: Boolean = false,
)

internal class PhotoExportViewModel @JvmOverloads constructor(
    application: Application,
    private val transport: CloudTransport = CloudTransport(),
) : AndroidViewModel(application) {
    private val context = application as PhotoLibraryApplication
    private val exporter = context.photoExport
    private val current = MutableStateFlow<ExportState?>(null)
    val state = current.asStateFlow()
    private var operation: Job? = null
    private var sourceWatch: Job? = null

    fun open(uri: Uri) {
        operation?.cancel()
        sourceWatch?.cancel()
        current.value = ExportState(uri, busy = true)
        operation = viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { context.cloudSettings.load() }
                current.update { if (it?.uri == uri) it.copy(connection = saved, busy = false) else it }
            } catch (_: PhotoIndexUnavailable) {
                current.update { it?.copy(busy = false, error = R.string.cloud_settings_failed) }
            } catch (_: IllegalArgumentException) {
                current.update { it?.copy(busy = false, error = R.string.cloud_settings_failed) }
            }
        }
    }

    fun close() {
        operation?.cancel()
        sourceWatch?.cancel()
        current.value = null
    }

    fun mode(mode: ExportMode) {
        val state = current.value ?: return
        if (state.busy) return
        sourceWatch?.cancel()
        val pending = ExportState(state.uri, mode, connection = state.connection)
        run(pending) {
            val source = exporter.inspect(state.uri, mode)
            pending.copy(artifact = exporter.render(source, ExportEdits()))
        }
    }

    fun mark(region: PhotoRegion, crop: Boolean) {
        val state = current.value ?: return
        val export = state.artifact ?: return
        if (state.busy || state.mode != ExportMode.CLOUD_REDACTED) return
        val sourceRegion = region.toOriginal(export.effectiveCrop)
        val edits = if (crop) export.edits.copy(crop = sourceRegion)
            else export.edits.copy(masks = (export.edits.masks + sourceRegion).take(MAX_MANUAL_MASKS))
        revise(state, export, edits)
    }

    fun undo() {
        val state = current.value ?: return
        val export = state.artifact ?: return
        if (state.busy || export.edits.masks.isEmpty()) return
        revise(state, export, export.edits.copy(masks = export.edits.masks.dropLast(1)))
    }

    fun resetCrop() {
        val state = current.value ?: return
        val export = state.artifact ?: return
        if (state.busy || export.edits.crop == null) return
        revise(state, export, export.edits.copy(crop = null))
    }

    private fun revise(state: ExportState, export: ExportArtifact, edits: ExportEdits) {
        val pending = state.copy(artifact = null, approved = null, cloudText = null)
        run(pending) { pending.copy(artifact = exporter.render(export.source, edits)) }
    }

    fun approve() {
        val state = current.value ?: return
        val export = state.artifact ?: return
        if (state.busy || state.approved != null) return
        val connection = state.connection
        if (connection == null) {
            current.update { it?.copy(settingsOpen = true) }
            return
        }
        operation?.cancel()
        current.value = state.copy(busy = true, error = null, cloudText = null, cleanupPending = false)
        operation = viewModelScope.launch {
            var approval: ApprovedExport? = null
            var request: OcrRequest? = null
            try {
                val pair = withContext(Dispatchers.IO) {
                    val approved = exporter.approve(export, connection.worker.id)
                    approved to exporter.seal(approved, connection.worker)
                }
                approval = pair.first
                request = pair.second
                current.update { it?.copy(approved = pair.first) }
                val text = transport.recognize(connection, pair.first, pair.second,
                    validate = { validateCurrent(export.source) }, progress = {})
                withContext(Dispatchers.IO) { exporter.validate(export.source) }
                withContext(Dispatchers.IO) {
                    val revision = checkNotNull(context.library.referenceRevision(export.source.uri))
                    exporter.validate(export.source)
                    val saved = context.cloudText.save(export.source.uri, CloudTextEvidence(
                        revision, text, connection.worker.id, export.digest, export.source.mode, pair.second.modelProvenance,
                    )) {
                        context.library.currentRevision(export.source.uri)?.let { "source:$it" }
                            ?: kotlinx.coroutines.runBlocking { context.library.referenceRevision(export.source.uri) }
                    }
                    if (!saved) throw ExportFailure.Source()
                }
                current.update { it?.copy(busy = false, cloudText = text) }
                watchSource(export.source)
            } catch (failure: Exception) {
                ensureActive()
                val sourceChanged = failure is ExportFailure.Source
                current.update { it?.copy(busy = false, approved = null, cloudText = null,
                    artifact = if (sourceChanged) null else it.artifact,
                    error = if (sourceChanged) R.string.export_source_error else R.string.cloud_failed) }
            } finally {
                request?.close()
                approval?.let { approved ->
                    val deleted = withContext(NonCancellable + Dispatchers.IO) { transport.delete(connection, approved.id) }
                    current.update { if (it?.artifact === approved.artifact) it.copy(cleanupPending = !deleted) else it }
                }
            }
        }
    }

    fun settings(open: Boolean) {
        current.update { if (it?.busy == true) it else it?.copy(settingsOpen = open, error = null) }
    }

    fun saveConnection(url: String, token: String, publicKey: String, local: Boolean) {
        val state = current.value ?: return
        if (state.busy) return
        operation?.cancel()
        current.value = state.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                val connection = withContext(Dispatchers.IO) {
                    CloudConnection.create(url, token, publicKey, local).also { context.cloudSettings.save(it) }
                }
                current.update { it?.copy(connection = connection, settingsOpen = false, busy = false,
                    approved = null, cloudText = null) }
            } catch (failure: Exception) {
                ensureActive()
                current.update { it?.copy(busy = false, error = R.string.cloud_settings_failed) }
            }
        }
    }

    fun forgetConnection() {
        if (current.value?.busy == true) return
        operation?.cancel()
        operation = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { context.cloudSettings.clear() }
                current.update { it?.copy(connection = null, approved = null, cloudText = null, settingsOpen = false) }
            } catch (_: PhotoIndexUnavailable) {
                current.update { it?.copy(error = R.string.cloud_settings_failed) }
            }
        }
    }

    fun cancelUpload() {
        operation?.cancel()
        current.update { it?.copy(busy = false, approved = null, cloudText = null) }
    }

    private fun validateCurrent(source: ExportSource) {
        if (source.revision == null) exporter.validate(source)
        else if (context.library.currentRevision(source.uri) != source.revision) throw ExportFailure.Source()
    }

    private fun watchSource(source: ExportSource) {
        sourceWatch?.cancel()
        sourceWatch = viewModelScope.launch {
            while (current.value?.cloudText != null) {
                delay(1000)
                try {
                    withContext(Dispatchers.IO) { validateCurrent(source) }
                } catch (_: ExportFailure.Source) {
                    current.update { it?.copy(cloudText = null, artifact = null, approved = null, error = R.string.export_source_error) }
                }
            }
        }
    }

    private fun run(base: ExportState, action: () -> ExportState) {
        operation?.cancel()
        current.value = base.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                current.value = withContext(Dispatchers.IO) { action() }.copy(busy = false, error = null)
            } catch (failure: ExportFailure) {
                ensureActive()
                val error = when (failure) {
                    is ExportFailure.Source -> R.string.export_source_error
                    is ExportFailure.Unsupported -> R.string.export_unsupported
                    is ExportFailure.Detector -> R.string.export_detector_error
                    is ExportFailure.Render -> R.string.export_render_error
                    is ExportFailure.Unredacted -> R.string.export_no_mask
                }
                current.update { it?.copy(busy = false, error = error, approved = null,
                    artifact = if (failure is ExportFailure.Source) null else it.artifact) }
            }
        }
    }

}
