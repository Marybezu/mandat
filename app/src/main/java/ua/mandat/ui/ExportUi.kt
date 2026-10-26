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
    var protect by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var warnOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pickedAt by remember { mutableStateOf(-1) }
    var importKind by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var pickingMatter by remember { mutableStateOf(false) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        session.pickingFile = false
        if (uri == null || pickedAt != session.generationNow()) return@rememberLauncherForActivityResult
        busy = true
        val pass = if (protect) password.toCharArray() else null
        password = ""; repeat = ""
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runExport(context, session, plan, uri, pass) }
            pass?.fill(' ')
            busy = false
            message = context.getString(if (ok) R.string.export_done else R.string.export_failed)
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        session.pickingFile = false
        val kind = importKind
        importKind = null
        if (uri == null || kind == null || pickedAt != session.generationNow()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val p = withContext(Dispatchers.IO) { readImport(context, kind, uri) }
            busy = false
            if (p == null) message = context.getString(R.string.import_failed) else pendingImport = p
        }
    }
    fun fileName(): String {
        val date = java.time.LocalDate.now().toString()
        val single = listOf(plan.people || plan.book, plan.calendar, plan.tables.isNotEmpty(), plan.diary, plan.matterId != null, plan.everything).count { it } == 1 &&
            plan.tables.size <= 1 && !(plan.people && plan.book)
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
    val passOk = !protect || (password.length >= MIN_EXPORT_PASSWORD && password == repeat)

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
            SectionCard(Sym.save, stringResource(R.string.export_what)) {
                Check(stringResource(R.string.export_people), plan.people) { plan.people = it }
                if (plan.people) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 40.dp)) {
                    FilterChip(selected = plan.peopleCategory == null, onClick = { plan.peopleCategory = null }, label = { Text(stringResource(R.string.matters_all_roles)) })
                    (listOf("TEAM") + categoryChoices(people)).forEach { k ->
                        FilterChip(selected = plan.peopleCategory == k, onClick = { plan.peopleCategory = k },
                            label = { Text(if (k == "TEAM") stringResource(R.string.team) else categoryLabel(k)) })
                    }
                }
                Check(stringResource(R.string.export_book, contacts.size), plan.book) { plan.book = it }
                if (plan.book) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 40.dp)) {
                    FilterChip(selected = plan.bookGroup == null, onClick = { plan.bookGroup = null }, label = { Text(stringResource(R.string.matters_all_roles)) })
                    contacts.map { it.label }.filter { it.isNotBlank() }.distinct().sorted().forEach { g ->
                        FilterChip(selected = plan.bookGroup == g, onClick = { plan.bookGroup = g }, label = { Text(g) })
                    }
                }
                Check(stringResource(R.string.export_calendar), plan.calendar) { plan.calendar = it }
                if (plan.calendar) Row(Modifier.padding(start = 40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(R.string.export_span_all, R.string.export_span_future, R.string.export_span_year).forEachIndexed { i, l ->
                        FilterChip(selected = plan.calendarSpan == i, onClick = { plan.calendarSpan = i }, label = { Text(stringResource(l)) })
                    }
                }
                Text(stringResource(R.string.export_tables), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TABLES.forEach { t ->
                        FilterChip(selected = t in plan.tables, onClick = { plan.tables = if (t in plan.tables) plan.tables - t else plan.tables + t },
                            label = { Text(stringResource(tableLabel(t))) })
                    }
                }
                Check(stringResource(R.string.export_diary), plan.diary) { plan.diary = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = plan.matterId != null, onCheckedChange = { if (it) pickingMatter = true else plan.matterId = null })
                    Text(plan.matterId?.let { id -> stringResource(R.string.export_matter_one, titles.firstOrNull { it.id == id }?.title.orEmpty()) }
                        ?: stringResource(R.string.export_matter), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable { pickingMatter = true })
                }
                if (!trip) Check(stringResource(R.string.export_everything), plan.everything) { plan.everything = it }
            }
            SectionCard(Sym.lock, stringResource(R.string.export_protection)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { protect = true }) {
                    RadioButton(selected = protect, onClick = { protect = true })
                    Column {
                        Text(stringResource(R.string.export_with_password), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.export_with_password_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }
                if (protect) {
                    OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.export_password, MIN_EXPORT_PASSWORD)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(repeat, { repeat = it }, label = { Text(stringResource(R.string.export_password_repeat)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = repeat.isNotEmpty() && repeat != password, modifier = Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { protect = false }) {
                    RadioButton(selected = !protect, onClick = { protect = false })
                    Column {
                        Text(stringResource(R.string.export_open), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.export_open_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(8.dp)); Text(stringResource(R.string.export_working)) }
            Button(onClick = { if (protect) startSave() else warnOpen = true }, enabled = plan.any() && passOk && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.export_go))
            }
            Spacer(Modifier.height(Space.section))
            SectionCard(Sym.inbox, stringResource(R.string.import_title)) {
                Text(stringResource(R.string.import_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                listOf(
                    "ics" to R.string.import_ics, "notes" to R.string.import_notes, "csv" to R.string.import_csv, "vcf" to R.string.vcf_import,
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
            text = { Text(stringResource(p.summary, p.count)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    p.apply(session)
                    message = context.getString(R.string.import_done, p.count)
                }, enabled = p.count > 0) { Text(stringResource(R.string.import_go)) }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

const val MIN_EXPORT_PASSWORD = 10

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

/** Gathers what was chosen (leaving out what trip mode hides), and writes one file. */
private suspend fun runExport(context: Context, session: Session, plan: ExportPlan, uri: Uri, password: CharArray?): Boolean {
    val files: List<Pair<String, ByteArray>> = session.withOpenDb { db -> buildFiles(context, db, session.hiddenMatters.value, plan) } ?: return false
    if (files.isEmpty()) return false
    return try {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            when {
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
                else -> java.util.zip.ZipOutputStream(out).use { zip ->
                    files.forEach { (name, bytes) ->
                        zip.putNextEntry(java.util.zip.ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }
        } ?: return false
        true
    } catch (e: Exception) {
        // Nothing half-written should stay as if it were complete
        try { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (x: Exception) { }
        false
    } finally {
        files.forEach { it.second.fill(0) }
    }
}

private suspend fun buildFiles(context: Context, db: MandatDb, hidden: Set<Long>, plan: ExportPlan): List<Pair<String, ByteArray>> {
    fun visible(matterId: Long?) = matterId == null || matterId !in hidden
    val out = ArrayList<Pair<String, ByteArray>>()
    fun add(name: String, text: String) { out += name to text.toByteArray(Charsets.UTF_8) }
    val people = db.people().all()
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
            listOf(p.what, p.personId?.let(nameOf::get) ?: p.toWhom, context.getString(p.level.label()),
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
        val reviews = db.reviews().observeAll().first().sortedBy { it.day }
        add("diary.md", buildString {
            append("# ").append(context.getString(R.string.diary_title)).append("\n\n")
            reviews.filter { it.progress.isNotBlank() || it.mood != null || it.energy != null }.forEach { r ->
                append("## ").append(Export.day(r.day)).append("\n\n")
                val feel = listOfNotNull(r.energy?.let { context.getString(R.string.diary_energy) + ": $it/5" }, r.mood?.let { context.getString(R.string.diary_mood) + ": $it/5" })
                if (feel.isNotEmpty()) append("_").append(feel.joinToString(" · ")).append("_\n\n")
                if (r.progress.isNotBlank()) append(Export.md(r.progress)).append("\n\n")
            }
        })
    }
    plan.matterId?.takeIf { visible(it) }?.let { id -> db.matters().get(id)?.let { m -> add("matter-$id.md", matterDossier(context, db, m, nameOf, hidden)) } }
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
    val links = db.people().observeForMatter(m.id).first()
    if (links.isNotEmpty()) {
        append("\n## ").append(context.getString(R.string.people_in_matter)).append("\n\n")
        links.forEach { append("- ").append(Export.md(it.name)).append(ua.mandat.data.Stance.of(it.stance)?.let { s -> " — " + context.getString(s.label()) }.orEmpty()).append("\n") }
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
class PendingImport(val summary: Int, val count: Int, val apply: (Session) -> Unit)

private const val MAX_IMPORT_BYTES = 20L * 1024 * 1024

private fun readImport(context: Context, kind: String, uri: Uri): PendingImport? = try {
    val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    if (size > MAX_IMPORT_BYTES) null
    else {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
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
        } ?: throw java.io.IOException()
        val text = String(bytes, Charsets.UTF_8)
        bytes.fill(0)
        when (kind) {
            "ics" -> {
                val items = ua.mandat.data.IcsReader.read(text)
                PendingImport(R.string.import_ics_summary, items.size) { s ->
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
            }
            "notes" -> {
                val notes = ua.mandat.data.notesFromText(text)
                PendingImport(R.string.import_notes_summary, notes.size) { s ->
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
