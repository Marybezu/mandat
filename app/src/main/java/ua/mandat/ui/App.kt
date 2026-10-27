package ua.mandat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import ua.mandat.data.FOCUS_LIMIT
import ua.mandat.data.TaskStatus
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
import kotlinx.coroutines.flow.flatMapLatest
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun App(session: Session, onUnlock: () -> Unit, onStartOver: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(Modifier.fillMaxSize()) {
        // Over every screen, the lock screen included: screenshots are allowed right now
        if (session.screenshotsAllowed) ScreenshotsBanner(session)
        Box(
            Modifier.weight(1f).let {
                if (session.screenshotsAllowed) it.consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.statusBars) else it
            },
        ) {
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
/** Overdue for longer than this is folded into "old, to sort out". */
const val OLD_OVERDUE_DAYS = 30

private enum class Tab(val title: Int, val icon: Int, val central: Boolean = false) {
    Today(R.string.tab_today, R.drawable.sym_home),
    Matters(R.string.tab_matters, R.drawable.sym_folder),
    Review(R.string.tab_review, R.drawable.sym_explore, central = true),
    People(R.string.tab_people, R.drawable.sym_group),
    Time(R.string.tab_time, R.drawable.sym_calendar_month),
}

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
private fun Main(db: MandatDb, session: Session) {
    var tab by rememberSaveable { mutableStateOf(session.lastTab?.let { n -> Tab.entries.firstOrNull { it.name == n } } ?: Tab.Today) }
    androidx.compose.runtime.LaunchedEffect(tab) { session.lastTab = tab.name }
    var options by rememberSaveable { mutableStateOf(false) }
    // Links from one screen to another (for example a role on the dashboard opens its matters)
    androidx.compose.runtime.LaunchedEffect(session.tabRequest) {
        session.tabRequest?.let { name -> Tab.entries.firstOrNull { it.name == name }?.let { tab = it } }
        session.tabRequest = null
    }
    androidx.compose.runtime.LaunchedEffect(session.optionsRequest) {
        if (session.optionsRequest) { options = true; session.optionsRequest = false }
    }

    val rolesAskedFlow = remember(db) { db.settings().observe(SETTING_ROLES_ASKED).map { it != null } }
    val rolesAsked by rolesAskedFlow.collectAsStateWithLifecycle(initialValue = null)
    // The chosen mode (parliament or local council) follows the setting
    val modeSetting by remember(db) { db.settings().observe(ua.mandat.data.SETTING_MODE) }.collectAsStateWithLifecycle(null)
    androidx.compose.runtime.LaunchedEffect(modeSetting) { AppMode.council = modeSetting?.value == ua.mandat.data.MODE_COUNCIL }
    // Trip mode follows its setting and the marks on matters, while Mandat is open
    androidx.compose.runtime.LaunchedEffect(db) {
        val lensHidden = session.lensRole.flatMapLatest { r ->
            if (r == null) kotlinx.coroutines.flow.flowOf(emptyList()) else db.matters().observeOutsideRole(r)
        }
        kotlinx.coroutines.flow.combine(db.settings().observe(SETTING_TRIP), db.matters().observeSensitiveIds(), lensHidden) { trip, ids, lens ->
            val on = trip?.value == "1"
            on to ((if (on) ids.toSet() else emptySet()) + lens)
        }.collect { (on, hidden) ->
            session.tripOn.value = on
            session.hiddenMatters.value = hidden
        }
    }

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
    session.eventDraft?.let { EventDialog(db, session, it) }
    session.infoDraft?.let { InfoDialog(db, session, it) }
    session.messageDraft?.let { MessageDialog(db, session, it) }
    session.allianceDraft?.let { AllianceDialog(db, session, it) }
    session.moneyDraft?.let { MoneyDialog(db, session, it) }
    session.communityDraft?.let { CommunityDialog(session, it) }
    session.textDraft?.let { TextEditor(db, session, it) }
    if (session.showAiSplit) AiSplitSheet(db, session) { session.showAiSplit = false }
    if (session.showInboxSort) InboxSortSheet(db, session) { session.showInboxSort = false }
    if (session.aiCenter != null) AiCenter(db, session)
    session.vcfDone?.let { (n, uri) -> VcfDoneDialog(session, n, uri) }

    session.draft?.let { draft ->
        MatterEditor(db, session, draft)
        return
    }
    session.personDraft?.let { draft ->
        PersonEditor(db, session, draft)
        return
    }
    session.thankFor?.let { ThankDialog(db, session, it) }
    // Export can be opened from a brief or a text, so it comes over them
    if (session.showExport) {
        ExportScreen(db, session)
        return
    }
    session.oneOnOne?.let {
        OneOnOneScreen(db, session, it)
        return
    }
    session.influenceFor?.let {
        InfluenceMapScreen(db, session, it.takeIf { id -> id != 0L })
        return
    }
    session.communityOpen?.let {
        CommunityScreen(db, session, it)
        return
    }
    if (session.showCategorize) {
        CategorizeScreen(db, session)
        return
    }
    if (session.showDistrict) {
        DistrictScreen(db, session)
        return
    }
    if (session.showBudget) {
        BudgetScreen(db, session)
        return
    }
    if (session.showTexts) {
        TextsScreen(db, session)
        return
    }
    if (session.showDiary) {
        DiaryScreen(db, session)
        return
    }
    if (session.showInfo) {
        InfoScreen(db, session)
        return
    }
    if (session.showAlliances) {
        AlliancesScreen(db, session)
        return
    }
    if (session.showDirectory) {
        val job = session.vcfImport
        if (job != null) VcfPreviewScreen(session, job) else DirectoryScreen(db, session)
        return
    }
    if (session.showTopics) {
        TopicsScreen(db, session)
        return
    }
    if (session.showTriage) {
        TriageScreen(db, session)
        return
    }
    if (session.showRetro) {
        // Lists opened from the retrospective come over it
        session.roleTime?.let { (role, span) -> RoleTimeScreen(db, session, role, span); return }
        session.periodView?.let { (kind, span) -> PeriodScreen(db, session, kind, span); return }
        RetroScreen(db, session)
        return
    }
    session.roleTime?.let { (role, span) ->
        RoleTimeScreen(db, session, role, span)
        return
    }
    session.periodView?.let { (kind, span) ->
        PeriodScreen(db, session, kind, span)
        return
    }
    session.smartList?.let {
        SmartListScreen(db, session, it)
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

    val aiCenterLabel = stringResource(R.string.ai_center)
    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                ScreenHeader(
                    title = stringResource(tab.title),
                    subtitle = if (tab == Tab.Today) Dates.longToday() else null,
                ) {
                    // Everything the AI can do, in one place; the same violet mark as every AI suggestion
                    IconButton(onClick = { session.aiCenter = tab.name }) {
                        Text(AI_MARK, style = MaterialTheme.typography.titleLarge, color = AiViolet,
                            modifier = Modifier.semantics { contentDescription = aiCenterLabel })
                    }
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
                LensBar(db, session, full = tab == Tab.Today || tab == Tab.Matters || tab == Tab.Review)
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
                Tab.Matters -> MattersList(db, onOpen = { session.draft = MatterDraft(EditRequest(it, isNew = false)) }, onTopics = { session.showTopics = true }, session = session)
                Tab.People -> PeopleList(db, session)
                Tab.Time -> TimeTab(db, session)
                Tab.Review -> ReviewScreen(db, session)
            }
        }
    }

    if (session.inboxDraft != null) AddDialog(db, session)

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

/**
 * Today: three rings (today, focus, overdue) that jump to their section, then clear cards:
 * overdue, today and tomorrow, focus, and the Inbox.
 */
@Composable
internal fun Today(db: MandatDb, session: Session, onOpen: (Matter) -> Unit, onToMatter: (InboxItem) -> Unit) {
    val items by remember(db) { db.inbox().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val focus by remember(db) { db.matters().observeFocus().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val roles by remember(db) { db.roles().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val deadlines by remember(db) { db.deadlines().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val delegations by remember(db) { db.delegations().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val promises by remember(db) { db.promises().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val roleNames = roles.associate { it.id to it.name }
    val nameOf = people.associate { it.id to it.name }
    val titleOf = titles.associate { it.id to it.title }
    val today = Dates.today()
    val tomorrow = Dates.addDays(today, 1)
    fun open(status: String) = TaskStatus.of(status) == TaskStatus.OPEN

    val openDl = deadlines.filter { open(it.status) }
    val openD = delegations.filter { open(it.status) }
    val openP = promises.filter { open(it.status) }
    val overdueDl = openDl.filter { it.day < today }
    val overdueD = openD.filter { it.due != null && it.due < today }
    val overdueP = openP.filter { it.due != null && it.due < today }
    val soonDl = openDl.filter { it.day == today || it.day == tomorrow }
    val soonD = openD.filter { it.due == today || it.due == tomorrow }
    val soonP = openP.filter { it.due == today || it.due == tomorrow }
    val active by remember(db) { db.matters().observeActive().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val overdueCount = overdueDl.size + overdueD.size + overdueP.size
    val dated = openDl.size + openD.count { it.due != null } + openP.count { it.due != null }
    // Everything due today, and how much of it is already done
    val dueToday = deadlines.filter { it.day == today && TaskStatus.of(it.status) != TaskStatus.CANCELLED }.map { it.status } +
        delegations.filter { it.due == today && TaskStatus.of(it.status) != TaskStatus.CANCELLED }.map { it.status } +
        promises.filter { it.due == today && TaskStatus.of(it.status) != TaskStatus.CANCELLED }.map { it.status }
    val doneToday = dueToday.count { TaskStatus.of(it) == TaskStatus.DONE }

    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val overdueAt = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val soonAt = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val focusAt = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    fun jump(r: androidx.compose.foundation.relocation.BringIntoViewRequester) {
        scope.launch { r.bringIntoView() }
    }
    var showAllOverdue by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(start = Space.screen, end = Space.screen, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Space.gap + 2.dp),
    ) {
        MCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RingStat(
                    text = "$doneToday/${dueToday.size}", label = stringResource(R.string.stat_today),
                    fraction = if (dueToday.isEmpty()) 0f else doneToday.toFloat() / dueToday.size,
                    color = ChartColors.blue, modifier = Modifier.weight(1f),
                ) { jump(soonAt) }
                RingStat(
                    text = "${focus.size}/$FOCUS_LIMIT", label = stringResource(R.string.stat_focus),
                    fraction = (focus.size.toFloat() / FOCUS_LIMIT).coerceAtMost(1f),
                    overflow = if (focus.size > FOCUS_LIMIT) (focus.size - FOCUS_LIMIT).toFloat() / focus.size else 0f,
                    color = ChartColors.focus, modifier = Modifier.weight(1f),
                ) { jump(focusAt) }
                RingStat(
                    text = overdueCount.toString(), label = stringResource(R.string.stat_overdue),
                    fraction = if (dated == 0) 0f else overdueCount.toFloat() / dated,
                    color = MaterialTheme.colorScheme.error, attention = overdueCount > 0, modifier = Modifier.weight(1f),
                ) { jump(overdueAt) }
            }
        }

        SmartListChips(smartCounts(active, openD.size, openP.size, today)) { session.smartList = it }
        // The one AI moment on this screen, folded until asked (only with the model on the phone)
        AiRow(session, ua.mandat.ai.LocalAi.Task.MORNING, facts = { session.withOpenDb { todayFacts(it, session) }.orEmpty() })
        DayStrip(db, session)

        // Overdue for more than a month is "old": folded into one line to sort out, so the list shows what is current
        val oldLimit = today - OLD_OVERDUE_DAYS
        val recentDl = overdueDl.filter { it.day >= oldLimit }
        val recentD = overdueD.filter { it.due!! >= oldLimit }
        val recentP = overdueP.filter { it.due!! >= oldLimit }
        val oldCount = overdueCount - recentDl.size - recentD.size - recentP.size
        if (overdueCount > 0) {
            SectionCard(Sym.warning, stringResource(R.string.overdue_title), Modifier.bringIntoViewRequester(overdueAt),
                count = overdueCount, attention = true) {
                val rows = buildList<@Composable () -> Unit> {
                    recentDl.forEach { d -> add { DeadlineRow(d, d.matterId?.let(titleOf::get), session) } }
                    recentD.forEach { d -> add { DelegationRow(d, d.personId?.let(nameOf::get), d.matterId?.let(titleOf::get), session) } }
                    recentP.forEach { p -> add { PromiseRow(p, p.personId?.let(nameOf::get), p.matterId?.let(titleOf::get), session) } }
                }
                val shown = if (showAllOverdue) rows else rows.take(4)
                shown.forEachIndexed { i, row -> if (i > 0) RowDivider(); row() }
                if (rows.size > 4) {
                    TextButton(onClick = { showAllOverdue = !showAllOverdue }) {
                        Text(if (showAllOverdue) stringResource(R.string.show_less) else stringResource(R.string.show_more, rows.size - 4))
                    }
                }
                if (oldCount > 0) {
                    if (rows.isNotEmpty()) RowDivider()
                    Row(
                        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.errorContainer).clickable { session.showTriage = true }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.old_overdue, oldCount), style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(stringResource(R.string.old_overdue_hint), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        Text("→", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        SectionCard(Sym.schedule, stringResource(R.string.soon_title), Modifier.bringIntoViewRequester(soonAt),
            count = soonDl.size + soonD.size + soonP.size) {
            val rows = buildList<@Composable () -> Unit> {
                soonDl.forEach { d -> add { DeadlineRow(d, d.matterId?.let(titleOf::get), session) } }
                soonD.forEach { d -> add { DelegationRow(d, d.personId?.let(nameOf::get), d.matterId?.let(titleOf::get), session) } }
                soonP.forEach { p -> add { PromiseRow(p, p.personId?.let(nameOf::get), p.matterId?.let(titleOf::get), session) } }
            }
            if (rows.isEmpty()) EmptyHint(Sym.checkCircle, stringResource(R.string.soon_empty))
            rows.forEachIndexed { i, row -> if (i > 0) RowDivider(); row() }
        }

        SectionCard(Sym.target, stringResource(R.string.focus_title), Modifier.bringIntoViewRequester(focusAt),
            count = focus.size, attention = focus.size > FOCUS_LIMIT) {
            if (focus.isEmpty()) EmptyHint(Sym.target, stringResource(R.string.focus_empty))
            focus.forEachIndexed { i, m ->
                if (i > 0) RowDivider()
                MatterLine(m, roleNames[m.roleId]) { onOpen(m) }
            }
        }

        SectionCard(Sym.inbox, stringResource(R.string.inbox), count = items.size.takeIf { it > 0 }) {
            if (items.isEmpty()) EmptyHint(Sym.inbox, stringResource(R.string.inbox_empty))
            items.forEachIndexed { i, item ->
                if (i > 0) RowDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(vertical = 8.dp),
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

@Composable
private fun AddDialog(db: MandatDb, session: Session) {
    val people by remember(db) { db.people().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val matters by remember(db) { db.matters().observeActiveTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val text = session.inboxDraft.orEmpty()
    // Smart input: what the note looks like (rules only); the owner decides
    val hint = remember(text, people, matters) { ua.mandat.data.suggest(text, people, matters, Dates.today()) }

    fun take() {
        val clean = text.trim().take(2_000)
        session.inboxDraft = null
        when (hint.kind) {
            ua.mandat.data.NoteKind.DELEGATION -> session.delegationDraft = newDelegation(hint.personId, hint.matterId).also { it.what = clean; it.due = hint.due }
            ua.mandat.data.NoteKind.PROMISE -> session.promiseDraft = newPromise(hint.personId, hint.matterId).also { it.what = clean; it.due = hint.due }
            ua.mandat.data.NoteKind.DEADLINE -> session.deadlineDraft = newDeadline(hint.matterId).also { d -> d.title = clean; hint.due?.let { d.day = it } }
            ua.mandat.data.NoteKind.MATTER -> {
                val m = matterFromNote(clean, System.currentTimeMillis())
                session.draft = MatterDraft(EditRequest(m.copy(type = (hint.matterType ?: ua.mandat.data.MatterType.OTHER).name), isNew = true))
            }
            ua.mandat.data.NoteKind.NOTE -> {}
        }
    }

    // The text lives in the Session: it survives turning the phone and is saved on lock
    EditSheet(
        title = stringResource(R.string.inbox),
        onDismiss = { session.inboxDraft = null },
        onSave = { session.saveInboxDraft() },
        saveText = stringResource(R.string.save_to_inbox),
    ) {
        SheetGroup {

                OutlinedTextField(
                    value = text,
                    onValueChange = { session.inboxDraft = it },
                    placeholder = { Text(stringResource(R.string.add_hint)) },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                // A longer text (a letter, meeting notes) can be split into separate items by the on-device AI
                val aiReady by session.ai.models.ready.collectAsStateWithLifecycle()
                // What was typed goes along into the split sheet
                if (aiReady && text.isNotBlank()) AiChip(stringResource(R.string.ai_split_go)) { session.aiSplitText = text; session.inboxDraft = null; session.showAiSplit = true }
                if (hint.kind != ua.mandat.data.NoteKind.NOTE && text.isNotBlank()) {
                    val kindLabel = stringResource(
                        when (hint.kind) {
                            ua.mandat.data.NoteKind.DELEGATION -> R.string.looks_delegation
                            ua.mandat.data.NoteKind.PROMISE -> R.string.looks_promise
                            ua.mandat.data.NoteKind.DEADLINE -> R.string.looks_deadline
                            else -> R.string.looks_matter
                        },
                    )
                    val parts = listOfNotNull(
                        hint.personId?.let { id -> people.firstOrNull { it.id == id }?.name },
                        hint.due?.let { Dates.format(it) },
                        hint.matterId?.let { id -> matters.firstOrNull { it.id == id }?.title },
                        hint.matterType?.let { stringResource(it.label()) },
                    )
                    Column(
                        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer).clickable { take() }.padding(12.dp),
                    ) {
                        Text(kindLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        if (parts.isNotEmpty()) {
                            Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(top = 2.dp))
                        }
                        Text(stringResource(R.string.looks_tap), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(top = 4.dp))
                    }
                }
        }
    }
}

/** A visible reminder while screenshots are allowed; tapping it forbids them again. */
@Composable
private fun ScreenshotsBanner(session: Session) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity ?: return
    var failed by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).statusBarsPadding()
            .clickable {
                failed = !Appearance.setScreenshotsAllowed(activity, false)
                session.screenshotsAllowed = Appearance.screenshotsAllowed(activity)
            }
            .padding(horizontal = Space.screen, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(if (failed) R.string.screenshots_off_failed else R.string.screenshots_banner), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.screenshots_turn_off), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer)
    }
}
