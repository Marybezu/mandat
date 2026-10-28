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
import java.time.LocalDate

/**
 * Sorting out old overdue items: each one can be closed as done, moved a week ahead or
 * dropped in one tap, so the current list shows what is really current.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriageScreen(db: MandatDb, session: Session) {
    BackHandler { session.showTriage = false }
    val deadlines by remember(db) { db.deadlines().observeOpen().hidingNamed(session, { it.matterId }) { it.title + " " + it.basis } }.collectAsStateWithLifecycle(emptyList())
    val delegations by remember(db) { db.delegations().observeOpen().hidingNamed(session, { it.matterId }) { it.what + " " + it.purpose } }.collectAsStateWithLifecycle(emptyList())
    val promises by remember(db) { db.promises().observeOpen().hidingNamed(session, { it.matterId }) { it.what + " " + it.toWhom } }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
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
    DEADLINES_DUE(R.string.stat_deadlines_due), ON_TIME(R.string.stat_on_time), DELEG_GIVEN(R.string.stat_deleg_given), DELEG_DONE(R.string.stat_deleg_done),
    PROM_MADE(R.string.stat_prom_made), PROM_KEPT(R.string.stat_prom_kept), YEAR(R.string.period_year),
}

/** The list behind a dashboard tile, for the same period and counted the same way. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodScreen(db: MandatDb, session: Session, kind: PeriodKind, span: Span) {
    BackHandler { session.periodView = null }
    val reviews by remember(db) { db.reviews().observeAll().hidingReviews(session) }.collectAsStateWithLifecycle(emptyList())
    val matters by remember(db) { db.matters().observeAll().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val deadlines by remember(db) { db.deadlines().observeAll().hidingNamed(session, { it.matterId }) { it.title + " " + it.basis } }.collectAsStateWithLifecycle(emptyList())
    val delegations by remember(db) { db.delegations().observeAll().hidingNamed(session, { it.matterId }) { it.what + " " + it.purpose } }.collectAsStateWithLifecycle(emptyList())
    val promises by remember(db) { db.promises().observeAll().hidingNamed(session, { it.matterId }) { it.what + " " + it.toWhom } }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll().hidingPeople(session) }.collectAsStateWithLifecycle(emptyList())
    val roles by remember(db) { db.roles().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val roleNames = roles.associate { it.id to it.name }
    val nameOf = people.associate { it.id to it.name }
    val titleOf = matters.associate { it.id to it.title }
    val today = Dates.today()
    fun inSpan(ms: Long) = Dates.dayOf(ms) in span
    fun done(s: String) = TaskStatus.of(s) == TaskStatus.DONE
    // Each list is counted exactly as the dashboard counts it (data/Stats.kt overview)
    val shownMatters = when (kind) {
        PeriodKind.MOVED -> matters.filter { inSpan(it.updatedAt) || inSpan(it.createdAt) }
        PeriodKind.STARTED, PeriodKind.YEAR -> matters.filter { inSpan(it.createdAt) }
        PeriodKind.FINISHED -> matters.filter { MatterStatus.of(it.status) == MatterStatus.DONE && inSpan(it.updatedAt) }
        else -> emptyList()
    }.sortedByDescending { it.updatedAt }
    val shownDeadlines = when (kind) {
        PeriodKind.DEADLINES_DUE -> deadlines.filter { it.day in span && it.day <= today && TaskStatus.of(it.status) != TaskStatus.CANCELLED }
        PeriodKind.ON_TIME -> deadlines.filter { it.day in span && it.day <= today && done(it.status) && it.closedAt != null && Dates.dayOf(it.closedAt) <= it.day }
        PeriodKind.YEAR -> deadlines.filter { it.day in span && done(it.status) }
        else -> emptyList()
    }.sortedByDescending { it.day }
    val shownDelegations = when (kind) {
        PeriodKind.DELEG_GIVEN -> delegations.filter { inSpan(it.createdAt) }
        PeriodKind.DELEG_DONE -> delegations.filter { done(it.status) && it.closedAt != null && inSpan(it.closedAt) }
        else -> emptyList()
    }.sortedByDescending { it.createdAt }
    val shownPromises = when (kind) {
        PeriodKind.PROM_MADE, PeriodKind.YEAR -> promises.filter { inSpan(it.createdAt) }
        PeriodKind.PROM_KEPT -> promises.filter { done(it.status) && it.closedAt != null && inSpan(it.closedAt) }
        else -> emptyList()
    }.sortedByDescending { it.createdAt }
    val shownEvenings = if (kind == PeriodKind.EVENINGS) reviews.filter { it.day in span && it.progress.isNotBlank() }.sortedByDescending { it.day } else emptyList()
    val count = shownMatters.size + shownDeadlines.size + shownDelegations.size + shownPromises.size + shownEvenings.size
    val range = "${Dates.format(span.from)} – ${Dates.format(minOf(span.to, today))}"
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (kind == PeriodKind.YEAR) LocalDate.ofEpochDay(span.from).year.toString() else stringResource(kind.title)) },
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
            verticalArrangement = Arrangement.spacedBy(Space.gap),
        ) {
            if (count == 0) item(key = "empty") {
                SectionCard(Sym.folder, range, count = 0) { EmptyHint(Sym.checkCircle, stringResource(R.string.smart_empty)) }
            }
            if (shownEvenings.isNotEmpty()) item(key = "evenings") {
                SectionCard(Sym.bedtime, range, count = shownEvenings.size) {
                    Paged(shownEvenings) { r -> TextTile(r.progress, Dates.format(r.day)) }
                }
            }
            if (shownMatters.isNotEmpty()) item(key = "matters") {
                SectionCard(Sym.folder, if (kind == PeriodKind.YEAR) stringResource(R.string.dash_started) else range, count = shownMatters.size) {
                    Paged(shownMatters) { m -> MatterLine(m, roleNames[m.roleId]) { session.draft = MatterDraft(EditRequest(m, isNew = false)) } }
                }
            }
            if (shownDeadlines.isNotEmpty()) item(key = "deadlines") {
                SectionCard(Sym.schedule, if (kind == PeriodKind.YEAR) stringResource(R.string.stat_deadlines_done) else range, count = shownDeadlines.size) {
                    Paged(shownDeadlines) { d -> DeadlineRow(d, d.matterId?.let(titleOf::get), session) }
                }
            }
            if (shownDelegations.isNotEmpty()) item(key = "delegations") {
                SectionCard(Sym.group, range, count = shownDelegations.size) {
                    Paged(shownDelegations) { d -> DelegationRow(d, d.personId?.let(nameOf::get), d.matterId?.let(titleOf::get), session) }
                }
            }
            if (shownPromises.isNotEmpty()) item(key = "promises") {
                SectionCard(Sym.checkCircle, if (kind == PeriodKind.YEAR) stringResource(R.string.stat_prom_made) else range, count = shownPromises.size) {
                    Paged(shownPromises) { p -> PromiseRow(p, p.personId?.let(nameOf::get), p.matterId?.let(titleOf::get), session) }
                }
            }
        }
    }
}
