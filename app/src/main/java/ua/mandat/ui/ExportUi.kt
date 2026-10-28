package ua.mandat.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import ua.mandat.R
import ua.mandat.data.Export
import ua.mandat.data.MandatDb
import ua.mandat.data.TaskStatus

/** What to export, as chosen on the screen. */
class ExportPlan {
    var people by mutableStateOf(false)
    var peopleCategory by mutableStateOf<String?>(null)
    var book by mutableStateOf(false)
    var bookGroup by mutableStateOf<String?>(null)
    var calendar by mutableStateOf(false)
    /** 0 everything, 1 from today on, 2 the last year. */
    var calendarSpan by mutableStateOf(0)
    var tables by mutableStateOf(setOf<String>())
    var diary by mutableStateOf(false)
    /** Documents (diary, matter) also as PDF. */
    var pdf by mutableStateOf(true)
    var matterId by mutableStateOf<Long?>(null)
    var everything by mutableStateOf(false)
    /** One text as Word, PDF and Markdown. */
    var textId by mutableStateOf<Long?>(null)
    /** A brief before meeting a person, as a document. */
    var briefId by mutableStateOf<Long?>(null)
    /** Notes for Obsidian: one per matter, person, event and diary day, with [[links]]. */
    var obsidian by mutableStateOf(false)
    /** The basic vault, or everything (delegations, promises, deadlines, media, communities, texts…). */
    var vaultScope by mutableStateOf(VaultScope.BASIC)
    /** All people and the address book, not only the people tied to what is exported. */
    var allPeople by mutableStateOf(false)
    /** A packet of matters and tasks for a colleague's Mandat (only inside Mandat's own file). */
    var packet by mutableStateOf(false)
    /** Set only on the copy of the plan used for Mandat's own file: the packet is built only there. */
    var packetFile = false
    /** People's contacts go into the packet (on by default); their stances only when chosen. */
    var packetContacts by mutableStateOf(true)
    var packetStances by mutableStateOf(false)
    /** The period records are taken from (a whole backup is never cut by it). */
    var period by mutableStateOf(ExportPeriod.ALL)
    var periodFrom by mutableStateOf(Dates.today() - 30)
    var periodTo by mutableStateOf(Dates.today())

    /** The chosen period as days, or null for everything. */
    fun span(today: Long, cadence: Long?): ua.mandat.data.Span? = when (period) {
        ExportPeriod.ALL -> null
        ExportPeriod.TODAY -> ua.mandat.data.Span(today, today)
        ExportPeriod.WEEK -> ua.mandat.data.Span(today - 6, today)
        ExportPeriod.MONTH -> ua.mandat.data.Span(java.time.LocalDate.ofEpochDay(today).minusMonths(1).plusDays(1).toEpochDay(), today)
        ExportPeriod.QUARTER -> ua.mandat.data.Span(java.time.LocalDate.ofEpochDay(today).minusMonths(3).plusDays(1).toEpochDay(), today)
        ExportPeriod.YEAR -> ua.mandat.data.Span(java.time.LocalDate.ofEpochDay(today).minusYears(1).plusDays(1).toEpochDay(), today)
        ExportPeriod.CADENCE -> ua.mandat.data.Span(cadence ?: java.time.LocalDate.ofEpochDay(today).minusYears(1).toEpochDay(), today)
        ExportPeriod.CUSTOM -> ua.mandat.data.Span(minOf(periodFrom, periodTo), maxOf(periodFrom, periodTo))
    }

    fun any() = people || book || calendar || tables.isNotEmpty() || diary || matterId != null || everything || textId != null || briefId != null || obsidian
}

/** Periods offered for an export. */
enum class ExportPeriod(val label: Int) {
    ALL(R.string.period_all), TODAY(R.string.period_today), WEEK(R.string.period_week), MONTH(R.string.period_month),
    QUARTER(R.string.period_quarter), YEAR(R.string.period_year), CADENCE(R.string.period_cadence), CUSTOM(R.string.period_custom),
}

private val TABLES = listOf("matters", "delegations", "promises", "deadlines", "events", "money", "people")

/**
 * Export and import. Export is protected with a password by default (a ZIP with AES-256 that
 * 7-Zip and similar apps open); an open file only after an explicit choice and a warning.
 * In trip mode, hidden matters and what is linked to them are left out.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportScreen(db: MandatDb, session: Session) {
    BackHandler { session.showExport = false }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plan = remember { ExportPlan().also { p -> session.exportText?.let { p.textId = it }; session.exportBrief?.let { p.briefId = it } } }
    androidx.compose.runtime.LaunchedEffect(Unit) { session.exportText = null; session.exportBrief = null }
    val textTitle by remember(db, plan.textId) { kotlinx.coroutines.flow.flow { emit(plan.textId?.let { id -> session.withOpenDb { it.texts().get(id) }
        ?.takeIf { t -> t.matterId == null || t.matterId !in session.hiddenMatters.value }?.title }) } }
        .collectAsStateWithLifecycle(null)
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    val contacts by remember(db) { db.contacts().observeAll().hidingNamed(session, { null }) { it.name + " " + it.organization + " " + it.jobTitle + " " + it.notes + " " + it.label } }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val trip by session.tripOn.collectAsStateWithLifecycle()
    // 0 a ZIP with a password (other apps open it), 1 Mandat's own encrypted file, 2 an open file
    // Mandat's own file by default (the strongest protection); a ZIP for other apps only by choice.
    // A text or a brief cannot go into Mandat's own file, so those start with the ZIP
    var mode by remember { mutableStateOf(if (plan.textId != null || plan.briefId != null) PROTECT_ZIP else PROTECT_MANDAT) }
    val protect = mode != PROTECT_OPEN
    var suggested by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var warnOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pickedAt by remember { mutableStateOf(-1) }
    var importKind by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var pickingMatter by remember { mutableStateOf(false) }
    // An imported open file that can now be deleted; an encrypted Mandat file waiting for its password
    var offerDelete by remember { mutableStateOf<Uri?>(null) }
    var sealed by remember { mutableStateOf<ByteArray?>(null) }
    val hidden by session.hiddenMatters.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(hidden) { if (hidden.isNotEmpty()) plan.everything = false }
    // A full copy is never cut by a period: choosing one turns the copy off rather than leave it ticked unseen
    androidx.compose.runtime.LaunchedEffect(plan.period) { if (plan.period != ExportPeriod.ALL) plan.everything = false }
    val cadenceSet by remember(db) { db.settings().observe(ua.mandat.data.SETTING_CADENCE_START) }.collectAsStateWithLifecycle(null)
    val sensitiveIds by remember(db) { db.matters().observeSensitiveIds() }.collectAsStateWithLifecycle(emptyList())

    var tooBigForMandat by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        session.pickingFile = false
        if (uri == null || pickedAt != session.generationNow()) return@rememberLauncherForActivityResult
        busy = true
        val pass = if (protect) password.toCharArray() else null
        val own = mode == PROTECT_MANDAT
        tooBigForMandat = false
        var existingFile = false
        password = ""; repeat = ""; suggested = false
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { runExport(context, session, plan, uri, pass, own, tooBig = { tooBigForMandat = true }, existing = { existingFile = true }) }
                message = context.getString(when {
                    ok -> R.string.export_done
                    existingFile -> R.string.export_existing_file
                    own && tooBigForMandat -> R.string.export_too_big
                    else -> R.string.export_failed
                })
            } finally {
                pass?.fill(' ')
                busy = false
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        session.pickingFile = false
        val kind = importKind
        importKind = null
        if (uri == null || kind == null || pickedAt != session.generationNow()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            if (kind == "mandat") {
                val bytes = withContext(Dispatchers.IO) { readBytes(context, uri) }
                busy = false
                if (bytes == null) message = context.getString(R.string.import_failed) else sealed = bytes
                return@launch
            }
            val p = withContext(Dispatchers.IO) { readImport(context, kind, uri) }
            busy = false
            if (p == null) message = context.getString(R.string.import_failed) else pendingImport = p
        }
    }
    fun fileName(): String {
        val date = java.time.LocalDate.now().toString()
        val single = !plan.obsidian && listOf(plan.people || plan.book, plan.calendar, plan.tables.isNotEmpty(), plan.diary, plan.matterId != null, plan.everything, plan.textId != null).count { it } == 1 &&
            plan.tables.size <= 1 && !(plan.people && plan.book) && plan.textId == null && plan.briefId == null && !((plan.diary || plan.matterId != null) && plan.pdf)
        // A file from the demo says so in its name, so it is never taken for real data
        // Neutral names: the file does not say which app it comes from (the demo's still says it is the demo)
        val base = if (session.demo) "demo-export" else "export"
        if (mode == PROTECT_MANDAT) return "$base-$date.$MANDAT_EXPORT_EXT"
        if (protect || !single) return "$base-$date.zip"
        return "$base-$date." + when {
            plan.people || plan.book -> "vcf"
            plan.calendar -> "ics"
            plan.tables.isNotEmpty() -> "csv"
            plan.everything -> "json"
            else -> "md"
        }
    }
    val confirmTitle = stringResource(R.string.confirm_export)
    fun startSave() {
        // Anything leaving Mandat needs the phone's lock first: a moment with the phone unattended
        // (or forced open) cannot carry data off
        context.findActivity()?.let { a ->
            confirmWithPhoneLock(a, session, confirmTitle) {
                pickedAt = session.generationNow()
                session.pickingFile = true
                save.launch(fileName())
            }
        }
    }
    // Mandat's own file holds only what Mandat can take back in: contacts and the calendar
    val ownOnly = mode == PROTECT_MANDAT
    val anyChosen = if (ownOnly) plan.people || plan.book || plan.calendar || plan.packet else plan.any()
    // A ZIP's own key derivation is weak (fixed by the ZIP standard), so it needs a longer password
    val minLength = if (mode == PROTECT_ZIP) MIN_ZIP_PASSWORD_LENGTH else ua.mandat.backup.BackupCrypto.MIN_PASSWORD_LENGTH
    fun weak(p: String) = ua.mandat.backup.BackupCrypto.isTooSimple(p) || p.length < minLength
    val passOk = !protect || (!weak(password) && password == repeat)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.export_title)) },
                navigationIcon = {
                    IconButton(onClick = { session.showExport = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen),
            verticalArrangement = Arrangement.spacedBy(Space.gap),
        ) {
            if (trip) Text(stringResource(R.string.export_trip_note), style = MaterialTheme.typography.labelMedium, color = Muted)
            else if (hidden.isNotEmpty()) Text(stringResource(R.string.export_lens_note), style = MaterialTheme.typography.labelMedium, color = Muted)
            // One period for everything chosen below; each kind of record is taken by its own dates
            SectionCard(Sym.calendar, stringResource(R.string.export_period)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // "Cadence" only once its start date is set (in the district settings), never silently a year
                    ExportPeriod.entries.filter { it != ExportPeriod.CADENCE || cadenceSet?.value?.toLongOrNull() != null }
                        .forEach { p -> QuickChip(stringResource(p.label), plan.period == p) { plan.period = p } }
                }
                if (plan.period == ExportPeriod.CUSTOM) {
                    DueField(plan.periodFrom, allowClear = false, label = R.string.report_from) { if (it != null) plan.periodFrom = it }
                    DueField(plan.periodTo, allowClear = false, label = R.string.report_to) { if (it != null) plan.periodTo = it }
                }
                if (plan.period != ExportPeriod.ALL) Text(stringResource(R.string.export_period_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            SectionCard(Sym.save, stringResource(R.string.export_what)) {
                Check(stringResource(R.string.export_people), plan.people) { plan.people = it }
                if (plan.people) Text(stringResource(R.string.export_people_note), style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(start = 40.dp))
                if (plan.people) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 40.dp)) {
                    QuickChip(stringResource(R.string.matters_all_roles), plan.peopleCategory == null) { plan.peopleCategory = null }
                    (listOf("TEAM") + categoryChoices(people)).forEach { k ->
                        QuickChip(if (k == "TEAM") stringResource(R.string.team) else categoryLabel(k), plan.peopleCategory == k) { plan.peopleCategory = k }
                    }
                }
                Check(stringResource(R.string.export_book, contacts.size), plan.book) { plan.book = it }
                if (plan.book) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 40.dp)) {
                    QuickChip(stringResource(R.string.matters_all_roles), plan.bookGroup == null) { plan.bookGroup = null }
                    contacts.map { it.label }.filter { it.isNotBlank() }.distinct().sorted().forEach { g ->
                        QuickChip(g, plan.bookGroup == g) { plan.bookGroup = g }
                    }
                }
                Check(stringResource(R.string.export_calendar), plan.calendar) { plan.calendar = it }
                if (plan.calendar) Text(stringResource(R.string.export_calendar_note), style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(start = 40.dp))
                if (plan.calendar && plan.period == ExportPeriod.ALL) Row(Modifier.padding(start = 40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(R.string.export_span_all, R.string.export_span_future, R.string.export_span_year).forEachIndexed { i, l ->
                        QuickChip(stringResource(l), plan.calendarSpan == i) { plan.calendarSpan = i }
                    }
                }
                if (ownOnly) {
                    // A packet for a colleague's Mandat: the chosen matter, or what moved in the period
                    Check(stringResource(R.string.packet_check), plan.packet) { plan.packet = it }
                    if (plan.packet) Column(Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = plan.matterId != null, onCheckedChange = { if (it) pickingMatter = true else plan.matterId = null })
                            Text(plan.matterId?.let { id -> stringResource(R.string.packet_matter_one, titles.firstOrNull { it.id == id }?.title.orEmpty()) }
                                ?: stringResource(R.string.packet_one_matter), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { pickingMatter = true })
                        }
                        if (plan.matterId != null && plan.matterId in sensitiveIds) Text(stringResource(R.string.packet_sensitive), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        Check(stringResource(R.string.packet_contacts), plan.packetContacts) { plan.packetContacts = it }
                        Check(stringResource(R.string.packet_stances), plan.packetStances) { plan.packetStances = it }
                        Text(stringResource(R.string.packet_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                    Text(stringResource(R.string.export_mandat_only), style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
                }
                if (ownOnly && (plan.textId != null || plan.briefId != null)) Text(stringResource(R.string.export_mandat_no_docs), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 12.dp, top = 2.dp))
                else {
                Text(stringResource(R.string.export_tables), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TABLES.forEach { t ->
                        QuickChip(stringResource(tableLabel(t)), t in plan.tables) { plan.tables = if (t in plan.tables) plan.tables - t else plan.tables + t }
                    }
                }
                Check(stringResource(R.string.export_diary), plan.diary) { plan.diary = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = plan.matterId != null, onCheckedChange = { if (it) pickingMatter = true else plan.matterId = null })
                    Text(plan.matterId?.let { id -> stringResource(R.string.export_matter_one, titles.firstOrNull { it.id == id }?.title.orEmpty()) }
                        ?: stringResource(R.string.export_matter), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable { pickingMatter = true })
                }
                plan.textId?.let { Check(stringResource(R.string.export_text_one, textTitle.orEmpty()), true) { on -> if (!on) plan.textId = null } }
                plan.briefId?.let { Check(stringResource(R.string.export_brief_one), true) { on -> if (!on) plan.briefId = null } }
                if (plan.diary || plan.matterId != null || plan.textId != null || plan.briefId != null) Check(stringResource(R.string.export_pdf), plan.pdf) { plan.pdf = it }
                Check(stringResource(R.string.export_obsidian), plan.obsidian) { plan.obsidian = it }
                if (plan.obsidian) Column(Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickChip(stringResource(R.string.export_vault_basic), plan.vaultScope == VaultScope.BASIC) { plan.vaultScope = VaultScope.BASIC }
                        QuickChip(stringResource(R.string.export_vault_full), plan.vaultScope == VaultScope.FULL) { plan.vaultScope = VaultScope.FULL }
                    }
                    Text(stringResource(if (plan.vaultScope == VaultScope.FULL) R.string.export_vault_full_hint else R.string.export_obsidian_hint),
                        style = MaterialTheme.typography.labelSmall, color = if (plan.vaultScope == VaultScope.FULL) MaterialTheme.colorScheme.error else Muted)
                    // Only where it changes something: the full vault (address book), or a period (people beyond those tied in)
                    if (plan.vaultScope == VaultScope.FULL || plan.period != ExportPeriod.ALL)
                        Check(stringResource(if (plan.vaultScope == VaultScope.FULL) R.string.export_vault_all_people else R.string.export_vault_all_people_basic), plan.allPeople) { plan.allPeople = it }
                }
                if (hidden.isEmpty() && plan.period == ExportPeriod.ALL) Check(stringResource(R.string.export_everything), plan.everything) { plan.everything = it }
                else if (hidden.isEmpty()) Text(stringResource(R.string.export_everything_period), style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(start = 12.dp))
                else Text(stringResource(R.string.export_everything_hidden), style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(start = 12.dp))
                }
            }
            SectionCard(Sym.lock, stringResource(R.string.export_protection)) {
                @Composable
                fun Choice(value: Int, title: Int, hint: Int, warn: Boolean = false) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { mode = value }) {
                        RadioButton(selected = mode == value, onClick = { mode = value })
                        Column {
                            Text(stringResource(title), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(hint), style = MaterialTheme.typography.labelSmall, color = if (warn) MaterialTheme.colorScheme.error else Muted)
                        }
                    }
                }
                Choice(PROTECT_MANDAT, R.string.export_mandat, R.string.export_mandat_hint)
                Choice(PROTECT_ZIP, R.string.export_with_password, R.string.export_with_password_hint)
                if (protect) {
                    TextButton(onClick = {
                        scope.launch {
                            val words = withContext(Dispatchers.IO) { session.backups.words }
                            password = ua.mandat.backup.BackupCrypto.suggestPassword(words)
                            repeat = password
                            suggested = true
                        }
                    }) { Text(stringResource(R.string.suggest_password)) }
                    if (suggested) {
                        Text(stringResource(R.string.export_suggested_note), style = MaterialTheme.typography.bodyMedium)
                        Text(password, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    OutlinedTextField(password, { password = it; suggested = false }, label = { Text(stringResource(R.string.export_password, minLength)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        isError = password.isNotEmpty() && weak(password),
                        supportingText = { if (password.isNotEmpty() && weak(password)) Text(stringResource(if (mode == PROTECT_ZIP) R.string.export_zip_password_weak else R.string.export_password_weak)) },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(repeat, { repeat = it; suggested = false }, label = { Text(stringResource(R.string.export_password_repeat)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        isError = repeat.isNotEmpty() && repeat != password, modifier = Modifier.fillMaxWidth())
                }
                Choice(PROTECT_OPEN, R.string.export_open, R.string.export_open_hint, warn = true)
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(8.dp)); Text(stringResource(R.string.export_working)) }
            Button(onClick = { if (protect) startSave() else warnOpen = true }, enabled = anyChosen && passOk && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.export_go))
            }
            Spacer(Modifier.height(Space.section))
            // No import in the demo: a real file brought in would vanish with it (and might be deleted as "imported")
            if (session.demo) Text(stringResource(R.string.demo_no_import), style = MaterialTheme.typography.labelMedium, color = Muted)
            else SectionCard(Sym.inbox, stringResource(R.string.import_title)) {
                Text(stringResource(R.string.import_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                listOf(
                    "mandat" to R.string.import_mandat, "ics" to R.string.import_ics, "notes" to R.string.import_notes, "csv" to R.string.import_csv, "vcf" to R.string.vcf_import,
                ).forEach { (k, label) ->
                    OutlinedButton(onClick = {
                        if (k == "vcf") { session.showExport = false; session.showDirectory = true; return@OutlinedButton }
                        importKind = k
                        pickedAt = session.generationNow()
                        session.pickingFile = true
                        open.launch(arrayOf("*/*"))
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (pickingMatter) PickTextDialog(titles.map { it.id to it.title }, onPick = { plan.matterId = it; pickingMatter = false }, onCancel = { pickingMatter = false })
    if (warnOpen) AlertDialog(
        onDismissRequest = { warnOpen = false },
        title = { Text(stringResource(R.string.export_open)) },
        // The whole vault in the open is the whole base readable by anyone with the folder: said plainly
        text = { Text(stringResource(R.string.export_open_warning) + if (plan.obsidian && plan.vaultScope == VaultScope.FULL) "\n\n" + stringResource(R.string.export_vault_open_warning) else "") },
        confirmButton = { TextButton(onClick = { warnOpen = false; startSave() }) { Text(stringResource(R.string.export_open_confirm)) } },
        dismissButton = { TextButton(onClick = { warnOpen = false }) { Text(stringResource(R.string.cancel)) } },
    )
    message?.let { m -> AlertDialog(onDismissRequest = { message = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.ok)) } }) }
    pendingImport?.takeIf { it.packet != null }?.let { p ->
        PacketReview(db, session, p.packet!!, stringResource(p.summary, *p.args.toTypedArray()), onDismiss = { pendingImport = null }) { personChoice, matterChoice ->
            pendingImport = null
            val pk = p.packet
            // The packet first; calendar and contacts from the same file only after it, so the windows never overlap
            scope.launch {
                val r = session.withOpenDb { d ->
                    try { ua.mandat.data.Packet.apply(d, pk, personChoice, matterChoice, session.hidden.value.let { h -> { m: Long?, t: String -> h.item(m, t) } }) } catch (e: Exception) { ua.mandat.data.Packet.Result(0, 0, 0, 0, 0, failed = true) }
                } ?: ua.mandat.data.Packet.Result(0, 0, 0, 0, 0, failed = true)
                session.packetResult = r
                // Deleting the file is offered only once everything from it is written
                val ok = !r.failed && p.apply(session)
                if (ok && p.source != null) offerDelete = p.source
            }
        }
    }
    pendingImport?.takeIf { it.packet == null }?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.import_title)) },
            text = { Text(stringResource(p.summary, *p.args.toTypedArray())) },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    scope.launch {
                        // Deleting the file is offered only after it is written: locked or failed meanwhile, the file stays
                        val ok = p.apply(session)
                        when {
                            !ok -> message = context.getString(R.string.import_save_failed)
                            p.source != null -> offerDelete = p.source
                            else -> message = context.getString(R.string.import_done, p.count)
                        }
                    }
                }, enabled = p.count > 0) { Text(stringResource(R.string.import_go)) }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    offerDelete?.let { uri ->
        var failed by remember(uri) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { offerDelete = null },
            title = { Text(stringResource(R.string.import_done_short)) },
            text = { Text(stringResource(if (failed) R.string.vcf_delete_failed else R.string.import_delete_offer)) },
            confirmButton = {
                if (!failed) TextButton(onClick = {
                    scope.launch {
                        if (deleteDocumentQuietly(context, uri)) offerDelete = null else failed = true
                    }
                }) { Text(stringResource(R.string.vcf_delete)) }
            },
            dismissButton = { TextButton(onClick = { offerDelete = null }) { Text(stringResource(if (failed) R.string.ok else R.string.vcf_keep)) } },
        )
    }
    sealed?.let { bytes ->
        var pass by remember(bytes) { mutableStateOf("") }
        var problem by remember(bytes) { mutableStateOf<Int?>(null) }
        var opening by remember(bytes) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        val stop = remember(bytes) { java.util.concurrent.atomic.AtomicBoolean(false) }
        fun close() { stop.set(true); opening?.cancel(); opening = null; bytes.fill(0); sealed = null }
        AlertDialog(
            onDismissRequest = { close() },
            title = { Text(stringResource(R.string.import_mandat)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoAutofill()
                    OutlinedTextField(pass, { pass = it; problem = null }, label = { Text(stringResource(R.string.password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        isError = problem != null, supportingText = { problem?.let { Text(stringResource(it)) } }, modifier = Modifier.fillMaxWidth())
                    if (opening != null) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(8.dp)); Text(stringResource(R.string.export_working)) }
                }
            },
            confirmButton = {
                TextButton(enabled = pass.isNotEmpty() && opening == null, onClick = {
                    val given = pass
                    pass = ""
                    opening = scope.launch {
                        try {
                            when (val result = withContext(Dispatchers.Default) { openMandatExport(bytes, given) { stop.get() } }) {
                                is Opened.Ok -> { bytes.fill(0); sealed = null; pendingImport = result.pending }
                                is Opened.Problem -> problem = result.text
                            }
                        } finally {
                            opening = null
                        }
                    }
                }) { Text(stringResource(R.string.import_open)) }
            },
            // Cancel always works, also while a file is being opened
            dismissButton = { TextButton(onClick = { close() }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A ZIP password must be longer: the suggested six words always are. */
const val MIN_ZIP_PASSWORD_LENGTH = 24
const val PROTECT_ZIP = 0
const val PROTECT_MANDAT = 1
const val PROTECT_OPEN = 2

/** Extension of Mandat's own encrypted export file. */
const val MANDAT_EXPORT_EXT = "bin"

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun tableLabel(t: String): Int = when (t) {
    "matters" -> R.string.tab_matters
    "delegations" -> R.string.delegations
    "promises" -> R.string.promises
    "deadlines" -> R.string.deadlines
    "events" -> R.string.events
    "money" -> R.string.budget_title
    else -> R.string.tab_people
}

/** Files packed into one ZIP in memory (no encryption of its own). */
private fun zipInMemory(files: List<Pair<String, ByteArray>>): ByteArray {
    val buf = ua.mandat.data.WipingBuffer()
    try {
        java.util.zip.ZipOutputStream(buf).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return buf.toByteArray()
    } finally {
        // The stream's own copy of the plain text is cleared too, not left for the garbage collector
        buf.wipe()
    }
}

/** A byte buffer that can clear what it held. */

/**
 * Gathers what was chosen (leaving out what trip mode and the lens hide), and writes one file.
 * Any failure, even running out of memory, ends without a crash and removes the new file.
 */
private suspend fun runExport(context: Context, session: Session, plan: ExportPlan, uri: Uri, password: CharArray?, own: Boolean, tooBig: () -> Unit, existing: () -> Unit): Boolean {
    // Never written over a file that already holds something (an older export picked to "replace")
    if (!isNewDocument(context, uri)) { existing(); return false }
    var files: List<Pair<String, ByteArray>> = emptyList()
    // Only a file Mandat began to write into is deleted after a failure: the owner may have
    // picked an existing file to replace, and that must stay as it was
    var touched = false
    fun discard() {
        // An empty file is the one the save dialog just made, never one the owner chose to replace
        val empty = !touched && try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c -> c.moveToFirst() && !c.isNull(0) && c.getLong(0) == 0L } == true
        } catch (x: Exception) { false }
        if (touched || empty) try { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (x: Exception) { }
    }
    return try {
        // For Mandat's own file only contacts and the calendar are even built
        val wanted = if (own) ExportPlan().also { p ->
            p.people = plan.people; p.peopleCategory = plan.peopleCategory; p.book = plan.book; p.bookGroup = plan.bookGroup
            p.calendar = plan.calendar; p.calendarSpan = plan.calendarSpan; p.pdf = false
            p.period = plan.period; p.periodFrom = plan.periodFrom; p.periodTo = plan.periodTo
            p.packet = plan.packet; p.packetFile = true; p.matterId = plan.matterId
            p.packetContacts = plan.packetContacts; p.packetStances = plan.packetStances
        } else plan
        files = session.withOpenDb { db -> buildFiles(context, db, session.hiddenMatters.value, wanted) }.orEmpty()
        // Mandat's own file carries only what Mandat can take back in
        if (own) files = files.filter { (name, bytes) -> (name.endsWith(".vcf") || name.endsWith(".ics") || name == ua.mandat.data.Packet.FILE).also { keep -> if (!keep) bytes.fill(0) } }
        if (files.isEmpty()) { discard(); return false }
        // Mandat's own file is sealed before anything is written, so a file too big to open again is never made
        val sealed: ByteArray? = if (password != null && own) {
            // The same encryption as a backup (Argon2id and AES-256-GCM); file names are hidden too
            val inner = zipInMemory(files)
            val marked = ua.mandat.backup.BackupCrypto.EXPORT_MARK + inner
            inner.fill(0)
            try { ua.mandat.backup.BackupCrypto.encrypt(marked, password) } finally { marked.fill(0) }
        } else null
        if (sealed != null && sealed.size > ua.mandat.backup.BackupCrypto.MAX_FILE_BYTES) { tooBig(); discard(); return false }
        val written = context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            touched = true
            when {
                sealed != null -> out.write(sealed)
                password != null -> {
                    // A ZIP keeps its entry names readable even with a password. Obsidian notes are named after
                    // people and matters, so they go inside one more archive and only "obsidian-vault.zip" shows
                    val vault = files.filter { it.first.startsWith(OBSIDIAN_ROOT) }
                    val vaultZip = if (vault.isEmpty()) null else zipInMemory(vault)
                    val entries = files.filterNot { it.first.startsWith(OBSIDIAN_ROOT) } + listOfNotNull(vaultZip?.let { "obsidian-vault.zip" to it })
                    // ZIP with AES-256: a standard that 7-Zip, Keka and similar apps open with the password
                    val params = net.lingala.zip4j.model.ZipParameters().apply {
                        isEncryptFiles = true
                        encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
                        aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
                    }
                    try {
                        net.lingala.zip4j.io.outputstream.ZipOutputStream(out, password).use { zip ->
                            entries.forEach { (name, bytes) ->
                                params.fileNameInZip = name
                                zip.putNextEntry(params)
                                zip.write(bytes)
                                zip.closeEntry()
                            }
                        }
                    } finally { vaultZip?.fill(0) }
                }
                files.size == 1 -> out.write(files.first().second)
                else -> out.write(zipInMemory(files))
            }
            true
        } ?: false
        if (!written) discard()
        written
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) { discard(); throw e }
        // Nothing half-written should stay as if it were complete
        discard()
        false
    } finally {
        files.forEach { it.second.fill(0) }
    }
}

private suspend fun buildFiles(context: Context, db: MandatDb, hidden: Set<Long>, plan: ExportPlan): List<Pair<String, ByteArray>> {
    fun visible(matterId: Long?) = matterId == null || matterId !in hidden
    val out = ArrayList<Pair<String, ByteArray>>()
    fun add(name: String, text: String) { out += name to text.toByteArray(Charsets.UTF_8) }
    fun addDoc(base: String, markdown: String) {
        add("$base.md", markdown)
        if (plan.pdf) out += "$base.pdf" to PdfText.render(markdown)
    }
    // A person known only through hidden matters stays hidden too, worked out as on the screens:
    // every link they have (a matter, a task, an event, media, an alliance) leads to a hidden matter
    val onlyHidden: Set<Long> = ua.mandat.data.Hiding.people(hidden, db.hiding().personMatter().first())
    // Free text not tied to a matter that names a hidden one stays out, as on the screens, in the AI and the Obsidian vault
    val names = if (hidden.isEmpty()) ua.mandat.data.HiddenNames.NONE else ua.mandat.data.HiddenNames(db.matters().observeAll().first().filter { it.id in hidden }.map { it.title })
    fun free(matterId: Long?, vararg text: String) = matterId != null || !names.namedAny(*text)
    // A person's own notes and contact line naming a hidden matter are left blank
    val people = db.people().all().filter { it.id !in onlyHidden }.map { p ->
        if (free(null, p.notes) && free(null, p.contact)) p else p.copy(notes = if (free(null, p.notes)) p.notes else "", contact = if (free(null, p.contact)) p.contact else "")
    }
    val titles = db.matters().observeAllTitles().first().filter { visible(it.id) }.associate { it.id to it.title }
    val nameOf = people.associate { it.id to it.name }
    fun catName(k: String): String = when {
        k.startsWith("OTHER:") -> k.removePrefix("OTHER:")
        else -> ua.mandat.data.PersonCategory.of(k)?.let { context.getString(it.label()) } ?: k
    }
    if (plan.people) {
        val chosen = people.filter { p ->
            when (val c = plan.peopleCategory) { null -> true; "TEAM" -> p.teamRole != null; else -> c in p.allCategories }
        }
        add("people.vcf", Export.vcard(chosen.map { Export.personToV(it, it.allCategories.map(::catName)) }))
    }
    if (plan.book) {
        val chosen = db.contacts().all().filter { (plan.bookGroup == null || it.label == plan.bookGroup) && free(null, it.name, it.organization, it.jobTitle, it.notes, it.label) }
        add("address-book.vcf", Export.vcard(chosen.map(Export::contactToV)))
    }
    val today = Dates.today()
    // The chosen period, applied to every kind of record by its own dates
    val span = plan.span(today, db.settings().get(ua.mandat.data.SETTING_CADENCE_START)?.value?.toLongOrNull())
    fun inSpan(day: Long?) = span == null || (day != null && day in span)
    fun msIn(ms: Long?) = ms != null && inSpan(Dates.dayOf(ms))
    fun taskIn(created: Long, closed: Long?, due: Long?) = span == null || msIn(created) || msIn(closed) || (due != null && inSpan(due))
    val movedIds = if (span == null) emptySet() else db.history().observeAll().first().filter { it.day in span }.map { it.matterId }.toSet()
    val events = db.events().observeAll().first().filter { visible(it.matterId) && free(it.matterId, it.title, it.notes, it.outcome, it.place) && (span == null || (it.day <= span.to && it.lastDay >= span.from)) }
    if (plan.calendar) {
        val chosen = if (span != null) events else events.filter { e ->
            when (plan.calendarSpan) { 1 -> e.lastDay >= today; 2 -> e.day >= today - 365; else -> true }
        }
        add("calendar.ics", Export.ics(chosen, { k -> context.getString(ua.mandat.data.EventKind.of(k).label()) }))
    }
    if ("matters" in plan.tables) {
        val roles = db.roles().observeAll().first().associate { it.id to it.name }
        add("matters.csv", Export.csv(
            listOf("title", "type", "role", "attention", "status", "next_step", "goal", "description", "created", "updated"),
            db.matters().observeAll().first().filter { m ->
                visible(m.id) && (span == null || msIn(m.createdAt) || msIn(m.updatedAt) || m.id in movedIds)
            }.map { m ->
                listOf(m.title, context.getString(ua.mandat.data.MatterType.of(m.type).label()), m.roleId?.let(roles::get).orEmpty(),
                    context.getString(ua.mandat.data.Attention.of(m.attention).label()), context.getString(ua.mandat.data.MatterStatus.of(m.status).label()),
                    m.nextStep, m.goal, m.description, Export.moment(m.createdAt), Export.moment(m.updatedAt))
            },
        ))
    }
    if ("delegations" in plan.tables) add("delegations.csv", Export.csv(
        listOf("what", "to_whom", "matter", "due", "status", "purpose", "done_means", "created", "closed"),
        db.delegations().observeAll().first().filter { visible(it.matterId) && free(it.matterId, it.what, it.purpose, it.doneMeans) && taskIn(it.createdAt, it.closedAt, it.due) }.map { d ->
            listOf(d.what, d.personId?.let(nameOf::get).orEmpty(), d.matterId?.let(titles::get).orEmpty(), Export.day(d.due),
                context.getString(TaskStatus.of(d.status).delegationLabel()), d.purpose, d.doneMeans, Export.moment(d.createdAt), Export.moment(d.closedAt))
        },
    ))
    if ("promises" in plan.tables) add("promises.csv", Export.csv(
        listOf("what", "to_whom", "publicity", "origin", "risk", "matter", "due", "status", "promised", "closed", "source", "note"),
        db.promises().observeAll().first().filter { visible(it.matterId) && free(it.matterId, it.what, it.toWhom, it.source, it.statusNote) && taskIn(it.createdAt, it.closedAt, it.due) }.map { p ->
            listOf(p.what, if (p.personId != null) nameOf[p.personId].orEmpty() else p.toWhom, context.getString(p.level.label()),
                ua.mandat.data.PromiseOrigin.of(p.origin)?.let { context.getString(it.label()) }.orEmpty(), p.risk?.toString().orEmpty(),
                p.matterId?.let(titles::get).orEmpty(), Export.day(p.due), context.getString(TaskStatus.of(p.status).promiseLabel()),
                Export.moment(p.createdAt), Export.moment(p.closedAt), p.source, p.statusNote)
        },
    ))
    if ("deadlines" in plan.tables) add("deadlines.csv", Export.csv(
        listOf("title", "day", "kind", "basis", "matter", "status"),
        db.deadlines().observeAll().first().filter { visible(it.matterId) && free(it.matterId, it.title, it.basis) && taskIn(it.createdAt, it.closedAt, it.day) }.map { d ->
            listOf(d.title, Export.day(d.day), context.getString(if (ua.mandat.data.DeadlineKind.of(d.kind) == ua.mandat.data.DeadlineKind.HARD) R.string.deadline_hard else R.string.deadline_soft),
                d.basis, d.matterId?.let(titles::get).orEmpty(), context.getString(TaskStatus.of(d.status).delegationLabel()))
        },
    ))
    if ("events" in plan.tables) add("events.csv", Export.csv(
        listOf("title", "day", "last_day", "start", "end", "kind", "place", "matter", "notes", "outcome"),
        events.map { e ->
            listOf(e.title, Export.day(e.day), Export.day(e.endDay), timeText(e.startMin), timeText(e.endMin),
                context.getString(ua.mandat.data.EventKind.of(e.kind).label()), e.place, e.matterId?.let(titles::get).orEmpty(), e.notes, e.outcome)
        },
    ))
    if ("money" in plan.tables) add("budget.csv", Export.csv(
        listOf("day", "kind", "category", "amount", "currency", "uah", "note", "charity", "to_whom", "awaiting_refund", "refunded"),
        db.money().observeAll().first().filter { visible(it.matterId) && free(it.matterId, it.note, it.toWhom, it.category) && inSpan(it.day) }.map { m ->
            listOf(Export.day(m.day), m.kind, m.category, Export.money(m.amount), m.currency, Export.money(m.uah), m.note, m.charityKind.orEmpty(), m.toWhom,
                if (m.awaitingRefund) "1" else "0", if (m.refunded) "1" else "0")
        },
    ))
    if ("people" in plan.tables) add("people.csv", Export.csv(
        listOf("name", "organization", "position", "contact", "categories", "team_role", "notes"),
        people.map { p -> listOf(p.name, p.organization, p.jobTitle, p.contact, p.allCategories.joinToString("; ", transform = ::catName),
            ua.mandat.data.TeamRole.of(p.teamRole)?.let { context.getString(it.label()) }.orEmpty(), p.notes) },
    ))
    if (plan.diary) {
        val hiddenReviews = db.reviews().observeLinks().first()
            .filter { (it.kind == "matter" && it.refId in hidden) || (it.kind == "person" && it.refId in onlyHidden) }.map { it.reviewId }.toSet()
        val reviews = db.reviews().observeAll().first().filter { it.id !in hiddenReviews && free(null, it.progress) && inSpan(it.day) }.sortedBy { it.day }
        addDoc("diary", buildString {
            append("# ").append(context.getString(R.string.diary_title)).append("\n\n")
            reviews.filter { it.progress.isNotBlank() || it.mood != null || it.energy != null }.forEach { r ->
                append("## ").append(Export.day(r.day)).append("\n\n")
                val feel = listOfNotNull(r.energy?.let { context.getString(R.string.diary_energy) + ": $it/5" }, r.mood?.let { context.getString(R.string.diary_mood) + ": $it/5" })
                if (feel.isNotEmpty()) append("_").append(feel.joinToString(" · ")).append("_\n\n")
                if (r.progress.isNotBlank()) append(Export.md(r.progress)).append("\n\n")
            }
        })
    }
    plan.matterId?.takeIf { visible(it) }?.let { id -> db.matters().get(id)?.let { m -> addDoc("matter", matterDossier(context, db, m, nameOf, hidden)) } }
    plan.textId?.let { id -> db.texts().get(id) }?.takeIf { visible(it.matterId) }?.let { t ->
        // Word for editing elsewhere; PDF and Markdown as with other documents
        out += "text.docx" to ua.mandat.data.Docx.build("# " + t.title + "\n\n" + t.body)
        val md = buildString {
            append("# ").append(Export.md(t.title)).append("\n\n")
            t.body.lines().forEach { l ->
                when {
                    l.startsWith("# ") -> append("# ").append(Export.md(l.removePrefix("# ")))
                    l.startsWith("## ") -> append("## ").append(Export.md(l.removePrefix("## ")))
                    l.startsWith("- ") -> append("- ").append(Export.md(l.removePrefix("- ")))
                    else -> append(Export.md(l))
                }
                append("\n")
            }
        }
        addDoc("text", md)
    }
    plan.briefId?.let { id -> people.firstOrNull { it.id == id } }?.let { p -> addDoc("brief", briefDocument(context, db, p, hidden, titles, names)) }
    if (plan.packet && plan.packetFile) {
        // Never a sensitive or hidden matter, nor anything tied to one or naming one in its text
        val all = db.matters().observeAll().first()
        val excluded = all.filter { it.sensitive || it.id in hidden }.map { it.id }.toSet()
        val excludedNames = ua.mandat.data.HiddenNames(all.filter { it.id in excluded }.map { it.title })
        fun clean(vararg text: String) = !excludedNames.namedAny(*text)
        val dgAll = db.delegations().observeAll().first().filter { it.matterId != null && it.matterId !in excluded && clean(it.what, it.purpose, it.doneMeans) && taskIn(it.createdAt, it.closedAt, it.due) }
        val prAll = db.promises().observeAll().first().filter { it.matterId != null && it.matterId !in excluded && clean(it.what, it.toWhom, it.source) && taskIn(it.createdAt, it.closedAt, it.due) }
        val dlAll = db.deadlines().observeAll().first().filter { it.matterId != null && it.matterId !in excluded && clean(it.title, it.basis) && taskIn(it.createdAt, it.closedAt, it.day) }
        val evAll = events.filter { it.matterId != null && it.matterId !in excluded && clean(it.title, it.notes, it.outcome, it.place) }
        // Matters: the one chosen; or those that moved in the period, and those its tasks and events belong to.
        // Records without a matter never go into a packet: a packet is about matters
        val ids: Set<Long> = when (val one = plan.matterId) {
            null -> all.filter { it.id !in excluded && (span == null || msIn(it.createdAt) || msIn(it.updatedAt) || it.id in movedIds) }.map { it.id }.toSet() +
                (dgAll.mapNotNull { it.matterId } + prAll.mapNotNull { it.matterId } + dlAll.mapNotNull { it.matterId } + evAll.mapNotNull { it.matterId })
            else -> if (one in excluded) emptySet() else setOf(one)
        }
        // A matter whose own text names a sensitive or hidden one keeps only its title and kind
        val matters = all.filter { it.id in ids }.map { m -> if (clean(m.description, m.goal, m.nextStep)) m else m.copy(description = "", goal = "", nextStep = "") }
            .filter { clean(it.title) }
        val dg = dgAll.filter { it.matterId in ids }
        val pr = prAll.filter { it.matterId in ids }
        val dl = dlAll.filter { it.matterId in ids }
        val ev = evAll.filter { it.matterId in ids }
        val evIds = ev.map { it.id }.toSet()
        val eventPeople = db.events().observeLinks().first().filter { it.eventId in evIds }
        // Stances are the owner's own judgement: they go only when chosen
        val links = db.people().observeLinks().first().filter { it.matterId in ids }.map { if (plan.packetStances) it else it.copy(stance = null) }
        // People who take part in what goes in; never private notes, contacts only when chosen
        val personIds = (links.map { it.personId } + dg.mapNotNull { it.personId } + pr.mapNotNull { it.personId } + eventPeople.map { it.personId }).toSet()
        val persons = people.filter { it.id in personIds }.map { if (plan.packetContacts) it else it.copy(contact = "") }
        val packetPeople = persons.map { it.id }.toSet()
        val roles = db.roles().observeAll().first().associate { it.id to it.name }
        val packet = ua.mandat.data.Packet.P(matters, persons, links.filter { it.personId in packetPeople }, dg, pr, dl, ev,
            eventPeople.filter { it.personId in packetPeople }, roles.filterKeys { k -> matters.any { it.roleId == k } })
        if (!packet.isEmpty) add(ua.mandat.data.Packet.FILE, ua.mandat.data.Packet.encode(packet))
    }
    if (plan.obsidian) obsidianVault(context, db, hidden, onlyHidden, plan.vaultScope, span, plan.allPeople, names).forEach { (name, text) -> add(name, text) }
    // A whole copy of everything is never cut by a period
    if (plan.everything && hidden.isEmpty() && span == null) out += "everything.json" to ua.mandat.backup.BackupData.dump(db)
    return out
}

/** The brief before meeting a person, as a readable document; hidden matters and what is tied to them stay out. */
private suspend fun briefDocument(context: Context, db: MandatDb, p: ua.mandat.data.Person, hidden: Set<Long>, titles: Map<Long, String>, names: ua.mandat.data.HiddenNames): String = buildString {
    fun visible(id: Long?) = id == null || id !in hidden
    // Not tied to a matter but naming a hidden one: left out too
    fun ok(id: Long?, vararg text: String) = visible(id) && !(id == null && names.namedAny(*text))
    val today = Dates.today()
    append("# ").append(Export.md(p.name)).append("\n\n")
    listOf(p.jobTitle, p.organization).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotEmpty() }?.let { append(Export.md(it)).append("\n\n") }
    if (p.contact.isNotBlank()) append(Export.md(p.contact)).append("\n\n")
    if (p.notes.isNotBlank()) append(Export.md(p.notes)).append("\n\n")
    val events = db.events().observeForPerson(p.id).first().filter { ok(it.matterId, it.title, it.outcome) }
    events.filter { it.lastDay >= today }.minByOrNull { it.day }?.let { e ->
        append("## ").append(context.getString(R.string.brief_next)).append("\n\n- ").append(Export.day(e.day)).append(" — ").append(Export.md(e.title)).append("\n\n")
    }
    val past = events.filter { it.lastDay < today }.sortedByDescending { it.day }.take(3)
    if (past.isNotEmpty()) {
        append("## ").append(context.getString(R.string.brief_last)).append("\n\n")
        past.forEach { e -> append("- ").append(Export.day(e.day)).append(" — ").append(Export.md(e.title))
            .append(e.outcome.takeIf { it.isNotBlank() }?.let { " ✓ " + Export.md(it) }.orEmpty()).append("\n") }
        append("\n")
    }
    val matters = db.people().observeMattersOf(p.id).first().filter { visible(it.matterId) }
    if (matters.isNotEmpty()) {
        val next = db.matters().observeAll().first().associate { it.id to it.nextStep }
        append("## ").append(context.getString(R.string.agenda_matters)).append("\n\n")
        matters.forEach { m -> append("- ").append(Export.md(m.title))
            .append(ua.mandat.data.Stance.of(m.stance)?.let { " — " + context.getString(it.label()) }.orEmpty())
            .append(next[m.matterId]?.takeIf { it.isNotBlank() }?.let { " → " + Export.md(it) }.orEmpty()).append("\n") }
        append("\n")
    }
    val promises = db.promises().observeForPerson(p.id).first().filter { ok(it.matterId, it.what) && TaskStatus.of(it.status) == TaskStatus.OPEN }
    if (promises.isNotEmpty()) {
        append("## ").append(context.getString(R.string.agenda_promises)).append("\n\n")
        promises.forEach { x -> append("- ").append(Export.md(x.what)).append(x.due?.let { " (" + Export.day(it) + ")" }.orEmpty()).append("\n") }
        append("\n")
    }
    val asked = db.delegations().observeForPerson(p.id).first().filter { ok(it.matterId, it.what) && TaskStatus.of(it.status) == TaskStatus.OPEN }
    if (asked.isNotEmpty()) {
        append("## ").append(context.getString(R.string.brief_asked)).append("\n\n")
        asked.forEach { d -> append("- ").append(Export.md(d.what)).append(d.due?.let { " (" + Export.day(it) + ")" }.orEmpty())
            .append(d.matterId?.let(titles::get)?.let { " · " + Export.md(it) }.orEmpty()).append("\n") }
    }
}

/** One matter as a readable document: its card, people, deadlines, delegations, promises, events. */
private suspend fun matterDossier(context: Context, db: MandatDb, m: ua.mandat.data.Matter, nameOf: Map<Long, String>, hidden: Set<Long>): String = buildString {
    fun line(label: Int, value: String) { if (value.isNotBlank()) append("- **").append(context.getString(label)).append(":** ").append(Export.md(value)).append("\n") }
    append("# ").append(Export.md(m.title)).append("\n\n")
    line(R.string.matter_type, context.getString(ua.mandat.data.MatterType.of(m.type).label()))
    line(R.string.matter_status, context.getString(ua.mandat.data.MatterStatus.of(m.status).label()))
    line(R.string.matter_goal, m.goal)
    line(R.string.matter_next_step, m.nextStep)
    if (m.description.isNotBlank()) append("\n").append(Export.md(m.description)).append("\n")
    val linked = db.people().observeForMatter(m.id).first()
    if (linked.isNotEmpty()) {
        append("\n## ").append(context.getString(R.string.people_in_matter)).append("\n\n")
        linked.forEach { append("- ").append(Export.md(it.name)).append(ua.mandat.data.Stance.of(it.stance)?.let { s -> " — " + context.getString(s.label()) }.orEmpty()).append("\n") }
    }
    val dls = db.deadlines().observeForMatter(m.id).first()
    if (dls.isNotEmpty()) {
        append("\n## ").append(context.getString(R.string.deadlines)).append("\n\n")
        dls.forEach { append("- ").append(Export.day(it.day)).append(" — ").append(Export.md(it.title)).append("\n") }
    }
    val ds = db.delegations().observeForMatter(m.id).first()
    if (ds.isNotEmpty()) {
        append("\n## ").append(context.getString(R.string.delegations)).append("\n\n")
        ds.forEach { d -> append("- ").append(Export.md(d.what)).append(d.personId?.let(nameOf::get)?.let { " — " + Export.md(it) }.orEmpty())
            .append(d.due?.let { " (" + Export.day(it) + ")" }.orEmpty()).append(" · ").append(context.getString(TaskStatus.of(d.status).delegationLabel())).append("\n") }
    }
    val ps = db.promises().observeForMatter(m.id).first()
    if (ps.isNotEmpty()) {
        append("\n## ").append(context.getString(R.string.promises)).append("\n\n")
        ps.forEach { p -> append("- ").append(Export.md(p.what)).append(" · ").append(context.getString(TaskStatus.of(p.status).promiseLabel())).append("\n") }
    }
    val es = db.events().observeForMatter(m.id).first()
    if (es.isNotEmpty()) {
        append("\n## ").append(context.getString(R.string.events)).append("\n\n")
        es.sortedBy { it.day }.forEach { e -> append("- ").append(Export.day(e.day)).append(" — ").append(Export.md(e.title))
            .append(e.outcome.takeIf { it.isNotBlank() }?.let { " ✓ " + Export.md(it) }.orEmpty()).append("\n") }
    }
}

/** A file read for import, waiting for confirmation. */
/** [args] fill the [summary] text (by default the count); [source] is an open file that may be deleted afterwards. */
/** [apply] saves what was read and says whether it was written; only then is deleting the file offered. */
class PendingImport(val summary: Int, val count: Int, val args: List<Int> = listOf(count), val source: Uri? = null, val packet: ua.mandat.data.Packet.P? = null, val apply: suspend (Session) -> Boolean)

private const val MAX_IMPORT_BYTES = 20L * 1024 * 1024

/** The whole file, at most [MAX_IMPORT_BYTES] (checked while reading, not only by the reported size). */
private fun readBytes(context: Context, uri: Uri): ByteArray? = try {
    val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    if (size > MAX_IMPORT_BYTES) null
    else context.contentResolver.openInputStream(uri)?.use { input ->
        // Every working copy of the file is cleared; only the returned one remains, cleared by the caller
        val buf = ua.mandat.data.WipingBuffer()
        val chunk = ByteArray(64 * 1024)
        try {
            var total = 0L
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_IMPORT_BYTES) throw java.io.IOException()
                buf.write(chunk, 0, n)
            }
            buf.toByteArray()
        } finally {
            buf.wipe()
            chunk.fill(0)
        }
    }
} catch (e: Exception) {
    null
} catch (e: OutOfMemoryError) {
    null
}

/** Saves events read from a calendar file, skipping those already there (same title, day and time). */
private suspend fun importEvents(s: Session, items: List<ua.mandat.data.IcsReader.Item>): Boolean = s.withOpenDb { db ->
    try {
        val now = System.currentTimeMillis()
        // Compared only with what is shown now: a skipped event must never tell that a hidden one exists
        val h = s.hidden.value
        val existing = db.events().observeAll().first().filterNot { h.item(it.matterId, it.title, it.notes) }.map { Triple(it.title, it.day, it.startMin) }.toSet()
        db.withTransaction {
            items.filter { Triple(it.title, it.day, it.startMin) !in existing }.forEach { i ->
                ua.mandat.data.cleanEvent(ua.mandat.data.Event(title = i.title, day = i.day, endDay = i.endDay, startMin = i.startMin, endMin = i.endMin,
                    kind = ua.mandat.data.EventKind.OTHER.name, place = i.place, notes = i.notes, createdAt = now, updatedAt = now))?.let { db.events().insert(it) }
            }
        }
        true
    } catch (e: Exception) {
        false
    }
} == true

private const val MAX_INNER_FILES = 64
private const val MAX_INNER_TOTAL = 40L * 1024 * 1024

/**
 * Opens Mandat's own export file with its password: calendars and contacts inside can be taken
 * in; tables and documents are for reading elsewhere and are skipped. Null for a wrong password
 * or a damaged file. Everything stays in memory.
 */
/** What opening Mandat's own file gave: something to import, or why not (a text to show). */
sealed interface Opened {
    class Ok(val pending: PendingImport) : Opened
    class Problem(val text: Int) : Opened
}

private fun openMandatExport(file: ByteArray, password: String, stop: () -> Boolean): Opened {
    val damaged = Opened.Problem(R.string.import_mandat_wrong)
    val inner = try {
        ua.mandat.backup.BackupCrypto.decrypt(file, password)
    } catch (e: ua.mandat.backup.BackupCrypto.NotABackup) {
        return Opened.Problem(R.string.import_mandat_not)
    } catch (e: Exception) {
        return damaged
    } catch (e: OutOfMemoryError) {
        return damaged
    }
    return try {
        // A backup opened here by mistake is named as such
        if (!ua.mandat.backup.BackupCrypto.startsWithExportMark(inner)) {
            return Opened.Problem(if (inner.isNotEmpty() && inner[0] == '{'.code.toByte()) R.string.import_mandat_is_backup else R.string.import_mandat_not)
        }
        val mark = ua.mandat.backup.BackupCrypto.EXPORT_MARK.size
        val events = ArrayList<ua.mandat.data.IcsReader.Item>()
        val contacts = ArrayList<ua.mandat.data.Contact>()
        var packet: ua.mandat.data.Packet.P? = null
        var skipped = 0
        var total = 0L
        val chunk = ByteArray(64 * 1024)
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(inner, mark, inner.size - mark)).use { zip ->
            var count = 0
            // Every byte unpacked counts toward the limit, also of entries that are skipped,
            // so a small file cannot make the phone unpack gigabytes
            fun drain(sink: java.io.OutputStream?) {
                while (true) {
                    if (stop()) throw java.io.InterruptedIOException()
                    val n = zip.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > MAX_INNER_TOTAL) throw java.io.IOException()
                    sink?.write(chunk, 0, n)
                }
            }
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > MAX_INNER_FILES) break
                val ext = entry.name.substringAfterLast('/').substringAfterLast('.', "").lowercase()
                // A packet of matters and tasks from a colleague's Mandat
                if (!entry.isDirectory && entry.name == ua.mandat.data.Packet.FILE && packet == null) {
                    val buf = ua.mandat.data.WipingBuffer()
                    try { drain(buf); packet = buf.toByteArray().let { b -> try { ua.mandat.data.Packet.decode(String(b, Charsets.UTF_8)) } finally { b.fill(0) } } } finally { buf.wipe() }
                    continue
                }
                if (entry.isDirectory || ext !in setOf("ics", "vcf")) { skipped++; drain(null); continue }
                val buf = ua.mandat.data.WipingBuffer()
                val text = try { drain(buf); buf.toByteArray().let { b -> String(b, Charsets.UTF_8).also { b.fill(0) } } } finally { buf.wipe() }
                when (ext) {
                    "ics" -> events += ua.mandat.data.IcsReader.read(text).take(ua.mandat.data.IcsReader.MAX_EVENTS - events.size)
                    else -> contacts += ua.mandat.data.VCard.read(text.reader().buffered(), stop = stop).contacts.take(ua.mandat.data.VCard.MAX_CONTACTS - contacts.size)
                }
            }
        }
        val pk = packet
        // A packet is reviewed in its own sheet (matches confirmed there); calendar and contacts come along after it
        if (pk != null) Opened.Ok(PendingImport(R.string.import_packet_summary, pk.matters.size + pk.tasks + pk.events.size + events.size + contacts.size,
            args = listOf(pk.matters.size, pk.people.size, pk.tasks, pk.events.size, events.size + contacts.size), packet = pk) { s ->
            val ok = events.isEmpty() || importEvents(s, events)
            if (contacts.isNotEmpty()) s.previewContacts(contacts, null)
            ok
        })
        else Opened.Ok(PendingImport(R.string.import_mandat_summary, events.size + contacts.size, args = listOf(events.size, contacts.size, skipped)) { s ->
            val ok = events.isEmpty() || importEvents(s, events)
            if (contacts.isNotEmpty()) s.previewContacts(contacts, null)
            ok
        })
    } catch (e: Exception) {
        damaged
    } catch (e: OutOfMemoryError) {
        damaged
    } finally {
        inner.fill(0)
    }
}

/** Deletes a file through its provider, off the screen thread; false when that is not possible. */
suspend fun deleteDocumentQuietly(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    try { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (e: Exception) { false }
}

private fun readImport(context: Context, kind: String, uri: Uri): PendingImport? = try {
    val bytes = readBytes(context, uri)
    if (bytes == null) null
    else {
        val text = String(bytes, Charsets.UTF_8)
        bytes.fill(0)
        when (kind) {
            "ics" -> {
                val items = ua.mandat.data.IcsReader.read(text)
                PendingImport(R.string.import_ics_summary, items.size, source = uri) { s -> importEvents(s, items) }
            }
            "notes" -> {
                val notes = ua.mandat.data.notesFromText(text)
                PendingImport(R.string.import_notes_summary, notes.size, source = uri) { s ->
                    s.withOpenDb { db ->
                        try {
                            val now = System.currentTimeMillis()
                            db.withTransaction { notes.forEachIndexed { i, n -> db.inbox().insert(ua.mandat.data.InboxItem(text = n, createdAt = now + i)) } }
                            true
                        } catch (e: Exception) {
                            false
                        }
                    } == true
                }
            }
            "csv" -> {
                val list = ua.mandat.data.ContactCsv.contacts(text)
                PendingImport(R.string.import_csv_summary, list.size) { s -> s.previewContacts(list, uri); true }
            }
            else -> null
        }
    }
} catch (e: Exception) {
    null
} catch (e: OutOfMemoryError) {
    null
}

/**
 * A simple PDF from Markdown text, made by the phone itself (no library): A4 pages, headings in
 * bold, paragraphs wrapped, a page number at the bottom. Readable and printable anywhere.
 */
object PdfText {
    private const val W = 595
    private const val H = 842
    private const val M = 48f

    fun render(markdown: String): ByteArray {
        val doc = android.graphics.pdf.PdfDocument()
        val body = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = android.graphics.Color.BLACK }
        val h1 = android.text.TextPaint(body).apply { textSize = 18f; isFakeBoldText = true }
        val h2 = android.text.TextPaint(body).apply { textSize = 14f; isFakeBoldText = true }
        val foot = android.text.TextPaint(body).apply { textSize = 9f; color = android.graphics.Color.GRAY }
        val width = (W - 2 * M).toInt()
        var pageNo = 0
        var page: android.graphics.pdf.PdfDocument.Page? = null
        var y = M
        fun newPage() {
            page?.let { p -> p.canvas.drawText(pageNo.toString(), W / 2f, H - 24f, foot); doc.finishPage(p) }
            pageNo++
            page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M
        }
        newPage()
        // Markup first (bold, italics), then the escaped characters back to themselves
        fun plain(s: String) = s.replace("**", "").replace(Regex("(?<!\\\\)_"), "").replace(Regex("\\\\([*_#`<>\\[\\]!\\\\+=~.)-])"), "$1")
        for (raw in markdown.split("\n")) {
            val (paint, text, gap) = when {
                raw.startsWith("# ") -> Triple(h1, plain(raw.removePrefix("# ")), 10f)
                raw.startsWith("## ") -> Triple(h2, plain(raw.removePrefix("## ")), 8f)
                raw.isBlank() -> Triple(body, "", 4f)
                else -> Triple(body, plain(raw).replace(Regex("^- "), "• "), 2f)
            }
            if (text.isEmpty()) { y += gap; continue }
            val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, width).build()
            for (line in 0 until layout.lineCount) {
                val lh = (layout.getLineBottom(line) - layout.getLineTop(line)).toFloat()
                if (y + lh > H - M) newPage()
                val c = page!!.canvas
                c.save()
                c.translate(M, y - layout.getLineTop(line))
                c.clipRect(0f, layout.getLineTop(line).toFloat(), width.toFloat(), layout.getLineBottom(line).toFloat())
                layout.draw(c)
                c.restore()
                y += lh
            }
            y += gap
        }
        page?.let { p -> p.canvas.drawText(pageNo.toString(), W / 2f, H - 24f, foot); doc.finishPage(p) }
        val out = ua.mandat.data.WipingBuffer()
        try {
            doc.writeTo(out)
            return out.toByteArray()
        } finally {
            doc.close()
            out.wipe()
        }
    }
}

/** The folder Obsidian notes are written under in an export. */
internal const val OBSIDIAN_ROOT = "Mandat/"


/**
 * Reviewing a colleague's packet before it is added: for each person or matter that may already be
 * here, the owner says whether it is the same one or a new one. A person is preselected as the
 * same only when the name and organisation both agree; a matter never by itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PacketReview(db: MandatDb, session: Session, pk: ua.mandat.data.Packet.P, summary: String, onDismiss: () -> Unit, onAdd: (Map<Long, Long?>, Map<Long, Long?>) -> Unit) {
    val plan by androidx.compose.runtime.produceState<ua.mandat.data.Packet.Plan?>(null, pk) {
        // What is hidden now (trip mode, lens) is never offered as a match, so the sheet does not show that it exists
        val h = session.hidden.value
        value = session.withOpenDb { d ->
            ua.mandat.data.Packet.plan(pk, d.people().all().filter { it.id !in h.people }, d.matters().observeAll().first().filter { it.id !in session.hiddenMatters.value })
        }
    }
    val personChoice = remember(plan) { androidx.compose.runtime.mutableStateMapOf<Long, Long?>().apply { plan?.personDefault?.let { putAll(it) } } }
    val matterChoice = remember(plan) { androidx.compose.runtime.mutableStateMapOf<Long, Long?>().apply { plan?.matterDefault?.let { putAll(it) } } }
    EditSheet(title = stringResource(R.string.packet_review_title), onDismiss = onDismiss,
        onSave = plan?.let { { onAdd(personChoice.toMap(), matterChoice.toMap()) } }, saveText = stringResource(R.string.packet_add)) {
        SheetGroup { Text(summary, style = MaterialTheme.typography.bodyMedium) }
        val pl = plan
        if (pl != null && (pl.personCandidates.isNotEmpty() || pl.matterCandidates.isNotEmpty())) {
            Text(stringResource(R.string.packet_review_hint), style = MaterialTheme.typography.labelMedium, color = Muted)
            if (pl.matterCandidates.isNotEmpty()) SheetGroup(stringResource(R.string.obs_matters)) {
                pk.matters.filter { it.id in pl.matterCandidates }.forEach { m ->
                    Text(m.title, style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickChip(stringResource(R.string.packet_as_new), matterChoice[m.id] == null) { matterChoice[m.id] = null }
                        pl.matterCandidates.getValue(m.id).forEach { c -> QuickChip(stringResource(R.string.packet_same_as, c.title), matterChoice[m.id] == c.id) { matterChoice[m.id] = c.id } }
                    }
                }
            }
            if (pl.personCandidates.isNotEmpty()) SheetGroup(stringResource(R.string.obs_people)) {
                pk.people.filter { it.id in pl.personCandidates }.forEach { x ->
                    Text(listOf(x.name, x.organization).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickChip(stringResource(R.string.packet_as_new), personChoice[x.id] == null) { personChoice[x.id] = null }
                        pl.personCandidates.getValue(x.id).forEach { c ->
                            QuickChip(stringResource(R.string.packet_same_as, listOf(c.name, c.organization).filter { it.isNotBlank() }.joinToString(" · ")), personChoice[x.id] == c.id) { personChoice[x.id] = c.id }
                        }
                    }
                }
            }
        }
    }
}
