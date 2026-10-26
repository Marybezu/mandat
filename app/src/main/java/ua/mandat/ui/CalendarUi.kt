package ua.mandat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.alpha
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
import androidx.room.withTransaction
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
fun CalendarScreen(db: MandatDb, session: Session) = CalendarScreenForTest(db, session, 0)

/** The calendar, opened in a given view (0 month, 1 week with hours, 2 week as a list). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarScreenForTest(db: MandatDb, session: Session, view0: Int) {
    val today = Dates.today()
    var selected by rememberSaveable { mutableStateOf(today) }
    var view by rememberSaveable { mutableStateOf(view0) }
    val weekMode = view != 0
    // The month shown (its first day); follows the chosen day
    val sel = LocalDate.ofEpochDay(selected)
    val monthFirst = sel.withDayOfMonth(1).toEpochDay()
    val from = if (weekMode) weekStart(selected) else weekStart(monthFirst)
    val to = if (weekMode) from + 6 else from + 41

    val events by remember(db, from, to) { db.events().observeRange(from, to).hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val links by remember(db) { db.events().observeLinks() }.collectAsStateWithLifecycle(emptyList())
    val deadlines by remember(db) { db.deadlines().observeOpen().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val delegations by remember(db) { db.delegations().observeOpen().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val promises by remember(db) { db.promises().observeOpen().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val nameOf = people.associate { it.id to it.name }
    val titleOf = titles.associate { it.id to it.title }
    val peopleOf = links.groupBy({ it.eventId }, { it.personId })
    val context = LocalContext.current
    // In trip mode the phone's own events are not shown either: they may copy hidden ones
    val trip by session.tripOn.collectAsStateWithLifecycle()
    val phone by produceState(emptyList<PhoneEvent>(), from, to, trip) {
        value = if (trip) emptyList() else withContext(Dispatchers.IO) { PhoneCalendar.events(context, from, to) }
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

    if (view == 1) {
        WeekGrid(
            from = from, today = today, events = events, phone = phone,
            tasksOn = { d -> deadlines.count { it.day == d } + delegations.count { it.due == d } + promises.count { it.due == d } },
            hardOn = { d -> deadlines.any { it.day == d && DeadlineKind.of(it.kind) == DeadlineKind.HARD } },
            onPrev = { selected -= 7 }, onNext = { selected += 7 }, onToday = { selected = today },
            view = view, onView = { view = it },
            onDay = { d -> selected = d; view = 2 },
            onNew = { d, min -> session.eventDraft = newEvent(day = d).also { it.startMin = min; it.endMin = (min + 60).coerceAtMost(24 * 60) } },
            session = session,
        )
        return
    }
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
                    ViewChips(view) { view = it }
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
            SectionCard(Sym.layers, stringResource(if (weekMode) R.string.cal_week_kinds else R.string.cal_month_kinds), count = shown.size, foldKey = "cal:kinds") {
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
    val matters by remember(db) { db.matters().observeActiveTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val allMatters by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
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
            session.write { db -> db.withTransaction { db.communities().unlinkRef(ua.mandat.data.LinkKind.EVENT, id); db.events().delete(id) } }
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
    val titles by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
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

@Composable
private fun ViewChips(view: Int, onView: (Int) -> Unit) {
    listOf(R.string.cal_month, R.string.cal_week, R.string.cal_list).forEachIndexed { i, label ->
        FilterChip(selected = view == i, onClick = { onView(i) }, label = { Text(stringResource(label)) })
    }
}

private val HOUR = 52.dp

/**
 * A week with hours, as in usual calendar apps: seven columns, events as coloured blocks by kind,
 * whole-day events and the number of dated tasks on top, a line for "now". Tapping an empty hour
 * starts a new event at that time; tapping a day's name opens its list.
 */
@Composable
private fun WeekGrid(
    from: Long, today: Long, events: List<Event>, phone: List<PhoneEvent>,
    tasksOn: (Long) -> Int, hardOn: (Long) -> Boolean,
    onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit,
    view: Int, onView: (Int) -> Unit, onDay: (Long) -> Unit, onNew: (Long, Int) -> Unit, session: Session,
) {
    val scheme = MaterialTheme.colorScheme
    val days = (0 until 7).map { from + it }
    val scroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Open at eight in the morning
    androidx.compose.runtime.LaunchedEffect(Unit) { scroll.scrollTo(with(density) { (HOUR * 8).roundToPx() }) }
    val nowMin = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev) { Text("‹", style = MaterialTheme.typography.titleLarge) }
            Text("${Dates.short(from)} – ${Dates.short(from + 6)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = onNext) { Text("›", style = MaterialTheme.typography.titleLarge) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ViewChips(view, onView)
            Spacer(Modifier.weight(1f))
            if (today !in from..(from + 6)) TextButton(onClick = onToday) { Text(stringResource(R.string.cal_today)) }
        }
        // Day names, whole-day events and tasks
        Row {
            Spacer(Modifier.width(40.dp))
            days.forEach { d ->
                val date = LocalDate.ofEpochDay(d)
                val isToday = d == today
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onDay(d) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(date.dayOfWeek.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = Muted)
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(if (isToday) scheme.primary else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleSmall, color = if (isToday) scheme.onPrimary else scheme.onSurface)
                    }
                    val n = tasksOn(d)
                    if (n > 0) Text("• $n", style = MaterialTheme.typography.labelSmall, color = if (hardOn(d)) ChartColors.coral else ChartColors.amber)
                    events.filter { it.covers(d) && (it.startMin == null || it.lastDay > it.day) }.take(2).forEach { e ->
                        Text(e.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = Color.White,
                            modifier = Modifier.padding(top = 2.dp).fillMaxWidth().padding(horizontal = 1.dp).clip(RoundedCornerShape(4.dp))
                                .background(EventKind.of(e.kind).color()).clickable { session.openEvent(e.id) }.padding(horizontal = 3.dp, vertical = 1.dp))
                    }
                }
            }
        }
        // Hours
        Row(Modifier.weight(1f).verticalScroll(scroll)) {
            Column(Modifier.width(40.dp)) {
                (0 until 24).forEach { h ->
                    Box(Modifier.height(HOUR)) { Text("%02d:00".format(h), style = MaterialTheme.typography.labelSmall, color = Muted) }
                }
            }
            days.forEach { d ->
                Box(
                    Modifier.weight(1f).height(HOUR * 24)
                        .background(if (d == today) scheme.primaryContainer.copy(alpha = 0.18f) else Color.Transparent)
                        .pointerInput(d) {
                            detectTapGestures { pos ->
                                val minute = ((pos.y / HOUR.toPx()) * 60).toInt()
                                onNew(d, (minute / 30) * 30)
                            }
                        },
                ) {
                    // Hour lines
                    (0 until 24).forEach { h ->
                        Box(Modifier.offset(y = HOUR * h).fillMaxWidth().height(1.dp).background(scheme.outlineVariant.copy(alpha = 0.6f)))
                    }
                    Box(Modifier.fillMaxHeight().width(1.dp).background(scheme.outlineVariant.copy(alpha = 0.6f)))
                    // Timed events of the day, side by side when they overlap
                    val timed = events.filter { it.startMin != null && it.day == d && (it.endDay == null || it.endDay <= it.day) }.sortedBy { it.startMin }
                    val phoneTimed = phone.filter { it.day == d && it.startMin != null }
                    val blocks = timed.map { Triple(it.startMin!!, it.endMin ?: (it.startMin + 60), it as Any) } +
                        phoneTimed.map { Triple(it.startMin!!, it.endMin ?: (it.startMin + 60), it as Any) }
                    val lanes = layoutLanes(blocks.map { it.first to it.second })
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                        val w = maxWidth
                        blocks.forEachIndexed { i, (s, e, item) ->
                            val (lane, count) = lanes[i]
                            val top = HOUR * (s / 60f)
                            val height = (HOUR * ((e - s).coerceAtLeast(30) / 60f))
                            val color = if (item is Event) EventKind.of(item.kind).color() else ChartColors.gray
                            val title = if (item is Event) item.title else (item as PhoneEvent).title
                            Box(
                                Modifier.offset(x = w / count * lane, y = top).width(w / count).height(height).padding(1.dp)
                                    .clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = if (item is Event) 0.9f else 0.5f))
                                    .let { m -> if (item is Event) m.clickable { session.openEvent(item.id) } else m }
                                    .padding(2.dp),
                            ) {
                                Text(title.ifBlank { "—" }, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (d == today) {
                        Box(Modifier.offset(y = HOUR * (nowMin / 60f)).fillMaxWidth().height(2.dp).background(ChartColors.coral))
                    }
                }
            }
        }
    }
}

/** Places overlapping blocks side by side: for each, its lane and how many lanes its group has. */
private fun layoutLanes(blocks: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
    val order = blocks.indices.sortedBy { blocks[it].first }
    val lane = IntArray(blocks.size)
    val group = IntArray(blocks.size)
    var groupStart = 0
    var groupEnd = -1
    var groupId = 0
    val laneEnds = mutableListOf<Int>()
    val groupLanes = mutableMapOf<Int, Int>()
    for (idx in order) {
        val (s, e) = blocks[idx]
        if (s >= groupEnd) { groupId++; laneEnds.clear(); groupStart = s }
        val free = laneEnds.indexOfFirst { it <= s }
        val l = if (free >= 0) free else laneEnds.size.also { laneEnds.add(0) }
        laneEnds[l] = maxOf(e, s + 30)
        lane[idx] = l
        group[idx] = groupId
        groupEnd = maxOf(groupEnd, maxOf(e, s + 30))
        groupLanes[groupId] = maxOf(groupLanes[groupId] ?: 0, laneEnds.size)
    }
    return blocks.indices.map { lane[it] to (groupLanes[group[it]] ?: 1) }
}

/**
 * Today's events as a strip of the day: in order of time, with a "now" line between what has
 * passed and what is coming. Shown on Today only when there is something in the calendar.
 */
@Composable
fun DayStrip(db: MandatDb, session: Session) {
    val today = Dates.today()
    val events by remember(db, today) { db.events().observeRange(today, today).hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    if (events.isEmpty()) return
    val nowMin = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
    val sorted = events.sortedWith(compareBy({ it.startMin != null }, { it.startMin }))
    SectionCard(Sym.calendar, stringResource(R.string.day_strip), count = events.size) {
        var nowShown = false
        sorted.forEachIndexed { i, e ->
            val past = e.startMin != null && (e.endMin ?: (e.startMin + 60)) <= nowMin
            if (!nowShown && e.startMin != null && !past) {
                nowShown = true
                NowLine(nowMin)
            }
            if (i > 0) RowDivider()
            Box(Modifier.let { if (past) it.alpha(0.5f) else it }) { EventRow(e, emptyList(), e.matterId?.let { null }, today, session) }
        }
        if (!nowShown && sorted.any { it.startMin != null }) NowLine(nowMin)
        TextButton(onClick = { session.tabRequest = "Time" }) { Text(stringResource(R.string.day_strip_open)) }
    }
}

@Composable
private fun NowLine(nowMin: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(stringResource(R.string.now_at, timeText(nowMin)), style = MaterialTheme.typography.labelSmall, color = ChartColors.coral)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f).height(2.dp).background(ChartColors.coral))
    }
}
