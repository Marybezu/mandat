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

    fun any() = people || book || calendar || tables.isNotEmpty() || diary || matterId != null || everything
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
    val plan = remember { ExportPlan() }
    val people by remember(db) { db.people().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val contacts by remember(db) { db.contacts().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val trip by session.tripOn.collectAsStateWithLifecycle()
    // 0 a ZIP with a password (other apps open it), 1 Mandat's own encrypted file, 2 an open file
    var mode by remember { mutableStateOf(PROTECT_ZIP) }
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

    var tooBigForMandat by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        session.pickingFile = false
        if (uri == null || pickedAt != session.generationNow()) return@rememberLauncherForActivityResult
        busy = true
        val pass = if (protect) password.toCharArray() else null
        val own = mode == PROTECT_MANDAT
        tooBigForMandat = false
        password = ""; repeat = ""; suggested = false
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { runExport(context, session, plan, uri, pass, own) { tooBigForMandat = true } }
                message = context.getString(if (ok) R.string.export_done else if (own && tooBigForMandat) R.string.export_too_big else R.string.export_failed)
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
        val single = listOf(plan.people || plan.book, plan.calendar, plan.tables.isNotEmpty(), plan.diary, plan.matterId != null, plan.everything).count { it } == 1 &&
            plan.tables.size <= 1 && !(plan.people && plan.book)
        if (mode == PROTECT_MANDAT) return "mandat-$date.$MANDAT_EXPORT_EXT"
        if (protect || !single) return "mandat-$date.zip"
        return "mandat-$date." + when {
            plan.people || plan.book -> "vcf"
            plan.calendar -> "ics"
            plan.tables.isNotEmpty() -> "csv"
            plan.everything -> "json"
            else -> "md"
        }
    }
    fun startSave() {
        pickedAt = session.generationNow()
        session.pickingFile = true
        save.launch(fileName())
    }
    // Mandat's own file holds only what Mandat can take back in: contacts and the calendar
    val ownOnly = mode == PROTECT_MANDAT
    val anyChosen = if (ownOnly) plan.people || plan.book || plan.calendar else plan.any()
    val passOk = !protect || (!ua.mandat.backup.BackupCrypto.isTooSimple(password) && password == repeat)

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
            if (trip) Text(stringResource(R.string.export_trip_note), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            else if (hidden.isNotEmpty()) Text(stringResource(R.string.export_lens_note), style = MaterialTheme.typography.labelMedium, color = Muted)
            SectionCard(Sym.save, stringResource(R.string.export_what)) {
                Check(stringResource(R.string.export_people), plan.people) { plan.people = it }
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
                if (plan.calendar) Row(Modifier.padding(start = 40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(R.string.export_span_all, R.string.export_span_future, R.string.export_span_year).forEachIndexed { i, l ->
                        QuickChip(stringResource(l), plan.calendarSpan == i) { plan.calendarSpan = i }
                    }
                }
                if (ownOnly) Text(stringResource(R.string.export_mandat_only), style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
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
                if (plan.diary || plan.matterId != null) Check(stringResource(R.string.export_pdf), plan.pdf) { plan.pdf = it }
                if (hidden.isEmpty()) Check(stringResource(R.string.export_everything), plan.everything) { plan.everything = it }
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
                Choice(PROTECT_ZIP, R.string.export_with_password, R.string.export_with_password_hint)
                Choice(PROTECT_MANDAT, R.string.export_mandat, R.string.export_mandat_hint)
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
                    OutlinedTextField(password, { password = it; suggested = false }, label = { Text(stringResource(R.string.export_password, ua.mandat.backup.BackupCrypto.MIN_PASSWORD_LENGTH)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        isError = password.isNotEmpty() && ua.mandat.backup.BackupCrypto.isTooSimple(password),
                        supportingText = { if (password.isNotEmpty() && ua.mandat.backup.BackupCrypto.isTooSimple(password)) Text(stringResource(R.string.export_password_weak)) },
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
            SectionCard(Sym.inbox, stringResource(R.string.import_title)) {
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
        text = { Text(stringResource(R.string.export_open_warning)) },
        confirmButton = { TextButton(onClick = { warnOpen = false; startSave() }) { Text(stringResource(R.string.export_open_confirm)) } },
        dismissButton = { TextButton(onClick = { warnOpen = false }) { Text(stringResource(R.string.cancel)) } },
    )
    message?.let { m -> AlertDialog(onDismissRequest = { message = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.ok)) } }) }
    pendingImport?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.import_title)) },
            text = { Text(stringResource(p.summary, *p.args.toTypedArray())) },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    p.apply(session)
                    if (p.source != null) offerDelete = p.source else message = context.getString(R.string.import_done, p.count)
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

const val PROTECT_ZIP = 0
const val PROTECT_MANDAT = 1
const val PROTECT_OPEN = 2

/** Extension of Mandat's own encrypted export file. */
const val MANDAT_EXPORT_EXT = "mandatx"

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
    val buf = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(buf).use { zip ->
        files.forEach { (name, bytes) ->
            zip.putNextEntry(java.util.zip.ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return buf.toByteArray()
}

/**
 * Gathers what was chosen (leaving out what trip mode and the lens hide), and writes one file.
 * Any failure, even running out of memory, ends without a crash and removes the new file.
 */
private suspend fun runExport(context: Context, session: Session, plan: ExportPlan, uri: Uri, password: CharArray?, own: Boolean, tooBig: () -> Unit): Boolean {
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
        } else plan
        files = session.withOpenDb { db -> buildFiles(context, db, session.hiddenMatters.value, wanted) }.orEmpty()
        // Mandat's own file carries only what Mandat can take back in
        if (own) files = files.filter { (name, bytes) -> (name.endsWith(".vcf") || name.endsWith(".ics")).also { keep -> if (!keep) bytes.fill(0) } }
        if (files.isEmpty()) { discard(); return false }
        // Mandat's own file is sealed before anything is written, so a file too big to open again is never made
        val sealed: ByteArray? = if (password != null && own) {
            // The same encryption as a backup (Argon2id and AES-256-GCM); file names are hidden too
            val inner = zipInMemory(files)
            val marked = ua.mandat.backup.BackupCrypto.EXPORT_MARK + inner
            inner.fill(0)
            try { ua.mandat.backup.BackupCrypto.encrypt(marked, String(password)) } finally { marked.fill(0) }
        } else null
        if (sealed != null && sealed.size > ua.mandat.backup.BackupCrypto.MAX_FILE_BYTES) { tooBig(); discard(); return false }
        val written = context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            touched = true
            when {
                sealed != null -> out.write(sealed)
                password != null -> {
                    // ZIP with AES-256: a standard that 7-Zip, Keka and similar apps open with the password
                    val params = net.lingala.zip4j.model.ZipParameters().apply {
                        isEncryptFiles = true
                        encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
                        aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
                    }
                    net.lingala.zip4j.io.outputstream.ZipOutputStream(out, password).use { zip ->
                        files.forEach { (name, bytes) ->
                            params.fileNameInZip = name
                            zip.putNextEntry(params)
                            zip.write(bytes)
                            zip.closeEntry()
                        }
                    }
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
    // A person known only through hidden matters stays hidden too: every link they have
    // (in a matter, a delegation, a promise, an event) leads to a hidden matter
    val onlyHidden: Set<Long> = if (hidden.isEmpty()) emptySet() else {
        val eventMatter = db.events().observeAll().first().associate { it.id to it.matterId }
        val refs = db.people().observeLinks().first().map { it.personId to it.matterId } +
            db.delegations().observeAll().first().mapNotNull { d -> d.personId?.let { it to d.matterId } } +
            db.promises().observeAll().first().mapNotNull { p -> p.personId?.let { it to p.matterId } } +
            db.events().observeLinks().first().map { it.personId to eventMatter[it.eventId] }
        refs.groupBy({ it.first }, { it.second }).filterValues { ms -> ms.all { it != null && it in hidden } }.keys
    }
    val people = db.people().all().filter { it.id !in onlyHidden }
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
        val chosen = db.contacts().all().filter { plan.bookGroup == null || it.label == plan.bookGroup }
        add("address-book.vcf", Export.vcard(chosen.map(Export::contactToV)))
    }
    val today = Dates.today()
    val events = db.events().observeAll().first().filter { visible(it.matterId) }
    if (plan.calendar) {
        val chosen = events.filter { e ->
            when (plan.calendarSpan) { 1 -> e.lastDay >= today; 2 -> e.day >= today - 365; else -> true }
        }
        add("calendar.ics", Export.ics(chosen, { k -> context.getString(ua.mandat.data.EventKind.of(k).label()) }))
    }
    if ("matters" in plan.tables) {
        val roles = db.roles().observeAll().first().associate { it.id to it.name }
        add("matters.csv", Export.csv(
            listOf("title", "type", "role", "attention", "status", "next_step", "goal", "description", "created", "updated"),
            db.matters().observeAll().first().filter { visible(it.id) }.map { m ->
                listOf(m.title, context.getString(ua.mandat.data.MatterType.of(m.type).label()), m.roleId?.let(roles::get).orEmpty(),
                    context.getString(ua.mandat.data.Attention.of(m.attention).label()), context.getString(ua.mandat.data.MatterStatus.of(m.status).label()),
                    m.nextStep, m.goal, m.description, Export.moment(m.createdAt), Export.moment(m.updatedAt))
            },
        ))
    }
    if ("delegations" in plan.tables) add("delegations.csv", Export.csv(
        listOf("what", "to_whom", "matter", "due", "status", "purpose", "done_means", "created", "closed"),
        db.delegations().observeAll().first().filter { visible(it.matterId) }.map { d ->
            listOf(d.what, d.personId?.let(nameOf::get).orEmpty(), d.matterId?.let(titles::get).orEmpty(), Export.day(d.due),
                context.getString(TaskStatus.of(d.status).delegationLabel()), d.purpose, d.doneMeans, Export.moment(d.createdAt), Export.moment(d.closedAt))
        },
    ))
    if ("promises" in plan.tables) add("promises.csv", Export.csv(
        listOf("what", "to_whom", "publicity", "origin", "risk", "matter", "due", "status", "promised", "closed", "source", "note"),
        db.promises().observeAll().first().filter { visible(it.matterId) }.map { p ->
            listOf(p.what, if (p.personId != null) nameOf[p.personId].orEmpty() else p.toWhom, context.getString(p.level.label()),
                ua.mandat.data.PromiseOrigin.of(p.origin)?.let { context.getString(it.label()) }.orEmpty(), p.risk?.toString().orEmpty(),
                p.matterId?.let(titles::get).orEmpty(), Export.day(p.due), context.getString(TaskStatus.of(p.status).promiseLabel()),
                Export.moment(p.createdAt), Export.moment(p.closedAt), p.source, p.statusNote)
        },
    ))
    if ("deadlines" in plan.tables) add("deadlines.csv", Export.csv(
        listOf("title", "day", "kind", "basis", "matter", "status"),
        db.deadlines().observeAll().first().filter { visible(it.matterId) }.map { d ->
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
        db.money().observeAll().first().filter { visible(it.matterId) }.map { m ->
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
        val reviews = db.reviews().observeAll().first().filter { it.id !in hiddenReviews }.sortedBy { it.day }
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
    plan.matterId?.takeIf { visible(it) }?.let { id -> db.matters().get(id)?.let { m -> addDoc("matter-$id", matterDossier(context, db, m, nameOf, hidden)) } }
    if (plan.everything && hidden.isEmpty()) out += "everything.json" to ua.mandat.backup.BackupData.dump(db)
    return out
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
class PendingImport(val summary: Int, val count: Int, val args: List<Int> = listOf(count), val source: Uri? = null, val apply: (Session) -> Unit)

private const val MAX_IMPORT_BYTES = 20L * 1024 * 1024

/** The whole file, at most [MAX_IMPORT_BYTES] (checked while reading, not only by the reported size). */
private fun readBytes(context: Context, uri: Uri): ByteArray? = try {
    val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    if (size > MAX_IMPORT_BYTES) null
    else context.contentResolver.openInputStream(uri)?.use { input ->
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_IMPORT_BYTES) throw java.io.IOException()
            buf.write(chunk, 0, n)
        }
        buf.toByteArray()
    }
} catch (e: Exception) {
    null
} catch (e: OutOfMemoryError) {
    null
}

/** Saves events read from a calendar file, skipping those already there (same title, day and time). */
private fun importEvents(s: Session, items: List<ua.mandat.data.IcsReader.Item>) {
    s.write { db ->
        val now = System.currentTimeMillis()
        val existing = db.events().observeAll().first().map { Triple(it.title, it.day, it.startMin) }.toSet()
        db.withTransaction {
            items.filter { Triple(it.title, it.day, it.startMin) !in existing }.forEach { i ->
                ua.mandat.data.cleanEvent(ua.mandat.data.Event(title = i.title, day = i.day, endDay = i.endDay, startMin = i.startMin, endMin = i.endMin,
                    kind = ua.mandat.data.EventKind.OTHER.name, place = i.place, notes = i.notes, createdAt = now, updatedAt = now))?.let { db.events().insert(it) }
            }
        }
    }
}

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
        var skipped = 0
        var total = 0L
        val chunk = ByteArray(64 * 1024)
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(inner, mark, inner.size - mark)).use { zip ->
            var count = 0
            // Every byte unpacked counts toward the limit, also of entries that are skipped,
            // so a small file cannot make the phone unpack gigabytes
            fun drain(sink: java.io.ByteArrayOutputStream?) {
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
                if (entry.isDirectory || ext !in setOf("ics", "vcf")) { skipped++; drain(null); continue }
                val buf = java.io.ByteArrayOutputStream()
                drain(buf)
                val text = String(buf.toByteArray(), Charsets.UTF_8)
                when (ext) {
                    "ics" -> events += ua.mandat.data.IcsReader.read(text).take(ua.mandat.data.IcsReader.MAX_EVENTS - events.size)
                    else -> contacts += ua.mandat.data.VCard.read(text.reader().buffered(), stop = stop).contacts.take(ua.mandat.data.VCard.MAX_CONTACTS - contacts.size)
                }
            }
        }
        Opened.Ok(PendingImport(R.string.import_mandat_summary, events.size + contacts.size, args = listOf(events.size, contacts.size, skipped)) { s ->
            if (events.isNotEmpty()) importEvents(s, events)
            if (contacts.isNotEmpty()) s.previewContacts(contacts, null)
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
                    s.write { db ->
                        val now = System.currentTimeMillis()
                        if (notes.isNotEmpty()) notes.forEachIndexed { i, n -> db.inbox().insert(ua.mandat.data.InboxItem(text = n, createdAt = now + i)) }
                    }
                }
            }
            "csv" -> {
                val list = ua.mandat.data.ContactCsv.contacts(text)
                PendingImport(R.string.import_csv_summary, list.size) { s -> s.previewContacts(list, uri) }
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
        val out = java.io.ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }
}
