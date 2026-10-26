package ua.mandat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.mandat.R
import ua.mandat.data.Attention
import ua.mandat.data.MandatDb
import ua.mandat.data.Matter
import ua.mandat.data.TaskStatus
import ua.mandat.data.isStale

/**
 * Lists that gather themselves (after GTD): next steps, what others owe, what the owner owes,
 * what is stuck, and what has no next step. Rules only, no AI.
 */
enum class SmartList(val title: Int, val hint: Int) {
    // Problems first: what is stuck or has no next step, then what others and the owner owe
    STUCK(R.string.smart_stuck, R.string.smart_stuck_hint),
    NO_NEXT(R.string.smart_no_next, R.string.smart_no_next_hint),
    WAITING(R.string.smart_waiting, R.string.smart_waiting_hint),
    PROMISES(R.string.smart_promises, R.string.smart_promises_hint),
    NEXT(R.string.smart_next, R.string.smart_next_hint),
}

/** Counts for the chips on Today. */
fun smartCounts(active: List<Matter>, openDelegations: Int, openPromises: Int, today: Long): Map<SmartList, Int> {
    val awake = active.filter { Attention.of(it.attention) != Attention.SLEEPING }
    return mapOf(
        SmartList.NEXT to awake.count { it.nextStep.isNotBlank() },
        SmartList.WAITING to openDelegations,
        SmartList.PROMISES to openPromises,
        SmartList.STUCK to active.count { isStale(it, today) },
        SmartList.NO_NEXT to awake.count { it.nextStep.isBlank() },
    )
}

/** A row of chips, each opening its list. "Stuck" is marked when there is something in it. */
@Composable
fun SmartListChips(counts: Map<SmartList, Int>, onOpen: (SmartList) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmartList.entries.forEach { list ->
            val n = counts[list] ?: 0
            val warn = (list == SmartList.STUCK || list == SmartList.NO_NEXT) && n > 0
            Row(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (warn) scheme.errorContainer else scheme.surface)
                    .clickable { onOpen(list) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(n.toString(), style = MaterialTheme.typography.titleSmall, color = if (warn) scheme.onErrorContainer else scheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(list.title), style = MaterialTheme.typography.labelLarge,
                    color = if (warn) scheme.onErrorContainer else scheme.onSurface)
            }
        }
    }
}

/** One smart list on its own screen, grouped so that each matter's things stay together. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartListScreen(db: MandatDb, session: Session, list: SmartList) {
    BackHandler { session.smartList = null }
    val active by remember(db) { db.matters().observeActive() }.collectAsStateWithLifecycle(emptyList())
    val roles by remember(db) { db.roles().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val delegations by remember(db) { db.delegations().observeOpen() }.collectAsStateWithLifecycle(emptyList())
    val promises by remember(db) { db.promises().observeOpen() }.collectAsStateWithLifecycle(emptyList())
    val people by remember(db) { db.people().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles() }.collectAsStateWithLifecycle(emptyList())
    val roleNames = roles.associate { it.id to it.name }
    val nameOf = people.associate { it.id to it.name }
    val titleOf = titles.associate { it.id to it.title }
    val today = Dates.today()
    val noMatter = stringResource(R.string.no_matter_group)
    val noRole = stringResource(R.string.dash_no_role)
    val otherMatters = stringResource(R.string.other_matters)
    fun open(m: Matter) { session.draft = MatterDraft(EditRequest(m, isNew = false)) }

    // (title, rows) groups for this list
    val groups: List<Pair<String, List<@Composable () -> Unit>>> = when (list) {
        SmartList.WAITING -> delegations.filter { TaskStatus.of(it.status) == TaskStatus.OPEN }
            .groupBy { it.matterId }.toList().sortedBy { (id, _) -> id == null }
            .map { (id, ds) ->
                (id?.let(titleOf::get) ?: noMatter) to ds.sortedWith(compareBy({ it.due == null }, { it.due }))
                    .map { d -> @Composable { DelegationRow(d, d.personId?.let(nameOf::get), null, session) } }
            }
        SmartList.PROMISES -> promises.filter { TaskStatus.of(it.status) == TaskStatus.OPEN }
            .groupBy { it.matterId }.toList().sortedBy { (id, _) -> id == null }
            .map { (id, ps) ->
                (id?.let(titleOf::get) ?: noMatter) to ps.sortedWith(compareBy({ it.due == null }, { it.due }))
                    .map { p -> @Composable { PromiseRow(p, p.personId?.let(nameOf::get), null, session) } }
            }
        SmartList.NEXT -> listOf(Attention.FOCUS, Attention.BACKGROUND).map { a ->
            stringResource(a.label()) to active.filter { it.attention == a.name && it.nextStep.isNotBlank() }
                .map { m -> @Composable { MatterLine(m, roleNames[m.roleId]) { open(m) } } }
        }
        SmartList.STUCK -> active.filter { isStale(it, today) }.groupBy { it.roleId }.toList()
            .map { (rid, ms) -> (rid?.let(roleNames::get) ?: noRole) to ms.map { m -> @Composable { MatterLine(m, null) { open(m) } } } }
        SmartList.NO_NEXT -> active.filter { Attention.of(it.attention) != Attention.SLEEPING && it.nextStep.isBlank() }
            .groupBy { it.roleId }.toList()
            .map { (rid, ms) -> (rid?.let(roleNames::get) ?: noRole) to ms.map { m -> @Composable { MatterLine(m, null) { open(m) } } } }
    }.filter { it.second.isNotEmpty() }.let { all ->
        // Many matters with one item each would make many tiny cards: they share one card
        if (list != SmartList.WAITING && list != SmartList.PROMISES) all
        else {
            val big = all.filter { it.second.size > 1 }
            val single = all.filter { it.second.size == 1 }
            if (single.size < 2) all else big + (otherMatters to single.flatMap { (title, rows) -> rows.map { row -> @Composable { Column { MiniChip(title); Spacer(Modifier.height(4.dp)); row() } } } })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(list.title)) },
                navigationIcon = {
                    IconButton(onClick = { session.smartList = null }) {
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
            item(key = "hint") {
                Text(stringResource(list.hint), style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            if (groups.isEmpty()) item(key = "empty") { EmptyHint(Sym.checkCircle, stringResource(R.string.smart_empty)) }
            groups.forEachIndexed { i, (title, rows) ->
                item(key = "g$i") {
                    SectionCard(Sym.folder, title, count = rows.size) {
                        Paged(rows) { row -> row() }
                    }
                }
            }
        }
    }
}
