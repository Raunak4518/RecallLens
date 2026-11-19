package org.photolibrary.app.library

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.photolibrary.app.R
import org.photolibrary.app.subjects.DatedAliasDraft
import org.photolibrary.app.subjects.DatedSubjectAlias
import org.photolibrary.app.subjects.PhotoRegion
import org.photolibrary.app.subjects.SubjectKind
import org.photolibrary.app.subjects.SubjectSelection
import org.photolibrary.app.subjects.validSubjectLabel

@Composable
internal fun SubjectEditor(
    photo: SelectedPhoto,
    state: LibraryState,
    loadImage: suspend (Uri, Int) -> Bitmap?,
    onDismiss: () -> Unit,
    onSave: (SubjectSelection, PhotoRegion?) -> Unit,
    onRemove: (Long) -> Unit,
    suggestion: NameSuggestion? = null,
    onRemoveDatedAlias: (DatedSubjectAlias) -> Unit = {},
) {
    var name by remember(photo.uri) { mutableStateOf("") }
    var aliases by remember(photo.uri) { mutableStateOf("") }
    var kind by remember(photo.uri, suggestion) { mutableStateOf(suggestion?.detection?.kind ?: SubjectKind.OBJECT) }
    var useExisting by remember(photo.uri) { mutableStateOf(false) }
    var filter by remember(photo.uri) { mutableStateOf("") }
    var existingId by remember(photo.uri) { mutableStateOf<Long?>(null) }
    var setCollege by remember(photo.uri) { mutableStateOf(false) }
    var dated by remember(photo.uri) { mutableStateOf(false) }
    var datedLabel by remember(photo.uri) { mutableStateOf("") }
    var firstYear by remember(photo.uri) { mutableStateOf("") }
    var lastYear by remember(photo.uri) { mutableStateOf("") }
    var region by remember(photo.uri, photo.sourceRevision, state.imageRevision, suggestion) {
        mutableStateOf(suggestion?.detection?.region)
    }
    val chosen = state.named.subjects.firstOrNull { it.id == existingId }
    val canSetCollege = (if (useExisting) chosen?.kind else kind) == SubjectKind.ORGANIZATION
    val alternateNames = aliases.split(',').map(String::trim).filter(String::isNotEmpty)
    val currentCollege = canSetCollege && setCollege
    val period = if (dated) DatedAliasDraft(datedLabel.trim(), firstYear.toIntOrNull() ?: 0, lastYear.toIntOrNull() ?: 0) else null
    val selection = if (period != null && !period.valid) null
        else if (useExisting) chosen?.let { SubjectSelection.Existing(it.id, currentCollege, period) }
        else if (validSubjectLabel(name.trim()) && alternateNames.size <= 12 && alternateNames.all(::validSubjectLabel)) {
            SubjectSelection.New(name.trim(), kind, alternateNames, currentCollege, period)
        } else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subject_editor_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoImage(photo.uri, 640, state.imageRevision, loadImage, ContentScale.Fit,
                    Modifier.fillMaxWidth().height(150.dp), regionPicker = { bitmap ->
                        RegionPicker(bitmap, region) { region = it }
                    })
                Text(stringResource(if (suggestion == null) R.string.subject_area_hint else R.string.suggestions_adjust),
                    style = MaterialTheme.typography.bodySmall)
                if (region != null && suggestion == null) TextButton(onClick = { region = null }) {
                    Text(stringResource(R.string.subject_clear_area))
                }
                if (state.named.subjects.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!useExisting, onClick = { useExisting = false }, label = { Text(stringResource(R.string.subject_new)) })
                        FilterChip(useExisting, onClick = { useExisting = true }, label = { Text(stringResource(R.string.subject_existing)) })
                    }
                }
                if (useExisting) {
                    OutlinedTextField(filter, { filter = it.take(80); existingId = null },
                        label = { Text(stringResource(R.string.subject_find)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                    val subjects = state.named.subjects.filter { subject ->
                        subject.name.contains(filter, ignoreCase = true) || subject.aliases.any { it.contains(filter, ignoreCase = true) }
                    }
                    Column(Modifier.heightIn(max = 156.dp).verticalScroll(rememberScrollState())) {
                        subjects.forEach { subject ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = existingId == subject.id, onClick = { existingId = subject.id })
                                TextButton(onClick = { existingId = subject.id }) { Text(subject.name) }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.subject_name)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(aliases, { aliases = it.take(500) }, label = { Text(stringResource(R.string.subject_aliases)) },
                        supportingText = { Text(stringResource(R.string.subject_alias_hint)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                    SubjectKindPicker(kind) { kind = it }
                }
                if (canSetCollege) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = setCollege, onCheckedChange = { setCollege = it })
                        Text(stringResource(R.string.subject_set_college))
                    }
                }
                TextButton(onClick = {
                    dated = !dated
                    if (dated && currentCollege && datedLabel.isBlank()) datedLabel = "my college"
                }) { Text(stringResource(if (dated) R.string.subject_dated_alias_hide else R.string.subject_dated_alias_add)) }
                if (dated) {
                    OutlinedTextField(datedLabel, { datedLabel = it.take(80) }, singleLine = true,
                        label = { Text(stringResource(R.string.subject_dated_alias_label)) })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val numeric = KeyboardOptions(keyboardType = KeyboardType.Number)
                        OutlinedTextField(firstYear, { firstYear = it.filter { char -> char in '0'..'9' }.take(4) },
                            modifier = Modifier.weight(1f), keyboardOptions = numeric, singleLine = true,
                            label = { Text(stringResource(R.string.subject_dated_alias_from)) })
                        OutlinedTextField(lastYear, { lastYear = it.filter { char -> char in '0'..'9' }.take(4) },
                            modifier = Modifier.weight(1f), keyboardOptions = numeric, singleLine = true,
                            label = { Text(stringResource(R.string.subject_dated_alias_to)) })
                    }
                }
                state.namingError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                if (suggestion == null) {
                    for (subject in state.named.subjectsIn(photo.uri)) {
                        for (alias in state.named.datedAliases.filter { it.subjectId == subject.id }) {
                            val span = alias.period
                            TextButton(onClick = { onRemoveDatedAlias(alias) }, enabled = !state.savingSubject) {
                                Text(stringResource(R.string.subject_dated_alias_remove, span.label, span.firstYear, span.lastYear))
                            }
                        }
                        TextButton(onClick = { onRemove(subject.id) }, enabled = !state.savingSubject) {
                            Text(stringResource(R.string.subject_remove, subject.name))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { selection?.let { onSave(it, region) } }, enabled = selection != null && !state.savingSubject) {
                Text(stringResource(R.string.subject_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.savingSubject) { Text(stringResource(R.string.subject_cancel)) } },
    )
}

@Composable
private fun SubjectKindPicker(kind: SubjectKind, onSelect: (SubjectKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.subject_kind, stringResource(kind.label()))) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            for (option in SubjectKind.entries) {
                DropdownMenuItem(text = { Text(stringResource(option.label())) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

private fun SubjectKind.label() = when (this) {
    SubjectKind.OBJECT -> R.string.subject_kind_object
    SubjectKind.PERSON -> R.string.subject_kind_person
    SubjectKind.GROUP -> R.string.subject_kind_group
    SubjectKind.DOCUMENT -> R.string.subject_kind_document
    SubjectKind.CLOTHING -> R.string.subject_kind_clothing
    SubjectKind.PLACE -> R.string.subject_kind_place
    SubjectKind.ORGANIZATION -> R.string.subject_kind_organization
    SubjectKind.MEME -> R.string.subject_kind_meme
    SubjectKind.OTHER -> R.string.subject_kind_other
}
