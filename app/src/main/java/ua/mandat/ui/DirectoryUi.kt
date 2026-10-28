package ua.mandat.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ua.mandat.R
import ua.mandat.data.Contact
import ua.mandat.data.ImportPlan
import ua.mandat.data.MandatDb
import ua.mandat.data.cleanContact
import ua.mandat.data.emailsIn
import ua.mandat.data.normalizePhone

/** A .vcf file read and waiting for the owner's choice. */
class VcfImport(val uri: Uri?, val plan: ImportPlan, val tooMany: Boolean)

/** The system contact picker for one phone number: no permission, only the chosen entry is shared. */
private object PickPhone : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit) = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}

/** Reads the name and number of the picked entry (allowed only for that entry, only now). */
private fun readPicked(context: Context, uri: Uri): Contact? = try {
    context.contentResolver.query(
        uri,
        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        null, null, null,
    )?.use { c ->
        if (!c.moveToFirst()) null
        else cleanContact(Contact(name = c.getString(0).orEmpty(), phones = c.getString(1).orEmpty(), sortKey = "", createdAt = System.currentTimeMillis()))
    }
} catch (e: SecurityException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

/** The first letter for the alphabet index; digits and signs share "#". */
private fun letterOf(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() }?.toString() ?: "#"

/**
 * The address book: everyone, quick to search, outside the dashboards. A group chart on top,
 * the alphabet to jump, and tapping a name opens the card with call and chat buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryScreen(db: MandatDb, session: Session) {
    BackHandler { session.showDirectory = false }
    val raw by remember(db) { db.contacts().observeAll().hidingNamed(session, { null }) { it.name + " " + it.organization + " " + it.jobTitle + " " + it.notes + " " + it.label } }.collectAsStateWithLifecycle(emptyList())
    // Sorted the way the language sorts (є, і, ї, ґ in their places), not by character codes
    val contacts = remember(raw) {
        val collator = java.text.Collator.getInstance(java.util.Locale.getDefault())
        raw.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedAt by remember { mutableStateOf(-1) }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val openVcf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        session.pickingFile = false
        if (uri != null && pickedAt == session.generationNow()) session.readVcf(uri)
    }
    val pickPhone = rememberLauncherForActivityResult(PickPhone) { uri ->
        session.pickingFile = false
        if (uri != null && pickedAt == session.generationNow()) {
            val c = readPicked(context, uri)
            if (c != null) session.contactDraft = ContactDraft(c)
        }
    }

    val noGroup = stringResource(R.string.dir_no_group)
    val q = query.trim().lowercase()
    val shown = contacts.filter { c ->
        (label == null || c.label == label) &&
            (q.length < 2 || c.sortKey.contains(q) || c.organization.lowercase().contains(q) || c.phones.contains(q) ||
                c.emails.lowercase().contains(q) || c.jobTitle.lowercase().contains(q))
    }
    val groups = contacts.groupingBy { it.label }.eachCount()
    val letters = shown.map { letterOf(it.name) }.distinct()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.directory)) },
                actions = { HowItWorks(R.string.how_directory) },
                navigationIcon = {
                    IconButton(onClick = { session.showDirectory = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).imePadding().fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "intro") {
                Text(stringResource(R.string.dir_hint), style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    Button(onClick = {
                        pickedAt = session.generationNow()
                        session.pickingFile = true
                        openVcf.launch(arrayOf("text/vcard", "text/x-vcard", "text/directory", "application/octet-stream", "*/*"))
                    }, enabled = !session.vcfBusy) { Text(stringResource(R.string.vcf_import)) }
                    OutlinedButton(onClick = {
                        pickedAt = session.generationNow()
                        session.pickingFile = true
                        try { pickPhone.launch(Unit) } catch (e: android.content.ActivityNotFoundException) { session.pickingFile = false }
                    }) { Text(stringResource(R.string.dir_from_phone)) }
                }
                TextButton(onClick = { session.contactDraft = ContactDraft(Contact(name = "", sortKey = "", createdAt = 0)) }) { Text("+ " + stringResource(R.string.dir_add)) }
            }
            if (session.vcfBusy) item(key = "busy") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                    Text(stringResource(R.string.vcf_reading))
                }
            }
            session.vcfError?.let { err ->
                item(key = "err") {
                    MCard { Text(stringResource(err), color = MaterialTheme.colorScheme.error); TextButton(onClick = { session.vcfError = null }) { Text(stringResource(R.string.ok)) } }
                }
            }
            if (contacts.isEmpty()) {
                item(key = "empty") { EmptyHint(Sym.group, stringResource(R.string.dir_empty)) }
                return@LazyColumn
            }
            if (groups.size > 1 || groups.keys.firstOrNull()?.isNotEmpty() == true) item(key = "groups") {
                MCard {
                    DonutChart(
                        slices = groups.entries.sortedByDescending { it.value }.mapIndexed { i, (k, n) ->
                            Slice(k, k.ifEmpty { noGroup }, n, if (k.isEmpty()) ChartColors.grayLight else SeriesColors[i % SeriesColors.size])
                        },
                        center = contacts.size.toString(), centerLabel = stringResource(R.string.dir_count),
                        selected = label, onPick = { label = it },
                    )
                }
            }
            item(key = "search") {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth())
            }
            if (letters.size > 3) item(key = "letters") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    letters.forEach { l ->
                        Text(l, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                                val index = shown.indexOfFirst { letterOf(it.name) == l }
                                // Header items come before the list: jump past them
                                if (index >= 0) scope.launch { listState.scrollToItem(headerCount(session, contacts, groups, letters) + index + letters.indexOf(l)) }
                            }.padding(horizontal = 8.dp, vertical = 6.dp))
                    }
                }
            }
            item(key = "count") {
                Text(stringResource(R.string.dir_shown, shown.size, contacts.size), style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            var last = ""
            shown.forEachIndexed { i, c ->
                val l = letterOf(c.name)
                if (l != last) {
                    last = l
                    item(key = "h$l$i") { Text(l, style = MaterialTheme.typography.titleSmall, color = Muted, modifier = Modifier.padding(top = 8.dp)) }
                }
                item(key = c.id) { ContactLine(c) { session.contactDraft = ContactDraft(c) } }
            }
        }
    }

    session.contactDraft?.let { d -> ContactDialog(session, d) }
}

/** How many items come before the first letter header (for jumping from the alphabet). */
private fun headerCount(session: Session, contacts: List<Contact>, groups: Map<String, Int>, letters: List<String>): Int {
    var n = 2 // intro, actions
    if (session.vcfBusy) n++
    if (session.vcfError != null) n++
    if (groups.size > 1 || groups.keys.firstOrNull()?.isNotEmpty() == true) n++
    n++ // search
    if (letters.size > 3) n++
    n++ // count
    return n
}

@Composable
private fun ContactLine(c: Contact, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onOpen).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Initials(c.name, team = false)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOf(c.jobTitle, c.organization, c.label).filter { it.isNotBlank() }.joinToString(" · ")
                .ifEmpty { c.phones.lineSequence().firstOrNull().orEmpty() }
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelMedium, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A contact's card: edit the basics, call or write, move to People, or delete. */
@Composable
private fun ContactDialog(session: Session, d: ContactDraft) {
    var confirmDelete by remember { mutableStateOf(false) }
    val c = d.contact
    fun close() { session.contactDraft = null }

    EditSheet(
        title = stringResource(if (d.isNew) R.string.dir_add else R.string.dir_contact),
        onDismiss = ::close,
        onSave = {
                close()
                session.write { d.persist(it) }
            },
        saveEnabled = d.name.isNotBlank() || d.phones.isNotBlank(),
    ) {
        SheetGroup {

                if (!d.isNew) {
                    ReachButtons(c.phones.lines().mapNotNull { normalizePhone(it) }.distinct(), emailsIn(c.emails))
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedTextField(d.name, { d.name = it }, label = { Text(stringResource(R.string.person_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.phones, { d.phones = it }, label = { Text(stringResource(R.string.dir_phones)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.emails, { d.emails = it }, label = { Text(stringResource(R.string.dir_emails)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.organization, { d.organization = it }, label = { Text(stringResource(R.string.person_org)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.jobTitle, { d.jobTitle = it }, label = { Text(stringResource(R.string.person_job)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.label, { d.label = it }, label = { Text(stringResource(R.string.dir_group)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.notes, { d.notes = it }, label = { Text(stringResource(R.string.person_notes)) }, modifier = Modifier.fillMaxWidth())
                if (!d.isNew) {
                    TextButton(onClick = {
                        val e = d.edited() ?: return@TextButton
                        close()
                        session.write { db2 ->
                            val id = db2.contacts().moveToPeople(db2.people(), e, System.currentTimeMillis())
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { session.openPerson(id) }
                        }
                    }) { Text(stringResource(R.string.dir_to_people)) }
                    TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                }
        }
    }
    if (confirmDelete) {
        ConfirmDelete(onConfirm = {
            confirmDelete = false
            close()
            session.write { it.contacts().delete(c.id) }
        }, onCancel = { confirmDelete = false })
    }
}

/**
 * Before an import: what was found, what is already known, a group for all of them, and a tick
 * for each entry. Nothing is saved until the owner confirms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VcfPreviewScreen(session: Session, job: VcfImport) {
    BackHandler { session.vcfImport = null }
    val all = job.plan.fresh
    var skip by remember(job) { mutableStateOf(setOf<Int>()) }
    var label by rememberSaveable { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val indexed = all.withIndex().filter { (_, c) -> q.length < 2 || c.sortKey.contains(q) || c.organization.lowercase().contains(q) || c.phones.contains(q) }
    val chosen = all.size - skip.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vcf_preview)) },
                navigationIcon = {
                    IconButton(onClick = { session.vcfImport = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).imePadding().fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "summary") {
                MCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                        StatTile(all.size, stringResource(R.string.vcf_new))
                        StatTile(job.plan.duplicates, stringResource(R.string.vcf_known))
                        StatTile(chosen, stringResource(R.string.vcf_chosen))
                    }
                    if (job.tooMany) Text(stringResource(R.string.vcf_too_many, ua.mandat.data.VCard.MAX_CONTACTS), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item(key = "label") {
                OutlinedTextField(label, { label = it }, singleLine = true, label = { Text(stringResource(R.string.dir_group)) },
                    supportingText = { Text(stringResource(R.string.vcf_group_hint)) }, modifier = Modifier.fillMaxWidth())
            }
            item(key = "go") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { session.importVcf(all.filterIndexed { i, _ -> i !in skip }, label) }, enabled = chosen > 0) {
                        Text(stringResource(R.string.vcf_import_n, chosen))
                    }
                    TextButton(onClick = { skip = if (skip.isEmpty()) all.indices.toSet() else emptySet() }) {
                        Text(stringResource(if (skip.isEmpty()) R.string.vcf_none else R.string.vcf_all))
                    }
                }
            }
            item(key = "search") {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth())
            }
            items(indexed, key = { it.index }) { (i, c) ->
                Row(Modifier.fillMaxWidth().clickable { skip = if (i in skip) skip - i else skip + i }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = i !in skip, onCheckedChange = { skip = if (it) skip - i else skip + i })
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val meta = listOf(c.organization, c.phones.lineSequence().firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelMedium, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** After an import: offer to delete the open .vcf file, which is not encrypted. */
@Composable
fun VcfDoneDialog(session: Session, count: Int, uri: Uri?) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { session.vcfDone = null },
        title = { Text(stringResource(R.string.vcf_done, count)) },
        // A source that was itself encrypted (Mandat's own file) needs no deleting
        text = if (uri == null) null else { { Text(stringResource(if (failed) R.string.vcf_delete_failed else R.string.vcf_delete_offer)) } },
        confirmButton = {
            if (uri != null && !failed) TextButton(onClick = {
                scope.launch {
                    val ok = deleteDocumentQuietly(context, uri)
                    if (ok) session.vcfDone = null else failed = true
                }
            }) { Text(stringResource(R.string.vcf_delete)) }
        },
        dismissButton = { TextButton(onClick = { session.vcfDone = null }) { Text(stringResource(if (failed || uri == null) R.string.ok else R.string.vcf_keep)) } },
    )
}
