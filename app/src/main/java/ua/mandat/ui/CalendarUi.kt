package ua.mandat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.mandat.R
import ua.mandat.data.DeadlineKind
import ua.mandat.data.Event
import ua.mandat.data.EventKind
import ua.mandat.data.MandatDb
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** The Time tab: the calendar, or the list of dates (deadlines, delegations, promises). */
@Composable
fun TimeTab(db: MandatDb, session: Session) {
    var mode by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = Space.screen, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text(stringResource(R.string.calendar)) })
            FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text(stringResource(R.string.time_dates)) })
        }
        Box(Modifier.weight(1f)) {
            if (mode == 0) CalendarScreen(db, session) else TimeScreen(db, session)
        }
    }
}

fun EventKind.label(): Int = when (this) {
    EventKind.MEETING -> R.string.event_meeting
    EventKind.SESSION -> R.string.event_session
    EventKind.COMMITTEE -> R.string.event_committee
    EventKind.TRIP -> R.string.event_trip
    EventKind.RECEPTION -> R.string.event_reception
    EventKind.CALL -> R.string.event_call
    EventKind.MEDIA -> R.string.event_media
    EventKind.OTHER -> R.string.event_other
}

fun EventKind.color(): Color = when (this) {
    EventKind.MEETING -> ChartColors.blue
    EventKind.SESSION -> ChartColors.purple
    EventKind.COMMITTEE -> ChartColors.purpleLight
    EventKind.TRIP -> ChartColors.green
    EventKind.RECEPTION -> ChartColors.teal
    EventKind.CALL -> ChartColors.sky
    EventKind.MEDIA -> ChartColors.amber
    EventKind.OTHER -> ChartColors.grayLight
}

fun timeText(min: Int?): String = min?.let { "%d:%02d".format(it / 60, it % 60) }.orEmpty()

private fun timeRange(start: Int?, end: Int?): String? =
    if (start == null) null else if (end == null) timeText(start) else "${timeText(start)}–${timeText(end)}"

/** The first day of the week for the phone's language (Monday in Ukraine). */
private fun firstDayOfWeek(): DayOfWeek = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek

private fun weekStart(day: Long): Long {
    val d = LocalDate.ofEpochDay(day)
    val shift = (d.dayOfWeek.value - firstDayOfWeek().value + 7) % 7
    return day - shift
}

/**
 * The calendar: a month (or a week) at the top, with coloured dots for what is on each day, and
 * the chosen day below. Mandat's own events, dates of deadlines and tasks, and (if turned on)
 * the phone's events, which are only shown and never saved.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(db: MandatDb, session: Session) {
    val today = Dates.today()
    var selected by rememberSaveable { mutableStateOf(today) }
    var weekMode by rememberSaveable { mutableStateOf(false) }
    // The month shown (its first day); follows the chosen day
    val sel = LocalDate.ofEpochDay(selected)
    val monthFirst = sel.withDayOfMonth(1).toEpochDay()
    val from = if (weekMode) weekStart(selected) else weekStart(monthFirst)
    val to = if (weekMode) from + 6 else from + 41

    val events by remember(db, from, to) { db.events().observeRange(from, to) }.collectAsStateWithLifecycle(emptyList())
    val links by remember(db) { db.events().observeLinks() }.collectAsStateWithLifecycle(emptyList())
    val deadlines by remember(db) { db.deadlines().observeOpen() }.collectAsStateWithLifecycle(emptyList())
    val delegations by remember(db) { db.delegations().observeOpen() }.collectAsStateWithLifecycle(emptyList())
    val promises by remember(db) { db.promises().observeOpen() }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles() }.collectAsStateWithLifecycle(emptyList())
    val nameOf = people.associate { it.id to it.name }
    val titleOf = titles.associate { it.id to it.title }
    val peopleOf = links.groupBy({ it.eventId }, { it.personId })
    val context = LocalContext.current
    val phone by produceState(emptyList<PhoneEvent>(), from, to) {
        value = withContext(Dispatchers.IO) { PhoneCalendar.events(context, from, to) }
    }

    fun eventsOn(d: Long) = events.filter { it.covers(d) }.sortedWith(compareBy({ it.startMin != null }, { it.startMin }))
    fun phoneOn(d: Long) = phone.filter { it.day == d }
    fun tasksOn(d: Long): List<@Composable () -> Unit> = buildList {
        deadlines.filter { it.day == d }.forEach { x -> add { DeadlineRow(x, x.matterId?.let(titleOf::get), session) } }
        delegations.filter { it.due == d }.forEach { x -> add { DelegationRow(x, x.personId?.let(nameOf::get), x.matterId?.let(titleOf::get), session) } }
        promises.filter { it.due == d }.forEach { x -> add { PromiseRow(x, x.personId?.let(nameOf::get), x.matterId?.let(titleOf::get), session) } }
    }
    /** Up to four dots for a day: events by kind, a hard deadline, other dates, the phone. */
    fun dotsOn(d: Long): List<Color> = buildList {
        eventsOn(d).map { EventKind.of(it.kind).color() }.distinct().take(2).forEach { add(it) }
        if (deadlines.any { it.day == d && DeadlineKind.of(it.kind) == DeadlineKind.HARD }) add(ChartColors.coral)
        else if (deadlines.any { it.day == d } || delegations.any { it.due == d } || promises.any { it.due == d }) add(ChartColors.amber)
        if (phoneOn(d).isNotEmpty()) add(ChartColors.gray)
    }.take(4)

    LazyColumn(
        contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Space.gap),
    ) {
        item(key = "grid") {
            MCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected = if (weekMode) selected - 7 else sel.minusMonths(1).toEpochDay() }) { Text("‹", style = MaterialTheme.typography.titleLarge) }
                    val title = if (weekMode) "${Dates.short(from)} – ${Dates.short(to)}"
                    else sel.format(DateTimeFormatter.ofPattern("LLLL yyyy")).replaceFirstChar { it.uppercase() }
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    IconButton(onClick = { selected = if (weekMode) selected + 7 else sel.plusMonths(1).toEpochDay() }) { Text("›", style = MaterialTheme.typography.titleLarge) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !weekMode, onClick = { weekMode = false }, label = { Text(stringResource(R.string.cal_month)) })
                    FilterChip(selected = weekMode, onClick = { weekMode = true }, label = { Text(stringResource(R.string.cal_week)) })
                    Spacer(Modifier.weight(1f))
                    if (selected != today) TextButton(onClick = { selected = today }) { Text(stringResource(R.string.cal_today)) }
                }
                Spacer(Modifier.height(4.dp))
                // Weekday names
                Row {
                    (0 until 7).forEach { i ->
                        val dow = firstDayOfWeek().plus(i.toLong())
                        Text(dow.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault()), style = MaterialTheme.typography.labelSmall,
                            color = Muted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    }
                }
                val weeks = if (weekMode) 1 else 6
                (0 until weeks).forEach { w ->
                    Row {
                        (0 until 7).forEach { i ->
                            val d = from + w * 7 + i
                            val inMonth = weekMode || LocalDate.ofEpochDay(d).month == sel.month
                            DayCell(d, d == selected, d == today, inMonth, dotsOn(d), Modifier.weight(1f)) { selected = d }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Legend()
            }
        }
        val days = if (weekMode) (from..to).toList() else listOf(selected)
        days.forEach { d ->
            val own = eventsOn(d)
            val fromPhone = phoneOn(d)
            val tasks = tasksOn(d)
            // In the week view, empty days take one quiet line
            item(key = "day$d") {
                val head = LocalDate.ofEpochDay(d).format(DateTimeFormatter.ofPattern("EEEE, d MMMM")).replaceFirstChar { it.uppercase() }
                SectionCard(Sym.calendar, head, count = (own.size + fromPhone.size + tasks.size).takeIf { it > 0 }, attention = false) {
                    if (own.isEmpty() && fromPhone.isEmpty() && tasks.isEmpty()) {
                        Text(stringResource(R.string.cal_free), style = MaterialTheme.typography.bodyMedium, color = Muted)
                    }
                    own.forEachIndexed { i, e ->
                        if (i > 0) RowDivider()
                        EventRow(e, peopleOf[e.id].orEmpty().mapNotNull(nameOf::get), e.matterId?.let(titleOf::get), d, session)
                    }
                    fromPhone.forEach { p -> RowDivider(); PhoneEventRow(p) }
                    tasks.forEach { row -> RowDivider(); row() }
                    TextButton(onClick = { session.eventDraft = newEvent(day = d) }) { Text("+ " + stringResource(R.string.event_new)) }
                }
            }
        }
        // What the shown period holds, by kind: the calendar's own small infographic
        val shown = events.filter { it.day >= from && it.day <= to }
        if (shown.isNotEmpty()) item(key = "kinds") {
            SectionCard(Sym.layers, stringResource(if (weekMode) R.string.cal_week_kinds else R.string.cal_month_kinds), count = shown.size) {
                Spacer(Modifier.height(4.dp))
                StackBar(
                    EventKind.entries.map { k -> Slice(k.name, stringResource(k.label()), shown.count { it.kind == k.name }, k.color()) }
                        .filter { it.value > 0 },
                )
                val withPeople = shown.count { peopleOf[it.id].orEmpty().isNotEmpty() }
                Text(stringResource(R.string.cal_with_people, withPeople, shown.size), style = MaterialTheme.typography.labelMedium,
                    color = Muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun DayCell(day: Long, selected: Boolean, today: Boolean, inMonth: Boolean, dots: List<Color>, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.aspectRatio(0.9f).padding(2.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) scheme.primaryContainer else Color.Transparent)
            .let { if (today && !selected) it.border(1.dp, scheme.primary, RoundedCornerShape(8.dp)) else it }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            LocalDate.ofEpochDay(day).dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                selected -> scheme.onPrimaryContainer
                !inMonth -> scheme.outline
                else -> scheme.onSurface
            },
        )
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(6.dp)) {
            dots.forEach { c -> Box(Modifier.size(5.dp).clip(CircleShape).background(c)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(
            ChartColors.blue to R.string.cal_legend_events,
            ChartColors.coral to R.string.cal_legend_hard,
            ChartColors.amber to R.string.cal_legend_dates,
            ChartColors.gray to R.string.cal_legend_phone,
        ).forEach { (c, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
    }
}

/** One event as a tile: time, title, kind, place, people and matter. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventRow(e: Event, names: List<String>, matterTitle: String?, onDay: Long?, session: Session) {
    val kind = EventKind.of(e.kind)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable { session.openEvent(e.id) }.padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(kind.color()))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val time = timeRange(e.startMin, e.endMin) ?: stringResource(R.string.cal_all_day)
            val days = if (e.endDay != null && e.endDay > e.day) " · ${Dates.short(e.day)}–${Dates.short(e.lastDay)}" else ""
            Text(time + days, style = MaterialTheme.typography.labelMedium, color = Muted)
            Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MiniChip(stringResource(kind.label()))
                if (onDay == null) MiniChip(Dates.short(e.day))
                if (e.place.isNotBlank()) MiniChip(e.place)
                names.take(3).forEach { MiniChip(it) }
                if (names.size > 3) MiniChip("+${names.size - 3}")
                if (matterTitle != null && e.matterId != null) MiniChip(matterTitle, onClick = { session.openMatter(e.matterId) })
            }
            if (e.outcome.isNotBlank()) {
                Text("✓ " + e.outcome, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** An event from the phone's calendar: grey and read-only, so it is clear it is not Mandat's own. */
@Composable
private fun PhoneEventRow(p: PhoneEvent) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).padding(10.dp),
    ) {
        Box(Modifier.width(4.dp).height(32.dp).clip(RoundedCornerShape(2.dp)).background(ChartColors.gray))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text((timeRange(p.startMin, p.endMin) ?: stringResource(R.string.cal_all_day)) + " · " + stringResource(R.string.cal_from_phone),
                style = MaterialTheme.typography.labelMedium, color = Muted)
            Text(p.title.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p.place.isNotBlank()) Text(p.place, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The event dialog. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventDialog(db: MandatDb, session: Session, draft: EventDraft) {
    val matters by remember(db) { db.matters().observeActiveTitles() }.collectAsStateWithLifecycle(emptyList())
    val allMatters by remember(db) { db.matters().observeAllTitles() }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll() }.collectAsStateWithLifecycle(emptyList())
    var confirmDelete by remember { mutableStateOf(false) }
    var pickingPerson by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf<Int?>(null) } // 0 start, 1 end
    var toPhone by remember { mutableStateOf(false) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { session.eventDraft = null },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(if (draft.isNew) R.string.event_new else R.string.event)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = draft.title, onValueChange = { draft.title = it },
                    label = { Text(stringResource(R.string.what)) }, minLines = 1, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EventKind.entries.forEach { k ->
                        FilterChip(selected = draft.kind == k, onClick = { draft.kind = k }, label = { Text(stringResource(k.label())) })
                    }
                }
                DueField(draft.day, allowClear = false, label = R.string.event_day) { if (it != null) draft.day = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.event_time), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { pickingTime = 0 }) { Text(draft.startMin?.let(::timeText) ?: stringResource(R.string.cal_all_day)) }
                    if (draft.startMin != null) {
                        Text("–")
                        TextButton(onClick = { pickingTime = 1 }) { Text(draft.endMin?.let(::timeText) ?: "…") }
                        TextButton(onClick = { draft.startMin = null; draft.endMin = null }) { Text("✕") }
                    }
                }
                if (draft.kind == EventKind.TRIP || draft.endDay != null) {
                    DueField(draft.endDay, label = R.string.event_end_day) { draft.endDay = it?.takeIf { d -> d > draft.day } }
                }
                OutlinedTextField(value = draft.place, onValueChange = { draft.place = it },
                    label = { Text(stringResource(R.string.event_place)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                // People taking part
                Text(stringResource(R.string.event_people), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    draft.people.mapNotNull { id -> people.firstOrNull { it.id == id } }.forEach { p ->
                        FilterBadge(p.name) { draft.people = draft.people - p.id }
                    }
                    TextButton(onClick = { pickingPerson = true }) { Text("+ " + stringResource(R.string.add_person)) }
                }
                MatterPicker(matters, allMatters, draft.matterId) { draft.matterId = it }
                OutlinedTextField(value = draft.notes, onValueChange = { draft.notes = it },
                    label = { Text(stringResource(R.string.event_notes)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                if (draft.day <= Dates.today()) {
                    OutlinedTextField(value = draft.outcome, onValueChange = { draft.outcome = it },
                        label = { Text(stringResource(R.string.event_outcome)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = draft.remind, onCheckedChange = { draft.remind = it })
                    Text(stringResource(R.string.event_remind), style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { toPhone = true }, enabled = draft.valid()) { Text(stringResource(R.string.event_to_phone)) }
                if (!draft.isNew) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete)) }
            }
        },
        confirmButton = { TextButton(onClick = { session.saveEventDraft() }, enabled = draft.valid()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { session.eventDraft = null }) { Text(stringResource(R.string.cancel)) } },
    )
    if (pickingPerson) {
        PersonOrContactDialog(db, session, exclude = draft.people, onPick = { id -> draft.people = draft.people + id; pickingPerson = false },
            onCancel = { pickingPerson = false })
    }
    pickingTime?.let { which ->
        val initial = if (which == 0) draft.startMin ?: (10 * 60) else draft.endMin ?: ((draft.startMin ?: 600) + 60).coerceAtMost(23 * 60 + 45)
        TimeDialog(initial, onPick = { m ->
            if (which == 0) {
                // Moving the start keeps the length
                val len = draft.endMin?.let { e -> draft.startMin?.let { e - it } }
                draft.startMin = m
                draft.endMin = len?.let { (m + it).coerceAtMost(24 * 60) }
            } else draft.endMin = m
            pickingTime = null
        }, onCancel = { pickingTime = null })
    }
    if (toPhone) {
        AlertDialog(
            onDismissRequest = { toPhone = false },
            title = { Text(stringResource(R.string.event_to_phone)) },
            text = { Text(stringResource(R.string.event_to_phone_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    toPhone = false
                    val e = ua.mandat.data.cleanEvent(draft.current(0))
                    if (e != null) {
                        // Saved first: leaving Mandat locks it, and nothing typed is lost
                        session.saveEventDraft()
                        PhoneCalendar.addToPhone(context, e.title, e.day, e.endDay, e.startMin, e.endMin, e.place)
                    }
                }) { Text(stringResource(R.string.event_to_phone_go)) }
            },
            dismissButton = { TextButton(onClick = { toPhone = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDelete) {
        ConfirmDelete(onConfirm = {
            val id = draft.event.id
            session.eventDraft = null
            session.write { it.events().delete(id) }
        }, onCancel = { confirmDelete = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initialMin: Int, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initialMin / 60, initialMinute = initialMin % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onCancel,
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Choose someone from People, or from the address book; someone from the address book moves to
 * People at that moment, because the owner now works with them.
 */
@Composable
fun PersonOrContactDialog(db: MandatDb, session: Session, exclude: Set<Long>, onPick: (Long) -> Unit, onCancel: () -> Unit) {
    val people by remember(db) { db.people().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val contacts by remember(db) { db.contacts().observeAll() }.collectAsStateWithLifecycle(emptyList())
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    fun hit(vararg s: String) = q.isEmpty() || s.any { it.lowercase().contains(q) }
    val shownPeople = people.filter { it.id !in exclude && hit(it.name, it.organization, it.jobTitle) }
    val shownContacts = if (q.length < 2) emptyList() else contacts.filter { hit(it.name, it.organization, it.phones, it.label) }.take(50)
    val bookTitle = stringResource(R.string.directory)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.pick_person)) },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth())
                if (contacts.isNotEmpty() && q.length < 2) Text(stringResource(R.string.pick_book_hint), style = MaterialTheme.typography.labelSmall, color = Muted,
                    modifier = Modifier.padding(top = 4.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shownPeople, key = { "p${it.id}" }) { p ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(p.id) }.padding(vertical = 10.dp)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            val meta = listOf(p.jobTitle, p.organization).filter { it.isNotBlank() }.joinToString(" · ")
                            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelMedium, color = Muted)
                        }
                    }
                    if (shownContacts.isNotEmpty()) item(key = "bookhead") {
                        Text(bookTitle, style = MaterialTheme.typography.labelLarge, color = Muted, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(shownContacts, key = { "c${it.id}" }) { c ->
                        Column(Modifier.fillMaxWidth().clickable {
                            session.write { db2 ->
                                val id = db2.contacts().moveToPeople(db2.people(), c, System.currentTimeMillis())
                                withContext(Dispatchers.Main) { onPick(id) }
                            }
                        }.padding(vertical = 10.dp)) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge)
                            val meta = listOf(c.organization, c.label, c.phones.lineSequence().firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
                            Text(meta.ifEmpty { bookTitle }, style = MaterialTheme.typography.labelMedium, color = Muted)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Events of a matter or a person inside its card: what is coming, then the last ones. */
@Composable
fun EventLinks(db: MandatDb, session: Session, flow: kotlinx.coroutines.flow.Flow<List<Event>>, matterId: Long? = null, personId: Long? = null) {
    val events by remember(flow) { flow }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles() }.collectAsStateWithLifecycle(emptyList())
    val today = Dates.today()
    var all by remember { mutableStateOf(false) }
    LinkSection(stringResource(R.string.events) + if (events.isEmpty()) "" else " · ${events.size}")
    val coming = events.filter { it.lastDay >= today }.sortedBy { it.day }
    val past = events.filter { it.lastDay < today }.sortedByDescending { it.day }
    val shown = coming + if (all) past else past.take(3)
    shown.forEachIndexed { i, e ->
        if (i > 0) RowDivider()
        EventRow(e, emptyList(), if (matterId == null) e.matterId?.let { id -> titles.firstOrNull { it.id == id }?.title } else null, null, session)
    }
    Row {
        if (past.size > 3) TextButton(onClick = { all = !all }) { Text(stringResource(R.string.show_more, past.size - 3)) }
        TextButton(onClick = { session.eventDraft = newEvent(matterId = matterId, personId = personId) }) { Text("+ " + stringResource(R.string.event_new)) }
    }
}
