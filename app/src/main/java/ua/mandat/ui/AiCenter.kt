package ua.mandat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ua.mandat.R
import ua.mandat.ai.LocalAi
import ua.mandat.data.InfoKind
import ua.mandat.data.MandatDb
import ua.mandat.data.SearchRow

/** What an AI action needs chosen first. */
enum class AiPick { NONE, MATTER, PERSON, TEXT, ATTACK, EVENT, DELEGATION }

/** The groups of the AI Center, in the order of a working day. */
enum class AiGroup(val title: Int) {
    DAY(R.string.ai_g_day), RECORDS(R.string.ai_g_records), MATTERS(R.string.ai_g_matters), PEOPLE(R.string.ai_g_people),
    TEXTS(R.string.ai_g_texts), MEDIA(R.string.ai_g_media), REVIEW(R.string.ai_g_review),
}

/** Every AI action in Mandat, each in one group; inline moments on screens use the same names. */
enum class AiAct(val group: AiGroup, val title: Int, val hint: Int, val pick: AiPick, val task: LocalAi.Task?) {
    MORNING(AiGroup.DAY, R.string.ai_morning, R.string.ai_morning_hint, AiPick.NONE, LocalAi.Task.MORNING),
    EVENING(AiGroup.DAY, R.string.ai_evening, R.string.ai_evening_hint, AiPick.NONE, LocalAi.Task.EVENING),
    MEETING(AiGroup.DAY, R.string.ai_meeting, R.string.ai_meeting_hint, AiPick.EVENT, LocalAi.Task.MEETING),
    SPLIT(AiGroup.RECORDS, R.string.ai_split_title, R.string.ai_split_short, AiPick.NONE, null),
    SORT(AiGroup.RECORDS, R.string.ai_sort_inbox, R.string.ai_sort_short, AiPick.NONE, null),
    NEXT(AiGroup.MATTERS, R.string.ai_next_step, R.string.ai_next_hint, AiPick.MATTER, LocalAi.Task.NEXT_STEP),
    SUMMARY(AiGroup.MATTERS, R.string.ai_matter_summary, R.string.ai_summary_hint, AiPick.MATTER, LocalAi.Task.MATTER_SUMMARY),
    RISK(AiGroup.MATTERS, R.string.ai_risk, R.string.ai_risk_hint, AiPick.NONE, LocalAi.Task.RISK),
    BRIEF(AiGroup.PEOPLE, R.string.ai_brief, R.string.ai_brief_hint, AiPick.PERSON, LocalAi.Task.BRIEF),
    REMINDER(AiGroup.PEOPLE, R.string.ai_reminder, R.string.ai_reminder_hint, AiPick.DELEGATION, LocalAi.Task.REMINDER),
    TEXT_HELP(AiGroup.TEXTS, R.string.ai_text_help, R.string.ai_text_help_hint, AiPick.TEXT, null),
    REPLY(AiGroup.TEXTS, R.string.ai_reply, R.string.ai_reply_hint, AiPick.NONE, null),
    DIGEST(AiGroup.TEXTS, R.string.ai_digest, R.string.ai_digest_hint, AiPick.NONE, null),
    REBUTTAL(AiGroup.MEDIA, R.string.ai_rebuttal, R.string.ai_rebuttal_hint, AiPick.ATTACK, LocalAi.Task.REBUTTAL),
    MESSAGES(AiGroup.MEDIA, R.string.ai_messages, R.string.ai_messages_hint, AiPick.MATTER, LocalAi.Task.MESSAGES),
    WEEK(AiGroup.REVIEW, R.string.ai_week_center, R.string.ai_week_hint, AiPick.NONE, LocalAi.Task.WEEK),
    PLAN(AiGroup.REVIEW, R.string.ai_plan, R.string.ai_plan_hint, AiPick.NONE, LocalAi.Task.PLAN),
}

/** Two or three actions that fit the screen the Center was opened from. */
fun contextActs(tab: String?): List<AiAct> = when (tab) {
    "Today" -> listOf(AiAct.MORNING, AiAct.MEETING, AiAct.SORT)
    "Matters" -> listOf(AiAct.NEXT, AiAct.SUMMARY, AiAct.RISK)
    "Review" -> listOf(AiAct.WEEK, AiAct.PLAN, AiAct.EVENING)
    "People" -> listOf(AiAct.BRIEF, AiAct.REMINDER)
    "Time" -> listOf(AiAct.MEETING, AiAct.MORNING, AiAct.EVENING)
    else -> emptyList()
}

/** The facts an action reads; hidden matters never reach them. */
private suspend fun factsFor(act: AiAct, db: MandatDb, session: Session, id: Long?): String = when (act) {
    AiAct.MORNING -> todayFacts(db, session)
    AiAct.EVENING -> eveningFacts(db, session)
    AiAct.NEXT, AiAct.SUMMARY, AiAct.MESSAGES -> id?.let { matterFacts(db, session, it) }.orEmpty()
    AiAct.BRIEF -> id?.let { briefFacts(db, session, it) }.orEmpty()
    AiAct.REBUTTAL -> id?.let { attackFacts(db, session, it) }.orEmpty()
    AiAct.WEEK -> weekFacts(db, session)
    AiAct.RISK -> riskFacts(db, session)
    AiAct.PLAN -> planFacts(db, session)
    AiAct.MEETING -> id?.let { db.events().get(it) }?.let { e ->
        meetingFacts(db, session, e.title, e.day, e.startMin, e.matterId, db.events().peopleOf(e.id).toSet(), e.notes)
    }.orEmpty()
    AiAct.REMINDER -> id?.let { reminderFacts(db, session, it) }.orEmpty()
    else -> ""
}

/** Record numbers the answer cites, like [2], as indexes into the records it was given. */
fun citedIndexes(answer: String, count: Int): List<Int> =
    Regex("\\[(\\d{1,2})]").findAll(answer).mapNotNull { it.groupValues[1].toIntOrNull()?.minus(1) }.filter { it in 0 until count }.distinct().toList()

/**
 * The AI Center: one place with everything the on-device AI can do. A question to Mandat on top
 * (answered only from the records, with links to them), what fits this screen, then every action
 * by group. Without the model it explains the same list and leads to the settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiCenter(db: MandatDb, session: Session) {
    val ready by session.ai.models.ready.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val run = rememberAiRun(session)
    var question by remember { mutableStateOf("") }
    var hits by remember(run) { mutableStateOf<List<SearchRow>>(emptyList()) }
    var picking by remember { mutableStateOf<AiAct?>(null) }
    // A sheet of its own for an action that needs a pasted text (a letter to answer, a long document)
    var sheet by remember { mutableStateOf<AiAct?>(null) }
    // What the answer on screen belongs to, so "Apply" knows where it goes
    var asked by remember(run) { mutableStateOf<Pair<AiAct, Long?>?>(null) }
    val from = session.aiCenter
    fun close() { session.aiCenter = null }
    val labels = AiAct.entries.associateWith { stringResource(it.title) }

    fun runAct(act: AiAct, id: Long?) {
        when (act) {
            AiAct.SPLIT -> { close(); session.aiSplitText = ""; session.showAiSplit = true }
            AiAct.SORT -> { close(); session.showInboxSort = true }
            AiAct.REPLY, AiAct.DIGEST -> sheet = act
            AiAct.TEXT_HELP -> if (id != null) session.readThenShow({ d -> d.texts().get(id) }) { t ->
                session.textDraft = TextDraft(t, isNew = false)
                session.aiTextHelp = true
                close()
            }
            else -> {
                hits = emptyList()
                asked = act to id
                val task = act.task ?: return
                run.start(scope, session, task, labels.getValue(act)) { session.withOpenDb { d -> factsFor(act, d, session, id) }.orEmpty() }
            }
        }
    }
    fun choose(act: AiAct) { if (act.pick == AiPick.NONE) runAct(act, null) else picking = act }

    SheetWindow("$AI_MARK " + stringResource(R.string.ai_center), onDismiss = { close() }) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.gap),
            verticalArrangement = Arrangement.spacedBy(Space.gap + 4.dp),
        ) {
            // Said first, so nobody wonders where the data goes
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.Icon(Sym.lock, contentDescription = null, tint = AiViolet, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.ai_local), style = MaterialTheme.typography.labelLarge, color = AiViolet)
            }
            if (!ready) SheetGroup {
                Text(stringResource(R.string.ai_off), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { close(); session.optionsRequest = true }) { Text(stringResource(R.string.ai_turn_on)) }
            } else {
                SheetGroup(stringResource(R.string.ai_ask_title)) {
                    OutlinedTextField(question, { question = it.take(500) }, placeholder = { Text(stringResource(R.string.ai_ask_hint)) },
                        minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                    if (question.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiChip(stringResource(R.string.ai_ask)) {
                            val q = question.trim()
                            asked = null
                            run.start(scope, session, LocalAi.Task.ANSWER, q) {
                                val (facts, rows) = session.withOpenDb { d -> askFacts(d, session, q) } ?: ("" to emptyList())
                                hits = rows
                                facts
                            }
                        }
                        AiChip(stringResource(R.string.ai_split_points)) {
                            session.aiSplitText = question.take(LocalAi.MAX_INPUT)
                            question = ""
                            close()
                            session.showAiSplit = true
                        }
                    }
                }
                val act = asked?.first
                AiResult(
                    run,
                    onApply = when (act) {
                        AiAct.EVENING -> { a ->
                            // Added under what is already written tonight, in the field; saved like typing
                            val typed = session.reviewDraft
                            if (typed != null) { session.reviewDraft = listOf(typed, a).filter { it.isNotBlank() }.joinToString("\n\n"); session.tabRequest = "Review"; close() }
                            else session.readThenShow({ d -> d.reviews().forDay(Dates.today())?.let { r -> r.id to r.progress } ?: (0L to "") }) { (rid, text) ->
                                // Tonight's entry hidden: not shown in the field; the answer is added under it on saving
                                val hiddenNow = rid in session.hidden.value.reviews
                                val before = if (hiddenNow) "" else text
                                session.reviewAppend = hiddenNow
                                session.reviewDraft = listOf(before, a).filter { it.isNotBlank() }.joinToString("\n\n")
                                session.tabRequest = "Review"
                                close()
                            }
                        }
                        AiAct.NEXT -> asked?.second?.let { id -> { a: String -> close(); session.openMatter(id, nextStep = a.lines().first().trim().take(200)) } }
                        else -> null
                    },
                    applyText = when (act) {
                        AiAct.EVENING -> stringResource(R.string.ai_to_diary)
                        AiAct.NEXT -> stringResource(R.string.ai_to_matter)
                        else -> null
                    },
                ) {
                    // Where the answer came from: the records it cites, each a link
                    val cited = run.answer?.let { a -> citedIndexes(a, hits.size).take(6).map { hits[it] } }.orEmpty()
                    if (cited.isNotEmpty()) {
                        Text(stringResource(R.string.ai_sources), style = MaterialTheme.typography.labelMedium, color = Muted)
                        cited.forEachIndexed { i, h ->
                            Text("[" + (hits.indexOf(h) + 1) + "] " + h.title + " · " + stringResource(kindLabel(h.kind)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clickable { close(); openHit(db, session, h.kind, h.id) }.padding(vertical = 6.dp))
                        }
                    }
                }
                val here = contextActs(from)
                if (here.isNotEmpty()) SheetGroup(stringResource(R.string.ai_for_screen)) {
                    here.forEach { a -> ActRow(a, true) { choose(a) } }
                }
            }
            AiGroup.entries.forEach { g ->
                SheetGroup(stringResource(g.title)) {
                    AiAct.entries.filter { it.group == g }.forEach { a -> ActRow(a, ready) { choose(a) } }
                }
            }
            Text(stringResource(R.string.ai_footer), style = MaterialTheme.typography.labelSmall, color = Muted,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }
    }
    when (sheet) {
        AiAct.REPLY -> ReplySheet(db, session, onDone = { sheet = null; close() }) { sheet = null }
        AiAct.DIGEST -> DigestSheet(session, onDone = { sheet = null; close() }) { sheet = null }
        else -> {}
    }
    picking?.let { act -> AiPicker(db, session, act, onDismiss = { picking = null }) { id -> picking = null; runAct(act, id) } }
}

/** One action: the mark and its name in violet, a line on what it gives; quiet when the model is off. */
@Composable
private fun ActRow(act: AiAct, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .let { if (enabled) it.clickable(onClick = onClick) else it }.padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("$AI_MARK " + stringResource(act.title), style = MaterialTheme.typography.bodyLarge, color = if (enabled) AiViolet else Muted)
            Text(stringResource(act.hint), style = MaterialTheme.typography.labelMedium, color = Muted)
        }
        if (enabled) Text(if (act.pick == AiPick.NONE) "›" else "…", style = MaterialTheme.typography.titleMedium, color = AiViolet)
    }
}

/** Choosing what an action is about: a matter, a person, a text or an attack (hidden ones are not offered). */
@Composable
private fun AiPicker(db: MandatDb, session: Session, act: AiAct, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val items by remember(db, act) {
        when (act.pick) {
            AiPick.MATTER -> db.matters().observeActiveTitles().hiding(session) { it.id }.map { l -> l.map { PickItem(it.id, it.title) } }
            AiPick.PERSON -> db.people().observeAll().hidingPeople(session).map { l -> l.filter { it.name.isNotBlank() }.map { PickItem(it.id, it.name, it.organization) } }
            AiPick.TEXT -> db.texts().observeAll().hidingNamed(session, { it.matterId }) { it.title + " " + it.body }.map { l -> l.map { PickItem(it.id, it.title) } }
            AiPick.ATTACK -> db.info().observeAll().hidingNamed(session, { it.matterId }) { it.title + " " + it.notes }.map { l ->
                l.filter { !it.closed && (it.kind == InfoKind.ATTACK.name || it.kind == InfoKind.CRITIQUE.name) }.map { PickItem(it.id, it.title, it.source) }
            }
            // Meetings from today on, for two weeks
            AiPick.EVENT -> kotlinx.coroutines.flow.combine(db.events().observeRange(Dates.today(), Dates.today() + 14).hiding(session) { it.matterId }, hiddenTitles(db, session)) { l, ht ->
                l.filterNot { it.matterId == null && namesAny(it.title + " " + it.notes, ht) }.sortedWith(compareBy({ it.day }, { it.startMin ?: -1 })).map { PickItem(it.id, it.title, Dates.format(it.day) + (it.startMin?.let { m -> " " + timeText(m) } ?: "")) }
            }
            // Open delegations to someone: a reminder needs a person to write to
            AiPick.DELEGATION -> kotlinx.coroutines.flow.combine(db.delegations().observeOpen().hidingNamed(session, { it.matterId }) { it.what + " " + it.purpose }, db.people().observeAll().hidingPeople(session), hiddenTitles(db, session)) { ds, ps, ht ->
                val names = ps.associate { it.id to it.name }
                ds.filter { it.personId != null && !(it.matterId == null && namesAny(it.what + " " + it.purpose, ht)) }.map { PickItem(it.id, it.what, names[it.personId].orEmpty() + (it.due?.let { d -> " · " + Dates.format(d) } ?: "")) }
            }
            AiPick.NONE -> kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }.collectAsStateWithLifecycle(emptyList())
    val title = stringResource(when (act.pick) {
        AiPick.MATTER -> R.string.ai_pick_matter
        AiPick.PERSON -> R.string.ai_pick_person
        AiPick.TEXT -> R.string.ai_pick_text
        AiPick.EVENT -> R.string.ai_pick_event
        AiPick.DELEGATION -> R.string.ai_pick_delegation
        else -> R.string.ai_pick_attack
    })
    PickSheet(title, items, onPick = onPick, onDismiss = onDismiss, footer = if (items.isEmpty()) ({
        Text(stringResource(R.string.ai_pick_empty), style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.padding(12.dp))
    }) else null)
}

/** A letter to answer: pasted, with the matter it belongs to; the draft becomes a text on a tap. */
@Composable
private fun ReplySheet(db: MandatDb, session: Session, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val matters by remember(db) { db.matters().observeActiveTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val allMatters by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    var letter by remember { mutableStateOf("") }
    var matterId by remember { mutableStateOf<Long?>(null) }
    val run = rememberAiRun(session, letter to matterId)
    val label = stringResource(R.string.ai_reply)
    val title = stringResource(R.string.ai_reply_title)
    EditSheet(title = "$AI_MARK $label", onDismiss = onDismiss, onSave = null) {
        SheetGroup {
            Text(stringResource(R.string.ai_reply_explain), style = MaterialTheme.typography.labelMedium, color = Muted)
            OutlinedTextField(letter, { letter = it.take(LocalAi.MAX_INPUT - 1500) }, label = { Text(stringResource(R.string.ai_reply_letter)) },
                minLines = 6, readOnly = run.busy, modifier = Modifier.fillMaxWidth())
            MatterPicker(matters, allMatters, matterId) { matterId = it }
            if (letter.isNotBlank() && !run.busy && run.answer == null) AiChip(stringResource(R.string.ai_reply_go)) {
                val l = letter; val m = matterId
                run.start(scope, session, LocalAi.Task.REPLY, label) {
                    "Вхідний лист:\n" + l + "\n\n" + (m?.let { id -> session.withOpenDb { d -> matterFacts(d, session, id) } }.orEmpty().ifBlank { "Фактів справи немає." })
                }
            }
            AiResult(run, onApply = { a ->
                session.textDraft = newText(matterId, ua.mandat.data.TextKind.LETTER, title, a)
                onDone()
            }, applyText = stringResource(R.string.ai_to_text))
        }
    }
}

/** A long document (a bill, a report) cut down to its points, part by part, on the phone. */
@Composable
private fun DigestSheet(session: Session, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var part by remember { mutableStateOf(0 to 0) }
    var answer by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { job?.cancel() } }
    val title = stringResource(R.string.ai_digest_title)
    EditSheet(title = "$AI_MARK " + stringResource(R.string.ai_digest), onDismiss = onDismiss, onSave = null) {
        SheetGroup {
            Text(stringResource(R.string.ai_digest_explain), style = MaterialTheme.typography.labelMedium, color = Muted)
            OutlinedTextField(text, { text = it.take(DIGEST_FIELD_MAX) }, label = { Text(stringResource(R.string.ai_digest_text)) },
                minLines = 8, maxLines = 14, readOnly = busy, modifier = Modifier.fillMaxWidth())
            // Said before reading, never silently: a document longer than what is read is cut at its end
            val parts = remember(text) { LocalAi.chunks(text, LocalAi.MAX_INPUT - 400).size }
            if (parts > LocalAi.DIGEST_PARTS) Text(stringResource(R.string.ai_digest_cut, LocalAi.DIGEST_PARTS, parts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            if (busy) {
                AiBusy()
                if (part.second > 1) Text(stringResource(R.string.ai_digest_part, part.first, part.second), style = MaterialTheme.typography.labelMedium, color = AiViolet)
            } else if (text.isNotBlank() && answer == null) AiChip(stringResource(R.string.ai_digest_go)) {
                busy = true; failed = false
                val t = text
                job = scope.launch {
                    answer = try { session.ai.digest(t) { i, n -> part = i to n }.takeIf { it.isNotBlank() } }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { null }
                    failed = answer == null
                    busy = false
                }
            }
            if (failed) Text(stringResource(R.string.ai_digest_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            answer?.let { a ->
                AiCard(a, title = stringResource(R.string.ai_digest), onApply = {
                    session.textDraft = newText(null, ua.mandat.data.TextKind.NOTE, title, a)
                    onDone()
                }, applyText = stringResource(R.string.ai_to_text), onDismiss = { answer = null })
            }
        }
    }
}

/** The digest field keeps what was pasted (up to a generous cap), so the warning counts the whole document. */
private const val DIGEST_FIELD_MAX = 400_000
