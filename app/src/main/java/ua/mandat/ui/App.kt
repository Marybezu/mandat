package ua.mandat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import kotlinx.coroutines.flow.map
import ua.mandat.data.Matter
import ua.mandat.data.SETTING_ROLES_ASKED
import ua.mandat.data.matterFromNote
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.mandat.R
import ua.mandat.data.InboxItem
import ua.mandat.data.MandatDb
import ua.mandat.data.cleanInboxText

@Composable
fun App(session: Session, onUnlock: () -> Unit, onStartOver: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val state = session.state) {
            LockState.NeedsScreenLock -> Message(stringResource(R.string.no_screen_lock))
            LockState.KeyLost -> KeyLost(onStartOver)
            LockState.FirstRun -> Welcome(onUnlock)
            LockState.Locked -> Locked(session.lastError, onUnlock)
            LockState.Opening -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is LockState.Open -> PrivateKeyboard { Main(state.db, session) }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun Notice() {
    Spacer(Modifier.height(32.dp))
    Text(
        stringResource(R.string.classified_notice),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.secondary,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Message(text: String) = Centered {
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

@Composable
private fun KeyLost(onStartOver: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    Centered {
        Text(stringResource(R.string.key_lost), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        TextButton(onClick = { asking = true }) { Text(stringResource(R.string.start_over)) }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.start_over_title)) },
            text = { Text(stringResource(R.string.start_over_text)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    onStartOver()
                }) { Text(stringResource(R.string.start_over_confirm)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Welcome(onStart: () -> Unit) = Centered {
    Text(stringResource(R.string.first_run_title), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.first_run_text),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(32.dp))
    Button(onClick = onStart) { Text(stringResource(R.string.first_run_start)) }
    Notice()
}

@Composable
private fun Locked(failed: Boolean, onUnlock: () -> Unit) = Centered {
    Icon(Sym.lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(32.dp))
    Button(onClick = onUnlock) { Text(stringResource(R.string.unlock)) }
    if (failed) {
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.unlock_failed), color = MaterialTheme.colorScheme.error)
    }
    Notice()
}

// "Review" sits in the middle and stands out: the place to step back and see the whole picture
private enum class Tab(val title: Int, val icon: Int, val central: Boolean = false) {
    Today(R.string.tab_today, R.drawable.sym_home),
    Matters(R.string.tab_matters, R.drawable.sym_folder),
    Review(R.string.tab_review, R.drawable.sym_explore, central = true),
    People(R.string.tab_people, R.drawable.sym_group),
    Time(R.string.tab_time, R.drawable.sym_calendar_month),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Main(db: MandatDb, session: Session) {
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    var options by rememberSaveable { mutableStateOf(false) }

    val rolesAskedFlow = remember(db) { db.settings().observe(SETTING_ROLES_ASKED).map { it != null } }
    val rolesAsked by rolesAskedFlow.collectAsStateWithLifecycle(initialValue = null)

    when {
        rolesAsked == null -> return
        rolesAsked == false -> {
            RolesSetup(session)
            return
        }
    }
    // Task dialogs can open over any screen, including the editors
    session.delegationDraft?.let { DelegationDialog(db, session, it) }
    session.promiseDraft?.let { PromiseDialog(db, session, it) }
    session.deadlineDraft?.let { DeadlineDialog(db, session, it) }

    session.draft?.let { draft ->
        MatterEditor(db, session, draft)
        return
    }
    session.personDraft?.let { draft ->
        PersonEditor(db, session, draft)
        return
    }
    if (session.showPromises) {
        PromisesScreen(db, session)
        return
    }
    if (session.searching) {
        SearchScreen(db, session)
        return
    }
    if (options) {
        Options(session, onBack = { options = false })
        return
    }

    fun newMatter() {
        session.draft = MatterDraft(EditRequest(matterFromNote("", System.currentTimeMillis()), isNew = true))
    }

    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                ScreenHeader(
                    title = stringResource(tab.title),
                    subtitle = if (tab == Tab.Today) Dates.longToday() else null,
                ) {
                    IconButton(onClick = { session.searching = true }) {
                        Icon(Sym.search, contentDescription = stringResource(R.string.search), tint = Muted)
                    }
                    IconButton(onClick = { options = true }) {
                        Icon(Sym.settings, contentDescription = stringResource(R.string.options), tint = Muted)
                    }
                    IconButton(onClick = { session.lock() }) {
                        Icon(Sym.lock, contentDescription = stringResource(R.string.lock_now), tint = Muted)
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tab = t },
                        icon = {
                            if (t.central) {
                                // Blue and yellow: the one place where the flag's yellow is used boldly
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    contentColor = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(44.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(ImageVector.vectorResource(t.icon), contentDescription = null, modifier = Modifier.size(24.dp))
                                    }
                                }
                            } else {
                                Icon(ImageVector.vectorResource(t.icon), contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(t.title), style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        },
        floatingActionButton = {
            // On Matters "+" starts a matter; everywhere else it notes something into the Inbox
            FloatingActionButton(onClick = {
                when (tab) {
                    Tab.Matters -> newMatter()
                    Tab.People -> session.personDraft = newPerson()
                    Tab.Time -> session.deadlineDraft = newDeadline()
                    else -> session.inboxDraft = ""
                }
            }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add))
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.Today -> Today(
                    db, session,
                    onOpen = { session.draft = MatterDraft(EditRequest(it, isNew = false)) },
                    onToMatter = { item ->
                        session.draft = MatterDraft(
                            EditRequest(matterFromNote(item.text, System.currentTimeMillis()), isNew = true, fromInboxId = item.id),
                        )
                    },
                )
                Tab.Matters -> MattersList(db, onOpen = { session.draft = MatterDraft(EditRequest(it, isNew = false)) })
                Tab.People -> PeopleList(db, session)
                Tab.Time -> TimeScreen(db, session)
                Tab.Review -> ReviewScreen(db, session)
            }
        }
    }

    if (session.inboxDraft != null) AddDialog(session)

    if (session.inboxDraft == null) session.sharedText?.let { shared ->
        AlertDialog(
            onDismissRequest = { session.sharedText = null },
            title = { Text(stringResource(R.string.share_title)) },
            text = {
                Text(
                    shared.take(600) + if (shared.length > 600) "…" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = cleanInboxText(shared)
                    if (clean != null) {
                        session.write { it.inbox().insert(InboxItem(text = clean, createdAt = System.currentTimeMillis())) }
                    }
                    session.sharedText = null
                }) { Text(stringResource(R.string.share_add)) }
            },
            dismissButton = { TextButton(onClick = { session.sharedText = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Today: what is in focus, then the Inbox. */
@Composable
private fun Today(db: MandatDb, session: Session, onOpen: (Matter) -> Unit, onToMatter: (InboxItem) -> Unit) {
    val inboxFlow = remember(db) { db.inbox().observeAll() }
    val focusFlow = remember(db) { db.matters().observeFocus() }
    val rolesFlow = remember(db) { db.roles().observeAll() }
    val items by inboxFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val focus by focusFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val roles by rolesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val roleNames = roles.associate { it.id to it.name }
    val delegationsFlow = remember(db) { db.delegations().observeOpen() }
    val promisesFlow = remember(db) { db.promises().observeOpen() }
    val peopleFlow = remember(db) { db.people().observeAll() }
    val titlesFlow = remember(db) { db.matters().observeAllTitles() }
    val delegations by delegationsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val promises by promisesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val people by peopleFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val titles by titlesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val nameOf = people.associate { it.id to it.name }
    val titleOf = titles.associate { it.id to it.title }
    val deadlinesFlow = remember(db) { db.deadlines().observeOpen() }
    val deadlines by deadlinesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val today = Dates.today()
    val tomorrow = Dates.addDays(today, 1)
    val overdueDl = deadlines.filter { it.day < today }
    val overdueD = delegations.filter { it.due != null && it.due < today }
    val overdueP = promises.filter { it.due != null && it.due < today }
    val soonDl = deadlines.filter { it.day == today || it.day == tomorrow }
    val soonD = delegations.filter { it.due == today || it.due == tomorrow }
    val soonP = promises.filter { it.due == today || it.due == tomorrow }
    val overdueCount = overdueDl.size + overdueD.size + overdueP.size
    val todayCount = deadlines.count { it.day == today } + delegations.count { it.due == today } + promises.count { it.due == today }

    LazyColumn(contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 96.dp)) {
        item(key = "stats") {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                StatTile(overdueCount, stringResource(R.string.stat_overdue), attention = true)
                StatTile(todayCount, stringResource(R.string.stat_today))
                StatTile(focus.size, stringResource(R.string.stat_focus))
            }
        }
        if (overdueCount > 0) {
            item(key = "overdue_h") { SectionHeader(Sym.warning, stringResource(R.string.overdue_title), attention = true) }
            items(overdueDl, key = { "odl${it.id}" }) { DeadlineRow(it, it.matterId?.let(titleOf::get), session) }
            items(overdueD, key = { "od${it.id}" }) { DelegationRow(it, it.personId?.let(nameOf::get), it.matterId?.let(titleOf::get), session) }
            items(overdueP, key = { "op${it.id}" }) { PromiseRow(it, it.personId?.let(nameOf::get), it.matterId?.let(titleOf::get), session) }
        }
        if (soonDl.isNotEmpty() || soonD.isNotEmpty() || soonP.isNotEmpty()) {
            item(key = "soon_h") { SectionHeader(Sym.schedule, stringResource(R.string.soon_title)) }
            items(soonDl, key = { "sdl${it.id}" }) { DeadlineRow(it, it.matterId?.let(titleOf::get), session) }
            items(soonD, key = { "sd${it.id}" }) { DelegationRow(it, it.personId?.let(nameOf::get), it.matterId?.let(titleOf::get), session) }
            items(soonP, key = { "sp${it.id}" }) { PromiseRow(it, it.personId?.let(nameOf::get), it.matterId?.let(titleOf::get), session) }
        }
        item(key = "focus_h") { SectionHeader(Sym.target, stringResource(R.string.focus_title)) }
        if (focus.isEmpty()) {
            item(key = "focus_empty") { EmptyHint(Sym.target, stringResource(R.string.focus_empty)) }
        } else {
            items(focus, key = { "m${it.id}" }) { m ->
                MatterCard(m, roleNames[m.roleId], onOpen, Modifier.padding(bottom = Space.gap))
            }
        }
        item(key = "inbox_h") {
            SectionHeader(Sym.inbox, stringResource(R.string.inbox) + if (items.isNotEmpty()) " · ${items.size}" else "")
        }
        if (items.isEmpty()) {
            item(key = "inbox_empty") { EmptyHint(Sym.inbox, stringResource(R.string.inbox_empty)) }
        } else {
            items(items, key = { "i${it.id}" }) { item ->
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                            maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        IconButton(onClick = { onToMatter(item) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.to_matter), tint = Muted)
                        }
                        IconButton(onClick = { session.write { it.inbox().delete(item.id) } }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete), tint = Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddDialog(session: Session) {
    // The text lives in the Session: it survives turning the phone and is saved on lock
    AlertDialog(
        onDismissRequest = { session.inboxDraft = null },
        title = { Text(stringResource(R.string.inbox)) },
        text = {
            OutlinedTextField(
                value = session.inboxDraft.orEmpty(),
                onValueChange = { session.inboxDraft = it },
                placeholder = { Text(stringResource(R.string.add_hint)) },
                minLines = 3,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { session.saveInboxDraft() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { session.inboxDraft = null }) { Text(stringResource(R.string.cancel)) } },
    )
}
