@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ua.mandat.ui

import kotlinx.coroutines.flow.flowOn
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
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
        // The coloured picture already says hard or soft; only the basis is written out
        chips = listOfNotNull(d.basis.ifBlank { null }, matterTitle),
        due = d.day,
        status = d.status,
        onToggleDone = {
            val next = if (TaskStatus.of(d.status) == TaskStatus.OPEN) TaskStatus.DONE else TaskStatus.OPEN
            session.write {
                it.deadlines().update(d.copy(status = next.name, closedAt = if (next == TaskStatus.OPEN) null else System.currentTimeMillis()))
            }
        },
        onOpen = { session.deadlineDraft = DeadlineDraft(d, isNew = false) },
        links = buildMap { if (matterTitle != null && d.matterId != null) put(matterTitle) { session.openMatter(d.matterId) } },
    )
}

/** The deadline dialog. A hard deadline cannot be saved without its basis. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeadlineDialog(db: MandatDb, session: Session, draft: DeadlineDraft) {
    val mattersFlow = remember(db) { db.matters().observeActiveTitles().hiding(session) { it.id } }
    val allFlow = remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }
    val matters by mattersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val allMatters by allFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var confirmDelete by remember { mutableStateOf(false) }

    EditSheet(
        title = stringResource(R.string.deadline),
        onDismiss = { session.deadlineDraft = null },
        changed = draft.changed(),
        onSave = { session.saveDeadlineDraft() },
        saveEnabled = draft.valid(),
    ) {
        SheetGroup {

                OutlinedTextField(
                    value = draft.title, onValueChange = { draft.title = it },
                    label = { Text(stringResource(R.string.what)) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickChip(stringResource(R.string.deadline_soft), draft.kind == DeadlineKind.SOFT) { draft.kind = DeadlineKind.SOFT }
                    QuickChip(stringResource(R.string.deadline_hard), draft.kind == DeadlineKind.HARD) { draft.kind = DeadlineKind.HARD }
                }
                if (draft.kind == DeadlineKind.HARD) {
                    OutlinedTextField(
                        value = draft.basis, onValueChange = { draft.basis = it },
                        label = { Text(stringResource(R.string.deadline_basis)) },
                        supportingText = { Text(stringResource(R.string.deadline_basis_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
        }
        SheetGroup(stringResource(R.string.delegation_group_when)) {
                DueField(draft.day, allowClear = false) { if (it != null) draft.day = it }
                Picker(
                    label = stringResource(R.string.remind_before),
                    current = remindLabel(draft.remindBefore),
                    options = listOf(0, 1, 2, 3, 5, 7, 14, 30).map { it to remindLabel(it) },
                    onPick = { draft.remindBefore = it },
                )
                MatterPicker(matters, allMatters, draft.matterId) { draft.matterId = it }
        }
        if (!draft.isNew) SheetGroup {
                    Text(stringResource(R.string.status), style = MaterialTheme.typography.labelLarge)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TASK_STATUSES.forEach { s ->
                            QuickChip(stringResource(s.delegationLabel()), draft.status == s) { draft.status = s }
                        }
                    }
        }
        if (!draft.isNew) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
    }
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
    val deadlines by remember(db) { db.deadlines().observeOpen().hidingNamed(session, { it.matterId }) { it.title + " " + it.basis } }.collectAsStateWithLifecycle(emptyList())
    val delegations by remember(db) { db.delegations().observeOpen().hidingNamed(session, { it.matterId }) { it.what + " " + it.purpose + " " + it.doneMeans } }.collectAsStateWithLifecycle(emptyList())
    val promises by remember(db) { db.promises().observeOpen().hidingNamed(session, { it.matterId }) { it.what + " " + it.toWhom + " " + it.source + " " + it.statusNote } }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val allDeadlines by remember(db) { db.deadlines().observeAll().hidingNamed(session, { it.matterId }) { it.title + " " + it.basis } }.collectAsStateWithLifecycle(emptyList())
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
                        RingStat("$onTimeMonth/${dueMonth.size}", "", onTimeMonth.toFloat() / dueMonth.size, ChartColors.blue) {
                            session.periodView = PeriodKind.ON_TIME to month
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.on_time_month_title), style = MaterialTheme.typography.titleMedium)
                            val late = dueMonth.size - onTimeMonth
                            if (late > 0) Text(stringResource(R.string.not_on_time, late) + " →", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { session.periodView = PeriodKind.DEADLINES_DUE to month })
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
                    Paged(list) { e -> e.content() }
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
    // Tonight's entry tied to something hidden is not shown; what is typed now is added under it
    val hidden by session.hidden.collectAsStateWithLifecycle()
    val todayHidden = todays != null && todays.id in hidden.reviews
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
                        value = session.reviewDraft ?: (if (todayHidden) "" else todays?.progress.orEmpty()),
                        // Before tonight's entry is known (or while hidden) what is typed goes under it, never over it
                        onValueChange = { if (session.reviewDraft == null) session.reviewAppend = todayHidden || todays == null; session.reviewDraft = it },
                        label = { Text(stringResource(R.string.evening_progress)) },
                        minLines = 3, modifier = Modifier.fillMaxWidth(),
                    )
                    if (session.reviewDraft != null) {
                        Button(onClick = { session.saveReviewDraft() }) { Text(stringResource(R.string.save)) }
                    }
                    TodayFeel(db, session)
                    OutlinedTextField(
                        value = unload, onValueChange = { unload = it },
                        label = { Text(stringResource(R.string.evening_unload)) },
                        minLines = 2, modifier = Modifier.fillMaxWidth(),
                    )
                    if (unload.isNotBlank()) {
                        val aiReady by session.ai.models.ready.collectAsStateWithLifecycle()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                val clean = cleanInboxText(unload)
                                unload = ""
                                if (clean != null) session.write { it.inbox().insert(InboxItem(text = clean, createdAt = System.currentTimeMillis())) }
                            }) { Text(stringResource(R.string.evening_to_inbox)) }
                            // Or let the AI split what was emptied from the head into matters, tasks and deadlines
                            if (aiReady) AiChip(stringResource(R.string.ai_split_go)) {
                                session.aiSplitText = unload.take(ua.mandat.ai.LocalAi.MAX_INPUT)
                                unload = ""
                                session.showAiSplit = true
                            }
                        }
                    }
                }
            }
        }
        item(key = "promises") { ReputationTiles(db, session) }
        item(key = "budget") { BudgetCard(db, session) }
        item(key = "diary") {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                OutlinedButton(onClick = { session.showDiary = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.diary_open)) }
                OutlinedButton(onClick = { session.showTexts = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.texts_title)) }
            }
        }
    }
}

/** Search across everything; the query lives only in memory. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(db: MandatDb, session: Session) {
    BackHandler { session.searching = false }
    var query by remember { mutableStateOf(session.searchSeed.orEmpty()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { session.searchSeed = null }
    val trimmed = query.trim()
    // Hidden matters and what is tied to them (people, diary days, communities, notes naming them) stay out
    val flow = remember(db) {
        kotlinx.coroutines.flow.combine(db.search().everything().hiding(session) { it.matterId }, session.hidden) { rows, h ->
            // Lower-cased once here, not on every keystroke
            visibleRows(rows, h).map { it.copy(haystack = it.haystack.lowercase()) }
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)
    }
    val rows by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    // Searched away from the screen thread, a moment after typing pauses
    val hits by androidx.compose.runtime.produceState(emptyList<ua.mandat.data.SearchHit>(), rows, trimmed) {
        kotlinx.coroutines.delay(120)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ua.mandat.data.search(rows, trimmed, lowered = true) }
    }

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
                actions = {
                    TextButton(onClick = { session.searching = false; session.showTopics = true }) { Text(stringResource(R.string.topics)) }
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

fun kindLabel(kind: String): Int = when (kind) {
    "matter" -> R.string.matter_edit
    "person" -> R.string.person_edit
    "inbox" -> R.string.inbox
    "delegation" -> R.string.delegation_new
    "promise" -> R.string.promise_new
    "deadline" -> R.string.deadline
    "event" -> R.string.event
    "contact" -> R.string.directory
    "info" -> R.string.info_title
    "message" -> R.string.message_box
    "alliance" -> R.string.alliances_title
    "money" -> R.string.budget_title
    "community" -> R.string.district_title
    else -> R.string.tab_review
}

fun openHit(db: MandatDb, session: Session, kind: String, id: Long) {
    when (kind) {
        "matter" -> { session.searching = false; session.openMatter(id) }
        "person" -> { session.searching = false; session.openPerson(id) }
        "delegation" -> session.openTask(kind, id)
        "promise" -> session.openTask(kind, id)
        "deadline" -> session.openTask(kind, id)
        "event" -> session.openEvent(id)
        "contact" -> { session.searching = false; session.showDirectory = true }
        "info", "alliance" -> session.openInfoItem(kind, id)
        "message" -> { session.searching = false; session.showInfo = true }
        "money" -> { session.searching = false; session.showBudget = true }
        "community" -> { session.searching = false; session.showDistrict = true; session.communityOpen = id }
        else -> session.searching = false
    }
}


/**
 * Three doors from Review: promises (how many risky), media and information (attacks in work),
 * alliances (active). The numbers say where to look first.
 */
@Composable
fun ReputationTiles(db: MandatDb, session: Session) {
    val promises by remember(db) { db.promises().observeOpen().hidingNamed(session, { it.matterId }) { it.what + " " + it.toWhom + " " + it.source + " " + it.statusNote } }.collectAsStateWithLifecycle(emptyList())
    val items by remember(db) { db.info().observeAll().hidingNamed(session, { it.matterId }) { it.title + " " + it.notes + " " + it.source + " " + it.result } }.collectAsStateWithLifecycle(emptyList())
    val alliances by remember(db) { db.info().observeAlliances().hidingNamed(session, { it.matterId }) { it.withWhom + " " + it.about + " " + it.notes + " " + it.gives + " " + it.gets } }.collectAsStateWithLifecycle(emptyList())
    val today = Dates.today()
    val risky = promises.count { isRisky(it, today) }
    val attacks = items.count { !it.closed && it.kind == ua.mandat.data.InfoKind.ATTACK.name }
    val active = alliances.count { it.status == ua.mandat.data.AllianceStatus.ACTIVE.name }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
        StatTile(risky, stringResource(R.string.tile_risky_promises), attention = true) { session.showPromises = true }
        StatTile(attacks, stringResource(R.string.tile_attacks), attention = true) { session.showInfo = true }
        StatTile(active, stringResource(R.string.tile_alliances)) { session.showAlliances = true }
    }
}
