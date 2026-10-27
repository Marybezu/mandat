package ua.mandat.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ua.mandat.R
import ua.mandat.ai.Found
import ua.mandat.ai.LocalAi
import ua.mandat.ai.ModelStore
import ua.mandat.data.matterFromNote

/**
 * Settings for the optional on-device AI: what it does, how to get the model (downloaded in a
 * browser, since Mandat has no internet, then picked here and checked by its checksum), and
 * removing it. Nothing is sent anywhere; answers are only suggestions.
 */
@Composable
fun AiSection(session: Session) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(session.ai.models.state()) }
    var importing by remember { mutableStateOf<Float?>(null) }
    var deleteOriginal by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        session.pickingFile = false
        if (uri == null) return@rememberLauncherForActivityResult
        importing = 0f
        scope.launch {
            note = try {
                val r = session.ai.models.import(uri, deleteOriginal) { p -> importing = p }
                when (r.originalDeleted) { true -> context.getString(R.string.ai_original_deleted); false -> context.getString(R.string.ai_original_kept); null -> null }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                context.getString(R.string.ai_failed, e.message ?: "")
            } finally {
                importing = null
                state = session.ai.models.state()
            }
        }
    }
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ai_explain), style = MaterialTheme.typography.bodyMedium)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Muted) }
        val s = state
        when {
            importing != null -> {
                LinearProgressIndicator(progress = { importing ?: 0f }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.ai_importing), style = MaterialTheme.typography.bodySmall)
            }
            s is ModelStore.State.Ready -> {
                Text(stringResource(R.string.ai_ready, s.sizeMb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.ai_delete)) }
            }
            else -> {
                Text(stringResource(R.string.ai_steps, ModelStore.E2B_FILE), style = MaterialTheme.typography.bodySmall)
                // A direct link to the one file that fits; the page lists variants for other chips
                androidx.compose.material3.Button(onClick = { open(ModelStore.E2B_DOWNLOAD) }) { Text(stringResource(R.string.ai_download)) }
                OutlinedButton(onClick = { session.pickingFile = true; picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.ai_import)) }
                TextButton(onClick = { open(ModelStore.E2B_PAGE) }) { Text(stringResource(R.string.ai_open_page)) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { deleteOriginal = !deleteOriginal }) {
                    Checkbox(checked = deleteOriginal, onCheckedChange = { deleteOriginal = it })
                    Text(stringResource(R.string.ai_delete_original), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    if (confirmDelete) ConfirmDialog(null, stringResource(R.string.ai_delete_question), stringResource(R.string.ai_delete), danger = true,
        onConfirm = {
            confirmDelete = false
            scope.launch { session.ai.deleteModel(); state = session.ai.models.state() }
        }, onCancel = { confirmDelete = false })
}

/**
 * Paste a letter or notes from a meeting: the model finds matters, delegations, promises,
 * deadlines and notes. Each is shown with a tick; only the ticked ones are saved.
 */
@Composable
fun AiSplitSheet(db: ua.mandat.data.MandatDb, session: Session, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val people by androidx.compose.runtime.remember(db) { db.people().observeAll() }.collectAsStateWithLifecycle(emptyList())
    // The text lives in the Session: on lock it is kept in the Inbox
    val text = session.aiSplitText
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<Pair<Found, Boolean>>() }
    val today = Dates.today()
    /** Only a certain match: the same name, or the one person whose name starts with it. */
    fun match(name: String): ua.mandat.data.Person? {
        val n = name.trim().lowercase()
        if (n.length < 2) return null
        people.filter { it.name.isNotBlank() && it.name.trim().lowercase() == n }.singleOrNull()?.let { return it }
        return people.filter { it.name.isNotBlank() && it.name.trim().lowercase().startsWith(n) }.singleOrNull()
    }
    /** A date from the model, unless it is clearly off (more than a year back or five years ahead). */
    fun dateOf(s: String): Long? = runCatching { java.time.LocalDate.parse(s).toEpochDay() }.getOrNull()?.takeIf { it in (today - 365)..(today + 5 * 365) }
    val notFound = stringResource(R.string.ai_person_not_found)
    val linkedTo = stringResource(R.string.ai_person_linked)
    val noDate = stringResource(R.string.ai_no_date_inbox)
    val kindLabel = mapOf(
        "matter" to stringResource(R.string.ai_kind_matter), "delegation" to stringResource(R.string.ai_kind_delegation),
        "promise" to stringResource(R.string.ai_kind_promise), "deadline" to stringResource(R.string.ai_kind_deadline), "note" to stringResource(R.string.ai_kind_note),
    )
    EditSheet(
        title = stringResource(R.string.ai_split_title),
        onDismiss = onClose,
        onSave = if (found.any { it.second }) ({
            val chosen = found.filter { it.second }.map { it.first }
            session.write { d ->
                val now = System.currentTimeMillis()
                d.withTransaction {
                    chosen.forEach { f ->
                        val personId = f.person.takeIf { it.isNotBlank() }?.let { match(it)?.id }
                        val due = dateOf(f.due)
                        // A name that matched nobody stays in the text, so it is not lost
                        val withName = if (personId == null && f.person.isNotBlank()) f.text + " — " + f.person else f.text
                        when (f.kind) {
                            "matter" -> ua.mandat.data.cleanMatter(matterFromNote(f.text, now))?.let { m ->
                                val id = d.matters().insert(m)
                                d.history().insertAll(ua.mandat.data.changesOf(null, m, id, now, Dates.today()))
                            }
                            "delegation" -> newDelegation(personId, null).also { it.what = withName; it.due = due }.persist(d)
                            "promise" -> newPromise(personId, null).also { it.what = f.text; it.due = due; if (personId == null) it.toWhom = f.person }.persist(d)
                            // A deadline without a date would land on today: it goes to the Inbox instead
                            "deadline" -> if (due != null) newDeadline(null).also { it.title = f.text; it.day = due }.persist(d)
                                else ua.mandat.data.cleanInboxText(withName)?.let { d.inbox().insert(ua.mandat.data.InboxItem(text = it, createdAt = now)) }
                            else -> ua.mandat.data.cleanInboxText(f.text + if (f.person.isNotBlank()) " — " + f.person else "")
                                ?.let { d.inbox().insert(ua.mandat.data.InboxItem(text = it, createdAt = now)) }
                        }
                    }
                }
            }
            onClose()
        }) else null,
        saveText = stringResource(R.string.ai_split_save, found.count { it.second }),
    ) {
        SheetGroup {
            Text(stringResource(R.string.ai_split_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
            OutlinedTextField(text, { session.aiSplitText = it.take(LocalAi.MAX_INPUT) }, label = { Text(stringResource(R.string.ai_split_text)) }, minLines = 6, modifier = Modifier.fillMaxWidth())
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(8.dp)); Text(stringResource(R.string.ai_thinking)) }
            else QuickChip(stringResource(R.string.ai_split_go), false) {
                busy = true; failed = false
                scope.launch {
                    val r = runCatching { session.ai.split(text, people.map { it.name }, java.time.LocalDate.now().toString()) }.getOrNull()
                    busy = false
                    found.clear()
                    // Nothing is ticked beforehand: each suggestion is read before it is saved
                    if (r.isNullOrEmpty()) failed = true else found.addAll(r.map { it to false })
                }
            }
            if (failed) Text(stringResource(R.string.ai_split_nothing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (found.isNotEmpty()) SheetGroup(stringResource(R.string.ai_split_found)) {
            QuickChip(stringResource(R.string.ai_tick_all), false) { found.indices.forEach { i -> found[i] = found[i].first to true } }
            found.forEachIndexed { i, (f, on) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { found[i] = f to !on }) {
                    Checkbox(checked = on, onCheckedChange = { found[i] = f to it })
                    Column(Modifier.weight(1f)) {
                        Text(f.text, style = MaterialTheme.typography.bodyMedium)
                        // What will really be saved: whom it is linked to, and the date (or where it goes without one)
                        val who = f.person.takeIf { it.isNotBlank() }?.let { n -> match(n)?.let { linkedTo + " " + it.name } ?: (notFound + ": " + n) }
                        val date = dateOf(f.due)?.let { Dates.format(it) } ?: if (f.kind == "deadline") noDate else null
                        Text(listOfNotNull(kindLabel[f.kind], who, date).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = Muted)
                    }
                }
            }
        }
    }
}

/**
 * A model's answer shown as a suggestion: the owner reads it, and may copy it into the text or
 * the diary; nothing is kept otherwise.
 */
@Composable
fun AiAnswer(session: Session, task: LocalAi.Task, facts: suspend () -> String, onUse: ((String) -> Unit)? = null, useText: String? = null, key: Any? = null) {
    val scope = rememberCoroutineScope()
    val ready by session.ai.models.ready.collectAsStateWithLifecycle()
    val hidden by session.hiddenMatters.collectAsStateWithLifecycle()
    // An answer belongs to what was hidden when it was asked (and to the text it was made from):
    // turning on trip mode or the lens, or editing the text, drops it
    var busy by remember(hidden, key) { mutableStateOf(false) }
    var answer by remember(hidden, key) { mutableStateOf<String?>(null) }
    var failed by remember(hidden, key) { mutableStateOf(false) }
    if (!ready) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(8.dp)); Text(stringResource(R.string.ai_thinking)) }
        else QuickChip(stringResource(when (task) {
            LocalAi.Task.BRIEF -> R.string.ai_brief
            LocalAi.Task.WEEK -> R.string.ai_week
            LocalAi.Task.SHORTEN -> R.string.ai_shorten
            LocalAi.Task.FILL -> R.string.ai_fill
        }), false) {
            busy = true; failed = false
            val askedWith = session.hiddenMatters.value
            scope.launch {
                val a = try { session.ai.write(task, facts()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { null }
                val still = session.hiddenMatters.value == askedWith
                answer = a?.takeIf { it.isNotBlank() && still }
                failed = answer == null && still
                busy = false
            }
        }
        if (failed) Text(stringResource(R.string.ai_split_nothing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        answer?.let { a ->
            MCard {
                Text(stringResource(R.string.ai_suggestion), style = MaterialTheme.typography.labelMedium, color = Muted)
                Text(a, style = MaterialTheme.typography.bodyMedium)
                Row {
                    if (onUse != null && useText != null) TextButton(onClick = { onUse(a); answer = null }) { Text(useText) }
                    TextButton(onClick = { answer = null }) { Text(stringResource(R.string.ai_dismiss)) }
                }
            }
        }
    }
}

/** Plain facts about a person for the brief suggestion; hidden matters are already left out by the caller's lists. */
suspend fun briefFacts(db: ua.mandat.data.MandatDb, session: Session, personId: Long): String {
    val hidden = session.hiddenMatters.value
    fun ok(id: Long?) = id == null || id !in hidden
    val p = db.people().all().firstOrNull { it.id == personId } ?: return ""
    val matters = db.people().observeMattersOf(personId).first().filter { ok(it.matterId) }
    val promises = db.promises().observeForPerson(personId).first().filter { ok(it.matterId) && ua.mandat.data.TaskStatus.of(it.status) == ua.mandat.data.TaskStatus.OPEN }
    val asked = db.delegations().observeForPerson(personId).first().filter { ok(it.matterId) && ua.mandat.data.TaskStatus.of(it.status) == ua.mandat.data.TaskStatus.OPEN }
    val events = db.events().observeForPerson(personId).first().filter { ok(it.matterId) }.sortedByDescending { it.day }.take(5)
    return buildString {
        append("Людина: ").append(p.name).append(". ").append(listOf(p.jobTitle, p.organization).filter { it.isNotBlank() }.joinToString(", ")).append("\n")
        if (p.notes.isNotBlank()) append("Нотатки: ").append(p.notes.take(800)).append("\n")
        if (matters.isNotEmpty()) append("Спільні справи: ").append(matters.joinToString("; ") { it.title + (it.stance?.let { s -> " (позиція: $s)" } ?: "") }).append("\n")
        if (promises.isNotEmpty()) append("Ми обіцяли: ").append(promises.joinToString("; ") { it.what }).append("\n")
        if (asked.isNotEmpty()) append("Ми доручили їй: ").append(asked.joinToString("; ") { it.what }).append("\n")
        if (events.isNotEmpty()) append("Зустрічі: ").append(events.joinToString("; ") { Dates.format(it.day) + " " + it.title + (it.outcome.takeIf { o -> o.isNotBlank() }?.let { o -> " — $o" } ?: "") }).append("\n")
    }
}
