package ua.mandat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.mandat.R
import ua.mandat.data.MandatDb
import ua.mandat.data.MatterStatus
import ua.mandat.data.Span
import ua.mandat.data.TaskStatus

/**
 * Sorting out old overdue items: each one can be closed as done, moved a week ahead or
 * dropped in one tap, so the current list shows what is really current.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriageScreen(db: MandatDb, session: Session) {
    BackHandler { session.showTriage = false }
    val deadlines by remember(db) { db.deadlines().observeOpen() }.collectAsStateWithLifecycle(emptyList())
    val delegations by remember(db) { db.delegations().observeOpen() }.collectAsStateWithLifecycle(emptyList())
    val promises by remember(db) { db.promises().observeOpen() }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles() }.collectAsStateWithLifecycle(emptyList())
    val nameOf = people.associate { it.id to it.name }
    val titleOf = titles.associate { it.id to it.title }
    val today = Dates.today()
    val limit = today - OLD_OVERDUE_DAYS
    val now = { System.currentTimeMillis() }

    val oldDl = deadlines.filter { it.day < limit }.sortedBy { it.day }
    val oldD = delegations.filter { it.due != null && it.due < limit }.sortedBy { it.due }
    val oldP = promises.filter { it.due != null && it.due < limit }.sortedBy { it.due }

    /** Three quick actions under each item. */
    @Composable
    fun Actions(done: () -> Unit, later: () -> Unit, drop: () -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = done) { Text(stringResource(R.string.triage_done)) }
            TextButton(onClick = later) { Text(stringResource(R.string.triage_week)) }
            TextButton(onClick = drop) { Text(stringResource(R.string.triage_drop)) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.triage_title)) },
                navigationIcon = {
                    IconButton(onClick = { session.showTriage = false }) {
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
            item(key = "hint") { Text(stringResource(R.string.triage_hint), style = MaterialTheme.typography.bodyMedium, color = Muted) }
            if (oldDl.isEmpty() && oldD.isEmpty() && oldP.isEmpty()) item(key = "empty") { EmptyHint(Sym.checkCircle, stringResource(R.string.triage_empty)) }
            if (oldDl.isNotEmpty()) item(key = "dl") {
                SectionCard(Sym.schedule, stringResource(R.string.deadlines), count = oldDl.size) {
                    Paged(oldDl) { d ->
                        Column {
                            DeadlineRow(d, d.matterId?.let(titleOf::get), session)
                            Actions(
                                done = { session.write { it.deadlines().update(d.copy(status = TaskStatus.DONE.name, closedAt = now())) } },
                                later = { session.write { it.deadlines().update(d.copy(day = today + 7)) } },
                                drop = { session.write { it.deadlines().update(d.copy(status = TaskStatus.CANCELLED.name, closedAt = now())) } },
                            )
                        }
                    }
                }
            }
            if (oldD.isNotEmpty()) item(key = "d") {
                SectionCard(Sym.group, stringResource(R.string.delegations), count = oldD.size) {
                    Paged(oldD) { d ->
                        Column {
                            DelegationRow(d, d.personId?.let(nameOf::get), d.matterId?.let(titleOf::get), session)
                            Actions(
                                done = { session.write { it.delegations().update(d.copy(status = TaskStatus.DONE.name, closedAt = now())) } },
                                later = { session.write { it.delegations().update(d.copy(due = today + 7)) } },
                                drop = { session.write { it.delegations().update(d.copy(status = TaskStatus.CANCELLED.name, closedAt = now())) } },
                            )
                        }
                    }
                }
            }
            if (oldP.isNotEmpty()) item(key = "p") {
                SectionCard(Sym.checkCircle, stringResource(R.string.promises), count = oldP.size) {
                    Paged(oldP) { p ->
                        Column {
                            PromiseRow(p, p.personId?.let(nameOf::get), p.matterId?.let(titleOf::get), session)
                            // For a promise, "dropped" opens it, so the owner writes why (it is public record)
                            Actions(
                                done = { session.write { it.promises().update(p.copy(status = TaskStatus.DONE.name, closedAt = now())) } },
                                later = { session.write { it.promises().update(p.copy(due = today + 7)) } },
                                drop = { session.promiseDraft = PromiseDraft(p, isNew = false).also { it.status = TaskStatus.CANCELLED } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What a dashboard tile lists. */
enum class PeriodKind(val title: Int) {
    MOVED(R.string.dash_moved_title), STARTED(R.string.dash_started), FINISHED(R.string.dash_finished), EVENINGS(R.string.dash_evenings_title),
}

/** The list behind a dashboard tile, for the same period and counted the same way. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodScreen(db: MandatDb, session: Session, kind: PeriodKind, span: Span) {
    BackHandler { session.periodView = null }
    val reviews by remember(db) { db.reviews().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val matters by remember(db) { db.matters().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val roles by remember(db) { db.roles().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val roleNames = roles.associate { it.id to it.name }
    fun inSpan(ms: Long) = Dates.dayOf(ms) in span
    val shownMatters = when (kind) {
        PeriodKind.MOVED -> matters.filter { inSpan(it.updatedAt) || inSpan(it.createdAt) }
        PeriodKind.STARTED -> matters.filter { inSpan(it.createdAt) }
        PeriodKind.FINISHED -> matters.filter { MatterStatus.of(it.status) == MatterStatus.DONE && inSpan(it.updatedAt) }
        PeriodKind.EVENINGS -> emptyList()
    }.sortedByDescending { it.updatedAt }
    val shownEvenings = if (kind == PeriodKind.EVENINGS) reviews.filter { it.day in span }.sortedByDescending { it.day } else emptyList()
    val count = if (kind == PeriodKind.EVENINGS) shownEvenings.size else shownMatters.size
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(kind.title)) },
                navigationIcon = {
                    IconButton(onClick = { session.periodView = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 32.dp),
        ) {
            item(key = "list") {
                SectionCard(
                    if (kind == PeriodKind.EVENINGS) Sym.bedtime else Sym.folder,
                    "${Dates.format(span.from)} – ${Dates.format(minOf(span.to, Dates.today()))}", count = count,
                ) {
                    if (count == 0) EmptyHint(Sym.checkCircle, stringResource(R.string.smart_empty))
                    if (kind == PeriodKind.EVENINGS) Paged(shownEvenings) { r -> TextTile(r.progress, Dates.format(r.day)) }
                    else Paged(shownMatters) { m ->
                        MatterLine(m, roleNames[m.roleId]) { session.draft = MatterDraft(EditRequest(m, isNew = false)) }
                    }
                }
            }
        }
    }
}
