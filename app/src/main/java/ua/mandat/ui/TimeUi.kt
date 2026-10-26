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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.saveable.rememberSaveable
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
        kind = if (hard) TaskKind.HARD else TaskKind.SOFT,
        chips = listOfNotNull(kind, d.basis.ifBlank { null }, matterTitle),
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
    val deadlines by remember(db) { db.deadlines().observeOpen() }.collectAsStateWithLifecycle(emptyList())
    val delegations by remember(db) { db.delegations().observeOpen() }.collectAsStateWithLifecycle(emptyList())
    val promises by remember(db) { db.promises().observeOpen() }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles() }.collectAsStateWithLifecycle(emptyList())
    val allDeadlines by remember(db) { db.deadlines().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val nameOf = people.associate { it.id to it.name }
    val titleOf = titles.associate { it.id to it.title }
    val today = Dates.today()
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedDay by rememberSaveable { mutableStateOf<Long?>(null) }

    // Each item with its day and kind; items without a date are not on this screen
    data class Entry(val key: String, val day: Long, val kind: String, val content: @Composable () -> Unit)
    val all = buildList {
        deadlines.forEach { d ->
            add(Entry("d${d.id}", d.day, if (DeadlineKind.of(d.kind) == DeadlineKind.HARD) "HARD" else "SOFT") { DeadlineRow(d, d.matterId?.let(titleOf::get), session) })
        }
        delegations.filter { it.due != null }.forEach { d -> add(Entry("g${d.id}", d.due!!, "DELEGATION") { DelegationRow(d, d.personId?.let(nameOf::get), d.matterId?.let(titleOf::get), session) }) }
        promises.filter { it.due != null }.forEach { p -> add(Entry("p${p.id}", p.due!!, "PROMISE") { PromiseRow(p, p.personId?.let(nameOf::get), p.matterId?.let(titleOf::get), session) }) }
    }.sortedWith(compareBy({ it.day }, { it.kind != "HARD" }))

    if (all.isEmpty()) {
        Column(Modifier.padding(Space.screen)) { EmptyHint(Sym.calendar, stringResource(R.string.time_empty)) }
        return
    }
    val entries = all.filter { (kind == null || it.kind == kind) && (pickedDay == null || it.day == pickedDay) }

    // Deadlines of this month that are due by today: how many were met on time
    val month = ua.mandat.data.spanOf(ua.mandat.data.Period.MONTH, today)
    val dueMonth = allDeadlines.filter { it.day in month && it.day <= today && TaskStatus.of(it.status) != TaskStatus.CANCELLED }
    val onTimeMonth = dueMonth.count { TaskStatus.of(it.status) == TaskStatus.DONE && it.closedAt != null && Dates.dayOf(it.closedAt) <= it.day }

    val dayFmt = java.time.format.DateTimeFormatter.ofPattern("EEE")
    val days = (0L until 14L).map { i ->
        val d = today + i
        val onDay = all.filter { it.day == d && (kind == null || it.kind == kind) }
        DayLoad(d, java.time.LocalDate.ofEpochDay(d).format(dayFmt), onDay.size, onDay.any { it.kind == "HARD" })
    }

    val groups = listOf(
        R.string.overdue_title to entries.filter { it.day < today },
        R.string.time_today to entries.filter { it.day == today },
        R.string.time_week to entries.filter { it.day in (today + 1)..(today + 7) },
        R.string.time_later to entries.filter { it.day > today + 7 },
    )
    LazyColumn(
        contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Space.gap),
    ) {
        item(key = "days") {
            SectionCard(Sym.calendar, stringResource(R.string.next_14_days)) {
                Spacer(Modifier.height(6.dp))
                DayColumns(days, pickedDay) { pickedDay = it }
                Text(stringResource(R.string.hard_day_hint), style = MaterialTheme.typography.labelSmall, color = Muted,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
        item(key = "kinds") {
            SectionCard(Sym.layers, stringResource(R.string.ahead_title)) {
                Spacer(Modifier.height(4.dp))
                StackBar(
                    listOf(
                        Slice("HARD", stringResource(R.string.deadline_hard), all.count { it.kind == "HARD" }, ChartColors.blue),
                        Slice("SOFT", stringResource(R.string.deadline_soft), all.count { it.kind == "SOFT" }, ChartColors.blueLight),
                        Slice("DELEGATION", stringResource(R.string.delegations), all.count { it.kind == "DELEGATION" }, ChartColors.teal),
                        Slice("PROMISE", stringResource(R.string.promises), all.count { it.kind == "PROMISE" }, ChartColors.amber),
                    ),
                    selected = kind, onPick = { kind = it },
                )
            }
        }
        if (dueMonth.isNotEmpty()) {
            item(key = "ontime") {
                MCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RingStat("$onTimeMonth/${dueMonth.size}", "", onTimeMonth.toFloat() / dueMonth.size, ChartColors.blue)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.on_time_month_title), style = MaterialTheme.typography.titleMedium)
                            val late = dueMonth.size - onTimeMonth
                            if (late > 0) Text(stringResource(R.string.not_on_time, late), style = MaterialTheme.typography.labelMedium, color = Muted)
                        }
                    }
                }
            }
        }
        if (pickedDay != null) {
            item(key = "dayfilter") {
                Row { FilterBadge(Dates.format(pickedDay!!)) { pickedDay = null } }
            }
        }
        groups.forEach { (title, list) ->
            if (list.isNotEmpty()) item(key = "g$title") {
                val overdue = title == R.string.overdue_title
                SectionCard(if (overdue) Sym.warning else Sym.schedule, stringResource(title), count = list.size, attention = overdue) {
                    list.forEachIndexed { i, e -> if (i > 0) RowDivider(); e.content() }
                }
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
    var statusPick by remember { mutableStateOf<String?>(null) }
    // Public/personal and search apply everywhere; the year and the status chosen in a chart narrow the list
    val baseNoYear = all.filter { p ->
        (publicOnly == null || p.isPublic == publicOnly) &&
            (q.length < 2 || listOf(p.what, p.toWhom, p.source, p.personId?.let(nameOf::get).orEmpty())
                .any { it.lowercase().contains(q) })
    }
    val base = baseNoYear.filter { year == null || yearOf(it) == year }
    val yearsAsc = years.sorted()
    val byStatusAll = TaskStatus.entries.associateWith { s -> base.filter { TaskStatus.of(it.status) == s } }
    val shown = base.filter { statusPick == null || it.status == statusPick }
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
            item(key = "query") {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.search)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            item(key = "summary") {
                MCard(Modifier.padding(top = 10.dp)) {
                    DonutChart(
                        slices = listOf(
                            Slice(TaskStatus.DONE.name, stringResource(TaskStatus.DONE.promiseLabel()), byStatusAll.getValue(TaskStatus.DONE).size, ChartColors.blue),
                            Slice(TaskStatus.OPEN.name, stringResource(TaskStatus.OPEN.promiseLabel()), byStatusAll.getValue(TaskStatus.OPEN).size, ChartColors.blueLight),
                            Slice(TaskStatus.FAILED.name, stringResource(TaskStatus.FAILED.promiseLabel()), byStatusAll.getValue(TaskStatus.FAILED).size, ChartColors.salmon),
                            Slice(TaskStatus.CANCELLED.name, stringResource(TaskStatus.CANCELLED.promiseLabel()), byStatusAll.getValue(TaskStatus.CANCELLED).size, ChartColors.grayLight),
                        ),
                        center = base.size.toString(), centerLabel = stringResource(R.string.promises),
                        selected = statusPick, onPick = { statusPick = it },
                    )
                }
            }
            if (yearsAsc.size > 1) item(key = "byyear") {
                SectionCard(Sym.calendar, stringResource(R.string.dash_years_title), Modifier.padding(top = 8.dp)) {
                    Spacer(Modifier.height(6.dp))
                    DayColumns(
                        yearsAsc.map { y -> DayLoad(y.toLong(), "’" + (y % 100).toString().padStart(2, '0'), baseNoYear.count { yearOf(it) == y }, false) },
                        selected = year?.toLong(), onPick = { year = it?.toInt() },
                    )
                }
            }
            if (shown.isEmpty()) item(key = "empty") { Text(stringResource(R.string.promises_empty), color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 12.dp)) }
            listOf(TaskStatus.OPEN, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED).forEach { status ->
                val list = byStatus.getValue(status)
                if (list.isNotEmpty()) item(key = "g$status") {
                    SectionCard(Sym.checkCircle, stringResource(status.promiseLabel()), Modifier.padding(top = 8.dp), count = list.size,
                        attention = status == TaskStatus.FAILED) {
                        list.forEachIndexed { i, p ->
                            if (i > 0) RowDivider()
                            PromiseRow(p, p.personId?.let(nameOf::get), p.matterId?.let(titleOf::get), session)
                            val origin = listOf(stringResource(R.string.promised_on, Dates.format(Dates.dayOf(p.createdAt))), p.source, p.statusNote)
                                .filter { it.isNotBlank() }.joinToString(" · ")
                            Text(origin, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(start = 48.dp, bottom = 6.dp))
                        }
                    }
                }
            }
        }
    }
}
