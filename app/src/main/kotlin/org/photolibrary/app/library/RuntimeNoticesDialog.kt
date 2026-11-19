package org.photolibrary.app.library

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.photolibrary.app.R

private data class RuntimeNoticeLoad<T>(val value: T? = null, val failed: Boolean = false)

@Composable
internal fun RuntimeNoticesDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val loader = remember(context) { RuntimeNotices(context.assets::open) }
    val index by produceState(RuntimeNoticeLoad<RuntimeNoticeIndex>()) {
        value = withContext(Dispatchers.IO) { loadRuntimeNotice(loader::index) }
    }
    var selected by remember { mutableStateOf<RuntimeNoticeDocument?>(null) }
    val document by key(selected?.sha256) {
        produceState(RuntimeNoticeLoad<List<String>>()) {
            val current = selected
            if (current != null) value = withContext(Dispatchers.IO) { loadRuntimeNotice { loader.text(current) } }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(selected?.title ?: stringResource(R.string.runtime_notices_title)) },
        text = {
            key(selected?.sha256) {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    val catalog = index.value
                    val current = selected
                    if (index.failed || document.failed) {
                        item { Text(stringResource(R.string.runtime_notices_error)) }
                    } else if (catalog == null || current != null && document.value == null) {
                        item { Text(stringResource(R.string.runtime_notices_loading)) }
                    } else if (current == null) {
                        item { Text(stringResource(R.string.runtime_notices_scope, catalog.componentCount, catalog.documents.size)) }
                        item { Text(stringResource(R.string.runtime_notices_incomplete)) }
                        items(catalog.documents, key = { it.sha256 }) { notice ->
                            TextButton(onClick = { selected = notice }) { Text(notice.title) }
                        }
                        items(catalog.gaps) { Text(it) }
                    } else {
                        item { Text(stringResource(R.string.runtime_notices_components, current.mappings.size)) }
                        if (current.mappings.size == 1) item { Text(current.mappings.single().coordinate) }
                        val chunks = checkNotNull(document.value)
                        if (chunks.isEmpty()) item { Text(stringResource(R.string.runtime_notices_empty_document)) }
                        items(chunks) { chunk -> SelectionContainer { Text(chunk) } }
                        item { Text(stringResource(R.string.runtime_notices_provenance)) }
                        items(current.mappings) { mapping ->
                            SelectionContainer {
                                Text(mapping.coordinate + "\n" + mapping.sourceEntry + "\n" + mapping.sourceUrl +
                                    "\nSHA-256: " + mapping.artifactHashes.joinToString())
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.library_done)) } },
        dismissButton = {
            if (selected != null) TextButton(onClick = { selected = null }) { Text(stringResource(R.string.runtime_notices_back)) }
        },
    )
}

private fun <T> loadRuntimeNotice(load: () -> T): RuntimeNoticeLoad<T> = try {
    RuntimeNoticeLoad(value = load())
} catch (_: IOException) {
    RuntimeNoticeLoad(failed = true)
} catch (_: JSONException) {
    RuntimeNoticeLoad(failed = true)
} catch (_: IllegalArgumentException) {
    RuntimeNoticeLoad(failed = true)
}
