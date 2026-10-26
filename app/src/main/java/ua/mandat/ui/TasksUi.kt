package ua.mandat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.mandat.R
import ua.mandat.data.Delegation
import ua.mandat.data.MandatDb
import ua.mandat.data.MatterTitle
import ua.mandat.data.Person
import ua.mandat.data.Promise
import ua.mandat.data.TaskStatus
import ua.mandat.data.isOverdue

/** One line for a delegation or promise: a tick to close it, the text, and who/when. */
@Composable
fun TaskRow(
    what: String,
    detail: String,
    due: Long?,
    status: String,
    onToggleDone: () -> Unit,
    onOpen: () -> Unit,
) {
    val today = Dates.today()
    val overdue = isOverdue(due, status, today)
    val done = TaskStatus.of(status) != TaskStatus.OPEN
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = done, onCheckedChange = { onToggleDone() })
        Column(Modifier.weight(1f)) {
            Text(
                what,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
            )
            val dueText = due?.let { Dates.format(it) + if (overdue) " · " + stringResource(R.string.overdue_label) else "" }
            val line = listOfNotNull(detail.ifBlank { null }, dueText).joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(
                    line,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
fun DelegationRow(d: Delegation, personName: String?, matterTitle: String?, session: Session) {
    TaskRow(
        what = d.what,
        detail = listOfNotNull(personName, matterTitle).joinToString(" · "),
        due = d.due,
        status = d.status,
        onToggleDone = {
            val next = if (TaskStatus.of(d.status) == TaskStatus.OPEN) TaskStatus.DONE else TaskStatus.OPEN
            session.write {
                it.delegations().update(
                    d.copy(status = next.name, closedAt = if (next == TaskStatus.OPEN) null else System.currentTimeMillis()),
                )
            }
        },
        onOpen = { session.delegationDraft = DelegationDraft(d, isNew = false) },
    )
}

@Composable
fun PromiseRow(p: Promise, personName: String?, matterTitle: String?, session: Session) {
    val whom = personName ?: p.toWhom.ifBlank { null }
    val kind = stringResource(if (p.isPublic) R.string.promise_public else R.string.promise_personal)
    TaskRow(
        what = p.what,
        detail = listOfNotNull(whom, kind, matterTitle).joinToString(" · "),
        due = p.due,
        status = p.status,
        onToggleDone = {
            val next = if (TaskStatus.of(p.status) == TaskStatus.OPEN) TaskStatus.DONE else TaskStatus.OPEN
            session.write {
                it.promises().update(
                    p.copy(status = next.name, closedAt = if (next == TaskStatus.OPEN) null else System.currentTimeMillis()),
                )
            }
        },
        onOpen = { session.promiseDraft = PromiseDraft(p, isNew = false) },
    )
}

/** Choose a person from Mandat (team first), or nobody. */
@Composable
fun PersonPicker(people: List<Person>, current: Long?, onPick: (Long?) -> Unit) {
    val none = stringResource(R.string.nobody)
    Picker(
        label = stringResource(R.string.to_whom),
        current = people.firstOrNull { it.id == current }?.name ?: none,
        options = listOf<Pair<Long?, String>>(null to none) + people.map { it.id to it.name },
        onPick = onPick,
    )
}

@Composable
fun MatterPicker(matters: List<MatterTitle>, current: Long?, onPick: (Long?) -> Unit) {
    val none = stringResource(R.string.no_matter)
    Picker(
        label = stringResource(R.string.matter_link),
        current = matters.firstOrNull { it.id == current }?.title ?: none,
        options = listOf<Pair<Long?, String>>(null to none) + matters.map { it.id to it.title },
        onPick = onPick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueField(due: Long?, onChange: (Long?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.due), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { picking = true }) {
                Text(due?.let { Dates.format(it) } ?: stringResource(R.string.pick_date))
            }
            if (due != null) TextButton(onClick = { onChange(null) }) { Text(stringResource(R.string.clear_date)) }
        }
    }
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = due?.let { Dates.toPicker(it) })
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Dates.fromPicker(it)) }
                    picking = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusChips(current: TaskStatus, label: (TaskStatus) -> Int, onPick: (TaskStatus) -> Unit) {
    Text(stringResource(R.string.status), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TaskStatus.entries.forEach { s ->
            FilterChip(selected = current == s, onClick = { onPick(s) }, label = { Text(stringResource(label(s))) })
        }
    }
}

/** The delegation dialog; its content lives in the Session and is saved on lock. */
@Composable
fun DelegationDialog(db: MandatDb, session: Session, draft: DelegationDraft) {
    val peopleFlow = remember(db) { db.people().observeAll() }
    val mattersFlow = remember(db) { db.matters().observeActiveTitles() }
    val people by peopleFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val matters by mattersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { session.delegationDraft = null },
        title = { Text(stringResource(R.string.delegation_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.what, onValueChange = { draft.what = it },
                    label = { Text(stringResource(R.string.what)) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
                PersonPicker(people, draft.personId) { draft.personId = it }
                MatterPicker(matters, draft.matterId) { draft.matterId = it }
                DueField(draft.due) { draft.due = it }
                if (!draft.isNew) {
                    StatusChips(draft.status, { it.delegationLabel() }) { draft.status = it }
                    TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { session.saveDelegationDraft() }, enabled = draft.what.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = { session.delegationDraft = null }) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmDelete) {
        ConfirmDelete(onConfirm = {
            val id = draft.delegation.id
            session.delegationDraft = null
            session.write { it.delegations().delete(id) }
        }, onCancel = { confirmDelete = false })
    }
}

/** The promise dialog; same rules as [DelegationDialog]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromiseDialog(db: MandatDb, session: Session, draft: PromiseDraft) {
    val peopleFlow = remember(db) { db.people().observeAll() }
    val mattersFlow = remember(db) { db.matters().observeActiveTitles() }
    val people by peopleFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val matters by mattersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { session.promiseDraft = null },
        title = { Text(stringResource(R.string.promise_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.what, onValueChange = { draft.what = it },
                    label = { Text(stringResource(R.string.what)) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
                PersonPicker(people, draft.personId) { draft.personId = it }
                if (draft.personId == null) {
                    OutlinedTextField(
                        value = draft.toWhom, onValueChange = { draft.toWhom = it },
                        label = { Text(stringResource(R.string.to_whom_text)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !draft.isPublic, onClick = { draft.isPublic = false },
                        label = { Text(stringResource(R.string.promise_personal)) })
                    FilterChip(selected = draft.isPublic, onClick = { draft.isPublic = true },
                        label = { Text(stringResource(R.string.promise_public)) })
                }
                MatterPicker(matters, draft.matterId) { draft.matterId = it }
                DueField(draft.due) { draft.due = it }
                if (!draft.isNew) {
                    StatusChips(draft.status, { it.promiseLabel() }) { draft.status = it }
                    TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { session.savePromiseDraft() }, enabled = draft.what.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = { session.promiseDraft = null }) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmDelete) {
        ConfirmDelete(onConfirm = {
            val id = draft.promise.id
            session.promiseDraft = null
            session.write { it.promises().delete(id) }
        }, onCancel = { confirmDelete = false })
    }
}

@Composable
fun ConfirmDelete(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        text = { Text(stringResource(R.string.delete) + "?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Pick a person to add to a matter, with a search box (useful with many people). */
@Composable
fun PersonSearchDialog(people: List<Person>, onPick: (Person) -> Unit, onCancel: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = people.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.organization.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.pick_person)) },
        text = {
            Column {
                if (people.isEmpty()) {
                    Text(stringResource(R.string.no_people_to_pick))
                } else {
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(shown, key = { it.id }) { p ->
                            Column(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 10.dp)) {
                                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                val meta = listOf(p.jobTitle, p.organization).filter { it.isNotBlank() }.joinToString(" · ")
                                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
