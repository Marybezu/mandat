package ua.mandat.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.mandat.R
import ua.mandat.data.Delegation
import ua.mandat.data.MandatDb
import ua.mandat.data.Person
import ua.mandat.data.Stance
import ua.mandat.data.TaskStatus

/** Most people manage complex work well with up to this many direct reports. */
const val SPAN_OF_CONTROL = 7

/** After a delegation is done: a gentle offer to thank the person. Writing it leaves Mandat. */
@Composable
fun ThankDialog(db: MandatDb, session: Session, d: Delegation) {
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    val name = people.firstOrNull { it.id == d.personId }?.name ?: return
    val context = LocalContext.current
    val text = stringResource(R.string.thank_text, d.what)
    fun mark() {
        session.thankFor = null
        session.write { it.delegations().update(d.copy(status = TaskStatus.DONE.name, closedAt = d.closedAt ?: System.currentTimeMillis(), thanked = true)) }
    }
    EditSheet(
        title = stringResource(R.string.thank_title, name),
        onDismiss = { session.thankFor = null },
        onSave = {
                mark()
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, null))
            },
        saveText = stringResource(R.string.thank_write),
    ) {
        SheetGroup {
Text(stringResource(R.string.thank_hint), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The team at a glance: how many report to the owner directly (with a gentle note above the
 * span of control), and how many open delegations each person has. Facts, not a rating.
 */
@Composable
fun TeamCard(people: List<Person>, delegations: List<Delegation>, session: Session) {
    val team = people.filter { it.teamRole != null }
    if (team.isEmpty()) return
    val direct = team.count { it.reportsToId == null || team.none { t -> t.id == it.reportsToId } }
    val open = delegations.filter { TaskStatus.of(it.status) == TaskStatus.OPEN }
    val load = team.associateWith { p -> open.count { it.personId == p.id } }
    val max = load.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val returned = open.count { it.returned }
    SectionCard(Sym.group, stringResource(R.string.team_load_title)) {
        Text(stringResource(R.string.direct_reports, direct), style = MaterialTheme.typography.bodyMedium)
        if (direct > SPAN_OF_CONTROL) {
            Text(stringResource(R.string.span_hint, SPAN_OF_CONTROL), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp))
        }
        if (returned > 0) {
            Text(stringResource(R.string.returned_count, returned), style = MaterialTheme.typography.labelMedium, color = Muted,
                modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.height(8.dp))
        team.sortedByDescending { load[it] }.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { session.oneOnOne = p.id }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(p.name, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.width(120.dp))
                Bar((load[p] ?: 0).toFloat() / max, Modifier.weight(1f))
                Text((load[p] ?: 0).toString(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp).padding(start = 8.dp))
            }
        }
        Text(stringResource(R.string.team_load_hint), style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * Before meeting someone: everything about them on one screen, gathered by itself. For the team
 * it is a one-to-one agenda (check-ins, open and done delegations); for anyone else a brief: who
 * they are, shared matters with their stance and next step, what was promised and asked, the
 * last meetings with their outcome and diary notes about them.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun OneOnOneScreen(db: MandatDb, session: Session, personId: Long) {
    BackHandler { session.oneOnOne = null }
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    val delegations by remember(db, personId) { db.delegations().observeForPerson(personId).hidingNamed(session, { it.matterId }) { it.what + " " + it.purpose + " " + it.doneMeans } }.collectAsStateWithLifecycle(emptyList())
    val promises by remember(db, personId) { db.promises().observeForPerson(personId).hidingNamed(session, { it.matterId }) { it.what + " " + it.toWhom + " " + it.source + " " + it.statusNote } }.collectAsStateWithLifecycle(emptyList())
    val matters by remember(db, personId) { db.people().observeMattersOf(personId).hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val titleOf = titles.associate { it.id to it.title }
    val allMatters by remember(db) { db.matters().observeAll().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val nextOf = allMatters.associate { it.id to it.nextStep }
    val events by remember(db, personId) { db.events().observeForPerson(personId).hidingNamed(session, { it.matterId }) { it.title + " " + it.notes + " " + it.place + " " + it.outcome } }.collectAsStateWithLifecycle(emptyList())
    val reviews by remember(db) { db.reviews().observeAll().hidingReviews(session) }.collectAsStateWithLifecycle(emptyList())
    val reviewLinks by remember(db) { db.reviews().observeLinks() }.collectAsStateWithLifecycle(emptyList())
    val hidden by session.hiddenMatters.collectAsStateWithLifecycle()
    val p = people.firstOrNull { it.id == personId }
    val today = Dates.today()
    // Diary notes about this person, leaving out those tied to a hidden matter
    val notesAbout = remember(reviews, reviewLinks, hidden, personId) {
        val byReview = reviewLinks.groupBy { it.reviewId }
        val mine = byReview.filterValues { l -> l.any { it.kind == "person" && it.refId == personId } && l.none { it.kind == "matter" && it.refId in hidden } }.keys
        reviews.filter { it.id in mine && it.progress.isNotBlank() }.sortedByDescending { it.day }.take(3)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (p?.teamRole != null) R.string.one_on_one else R.string.brief_title)) },
                navigationIcon = {
                    IconButton(onClick = { session.oneOnOne = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (p == null) return@Scaffold
        val team = p.teamRole != null
        val open = delegations.filter { TaskStatus.of(it.status) == TaskStatus.OPEN }
        val done = delegations.filter { TaskStatus.of(it.status) == TaskStatus.DONE && it.closedAt != null && Dates.dayOf(it.closedAt) > today - 30 }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.gap),
        ) {
            item(key = "who") {
                MCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Initials(p.name, team = team)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            val role = listOf(p.jobTitle, p.organization).filter { it.isNotBlank() }.joinToString(" · ")
                            if (role.isNotEmpty()) Text(role, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                p.lastContact?.let { stringResource(R.string.last_talk, Dates.format(it)) } ?: stringResource(R.string.last_talk_unknown),
                                style = MaterialTheme.typography.labelMedium, color = Muted,
                            )
                        }
                    }
                    TextButton(onClick = { session.write { it.people().setLastContact(p.id, today) } }) {
                        Text(stringResource(R.string.talked_today))
                    }
                    if (!team && p.allCategories.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        p.allCategories.forEach { MiniChip(categoryLabel(it)) }
                    }
                    if (p.notes.isNotBlank()) Text(p.notes, style = MaterialTheme.typography.bodyMedium, color = Muted, maxLines = 4)
                    Spacer(Modifier.height(6.dp))
                    ReachButtons(ua.mandat.data.phonesIn(p.contact), ua.mandat.data.emailsIn(p.contact))
                    // A short brief drafted by the on-device AI from these same facts (only a suggestion)
                    AiAnswer(session, ua.mandat.ai.LocalAi.Task.BRIEF, facts = { session.withOpenDb { briefFacts(it, session, p.id) }.orEmpty() })
                    // To print or have at hand: the brief as PDF, through Export (with a password by default)
                    QuickChip(stringResource(R.string.brief_pdf), false, Modifier.padding(top = 6.dp)) {
                        session.exportBrief = p.id
                        session.showExport = true
                    }
                }
            }
            // The next meeting and the last ones, with what came of them
            val next = events.filter { it.lastDay >= today }.minByOrNull { it.day }
            val past = events.filter { it.lastDay < today }.sortedByDescending { it.day }.take(3)
            if (next != null || past.isNotEmpty()) item(key = "meetings") {
                SectionCard(Sym.calendar, stringResource(R.string.brief_meetings), count = events.size) {
                    next?.let { e ->
                        Text(stringResource(R.string.brief_next), style = MaterialTheme.typography.labelMedium, color = Muted)
                        EventRow(e, emptyList(), e.matterId?.let(titleOf::get), null, session)
                    }
                    if (past.isNotEmpty()) {
                        Text(stringResource(R.string.brief_last), style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.padding(top = 6.dp))
                        past.forEachIndexed { i, e -> if (i > 0) RowDivider(); EventRow(e, emptyList(), e.matterId?.let(titleOf::get), null, session) }
                    }
                }
            }
            val checkIns = open.filter { it.checkIn != null && it.checkIn <= today + 7 }
            if (checkIns.isNotEmpty()) item(key = "check") {
                SectionCard(Sym.schedule, stringResource(R.string.agenda_check_ins), count = checkIns.size) {
                    Paged(checkIns.sortedBy { it.checkIn }) { d -> DelegationRow(d, null, d.matterId?.let(titleOf::get), session) }
                }
            }
            if (open.isNotEmpty()) item(key = "open") {
                SectionCard(Sym.checkCircle, stringResource(if (team) R.string.agenda_open else R.string.brief_asked), count = open.size) {
                    Paged(open) { d -> DelegationRow(d, null, d.matterId?.let(titleOf::get), session) }
                }
            }
            if (team && done.isNotEmpty()) item(key = "done") {
                SectionCard(Sym.checkCircle, stringResource(R.string.agenda_done), count = done.size) {
                    done.forEachIndexed { i, d ->
                        if (i > 0) RowDivider()
                        DelegationRow(d, null, d.matterId?.let(titleOf::get), session)
                        if (!d.thanked) TextButton(onClick = { session.thankFor = d }) { Text(stringResource(R.string.thank_short)) }
                    }
                }
            }
            if (matters.isNotEmpty()) item(key = "matters") {
                SectionCard(Sym.folder, stringResource(R.string.agenda_matters), count = matters.size) {
                    Paged(matters) { m ->
                        val stance = ua.mandat.data.Stance.of(m.stance)?.let { stringResource(it.label()) }
                        val sub = listOfNotNull(stance, nextOf[m.matterId]?.takeIf { it.isNotBlank() }?.let { "→ $it" }).joinToString(" · ")
                        TextTile(m.title, sub.ifEmpty { null }) { session.openMatter(m.matterId) }
                    }
                }
            }
            val openP = promises.filter { TaskStatus.of(it.status) == TaskStatus.OPEN }
            if (openP.isNotEmpty()) item(key = "promises") {
                SectionCard(Sym.checkCircle, stringResource(R.string.agenda_promises), count = openP.size) {
                    Paged(openP) { x -> PromiseRow(x, null, x.matterId?.let(titleOf::get), session) }
                }
            }
            if (notesAbout.isNotEmpty()) item(key = "diary") {
                SectionCard(Sym.bedtime, stringResource(R.string.brief_diary), count = notesAbout.size) {
                    notesAbout.forEach { r -> TextTile(r.progress, Dates.format(r.day)) }
                }
            }
        }
    }
}

/** Where someone stands, for the map: per matter if given, otherwise their general stance. */
private enum class Cell { RELY, PERSUADE, WATCH, INFORM }

/**
 * The influence map (influence × stance): whom to rely on, whom to persuade, whom to watch,
 * whom to keep informed. For one matter or for everyone the owner works with. The team is
 * never placed here: no rating of one's own people.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun InfluenceMapScreen(db: MandatDb, session: Session, matterId: Long?) {
    BackHandler { session.influenceFor = null }
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    val links by remember(db) { db.people().observeLinks().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val today = Dates.today()
    val outside = people.filter { it.teamRole == null }

    // Stance per person: on this matter, or their general stance, or the most common one across matters
    val stanceOf: Map<Long, Stance?> = if (matterId != null) {
        links.filter { it.matterId == matterId }.associate { it.personId to Stance.of(it.stance) }
    } else {
        outside.associate { p ->
            p.id to (Stance.of(p.stance) ?: links.filter { it.personId == p.id }.mapNotNull { Stance.of(it.stance) }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key)
        }
    }
    val shown = outside.filter { it.id in stanceOf.keys || (matterId == null && it.influence != null) }
    fun cell(p: Person): Cell? {
        val inf = p.influence ?: return null
        val st = stanceOf[p.id]
        return when {
            st == Stance.OPPONENT -> Cell.WATCH
            inf <= 1 -> Cell.INFORM
            st == Stance.ALLY -> Cell.RELY
            else -> Cell.PERSUADE
        }
    }
    val unknown = shown.filter { it.influence == null }
    val longAgo = if (matterId == null) outside.filter { (it.influence ?: 0) >= 2 && (it.lastContact == null || today - it.lastContact > 30) } else emptyList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(matterId?.let { id -> titles.firstOrNull { it.id == id }?.title } ?: stringResource(R.string.influence_map_all)) },
                navigationIcon = {
                    IconButton(onClick = { session.influenceFor = null }) {
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
            item(key = "hint") { Text(stringResource(R.string.influence_hint), style = MaterialTheme.typography.bodyMedium, color = Muted) }
            item(key = "grid") {
                val scheme = MaterialTheme.colorScheme
                val dark = androidx.compose.foundation.isSystemInDarkTheme()
                fun c(light: Long, darkV: Long) = Color(if (dark) darkV else light)
                val cells = listOf(
                    Triple(Cell.PERSUADE, R.string.cell_persuade, c(0xFFFAEEDA, 0xFF412402) to c(0xFF633806, 0xFFFAC775)),
                    Triple(Cell.RELY, R.string.cell_rely, c(0xFFE1F5EE, 0xFF04342C) to c(0xFF085041, 0xFF9FE1CB)),
                    Triple(Cell.WATCH, R.string.cell_watch, c(0xFFFAECE7, 0xFF4A1B0C) to c(0xFF712B13, 0xFFF5C4B3)),
                    Triple(Cell.INFORM, R.string.cell_inform, scheme.surfaceVariant to scheme.onSurfaceVariant),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.influence_axes), style = MaterialTheme.typography.labelSmall, color = Muted)
                    cells.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (cellKind, title, colors) ->
                                val members = shown.filter { cell(it) == cellKind }
                                Column(
                                    Modifier.weight(1f).heightIn(min = 120.dp).clip(RoundedCornerShape(12.dp)).background(colors.first).padding(10.dp),
                                ) {
                                    Text("${stringResource(title)} · ${members.size}", style = MaterialTheme.typography.labelLarge, color = colors.second)
                                    Spacer(Modifier.height(6.dp))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        members.forEach { p ->
                                            Text(
                                                (if (p.isGroup) "◎ " else "") + p.name,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = colors.second,
                                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(scheme.surface.copy(alpha = 0.6f))
                                                    .clickable { session.personDraft = PersonDraft(p, isNew = false) }
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (longAgo.isNotEmpty()) item(key = "long") {
                SectionCard(Sym.schedule, stringResource(R.string.long_no_talk), count = longAgo.size, attention = true) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        longAgo.forEach { p -> Text(p.name, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { session.personDraft = PersonDraft(p, isNew = false) }.padding(horizontal = 8.dp, vertical = 4.dp)) }
                    }
                }
            }
            if (unknown.isNotEmpty()) item(key = "unknown") {
                SectionCard(Sym.info, stringResource(R.string.influence_unknown), count = unknown.size) {
                    Text(stringResource(R.string.influence_unknown_hint), style = MaterialTheme.typography.labelMedium, color = Muted,
                        modifier = Modifier.padding(bottom = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        unknown.forEach { p -> Text(p.name, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { session.personDraft = PersonDraft(p, isNew = false) }.padding(horizontal = 8.dp, vertical = 4.dp)) }
                    }
                }
            }
        }
    }
}
