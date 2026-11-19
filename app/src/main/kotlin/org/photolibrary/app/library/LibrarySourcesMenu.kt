package org.photolibrary.app.library

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.photolibrary.app.R

@Composable
internal fun LibrarySourcesMenu(
    state: LibraryState,
    onAllowPhotos: () -> Unit,
    onChooseFolder: () -> Unit,
    onPermissionSettings: () -> Unit,
    onRemoveFolder: (Uri) -> Unit,
    onRefresh: () -> Unit,
    onSelectSelf: (Long?) -> Unit,
    onPauseIndexing: () -> Unit,
    onResumeIndexing: () -> Unit,
    onNameSuggestions: () -> Unit,
    onAnalyzeClothing: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var foldersOpen by remember { mutableStateOf(false) }
    var noticesOpen by remember { mutableStateOf(false) }
    var runtimeNoticesOpen by remember { mutableStateOf(false) }
    var selfOpen by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { menuOpen = true }, enabled = !state.loading) {
            Text(stringResource(R.string.library_sources))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (state.galleryAccess != GalleryAccess.ALL) {
                val label = if (state.galleryAccess == GalleryAccess.SELECTED) R.string.library_manage_selection else R.string.library_allow_photos
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { menuOpen = false; onAllowPhotos() })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.library_choose_folder)) }, onClick = { menuOpen = false; onChooseFolder() })
            if (state.unavailableVolumes.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_storage_unavailable, state.unavailableVolumes.size)) },
                    onClick = { menuOpen = false; onRefresh() },
                )
            }
            if (state.folders.isNotEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.library_manage_folders)) }, onClick = { menuOpen = false; foldersOpen = true })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.library_permissions)) }, onClick = { menuOpen = false; onPermissionSettings() })
            DropdownMenuItem(text = { Text(stringResource(R.string.self_identity_title)) }, onClick = { menuOpen = false; selfOpen = true })
            if (state.indexedPhotos.isNotEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.suggestions_title)) }, onClick = { menuOpen = false; onNameSuggestions() })
            }
            if (state.named.references.any { it.region != null &&
                    state.named.kindOf(it.subjectId) == org.photolibrary.app.subjects.SubjectKind.PERSON }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.clothing_analyze)) }, enabled = !state.clothingAnalyzing,
                    onClick = { menuOpen = false; onAnalyzeClothing() })
            }
            if (state.indexingPaused || state.indexingChecked < state.indexingTotal) {
                val label = if (state.indexingPaused) R.string.index_resume else R.string.index_pause
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                    menuOpen = false
                    if (state.indexingPaused) onResumeIndexing() else onPauseIndexing()
                })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.model_notices_title)) }, onClick = { menuOpen = false; noticesOpen = true })
            DropdownMenuItem(text = { Text(stringResource(R.string.runtime_notices_title)) }, onClick = { menuOpen = false; runtimeNoticesOpen = true })
        }
    }
    if (noticesOpen) ModelNoticesDialog { noticesOpen = false }
    if (runtimeNoticesOpen) RuntimeNoticesDialog { runtimeNoticesOpen = false }
    if (selfOpen) SelfIdentityDialog(state.named, state.loading, onDismiss = { selfOpen = false }, onSave = {
        selfOpen = false
        onSelectSelf(it)
    })
    if (foldersOpen) {
        AlertDialog(
            onDismissRequest = { foldersOpen = false },
            title = { Text(stringResource(R.string.library_manage_folders)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(state.folders, key = { it.uri.toString() }) { folder ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            val label = if (folder.available) folder.name else stringResource(R.string.library_folder_unavailable, folder.name)
                            Text(label, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onRemoveFolder(folder.uri) }, enabled = !state.loading) {
                                Text(stringResource(R.string.library_remove_folder))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { foldersOpen = false }) { Text(stringResource(R.string.library_done)) } },
            dismissButton = {
                if (state.folders.any { !it.available }) {
                    TextButton(onClick = onRefresh, enabled = !state.loading) {
                        Text(stringResource(R.string.library_retry_folders))
                    }
                }
            },
        )
    }
}
