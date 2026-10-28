package ua.mandat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.mandat.R
import ua.mandat.data.MandatDb
import ua.mandat.data.Promise
import ua.mandat.data.PromiseOrigin
import ua.mandat.data.Publicity
import ua.mandat.data.TaskStatus

/** Open promises that need care: high risk, or public ones due within two weeks or overdue. */
fun isRisky(p: Promise, today: Long): Boolean =
    TaskStatus.of(p.status) == TaskStatus.OPEN &&
        (p.risk == 3 || (p.level == Publicity.PUBLIC && p.due != null && p.due <= today + 14))

/** Open promises the owner did not choose to make: attributed to them, or dug up by the media. */
fun isAttributed(p: Promise): Boolean =
    TaskStatus.of(p.status) == TaskStatus.OPEN &&
        (PromiseOrigin.of(p.origin) == PromiseOrigin.ATTRIBUTED || PromiseOrigin.of(p.origin) == PromiseOrigin.DUG_UP)

private val STATUS_ORDER = listOf(TaskStatus.OPEN, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED)

/**
 * Promises 2.0: a matrix of how widely they were made against how they went, where they came
 * from, what is risky and what was attributed, then the list. Every chart filters the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromisesScreen(db: MandatDb, session: Session) {
    BackHandler { session.showPromises = false }
    val all by remember(db) { db.promises().observeAll().hidingNamed(session, { it.matterId }) { it.what + " " + it.toWhom + " " + it.source + " " + it.statusNote } }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val nameOf = people.associate { it.id to it.name }
    val titleOf = titles.associate { it.id to it.title }
    val today = Dates.today()

    var level by rememberSaveable { mutableStateOf<String?>(null) }
    var statusPick by rememberSaveable { mutableStateOf<String?>(null) }
    var originPick by rememberSaveable { mutableStateOf<String?>(null) }
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    var query by remember { mutableStateOf("") }

    fun yearOf(p: Promise) = java.time.LocalDate.ofEpochDay(Dates.dayOf(p.createdAt)).year
    val q = query.trim().lowercase()
    // Search applies to everything; the charts narrow the list
    val searched = all.filter { p ->
        q.length < 2 || listOf(p.what, p.toWhom, p.source, p.statusNote, p.personId?.let(nameOf::get).orEmpty()).any { it.lowercase().contains(q) }
    }
    val shown = searched.filter { p ->
        (level == null || p.level.name == level) && (statusPick == null || p.status == statusPick) &&
            (originPick == null || (p.origin ?: "NONE") == originPick) && (year == null || yearOf(p) == year)
    }
    val risky = searched.filter { isRisky(it, today) }.sortedWith(compareBy({ it.due == null }, { it.due }))
    val attributed = searched.filter(::isAttributed)
    val years = searched.map(::yearOf).distinct().sorted()

    @Composable
    fun row(p: Promise) {
        val origin = listOf(
            stringResource(R.string.promised_on, Dates.format(Dates.dayOf(p.createdAt))),
            PromiseOrigin.of(p.origin)?.let { stringResource(it.label()) }.orEmpty(),
            p.risk?.let { stringResource(riskLabel(it)) }.orEmpty(),
            p.source, p.statusNote,
        ).filter { it.isNotBlank() }.joinToString(" · ")
        PromiseRow(p, p.personId?.let(nameOf::get), p.matterId?.let(titleOf::get), session, extra = origin)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.my_promises)) },
                navigationIcon = {
                    IconButton(onClick = { session.showPromises = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = { HowItWorks(R.string.how_promises);
                    IconButton(onClick = { session.promiseDraft = newPromise() }) {
                        Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(R.drawable.sym_add), contentDescription = stringResource(R.string.add_promise))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).imePadding().fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.gap),
        ) {
            item(key = "query") {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth())
            }
            item(key = "matrix") {
                SectionCard(Sym.layers, stringResource(R.string.promises_matrix), count = searched.size) {
                    Text(stringResource(R.string.promises_matrix_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                    Spacer(Modifier.height(6.dp))
                    PromiseMatrix(searched, level, statusPick) { l, s -> level = l; statusPick = s }
                }
            }
            item(key = "origin") {
                SectionCard(Sym.explore, stringResource(R.string.origin)) {
                    Spacer(Modifier.height(4.dp))
                    StackBar(
                        PromiseOrigin.entries.mapIndexed { i, o ->
                            Slice(o.name, stringResource(o.label()), searched.count { it.origin == o.name },
                                listOf(ChartColors.blue, ChartColors.amber, ChartColors.coral, ChartColors.purple)[i])
                        } + Slice("NONE", stringResource(R.string.not_set), searched.count { PromiseOrigin.of(it.origin) == null }, ChartColors.grayLight),
                        selected = originPick, onPick = { originPick = it },
                    )
                }
            }
            if (risky.isNotEmpty()) item(key = "risky") {
                SectionCard(Sym.warning, stringResource(R.string.promises_risky), count = risky.size, attention = true) {
                    Text(stringResource(R.string.promises_risky_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                    AiAnswer(session, ua.mandat.ai.LocalAi.Task.RISK, facts = { session.withOpenDb { riskFacts(it, session) }.orEmpty() })
                    Spacer(Modifier.height(4.dp))
                    Paged(risky, page = 5) { row(it) }
                }
            }
            if (attributed.isNotEmpty()) item(key = "attributed") {
                SectionCard(Sym.info, stringResource(R.string.promises_attributed), count = attributed.size) {
                    Text(stringResource(R.string.promises_attributed_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                    Spacer(Modifier.height(4.dp))
                    Paged(attributed, page = 5) { p ->
                        Column {
                            row(p)
                            Row {
                                // Taking it on as one's own, or saying publicly that it was never promised
                                TextButton(onClick = { session.write { it.promises().update(p.copy(origin = PromiseOrigin.CHOSEN.name)) } }) {
                                    Text(stringResource(R.string.promise_take_on))
                                }
                                TextButton(onClick = {
                                    session.promiseDraft = PromiseDraft(p, isNew = false).also { it.status = TaskStatus.CANCELLED }
                                }) { Text(stringResource(R.string.promise_refute)) }
                            }
                        }
                    }
                }
            }
            if (years.size > 1) item(key = "years") {
                SectionCard(Sym.calendar, stringResource(R.string.dash_years_title)) {
                    Spacer(Modifier.height(6.dp))
                    DayColumns(
                        years.map { y -> DayLoad(y.toLong(), "’" + (y % 100).toString().padStart(2, '0'), searched.count { yearOf(it) == y }, false) },
                        selected = year?.toLong(), onPick = { year = it?.toInt() },
                    )
                }
            }
            if (level != null || statusPick != null || originPick != null || year != null) item(key = "filters") {
                val filters = listOfNotNull(
                    level?.let { stringResource(Publicity.of(it).label()) to { level = null } },
                    statusPick?.let { stringResource(TaskStatus.of(it).promiseLabel()) to { statusPick = null } },
                    originPick?.let { (PromiseOrigin.of(it)?.let { o -> stringResource(o.label()) } ?: stringResource(R.string.not_set)) to { originPick = null } },
                    year?.let { it.toString() to { year = null } },
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    filters.forEach { (label, clear) -> FilterBadge(label, clear) }
                }
            }
            if (shown.isEmpty()) item(key = "empty") { EmptyHint(Sym.checkCircle, stringResource(R.string.promises_empty)) }
            STATUS_ORDER.forEach { st ->
                val list = shown.filter { TaskStatus.of(it.status) == st }
                if (list.isNotEmpty()) item(key = "g$st") {
                    SectionCard(Sym.checkCircle, stringResource(st.promiseLabel()), count = list.size, attention = st == TaskStatus.FAILED) {
                        Paged(list) { row(it) }
                    }
                }
            }
        }
    }
}

/**
 * Rows: how widely it was promised; columns: open, kept, failed, dropped. Darker means more.
 * Tapping a cell shows just those promises; tapping it again clears the choice.
 */
@Composable
private fun PromiseMatrix(promises: List<Promise>, level: String?, status: String?, onPick: (String?, String?) -> Unit) {
    val counts = Publicity.entries.associateWith { l -> STATUS_ORDER.associateWith { s -> promises.count { it.level == l && TaskStatus.of(it.status) == s } } }
    val max = counts.values.flatMap { it.values }.maxOrNull()?.coerceAtLeast(1) ?: 1
    val colors = mapOf(TaskStatus.OPEN to ChartColors.blueLight, TaskStatus.DONE to ChartColors.blue, TaskStatus.FAILED to ChartColors.salmon, TaskStatus.CANCELLED to ChartColors.grayLight)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Spacer(Modifier.weight(1.4f))
            STATUS_ORDER.forEach { s ->
                Text(stringResource(s.matrixLabel()), style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Publicity.entries.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(l.label()), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.4f))
                STATUS_ORDER.forEach { s ->
                    val n = counts.getValue(l).getValue(s)
                    val picked = level == l.name && status == s.name
                    val dim = (level != null || status != null) && !picked
                    Box(
                        Modifier.weight(1f).padding(2.dp).height(34.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (n == 0) MaterialTheme.colorScheme.surfaceVariant else colors.getValue(s).copy(alpha = (0.25f + 0.75f * n / max) * (if (dim) 0.4f else 1f)))
                            .clickable { if (picked) onPick(null, null) else onPick(l.name, s.name) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (n == 0) "·" else n.toString(), style = MaterialTheme.typography.labelLarge,
                            color = if (n > max / 2 && s == TaskStatus.DONE) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/** Short column names for the matrix, so they fit a phone screen. */
private fun TaskStatus.matrixLabel(): Int = when (this) {
    TaskStatus.OPEN -> R.string.matrix_open
    TaskStatus.DONE -> R.string.matrix_done
    TaskStatus.FAILED -> R.string.matrix_failed
    TaskStatus.CANCELLED -> R.string.matrix_dropped
}
