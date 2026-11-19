package org.photolibrary.app.library

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.photolibrary.app.R
import org.photolibrary.app.subjects.NamedLibrary
import org.photolibrary.app.subjects.SubjectKind

@Composable
internal fun SelfIdentityDialog(
    named: NamedLibrary,
    saving: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (Long?) -> Unit,
) {
    val confirmed = named.references.mapTo(hashSetOf()) { it.subjectId }
    val people = named.subjects.filter {
        it.kind == SubjectKind.PERSON && (it.id in confirmed || it.id == named.selfSubjectId)
    }.sortedBy { it.name.lowercase() }
    var selected by remember(named.selfSubjectId) { mutableStateOf(named.selfSubjectId) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.self_identity_title)) },
        text = {
            Column {
                Text(stringResource(R.string.self_identity_explanation), style = MaterialTheme.typography.bodyMedium)
                if (people.isEmpty()) Text(stringResource(R.string.self_identity_empty))
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(people, key = { it.id }) { person ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = selected == person.id, enabled = !saving,
                                role = Role.RadioButton) { selected = person.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == person.id, onClick = null, enabled = !saving)
                            Column {
                                Text(person.name)
                                if (person.id == named.selfSubjectId) Text(stringResource(R.string.self_identity_current),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                TextButton(enabled = !saving, onClick = { onSave(null) }) {
                    Text(stringResource(R.string.self_identity_clear))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && selected != null && people.any { it.id == selected }, onClick = { onSave(selected) }) {
                Text(stringResource(R.string.self_identity_action))
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.self_identity_cancel)) }
        },
    )
}
