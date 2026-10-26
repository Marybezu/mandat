package ua.mandat.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ua.mandat.R
import ua.mandat.data.Community
import ua.mandat.data.CommunityKind
import ua.mandat.data.CommunityLink
import ua.mandat.data.Event
import ua.mandat.data.EventKind
import ua.mandat.data.LinkKind
import ua.mandat.data.MandatDb
import ua.mandat.data.Matter
import ua.mandat.data.MatterStatus
import ua.mandat.data.Promise
import ua.mandat.data.SETTING_DISTRICT
import ua.mandat.data.TaskStatus

fun CommunityKind.label(): Int = when (this) {
    CommunityKind.CITY -> R.string.community_city
    CommunityKind.TOWN -> R.string.community_town
    CommunityKind.VILLAGE -> R.string.community_village
    CommunityKind.AREA -> R.string.community_area
    CommunityKind.OTHER -> R.string.community_other
}

/** A day with the owner in the community: a reception or a visit. */
private fun isVisit(e: Event) = EventKind.of(e.kind) == EventKind.RECEPTION || EventKind.of(e.kind) == EventKind.TRIP

/** Everything counted for one community. */
private data class CommunityStats(
    val c: Community,
    val matters: List<Matter>,
    val events: List<Event>,
    val promises: List<Promise>,
    val people: Int,
    val lastVisit: Long?,
    val visitsYear: Int,
    val doneYear: Int,
)

private fun stats(
    communities: List<Community>, links: List<CommunityLink>, matters: List<Matter>, events: List<Event>, promises: List<Promise>, today: Long,
): List<CommunityStats> {
    val byKind = links.groupBy { it.communityId }
    val matterOf = matters.associateBy { it.id }
    val eventOf = events.associateBy { it.id }
    val promiseOf = promises.associateBy { it.id }
    return communities.map { c ->
        val l = byKind[c.id].orEmpty()
        val ms = l.filter { it.kind == LinkKind.MATTER }.mapNotNull { matterOf[it.refId] }
        val es = l.filter { it.kind == LinkKind.EVENT }.mapNotNull { eventOf[it.refId] }
        val ps = l.filter { it.kind == LinkKind.PROMISE }.mapNotNull { promiseOf[it.refId] }
        val visits = es.filter { isVisit(it) && it.day <= today }
        CommunityStats(
            c, ms, es, ps, l.count { it.kind == LinkKind.PERSON },
            lastVisit = visits.maxOfOrNull { it.lastDay.coerceAtMost(today) },
            visitsYear = visits.count { it.day > today - 365 },
            doneYear = ms.count { MatterStatus.of(it.status) == MatterStatus.DONE && Dates.dayOf(it.updatedAt) > today - 365 },
        )
    }
}

/** On the Matters tab: the way into the district, shown when it is turned on or communities exist. */
@Composable
fun DistrictEntry(db: MandatDb, session: Session) {
    val on by remember(db) { db.settings().observe(SETTING_DISTRICT) }.collectAsStateWithLifecycle(null)
    val count by remember(db) { db.communities().observeCount() }.collectAsStateWithLifecycle(0)
    if (on?.value != "1" && count == 0) return
    OutlinedButton(onClick = { session.showDistrict = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.district_open, count))
    }
}

/**
 * The district: communities with how long since the last visit, what is open and what was done,
 * the coming receptions and visits, and a report by community. Those not visited longest come first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DistrictScreen(db: MandatDb, session: Session) {
    BackHandler { session.showDistrict = false }
    val communities by remember(db) { db.communities().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val links by remember(db) { db.communities().observeLinks() }.collectAsStateWithLifecycle(emptyList())
    val matters by remember(db) { db.matters().observeAll().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val events by remember(db) { db.events().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val promises by remember(db) { db.promises().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val today = Dates.today()
    val all = stats(communities, links, matters, events, promises, today).sortedWith(compareBy({ it.lastVisit != null }, { it.lastVisit }))
    val coming = events.filter { isVisit(it) && it.lastDay >= today && it.day <= today + 30 }.sortedBy { it.day }
    val openPromises = all.flatMap { it.promises }.distinctBy { it.id }.count { TaskStatus.of(it.status) == TaskStatus.OPEN }
    val longAgo = all.count { it.lastVisit == null || it.lastVisit < today - 90 }
    var sharing by remember { mutableStateOf(false) }
    // 0 all, 1 not visited for 90+ days, 2 with open promises
    var only by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.district_title)) },
                navigationIcon = {
                    IconButton(onClick = { session.showDistrict = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = { TextButton(onClick = { session.communityDraft = newCommunity() }) { Text("+ " + stringResource(R.string.community_new)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.gap),
        ) {
            item(key = "tiles") {
                MCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                        StatTile(communities.size, stringResource(R.string.district_communities)) { only = 0 }
                        StatTile(all.sumOf { it.visitsYear }, stringResource(R.string.district_visits_year)) { only = if (only == 3) 0 else 3 }
                    }
                    Spacer(Modifier.height(Space.gap))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                        StatTile(openPromises, stringResource(R.string.district_open_promises)) { only = if (only == 2) 0 else 2 }
                        StatTile(longAgo, stringResource(R.string.district_long_ago), attention = true) { only = if (only == 1) 0 else 1 }
                    }
                }
            }
            if (communities.isEmpty()) item(key = "empty") { EmptyHint(Sym.home, stringResource(R.string.district_empty)) }
            if (coming.isNotEmpty()) item(key = "coming") {
                SectionCard(Sym.calendar, stringResource(R.string.district_coming), count = coming.size) {
                    Paged(coming, page = 5) { e -> EventRow(e, emptyList(), null, null, session) }
                }
            }
            if (all.isNotEmpty()) item(key = "coverage") {
                SectionCard(Sym.home, stringResource(R.string.district_coverage), count = all.size) {
                    Text(stringResource(R.string.district_coverage_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                    Spacer(Modifier.height(4.dp))
                    val list = when (only) {
                        1 -> all.filter { it.lastVisit == null || it.lastVisit < today - 90 }
                        2 -> all.filter { s -> s.promises.any { TaskStatus.of(it.status) == TaskStatus.OPEN } }
                        3 -> all.filter { it.visitsYear > 0 }.sortedByDescending { it.visitsYear }
                        else -> all
                    }
                    if (only != 0) Row { FilterBadge(stringResource(when (only) { 1 -> R.string.district_long_ago; 3 -> R.string.district_visits_year; else -> R.string.district_open_promises })) { only = 0 } }
                    Paged(list) { s -> CommunityLine(s, today) { session.communityOpen = s.c.id } }
                }
            }
            if (all.isNotEmpty()) item(key = "report") {
                SectionCard(Sym.checkCircle, stringResource(R.string.district_report)) {
                    Text(stringResource(R.string.district_report_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                    Spacer(Modifier.height(4.dp))
                    all.sortedBy { it.c.name }.forEach { s ->
                        val kept = s.promises.count { TaskStatus.of(it.status) == TaskStatus.DONE }
                        val open = s.promises.count { TaskStatus.of(it.status) == TaskStatus.OPEN }
                        // A row opens the community, where each of these numbers is listed
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { session.communityOpen = s.c.id }.padding(vertical = 3.dp)) {
                            Text(s.c.name + " →", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.district_report_row, s.visitsYear, s.doneYear, kept, open), style = MaterialTheme.typography.labelMedium, color = Muted)
                        }
                    }
                    TextButton(onClick = { sharing = true }) { Text(stringResource(R.string.district_share)) }
                }
            }
        }
    }
    if (sharing) {
        val context = LocalContext.current
        val text = buildString {
            append(context.getString(R.string.district_report)).append("\n\n")
            all.sortedBy { it.c.name }.forEach { s ->
                append("• ").append(s.c.name).append(": ")
                append(context.getString(R.string.district_report_row, s.visitsYear, s.doneYear,
                    s.promises.count { TaskStatus.of(it.status) == TaskStatus.DONE }, s.promises.count { TaskStatus.of(it.status) == TaskStatus.OPEN }))
                append("\n")
            }
        }
        AlertDialog(
            onDismissRequest = { sharing = false },
            title = { Text(stringResource(R.string.district_share)) },
            text = { Text(stringResource(R.string.district_share_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    sharing = false
                    try {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                    } catch (e: android.content.ActivityNotFoundException) {
                        // No app to share with
                    }
                }) { Text(stringResource(R.string.event_to_phone_go)) }
            },
            dismissButton = { TextButton(onClick = { sharing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** One community: name, how long since the last visit (a bar that grows with time), and counts. */
@Composable
private fun CommunityLine(s: CommunityStats, today: Long, onOpen: () -> Unit) {
    val days = s.lastVisit?.let { (today - it).toInt() }
    val fraction = ((days ?: 365).coerceAtMost(365)) / 365f
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onOpen).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.c.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (days == null) stringResource(R.string.district_never) else stringResource(R.string.district_days_ago, days),
                style = MaterialTheme.typography.labelMedium, color = if (days == null || days > 90) ChartColors.coral else Muted)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surface)) {
            Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.02f)).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                .background(if (days == null || days > 90) ChartColors.coral else if (days > 30) ChartColors.amber else ChartColors.teal))
        }
        Spacer(Modifier.height(4.dp))
        val active = s.matters.count { MatterStatus.of(it.status) == MatterStatus.ACTIVE }
        val open = s.promises.count { TaskStatus.of(it.status) == TaskStatus.OPEN }
        Text(stringResource(R.string.district_line, active, open, s.people), style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

/** One community: its card, then everything linked to it, with ways to link more. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CommunityScreen(db: MandatDb, session: Session, id: Long) {
    BackHandler { session.communityOpen = null }
    val communities by remember(db) { db.communities().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val links by remember(db) { db.communities().observeLinks() }.collectAsStateWithLifecycle(emptyList())
    val matters by remember(db) { db.matters().observeAll().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val events by remember(db) { db.events().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val promises by remember(db) { db.promises().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val roles by remember(db) { db.roles().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val c = communities.firstOrNull { it.id == id }
    // Deleted meanwhile (or not loaded yet): go back once the list is known
    if (c == null) {
        androidx.compose.runtime.LaunchedEffect(communities) { if (communities.isNotEmpty()) session.communityOpen = null }
        return
    }
    val today = Dates.today()
    val s = stats(listOf(c), links, matters, events, promises, today).first()
    val mine = links.filter { it.communityId == id }
    val linkedPeople = mine.filter { it.kind == LinkKind.PERSON }.mapNotNull { l -> people.firstOrNull { it.id == l.refId } }
    var picking by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val roleNames = roles.associate { it.id to it.name }
    fun unlink(kind: String, ref: Long) = session.write { it.communities().unlink(id, kind, ref) }

    @Composable
    fun Removable(kind: String, ref: Long, content: @Composable () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { content() }
            TextButton(onClick = { unlink(kind, ref) }) { Text("✕") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { session.communityOpen = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = { TextButton(onClick = { session.communityDraft = CommunityDraft(c, isNew = false) }) { Text(stringResource(R.string.edit)) } },
            )
        },
    ) { padding ->
        // Each number at the top scrolls to the list it counts
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        fun goTo(key: String) {
            val i = listOf("card", "visits", "matters", "promises", "people").indexOf(key)
            if (i >= 0) scope.launch { listState.animateScrollToItem(i) }
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.gap),
        ) {
            item(key = "card") {
                MCard {
                    val meta = listOfNotNull(stringResource(CommunityKind.of(c.kind).label()), c.population?.let { stringResource(R.string.community_people, groupDigits(it)) },
                        c.head.ifBlank { null }).joinToString(" · ")
                    Text(meta, style = MaterialTheme.typography.labelLarge, color = Muted)
                    if (c.notes.isNotBlank()) Text(c.notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(Space.gap))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                        StatTile(s.visitsYear, stringResource(R.string.district_visits_year)) { goTo("visits") }
                        StatTile(s.doneYear, stringResource(R.string.district_done_year)) { goTo("matters") }
                        StatTile(s.promises.count { TaskStatus.of(it.status) == TaskStatus.OPEN }, stringResource(R.string.district_open_promises)) { goTo("promises") }
                    }
                    Text(s.lastVisit?.let { stringResource(R.string.district_last_visit, Dates.format(it)) } ?: stringResource(R.string.district_never),
                        style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item(key = "visits") {
                SectionCard(Sym.calendar, stringResource(R.string.district_visits), count = s.events.size) {
                    s.events.sortedByDescending { it.day }.take(20).forEach { e -> Removable(LinkKind.EVENT, e.id) { EventRow(e, emptyList(), null, null, session) } }
                    Row {
                        TextButton(onClick = {
                            session.eventDraft = EventDraft(
                                Event(title = "", day = today, kind = EventKind.RECEPTION.name, place = c.name, createdAt = 0, updatedAt = 0),
                                isNew = true, communityId = id,
                            ).also { it.title = c.name }
                        }) { Text("+ " + stringResource(R.string.event_reception)) }
                        TextButton(onClick = { picking = LinkKind.EVENT }) { Text(stringResource(R.string.district_link_existing)) }
                    }
                }
            }
            item(key = "matters") {
                SectionCard(Sym.folder, stringResource(R.string.district_matters), count = s.matters.size) {
                    s.matters.sortedBy { MatterStatus.of(it.status) != MatterStatus.ACTIVE }.forEach { m ->
                        Removable(LinkKind.MATTER, m.id) { MatterLine(m, roleNames[m.roleId]) { session.openMatter(m.id) } }
                    }
                    TextButton(onClick = { picking = LinkKind.MATTER }) { Text("+ " + stringResource(R.string.district_link_matter)) }
                }
            }
            item(key = "promises") {
                SectionCard(Sym.checkCircle, stringResource(R.string.promises), count = s.promises.size) {
                    s.promises.forEach { p -> Removable(LinkKind.PROMISE, p.id) { PromiseRow(p, p.personId?.let { pid -> people.firstOrNull { it.id == pid }?.name }, null, session) } }
                    TextButton(onClick = { picking = LinkKind.PROMISE }) { Text("+ " + stringResource(R.string.district_link_promise)) }
                }
            }
            item(key = "people") {
                SectionCard(Sym.group, stringResource(R.string.district_people), count = linkedPeople.size) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        linkedPeople.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MiniChip(p.name, onClick = { session.openPerson(p.id) })
                                TextButton(onClick = { unlink(LinkKind.PERSON, p.id) }) { Text("✕") }
                            }
                        }
                    }
                    TextButton(onClick = { picking = LinkKind.PERSON }) { Text("+ " + stringResource(R.string.add_person)) }
                }
            }
            item(key = "delete") { TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.community_delete)) } }
        }
    }
    picking?.let { kind ->
        val linked = mine.filter { it.kind == kind }.map { it.refId }.toSet()
        if (kind == LinkKind.PERSON) {
            PersonOrContactDialog(db, session, exclude = linked, onPick = { pid ->
                picking = null
                session.write { it.communities().link(CommunityLink(id, kind, pid)) }
            }, onCancel = { picking = null })
        } else {
            val options = when (kind) {
                LinkKind.MATTER -> matters.filter { MatterStatus.of(it.status) == MatterStatus.ACTIVE }.map { it.id to it.title }
                LinkKind.PROMISE -> promises.filter { TaskStatus.of(it.status) == TaskStatus.OPEN }.map { it.id to it.what }
                else -> events.filter { isVisit(it) }.sortedByDescending { it.day }.map { it.id to "${Dates.short(it.day)} · ${it.title}" }
            }.filter { it.first !in linked }
            PickTextDialog(options, onPick = { ref ->
                picking = null
                session.write { it.communities().link(CommunityLink(id, kind, ref)) }
            }, onCancel = { picking = null })
        }
    }
    if (confirmDelete) {
        ConfirmDelete(onConfirm = {
            confirmDelete = false
            session.communityOpen = null
            session.write { it.communities().delete(id) }
        }, onCancel = { confirmDelete = false })
    }
}

/** Choose one item from a list by its text, with a search box. */
@Composable
fun PickTextDialog(options: List<Pair<Long, String>>, onPick: (Long) -> Unit, onCancel: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = options.filter { q.isBlank() || it.second.contains(q.trim(), ignoreCase = true) }.take(100)
    AlertDialog(
        onDismissRequest = onCancel,
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth())
                Column(Modifier.height(340.dp).verticalScroll(rememberScrollState())) {
                    if (shown.isEmpty()) Text(stringResource(R.string.search_nothing), color = Muted, modifier = Modifier.padding(top = 12.dp))
                    shown.forEach { (id, text) ->
                        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = 10.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommunityDialog(session: Session, d: CommunityDraft) {
    AlertDialog(
        onDismissRequest = { session.communityDraft = null },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.community_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(d.name, { d.name = it }, label = { Text(stringResource(R.string.community_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CommunityKind.entries.forEach { k -> FilterChip(selected = d.kind == k, onClick = { d.kind = k }, label = { Text(stringResource(k.label())) }) }
                }
                OutlinedTextField(d.population, { d.population = it.filter { ch -> ch.isDigit() }.take(9) }, label = { Text(stringResource(R.string.community_population)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.head, { d.head = it }, label = { Text(stringResource(R.string.community_head)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.notes, { d.notes = it }, label = { Text(stringResource(R.string.community_notes)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { session.saveCommunityDraft() }, enabled = d.name.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { session.communityDraft = null }) { Text(stringResource(R.string.cancel)) } },
    )
}
