@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ua.mandat.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.mandat.R
import ua.mandat.data.MandatDb
import ua.mandat.data.Overview
import ua.mandat.data.Period
import ua.mandat.data.SETTING_CADENCE_START
import ua.mandat.data.Setting
import ua.mandat.data.Span
import ua.mandat.data.earliestDay
import ua.mandat.data.overview
import ua.mandat.data.spanOf
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The Review dashboard: at most four blocks, each answering one question, for a chosen period.
 * Everything is counted here on the phone from the owner's own records.
 */
@Composable
fun Dashboard(db: MandatDb, session: Session) {
    val roles by remember(db) { db.roles().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val matters by remember(db) { db.matters().observeAll().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val promises by remember(db) { db.promises().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val delegations by remember(db) { db.delegations().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val deadlines by remember(db) { db.deadlines().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val reviews by remember(db) { db.reviews().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val cadenceSetting by remember(db) { db.settings().observe(SETTING_CADENCE_START) }.collectAsStateWithLifecycle(null)
    val cadenceStart = cadenceSetting?.value?.toLongOrNull()

    var period by rememberSaveable { mutableStateOf(Period.WEEK) }
    var back by rememberSaveable { mutableStateOf(0) }
    var pickingCadence by remember { mutableStateOf(false) }

    val today = Dates.today()
    val earliest = remember(matters, promises, delegations, deadlines, reviews) {
        earliestDay(matters, promises, delegations, deadlines, reviews)
    }
    val span = spanOf(period, today, back, cadenceStart, earliest)
    val long = period == Period.CADENCE || period == Period.ALL
    // Counted away from the screen thread, so thousands of records never slow the screen down
    val data by androidx.compose.runtime.produceState<DashData?>(null, span, today, roles, matters, promises, delegations, deadlines, reviews) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val prevSpan = if (long) null else spanOf(period, today, back + 1, cadenceStart, earliest)
            DashData(
                overview(span, today, long, roles, matters, promises, delegations, deadlines, reviews),
                prevSpan?.let { overview(it, today, false, roles, matters, promises, delegations, deadlines, reviews) },
                ua.mandat.data.weeklyMoved(matters, today),
            )
        }
    }
    val o = data?.o ?: return

    Column(verticalArrangement = Arrangement.spacedBy(Space.gap)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
            Period.entries.forEach { p ->
                QuickChip(stringResource(p.label()), period == p) { period = p; back = 0 }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!long) {
                IconButton(onClick = { back++ }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.dash_earlier))
                }
            }
            Text(
                spanText(period, span, cadenceStart),
                style = MaterialTheme.typography.labelLarge,
                color = Muted,
                modifier = Modifier.weight(1f),
            )
            if (!long && back > 0) {
                IconButton(onClick = { back-- }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.dash_later))
                }
            }
            if (period == Period.CADENCE) {
                TextButton(onClick = { pickingCadence = true }) {
                    Text(stringResource(if (cadenceStart == null) R.string.dash_cadence_set else R.string.dash_cadence_change))
                }
            }
        }

        // Looking back over months: directions, past priorities and how things moved
        MCard(onClick = { session.showRetro = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.retro_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.retro_card_hint), style = MaterialTheme.typography.labelMedium, color = Muted)
                }
                Text("→", color = MaterialTheme.colorScheme.primary)
            }
        }
        // A short summary of the period by the on-device AI, from the same numbers and titles (only a suggestion)
        if (!o.isEmpty) AiAnswer(session, ua.mandat.ai.LocalAi.Task.WEEK, facts = {
            val moved = matters.filter { ua.mandat.data.MatterStatus.of(it.status) == ua.mandat.data.MatterStatus.ACTIVE && Dates.dayOf(it.updatedAt) in span }
            val stuck = matters.filter { ua.mandat.data.isStale(it, today) }
            buildString {
                append("Період: ").append(Dates.format(span.from)).append(" – ").append(Dates.format(span.to)).append("\n")
                append("Справ у русі: ").append(o.moved).append(", нових: ").append(o.started).append(", завершено: ").append(o.finished).append("\n")
                append("Строків настало: ").append(o.deadlinesDue).append(", вчасно: ").append(o.deadlinesOnTime).append("\n")
                if (moved.isNotEmpty()) append("Рухались: ").append(moved.take(10).joinToString("; ") { it.title + (it.nextStep.takeIf { n -> n.isNotBlank() }?.let { n -> " (далі: $n)" } ?: "") }).append("\n")
                if (stuck.isNotEmpty()) append("Без руху понад місяць: ").append(stuck.take(10).joinToString("; ") { it.title }).append("\n")
            }
        }, onUse = null)
        if (o.isEmpty) {
            EmptyHint(Sym.explore, stringResource(R.string.dash_empty))
        } else {
            val blocks = buildList<@Composable () -> Unit> {
                if (long) {
                    if (o.years.any { it.started + it.promised + it.deadlinesDone > 0 }) add { YearsBlock(o) { y -> session.periodView = PeriodKind.YEAR to yearSpan(y) } }
                } else if (o.moved + o.evenings > 0) {
                    add { MovedBlock(o, data?.prev, data?.weekly.orEmpty()) { kind -> session.periodView = kind to span } }
                }
                if (o.roles.any { it.moved > 0 }) add { RolesBlock(o, roles.associate { it.id to it.name }, hintIdle = !long, session) }
                add { TimeBlock(db, session, span, roles) }
                val open: (PeriodKind) -> Unit = { kind -> session.periodView = kind to span }
                if (o.deadlinesDue + o.delegationsGiven + o.delegationsDone > 0) add { DeadlinesBlock(o, open) }
                if (o.publicAll.total + o.personalPromises.total + o.keptInSpan > 0) add { PromisesBlock(o, open) { session.showPromises = true } }
            }
            BoxWithConstraints {
                if (maxWidth >= 600.dp) {
                    // Tablet: the same blocks in two columns
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                        listOf(0, 1).forEach { col ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.gap)) {
                                blocks.filterIndexed { i, _ -> i % 2 == col }.forEach { it() }
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.gap)) { blocks.forEach { it() } }
                }
            }
        }
    }

    if (pickingCadence) {
        DayPickerDialog(initial = cadenceStart ?: today, onPick = { day ->
            pickingCadence = false
            if (day != null && day <= today) {
                session.write { it.settings().put(Setting(SETTING_CADENCE_START, day.toString())) }
            }
        })
    }
}

/** A whole calendar year as a span of days. */
fun yearSpan(year: Int) = Span(java.time.LocalDate.of(year, 1, 1).toEpochDay(), java.time.LocalDate.of(year, 12, 31).toEpochDay())

private fun Period.label() = when (this) {
    Period.WEEK -> R.string.dash_week
    Period.MONTH -> R.string.dash_month
    Period.YEAR -> R.string.dash_year
    Period.CADENCE -> R.string.dash_cadence
    Period.ALL -> R.string.dash_all
}

@Composable
private fun spanText(period: Period, span: Span, cadenceStart: Long?): String {
    val from = LocalDate.ofEpochDay(span.from)
    val to = LocalDate.ofEpochDay(span.to)
    return when (period) {
        Period.WEEK -> {
            val f = DateTimeFormatter.ofPattern("d MMM")
            "${from.format(f)} – ${to.format(f)}"
        }
        Period.MONTH -> from.format(DateTimeFormatter.ofPattern("LLLL yyyy"))
        Period.YEAR -> from.year.toString()
        Period.CADENCE ->
            if (cadenceStart == null) stringResource(R.string.dash_cadence_unset)
            else stringResource(R.string.dash_since, Dates.format(span.from))
        Period.ALL -> stringResource(R.string.dash_since, Dates.format(span.from))
    }
}

/** A titled block of the dashboard. */
@Composable
private fun Block(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, content: @Composable () -> Unit) =
    SectionCard(icon, title, foldKey = "dash:" + title) {
        Spacer(Modifier.height(6.dp))
        content()
    }

/** Everything the dashboard shows, counted in the background. */
private class DashData(val o: Overview, val prev: Overview?, val weekly: List<Int>)

@Composable
private fun MovedBlock(o: Overview, prev: Overview?, weekly: List<Int>, open: (PeriodKind) -> Unit) = Block(Sym.checkCircle, stringResource(R.string.dash_moved_title)) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.gap)) {
        // One compact line of four numbers, each in its own colour, with the change since the last period
        Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            DeltaTile(o.moved, prev?.moved, stringResource(R.string.dash_moved), ChartColors.blue) { open(PeriodKind.MOVED) }
            StatDivider()
            DeltaTile(o.started, prev?.started, stringResource(R.string.dash_started_short), ChartColors.teal) { open(PeriodKind.STARTED) }
            StatDivider()
            DeltaTile(o.finished, prev?.finished, stringResource(R.string.dash_finished), ChartColors.green) { open(PeriodKind.FINISHED) }
            StatDivider()
            DeltaTile(o.evenings, prev?.evenings, stringResource(R.string.dash_evenings), ChartColors.purple) { open(PeriodKind.EVENINGS) }
        }
        if (weekly.any { it > 0 }) {
            Text(stringResource(R.string.dash_trend), style = MaterialTheme.typography.labelSmall, color = Muted)
            Sparkline(weekly, Modifier.fillMaxWidth().height(36.dp))
        }
    }
}

/** A number with the change since the previous period: ↑ green, ↓ coral; tapping opens the list behind it. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.DeltaTile(value: Int, prev: Int?, label: String, accent: Color, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(enabled = value > 0, onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, color = if (value > 0) accent else Muted)
            if (prev != null && prev != value) {
                val up = value > prev
                Spacer(Modifier.width(3.dp))
                Text((if (up) "↑" else "↓") + kotlin.math.abs(value - prev), style = MaterialTheme.typography.labelSmall,
                    color = if (up) ChartColors.teal else ChartColors.coral, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun StatDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** A small line of weekly values, oldest on the left; the last point is marked. */
@Composable
fun Sparkline(values: List<Int>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val max = values.max().coerceAtLeast(1).toFloat()
        val stepX = size.width / (values.size - 1)
        fun y(v: Int) = size.height - 4.dp.toPx() - (size.height - 8.dp.toPx()) * v / max
        val path = androidx.compose.ui.graphics.Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * stepX, y(v)) }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        drawCircle(color, 3.dp.toPx(), androidx.compose.ui.geometry.Offset((values.size - 1) * stepX, y(values.last())))
    }
}

@Composable
private fun RolesBlock(o: Overview, names: Map<Long, String>, hintIdle: Boolean, session: Session) =
    Block(Sym.badge, stringResource(R.string.dash_roles_title)) {
        val max = o.roles.maxOf { it.moved }.coerceAtLeast(1)
        val noRole = stringResource(R.string.dash_no_role)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            fun openRole(id: Long?) {
                session.mattersRequest = 1 to (id?.toString() ?: "none")
                session.tabRequest = "Matters"
            }
            // Roles that moved get a bar; those without a step are folded into one line below
            o.roles.filter { it.moved > 0 }.sortedByDescending { it.moved }.forEach { r ->
                val name = r.roleId?.let(names::get) ?: noRole
                // Tap a role: its matters, filtered, in Matters
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { openRole(r.roleId) }.padding(vertical = 2.dp),
                ) {
                    Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.width(140.dp))
                    Bar(r.moved.toFloat() / max, Modifier.weight(1f))
                    Text(r.moved.toString(), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(32.dp).padding(start = 8.dp))
                }
            }
            if (hintIdle) {
                val idle = o.roles.filter { it.moved == 0 && it.active > 0 }
                if (idle.isNotEmpty()) {
                    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                            .clickable { open = !open }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.dash_idle_row, idle.size), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        Text(if (open) "▾" else "▸", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    if (open) idle.forEach { r ->
                        Text(r.roleId?.let(names::get) ?: noRole, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { openRole(r.roleId) }.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }

@Composable
private fun DeadlinesBlock(o: Overview, open: (PeriodKind) -> Unit) = Block(Sym.schedule, stringResource(R.string.dash_deadlines_title)) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (o.deadlinesDue > 0) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatLink(o.deadlinesDue, stringResource(R.string.stat_deadlines_due)) { open(PeriodKind.DEADLINES_DUE) }
                StatLink(o.deadlinesOnTime, stringResource(R.string.stat_on_time)) { open(PeriodKind.ON_TIME) }
            }
            Bar(o.deadlinesOnTime.toFloat() / o.deadlinesDue, Modifier.fillMaxWidth())
        }
        if (o.delegationsGiven + o.delegationsDone > 0) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatLink(o.delegationsGiven, stringResource(R.string.stat_deleg_given)) { open(PeriodKind.DELEG_GIVEN) }
                StatLink(o.delegationsDone, stringResource(R.string.stat_deleg_done)) { open(PeriodKind.DELEG_DONE) }
            }
        }
    }
}

@Composable
private fun PromisesBlock(o: Overview, open: (PeriodKind) -> Unit, openAll: () -> Unit) = Block(Sym.checkCircle, stringResource(R.string.dash_promises_title)) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val made = o.publicPromises.total + o.personalPromises.total
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatLink(made, stringResource(R.string.stat_prom_made)) { open(PeriodKind.PROM_MADE) }
            StatLink(o.keptInSpan, stringResource(R.string.stat_prom_kept)) { open(PeriodKind.PROM_KEPT) }
        }
        if (o.publicAll.total > 0) {
            Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = openAll)) {
                Text(stringResource(R.string.dash_public_all) + " →", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                StackBar(
                    listOf(
                        Slice("k", stringResource(R.string.tally_kept), o.publicAll.kept, ChartColors.blue),
                        Slice("o", stringResource(R.string.tally_open), o.publicAll.open, ChartColors.blueLight),
                        Slice("f", stringResource(R.string.tally_failed), o.publicAll.failed, ChartColors.salmon),
                        Slice("d", stringResource(R.string.tally_dropped), o.publicAll.dropped, ChartColors.grayLight),
                    ),
                )
            }
        }
    }
}

@Composable
private fun YearsBlock(o: Overview, openYear: (Int) -> Unit) = Block(Sym.calendar, stringResource(R.string.dash_years_title)) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val max = o.years.maxOf { it.promised }.coerceAtLeast(1)
        o.years.sortedByDescending { it.year }.forEach { y ->
            Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { openYear(y.year) }.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(y.year.toString(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(52.dp))
                    // Promises made that year, the kept part darker
                    Box(Modifier.weight(1f)) {
                        if (y.promised > 0) {
                            Row(Modifier.fillMaxWidth(y.promised.toFloat() / max).height(8.dp).clip(RoundedCornerShape(4.dp))) {
                                if (y.kept > 0) Box(Modifier.weight(y.kept.toFloat()).height(8.dp).background(ChartColors.blue))
                                if (y.promised > y.kept) Box(Modifier.weight((y.promised - y.kept).toFloat()).height(8.dp).background(ChartColors.blueLight))
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.dash_year_line, y.started, y.promised, y.kept, y.deadlinesDone) + " →",
                    style = MaterialTheme.typography.labelMedium, color = Muted,
                    modifier = Modifier.padding(start = 52.dp),
                )
            }
        }
    }
}

/** A plain horizontal bar, [fraction] of the width filled. */
@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) Box(Modifier.fillMaxWidth(f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(ChartColors.blue))
    }
}

