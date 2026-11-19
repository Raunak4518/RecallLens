package org.photolibrary.app.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.photolibrary.app.R

@Composable
internal fun SearchCapabilitiesDialog(
    capabilities: SearchCapabilities,
    modelPackStored: Boolean,
    onInstallModelPack: suspend (android.net.Uri) -> Unit,
    onRemoveModelPack: suspend () -> Unit,
    onCapabilitiesChanged: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var installing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var installStatus by remember { mutableIntStateOf(0) }
    val needsSpecialistPack = !capabilities.anonymousRegionReview ||
        !capabilities.personIdentitySearch || !capabilities.exactObjectSearch || !capabilities.clothingSearch
    val packPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                installing = true
                installStatus = 0
                val installed = try {
                    withContext(Dispatchers.IO) { onInstallModelPack(uri) }
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                installing = false
                installStatus = if (installed) R.string.model_pack_install_success else R.string.model_pack_install_failed
                if (installed) onCapabilitiesChanged()
            }
        }
    }
    val rows = listOf(
        R.string.search_capability_visual to capabilities.semanticSearch,
        R.string.search_capability_text to capabilities.rememberedTextSearch,
        R.string.search_capability_regions to capabilities.anonymousRegionReview,
        R.string.search_capability_people to capabilities.personIdentitySearch,
        R.string.search_capability_objects to capabilities.exactObjectSearch,
        R.string.search_capability_clothing to capabilities.clothingSearch,
    )
    AlertDialog(
        onDismissRequest = { if (!installing && !removing) onClose() },
        title = { Text(stringResource(R.string.search_capabilities_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.search_capabilities_profile, capabilities.profile), style = MaterialTheme.typography.bodySmall)
                rows.forEach { (label, available) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(label), modifier = Modifier.weight(1f).padding(end = 12.dp))
                        Text(
                            stringResource(if (available) R.string.search_capability_available else R.string.search_capability_not_included),
                            color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (needsSpecialistPack) {
                    Text(stringResource(R.string.model_pack_local_only), style = MaterialTheme.typography.bodySmall)
                    if (installing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.model_pack_installing), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { packPicker.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !installing && !removing) {
                        Text(stringResource(R.string.model_pack_install))
                    }
                }
                if (modelPackStored) {
                    if (removing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.model_pack_removing), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        scope.launch {
                            removing = true
                            val removed = try {
                                withContext(Dispatchers.IO) { onRemoveModelPack() }
                                true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            } finally {
                                removing = false
                            }
                            onCapabilitiesChanged()
                            if (removed) {
                                installStatus = R.string.model_pack_removed
                            } else {
                                installStatus = R.string.model_pack_remove_failed
                            }
                        }
                    }, enabled = !installing && !removing) {
                        Text(stringResource(R.string.model_pack_remove))
                    }
                }
                if (installStatus != 0) Text(stringResource(installStatus), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onClose, enabled = !installing && !removing) { Text(stringResource(R.string.library_done)) } },
    )
}
