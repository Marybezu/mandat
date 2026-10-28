package ua.mandat.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    // The copy runs in the Session: folding this group or leaving the screen does not cut it off
    val state = session.modelState
    val importing = session.modelImport
    val note = session.modelNote
    var deleteOriginal by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        session.pickingFile = false
        if (uri == null) return@rememberLauncherForActivityResult
        session.importModel(uri, deleteOriginal)
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
            scope.launch { session.ai.deleteModel(); session.modelState = session.ai.models.state() }
        }, onCancel = { confirmDelete = false })
}

/** The one mark of everything the on-device AI offers. */
const val AI_MARK = "✦"

/** The one colour of everything the AI offers: violet, so a suggestion is never mistaken for a record. */
val AiViolet: Color @Composable get() = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) ChartColors.aiDark else ChartColors.aiLight

/** A small violet button with the mark: tapping it asks the AI. */
@Composable
fun AiChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        "$AI_MARK $text", style = MaterialTheme.typography.labelLarge, color = AiViolet, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(20.dp)).background(AiViolet.copy(alpha = 0.12f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** One AI request and its answer, kept in memory only. */
class AiRun {
    var busy by mutableStateOf(false)
    var answer by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false)
    /** What was asked, shown on the card. */
    var label by mutableStateOf<String?>(null)
    internal var job: Job? = null

    fun clear() { job?.cancel(); busy = false; answer = null; failed = false }

    /**
     * Asks in the background. An answer belongs to what was hidden when it was asked: if trip mode
     * or the lens changes meanwhile, it is dropped (and [rememberAiRun] starts afresh).
     */
    fun start(scope: CoroutineScope, session: Session, task: LocalAi.Task, label: String? = null, facts: suspend () -> String) {
        job?.cancel()
        busy = true; failed = false; answer = null; this.label = label
        val askedWith = session.hiddenMatters.value
        job = scope.launch {
            val f = try { facts() } catch (e: CancellationException) { throw e } catch (e: Throwable) { "" }
            val a = if (f.isBlank()) null else try { session.ai.write(task, f) } catch (e: CancellationException) { throw e } catch (e: Throwable) { null }
            val still = session.hiddenMatters.value == askedWith
            answer = a?.trim()?.takeIf { it.isNotBlank() && still }
            failed = answer == null && still
            busy = false
        }
    }
}

/** An [AiRun] tied to what is hidden now (and to [key], for example the text it reads). */
@Composable
fun rememberAiRun(session: Session, key: Any? = null): AiRun {
    val hidden by session.hiddenMatters.collectAsStateWithLifecycle()
    val run = remember(hidden, key) { AiRun() }
    // A request whose answer can no longer be shown is stopped, so the model is free for the next one
    androidx.compose.runtime.DisposableEffect(run) { onDispose { run.job?.cancel() } }
    return run
}

/** Puts text on the clipboard, marked as sensitive so the system does not show it in a preview. */
fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(AiClipboard.LABEL, text)
    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    cm.setPrimaryClip(clip)
    AiClipboard.markCopied(context)
}

/** What Mandat put on the clipboard is taken off it again on lock. */
object AiClipboard {
    // A neutral label that other apps are unlikely to use; it does not name the app
    const val LABEL = "note-copy-n7"
    /** The label of copies made by earlier versions, cleared too. */
    private const val OLD_LABEL = "mandat"
    /** Only a yes/no mark (never the text), kept on disk so a copy is cleared even after Android closed Mandat. */
    private const val PREFS = "clipboard"
    private const val KEY = "copied"

    fun markCopied(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }

    private fun copied(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    private fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /**
     * Clears the clipboard when it still holds Mandat's copy. In the foreground (on unlock and on
     * the lock button) the clipboard can be read, so something copied elsewhere is never touched.
     * When the screen turns off ([screenOff]) it may not be readable any more: then Mandat's copy
     * is cleared unless the clipboard plainly holds something else.
     */
    fun clearIfOurs(context: Context, screenOff: Boolean = false) {
        if (!copied(context)) return
        runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java) ?: return
            val label = cm.primaryClipDescription?.label?.toString()
            if (label == null && !screenOff) return
            forget(context)
            if (label == null || label == LABEL || label == OLD_LABEL) cm.clearPrimaryClip()
        }
    }
}

/** While the AI thinks: a small violet spinner and a line. */
@Composable
fun AiBusy() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        CircularProgressIndicator(color = AiViolet, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.ai_thinking), style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

/**
 * The one card for every AI answer: "✦ Suggestion", the text, then Apply (when there is somewhere
 * to put it), Copy and Dismiss. Nothing is kept unless the owner applies it.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AiCard(text: String, title: String? = null, onApply: ((String) -> Unit)? = null, applyText: String? = null, onDismiss: () -> Unit, extra: @Composable ColumnScope.() -> Unit = {}) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    Surface(shape = shape, color = AiViolet.copy(alpha = 0.06f), border = BorderStroke(1.dp, AiViolet.copy(alpha = 0.35f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.card), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$AI_MARK " + (title ?: stringResource(R.string.ai_suggestion)), style = MaterialTheme.typography.labelLarge, color = AiViolet)
            Text(stringResource(R.string.ai_check), style = MaterialTheme.typography.labelSmall, color = Muted)
            // Not selectable: a copy made by selecting would carry no mark and could not be cleared
            Text(text, style = MaterialTheme.typography.bodyMedium)
            extra()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onApply != null) TextButton(onClick = { onApply(text) }) { Text(applyText ?: stringResource(R.string.ai_apply), color = AiViolet) }
                TextButton(onClick = { copyToClipboard(context, text) }) { Text(stringResource(R.string.ai_copy), color = AiViolet) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_dismiss), color = Muted) }
            }
        }
    }
}

/** What an [AiRun] shows: the spinner, a "nothing came" line, or the card. */
@Composable
fun AiResult(run: AiRun, onApply: ((String) -> Unit)? = null, applyText: String? = null, extra: @Composable ColumnScope.() -> Unit = {}) {
    when {
        run.busy -> AiBusy()
        run.failed -> Text(stringResource(R.string.ai_split_nothing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else -> run.answer?.let { a ->
            AiCard(a, title = run.label, onApply = onApply?.let { f -> { t: String -> f(t); run.clear() } }, applyText = applyText, onDismiss = { run.clear() }, extra = extra)
        }
    }
}

/** The name of each AI action, the same wherever it is offered. */
fun taskLabel(task: LocalAi.Task): Int = when (task) {
    LocalAi.Task.BRIEF -> R.string.ai_brief
    LocalAi.Task.WEEK -> R.string.ai_week
    LocalAi.Task.SHORTEN -> R.string.ai_shorten
    LocalAi.Task.FILL -> R.string.ai_fill
    LocalAi.Task.ANSWER -> R.string.ai_ask
    LocalAi.Task.MORNING -> R.string.ai_morning
    LocalAi.Task.EVENING -> R.string.ai_evening
    LocalAi.Task.NEXT_STEP -> R.string.ai_next_step
    LocalAi.Task.MATTER_SUMMARY -> R.string.ai_matter_summary
    LocalAi.Task.FORMAL -> R.string.ai_formal
    LocalAi.Task.SIMPLER -> R.string.ai_simpler
    LocalAi.Task.LANGUAGE -> R.string.ai_language
    LocalAi.Task.REBUTTAL -> R.string.ai_rebuttal
    LocalAi.Task.MESSAGES -> R.string.ai_messages
    LocalAi.Task.MEETING -> R.string.ai_meeting
    LocalAi.Task.REPLY -> R.string.ai_reply
    LocalAi.Task.REMINDER -> R.string.ai_reminder
    LocalAi.Task.RISK -> R.string.ai_risk
    LocalAi.Task.PLAN -> R.string.ai_plan
    LocalAi.Task.DIGEST, LocalAi.Task.DIGEST_JOIN -> R.string.ai_digest
}

/**
 * One inline AI moment: a violet chip, then its answer as the shared card. Shown only when the
 * model is on the phone.
 */
@Composable
fun AiAnswer(session: Session, task: LocalAi.Task, facts: suspend () -> String, onUse: ((String) -> Unit)? = null, useText: String? = null, key: Any? = null, label: String? = null) {
    val scope = rememberCoroutineScope()
    val ready by session.ai.models.ready.collectAsStateWithLifecycle()
    val run = rememberAiRun(session, key)
    if (!ready) return
    val name = label ?: stringResource(taskLabel(task))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!run.busy && run.answer == null) AiChip(name) { run.start(scope, session, task, name, facts) }
        AiResult(run, onUse, useText)
    }
}

/**
 * The single row a screen gives to the AI, folded: "✦ Morning overview". A tap asks; the answer
 * opens under it in the shared card.
 */
@Composable
fun AiRow(session: Session, task: LocalAi.Task, facts: suspend () -> String, onUse: ((String) -> Unit)? = null, useText: String? = null) {
    val scope = rememberCoroutineScope()
    val ready by session.ai.models.ready.collectAsStateWithLifecycle()
    val run = rememberAiRun(session)
    if (!ready) return
    val name = stringResource(taskLabel(task))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!run.busy && run.answer == null) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AiViolet.copy(alpha = 0.08f))
                .clickable { run.start(scope, session, task, name, facts) }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$AI_MARK $name", style = MaterialTheme.typography.labelLarge, color = AiViolet, modifier = Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.titleMedium, color = AiViolet)
        }
        AiResult(run, onUse, useText)
    }
}

/** A suggestion found in a text or a note: ticked ones become records. */
private class Pick(val found: Found, val note: Long? = null, on: Boolean = false) { var on by mutableStateOf(on) }

/** Saves chosen suggestions; [matchId] ties a name to a known person only when it is certain. */
private suspend fun saveFound(d: ua.mandat.data.MandatDb, chosen: List<Found>, matchId: (String) -> Long?, dateOf: (String) -> Long?) =
    saveFoundWith(d, chosen.map { it to null }, matchId, dateOf)

/**
 * Saves suggestions; [Pair.second] is the owner's original note, kept whole in the record (in the
 * description, purpose, source or basis) when the model's line is shorter, so no detail is lost.
 */
private suspend fun saveFoundWith(d: ua.mandat.data.MandatDb, chosen: List<Pair<Found, String?>>, matchId: (String) -> Long?, dateOf: (String) -> Long?) {
    val now = System.currentTimeMillis()
    chosen.forEach { (f, original) ->
        val keep = original?.trim()?.takeIf { it.isNotBlank() && it != f.text.trim() }.orEmpty()
        val personId = f.person.takeIf { it.isNotBlank() }?.let(matchId)
        val due = dateOf(f.due)
        // A name that matched nobody stays in the text, so it is not lost
        val withName = if (personId == null && f.person.isNotBlank()) f.text + " — " + f.person else f.text
        when (f.kind) {
            "matter" -> ua.mandat.data.cleanMatter(matterFromNote(f.text, now).let { if (keep.isNotEmpty()) it.copy(description = keep) else it })?.let { m ->
                val id = d.matters().insert(m)
                d.history().insertAll(ua.mandat.data.changesOf(null, m, id, now, Dates.today()))
            }
            "delegation" -> newDelegation(personId, null).also { it.what = withName; it.due = due; if (keep.isNotEmpty()) it.purpose = keep }.persist(d)
            "promise" -> newPromise(personId, null).also { it.what = f.text; it.due = due; if (personId == null) it.toWhom = f.person; if (keep.isNotEmpty()) it.source = keep }.persist(d)
            // A deadline without a date would land on today: it goes to the Inbox instead
            "deadline" -> if (due != null) newDeadline(null).also { it.title = f.text; it.day = due; if (keep.isNotEmpty()) it.basis = keep }.persist(d)
                else ua.mandat.data.cleanInboxText(withName)?.let { d.inbox().insert(ua.mandat.data.InboxItem(text = it, createdAt = now)) }
            else -> ua.mandat.data.cleanInboxText(withName)?.let { d.inbox().insert(ua.mandat.data.InboxItem(text = it, createdAt = now)) }
        }
    }
}

/** Matching names and dates the same way for every list of suggestions. */
private class Matcher(val people: List<ua.mandat.data.Person>, val today: Long) {
    /** Only a certain match: the same name, or the one person whose name starts with it. */
    fun person(name: String): ua.mandat.data.Person? {
        val n = name.trim().lowercase()
        if (n.length < 2) return null
        people.filter { it.name.isNotBlank() && it.name.trim().lowercase() == n }.singleOrNull()?.let { return it }
        return people.filter { it.name.isNotBlank() && it.name.trim().lowercase().startsWith(n) }.singleOrNull()
    }
    /** A date from the model, unless it is clearly off (more than a year back or five years ahead). */
    fun date(s: String): Long? = runCatching { java.time.LocalDate.parse(s).toEpochDay() }.getOrNull()?.takeIf { it in (today - 365)..(today + 5 * 365) }
}

/** The ticked list of suggestions, the same in "Split a text" and "Sort the Inbox". */
@Composable
private fun PickList(picks: List<Pick>, matcher: Matcher, source: ((Pick) -> String?)? = null) {
    val notFound = stringResource(R.string.ai_person_not_found)
    val linkedTo = stringResource(R.string.ai_person_linked)
    val noDate = stringResource(R.string.ai_no_date_inbox)
    val kindLabel = mapOf(
        "matter" to stringResource(R.string.ai_kind_matter), "delegation" to stringResource(R.string.ai_kind_delegation),
        "promise" to stringResource(R.string.ai_kind_promise), "deadline" to stringResource(R.string.ai_kind_deadline), "note" to stringResource(R.string.ai_kind_note),
    )
    QuickChip(stringResource(R.string.ai_tick_all), false) { picks.forEach { it.on = true } }
    picks.forEach { p ->
        val f = p.found
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { p.on = !p.on }) {
            Checkbox(checked = p.on, onCheckedChange = { p.on = it })
            Column(Modifier.weight(1f)) {
                source?.invoke(p)?.let { Text("«$it»", style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(f.text, style = MaterialTheme.typography.bodyMedium)
                // What will really be saved: whom it is linked to, and the date (or where it goes without one)
                val who = f.person.takeIf { it.isNotBlank() }?.let { n -> matcher.person(n)?.let { linkedTo + " " + it.name } ?: (notFound + ": " + n) }
                val date = matcher.date(f.due)?.let { Dates.format(it) } ?: if (f.kind == "deadline") noDate else null
                Text(listOfNotNull(kindLabel[f.kind], who, date).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = AiViolet)
            }
        }
    }
}

/**
 * Paste a letter or notes from a meeting, or empty the head in the evening: the model finds
 * matters, delegations, promises, deadlines and notes. Only the ticked ones are saved.
 */
@Composable
fun AiSplitSheet(db: ua.mandat.data.MandatDb, session: Session, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    // The text lives in the Session: on lock it is kept in the Inbox
    val text = session.aiSplitText
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val picks = remember { mutableStateListOf<Pick>() }
    val matcher = Matcher(people, Dates.today())
    EditSheet(
        title = "$AI_MARK " + stringResource(R.string.ai_split_title),
        // Closed without saving: the text is kept in the Inbox, so nothing typed is lost
        onDismiss = {
            ua.mandat.data.cleanInboxText(session.aiSplitText)?.let { t -> session.write { d -> d.inbox().insert(ua.mandat.data.InboxItem(text = t, createdAt = System.currentTimeMillis())) } }
            session.aiSplitText = ""
            onClose()
        },
        onSave = if (picks.any { it.on }) ({
            val chosen = picks.filter { it.on }.map { it.found }
            session.write { d -> d.withTransaction { saveFound(d, chosen, { matcher.person(it)?.id }, matcher::date) } }
            session.aiSplitText = ""
            onClose()
        }) else null,
        saveText = stringResource(R.string.ai_split_save, picks.count { it.on }),
    ) {
        SheetGroup {
            Text(stringResource(R.string.ai_split_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
            OutlinedTextField(text, { session.aiSplitText = it.take(LocalAi.MAX_INPUT) }, label = { Text(stringResource(R.string.ai_split_text)) }, minLines = 6, modifier = Modifier.fillMaxWidth())
            if (busy) AiBusy()
            else if (text.isNotBlank()) AiChip(stringResource(R.string.ai_split_go)) {
                busy = true; failed = false
                scope.launch {
                    val r = runCatching { session.ai.split(text, people.map { it.name }, java.time.LocalDate.now().toString()) }.getOrNull()
                    busy = false
                    picks.clear()
                    // Nothing is ticked beforehand: each suggestion is read before it is saved
                    if (r.isNullOrEmpty()) failed = true else picks.addAll(r.map { Pick(it) })
                }
            }
            if (failed) Text(stringResource(R.string.ai_split_nothing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (picks.isNotEmpty()) SheetGroup(stringResource(R.string.ai_split_found)) { PickList(picks, matcher) }
    }
}

/**
 * Sorting the Inbox: for each quick note, what it most likely is. Saving a ticked suggestion
 * creates the record and removes its note from the Inbox; unticked notes stay as they are.
 */
@Composable
fun InboxSortSheet(db: ua.mandat.data.MandatDb, session: Session, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    val inbox by remember(db) { db.inbox().observeAll().hidingNamed(session, { null }) { it.text } }.collectAsStateWithLifecycle(emptyList())
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val picks = remember { mutableStateListOf<Pick>() }
    val notes = remember { mutableStateMapOf<Long, String>() }
    // Notes already offered in this sheet: "Sort" goes on to the next ones
    val offered = remember { mutableStateListOf<Long>() }
    val matcher = Matcher(people, Dates.today())
    EditSheet(
        title = "$AI_MARK " + stringResource(R.string.ai_sort_inbox),
        onDismiss = onClose,
        onSave = if (picks.any { it.on }) ({
            // Read on the main thread: what is ticked, and the notes as they were sorted
            fun isRecord(f: Found) = f.kind != "note" && !(f.kind == "deadline" && matcher.date(f.due) == null)
            val all = picks.map { Triple(it.note, it.found, it.on) }
            val noteText = notes.toMap()
            // A note leaves the Inbox only when all that was made from it is saved, it was read whole,
            // and each part became a record that can hold the original (a deadline cannot: then the note stays)
            val toDelete = all.filter { it.first != null }.groupBy { it.first!! }.filter { (note, from) ->
                (noteText[note]?.length ?: Int.MAX_VALUE) <= LocalAi.NOTE_CHARS && from.all { it.third && isRecord(it.second) && it.second.kind != "deadline" }
            }.keys
            // A note or an undated deadline stays as the original note itself, not as a copy of the model's line;
            // a record keeps the whole original only when that note leaves the Inbox
            val toSave = all.filter { it.third && isRecord(it.second) }.map { t -> t.second to t.first?.takeIf { it in toDelete }?.let(noteText::get) }
            session.write { d ->
                d.withTransaction {
                    saveFoundWith(d, toSave, { matcher.person(it)?.id }, matcher::date)
                    toDelete.forEach { d.inbox().delete(it) }
                }
            }
            onClose()
        }) else null,
        saveText = stringResource(R.string.ai_split_save, picks.count { it.on }),
    ) {
        SheetGroup {
            Text(stringResource(R.string.ai_sort_hint, inbox.size), style = MaterialTheme.typography.bodyMedium, color = Muted)
            if (busy) AiBusy()
            else if (inbox.isNotEmpty()) AiChip(stringResource(if (offered.isEmpty()) R.string.ai_sort_go else R.string.ai_sort_next)) {
                busy = true; failed = false
                scope.launch {
                    // A note that names a hidden matter is not sent while it is hidden
                    val named = session.withOpenDb { d -> d.namesHidden(session) }
                    if (named == null) { busy = false; return@launch }
                    // Long notes are left for "Split a text": they cannot be read whole here
                    val batch = inbox.filter { it.text.length <= LocalAi.NOTE_CHARS && it.id !in offered && !named(it.text) }.take(LocalAi.SORT_BATCH)
                    offered.addAll(batch.map { it.id })
                    val r = if (batch.isEmpty()) null else runCatching { session.ai.sortInbox(batch.map { it.text }, people.map { it.name }, java.time.LocalDate.now().toString()) }.getOrNull()
                    busy = false
                    picks.clear(); notes.clear()
                    batch.forEach { notes[it.id] = it.text }
                    if (r.isNullOrEmpty()) failed = true else picks.addAll(r.map { (i, f) -> Pick(f, batch[i].id) })
                }
            }
            if (failed) Text(stringResource(R.string.ai_split_nothing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (picks.isNotEmpty()) SheetGroup(stringResource(R.string.ai_split_found)) { PickList(picks, matcher) { p -> p.note?.let { notes[it] } } }
    }
}

/** Plain facts about a person for the brief suggestion; hidden matters are left out. */
suspend fun briefFacts(db: ua.mandat.data.MandatDb, session: Session, personId: Long): String {
    val hidden = session.hiddenMatters.value
    fun ok(id: Long?) = id == null || id !in hidden
    val p = db.people().all().firstOrNull { it.id == personId } ?: return ""
    val named = db.namesHidden(session)
    fun free(id: Long?, text: String) = ok(id) && !(id == null && named(text))
    val matters = db.people().observeMattersOf(personId).first().filter { ok(it.matterId) }
    val promises = db.promises().observeForPerson(personId).first().filter { free(it.matterId, it.what) && ua.mandat.data.TaskStatus.of(it.status) == ua.mandat.data.TaskStatus.OPEN }
    val asked = db.delegations().observeForPerson(personId).first().filter { free(it.matterId, it.what) && ua.mandat.data.TaskStatus.of(it.status) == ua.mandat.data.TaskStatus.OPEN }
    val events = db.events().observeForPerson(personId).first().filter { free(it.matterId, it.title + " " + it.outcome) }.sortedByDescending { it.day }.take(5)
    return buildString {
        append("Людина: ").append(p.name).append(". ").append(listOf(p.jobTitle, p.organization).filter { it.isNotBlank() }.joinToString(", ")).append("\n")
        if (p.notes.isNotBlank() && !named(p.notes)) append("Нотатки: ").append(p.notes.take(800)).append("\n")
        if (matters.isNotEmpty()) append("Спільні справи: ").append(matters.joinToString("; ") { it.title + (it.stance?.let { s -> " (позиція: $s)" } ?: "") }).append("\n")
        if (promises.isNotEmpty()) append("Ми обіцяли: ").append(promises.joinToString("; ") { it.what }).append("\n")
        if (asked.isNotEmpty()) append("Ми доручили цій людині: ").append(asked.joinToString("; ") { it.what }).append("\n")
        if (events.isNotEmpty()) append("Зустрічі: ").append(events.joinToString("; ") { factDate(it.day) + " " + it.title + (it.outcome.takeIf { o -> o.isNotBlank() }?.let { o -> " — $o" } ?: "") }).append("\n")
    }
}
