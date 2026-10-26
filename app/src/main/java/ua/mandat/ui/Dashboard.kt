package ua.mandat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import ua.mandat.data.Tally
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
    val matters by remember(db) { db.matters().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val promises by remember(db) { db.promises().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val delegations by remember(db) { db.delegations().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val deadlines by remember(db) { db.deadlines().observeAll() }.collectAsStateWithLifecycle(emptyList())
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
    val o = remember(span, today, roles, matters, promises, delegations, deadlines, reviews) {
        overview(span, today, long, roles, matters, promises, delegations, deadlines, reviews)
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.gap)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
            Period.entries.forEach { p ->
                FilterChip(
                    selected = period == p,
                    onClick = { period = p; back = 0 },
                    label = { Text(stringResource(p.label())) },
                )
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

        if (o.isEmpty) {
            EmptyHint(Sym.explore, stringResource(R.string.dash_empty))
        } else {
            val blocks = buildList<@Composable () -> Unit> {
                if (long) {
                    if (o.years.any { it.started + it.promised + it.deadlinesDone > 0 }) add { YearsBlock(o) }
                } else if (o.moved + o.evenings > 0) {
                    add { MovedBlock(o) }
                }
                if (o.roles.any { it.moved > 0 }) add { RolesBlock(o, roles.associate { it.id to it.name }, hintIdle = !long) }
                if (o.deadlinesDue + o.delegationsGiven + o.delegationsDone > 0) add { DeadlinesBlock(o) }
                if (o.publicAll.total + o.personalPromises.total + o.keptInSpan > 0) add { PromisesBlock(o) }
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
    SectionCard(icon, title) {
        Spacer(Modifier.height(6.dp))
        content()
    }

@Composable
private fun MovedBlock(o: Overview) = Block(Sym.checkCircle, stringResource(R.string.dash_moved_title)) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.gap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
            StatTile(o.moved, stringResource(R.string.dash_moved))
            StatTile(o.started, stringResource(R.string.dash_started))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
            StatTile(o.finished, stringResource(R.string.dash_finished))
            StatTile(o.evenings, stringResource(R.string.dash_evenings))
        }
    }
}

@Composable
private fun RolesBlock(o: Overview, names: Map<Long, String>, hintIdle: Boolean) =
    Block(Sym.badge, stringResource(R.string.dash_roles_title)) {
        val max = o.roles.maxOf { it.moved }.coerceAtLeast(1)
        val noRole = stringResource(R.string.dash_no_role)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            o.roles.sortedByDescending { it.moved }.forEach { r ->
                val name = r.roleId?.let(names::get) ?: noRole
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
                    Bar(r.moved.toFloat() / max, Modifier.weight(1f))
                    Text(r.moved.toString(), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(32.dp).padding(start = 8.dp))
                }
            }
            if (hintIdle) {
                val idle = o.roles.filter { it.moved == 0 && it.active > 0 }.map { it.roleId?.let(names::get) ?: noRole }
                if (idle.isNotEmpty()) {
                    Text(stringResource(R.string.dash_idle, idle.joinToString(", ")),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

@Composable
private fun DeadlinesBlock(o: Overview) = Block(Sym.schedule, stringResource(R.string.dash_deadlines_title)) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (o.deadlinesDue > 0) {
            Text(stringResource(R.string.dash_on_time, o.deadlinesOnTime, o.deadlinesDue), style = MaterialTheme.typography.bodyMedium)
            Bar(o.deadlinesOnTime.toFloat() / o.deadlinesDue, Modifier.fillMaxWidth())
        }
        if (o.delegationsGiven + o.delegationsDone > 0) {
            Text(stringResource(R.string.dash_delegations, o.delegationsGiven, o.delegationsDone),
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PromisesBlock(o: Overview) = Block(Sym.checkCircle, stringResource(R.string.dash_promises_title)) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val made = o.publicPromises.total + o.personalPromises.total
        Text(stringResource(R.string.dash_promises_period, made, o.keptInSpan), style = MaterialTheme.typography.bodyMedium)
        if (o.publicAll.total > 0) {
            Text(stringResource(R.string.dash_public_all), style = MaterialTheme.typography.labelMedium, color = Muted,
                modifier = Modifier.padding(top = 4.dp))
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

@Composable
private fun YearsBlock(o: Overview) = Block(Sym.calendar, stringResource(R.string.dash_years_title)) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val max = o.years.maxOf { it.promised }.coerceAtLeast(1)
        o.years.sortedByDescending { it.year }.forEach { y ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(y.year.toString(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(52.dp))
                    // Promises made that year, the kept part darker
                    Box(Modifier.weight(1f)) {
                        if (y.promised > 0) {
                            Row(Modifier.fillMaxWidth(y.promised.toFloat() / max).height(8.dp).clip(RoundedCornerShape(4.dp))) {
                                if (y.kept > 0) Box(Modifier.weight(y.kept.toFloat()).height(8.dp).background(MaterialTheme.colorScheme.primary))
                                if (y.promised > y.kept) Box(Modifier.weight((y.promised - y.kept).toFloat()).height(8.dp).background(MaterialTheme.colorScheme.primaryContainer))
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.dash_year_line, y.started, y.promised, y.kept, y.deadlinesDone),
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
        if (f > 0f) Box(Modifier.fillMaxWidth(f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun tallyColors(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.primaryContainer,
    MaterialTheme.colorScheme.error.copy(alpha = 0.55f),
    MaterialTheme.colorScheme.outline,
)

@Composable
fun TallyBar(t: Tally) {
    if (t.total == 0) return
    val colors = tallyColors()
    Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
        listOf(t.kept, t.open, t.failed, t.dropped).forEachIndexed { i, n ->
            if (n > 0) Box(Modifier.weight(n.toFloat()).height(10.dp).background(colors[i]))
        }
    }
}

@Composable
fun TallyLegend(t: Tally) {
    Text(
        stringResource(R.string.dash_tally, t.kept, t.open, t.failed, t.dropped),
        style = MaterialTheme.typography.labelMedium, color = Muted,
    )
}
