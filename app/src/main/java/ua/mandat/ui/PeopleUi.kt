package ua.mandat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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

@Composable
fun PeopleList(db: MandatDb, session: Session) {
    val peopleFlow = remember(db) { db.people().observeAll() }
    val delegationsFlow = remember(db) { db.delegations().observeOpen() }
    val people by peopleFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val open by delegationsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // The search text is not sensitive enough to matter, but stays out of saved state anyway
    var query by remember { mutableStateOf("") }
    val shown = people.filter {
        query.isBlank() || listOf(it.name, it.organization, it.jobTitle).any { f -> f.contains(query.trim(), ignoreCase = true) }
    }
    val team = shown.filter { it.teamRole != null }
    val others = shown.filter { it.teamRole == null }
    val openBy = open.groupBy { it.personId }

    if (people.isEmpty()) {
        Column(Modifier.padding(Space.screen)) { EmptyHint(Icons.Filled.Person, stringResource(R.string.people_empty)) }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 96.dp),
    ) {
        item(key = "search") {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text(stringResource(R.string.search)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        if (team.isNotEmpty()) {
            item(key = "team_h") { GroupTitle(stringResource(R.string.team)) }
            items(team, key = { "t${it.id}" }) { p ->
                val mine = openBy[p.id].orEmpty()
                PersonCard(p, mine.size) { session.personDraft = PersonDraft(p, isNew = false) }
            }
        }
        if (others.isNotEmpty()) {
            item(key = "others_h") { GroupTitle(stringResource(R.string.others)) }
            items(others, key = { "o${it.id}" }) { p ->
                val mine = openBy[p.id].orEmpty()
                PersonCard(p, mine.size) { session.personDraft = PersonDraft(p, isNew = false) }
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    SectionHeader(Icons.Filled.Person, text)
}

/** Plain facts only: who it is and how much is open. No scores, no ranking. */
@Composable
private fun PersonCard(p: Person, openCount: Int, onOpen: () -> Unit) {
    MCard(Modifier.padding(bottom = Space.gap), onClick = onOpen) {
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
            OutlinedTextField(value = draft.notes, onValueChange = { draft.notes = it },
                label = { Text(stringResource(R.string.person_notes)) }, minLines = 3, modifier = Modifier.fillMaxWidth())

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
