package org.photolibrary.app.privacy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import org.photolibrary.app.BuildConfig
import org.photolibrary.app.R
import org.photolibrary.app.library.RegionPicker

@Composable
internal fun PhotoExportScreen(state: ExportState, actions: PhotoExportViewModel) {
    BackHandler(onBack = actions::close)
    var cropping by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(onClick = actions::close) { Text(stringResource(R.string.export_keep_local)) }
            Text(stringResource(R.string.export_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.export_task))
            TextButton(onClick = { actions.settings(!state.settingsOpen) }, enabled = !state.busy) {
                Text(stringResource(R.string.cloud_connection))
            }
            if (state.settingsOpen) CloudConnectionEditor(state, actions)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.mode == ExportMode.CLOUD_REDACTED, onClick = { actions.mode(ExportMode.CLOUD_REDACTED) },
                    enabled = !state.busy, label = { Text(stringResource(R.string.export_redacted)) })
                FilterChip(state.mode == ExportMode.CLOUD_FULL, onClick = { actions.mode(ExportMode.CLOUD_FULL) },
                    enabled = !state.busy, label = { Text(stringResource(R.string.export_full)) })
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (state.busy) TextButton(onClick = actions::cancelUpload) { Text(stringResource(R.string.cloud_cancel)) }
            if (state.cleanupPending) Text(stringResource(R.string.cloud_cleanup_pending))
            state.cloudText?.let { text ->
                Text(stringResource(R.string.cloud_result), style = MaterialTheme.typography.titleMedium)
                Text(text.ifEmpty { stringResource(R.string.cloud_empty) })
            }
            val artifact = state.artifact
            if (artifact != null) {
                val redacted = state.mode == ExportMode.CLOUD_REDACTED
                Text(stringResource(if (redacted) R.string.export_redacted_visibility else R.string.export_full_visibility))
                if (redacted) {
                    val detected = artifact.source.sensitive
                    Text(stringResource(R.string.export_mask_count, detected?.identifiers?.size ?: 0, detected?.faces?.size ?: 0))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!cropping, { cropping = false }, label = { Text(stringResource(R.string.export_add_mask)) })
                        FilterChip(cropping, { cropping = true }, label = { Text(stringResource(R.string.export_crop)) })
                    }
                    Text(stringResource(if (cropping) R.string.export_crop_hint else R.string.export_mask_hint))
                }
                Box(Modifier.fillMaxWidth().height(280.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    RegionPicker(artifact.preview, null, if (redacted && !state.busy) { region -> actions.mark(region, cropping) } else null)
                }
                Row {
                    if (artifact.edits.masks.isNotEmpty()) TextButton(onClick = actions::undo) { Text(stringResource(R.string.export_undo)) }
                    if (artifact.edits.crop != null) TextButton(onClick = actions::resetCrop) { Text(stringResource(R.string.export_reset_crop)) }
                }
                Text(stringResource(R.string.export_exact, artifact.preview.width, artifact.preview.height, artifact.bytesCount))
                Text(state.connection?.url ?: stringResource(R.string.cloud_not_connected))
                if (state.approved == null) {
                    Button(onClick = actions::approve, enabled = !state.busy) { Text(stringResource(R.string.cloud_approve_send)) }
                } else {
                    Text(stringResource(if (state.busy) R.string.cloud_processing else R.string.cloud_complete))
                }
            }
        }
    }
}

@Composable
private fun CloudConnectionEditor(state: ExportState, actions: PhotoExportViewModel) {
    var url by remember(state.connection) { mutableStateOf(state.connection?.url.orEmpty()) }
    var token by remember(state.connection) { mutableStateOf(state.connection?.token.orEmpty()) }
    var key by remember(state.connection) { mutableStateOf(state.connection?.publicKey.orEmpty()) }
    var local by remember(state.connection) { mutableStateOf(state.connection?.localDevelopment ?: false) }
    Text(stringResource(R.string.cloud_setup_hint))
    OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.cloud_service_url)) },
        singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.cloud_access_key)) },
        singleLine = true, enabled = !state.busy, visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth())
    OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.cloud_worker_key)) },
        singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
    if (BuildConfig.DEBUG) Row {
        Checkbox(local, { local = it }, enabled = !state.busy)
        Text(stringResource(R.string.cloud_development))
    }
    Row {
        Button(onClick = { actions.saveConnection(url, token, key, local) }, enabled = !state.busy) {
            Text(stringResource(R.string.cloud_save))
        }
        TextButton(onClick = actions::forgetConnection, enabled = !state.busy) {
            Text(stringResource(R.string.cloud_forget))
        }
    }
}
