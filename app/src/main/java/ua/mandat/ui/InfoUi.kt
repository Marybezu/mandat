@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ua.mandat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.mandat.R
import ua.mandat.data.Alliance
import ua.mandat.data.AllianceStatus
import ua.mandat.data.AttackDecision
import ua.mandat.data.AttackStage
import ua.mandat.data.InfoItem
import ua.mandat.data.InfoKind
import ua.mandat.data.IntegrityCheck
import ua.mandat.data.KeyMessage
import ua.mandat.data.MandatDb
import ua.mandat.data.checksOf

fun InfoKind.color(): Color = when (this) {
    InfoKind.DISCUSSION -> ChartColors.blue
    InfoKind.CRITIQUE -> ChartColors.purple
    InfoKind.ATTACK -> ChartColors.coral
    InfoKind.CORRECTION -> ChartColors.teal
}

private fun reachLabel(r: Int): Int = when (r) {
    1 -> R.string.reach_small
    2 -> R.string.reach_mid
    else -> R.string.reach_wide
}

/**
 * Media and information, with integrity: attacks on their way from spotted to result, public
 * discussions and criticism with a soft check, owned-up mistakes, and the key messages with
 * their facts. Every chart filters the lists below it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(db: MandatDb, session: Session) {
    BackHandler { session.showInfo = false }
    val items by remember(db) { db.info().observeAll().hidingNamed(session, { it.matterId }) { it.title + " " + it.notes } }.collectAsStateWithLifecycle(emptyList())
    val messages by remember(db) { db.info().observeMessages().hidingNamed(session, { it.matterId }) { it.text + " " + it.proof } }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val nameOf = people.associate { it.id to it.name }
    val titleOf = titles.associate { it.id to it.title }
    var kindPick by rememberSaveable { mutableStateOf<String?>(null) }
    var ignoredOnly by rememberSaveable { mutableStateOf(false) }
    var stagePick by rememberSaveable { mutableStateOf<String?>(null) }

    val open = items.filter { !it.closed }
    val attacks = open.filter { it.kind == InfoKind.ATTACK.name }
    fun dec(i: InfoItem) = !ignoredOnly || i.decision == AttackDecision.IGNORE.name
    val shown = open.filter { (kindPick == null || it.kind == kindPick) && (stagePick == null || it.stage == stagePick) && dec(it) }
    val closed = items.filter { it.closed && (kindPick == null || it.kind == kindPick) && dec(it) }
    val ignored = items.count { it.decision == AttackDecision.IGNORE.name }
    val corrections = items.count { it.kind == InfoKind.CORRECTION.name }

    @Composable
    fun row(i: InfoItem) = InfoRow(i, i.personId?.let(nameOf::get), i.matterId?.let(titleOf::get), session)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.info_title)) },
                navigationIcon = {
                    IconButton(onClick = { session.showInfo = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.gap),
        ) {
            item(key = "integrity") {
                MCard {
                    Text(stringResource(R.string.info_integrity_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.info_integrity_text), style = MaterialTheme.typography.bodySmall, color = Muted,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            item(key = "add") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoKind.entries.forEach { k ->
                        OutlinedButton(onClick = { session.infoDraft = newInfo(k) }) { Text("+ " + stringResource(k.label())) }
                    }
                }
            }
            item(key = "kinds") {
                SectionCard(Sym.layers, stringResource(R.string.info_open), count = open.size) {
                    Spacer(Modifier.height(4.dp))
                    StackBar(
                        InfoKind.entries.map { k -> Slice(k.name, stringResource(k.label()), open.count { it.kind == k.name }, k.color()) },
                        selected = kindPick, onPick = { kindPick = it; stagePick = null },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.gap), modifier = Modifier.padding(top = 8.dp)) {
                        StatTile(ignored, stringResource(R.string.info_not_fed)) { ignoredOnly = true; kindPick = InfoKind.ATTACK.name; stagePick = null }
                        StatTile(corrections, stringResource(R.string.info_owned_up)) { ignoredOnly = false; kindPick = InfoKind.CORRECTION.name; stagePick = null }
                    }
                }
            }
            if (attacks.isNotEmpty()) item(key = "pipeline") {
                SectionCard(Sym.warning, stringResource(R.string.info_attacks_way), count = attacks.size) {
                    Spacer(Modifier.height(6.dp))
                    StagePipeline(attacks, stagePick) { stagePick = it; if (it != null) kindPick = InfoKind.ATTACK.name }
                }
            }
            if (kindPick != null || stagePick != null || ignoredOnly) item(key = "filter") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    kindPick?.let { FilterBadge(stringResource(InfoKind.of(it).label())) { kindPick = null; stagePick = null; ignoredOnly = false } }
                    if (ignoredOnly) FilterBadge(stringResource(R.string.decision_ignore)) { ignoredOnly = false }
                    stagePick?.let { FilterBadge(stringResource(AttackStage.of(it).label())) { stagePick = null } }
                }
            }
            InfoKind.entries.forEach { k ->
                val list = shown.filter { it.kind == k.name }.let { l ->
                    if (k == InfoKind.ATTACK) l.sortedBy { AttackStage.of(it.stage).ordinal } else l
                }
                if (list.isNotEmpty()) item(key = "k$k") {
                    SectionCard(Sym.folder, stringResource(k.label()), count = list.size, attention = k == InfoKind.ATTACK) {
                        Paged(list, page = 10) { row(it) }
                    }
                }
            }
            if (open.isEmpty()) item(key = "empty") { EmptyHint(Sym.checkCircle, stringResource(R.string.info_empty)) }
            item(key = "messages") {
                SectionCard(Sym.target, stringResource(R.string.message_box), count = messages.size) {
                    Text(stringResource(R.string.message_box_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                    Spacer(Modifier.height(4.dp))
                    Paged(messages, page = 5) { m -> MessageRow(m, m.matterId?.let(titleOf::get), session) }
                    TextButton(onClick = { session.messageDraft = newMessage() }) { Text("+ " + stringResource(R.string.message_new)) }
                }
            }
            item(key = "alliances") {
                OutlinedButton(onClick = { session.showInfo = false; session.showAlliances = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.alliances_title))
                }
            }
            if (closed.isNotEmpty()) item(key = "closed") {
                SectionCard(Sym.checkCircle, stringResource(R.string.info_closed), count = closed.size) {
                    Paged(closed, page = 5) { row(it) }
                }
            }
        }
    }
}

/** Five steps of dealing with an attack, each with how many are at it now. Tap a step to filter. */
@Composable
private fun StagePipeline(attacks: List<InfoItem>, picked: String?, onPick: (String?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AttackStage.entries.forEach { s ->
            val n = attacks.count { AttackStage.of(it.stage) == s }
            val sel = picked == s.name
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .background(if (sel) scheme.primaryContainer else if (n > 0) ChartColors.coral.copy(alpha = 0.15f) else scheme.surfaceVariant)
                    .clickable { onPick(if (sel) null else s.name) }.padding(vertical = 8.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(n.toString(), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(s.label()), style = MaterialTheme.typography.labelSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    color = Muted, maxLines = 1, softWrap = false, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One item: its kind as a coloured bar, the title, and small labels. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InfoRow(i: InfoItem, personName: String?, matterTitle: String?, session: Session) {
    val kind = InfoKind.of(i.kind)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable { session.infoDraft = InfoDraft(i, isNew = false) }.padding(10.dp),
    ) {
        Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(kind.color()))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(i.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MiniChip(Dates.short(i.day))
                if (kind == InfoKind.ATTACK) {
                    MiniChip(stringResource(AttackStage.of(i.stage).label()), ChartColors.coral.copy(alpha = 0.18f))
                    AttackDecision.of(i.decision)?.let { MiniChip(stringResource(it.label())) }
                }
                if (kind == InfoKind.DISCUSSION || kind == InfoKind.CRITIQUE) {
                    MiniChip(stringResource(R.string.checks_count, checksOf(i.checks).size, IntegrityCheck.entries.size))
                }
                i.reach?.let { MiniChip(stringResource(reachLabel(it))) }
                if (personName != null && i.personId != null) MiniChip(personName, onClick = { session.openPerson(i.personId) })
                if (matterTitle != null && i.matterId != null) MiniChip(matterTitle, onClick = { session.openMatter(i.matterId) })
            }
            if (i.result.isNotBlank()) Text("✓ " + i.result, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun MessageRow(m: KeyMessage, matterTitle: String?, session: Session) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable { session.messageDraft = MessageDraft(m, isNew = false) }.padding(10.dp),
    ) {
        Text("«${m.text}»", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (m.proof.isNotBlank()) Text(m.proof, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        val meta = listOfNotNull(m.audience.ifBlank { null }, matterTitle).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(top = 2.dp))
    }
}

/** The dialog for one item. The check before publishing reminds; it never blocks saving. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InfoDialog(db: MandatDb, session: Session, d: InfoDraft) {
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    val matters by remember(db) { db.matters().observeActiveTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val allMatters by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    var confirmDelete by remember { mutableStateOf(false) }
    val none = stringResource(R.string.nobody)

    EditSheet(
        title = stringResource(d.kind.label()),
        onDismiss = { session.infoDraft = null },
        changed = d.changed(),
        onSave = { session.saveInfoDraft() },
        saveEnabled = d.title.isNotBlank(),
    ) {
        SheetGroup {

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoKind.entries.forEach { k -> QuickChip(stringResource(k.label()), d.kind == k) { d.kind = k } }
                }
                Text(stringResource(d.kind.hint()), style = MaterialTheme.typography.labelSmall, color = Muted)
                OutlinedTextField(d.title, { d.title = it }, label = { Text(stringResource(R.string.what)) }, modifier = Modifier.fillMaxWidth())
                DueField(d.day, allowClear = false, label = R.string.info_day) { if (it != null) d.day = it }
                OutlinedTextField(d.source, { d.source = it }, label = { Text(stringResource(R.string.info_source)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Picker(
                    label = stringResource(R.string.info_person),
                    current = people.firstOrNull { it.id == d.personId }?.name ?: none,
                    options = listOf<Pair<Long?, String>>(null to none) + people.map { it.id to it.name },
                    onPick = { d.personId = it },
                )
                MatterPicker(matters, allMatters, d.matterId) { d.matterId = it }
                Text(stringResource(R.string.info_reach), style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 3).forEach { r ->
                        QuickChip(stringResource(reachLabel(r)), d.reach == r) { d.reach = if (d.reach == r) null else r }
                    }
                }
                when (d.kind) {
                    InfoKind.ATTACK -> {
                        Text(stringResource(R.string.info_stage), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AttackStage.entries.forEach { s -> QuickChip(stringResource(s.label()), d.stage == s) { d.stage = s } }
                        }
                        Text(stringResource(R.string.info_decision), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AttackDecision.entries.forEach { a ->
                                QuickChip(stringResource(a.label()), d.decision == a) { d.decision = if (d.decision == a) null else a }
                            }
                        }
                        d.decision?.let { Text(stringResource(it.hint()), style = MaterialTheme.typography.labelSmall, color = Muted) }
                    }
                    InfoKind.DISCUSSION, InfoKind.CRITIQUE -> {
                        Text(stringResource(R.string.checks_title), style = MaterialTheme.typography.labelLarge)
                        IntegrityCheck.entries.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                                d.checks = if (c in d.checks) d.checks - c else d.checks + c
                            }) {
                                Checkbox(checked = c in d.checks, onCheckedChange = { on -> d.checks = if (on) d.checks + c else d.checks - c })
                                Text(stringResource(c.label()), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text(stringResource(R.string.checks_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                    InfoKind.CORRECTION -> {}
                }
                OutlinedTextField(d.notes, { d.notes = it }, label = { Text(stringResource(d.kind.notesLabel())) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.result, { d.result = it }, label = { Text(stringResource(R.string.info_result)) }, minLines = 1, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = d.closed, onCheckedChange = { d.closed = it })
                    Text(stringResource(R.string.info_closed_mark), style = MaterialTheme.typography.bodyMedium)
                }
                if (!d.isNew) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmDelete) {
        ConfirmDelete(onConfirm = {
            val id = d.item.id
            session.infoDraft = null
            session.write { it.info().delete(id) }
        }, onCancel = { confirmDelete = false })
    }
}

private fun InfoKind.hint(): Int = when (this) {
    InfoKind.DISCUSSION -> R.string.info_discussion_hint
    InfoKind.CRITIQUE -> R.string.info_critique_hint
    InfoKind.ATTACK -> R.string.info_attack_hint
    InfoKind.CORRECTION -> R.string.info_correction_hint
}

private fun InfoKind.notesLabel(): Int = when (this) {
    InfoKind.ATTACK -> R.string.info_notes_attack
    InfoKind.CORRECTION -> R.string.info_notes_correction
    else -> R.string.info_notes
}

private fun AttackDecision.hint(): Int = when (this) {
    AttackDecision.IGNORE -> R.string.decision_ignore_hint
    AttackDecision.ANSWER -> R.string.decision_answer_hint
    AttackDecision.REFUTE -> R.string.decision_refute_hint
    AttackDecision.ALLIES -> R.string.decision_allies_hint
    AttackDecision.LEGAL -> R.string.decision_legal_hint
}

@Composable
fun MessageDialog(db: MandatDb, session: Session, d: MessageDraft) {
    val matters by remember(db) { db.matters().observeActiveTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val allMatters by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    var confirmDelete by remember { mutableStateOf(false) }
    EditSheet(
        title = stringResource(R.string.message_new),
        onDismiss = { session.messageDraft = null },
        changed = d.changed(),
        onSave = { session.saveMessageDraft() },
        saveEnabled = d.text.isNotBlank(),
    ) {
        SheetGroup {

                OutlinedTextField(d.text, { d.text = it }, label = { Text(stringResource(R.string.message_text)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.proof, { d.proof = it }, label = { Text(stringResource(R.string.message_proof)) },
                    supportingText = { Text(stringResource(R.string.message_proof_hint)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.audience, { d.audience = it }, label = { Text(stringResource(R.string.message_audience)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                MatterPicker(matters, allMatters, d.matterId) { d.matterId = it }
                if (!d.isNew) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmDelete) {
        ConfirmDelete(onConfirm = {
            val id = d.message.id
            session.messageDraft = null
            session.write { it.info().deleteMessage(id) }
        }, onCancel = { confirmDelete = false })
    }
}

/**
 * Situational alliances: who with, about what, until when, and what each side brings. Those
 * ending within two weeks come first, so nothing lapses unnoticed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlliancesScreen(db: MandatDb, session: Session) {
    BackHandler { session.showAlliances = false }
    val all by remember(db) { db.info().observeAlliances().hidingNamed(session, { it.matterId }) { it.withWhom + " " + it.about + " " + it.notes } }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val nameOf = people.associate { it.id to it.name }
    val titleOf = titles.associate { it.id to it.title }
    val today = Dates.today()
    var pick by rememberSaveable { mutableStateOf<String?>(null) }
    val active = all.filter { it.status == AllianceStatus.ACTIVE.name }
    val ending = active.filter { it.untilDay != null && it.untilDay <= today + 14 }.sortedBy { it.untilDay }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alliances_title)) },
                navigationIcon = {
                    IconButton(onClick = { session.showAlliances = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = { TextButton(onClick = { session.allianceDraft = newAlliance() }) { Text("+ " + stringResource(R.string.alliance_new)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.gap),
        ) {
            item(key = "hint") { Text(stringResource(R.string.alliances_hint), style = MaterialTheme.typography.bodyMedium, color = Muted) }
            item(key = "summary") {
                MCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                        AllianceStatus.entries.forEach { s ->
                            StatTile(all.count { it.status == s.name }, stringResource(s.label()), attention = s == AllianceStatus.BROKEN && pick == null) {
                                pick = if (pick == s.name) null else s.name
                            }
                        }
                    }
                }
            }
            if (ending.isNotEmpty() && pick == null) item(key = "ending") {
                SectionCard(Sym.schedule, stringResource(R.string.alliances_ending), count = ending.size, attention = true) {
                    Paged(ending, page = 5) { a -> AllianceRow(a, a.personId?.let(nameOf::get), a.matterId?.let(titleOf::get), session) }
                }
            }
            if (all.isEmpty()) item(key = "empty") { EmptyHint(Sym.group, stringResource(R.string.alliances_empty)) }
            AllianceStatus.entries.filter { pick == null || it.name == pick }.forEach { s ->
                val list = all.filter { it.status == s.name }
                if (list.isNotEmpty()) item(key = "s$s") {
                    SectionCard(Sym.group, stringResource(s.label()), count = list.size) {
                        Paged(list, page = 10) { a -> AllianceRow(a, a.personId?.let(nameOf::get), a.matterId?.let(titleOf::get), session) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AllianceRow(a: Alliance, personName: String?, matterTitle: String?, session: Session) {
    val today = Dates.today()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable { session.allianceDraft = AllianceDraft(a, isNew = false) }.padding(10.dp),
    ) {
        Text(a.withWhom, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(a.about, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (a.gives.isNotBlank() || a.gets.isNotBlank()) {
            Text(
                listOfNotNull(
                    a.gives.ifBlank { null }?.let { stringResource(R.string.alliance_gives_short, it) },
                    a.gets.ifBlank { null }?.let { stringResource(R.string.alliance_gets_short, it) },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            a.untilDay?.let { DueChip(it, a.status == AllianceStatus.ACTIVE.name && it < today) }
            if (personName != null && a.personId != null) MiniChip(personName, onClick = { session.openPerson(a.personId) })
            if (matterTitle != null && a.matterId != null) MiniChip(matterTitle, onClick = { session.openMatter(a.matterId) })
        }
    }
}

@Composable
fun AllianceDialog(db: MandatDb, session: Session, d: AllianceDraft) {
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    val matters by remember(db) { db.matters().observeActiveTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val allMatters by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    var confirmDelete by remember { mutableStateOf(false) }
    val none = stringResource(R.string.nobody)
    EditSheet(
        title = stringResource(R.string.alliance_new),
        onDismiss = { session.allianceDraft = null },
        changed = d.changed(),
        onSave = { session.saveAllianceDraft() },
        saveEnabled = d.valid(),
    ) {
        SheetGroup {

                OutlinedTextField(d.withWhom, { d.withWhom = it }, label = { Text(stringResource(R.string.alliance_with)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.about, { d.about = it }, label = { Text(stringResource(R.string.alliance_about)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Picker(
                    label = stringResource(R.string.alliance_contact),
                    current = people.firstOrNull { it.id == d.personId }?.name ?: none,
                    options = listOf<Pair<Long?, String>>(null to none) + people.map { it.id to it.name },
                    onPick = { d.personId = it },
                )
                MatterPicker(matters, allMatters, d.matterId) { d.matterId = it }
                DueField(d.untilDay, label = R.string.alliance_until) { d.untilDay = it }
                OutlinedTextField(d.gives, { d.gives = it }, label = { Text(stringResource(R.string.alliance_gives)) }, minLines = 1, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.gets, { d.gets = it }, label = { Text(stringResource(R.string.alliance_gets)) }, minLines = 1, modifier = Modifier.fillMaxWidth())
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AllianceStatus.entries.forEach { s -> QuickChip(stringResource(s.label()), d.status == s) { d.status = s } }
                }
                OutlinedTextField(d.notes, { d.notes = it }, label = { Text(stringResource(R.string.person_notes)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                if (!d.isNew) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmDelete) {
        ConfirmDelete(onConfirm = {
            val id = d.alliance.id
            session.allianceDraft = null
            session.write { it.info().deleteAlliance(id) }
        }, onCancel = { confirmDelete = false })
    }
}

/** Media items and alliances of a matter, inside its card. */
@Composable
fun MatterInfoLinks(db: MandatDb, session: Session, matterId: Long) {
    val items by remember(db, matterId) { db.info().observeForMatter(matterId).hidingNamed(session, { it.matterId }) { it.title + " " + it.notes } }.collectAsStateWithLifecycle(emptyList())
    val alliances by remember(db, matterId) { db.info().observeAlliancesForMatter(matterId).hidingNamed(session, { it.matterId }) { it.title + " " + it.notes } }.collectAsStateWithLifecycle(emptyList())
    LinkSection(stringResource(R.string.info_title) + if (items.isEmpty()) "" else " · ${items.size}")
    items.take(5).forEachIndexed { i, it -> if (i > 0) RowDivider(); InfoRow(it, null, null, session) }
    TextButton(onClick = { session.infoDraft = newInfo(matterId = matterId) }) { Text("+ " + stringResource(R.string.info_new)) }
    LinkSection(stringResource(R.string.alliances_title) + if (alliances.isEmpty()) "" else " · ${alliances.size}")
    alliances.take(5).forEachIndexed { i, a -> if (i > 0) RowDivider(); AllianceRow(a, null, null, session) }
    TextButton(onClick = { session.allianceDraft = newAlliance(matterId = matterId) }) { Text("+ " + stringResource(R.string.alliance_new)) }
}
