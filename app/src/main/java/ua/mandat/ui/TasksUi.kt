package ua.mandat.ui

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
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
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.mandat.R
import ua.mandat.data.Delegation
import ua.mandat.data.MandatDb
import ua.mandat.data.MatterTitle
import ua.mandat.data.Person
import ua.mandat.data.Promise
import ua.mandat.data.TaskStatus
import ua.mandat.data.isOverdue

/** What a row is; each kind has its own colour and picture, readable at a glance. */
enum class TaskKind { HARD, SOFT, DELEGATION, PROMISE }

@Composable
private fun TaskKind.badge(): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    fun c(v: Long) = androidx.compose.ui.graphics.Color(v)
    return when (this) {
        TaskKind.HARD -> FlagBlue to c(0xFFFFFFFF)
        TaskKind.SOFT -> if (dark) c(0xFF042C53) to c(0xFFB5D4F4) else c(0xFFE6F1FB) to c(0xFF0C447C)
        TaskKind.DELEGATION -> if (dark) c(0xFF04342C) to c(0xFF9FE1CB) else c(0xFFE1F5EE) to c(0xFF085041)
        TaskKind.PROMISE -> if (dark) c(0xFF412402) to c(0xFFFAC775) else c(0xFFFAEEDA) to c(0xFF633806)
    }
}

private fun TaskKind.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    TaskKind.HARD, TaskKind.SOFT -> androidx.compose.material.icons.Icons.Filled.DateRange
    TaskKind.DELEGATION -> androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowForward
    TaskKind.PROMISE -> androidx.compose.material.icons.Icons.Filled.ThumbUp
}

/** A small label inside a row: a person, a matter, a date. */
@Composable
fun MiniChip(
    text: String,
    bg: androidx.compose.ui.graphics.Color? = null,
    fg: androidx.compose.ui.graphics.Color? = null,
    onClick: (() -> Unit)? = null,
) {
    Text(
        // A label that leads somewhere (a person, a matter) says so with an arrow
        if (onClick != null) "$text →" else text,
        style = MaterialTheme.typography.labelSmall,
        color = fg ?: if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .background(bg ?: MaterialTheme.colorScheme.surface)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** The due date as a label: "today", "tomorrow", "overdue N days" or the date. */
@Composable
fun DueChip(due: Long, open: Boolean) {
    val today = Dates.today()
    val scheme = MaterialTheme.colorScheme
    when {
        open && due < today -> MiniChip(stringResource(R.string.overdue_days, (today - due).toInt()), scheme.errorContainer, scheme.onErrorContainer)
        due == today -> MiniChip(stringResource(R.string.due_today), scheme.primaryContainer, scheme.onPrimaryContainer)
        due == today + 1 -> MiniChip(stringResource(R.string.due_tomorrow), scheme.primaryContainer, scheme.onPrimaryContainer)
        else -> MiniChip(Dates.short(due))
    }
}

/**
 * One deadline, delegation or promise as a tile: a coloured picture of its kind, the text,
 * small labels (who, which matter, when), and a tick to close it.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TaskRow(
    what: String,
    kind: TaskKind,
    chips: List<String>,
    due: Long?,
    status: String,
    onToggleDone: () -> Unit,
    onOpen: () -> Unit,
    extra: String? = null,
    links: Map<String, () -> Unit> = emptyMap(),
) {
    val done = TaskStatus.of(status) != TaskStatus.OPEN
    val (bg, fg) = kind.badge()
    Row(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onOpen).padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(kind.icon(), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        }
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                what,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            )
            val labels = chips.filter { it.isNotBlank() }
            if (labels.isNotEmpty() || due != null) {
                androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (due != null) DueChip(due, !done)
                    labels.forEach { MiniChip(it, onClick = links[it]) }
                }
            }
            if (!extra.isNullOrBlank()) {
                Text(extra, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Checkbox(checked = done, onCheckedChange = { onToggleDone() })
    }
}

@Composable
fun DelegationRow(d: Delegation, personName: String?, matterTitle: String?, session: Session) {
    TaskRow(
        what = d.what,
        kind = TaskKind.DELEGATION,
        chips = listOfNotNull(
            personName, matterTitle,
            d.checkIn?.takeIf { TaskStatus.of(d.status) == TaskStatus.OPEN }?.let { stringResource(R.string.check_in_chip, Dates.short(it)) },
            if (d.returned) stringResource(R.string.returned_chip) else null,
            ua.mandat.data.Freedom.of(d.freedom)?.let { stringResource(it.label()) },
        ),
        due = d.due,
        status = d.status,
        onToggleDone = {
            val next = if (TaskStatus.of(d.status) == TaskStatus.OPEN) TaskStatus.DONE else TaskStatus.OPEN
            // Done by someone: a gentle offer to thank them
            if (next == TaskStatus.DONE && d.personId != null && !d.thanked) session.thankFor = d
            session.write {
                it.delegations().update(
                    d.copy(status = next.name, closedAt = if (next == TaskStatus.OPEN) null else System.currentTimeMillis()),
                )
            }
        },
        onOpen = { session.delegationDraft = DelegationDraft(d, isNew = false) },
        links = buildMap {
            if (personName != null && d.personId != null) put(personName) { session.openPerson(d.personId) }
            if (matterTitle != null && d.matterId != null) put(matterTitle) { session.openMatter(d.matterId) }
        },
    )
}

@Composable
fun PromiseRow(p: Promise, personName: String?, matterTitle: String?, session: Session, extra: String? = null) {
    val whom = personName ?: p.toWhom.ifBlank { null }
    val publicLabel = stringResource(R.string.chip_public)
    TaskRow(
        what = p.what,
        kind = TaskKind.PROMISE,
        chips = listOfNotNull(if (p.isPublic) publicLabel else null, whom, matterTitle),
        due = p.due,
        status = p.status,
        onToggleDone = {
            val next = if (TaskStatus.of(p.status) == TaskStatus.OPEN) TaskStatus.DONE else TaskStatus.OPEN
            session.write {
                // Back to open or kept: an old "why it failed" note no longer applies
                it.promises().update(
                    p.copy(status = next.name, statusNote = "", closedAt = if (next == TaskStatus.OPEN) null else System.currentTimeMillis()),
                )
            }
        },
        onOpen = { session.promiseDraft = PromiseDraft(p, isNew = false) },
        extra = extra,
        links = buildMap {
            if (personName != null && p.personId != null) put(personName) { session.openPerson(p.personId) }
            if (matterTitle != null && p.matterId != null) put(matterTitle) { session.openMatter(p.matterId) }
        },
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

/** Offers active matters; [all] (also closed ones) is used to show the current choice. */
@Composable
fun MatterPicker(active: List<MatterTitle>, all: List<MatterTitle>, current: Long?, onPick: (Long?) -> Unit) {
    val none = stringResource(R.string.no_matter)
    val currentTitle = all.firstOrNull { it.id == current }
    val choices = if (currentTitle != null && active.none { it.id == current }) active + currentTitle else active
    Picker(
        label = stringResource(R.string.matter_link),
        current = currentTitle?.title ?: none,
        options = listOf<Pair<Long?, String>>(null to none) + choices.map { it.id to it.title },
        onPick = onPick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueField(due: Long?, allowClear: Boolean = true, label: Int = R.string.due, onChange: (Long?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { picking = true }) {
                Text(due?.let { Dates.format(it) } ?: stringResource(R.string.pick_date))
            }
            if (due != null && allowClear) TextButton(onClick = { onChange(null) }) { Text(stringResource(R.string.clear_date)) }
        }
    }
    if (picking) {
        DayPickerDialog(due) { day ->
            picking = false
            if (day != null) onChange(day)
        }
    }
}

/** The system date picker; gives a calendar day, or null when cancelled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayPickerDialog(initial: Long?, onPick: (Long?) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial?.let { Dates.toPicker(it) })
    DatePickerDialog(
        onDismissRequest = { onPick(null) },
        confirmButton = {
            TextButton(onClick = { onPick(state.selectedDateMillis?.let { Dates.fromPicker(it) }) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.cancel)) } },
    ) { DatePicker(state = state) }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StatusChips(current: TaskStatus, options: List<TaskStatus>, label: (TaskStatus) -> Int, onPick: (TaskStatus) -> Unit) {
    Text(stringResource(R.string.status), style = MaterialTheme.typography.labelLarge)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { s ->
            FilterChip(selected = current == s, onClick = { onPick(s) }, label = { Text(stringResource(label(s))) })
        }
    }
}

/** The delegation dialog; its content lives in the Session and is saved on lock. */
@Composable
fun DelegationDialog(db: MandatDb, session: Session, draft: DelegationDraft) {
    val peopleFlow = remember(db) { db.people().observeAll() }
    val mattersFlow = remember(db) { db.matters().observeActiveTitles() }
    val allFlow = remember(db) { db.matters().observeAllTitles() }
    val allMatters by allFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val people by peopleFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val matters by mattersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { session.delegationDraft = null },
        // A tap outside must not throw away what was typed
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.delegation_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.what, onValueChange = { draft.what = it },
                    label = { Text(stringResource(R.string.what)) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
                PersonPicker(people, draft.personId) { draft.personId = it }
                // The intent and what "done" means: the person can act sensibly when things change
                OutlinedTextField(
                    value = draft.purpose, onValueChange = { draft.purpose = it },
                    label = { Text(stringResource(R.string.delegation_purpose)) }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.doneMeans, onValueChange = { draft.doneMeans = it },
                    label = { Text(stringResource(R.string.delegation_done_means)) }, modifier = Modifier.fillMaxWidth(),
                )
                FreedomPicker(draft.freedom) { draft.freedom = it }
                MatterPicker(matters, allMatters, draft.matterId) { draft.matterId = it }
                DueField(draft.due) { draft.due = it }
                DueField(draft.checkIn, label = R.string.delegation_check_in) { draft.checkIn = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.delegation_returned), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.delegation_returned_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                    androidx.compose.material3.Switch(checked = draft.returned, onCheckedChange = { draft.returned = it })
                }
                if (!draft.isNew) {
                    StatusChips(draft.status, TASK_STATUSES, { it.delegationLabel() }) { draft.status = it }
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

/** How much freedom the person has; tapping the chosen level again clears it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FreedomPicker(current: ua.mandat.data.Freedom?, onPick: (ua.mandat.data.Freedom?) -> Unit) {
    Column {
        Text(stringResource(R.string.delegation_freedom), style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ua.mandat.data.Freedom.entries.forEach { f ->
                FilterChip(selected = current == f, onClick = { onPick(if (current == f) null else f) }, label = { Text(stringResource(f.label())) })
            }
        }
        current?.let { Text(stringResource(it.hint()), style = MaterialTheme.typography.labelSmall, color = Muted) }
    }
}

/** The promise dialog; same rules as [DelegationDialog]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromiseDialog(db: MandatDb, session: Session, draft: PromiseDraft) {
    val peopleFlow = remember(db) { db.people().observeAll() }
    val mattersFlow = remember(db) { db.matters().observeActiveTitles() }
    val allFlow = remember(db) { db.matters().observeAllTitles() }
    val allMatters by allFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val people by peopleFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val matters by mattersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { session.promiseDraft = null },
        properties = DialogProperties(dismissOnClickOutside = false),
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
                OutlinedTextField(
                    value = draft.source, onValueChange = { draft.source = it },
                    label = { Text(stringResource(R.string.promise_source)) },
                    supportingText = { Text(stringResource(R.string.promise_source_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                DueField(draft.madeOn, allowClear = false, label = R.string.promise_made_on) { d -> d?.let { draft.madeOn = minOf(it, Dates.today()) } }
                MatterPicker(matters, allMatters, draft.matterId) { draft.matterId = it }
                DueField(draft.due) { draft.due = it }
                if (!draft.isNew) {
                    StatusChips(draft.status, TaskStatus.entries, { it.promiseLabel() }) { draft.status = it }
                    if (draft.status == TaskStatus.FAILED || draft.status == TaskStatus.CANCELLED) {
                        OutlinedTextField(
                            value = draft.statusNote, onValueChange = { draft.statusNote = it },
                            label = { Text(stringResource(R.string.promise_status_note)) }, minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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
