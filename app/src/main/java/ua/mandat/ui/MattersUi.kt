@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
fun MattersList(db: MandatDb, onOpen: (Matter) -> Unit, onTopics: () -> Unit = {}, session: Session) {
    val roles by remember(db) { db.roles().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val active by remember(db) { db.matters().observeActive().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val closed by remember(db) { db.matters().observeClosed().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    // 0 = by type, 1 = by role, 2 = by topic
    var mode by rememberSaveable { mutableStateOf(0) }
    val byRole = mode == 1
    val topics = rememberTopics(db)
    fun text(m: Matter) = "${m.title} ${m.goal} ${m.nextStep} ${m.description}"
    var pick by rememberSaveable { mutableStateOf<String?>(null) }
    var level by rememberSaveable { mutableStateOf<String?>(null) }
    // Which matters: 0 active, 1 completed, 2 closed without completing
    var statusView by rememberSaveable { mutableStateOf(0) }
    val today = Dates.today()
    // Opened from another screen with a filter (for example a role from the dashboard)
    androidx.compose.runtime.LaunchedEffect(session.mattersRequest) {
        session.mattersRequest?.let { (m, key) -> mode = m; pick = key; level = null }
        session.mattersRequest = null
    }

    val roleNames = roles.associate { it.id to it.name }
    val noRole = stringResource(R.string.dash_no_role)
    fun roleKey(m: Matter) = m.roleId?.toString() ?: "none"
    fun visible(m: Matter): Boolean {
        val slice = pick == null || when (mode) {
            1 -> roleKey(m) == pick
            2 -> topics.firstOrNull { it.name == pick }?.let { ua.mandat.data.matches(text(m), it) } ?: true
            else -> m.type == pick
        }
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
    val done = closed.filter { MatterStatus.of(it.status) == MatterStatus.DONE }
    val dropped = closed.filter { MatterStatus.of(it.status) == MatterStatus.DROPPED }
    LazyColumn(
        contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Space.gap),
    ) {
        // Every matter is reachable: active ones, the completed and those closed without completing
        item(key = "status") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickChip(stringResource(R.string.matters_active_n, active.size), statusView == 0) { statusView = 0 }
                QuickChip(stringResource(R.string.matters_done_n, done.size), statusView == 1) { statusView = 1 }
                QuickChip(stringResource(R.string.matters_dropped_n, dropped.size), statusView == 2) { statusView = 2 }
            }
        }
        if (statusView != 0) {
            val list = (if (statusView == 1) done else dropped).sortedByDescending { it.updatedAt }
            item(key = "closed_list") {
                SectionCard(Sym.checkCircle, stringResource(if (statusView == 1) R.string.matters_done else R.string.matters_dropped), count = list.size) {
                    if (list.isEmpty()) EmptyHint(Sym.checkCircle, stringResource(R.string.smart_empty))
                    Paged(list) { m ->
                        Column {
                            MatterLine(m, roleNames[m.roleId]) { onOpen(m) }
                            // When it ended and how long it took
                            Text(stringResource(R.string.matter_closed_line, Dates.format(Dates.dayOf(m.updatedAt)),
                                (Dates.dayOf(m.updatedAt) - Dates.dayOf(m.createdAt)).coerceAtLeast(0).toInt()),
                                style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
                        }
                    }
                }
            }
            return@LazyColumn
        }
        if (active.isNotEmpty()) {
            item(key = "district") { DistrictEntry(db, session) }
            item(key = "chart") {
                MCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        QuickChip(stringResource(R.string.by_type), mode == 0) { mode = 0; pick = null }
                        QuickChip(stringResource(R.string.by_role), mode == 1) { mode = 1; pick = null }
                        QuickChip(stringResource(R.string.by_topic), mode == 2) { mode = 2; pick = null }
                    }
                    if (mode == 2 && topics.isEmpty()) {
                        EmptyHint(Sym.layers, stringResource(R.string.topics_empty))
                    } else DonutChart(
                        slices = when (mode) {
                            1 -> roleSlices
                            2 -> topics.mapIndexed { i, t -> Slice(t.name, t.name, active.count { ua.mandat.data.matches(text(it), t) }, SeriesColors[i % SeriesColors.size]) }
                                .sortedByDescending { it.value }
                            else -> typeSlices
                        },
                        center = active.size.toString(), centerLabel = stringResource(R.string.strip_active),
                        selected = pick, onPick = { pick = it },
                    )
                    if (mode == 2) TextButton(onClick = onTopics) { Text(stringResource(R.string.topics_manage)) }
                }
            }
            item(key = "attention") {
                SectionCard(Sym.layers, stringResource(R.string.attention_title)) {
                    Spacer(Modifier.height(4.dp))
                    val totalActive = active.size.coerceAtLeast(1)
                    StackBar(
                        Attention.entries.map { a ->
                            val n = active.count { it.attention == a.name }
                            // The share as well as the colour, so it reads without telling colours apart
                            Slice(a.name, stringResource(a.label()), n, a.color(), suffix = " · ${n * 100 / totalActive} %")
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
                        val label = when (mode) {
                            1 -> k.toLongOrNull()?.let(roleNames::get) ?: noRole
                            2 -> k
                            else -> stringResource(MatterType.of(k).label())
                        }
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
                SectionCard(lv.icon(), stringResource(lv.label()), count = group.size, attention = lv == Attention.FOCUS && group.size > FOCUS_LIMIT, tint = lv.color()) {
                    Text(stringResource(lv.hint()), style = MaterialTheme.typography.labelMedium, color = Muted,
                        modifier = Modifier.padding(bottom = 6.dp))
                    Paged(group) { m -> MatterLine(m, roleNames[m.roleId]) { onOpen(m) } }
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
    val focusFlow = remember(db) { db.matters().observeFocus().hiding(session) { it.id } }
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
                .padding(horizontal = Space.screen, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Space.gap + 4.dp),
        ) {
            if (!request.isNew) MatterStrip(db, request.matter, session)
            if (!request.isNew) MatterTime(db, request.matter, session)
            SheetGroup {
            OutlinedTextField(
                value = draft.title, onValueChange = { draft.title = it },
                label = { Text(stringResource(R.string.matter_title)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Picker(
                label = stringResource(R.string.matter_type),
                current = stringResource(draft.type.label()),
                options = matterTypesForMode().map { it to stringResource(it.label()) },
                onPick = { draft.type = it },
            )
            Picker(
                label = stringResource(R.string.matter_role),
                current = roles.firstOrNull { it.id == draft.roleId }?.name ?: stringResource(R.string.matter_no_role),
                options = listOf<Pair<Role?, String>>(null to stringResource(R.string.matter_no_role)) +
                    roles.map { it to it.name },
                onPick = { draft.roleId = it?.id },
            )
            }
            SheetGroup(stringResource(R.string.matter_attention)) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Attention.entries.forEach { level ->
                    QuickChip(stringResource(level.label()), draft.attention == level) { draft.attention = level }
                }
            }
            if (draft.attention == Attention.FOCUS && otherFocus >= FOCUS_LIMIT) {
                Text(
                    pluralStringResource(R.plurals.focus_too_many, otherFocus, otherFocus, FOCUS_LIMIT),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            }
            SheetGroup(stringResource(R.string.matter_group_work)) {
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { draft.sensitive = !draft.sensitive }) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.matter_sensitive), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.matter_sensitive_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                }
                androidx.compose.material3.Switch(checked = draft.sensitive, onCheckedChange = { draft.sensitive = it })
            }
            }
            if (request.isNew) {
                Text(stringResource(R.string.save_matter_first), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary)
            } else {
                // A fundraising matter keeps its own ledger, apart from the personal budget
                if (draft.type == MatterType.AID) SheetGroup { AidLedger(db, session, request.matter.id) }
                SheetGroup { MatterLinks(db, session, request.matter.id, draft) }
            }
            if (!request.isNew) {
                SheetGroup {
                Picker(
                    label = stringResource(R.string.matter_status),
                    current = stringResource(draft.status.label()),
                    options = MatterStatus.entries.map { it to stringResource(it.label()) },
                    onPick = { draft.status = it },
                )
                }
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.matter_delete), color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        val aidRecords by remember(db, request.matter.id) { db.money().observeAidFor(request.matter.id).hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = {
                Column {
                    Text(stringResource(R.string.matter_delete_confirm))
                    // The aid ledger belongs to the matter and goes with it: say so plainly
                    if (aidRecords.isNotEmpty()) Text(stringResource(R.string.matter_delete_aid, aidRecords.size),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            },
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
                            db.events().clearMatter(id)
                            db.info().clearMatter(id)
                            db.info().clearMessageMatter(id)
                            db.info().clearAllianceMatter(id)
                            db.money().clearMatter(id)
                            db.money().deleteAidFor(id)
                            db.reviews().unlinkRef("matter", id)
                            db.communities().unlinkRef("matter", id)
                            db.time().clearMatter(id)
                            db.history().deleteFor(id)
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
    // A few options: all in view as buttons. Many: one field that opens a list with search.
    if (options.size <= 8 && options.all { it.second.length <= 24 }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (value, text) -> QuickChip(text, on = text == current) { onPick(value) } }
            }
        }
    } else {
        var open by remember { mutableStateOf(false) }
        FieldTile(label, current) { open = true }
        if (open) PickSheet(label, options.mapIndexed { i, (_, text) -> PickItem(i, text) }, onPick = { i -> open = false; onPick(options[i].first) },
            onDismiss = { open = false }, current = options.indexOfFirst { it.second == current }.takeIf { it >= 0 })
    }
}

/** People in a matter (with their position on it), and its delegations and promises. */
@Composable
private fun MatterLinks(db: MandatDb, session: Session, matterId: Long, draft: MatterDraft) {
    val linkedFlow = remember(db, matterId) { db.people().observeForMatter(matterId) }
    val peopleFlow = remember(db) { db.people().observeAll() }
    val delegationsFlow = remember(db, matterId) { db.delegations().observeForMatter(matterId).hiding(session) { it.matterId } }
    val promisesFlow = remember(db, matterId) { db.promises().observeForMatter(matterId).hiding(session) { it.matterId } }
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
                    listOfNotNull(
                        ua.mandat.data.Raci.of(row.raci)?.let { stringResource(it.label()) },
                        ua.mandat.data.Stance.of(row.stance)?.let { stringResource(it.label()) } ?: stringResource(R.string.stance_none),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
                )
            }
            IconButton(onClick = { session.write { it.people().unlink(matterId, row.personId) } }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.delete))
            }
        }
    }
    Row {
        TextButton(onClick = { adding = true }) { Text("+ " + stringResource(R.string.add_person)) }
        if (linked.isNotEmpty()) TextButton(onClick = { session.saveDraft(); session.influenceFor = matterId }) {
            Text(stringResource(R.string.influence_map))
        }
    }
    if (linked.any { it.stance != null } || draft.votesNeeded != null) VoteCounter(linked, draft, session)

    val deadlinesFlow = remember(db, matterId) { db.deadlines().observeForMatter(matterId).hiding(session) { it.matterId } }
    val deadlines by deadlinesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    LinkSection(stringResource(R.string.deadlines))
    deadlines.forEach { DeadlineRow(it, null, session) }
    TextButton(onClick = { session.deadlineDraft = newDeadline(matterId = matterId) }) { Text("+ " + stringResource(R.string.add_deadline)) }

    EventLinks(db, session, db.events().observeForMatter(matterId).hiding(session) { it.matterId }, matterId = matterId)
    MatterInfoLinks(db, session, matterId)

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
        // Where this person stands and what they do here; each tap is saved at once
        var stance by remember(row) { mutableStateOf(ua.mandat.data.Stance.of(row.stance)) }
        var raci by remember(row) { mutableStateOf(ua.mandat.data.Raci.of(row.raci)) }
        EditSheet(title = row.name, onDismiss = { stanceFor = null }, onSave = null) {
            SheetGroup(stringResource(R.string.stance)) {
                ChoiceTiles(listOf<ua.mandat.data.Stance?>(null) + ua.mandat.data.Stance.entries, stance,
                    label = { st -> st?.let { stringResource(it.label()) } ?: stringResource(R.string.stance_none) },
                    onPick = { st ->
                        stance = st
                        session.write { it.people().setStance(matterId, row.personId, st?.name, Dates.today()) }
                    },
                    columns = 3,
                    mark = { st -> when (st) {
                        ua.mandat.data.Stance.ALLY -> ChartColors.teal
                        ua.mandat.data.Stance.UNDECIDED -> ChartColors.amberLight
                        ua.mandat.data.Stance.OPPONENT -> ChartColors.salmon
                        ua.mandat.data.Stance.NEUTRAL -> ChartColors.grayLight
                        null -> null
                    } },
                )
            }
            // Who does what (RACI); only one person leads
            SheetGroup(stringResource(R.string.raci_title)) {
                ChoiceTiles(listOf<ua.mandat.data.Raci?>(null) + ua.mandat.data.Raci.entries, raci,
                    label = { r -> r?.let { stringResource(it.label()) } ?: stringResource(R.string.raci_none) },
                    onPick = { r ->
                        raci = r
                        session.write { db ->
                            db.withTransaction {
                                if (r == ua.mandat.data.Raci.LEAD) db.people().clearLead(matterId)
                                db.people().setRaci(matterId, row.personId, r?.name)
                            }
                        }
                    },
                    columns = 3,
                )
            }
            TextButton(onClick = { stanceFor = null; session.saveDraft(); session.oneOnOne = row.personId }) {
                Text(stringResource(R.string.brief_title) + " →")
            }
        }
    }
}

/**
 * The top of a matter, as a small dashboard: how long in work, the nearest deadline, where
 * people stand, how its delegations go, and its topics (each topic opens everything about it).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MatterStrip(db: MandatDb, matter: Matter, session: Session) {
    val linked by remember(db, matter.id) { db.people().observeForMatter(matter.id) }.collectAsStateWithLifecycle(emptyList())
    val deadlines by remember(db, matter.id) { db.deadlines().observeForMatter(matter.id).hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val delegations by remember(db, matter.id) { db.delegations().observeForMatter(matter.id).hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val topics = rememberTopics(db)
    val today = Dates.today()
    val open = ua.mandat.data.TaskStatus.OPEN
    val text = "${matter.title} ${matter.goal} ${matter.nextStep} ${matter.description}"
    val mine = topics.filter { ua.mandat.data.matches(text, it) }
    val titleOfNone: String? = null
    var stancePick by remember { mutableStateOf<String?>(null) }
    var delegPick by remember { mutableStateOf<String?>(null) }
    MCard {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (MatterStatus.of(matter.status) == MatterStatus.ACTIVE) {
                MiniChip(stringResource(R.string.days_in_work, (today - Dates.dayOf(matter.createdAt)).coerceAtLeast(0).toInt()))
            }
            deadlines.filter { ua.mandat.data.TaskStatus.of(it.status) == open }.minByOrNull { it.day }?.let { d ->
                MiniChip(stringResource(R.string.next_deadline_chip, Dates.short(d.day)),
                    if (d.day < today) MaterialTheme.colorScheme.errorContainer else null,
                    if (d.day < today) MaterialTheme.colorScheme.onErrorContainer else null)
            }
            mine.forEach { t -> MiniChip("#" + t.name, onClick = { session.saveDraft(); session.showTopics = true; session.topicOpen = t.name }) }
        }
        val withStance = linked.filter { it.stance != null }
        if (withStance.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.stances_people), style = MaterialTheme.typography.labelMedium, color = Muted)
            Spacer(Modifier.height(4.dp))
            StackBar(listOf(
                Slice("a", stringResource(R.string.strip_allies), withStance.count { it.stance == "ALLY" }, ChartColors.teal),
                Slice("u", stringResource(R.string.strip_undecided), withStance.count { it.stance == "UNDECIDED" }, ChartColors.amberLight),
                Slice("o", stringResource(R.string.strip_opponents), withStance.count { it.stance == "OPPONENT" }, ChartColors.salmon),
                Slice("n", stringResource(R.string.stance_neutral_many), withStance.count { it.stance == "NEUTRAL" }, ChartColors.grayLight),
            ), selected = stancePick, onPick = { stancePick = it })
            // The people behind the chosen part, right under the bar; a name opens the brief
            stancePick?.let { k ->
                val key = mapOf("a" to "ALLY", "u" to "UNDECIDED", "o" to "OPPONENT", "n" to "NEUTRAL")[k]
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp)) {
                    withStance.filter { it.stance == key }.forEach { r -> MiniChip(r.name, onClick = { session.saveDraft(); session.oneOnOne = r.personId }) }
                }
            }
        }
        val openD = delegations.filter { ua.mandat.data.TaskStatus.of(it.status) == open }
        if (openD.isNotEmpty()) {
            val late = openD.count { ua.mandat.data.isOverdue(it.due, it.status, today) }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.delegations), style = MaterialTheme.typography.labelMedium, color = Muted)
            Spacer(Modifier.height(4.dp))
            StackBar(listOf(
                Slice("t", stringResource(R.string.on_time), openD.size - late, ChartColors.blue),
                Slice("l", stringResource(R.string.stat_overdue), late, MaterialTheme.colorScheme.error),
            ), selected = delegPick, onPick = { delegPick = it })
            delegPick?.let { k ->
                val names by remember(db) { db.people().observeAll() }.collectAsStateWithLifecycle(emptyList())
                val nameOf = names.associate { it.id to it.name }
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    openD.filter { (k == "l") == ua.mandat.data.isOverdue(it.due, it.status, today) }
                        .forEach { d -> DelegationRow(d, d.personId?.let(nameOf::get), titleOfNone, session) }
                }
            }
        }
    }
}

/**
 * A vote counter for a matter that is decided by a vote: how many are for against how many
 * are needed, who is still undecided, who is against, and who changed their stance lately.
 * A name opens the brief before talking to them.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun VoteCounter(linked: List<ua.mandat.data.MatterPersonRow>, draft: MatterDraft, session: Session) {
    val forThem = linked.filter { it.stance == "ALLY" }
    val undecided = linked.filter { it.stance == "UNDECIDED" }
    val against = linked.filter { it.stance == "OPPONENT" }
    val needed = draft.votesNeeded
    var editing by remember { mutableStateOf(false) }
    val today = Dates.today()
    LinkSection(stringResource(R.string.votes_title))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (needed != null) {
            val missing = (needed - forThem.size).coerceAtLeast(0)
            Text(
                if (missing == 0) stringResource(R.string.votes_enough, forThem.size, needed)
                else stringResource(R.string.votes_missing, forThem.size, needed, missing),
                style = MaterialTheme.typography.titleSmall,
                color = if (missing == 0) ChartColors.teal else MaterialTheme.colorScheme.onSurface,
            )
            // For, then undecided, against the number needed
            val total = maxOf(needed, forThem.size + undecided.size).coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (forThem.isNotEmpty()) Box(Modifier.weight(forThem.size.toFloat()).height(10.dp).background(ChartColors.teal))
                if (undecided.isNotEmpty()) Box(Modifier.weight(undecided.size.toFloat()).height(10.dp).background(ChartColors.amberLight))
                val rest = total - forThem.size - undecided.size
                if (rest > 0) Box(Modifier.weight(rest.toFloat()).height(10.dp))
            }
            if (missing > 0 && undecided.isNotEmpty()) Text(stringResource(R.string.votes_hint, undecided.size), style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        @Composable
        fun Group(title: String, rows: List<ua.mandat.data.MatterPersonRow>, color: androidx.compose.ui.graphics.Color) {
            if (rows.isEmpty()) return
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
                Spacer(Modifier.width(6.dp))
                Text("$title · ${rows.size}", style = MaterialTheme.typography.labelLarge)
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                rows.forEach { r -> MiniChip(r.name, onClick = { session.saveDraft(); session.oneOnOne = r.personId }) }
            }
        }
        Group(stringResource(R.string.strip_allies), forThem, ChartColors.teal)
        Group(stringResource(R.string.strip_undecided), undecided, ChartColors.amberLight)
        Group(stringResource(R.string.strip_opponents), against, ChartColors.salmon)
        // Who moved in the last 30 days
        val moved = linked.filter { it.stanceSince != null && it.stanceSince > today - 30 && it.previousStance != null }
        if (moved.isNotEmpty()) {
            Text(stringResource(R.string.votes_moved), style = MaterialTheme.typography.labelLarge)
            moved.sortedByDescending { it.stanceSince }.forEach { r ->
                val from = ua.mandat.data.Stance.of(r.previousStance)?.let { stringResource(it.label()) } ?: stringResource(R.string.stance_none)
                val to = ua.mandat.data.Stance.of(r.stance)?.let { stringResource(it.label()) } ?: stringResource(R.string.stance_none)
                Text("${r.name}: $from → $to, ${Dates.format(r.stanceSince!!)}", style = MaterialTheme.typography.labelMedium, color = Muted)
            }
        }
        TextButton(onClick = { editing = true }) {
            Text(stringResource(if (needed == null) R.string.votes_set else R.string.votes_change))
        }
    }
    if (editing) {
        var text by remember { mutableStateOf(needed?.toString().orEmpty()) }
        EditSheet(
            title = stringResource(R.string.votes_needed),
            onDismiss = { editing = false },
            onSave = { draft.votesNeeded = text.toIntOrNull()?.takeIf { it in 1..10_000 }; editing = false },
            saveText = stringResource(R.string.ok),
        ) {
            SheetGroup {

                    Text(stringResource(R.string.votes_needed_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                    OutlinedTextField(text, { v -> text = v.filter { it.isDigit() }.take(5) }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Time on this matter: calendar events linked to it count by themselves; work outside events is
 * added with one tap. Shown for the last 30 days.
 */
@Composable
private fun MatterTime(db: MandatDb, matter: Matter, session: Session) {
    val entries by remember(db, matter.id) { db.time().observeForMatter(matter.id) }.collectAsStateWithLifecycle(emptyList())
    val events by remember(db, matter.id) { db.events().observeForMatter(matter.id) }.collectAsStateWithLifecycle(emptyList())
    val today = Dates.today()
    val span = ua.mandat.data.Span(today - 29, today)
    val fromEvents = events.filter { it.day in span }.sumOf { ua.mandat.data.eventMinutes(it) }
    val byHand = entries.filter { it.day in span }.sumOf { it.minutes }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            stringResource(R.string.time_matter, hoursText(fromEvents + byHand)),
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
        )
        listOf(15, 30, 60).forEach { m ->
            TextButton(onClick = {
                session.write { it.time().insert(ua.mandat.data.TimeEntry(day = today, minutes = m, matterId = matter.id, roleId = matter.roleId, createdAt = System.currentTimeMillis())) }
            }) { Text("+$m") }
        }
    }
}

/** Minutes as "1 год 30 хв", "45 хв" or "0". */
@Composable
fun hoursText(minutes: Int): String = when {
    minutes <= 0 -> "0"
    minutes < 60 -> stringResource(R.string.minutes_short, minutes)
    minutes % 60 == 0 -> stringResource(R.string.hours_short, minutes / 60)
    else -> stringResource(R.string.hours_short, minutes / 60) + " " + stringResource(R.string.minutes_short, minutes % 60)
}
