package ua.mandat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import ua.mandat.R
import ua.mandat.data.MandatDb
import ua.mandat.data.Person
import ua.mandat.data.Stance
import ua.mandat.data.TaskStatus
import ua.mandat.data.TeamRole
import ua.mandat.data.isOverdue

/**
 * People: a ring of who the owner works with (by category or by team role), bars of how
 * delegations move and where people stand on matters, then the list in two cards.
 * Plain facts only; nobody is scored or ranked.
 */
@Composable
fun PeopleList(db: MandatDb, session: Session) {
    val people by remember(db) { db.people().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val delegations by remember(db) { db.delegations().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val links by remember(db) { db.people().observeLinks() }.collectAsStateWithLifecycle(emptyList())
    var query by remember { mutableStateOf("") }
    var byTeam by rememberSaveable { mutableStateOf(false) }
    var pick by rememberSaveable { mutableStateOf<String?>(null) }
    var stance by rememberSaveable { mutableStateOf<String?>(null) }
    val today = Dates.today()

    if (people.isEmpty()) {
        Column(Modifier.padding(Space.screen)) { EmptyHint(Sym.group, stringResource(R.string.people_empty)) }
        return
    }

    fun categoryKey(p: Person) = if (p.teamRole != null) "TEAM" else (p.category ?: "NONE")
    val notSet = stringResource(R.string.not_set)
    val teamLabel = stringResource(R.string.team)
    val categorySlices = buildList {
        add(Slice("TEAM", teamLabel, people.count { it.teamRole != null }, ChartColors.blue))
        ua.mandat.data.PersonCategory.entries.forEachIndexed { i, c ->
            add(Slice(c.name, stringResource(c.label()), people.count { it.teamRole == null && it.category == c.name }, SeriesColors[(i + 1) % SeriesColors.size]))
        }
        add(Slice("NONE", notSet, people.count { it.teamRole == null && it.category == null }, ChartColors.grayLight))
    }.sortedByDescending { it.value }
    val team = people.filter { it.teamRole != null }
    val teamSlices = TeamRole.entries.mapIndexed { i, r ->
        Slice(r.name, stringResource(r.label()), team.count { it.teamRole == r.name }, SeriesColors[i % SeriesColors.size])
    }.sortedByDescending { it.value }

    val stanceOf = links.groupBy { it.personId }.mapValues { (_, l) -> l.mapNotNull { it.stance }.toSet() }
    val shown = people.filter { p ->
        (query.isBlank() || listOf(p.name, p.organization, p.jobTitle).any { f -> f.contains(query.trim(), ignoreCase = true) }) &&
            (pick == null || (if (byTeam) p.teamRole == pick else categoryKey(p) == pick)) &&
            (stance == null || stanceOf[p.id]?.contains(stance) == true)
    }
    val openBy = delegations.filter { ua.mandat.data.TaskStatus.of(it.status) == ua.mandat.data.TaskStatus.OPEN }.groupBy { it.personId }

    val openD = delegations.filter { ua.mandat.data.TaskStatus.of(it.status) == ua.mandat.data.TaskStatus.OPEN }
    val overdue = openD.count { ua.mandat.data.isOverdue(it.due, it.status, today) }
    val done30 = delegations.count {
        ua.mandat.data.TaskStatus.of(it.status) == ua.mandat.data.TaskStatus.DONE && it.closedAt != null && Dates.dayOf(it.closedAt) > today - 30
    }

    LazyColumn(
        contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Space.gap),
    ) {
        item(key = "chart") {
            MCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    FilterChip(selected = !byTeam, onClick = { byTeam = false; pick = null }, label = { Text(stringResource(R.string.person_category)) })
                    FilterChip(selected = byTeam, onClick = { byTeam = true; pick = null }, label = { Text(teamLabel) })
                }
                DonutChart(
                    slices = if (byTeam) teamSlices else categorySlices,
                    center = (if (byTeam) team.size else people.size).toString(),
                    centerLabel = stringResource(R.string.people_count),
                    selected = pick, onPick = { pick = it },
                )
            }
        }
        item(key = "map") {
            OutlinedButton(onClick = { session.influenceFor = 0L }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.influence_map_all))
            }
        }
        if (people.any { it.teamRole != null }) item(key = "team") { TeamCard(people, delegations, session) }
        if (openD.isNotEmpty() || done30 > 0) {
            item(key = "delegations") {
                SectionCard(Sym.checkCircle, stringResource(R.string.delegations)) {
                    Spacer(Modifier.height(4.dp))
                    StackBar(
                        listOf(
                            Slice("ontime", stringResource(R.string.on_time), openD.size - overdue, ChartColors.blue),
                            Slice("overdue", stringResource(R.string.stat_overdue), overdue, MaterialTheme.colorScheme.error),
                            Slice("done", stringResource(R.string.strip_done_30), done30, ChartColors.blueLight),
                        ),
                    )
                }
            }
        }
        if (links.any { it.stance != null }) {
            item(key = "stances") {
                SectionCard(Sym.group, stringResource(R.string.stances_title)) {
                    Spacer(Modifier.height(4.dp))
                    StackBar(
                        listOf(
                            Slice("ALLY", stringResource(R.string.strip_allies), links.count { it.stance == "ALLY" }, ChartColors.teal),
                            Slice("UNDECIDED", stringResource(R.string.strip_undecided), links.count { it.stance == "UNDECIDED" }, ChartColors.amberLight),
                            Slice("OPPONENT", stringResource(R.string.strip_opponents), links.count { it.stance == "OPPONENT" }, ChartColors.salmon),
                            Slice("NEUTRAL", stringResource(R.string.stance_neutral_many), links.count { it.stance == "NEUTRAL" }, ChartColors.grayLight),
                        ),
                        selected = stance, onPick = { stance = it },
                    )
                }
            }
        }
        item(key = "search") {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text(stringResource(R.string.search)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (pick != null || stance != null) {
            item(key = "filters") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pick?.let { k ->
                        val slices = if (byTeam) teamSlices else categorySlices
                        FilterBadge(slices.firstOrNull { it.key == k }?.label ?: k) { pick = null }
                    }
                    stance?.let { st ->
                        val label = when (st) {
                            "ALLY" -> R.string.strip_allies
                            "UNDECIDED" -> R.string.strip_undecided
                            "OPPONENT" -> R.string.strip_opponents
                            else -> R.string.stance_neutral_many
                        }
                        FilterBadge(stringResource(label)) { stance = null }
                    }
                }
            }
        }
        // Team first, then one card per kind of person, largest groups first
        val groups = listOf(teamLabel to shown.filter { it.teamRole != null }) +
            categorySlices.filter { it.key != "TEAM" }.map { sl -> sl.label to shown.filter { it.teamRole == null && categoryKey(it) == sl.key } }
        groups.forEach { (title, list) ->
            if (list.isNotEmpty()) item(key = "group_$title") {
                SectionCard(Sym.group, title, count = list.size) {
                    list.forEachIndexed { i, p ->
                        if (i > 0) RowDivider()
                        PersonLine(p, openBy[p.id]?.size ?: 0) { session.personDraft = PersonDraft(p, isNew = false) }
                    }
                }
            }
        }
    }
}

/** Plain facts only: who it is and how much is open. No scores, no ranking. */
@Composable
private fun PersonLine(p: Person, openCount: Int, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onOpen).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Initials(p.name, team = p.teamRole != null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium)
                val role = TeamRole.of(p.teamRole)?.let { if (it == TeamRole.OTHER && p.teamCustom.isNotBlank()) p.teamCustom else stringResource(it.label()) }
                val who = ua.mandat.data.PersonCategory.of(p.category)?.let {
                    if (it == ua.mandat.data.PersonCategory.OTHER && p.categoryCustom.isNotBlank()) p.categoryCustom else stringResource(it.label())
                }
                val meta = listOfNotNull(role ?: who, p.jobTitle.ifBlank { null }, p.organization.ifBlank { null }).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelMedium, color = Muted, maxLines = 2)
            }
            if (openCount > 0) NeutralTag(stringResource(R.string.open_count, openCount))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PersonEditor(db: MandatDb, session: Session, draft: PersonDraft) {
    val onBack = { session.personDraft = null }
    BackHandler(onBack = onBack)
    var confirmDelete by remember { mutableStateOf(false) }
    val id = draft.person.id
    val allPeople by remember(db) { db.people().observeAll() }.collectAsStateWithLifecycle(emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (draft.isNew) R.string.person_new else R.string.person_edit)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = { session.savePersonDraft() }, enabled = draft.name.isNotBlank()) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(value = draft.name, onValueChange = { draft.name = it },
                label = { Text(stringResource(R.string.person_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = draft.jobTitle, onValueChange = { draft.jobTitle = it },
                label = { Text(stringResource(R.string.person_job)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = draft.organization, onValueChange = { draft.organization = it },
                label = { Text(stringResource(R.string.person_org)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = draft.contact, onValueChange = { draft.contact = it },
                label = { Text(stringResource(R.string.person_contact)) }, modifier = Modifier.fillMaxWidth())
            Picker(
                label = stringResource(R.string.person_category),
                current = draft.category?.let { stringResource(it.label()) } ?: stringResource(R.string.not_set),
                options = listOf<Pair<ua.mandat.data.PersonCategory?, String>>(null to stringResource(R.string.not_set)) +
                    ua.mandat.data.PersonCategory.entries.map { it to stringResource(it.label()) },
                onPick = { draft.category = it },
            )
            if (draft.category == ua.mandat.data.PersonCategory.OTHER) {
                OutlinedTextField(value = draft.categoryCustom, onValueChange = { draft.categoryCustom = it },
                    label = { Text(stringResource(R.string.own_words)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Picker(
                label = stringResource(R.string.person_team),
                current = draft.teamRole?.let { stringResource(it.label()) } ?: stringResource(R.string.not_team),
                options = listOf<Pair<TeamRole?, String>>(null to stringResource(R.string.not_team)) +
                    TeamRole.entries.map { it to stringResource(it.label()) },
                onPick = { draft.teamRole = it },
            )
            if (draft.teamRole == TeamRole.OTHER) {
                OutlinedTextField(value = draft.teamCustom, onValueChange = { draft.teamCustom = it },
                    label = { Text(stringResource(R.string.own_words)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (draft.teamRole != null) {
                val team = allPeople.filter { it.teamRole != null && it.id != id }
                val me = stringResource(R.string.reports_to_me)
                Picker(
                    label = stringResource(R.string.reports_to),
                    current = team.firstOrNull { it.id == draft.reportsToId }?.name ?: me,
                    options = listOf<Pair<Long?, String>>(null to me) + team.map { it.id to it.name },
                    onPick = { draft.reportsToId = it },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.person_is_group), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.person_is_group_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                    androidx.compose.material3.Switch(checked = draft.isGroup, onCheckedChange = { draft.isGroup = it })
                }
                // Influence and stance: for the influence map; never for the team (no rating of people)
                Text(stringResource(R.string.influence), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 3).forEach { lv ->
                        FilterChip(selected = draft.influence == lv, onClick = { draft.influence = if (draft.influence == lv) null else lv },
                            label = { Text(stringResource(influenceLabel(lv))) })
                    }
                }
                Text(stringResource(R.string.general_stance), style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ua.mandat.data.Stance.entries.forEach { st ->
                        FilterChip(selected = draft.stance == st, onClick = { draft.stance = if (draft.stance == st) null else st },
                            label = { Text(stringResource(st.label())) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { DueField(draft.lastContact, label = R.string.last_contact) { draft.lastContact = it } }
                TextButton(onClick = { draft.lastContact = Dates.today() }) { Text(stringResource(R.string.talked_today)) }
            }
            OutlinedTextField(value = draft.notes, onValueChange = { draft.notes = it },
                label = { Text(stringResource(R.string.person_notes)) }, minLines = 3, modifier = Modifier.fillMaxWidth())

            if (!draft.isNew && draft.teamRole != null) {
                OutlinedButton(onClick = { session.savePersonDraft(); session.oneOnOne = id }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.one_on_one))
                }
            }
            if (!draft.isNew) {
                PersonLinks(db, session, id)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.person_delete)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.person_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    session.personDraft = null
                    session.write { db ->
                        db.withTransaction {
                            db.people().unlinkPerson(id)
                            db.people().clearReportsTo(id)
                            db.delegations().clearPerson(id)
                            db.promises().clearPerson(id)
                            db.people().delete(id)
                        }
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A person's matters, delegations and promises. */
@Composable
private fun PersonLinks(db: MandatDb, session: Session, personId: Long) {
    val mattersFlow = remember(db, personId) { db.people().observeMattersOf(personId) }
    val delegationsFlow = remember(db, personId) { db.delegations().observeForPerson(personId) }
    val promisesFlow = remember(db, personId) { db.promises().observeForPerson(personId) }
    val titlesFlow = remember(db) { db.matters().observeAllTitles() }
    val matters by mattersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val delegations by delegationsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val promises by promisesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val titles by titlesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val titleOf = titles.associate { it.id to it.title }
    var showClosed by rememberSaveable { mutableStateOf(false) }

    LinkSection(stringResource(R.string.matters_of_person))
    matters.forEach { m ->
        Column(Modifier.fillMaxWidth().clickable { session.openMatter(m.matterId) }.padding(vertical = 6.dp)) {
            Text(m.title, style = MaterialTheme.typography.bodyLarge)
            Stance.of(m.stance)?.let { Text(stringResource(it.label()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary) }
        }
    }

    LinkSection(stringResource(R.string.delegations))
    val openD = delegations.filter { TaskStatus.of(it.status) == TaskStatus.OPEN }
    val closedD = delegations - openD.toSet()
    openD.forEach { DelegationRow(it, null, it.matterId?.let(titleOf::get), session) }
    TextButton(onClick = { session.delegationDraft = newDelegation(personId = personId) }) { Text("+ " + stringResource(R.string.add_delegation)) }

    LinkSection(stringResource(R.string.promises))
    val openP = promises.filter { TaskStatus.of(it.status) == TaskStatus.OPEN }
    val closedP = promises - openP.toSet()
    openP.forEach { PromiseRow(it, null, it.matterId?.let(titleOf::get), session) }
    TextButton(onClick = { session.promiseDraft = newPromise(personId = personId) }) { Text("+ " + stringResource(R.string.add_promise)) }

    if (closedD.isNotEmpty() || closedP.isNotEmpty()) {
        TextButton(onClick = { showClosed = !showClosed }) {
            Text("${stringResource(R.string.matters_closed)} (${closedD.size + closedP.size})")
        }
        if (showClosed) {
            closedD.forEach { DelegationRow(it, null, it.matterId?.let(titleOf::get), session) }
            closedP.forEach { PromiseRow(it, null, it.matterId?.let(titleOf::get), session) }
        }
    }
}

@Composable
fun LinkSection(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = Muted, modifier = Modifier.padding(top = 16.dp))
}
