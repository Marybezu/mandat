package ua.mandat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.mandat.R
import ua.mandat.data.MandatDb
import ua.mandat.data.SETTING_TOPICS
import ua.mandat.data.Setting
import ua.mandat.data.TaskStatus
import ua.mandat.data.Topic
import ua.mandat.data.decodeTopics
import ua.mandat.data.encodeTopics
import ua.mandat.data.keysFor
import ua.mandat.data.matches
import ua.mandat.data.suggestTopics

/** The owner's topics, read from the encrypted settings. */
@Composable
fun rememberTopics(db: MandatDb): List<Topic> {
    val raw by remember(db) { db.settings().observe(SETTING_TOPICS) }.collectAsStateWithLifecycle(null)
    return remember(raw) { decodeTopics(raw?.value) }
}

fun saveTopics(session: Session, list: List<Topic>) {
    session.write { it.settings().put(Setting(SETTING_TOPICS, encodeTopics(list))) }
}

/** Everything that mentions a topic, in all sections. */
private class TopicItems(db: MandatDb, session: Session) {
    val matters = db.matters().observeAll().hiding(session) { it.id }
    val delegations = db.delegations().observeAll().hidingNamed(session, { it.matterId }) { it.what + " " + it.purpose + " " + it.doneMeans }
    val promises = db.promises().observeAll().hidingNamed(session, { it.matterId }) { it.what + " " + it.toWhom + " " + it.source + " " + it.statusNote }
    val deadlines = db.deadlines().observeAll().hidingNamed(session, { it.matterId }) { it.title + " " + it.basis }
    val inbox = db.inbox().observeAll().hidingNamed(session, { null }) { it.text }
    val reviews = db.reviews().observeAll().hidingReviews(session)
}

/** All topics with how much each one gathers, suggested topics, and adding a topic. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TopicsScreen(db: MandatDb, session: Session) {
    session.topicOpen?.let { name ->
        TopicDetail(db, session, name)
        return
    }
    BackHandler { session.showTopics = false }
    val topics = rememberTopics(db)
    val src = remember(db) { TopicItems(db, session) }
    val matters by src.matters.collectAsStateWithLifecycle(emptyList())
    val delegations by src.delegations.collectAsStateWithLifecycle(emptyList())
    val promises by src.promises.collectAsStateWithLifecycle(emptyList())
    val deadlines by src.deadlines.collectAsStateWithLifecycle(emptyList())
    val inbox by src.inbox.collectAsStateWithLifecycle(emptyList())
    val reviews by src.reviews.collectAsStateWithLifecycle(emptyList())
    var adding by remember { mutableStateOf<String?>(null) }

    val texts = remember(matters, delegations, promises, deadlines, inbox) {
        matters.map { "${it.title} ${it.goal} ${it.nextStep}" } + delegations.map { it.what } + promises.map { "${it.what} ${it.toWhom}" } +
            deadlines.map { it.title } + inbox.map { it.text }
    }
    val suggestions = remember(texts, topics) { suggestTopics(texts, topics) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.topics)) },
                actions = { HowItWorks(R.string.how_topics) },
                navigationIcon = {
                    IconButton(onClick = { session.showTopics = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        // Counted away from the screen thread: matching every record against every topic takes a while with years of records
        val topicCounts by androidx.compose.runtime.produceState(emptyMap<String, List<Pair<Int, Int>>>(), topics, matters, delegations, promises, deadlines, inbox, reviews) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                topics.associate { t ->
                    t.name to listOf(
                        R.string.topic_matters to matters.count { matches("${it.title} ${it.goal} ${it.nextStep} ${it.description}", t) },
                        R.string.delegations to delegations.count { matches(it.what, t) },
                        R.string.promises to promises.count { matches("${it.what} ${it.toWhom}", t) },
                        R.string.deadlines to deadlines.count { matches("${it.title} ${it.basis}", t) },
                        R.string.inbox to inbox.count { matches(it.text, t) },
                        R.string.topic_evenings to reviews.count { matches(it.progress, t) },
                    ).filter { it.second > 0 }
                }
            }
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.gap),
        ) {
            item(key = "hint") { Text(stringResource(R.string.topics_hint), style = MaterialTheme.typography.bodyMedium, color = Muted) }
            if (topics.isNotEmpty()) item(key = "list") {
                SectionCard(Sym.layers, stringResource(R.string.topics), count = topics.size) {
                    topics.forEachIndexed { i, t ->
                        if (i > 0) RowDivider()
                        val counts = topicCounts[t.name].orEmpty()
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .clickable { session.topicOpen = t.name }.padding(10.dp),
                        ) {
                            Text(t.name, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (counts.isEmpty()) MiniChip(stringResource(R.string.topic_nothing))
                                counts.forEach { (label, n) -> MiniChip("${stringResource(label)} $n") }
                            }
                        }
                    }
                }
            }
            if (suggestions.isNotEmpty()) item(key = "suggest") {
                SectionCard(Sym.explore, stringResource(R.string.topics_suggested)) {
                    Text(stringResource(R.string.topics_suggested_hint), style = MaterialTheme.typography.labelMedium, color = Muted,
                        modifier = Modifier.padding(bottom = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { (word, n) ->
                            Text(
                                "+ $word · $n",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer)
                                    .clickable { adding = word.replaceFirstChar { it.uppercase() } }.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
            item(key = "add") {
                OutlinedButton(onClick = { adding = "" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.topic_add)) }
            }
        }
    }
    adding?.let { start ->
        TopicDialog(Topic(start, keysFor(start)), isNew = true, onDismiss = { adding = null }) { t ->
            adding = null
            saveTopics(session, topics.filter { it.name != t.name } + t)
        }
    }
}

/** Name and key words of a topic. */
@Composable
private fun TopicDialog(topic: Topic, isNew: Boolean, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null, onSave: (Topic) -> Unit) {
    var name by remember { mutableStateOf(topic.name) }
    var keys by remember { mutableStateOf(topic.keys.joinToString(", ")) }
    EditSheet(
        title = stringResource(if (isNew) R.string.topic_add else R.string.topic_edit),
        onDismiss = onDismiss,
        onSave = {
                val k = keysFor(keys.ifBlank { name })
                onSave(Topic(name.trim().take(60), k.ifEmpty { keysFor(name) }))
            },
        saveEnabled = name.isNotBlank(),
    ) {
        SheetGroup {

                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.topic_name)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = keys, onValueChange = { keys = it },
                    label = { Text(stringResource(R.string.topic_keys)) },
                    supportingText = { Text(stringResource(R.string.topic_keys_hint)) }, modifier = Modifier.fillMaxWidth())
                if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** One topic: a ring of where it appears, then everything about it, by section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopicDetail(db: MandatDb, session: Session, name: String) {
    BackHandler { session.topicOpen = null }
    val topics = rememberTopics(db)
    val topic = topics.firstOrNull { it.name == name }
    val src = remember(db) { TopicItems(db, session) }
    val matters by src.matters.collectAsStateWithLifecycle(emptyList())
    val delegations by src.delegations.collectAsStateWithLifecycle(emptyList())
    val promises by src.promises.collectAsStateWithLifecycle(emptyList())
    val deadlines by src.deadlines.collectAsStateWithLifecycle(emptyList())
    val inbox by src.inbox.collectAsStateWithLifecycle(emptyList())
    val reviews by src.reviews.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    val roles by remember(db) { db.roles().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val nameOf = people.associate { it.id to it.name }
    val roleNames = roles.associate { it.id to it.name }
    val titleOf = matters.associate { it.id to it.title }
    var editing by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = {
                    IconButton(onClick = { session.topicOpen = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = { TextButton(onClick = { editing = true }) { Text(stringResource(R.string.topic_edit)) } },
            )
        },
    ) { padding ->
        if (topic == null) return@Scaffold
        // Matched away from the screen thread
        val found by androidx.compose.runtime.produceState<TopicFound?>(null, topic, matters, delegations, promises, deadlines, inbox, reviews) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                TopicFound(
                    matters.filter { matches("${it.title} ${it.goal} ${it.nextStep} ${it.description}", topic) },
                    delegations.filter { matches(it.what, topic) },
                    promises.filter { matches("${it.what} ${it.toWhom}", topic) },
                    deadlines.filter { matches("${it.title} ${it.basis}", topic) },
                    inbox.filter { matches(it.text, topic) },
                    reviews.filter { matches(it.progress, topic) },
                )
            }
        }
        val f = found ?: return@Scaffold
        val m = f.m; val d = f.d; val p = f.p; val dl = f.dl; val ib = f.ib; val rv = f.rv
        val slices = listOf(
            Slice("m", stringResource(R.string.topic_matters), m.size, ChartColors.purple),
            Slice("d", stringResource(R.string.delegations), d.count { TaskStatus.of(it.status) == TaskStatus.OPEN }, ChartColors.teal),
            Slice("p", stringResource(R.string.promises), p.size, ChartColors.amber),
            Slice("t", stringResource(R.string.deadlines), dl.count { TaskStatus.of(it.status) == TaskStatus.OPEN }, ChartColors.blue),
            Slice("i", stringResource(R.string.inbox), ib.size, ChartColors.grayLight),
            Slice("r", stringResource(R.string.topic_evenings), rv.size, ChartColors.pink),
        )
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.gap),
        ) {
            item(key = "chart") {
                MCard {
                    DonutChart(slices, slices.sumOf { it.value }.toString(), stringResource(R.string.topic_items), pick, { pick = it })
                }
            }
            fun show(k: String) = pick == null || pick == k
            if (show("m") && m.isNotEmpty()) item(key = "m") {
                SectionCard(Sym.folder, stringResource(R.string.topic_matters), count = m.size) {
                    Paged(m) { x -> MatterLine(x, roleNames[x.roleId]) { session.draft = MatterDraft(EditRequest(x, isNew = false)) } }
                }
            }
            val openD = d.filter { TaskStatus.of(it.status) == TaskStatus.OPEN }
            if (show("d") && openD.isNotEmpty()) item(key = "d") {
                SectionCard(Sym.group, stringResource(R.string.delegations), count = openD.size) {
                    Paged(openD) { x -> DelegationRow(x, x.personId?.let(nameOf::get), x.matterId?.let(titleOf::get), session) }
                }
            }
            if (show("p") && p.isNotEmpty()) item(key = "p") {
                SectionCard(Sym.checkCircle, stringResource(R.string.promises), count = p.size) {
                    Paged(p) { x -> PromiseRow(x, x.personId?.let(nameOf::get), x.matterId?.let(titleOf::get), session) }
                }
            }
            val openDl = dl.filter { TaskStatus.of(it.status) == TaskStatus.OPEN }
            if (show("t") && openDl.isNotEmpty()) item(key = "t") {
                SectionCard(Sym.schedule, stringResource(R.string.deadlines), count = openDl.size) {
                    Paged(openDl) { x -> DeadlineRow(x, x.matterId?.let(titleOf::get), session) }
                }
            }
            if (show("i") && ib.isNotEmpty()) item(key = "i") {
                SectionCard(Sym.inbox, stringResource(R.string.inbox), count = ib.size) {
                    Paged(ib) { x -> TextTile(x.text) }
                }
            }
            if (show("r") && rv.isNotEmpty()) item(key = "r") {
                SectionCard(Sym.bedtime, stringResource(R.string.topic_evenings), count = rv.size) {
                    Paged(rv.sortedByDescending { it.day }) { x -> TextTile(x.progress, Dates.format(x.day)) }
                }
            }
        }
    }
    if (editing && topic != null) {
        TopicDialog(topic, isNew = false, onDismiss = { editing = false }, onDelete = {
            editing = false
            session.topicOpen = null
            saveTopics(session, topics.filter { it.name != topic.name })
        }) { t ->
            editing = false
            saveTopics(session, topics.map { if (it.name == topic.name) t else it })
            session.topicOpen = t.name
        }
    }
}

/** A plain text tile, with an optional label above (for example a date). */
@Composable
fun TextTile(text: String, label: String? = null, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(10.dp),
    ) {
        if (label != null) {
            MiniChip(label)
            Spacer(Modifier.height(4.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** What one topic gathers, matched in the background. */
private class TopicFound(
    val m: List<ua.mandat.data.Matter>, val d: List<ua.mandat.data.Delegation>, val p: List<ua.mandat.data.Promise>,
    val dl: List<ua.mandat.data.Deadline>, val ib: List<ua.mandat.data.InboxItem>, val rv: List<ua.mandat.data.Review>,
)
