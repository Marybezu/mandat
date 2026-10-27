package ua.mandat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.mandat.R
import ua.mandat.data.MandatDb
import ua.mandat.data.MatterStatus
import ua.mandat.data.Retro
import ua.mandat.data.SETTING_CADENCE_START
import ua.mandat.data.Span
import ua.mandat.data.TaskStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** How far back the retrospective looks. */
private enum class RetroSpan(val label: Int) { QUARTER(R.string.retro_quarter), HALF(R.string.retro_half), YEAR(R.string.retro_year), CADENCE(R.string.retro_cadence) }

/**
 * Looking back: directions (time by role, month by month), past priorities (what was in focus
 * and how it ended), the flow of matters, deadlines on time, promises and people. Every part
 * opens the records behind it. Months before the journal began are marked as approximate.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RetroScreen(db: MandatDb, session: Session) {
    BackHandler { session.showRetro = false }
    val matters by remember(db) { db.matters().observeAll().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val history by remember(db) { db.history().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val deadlines by remember(db) { db.deadlines().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val events by remember(db) { db.events().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val eventLinks by remember(db) { db.events().observeLinks() }.collectAsStateWithLifecycle(emptyList())
    val entries by remember(db) { db.time().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val promises by remember(db) { db.promises().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val delegations by remember(db) { db.delegations().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val roles by remember(db) { db.roles().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val links by remember(db) { db.people().observeLinks() }.collectAsStateWithLifecycle(emptyList())
    val cadence by remember(db) { db.settings().observe(SETTING_CADENCE_START) }.collectAsStateWithLifecycle(null)
    var range by rememberSaveable { mutableStateOf(RetroSpan.HALF) }
    val today = Dates.today()
    val start = when (range) {
        RetroSpan.QUARTER -> LocalDate.ofEpochDay(today).withDayOfMonth(1).minusMonths(2).toEpochDay()
        RetroSpan.HALF -> LocalDate.ofEpochDay(today).withDayOfMonth(1).minusMonths(5).toEpochDay()
        RetroSpan.YEAR -> LocalDate.ofEpochDay(today).withDayOfMonth(1).minusMonths(11).toEpochDay()
        RetroSpan.CADENCE -> cadence?.value?.toLongOrNull() ?: LocalDate.ofEpochDay(today).withDayOfMonth(1).minusMonths(11).toEpochDay()
    }
    val span = Span(start, today)
    val titleOf = matters.associate { it.id to it.title }
    val nameOf = people.associate { it.id to it.name }
    val roleName = roles.associate { it.id to it.name }

    // Counted away from the screen thread: years of records never slow the screen down
    val data by produceState<RetroData?>(null, span, matters, history, deadlines, events, eventLinks, entries, delegations) {
        value = withContext(Dispatchers.Default) {
            val byMatter = history.groupBy { it.matterId }
            RetroData(
                focus = Retro.focusByMonth(span, matters, byMatter) { m -> Retro.mostActive(m, events, delegations, deadlines, entries) },
                flow = Retro.flowByMonth(span, today, matters, byMatter, deadlines),
                durations = Retro.durations(span, matters, byMatter),
                time = Retro.timeByMonth(span, events, entries, matters.associate { it.id to it.roleId }),
                topPeople = Retro.topPeople(span, events, eventLinks),
                finishedIds = matters.filter { m -> Retro.finishedOn(m, byMatter[m.id].orEmpty())?.let { it in span } == true }.map { it.id },
            )
        }
    }
    val monthName = { s: Span -> LocalDate.ofEpochDay(s.from).format(DateTimeFormatter.ofPattern("LLL")).replace(".", "") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.retro_title)) },
                navigationIcon = { IconButton(onClick = { session.showRetro = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        val d = data
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.gap),
        ) {
            item(key = "range") {
                Column {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RetroSpan.entries.forEach { r -> QuickChip(stringResource(r.label), range == r) { range = r } }
                    }
                    Text("${Dates.format(span.from)} – ${Dates.format(span.to)}", style = MaterialTheme.typography.labelLarge, color = Muted, modifier = Modifier.padding(top = 6.dp))
                    if (d != null && d.focus.any { it.approximate }) Text(stringResource(R.string.retro_approx), style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
            if (d == null) return@LazyColumn

            // The period at a glance
            item(key = "sum") {
                val avg = if (d.durations.isEmpty()) null else d.durations.values.average().toInt()
                val due = d.flow.sumOf { it.dueDeadlines }
                val onTime = d.flow.sumOf { it.onTime }
                MCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                        StatTile(d.flow.sumOf { it.started }, stringResource(R.string.retro_started)) { session.periodView = PeriodKind.STARTED to span }
                        StatTile(d.finishedIds.size, stringResource(R.string.retro_finished)) { session.periodView = PeriodKind.FINISHED to span }
                    }
                    Spacer(Modifier.height(Space.gap))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                        StatTile(avg ?: 0, stringResource(R.string.retro_avg_days)) { session.periodView = PeriodKind.FINISHED to span }
                        StatTile(if (due == 0) 0 else onTime * 100 / due, stringResource(R.string.retro_on_time_pct)) { session.periodView = PeriodKind.ON_TIME to span }
                    }
                }
            }

            // Directions: where the time went, month by month
            val roleIds = d.time.flatMap { it.second.keys }.toSet()
            if (roleIds.isNotEmpty()) item(key = "directions") {
                val ranked = roleIds.sortedByDescending { id -> d.time.sumOf { it.second[id] ?: 0 } }
                val colors = listOf(ChartColors.blue, ChartColors.teal, ChartColors.amber, ChartColors.purple, ChartColors.coral)
                val colorOf = ranked.mapIndexed { i, id -> id to if (id == 0L) ChartColors.grayLight else if (i < colors.size) colors[i] else ChartColors.grayLight }.toMap()
                SectionCard(Sym.layers, stringResource(R.string.retro_directions), foldKey = "retro:directions") {
                    Text(stringResource(R.string.retro_directions_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                    Spacer(Modifier.height(6.dp))
                    MonthStacks(d.time.map { (m, byRole) -> monthName(m) to ranked.map { id -> (byRole[id] ?: 0) to colorOf.getValue(id) } })
                    Spacer(Modifier.height(6.dp))
                    ranked.forEach { id ->
                        val total = d.time.sumOf { it.second[id] ?: 0 }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { session.roleTime = id to span }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(colorOf.getValue(id)))
                            Spacer(Modifier.width(8.dp))
                            Text(roleName[id]?.let(::shortRole) ?: stringResource(R.string.time_no_role), style = MaterialTheme.typography.labelMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(hoursText(total) + " →", style = MaterialTheme.typography.labelMedium, color = Muted)
                        }
                    }
                }
            }

            // Past priorities: what was in focus each month, and how it ended
            if (d.focus.any { it.matterIds.isNotEmpty() }) item(key = "focus") {
                SectionCard(Sym.target, stringResource(R.string.retro_focus), foldKey = "retro:focus") {
                    Text(stringResource(R.string.retro_focus_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                    d.focus.reversed().filter { it.matterIds.isNotEmpty() }.forEach { fm ->
                        Text(LocalDate.ofEpochDay(fm.month.from).format(DateTimeFormatter.ofPattern("LLLL yyyy")).replaceFirstChar { it.uppercase() } +
                            if (fm.approximate) " ≈" else "", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            fm.matterIds.forEach { id ->
                                val m = matters.firstOrNull { it.id == id } ?: return@forEach
                                val st = MatterStatus.of(m.status)
                                // Outcome in words as well as colour: ✓ finished, ▶ still in work, ‖ paused or dropped
                                val (mark, bg) = when (st) {
                                    MatterStatus.DONE -> "✓ " to ChartColors.teal.copy(alpha = 0.25f)
                                    MatterStatus.ACTIVE -> "▶ " to ChartColors.blue.copy(alpha = 0.18f)
                                    else -> "‖ " to MaterialTheme.colorScheme.surfaceVariant
                                }
                                MiniChip(mark + m.title, bg = bg, onClick = { session.openMatter(id) })
                            }
                        }
                    }
                    Text(stringResource(R.string.retro_focus_legend), style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(top = 8.dp))
                }
            }

            // How matters moved: new and finished each month, deadlines on time
            item(key = "flow") {
                SectionCard(Sym.schedule, stringResource(R.string.retro_flow), foldKey = "retro:flow") {
                    PairColumns(d.flow.map { monthName(it.month) to (it.started to it.finished) }, ChartColors.blueLight, ChartColors.teal) { i ->
                        session.periodView = PeriodKind.FINISHED to d.flow[i].month
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                        LegendDot(ChartColors.blueLight, stringResource(R.string.retro_started))
                        LegendDot(ChartColors.teal, stringResource(R.string.retro_finished))
                    }
                    val withDue = d.flow.filter { it.dueDeadlines > 0 }
                    if (withDue.isNotEmpty()) {
                        Text(stringResource(R.string.retro_on_time_months), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            withDue.forEach { f -> MiniChip("${monthName(f.month)} ${f.onTime * 100 / f.dueDeadlines} %", onClick = { session.periodView = PeriodKind.ON_TIME to f.month }) }
                        }
                    }
                    val longest = d.durations.entries.sortedByDescending { it.value }.take(3)
                    if (longest.isNotEmpty()) {
                        Text(stringResource(R.string.retro_longest), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                        longest.forEach { (id, days) -> TextTile(titleOf[id] ?: "—", stringResource(R.string.retro_days, days.toInt())) { session.openMatter(id) } }
                    }
                }
            }

            // Promises: made and kept within the period, and those still open from before it
            item(key = "promises") {
                fun inSpan(ms: Long) = Dates.dayOf(ms) in span
                val made = promises.count { inSpan(it.createdAt) }
                val kept = promises.count { TaskStatus.of(it.status) == TaskStatus.DONE && it.closedAt != null && inSpan(it.closedAt) }
                val oldOpen = promises.count { TaskStatus.of(it.status) == TaskStatus.OPEN && Dates.dayOf(it.createdAt) < span.from }
                SectionCard(Sym.checkCircle, stringResource(R.string.promises), foldKey = "retro:promises") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatLink(made, stringResource(R.string.stat_prom_made)) { session.periodView = PeriodKind.PROM_MADE to span }
                        StatLink(kept, stringResource(R.string.stat_prom_kept)) { session.periodView = PeriodKind.PROM_KEPT to span }
                        StatLink(oldOpen, stringResource(R.string.retro_old_open)) { session.showPromises = true }
                    }
                }
            }

            // People: met most often, and who changed their stance
            val moved = links.filter { it.stanceSince != null && it.stanceSince in span && it.previousStance != null }
            if (d.topPeople.isNotEmpty() || moved.isNotEmpty()) item(key = "people") {
                SectionCard(Sym.group, stringResource(R.string.retro_people), foldKey = "retro:people") {
                    if (d.topPeople.isNotEmpty()) {
                        Text(stringResource(R.string.retro_met_most), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            d.topPeople.forEach { (id, n) -> nameOf[id]?.let { MiniChip("$it · $n", onClick = { session.oneOnOne = id }) } }
                        }
                    }
                    if (moved.isNotEmpty()) {
                        Text(stringResource(R.string.votes_moved_period), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        moved.sortedByDescending { it.stanceSince }.take(10).forEach { l ->
                            val from = ua.mandat.data.Stance.of(l.previousStance)?.let { stringResource(it.label()) } ?: "—"
                            val to = ua.mandat.data.Stance.of(l.stance)?.let { stringResource(it.label()) } ?: "—"
                            TextTile("${nameOf[l.personId] ?: "—"}: $from → $to", (titleOf[l.matterId] ?: "") + " · " + Dates.format(l.stanceSince!!)) { session.openMatter(l.matterId) }
                        }
                    }
                }
            }
        }
    }
}

private class RetroData(
    val focus: List<Retro.FocusMonth>,
    val flow: List<Retro.MonthFlow>,
    val durations: Map<Long, Long>,
    val time: List<Pair<Span, Map<Long, Int>>>,
    val topPeople: List<Pair<Long, Int>>,
    val finishedIds: List<Long>,
)

/** Months as columns, each stacked from parts (value, colour); the label under each. */
@Composable
private fun MonthStacks(months: List<Pair<String, List<Pair<Int, Color>>>>) {
    val max = months.maxOfOrNull { m -> m.second.sumOf { it.first } }?.coerceAtLeast(1) ?: 1
    Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        months.forEach { (label, parts) ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                val total = parts.sumOf { it.first }
                Column(Modifier.fillMaxWidth(0.7f).weight(1f), verticalArrangement = Arrangement.Bottom) {
                    if (total > 0) Column(Modifier.fillMaxWidth().fillMaxHeight(total.toFloat() / max).clip(RoundedCornerShape(3.dp))) {
                        parts.filter { it.first > 0 }.reversed().forEach { (v, c) -> Box(Modifier.fillMaxWidth().weight(v.toFloat()).background(c)) }
                    }
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1)
            }
        }
    }
}

/** Two bars per month (for example new and finished); a month can be tapped. */
@Composable
private fun PairColumns(values: List<Pair<String, Pair<Int, Int>>>, colorA: Color, colorB: Color, onPick: (Int) -> Unit) {
    val max = values.maxOfOrNull { maxOf(it.second.first, it.second.second) }?.coerceAtLeast(1) ?: 1
    Row(Modifier.fillMaxWidth().height(100.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, (label, v) ->
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).clickable { onPick(i) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(Modifier.width(7.dp).fillMaxHeight((v.first.toFloat() / max).coerceAtLeast(0.02f)).clip(RoundedCornerShape(2.dp)).background(colorA))
                    Box(Modifier.width(7.dp).fillMaxHeight((v.second.toFloat() / max).coerceAtLeast(0.02f)).clip(RoundedCornerShape(2.dp)).background(colorB))
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1)
            }
        }
    }
}
