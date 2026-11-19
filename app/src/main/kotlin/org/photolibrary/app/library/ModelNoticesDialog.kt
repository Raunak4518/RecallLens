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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.photolibrary.app.R

@Composable
internal fun ModelNoticesDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val notices by produceState<List<Pair<String, String>>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            context.assets.list("third-party-models").orEmpty().filter { it.endsWith(".txt") || it.endsWith(".md") }
                .sorted().map { name ->
                    name to context.assets.open("third-party-models/$name").bufferedReader().use { it.readText() }
                }
        }
    }
    var selected by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(selected ?: stringResource(R.string.model_notices_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (selected == null) {
                    items(notices, key = { it.first }) { notice ->
                        TextButton(onClick = { selected = notice.first }) { Text(notice.first) }
                    }
                } else {
                    item { SelectionContainer { Text(notices.firstOrNull { it.first == selected }?.second.orEmpty()) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.library_done)) } },
        dismissButton = {
            if (selected != null) TextButton(onClick = { selected = null }) { Text(stringResource(R.string.model_notices_back)) }
        },
    )
}
