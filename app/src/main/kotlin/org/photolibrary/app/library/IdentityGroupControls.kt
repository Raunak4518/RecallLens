package org.photolibrary.app.library

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.photolibrary.app.R
import org.photolibrary.app.subjects.NamedSubject
import org.photolibrary.app.subjects.SubjectGroupChange
import org.photolibrary.app.subjects.validSubjectLabel

private enum class GroupDialog { SPLIT, MERGE }

@Composable
internal fun IdentityGroupControls(
    state: LibraryState,
    review: IdentityReview,
    confirmed: List<SelectedPhoto>,
    actions: IdentityActions,
    loadImage: suspend (Uri, Int) -> Bitmap?,
) {
    var dialog by remember(review.subject.id) { mutableStateOf<GroupDialog?>(null) }
    val enabled = !state.loading && !state.savingSubject && !review.editingRejection
    val targets = state.named.subjects.filter { it.kind == review.subject.kind && it.id != review.subject.id }
    Row {
        if (confirmed.size >= 2) TextButton(onClick = { dialog = GroupDialog.SPLIT }, enabled = enabled) {
            Text(stringResource(R.string.identity_group_split))
        }
        if (targets.isNotEmpty()) TextButton(onClick = { dialog = GroupDialog.MERGE }, enabled = enabled) {
            Text(stringResource(R.string.identity_group_merge))
        }
    }
    if (review.canUndoGroupEdit) TextButton(onClick = actions.undoGroupEdit, enabled = enabled) {
        Text(stringResource(R.string.identity_group_undo))
    }
    val close = { dialog = null }
    when (dialog) {
        GroupDialog.SPLIT -> SplitIdentityDialog(confirmed, state.imageRevision, loadImage, enabled, close) { change ->
            close()
            actions.editGroup(change)
        }
        GroupDialog.MERGE -> MergeIdentityDialog(targets, enabled, close) { change ->
            close()
            actions.editGroup(change)
        }
        null -> Unit
    }
}

@Composable
private fun SplitIdentityDialog(
    photos: List<SelectedPhoto>,
    imageRevision: Long,
    loadImage: suspend (Uri, Int) -> Bitmap?,
    enabled: Boolean,
    onClose: () -> Unit,
    onApply: (SubjectGroupChange.Split) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selected by remember(photos) { mutableStateOf(emptySet<Uri>()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.identity_group_split)) },
        text = {
            Column {
                Text(stringResource(R.string.identity_group_split_hint))
                OutlinedTextField(name, { name = it.take(80) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.subject_name)) })
                LazyColumn(Modifier.heightIn(max = 250.dp)) {
                    items(photos, key = { it.uri.toString() }) { photo ->
                        val checked = photo.uri in selected
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Checkbox) {
                                selected = if (checked) selected - photo.uri else selected + photo.uri
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked, onCheckedChange = null)
                            PhotoImage(photo.uri, 144, imageRevision, loadImage, ContentScale.Crop, Modifier.size(44.dp))
                            Text(photo.name ?: stringResource(R.string.library_unnamed))
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(android.R.string.cancel)) } },
        confirmButton = {
            TextButton(
                onClick = { onApply(SubjectGroupChange.Split(selected, name.trim())) },
                enabled = enabled && selected.isNotEmpty() && selected.size < photos.size && validSubjectLabel(name.trim()),
            ) { Text(stringResource(R.string.identity_group_move)) }
        },
    )
}

@Composable
private fun MergeIdentityDialog(
    targets: List<NamedSubject>,
    enabled: Boolean,
    onClose: () -> Unit,
    onApply: (SubjectGroupChange.Merge) -> Unit,
) {
    var target by remember(targets) { mutableStateOf<Long?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.identity_group_merge)) },
        text = {
            Column {
                Text(stringResource(R.string.identity_group_merge_hint))
                LazyColumn(Modifier.heightIn(max = 250.dp)) {
                    items(targets, key = { it.id }) { subject ->
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { target = subject.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(target == subject.id, onClick = null)
                            Text(subject.name)
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(android.R.string.cancel)) } },
        confirmButton = {
            TextButton(
                onClick = { target?.let { onApply(SubjectGroupChange.Merge(it)) } },
                enabled = enabled && target != null,
            ) { Text(stringResource(R.string.identity_group_merge)) }
        },
    )
}
