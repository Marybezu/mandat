package ua.mandat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import ua.mandat.R
import ua.mandat.data.MandatDb
import ua.mandat.data.Role
import ua.mandat.data.Setting
import ua.mandat.data.Span
import ua.mandat.data.TIME_SHARE_PREFIX
import ua.mandat.data.eventMinutes
import ua.mandat.data.minutesByRole

/**
 * Where the time went, by role, for the dashboard's period: calendar events through their
 * matter, plus time added by hand. Next to each role, its share and the share the owner wanted
 * ("as planned / as it went"). A row opens what the time was spent on.
 */
@Composable
fun TimeBlock(db: MandatDb, session: Session, span: Span, roles: List<Role>) {
    val events by remember(db) { db.events().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val entries by remember(db) { db.time().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val matters by remember(db) { db.matters().observeAll().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val shares by remember(db) { db.time().observeShares() }.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf(false) }
    val roleOfMatter = matters.associate { it.id to it.roleId }
    val byRole = minutesByRole(span, events, entries, roleOfMatter)
    val total = byRole.values.sum()
    val planned = shares.associate { it.key.removePrefix(TIME_SHARE_PREFIX).toLongOrNull() to (it.value.toIntOrNull() ?: 0) }
    val names = roles.associate { it.id to it.name }
    if (total == 0 && planned.isEmpty()) return
    SectionCard(Sym.schedule, stringResource(R.string.time_by_role), foldKey = "dash:time") {
        Text(stringResource(R.string.time_total, hoursText(total)), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.time_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
        val rows = (roles.map { it.id } + 0L).filter { (byRole[it] ?: 0) > 0 || (planned[it] ?: 0) > 0 }
            .sortedByDescending { byRole[it] ?: 0 }
        val max = rows.maxOfOrNull { byRole[it] ?: 0 }?.coerceAtLeast(1) ?: 1
        rows.forEachIndexed { i, id ->
            val minutes = byRole[id] ?: 0
            val share = if (total == 0) 0 else minutes * 100 / total
            val plan = planned[id]
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { session.roleTime = id to span }.padding(vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(names[id] ?: stringResource(R.string.time_no_role), style = MaterialTheme.typography.labelLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        hoursText(minutes) + " · $share %" + (plan?.let { " " + stringResource(R.string.time_plan, it) } ?: "") + " →",
                        style = MaterialTheme.typography.labelMedium,
                        // Far from the plan (by a third of it or more) is marked, in words as well as colour
                        color = if (plan != null && plan > 0 && kotlin.math.abs(share - plan) * 3 >= plan) ChartColors.coral else Muted,
                    )
                }
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    if (minutes > 0) Box(Modifier.fillMaxWidth(minutes.toFloat() / max).height(8.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (id == 0L) ChartColors.grayLight else ChartColors.blue))
                }
            }
        }
        TextButton(onClick = { editing = true }) { Text(stringResource(R.string.time_set_plan)) }
    }
    if (editing) SharesDialog(session, roles, planned) { editing = false }
}

/** The share of time the owner wants for each role, in percent. */
@Composable
private fun SharesDialog(session: Session, roles: List<Role>, planned: Map<Long?, Int>, onClose: () -> Unit) {
    val values = remember { mutableStateMapOf<Long, String>().apply { roles.forEach { r -> put(r.id, planned[r.id]?.toString().orEmpty()) } } }
    val sum = values.values.sumOf { it.toIntOrNull() ?: 0 }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.time_set_plan)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.time_plan_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                roles.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, modifier = Modifier.weight(1f))
                        OutlinedTextField(values[r.id].orEmpty(), { v -> values[r.id] = v.filter { it.isDigit() }.take(3) }, singleLine = true,
                            suffix = { Text("%") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp))
                    }
                }
                Text(stringResource(R.string.time_plan_sum, sum), style = MaterialTheme.typography.labelMedium,
                    color = if (sum == 100 || sum == 0) Muted else ChartColors.coral)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val chosen = values.mapValues { it.value.toIntOrNull()?.coerceIn(0, 100) ?: 0 }
                session.write { db ->
                    db.withTransaction {
                        db.settings().deletePrefix(TIME_SHARE_PREFIX)
                        chosen.filterValues { it > 0 }.forEach { (id, v) -> db.settings().put(Setting(TIME_SHARE_PREFIX + id, v.toString())) }
                    }
                }
                onClose()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

/** What the time of one role (0: no role) went to in a period: events and time added by hand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleTimeScreen(db: MandatDb, session: Session, roleId: Long, span: Span) {
    BackHandler { session.roleTime = null }
    val roles by remember(db) { db.roles().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val events by remember(db) { db.events().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val entries by remember(db) { db.time().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val matters by remember(db) { db.matters().observeAll().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val roleOfMatter = matters.associate { it.id to it.roleId }
    val titleOf = matters.associate { it.id to it.title }
    fun roleOf(matterId: Long?, kept: Long?): Long =
        if (matterId != null && roleOfMatter.containsKey(matterId)) roleOfMatter[matterId] ?: 0L else kept ?: 0L
    val shownEvents = events.filter { it.day in span && eventMinutes(it) > 0 && roleOf(it.matterId, null) == roleId }.sortedByDescending { it.day }
    val shownEntries = entries.filter { it.day in span && roleOf(it.matterId, it.roleId) == roleId }
    val title = roles.firstOrNull { it.id == roleId }?.name ?: stringResource(R.string.time_no_role)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { session.roleTime = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.gap),
        ) {
            item(key = "span") {
                Text("${Dates.format(span.from)} – ${Dates.format(minOf(span.to, Dates.today()))}", style = MaterialTheme.typography.labelLarge, color = Muted)
            }
            if (shownEvents.isEmpty() && shownEntries.isEmpty()) item(key = "empty") { EmptyHint(Sym.checkCircle, stringResource(R.string.smart_empty)) }
            if (shownEvents.isNotEmpty()) item(key = "events") {
                SectionCard(Sym.calendar, stringResource(R.string.time_from_events, hoursText(shownEvents.sumOf { eventMinutes(it) })), count = shownEvents.size) {
                    Paged(shownEvents) { e -> EventRow(e, emptyList(), e.matterId?.let(titleOf::get), null, session) }
                }
            }
            if (shownEntries.isNotEmpty()) item(key = "entries") {
                SectionCard(Sym.schedule, stringResource(R.string.time_by_hand, hoursText(shownEntries.sumOf { it.minutes })), count = shownEntries.size) {
                    Paged(shownEntries) { t ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(enabled = t.matterId != null) { t.matterId?.let(session::openMatter) }.padding(vertical = 4.dp)) {
                                Text(t.matterId?.let(titleOf::get) ?: "—", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Dates.format(t.day) + " · " + hoursText(t.minutes), style = MaterialTheme.typography.labelMedium, color = Muted)
                            }
                            TextButton(onClick = { session.write { it.time().delete(t.id) } }) { Text("✕") }
                        }
                    }
                }
            }
        }
    }
}
