package ua.mandat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import ua.mandat.R
import ua.mandat.data.Attention
import ua.mandat.data.FOCUS_LIMIT
import ua.mandat.data.MandatDb
import ua.mandat.data.Matter
import ua.mandat.data.MatterStatus
import ua.mandat.data.MatterType
import ua.mandat.data.Role

/** What the matter editor should open: a matter, and the Inbox note it came from, if any. */
data class EditRequest(val matter: Matter, val isNew: Boolean, val fromInboxId: Long? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MattersList(db: MandatDb, onOpen: (Matter) -> Unit) {
    val rolesFlow = remember(db) { db.roles().observeAll() }
    val activeFlow = remember(db) { db.matters().observeActive() }
    val closedFlow = remember(db) { db.matters().observeClosed() }
    val roles by rolesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val active by activeFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val closed by closedFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var roleFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    var showClosed by rememberSaveable { mutableStateOf(false) }

    val roleNames = roles.associate { it.id to it.name }
    fun visible(m: Matter) = roleFilter == null || m.roleId == roleFilter

    Column(Modifier.fillMaxSize()) {
        if (roles.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = roleFilter == null,
                    onClick = { roleFilter = null },
                    label = { Text(stringResource(R.string.matters_all_roles)) },
                )
                roles.forEach { role ->
                    FilterChip(
                        selected = roleFilter == role.id,
                        onClick = { roleFilter = role.id },
                        label = { Text(role.name) },
                    )
                }
            }
        }
        if (active.isEmpty() && closed.isEmpty()) {
            Column(Modifier.padding(Space.screen)) { EmptyHint(Sym.folder, stringResource(R.string.matters_empty)) }
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 96.dp),
        ) {
            Attention.entries.forEach { level ->
                val group = active.filter { Attention.of(it.attention) == level && visible(it) }
                if (group.isNotEmpty()) {
                    item(key = "h_$level") { AttentionHeader(level) }
                    items(group, key = { "a${it.id}" }) { m -> MatterCard(m, roleNames[m.roleId], onOpen, Modifier.padding(bottom = Space.gap)) }
                }
            }
            val closedVisible = closed.filter { visible(it) }
            if (closedVisible.isNotEmpty()) {
                item(key = "closed") {
                    TextButton(onClick = { showClosed = !showClosed }) {
                        Text("${stringResource(R.string.matters_closed)} (${closedVisible.size})")
                    }
                }
                if (showClosed) items(closedVisible, key = { "c${it.id}" }) { m -> MatterCard(m, roleNames[m.roleId], onOpen, Modifier.padding(bottom = Space.gap)) }
            }
        }
    }
}

@Composable
private fun AttentionHeader(level: Attention) {
    val icon = when (level) {
        Attention.FOCUS -> Sym.target
        Attention.BACKGROUND -> Sym.layers
        Attention.SLEEPING -> Sym.bedtime
    }
    Column {
        SectionHeader(icon, stringResource(level.label()))
        Text(stringResource(level.hint()), style = MaterialTheme.typography.labelMedium, color = Muted,
            modifier = Modifier.padding(bottom = Space.gap).offset(y = (-4).dp))
    }
}

/** A matter as a card: type and role as small tags, the title, and the next step. */
@Composable
fun MatterCard(m: Matter, roleName: String?, onOpen: (Matter) -> Unit, modifier: Modifier = Modifier) {
    val type = MatterType.of(m.type)
    MCard(modifier, onClick = { onOpen(m) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(stringResource(type.label()), type.tint())
            if (roleName != null) NeutralTag(roleName)
        }
        Spacer(Modifier.height(8.dp))
        Text(m.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                m.nextStep.ifBlank { stringResource(R.string.matter_no_next_step) },
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MatterEditor(db: MandatDb, session: Session, draft: MatterDraft) {
    val request = draft.request
    // Back without saving discards the changes
    val onDone = { session.draft = null }
    BackHandler(onBack = onDone)
    val rolesFlow = remember(db) { db.roles().observeAll() }
    val focusFlow = remember(db) { db.matters().observeFocus() }
    val roles by rolesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val focus by focusFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var confirmDelete by remember { mutableStateOf(false) }

    val otherFocus = focus.count { it.id != request.matter.id }

    fun save() = session.saveDraft()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (request.isNew) R.string.matter_new else R.string.matter_edit)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = ::save, enabled = draft.title.isNotBlank()) { Text(stringResource(R.string.save)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = draft.title, onValueChange = { draft.title = it },
                label = { Text(stringResource(R.string.matter_title)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Picker(
                label = stringResource(R.string.matter_type),
                current = stringResource(draft.type.label()),
                options = MatterType.entries.map { it to stringResource(it.label()) },
                onPick = { draft.type = it },
            )
            Picker(
                label = stringResource(R.string.matter_role),
                current = roles.firstOrNull { it.id == draft.roleId }?.name ?: stringResource(R.string.matter_no_role),
                options = listOf<Pair<Role?, String>>(null to stringResource(R.string.matter_no_role)) +
                    roles.map { it to it.name },
                onPick = { draft.roleId = it?.id },
            )
            Text(stringResource(R.string.matter_attention), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Attention.entries.forEach { level ->
                    FilterChip(
                        selected = draft.attention == level,
                        onClick = { draft.attention = level },
                        label = { Text(stringResource(level.label())) },
                    )
                }
            }
            if (draft.attention == Attention.FOCUS && otherFocus >= FOCUS_LIMIT) {
                Text(
                    pluralStringResource(R.plurals.focus_too_many, otherFocus, otherFocus, FOCUS_LIMIT),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedTextField(
                value = draft.nextStep, onValueChange = { draft.nextStep = it },
                label = { Text(stringResource(R.string.matter_next_step)) },
                placeholder = { Text(stringResource(R.string.matter_next_step_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.goal, onValueChange = { draft.goal = it },
                label = { Text(stringResource(R.string.matter_goal)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.description, onValueChange = { draft.description = it },
                label = { Text(stringResource(R.string.matter_description)) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            if (request.isNew) {
                Text(stringResource(R.string.save_matter_first), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary)
            } else {
                MatterLinks(db, session, request.matter.id)
            }
            if (!request.isNew) {
                Picker(
                    label = stringResource(R.string.matter_status),
                    current = stringResource(draft.status.label()),
                    options = MatterStatus.entries.map { it to stringResource(it.label()) },
                    onPick = { draft.status = it },
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.matter_delete)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.matter_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val id = request.matter.id
                    session.draft = null
                    session.write { db ->
                        // Deleting a matter keeps its delegations and promises, just without the matter
                        db.withTransaction {
                            db.people().unlinkMatter(id)
                            db.delegations().clearMatter(id)
                            db.promises().clearMatter(id)
                            db.deadlines().clearMatter(id)
                            db.matters().delete(id)
                        }
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A simple labelled choice: a button showing the current value, opening a list. */
@Composable
fun <T> Picker(label: String, current: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(current, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = {
                        open = false
                        onPick(value)
                    })
                }
            }
        }
    }
}

/** People in a matter (with their position on it), and its delegations and promises. */
@Composable
private fun MatterLinks(db: MandatDb, session: Session, matterId: Long) {
    val linkedFlow = remember(db, matterId) { db.people().observeForMatter(matterId) }
    val peopleFlow = remember(db) { db.people().observeAll() }
    val delegationsFlow = remember(db, matterId) { db.delegations().observeForMatter(matterId) }
    val promisesFlow = remember(db, matterId) { db.promises().observeForMatter(matterId) }
    val linked by linkedFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val people by peopleFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val delegations by delegationsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val promises by promisesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val nameOf = people.associate { it.id to it.name }
    var adding by remember { mutableStateOf(false) }
    var stanceFor by remember { mutableStateOf<ua.mandat.data.MatterPersonRow?>(null) }

    LinkSection(stringResource(R.string.people_in_matter))
    linked.forEach { row ->
        Row(Modifier.fillMaxWidth().clickable { stanceFor = row }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    ua.mandat.data.Stance.of(row.stance)?.let { stringResource(it.label()) } ?: stringResource(R.string.stance_none),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
                )
            }
            IconButton(onClick = { session.write { it.people().unlink(matterId, row.personId) } }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.delete))
            }
        }
    }
    TextButton(onClick = { adding = true }) { Text("+ " + stringResource(R.string.add_person)) }

    val deadlinesFlow = remember(db, matterId) { db.deadlines().observeForMatter(matterId) }
    val deadlines by deadlinesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    LinkSection(stringResource(R.string.deadlines))
    deadlines.forEach { DeadlineRow(it, null, session) }
    TextButton(onClick = { session.deadlineDraft = newDeadline(matterId = matterId) }) { Text("+ " + stringResource(R.string.add_deadline)) }

    LinkSection(stringResource(R.string.delegations))
    delegations.forEach { DelegationRow(it, it.personId?.let(nameOf::get), null, session) }
    TextButton(onClick = { session.delegationDraft = newDelegation(matterId = matterId) }) { Text("+ " + stringResource(R.string.add_delegation)) }

    LinkSection(stringResource(R.string.promises))
    promises.forEach { PromiseRow(it, it.personId?.let(nameOf::get), null, session) }
    TextButton(onClick = { session.promiseDraft = newPromise(matterId = matterId) }) { Text("+ " + stringResource(R.string.add_promise)) }

    if (adding) {
        val already = linked.map { it.personId }.toSet()
        PersonSearchDialog(
            people = people.filter { it.id !in already },
            onPick = { p ->
                adding = false
                session.write { it.people().link(ua.mandat.data.MatterPerson(matterId, p.id, stance = null, note = "")) }
            },
            onCancel = { adding = false },
        )
    }
    stanceFor?.let { row ->
        AlertDialog(
            onDismissRequest = { stanceFor = null },
            title = { Text(row.name) },
            text = {
                Column {
                    Text(stringResource(R.string.stance), style = MaterialTheme.typography.labelLarge)
                    (listOf<ua.mandat.data.Stance?>(null) + ua.mandat.data.Stance.entries).forEach { st ->
                        TextButton(onClick = {
                            stanceFor = null
                            session.write { it.people().setStance(matterId, row.personId, st?.name) }
                        }) { Text(st?.let { stringResource(it.label()) } ?: stringResource(R.string.stance_none)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { stanceFor = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
