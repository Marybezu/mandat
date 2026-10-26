package ua.mandat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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

/**
 * Matters: a ring of what the work is made of (by type or by role) and a bar of attention,
 * both tappable to filter, then the matters grouped by attention.
 */
@Composable
fun MattersList(db: MandatDb, onOpen: (Matter) -> Unit) {
    val roles by remember(db) { db.roles().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val active by remember(db) { db.matters().observeActive() }.collectAsStateWithLifecycle(emptyList())
    val closed by remember(db) { db.matters().observeClosed() }.collectAsStateWithLifecycle(emptyList())
    var byRole by rememberSaveable { mutableStateOf(false) }
    var pick by rememberSaveable { mutableStateOf<String?>(null) }
    var level by rememberSaveable { mutableStateOf<String?>(null) }
    var showClosed by rememberSaveable { mutableStateOf(false) }
    val today = Dates.today()

    val roleNames = roles.associate { it.id to it.name }
    val noRole = stringResource(R.string.dash_no_role)
    fun roleKey(m: Matter) = m.roleId?.toString() ?: "none"
    fun visible(m: Matter): Boolean {
        val slice = pick == null || (if (byRole) roleKey(m) else m.type) == pick
        val lv = when (level) {
            null -> true
            "STALE" -> ua.mandat.data.isStale(m, today)
            else -> m.attention == level
        }
        return slice && lv
    }

    val typeSlices = MatterType.entries.map { t ->
        Slice(t.name, stringResource(t.label()), active.count { it.type == t.name }, t.chartColor())
    }.sortedByDescending { it.value }
    val roleSlices = (roles.mapIndexed { i, r ->
        Slice(r.id.toString(), r.name, active.count { it.roleId == r.id }, SeriesColors[i % SeriesColors.size])
    } + Slice("none", noRole, active.count { it.roleId == null || it.roleId !in roleNames }, ChartColors.grayLight))
        .sortedByDescending { it.value }
    val stale = active.count { ua.mandat.data.isStale(it, today) }

    if (active.isEmpty() && closed.isEmpty()) {
        Column(Modifier.padding(Space.screen)) { EmptyHint(Sym.folder, stringResource(R.string.matters_empty)) }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Space.gap),
    ) {
        if (active.isNotEmpty()) {
            item(key = "chart") {
                MCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        FilterChip(selected = !byRole, onClick = { byRole = false; pick = null }, label = { Text(stringResource(R.string.by_type)) })
                        FilterChip(selected = byRole, onClick = { byRole = true; pick = null }, label = { Text(stringResource(R.string.by_role)) })
                    }
                    DonutChart(
                        slices = if (byRole) roleSlices else typeSlices,
                        center = active.size.toString(), centerLabel = stringResource(R.string.strip_active),
                        selected = pick, onPick = { pick = it },
                    )
                }
            }
            item(key = "attention") {
                SectionCard(Sym.layers, stringResource(R.string.attention_title)) {
                    Spacer(Modifier.height(4.dp))
                    StackBar(
                        Attention.entries.map { a ->
                            Slice(a.name, stringResource(a.label()), active.count { it.attention == a.name },
                                when (a) {
                                    Attention.FOCUS -> ChartColors.blue
                                    Attention.BACKGROUND -> ChartColors.blueLight
                                    Attention.SLEEPING -> ChartColors.grayLight
                                })
                        },
                        selected = level, onPick = { level = it },
                    )
                    if (stale > 0) {
                        TextButton(onClick = { level = if (level == "STALE") null else "STALE" }) {
                            Text(stringResource(R.string.strip_stale, ua.mandat.data.STALE_DAYS) + ": $stale",
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        if (pick != null || level != null) {
            item(key = "filters") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pick?.let { k ->
                        val label = if (byRole) (k.toLongOrNull()?.let(roleNames::get) ?: noRole) else stringResource(MatterType.of(k).label())
                        FilterBadge(label) { pick = null }
                    }
                    level?.let { l ->
                        val label = if (l == "STALE") stringResource(R.string.strip_stale, ua.mandat.data.STALE_DAYS)
                        else stringResource(Attention.of(l).label())
                        FilterBadge(label) { level = null }
                    }
                }
            }
        }
        Attention.entries.forEach { lv ->
            val group = active.filter { Attention.of(it.attention) == lv && visible(it) }
            if (group.isNotEmpty()) item(key = "g_$lv") {
                SectionCard(lv.icon(), stringResource(lv.label()), count = group.size, attention = lv == Attention.FOCUS && group.size > FOCUS_LIMIT) {
                    Text(stringResource(lv.hint()), style = MaterialTheme.typography.labelMedium, color = Muted,
                        modifier = Modifier.padding(bottom = 6.dp))
                    group.forEachIndexed { i, m -> if (i > 0) RowDivider(); MatterLine(m, roleNames[m.roleId]) { onOpen(m) } }
                }
            }
        }
        val closedVisible = if (level != null) emptyList() else closed.filter { visible(it) }
        if (closedVisible.isNotEmpty()) {
            item(key = "closed") {
                TextButton(onClick = { showClosed = !showClosed }) {
                    Text("${stringResource(R.string.matters_closed)} (${closedVisible.size})")
                }
            }
            if (showClosed) item(key = "closed_list") {
                SectionCard(Sym.checkCircle, stringResource(R.string.matters_closed), count = closedVisible.size) {
                    closedVisible.forEachIndexed { i, m -> if (i > 0) RowDivider(); MatterLine(m, roleNames[m.roleId]) { onOpen(m) } }
                }
            }
        }
    }
}

@Composable
private fun Attention.icon() = when (this) {
    Attention.FOCUS -> Sym.target
    Attention.BACKGROUND -> Sym.layers
    Attention.SLEEPING -> Sym.bedtime
}

@Composable
private fun AttentionHeader(level: Attention) {
    val icon = level.icon()
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

/** A compact matter row for lists inside a card: type colour, title, next step. */
@Composable
fun MatterLine(m: Matter, roleName: String?, onOpen: () -> Unit) {
    val type = MatterType.of(m.type)
    Row(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onOpen).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(type.tint().bg), contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape).background(type.chartColor()))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(m.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(stringResource(type.label()), roleName).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.labelMedium, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (m.nextStep.isNotBlank()) {
                Text("→ " + m.nextStep, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
            if (!request.isNew) MatterStrip(db, request.matter)
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

/** Facts about one matter: how long in work, the nearest deadline, who stands where. */
@Composable
private fun MatterStrip(db: MandatDb, matter: Matter) {
    val linked by remember(db, matter.id) { db.people().observeForMatter(matter.id) }.collectAsStateWithLifecycle(emptyList())
    val deadlines by remember(db, matter.id) { db.deadlines().observeForMatter(matter.id) }.collectAsStateWithLifecycle(emptyList())
    val today = Dates.today()
    val items = buildList {
        if (MatterStatus.of(matter.status) == MatterStatus.ACTIVE) {
            add(StripItem((today - Dates.dayOf(matter.createdAt)).coerceAtLeast(0).toString(), stringResource(R.string.strip_days_in_work)))
        }
        deadlines.filter { ua.mandat.data.TaskStatus.of(it.status) == ua.mandat.data.TaskStatus.OPEN }.minByOrNull { it.day }?.let { d ->
            add(StripItem(Dates.format(d.day), stringResource(R.string.strip_next_deadline), attention = d.day < today))
        }
        val stances = linked.mapNotNull { ua.mandat.data.Stance.of(it.stance) }.groupingBy { it }.eachCount()
        listOf(
            ua.mandat.data.Stance.ALLY to R.string.strip_allies,
            ua.mandat.data.Stance.UNDECIDED to R.string.strip_undecided,
            ua.mandat.data.Stance.OPPONENT to R.string.strip_opponents,
        ).forEach { (st, label) -> stances[st]?.let { n -> add(StripItem(n.toString(), stringResource(label))) } }
    }
    SummaryStrip("matter", items)
}
