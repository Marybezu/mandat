package ua.mandat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import ua.mandat.data.Deadline
import ua.mandat.data.DeadlineKind
import ua.mandat.data.InboxItem
import ua.mandat.data.MandatDb
import ua.mandat.data.TaskStatus
import ua.mandat.data.cleanInboxText

@Composable
fun DeadlineRow(d: Deadline, matterTitle: String?, session: Session) {
    val hard = DeadlineKind.of(d.kind) == DeadlineKind.HARD
    val kind = stringResource(if (hard) R.string.deadline_hard else R.string.deadline_soft)
    TaskRow(
        what = d.title,
        detail = listOfNotNull(kind, d.basis.ifBlank { null }, matterTitle).joinToString(" · "),
        due = d.day,
        status = d.status,
        onToggleDone = {
            val next = if (TaskStatus.of(d.status) == TaskStatus.OPEN) TaskStatus.DONE else TaskStatus.OPEN
            session.write {
                it.deadlines().update(d.copy(status = next.name, closedAt = if (next == TaskStatus.OPEN) null else System.currentTimeMillis()))
            }
        },
        onOpen = { session.deadlineDraft = DeadlineDraft(d, isNew = false) },
    )
}

/** The deadline dialog. A hard deadline cannot be saved without its basis. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeadlineDialog(db: MandatDb, session: Session, draft: DeadlineDraft) {
    val mattersFlow = remember(db) { db.matters().observeActiveTitles() }
    val allFlow = remember(db) { db.matters().observeAllTitles() }
    val matters by mattersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val allMatters by allFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { session.deadlineDraft = null },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.deadline)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.title, onValueChange = { draft.title = it },
                    label = { Text(stringResource(R.string.what)) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = draft.kind == DeadlineKind.SOFT, onClick = { draft.kind = DeadlineKind.SOFT },
                        label = { Text(stringResource(R.string.deadline_soft)) })
                    FilterChip(selected = draft.kind == DeadlineKind.HARD, onClick = { draft.kind = DeadlineKind.HARD },
                        label = { Text(stringResource(R.string.deadline_hard)) })
                }
                if (draft.kind == DeadlineKind.HARD) {
                    OutlinedTextField(
                        value = draft.basis, onValueChange = { draft.basis = it },
                        label = { Text(stringResource(R.string.deadline_basis)) },
                        supportingText = { Text(stringResource(R.string.deadline_basis_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                DueField(draft.day, allowClear = false) { if (it != null) draft.day = it }
                Picker(
                    label = stringResource(R.string.remind_before),
                    current = remindLabel(draft.remindBefore),
                    options = listOf(0, 1, 2, 3, 5, 7, 14, 30).map { it to remindLabel(it) },
                    onPick = { draft.remindBefore = it },
                )
                MatterPicker(matters, allMatters, draft.matterId) { draft.matterId = it }
                if (!draft.isNew) {
                    Text(stringResource(R.string.status), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TASK_STATUSES.forEach { s ->
                            FilterChip(selected = draft.status == s, onClick = { draft.status = s },
                                label = { Text(stringResource(s.delegationLabel())) })
                        }
                    }
                    TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { session.saveDeadlineDraft() }, enabled = draft.valid()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = { session.deadlineDraft = null }) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmDelete) {
        ConfirmDelete(onConfirm = {
            val id = draft.deadline.id
            session.deadlineDraft = null
            session.write { it.deadlines().delete(id) }
        }, onCancel = { confirmDelete = false })
    }
}

@Composable
private fun remindLabel(days: Int): String =
    if (days == 0) stringResource(R.string.remind_same_day) else stringResource(R.string.remind_days_before, days)

/** Time: deadlines, delegations and promises with dates, in order. */
@Composable
fun TimeScreen(db: MandatDb, session: Session) {
    val deadlinesFlow = remember(db) { db.deadlines().observeOpen() }
    val delegationsFlow = remember(db) { db.delegations().observeOpen() }
    val promisesFlow = remember(db) { db.promises().observeOpen() }
    val peopleFlow = remember(db) { db.people().observeAll() }
    val titlesFlow = remember(db) { db.matters().observeAllTitles() }
    val deadlines by deadlinesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val delegations by delegationsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val promises by promisesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val people by peopleFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val titles by titlesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val nameOf = people.associate { it.id to it.name }
    val titleOf = titles.associate { it.id to it.title }
    val today = Dates.today()

    // Each item with its day; items without a date are not on this screen
    data class Entry(val key: String, val day: Long, val hard: Boolean, val content: @Composable () -> Unit)
    val entries = buildList {
        deadlines.forEach { d -> add(Entry("d${d.id}", d.day, DeadlineKind.of(d.kind) == DeadlineKind.HARD) { DeadlineRow(d, d.matterId?.let(titleOf::get), session) }) }
        delegations.filter { it.due != null }.forEach { d -> add(Entry("g${d.id}", d.due!!, false) { DelegationRow(d, d.personId?.let(nameOf::get), d.matterId?.let(titleOf::get), session) }) }
        promises.filter { it.due != null }.forEach { p -> add(Entry("p${p.id}", p.due!!, false) { PromiseRow(p, p.personId?.let(nameOf::get), p.matterId?.let(titleOf::get), session) }) }
    }.sortedWith(compareBy({ it.day }, { !it.hard }))

    val groups = listOf(
        R.string.overdue_title to entries.filter { it.day < today },
        R.string.time_today to entries.filter { it.day == today },
        R.string.time_week to entries.filter { it.day in (today + 1)..(today + 7) },
        R.string.time_later to entries.filter { it.day > today + 7 },
    )
    if (entries.isEmpty()) {
        Column(Modifier.padding(Space.screen)) { EmptyHint(Sym.calendar, stringResource(R.string.time_empty)) }
        return
    }
    val allDeadlines by remember(db) { db.deadlines().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val month = ua.mandat.data.spanOf(ua.mandat.data.Period.MONTH, today)
    val dueMonth = allDeadlines.filter {
        it.day in month && it.day <= today && ua.mandat.data.TaskStatus.of(it.status) != ua.mandat.data.TaskStatus.CANCELLED
    }
    val onTimeMonth = dueMonth.count {
        ua.mandat.data.TaskStatus.of(it.status) == ua.mandat.data.TaskStatus.DONE && it.closedAt != null && Dates.dayOf(it.closedAt) <= it.day
    }
    val week = ua.mandat.data.spanOf(ua.mandat.data.Period.WEEK, today)
    LazyColumn(contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 96.dp)) {
        item(key = "strip") {
            SummaryStrip(
                "time",
                listOfNotNull(
                    StripItem(entries.count { it.day in today..week.to }.toString(), stringResource(R.string.strip_this_week)),
                    StripItem(entries.count { it.day in (week.to + 1)..(week.to + 7) }.toString(), stringResource(R.string.strip_next_week)),
                    if (dueMonth.isNotEmpty()) StripItem("$onTimeMonth/${dueMonth.size}", stringResource(R.string.strip_on_time_month)) else null,
                ),
                Modifier.padding(top = 8.dp),
            )
        }
        groups.forEach { (title, list) ->
            if (list.isNotEmpty()) {
                item(key = "h$title") {
                    val overdue = title == R.string.overdue_title
                    SectionHeader(if (overdue) Sym.warning else Sym.schedule, stringResource(title), attention = overdue)
                }
                items(list, key = { it.key }) { it.content() }
            }
        }
    }
}

/** Review: the evening question, then the answers of earlier days. */
@Composable
fun ReviewScreen(db: MandatDb, session: Session) {
    val recentFlow = remember(db) { db.reviews().observeRecent() }
    val recent by recentFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val today = Dates.today()
    val todays = recent.firstOrNull { it.day == today }
    var unload by remember { mutableStateOf("") }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "dash") { Dashboard(db, session) }
        item(key = "evening") {
            MCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.evening_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.evening_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary)
                    OutlinedTextField(
                        value = session.reviewDraft ?: todays?.progress.orEmpty(),
                        onValueChange = { session.reviewDraft = it },
                        label = { Text(stringResource(R.string.evening_progress)) },
                        minLines = 3, modifier = Modifier.fillMaxWidth(),
                    )
                    if (session.reviewDraft != null) {
                        Button(onClick = { session.saveReviewDraft() }) { Text(stringResource(R.string.save)) }
                    }
                    OutlinedTextField(
                        value = unload, onValueChange = { unload = it },
                        label = { Text(stringResource(R.string.evening_unload)) },
                        minLines = 2, modifier = Modifier.fillMaxWidth(),
                    )
                    if (unload.isNotBlank()) {
                        TextButton(onClick = {
                            val clean = cleanInboxText(unload)
                            unload = ""
                            if (clean != null) session.write { it.inbox().insert(InboxItem(text = clean, createdAt = System.currentTimeMillis())) }
                        }) { Text(stringResource(R.string.evening_to_inbox)) }
                    }
                }
            }
        }
        item(key = "promises") {
            OutlinedButton(onClick = { session.showPromises = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.my_promises))
            }
        }
        val earlier = recent.filter { it.day != today }
        if (earlier.isNotEmpty()) {
            item(key = "earlier_h") { SectionHeader(Sym.calendar, stringResource(R.string.evening_earlier)) }
            items(earlier, key = { it.id }) { r ->
                Column {
                    Text(Dates.format(r.day), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    Text(r.progress, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** Search across everything; the query lives only in memory. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(db: MandatDb, session: Session) {
    BackHandler { session.searching = false }
    var query by remember { mutableStateOf("") }
    val trimmed = query.trim()
    val flow = remember(db) { db.search().everything() }
    val rows by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val hits = remember(rows, trimmed) { ua.mandat.data.search(rows, trimmed) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth(),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { session.searching = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (trimmed.length < 2) {
            Text(stringResource(R.string.search_hint), modifier = Modifier.padding(padding).padding(16.dp),
                color = MaterialTheme.colorScheme.secondary)
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).imePadding().fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            if (hits.isEmpty()) item { Text(stringResource(R.string.search_nothing)) }
            items(hits, key = { "${it.kind}${it.id}" }) { hit ->
                Column(
                    Modifier.fillMaxWidth().clickable { openHit(db, session, hit.kind, hit.id) }.padding(vertical = 8.dp),
                ) {
                    Text(hit.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val label = stringResource(kindLabel(hit.kind))
                    Text(listOf(label, hit.detail).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun kindLabel(kind: String): Int = when (kind) {
    "matter" -> R.string.matter_edit
    "person" -> R.string.person_edit
    "inbox" -> R.string.inbox
    "delegation" -> R.string.delegation_new
    "promise" -> R.string.promise_new
    "deadline" -> R.string.deadline
    else -> R.string.tab_review
}

private fun openHit(db: MandatDb, session: Session, kind: String, id: Long) {
    when (kind) {
        "matter" -> { session.searching = false; session.openMatter(id) }
        "person" -> { session.searching = false; session.openPerson(id) }
        "delegation" -> session.openTask(kind, id)
        "promise" -> session.openTask(kind, id)
        "deadline" -> session.openTask(kind, id)
        else -> session.searching = false
    }
}

/** All promises ever made: public, personal or all; open, kept and cancelled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromisesScreen(db: MandatDb, session: Session) {
    BackHandler { session.showPromises = false }
    val allFlow = remember(db) { db.promises().observeAll() }
    val peopleFlow = remember(db) { db.people().observeAll() }
    val titlesFlow = remember(db) { db.matters().observeAllTitles() }
    val all by allFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val people by peopleFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val titles by titlesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val nameOf = people.associate { it.id to it.name }
    val titleOf = titles.associate { it.id to it.title }
    // true = public only, false = personal only, null = all
    var publicOnly by remember { mutableStateOf<Boolean?>(true) }
    var year by remember { mutableStateOf<Int?>(null) }
    var query by remember { mutableStateOf("") }

    fun yearOf(p: ua.mandat.data.Promise) = java.time.LocalDate.ofEpochDay(Dates.dayOf(p.createdAt)).year
    val years = all.map(::yearOf).distinct().sortedDescending()
    val q = query.trim().lowercase()
    val shown = all.filter { p ->
        (publicOnly == null || p.isPublic == publicOnly) &&
            (year == null || yearOf(p) == year) &&
            (q.length < 2 || listOf(p.what, p.toWhom, p.source, p.personId?.let(nameOf::get).orEmpty())
                .any { it.lowercase().contains(q) })
    }
    val byStatus = TaskStatus.entries.associateWith { s -> shown.filter { TaskStatus.of(it.status) == s } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.my_promises)) },
                navigationIcon = {
                    IconButton(onClick = { session.showPromises = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).imePadding().fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item(key = "filters") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = publicOnly == true, onClick = { publicOnly = true },
                        label = { Text(stringResource(R.string.promises_public)) })
                    FilterChip(selected = publicOnly == false, onClick = { publicOnly = false },
                        label = { Text(stringResource(R.string.promises_personal)) })
                    FilterChip(selected = publicOnly == null, onClick = { publicOnly = null },
                        label = { Text(stringResource(R.string.matters_all_roles)) })
                }
            }
            if (years.size > 1) item(key = "years") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = year == null, onClick = { year = null }, label = { Text(stringResource(R.string.all_years)) })
                    years.forEach { y -> FilterChip(selected = year == y, onClick = { year = y }, label = { Text(y.toString()) }) }
                }
            }
            item(key = "query") {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.search)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            item(key = "summary") {
                Text(
                    stringResource(
                        R.string.promises_summary, shown.size,
                        byStatus.getValue(TaskStatus.DONE).size,
                        byStatus.getValue(TaskStatus.OPEN).size,
                        byStatus.getValue(TaskStatus.FAILED).size,
                        byStatus.getValue(TaskStatus.CANCELLED).size,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            if (shown.isEmpty()) item(key = "empty") { Text(stringResource(R.string.promises_empty), color = MaterialTheme.colorScheme.secondary) }
            listOf(TaskStatus.OPEN, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED).forEach { status ->
                val list = byStatus.getValue(status)
                if (list.isNotEmpty()) {
                    item(key = "h$status") {
                            SectionHeader(Sym.checkCircle, stringResource(status.promiseLabel()))
                    }
                    items(list, key = { "p${it.id}" }) { p ->
                        Column {
                            PromiseRow(p, p.personId?.let(nameOf::get), p.matterId?.let(titleOf::get), session)
                            val origin = listOf(stringResource(R.string.promised_on, Dates.format(Dates.dayOf(p.createdAt))), p.source, p.statusNote)
                                .filter { it.isNotBlank() }.joinToString(" · ")
                            Text(origin,
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(start = 48.dp, bottom = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
