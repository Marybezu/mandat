package ua.mandat.ui

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import ua.mandat.R
import ua.mandat.data.MandatDb
import ua.mandat.data.TextDoc
import ua.mandat.data.TextKind
import ua.mandat.data.speakingMinutes
import ua.mandat.data.wordCount

fun TextKind.label(): Int = when (this) {
    TextKind.LETTER -> R.string.text_kind_letter
    TextKind.SPEECH -> R.string.text_kind_speech
    TextKind.COLUMN -> R.string.text_kind_column
    TextKind.NOTE -> R.string.text_kind_note
    TextKind.DRAFT -> R.string.text_kind_draft
    TextKind.REPORT -> R.string.text_kind_report
    TextKind.OTHER -> R.string.text_kind_other
    TextKind.TEMPLATE -> R.string.text_kind_template
}

/** A ready template: typical text with [fields] to fill in. */
class TextTemplate(val key: String, val kind: TextKind, val title: Int, val body: Int)

val TEXT_TEMPLATES = listOf(
    TextTemplate("appeal", TextKind.LETTER, R.string.tpl_appeal_title, R.string.tpl_appeal_body),
    TextTemplate("inquiry", TextKind.LETTER, R.string.tpl_inquiry_title, R.string.tpl_inquiry_body),
    TextTemplate("reply", TextKind.LETTER, R.string.tpl_reply_title, R.string.tpl_reply_body),
    TextTemplate("invite", TextKind.LETTER, R.string.tpl_invite_title, R.string.tpl_invite_body),
    TextTemplate("cover", TextKind.LETTER, R.string.tpl_cover_title, R.string.tpl_cover_body),
    TextTemplate("thanks", TextKind.LETTER, R.string.tpl_thanks_title, R.string.tpl_thanks_body),
    TextTemplate("greeting", TextKind.LETTER, R.string.tpl_greeting_title, R.string.tpl_greeting_body),
    TextTemplate("speech", TextKind.SPEECH, R.string.tpl_speech_title, R.string.tpl_speech_body),
    TextTemplate("column", TextKind.COLUMN, R.string.tpl_column_title, R.string.tpl_column_body),
    TextTemplate("note", TextKind.NOTE, R.string.tpl_note_title, R.string.tpl_note_body),
)

/** Fields still to fill in: anything in square brackets. */
private val FIELD = Regex("\\[[^\\[\\]\\n]{1,200}\\]")

/** All texts (speeches, letters, columns, notes…), newest first, with a filter by kind. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TextsScreen(db: MandatDb, session: Session) {
    BackHandler { session.showTexts = false }
    val texts by remember(db) { db.texts().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val titleOf = titles.associate { it.id to it.title }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    val own = texts.filter { it.kind == TextKind.TEMPLATE.name }
    val q = query.trim().lowercase()
    val shown = texts.filter { it.kind != TextKind.TEMPLATE.name && (kind == null || it.kind == kind) &&
        (q.length < 2 || it.title.lowercase().contains(q) || it.body.lowercase().contains(q)) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.texts_title)) },
                navigationIcon = { IconButton(onClick = { session.showTexts = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.gap),
        ) {
            item(key = "new") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickChip("+ " + stringResource(R.string.text_new), false) { session.textDraft = newText() }
                    QuickChip(stringResource(R.string.text_from_template), false) { choosing = true }
                }
            }
            item(key = "kinds") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickChip(stringResource(R.string.matters_all_roles), kind == null) { kind = null }
                    TextKind.entries.filter { it != TextKind.TEMPLATE }.forEach { k ->
                        val n = texts.count { it.kind == k.name }
                        if (n > 0) QuickChip(stringResource(k.label()) + " · " + n, kind == k.name) { kind = if (kind == k.name) null else k.name }
                    }
                }
            }
            if (texts.size > 5) item(key = "search") {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth())
            }
            item(key = "list") {
                SectionCard(Sym.folder, stringResource(R.string.texts_title), count = shown.size) {
                    if (shown.isEmpty()) EmptyHint(Sym.info, stringResource(R.string.texts_empty))
                    Paged(shown) { t -> TextLine(t, t.matterId?.let(titleOf::get)) { session.textDraft = TextDraft(t, isNew = false) } }
                }
            }
            if (own.isNotEmpty()) item(key = "own") {
                SectionCard(Sym.layers, stringResource(R.string.texts_own_templates), count = own.size) {
                    Paged(own) { t -> TextLine(t, null) { session.textDraft = TextDraft(t, isNew = false) } }
                }
            }
        }
    }
    if (choosing) TemplatePicker(own, onDismiss = { choosing = false }) { draft -> choosing = false; session.textDraft = draft }
}

@Composable
private fun TextLine(t: TextDoc, matterTitle: String?, onOpen: () -> Unit) {
    val words = wordCount(t.body)
    val meta = listOfNotNull(stringResource(TextKind.of(t.kind).label()), stringResource(R.string.text_words, words), Dates.format(Dates.dayOf(t.updatedAt)), matterTitle)
    TextTile(t.title, meta.joinToString(" · "), onOpen)
}

/** New text from a ready template or from one of the owner's own. */
@Composable
fun TemplatePicker(own: List<TextDoc>, matterId: Long? = null, onDismiss: () -> Unit, onPick: (TextDraft) -> Unit) {
    val builtIn = TEXT_TEMPLATES.map { t -> PickItem("b:" + t.key, stringResource(t.title), stringResource(t.kind.label())) }
    val mine = own.map { PickItem("o:" + it.id, it.title, stringResource(R.string.text_kind_template)) }
    val bodies = TEXT_TEMPLATES.associate { "b:" + it.key to Triple(stringResource(it.title), stringResource(it.body), it.kind) }
    PickSheet(stringResource(R.string.text_from_template), builtIn + mine, onPick = { key ->
        if (key.startsWith("b:")) bodies[key]?.let { (title, body, kind) -> onPick(newText(matterId, kind, title, body)) }
        else own.firstOrNull { "o:" + it.id == key }?.let { o -> onPick(newText(matterId, TextKind.OTHER, o.title, o.body)) }
    }, onDismiss = onDismiss)
}

/**
 * Writing a text: title, kind, matter, and the body with light marks ("# " heading, "- " list).
 * Words and speaking time are counted as you go; fields in [brackets] are found one by one.
 * Earlier versions can be brought back; the text goes out as Word, PDF or Markdown through
 * Export, with a password by default.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextEditor(db: MandatDb, session: Session, draft: TextDraft) {
    val matters by remember(db) { db.matters().observeActiveTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val allMatters by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    var value by remember(draft) { mutableStateOf(TextFieldValue(draft.body)) }
    var versions by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<Int?>(null) }
    val sensitive by remember(db) { db.matters().observeSensitiveIds() }.collectAsStateWithLifecycle(emptyList())
    val words = wordCount(value.text)
    val fields = FIELD.findAll(value.text).toList()
    // An AI answer belongs to the text it was made from; while the AI reads it, the text is not edited
    val run = rememberAiRun(session, value.text)
    fun nextField() {
        val from = value.selection.max
        val next = fields.firstOrNull { it.range.first >= from } ?: fields.firstOrNull() ?: return
        value = value.copy(selection = TextRange(next.range.first, next.range.last + 1))
    }
    EditSheet(
        title = stringResource(if (draft.kind == TextKind.TEMPLATE) R.string.text_kind_template else R.string.text_title_one),
        onDismiss = { session.textDraft = null },
        changed = draft.changed(),
        onSave = { session.saveTextDraft() },
        saveEnabled = draft.valid(),
    ) {
        SheetGroup {
            OutlinedTextField(draft.title, { draft.title = it }, label = { Text(stringResource(R.string.text_name)) }, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextKind.entries.forEach { k -> QuickChip(stringResource(k.label()), draft.kind == k) { draft.kind = k } }
            }
            if (draft.kind != TextKind.TEMPLATE) MatterPicker(matters, allMatters, draft.matterId) { draft.matterId = it }
        }
        SheetGroup {
            // Words, time to read aloud and fields left: the length is always in view
            Text(stringResource(R.string.text_stats, words, speakingMinutes(words)) +
                if (fields.isNotEmpty()) " · " + stringResource(R.string.text_fields_left, fields.size) else "",
                style = MaterialTheme.typography.labelLarge, color = if (fields.isNotEmpty()) ChartColors.amber else Muted)
            if (fields.isNotEmpty()) QuickChip(stringResource(R.string.text_next_field), false) { nextField() }
            OutlinedTextField(
                value, { v ->
                    // The field itself stops at the limit, so what is seen is what is saved
                    val max = ua.mandat.data.MAX_TEXT_LENGTH
                    value = if (v.text.length <= max) v else v.copy(text = v.text.take(max), selection = TextRange(minOf(v.selection.start, max), minOf(v.selection.end, max)))
                    if (v.text.length > max) info = R.string.text_limit
                    draft.body = value.text
                },
                label = { Text(stringResource(R.string.text_body)) }, minLines = 14, readOnly = run.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
            )
            Text(stringResource(R.string.text_marks_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
            // The on-device AI as an editor, behind one "✦ Help" button: shorter, more formal, simpler,
            // fields filled from the matter, a language check. A new version replaces the text only on a
            // tap, and the old one stays among the versions once saved
            val matterFacts = allMatters.firstOrNull { it.id == draft.matterId }?.title
            // Only a text the model can read whole: otherwise "replace" would lose its end
            val fits = value.text.length <= ua.mandat.ai.LocalAi.MAX_INPUT - 200
            val aiReady by session.ai.models.ready.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            var menu by remember { mutableStateOf(false) }
            LaunchedEffect(session.aiTextHelp) { if (session.aiTextHelp) { menu = aiReady; session.aiTextHelp = false } }
            if (aiReady && value.text.isNotBlank()) {
                if (!fits) Text(stringResource(R.string.ai_text_too_long), style = MaterialTheme.typography.labelSmall, color = Muted)
                else Box {
                    if (!run.busy && run.answer == null) AiChip(stringResource(R.string.ai_help)) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        fun ask(task: ua.mandat.ai.LocalAi.Task, label: String, facts: String) {
                            menu = false
                            run.start(scope, session, task, label) { facts }
                        }
                        val shorten = stringResource(R.string.ai_shorten)
                        val formal = stringResource(R.string.ai_formal)
                        val simpler = stringResource(R.string.ai_simpler)
                        val fill = stringResource(R.string.ai_fill)
                        val language = stringResource(R.string.ai_language)
                        // Only a text long enough to shorten: about two thirds, at most three minutes aloud
                        if (words > 120) DropdownMenuItem(text = { Text(shorten) }, onClick = {
                            ask(ua.mandat.ai.LocalAi.Task.SHORTEN, shorten, "Скороти приблизно до " + minOf(words * 2 / 3, 360) + " слів.\n\n" + value.text)
                        })
                        DropdownMenuItem(text = { Text(formal) }, onClick = { ask(ua.mandat.ai.LocalAi.Task.FORMAL, formal, value.text) })
                        DropdownMenuItem(text = { Text(simpler) }, onClick = { ask(ua.mandat.ai.LocalAi.Task.SIMPLER, simpler, value.text) })
                        if (fields.isNotEmpty()) DropdownMenuItem(text = { Text(fill) }, onClick = {
                            ask(ua.mandat.ai.LocalAi.Task.FILL, fill, "Довідка: " + listOfNotNull(draft.title, matterFacts).joinToString("; ") + "\n\nТекст:\n" + value.text)
                        })
                        DropdownMenuItem(text = { Text(language) }, onClick = { ask(ua.mandat.ai.LocalAi.Task.LANGUAGE, language, value.text) })
                    }
                }
                // A language check is a list of remarks, not a new text: it is only read or copied
                val replaces = run.label != stringResource(R.string.ai_language)
                AiResult(run, onApply = if (replaces) ({ a -> value = TextFieldValue(a); draft.body = a }) else null, applyText = stringResource(R.string.ai_replace))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!draft.isNew) QuickChip(stringResource(R.string.text_versions), false) { versions = true }
            if (!draft.isNew && draft.valid()) QuickChip(stringResource(R.string.text_export), false) {
                val id = draft.doc.id
                session.saveTextDraft()
                session.exportText = id
                session.showExport = true
            }
            if (draft.kind != TextKind.TEMPLATE) QuickChip(stringResource(R.string.text_save_as_template), false) {
                // A copy becomes the owner's template; the text itself stays as it is. A sensitive
                // matter's text never becomes a template, since templates show even in trip mode
                if (draft.matterId != null && draft.matterId in sensitive) info = R.string.text_template_sensitive
                else {
                    val copy = newText(null, TextKind.TEMPLATE, draft.title, draft.body)
                    session.write { copy.persist(it) }
                    info = R.string.text_template_saved
                }
            }
        }
        if (!draft.isNew) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.padding(4.dp))
    }
    if (versions) {
        val list by remember(db, draft.doc.id) { db.texts().observeVersions(draft.doc.id) }.collectAsStateWithLifecycle(emptyList())
        PickSheet(
            stringResource(R.string.text_versions),
            list.map { v -> PickItem(v.id, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(v.savedAt)),
                stringResource(R.string.text_words, wordCount(v.body)) + " · " + v.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(80)) },
            onPick = { id ->
                // Bringing back an earlier version: the current body becomes a version itself when saved
                list.firstOrNull { it.id == id }?.let { v -> value = TextFieldValue(v.body); draft.body = v.body }
                versions = false
            },
            onDismiss = { versions = false },
            footer = { if (list.isEmpty()) EmptyHint(Sym.info, stringResource(R.string.text_no_versions)) },
        )
    }
    info?.let { m -> ConfirmDialog(null, stringResource(m), stringResource(R.string.ok), onConfirm = { info = null }, onCancel = { info = null }) }
    if (confirmDelete) ConfirmDelete(onConfirm = {
        val id = draft.doc.id
        session.textDraft = null
        session.write { db2 -> db2.withTransaction { db2.texts().deleteVersions(id); db2.texts().delete(id) } }
    }, onCancel = { confirmDelete = false })
}

/** Texts of one matter, inside its card. */
@Composable
fun MatterTexts(db: MandatDb, session: Session, matterId: Long) {
    val texts by remember(db, matterId) { db.texts().observeForMatter(matterId) }.collectAsStateWithLifecycle(emptyList())
    val own by remember(db) { db.texts().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    var choosing by remember { mutableStateOf(false) }
    Text(stringResource(R.string.texts_title), style = MaterialTheme.typography.labelLarge)
    texts.forEach { t -> TextLine(t, null) { session.textDraft = TextDraft(t, isNew = false) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickChip("+ " + stringResource(R.string.text_new), false) { session.textDraft = newText(matterId) }
        QuickChip(stringResource(R.string.text_from_template), false) { choosing = true }
    }
    if (choosing) TemplatePicker(own.filter { it.kind == TextKind.TEMPLATE.name }, matterId, onDismiss = { choosing = false }) { d -> choosing = false; session.textDraft = d }
}
