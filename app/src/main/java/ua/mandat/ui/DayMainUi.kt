package ua.mandat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.mandat.R
import ua.mandat.data.DAY_MAIN_LIMIT
import ua.mandat.data.DayMain
import ua.mandat.data.Deadline
import ua.mandat.data.Delegation
import ua.mandat.data.Matter
import ua.mandat.data.MandatDb
import ua.mandat.data.Promise
import ua.mandat.data.SETTING_DAY_MAIN
import ua.mandat.data.Setting
import ua.mandat.data.TaskStatus

/**
 * The day's one to three main things, at the top of Today. Chosen by the owner from what is due
 * and what is in focus; ticked off one by one, and when all are done the card says so. Open loops
 * weigh on attention; seeing the few that matter closed is what makes a day feel done.
 */
@Composable
fun DayMainCard(
    db: MandatDb, session: Session, focus: List<Matter>, deadlines: List<Deadline>, delegations: List<Delegation>, promises: List<Promise>,
    titleOf: Map<Long, String>, nameOf: Map<Long, String>, roleNames: Map<Long, String>, onOpenMatter: (Matter) -> Unit,
) {
    val today = Dates.today()
    val stored by remember(db) { db.settings().observe(SETTING_DAY_MAIN) }.collectAsStateWithLifecycle(null)
    val main = DayMain.decode(stored?.value, today)
    var picking by remember { mutableStateOf(false) }
    fun save(m: DayMain) = session.write { it.settings().put(Setting(SETTING_DAY_MAIN, m.encode())) }

    val matterById = focus.associateBy { it.id }
    val dl = deadlines.associateBy { it.id }
    val dg = delegations.associateBy { it.id }
    val pr = promises.associateBy { it.id }
    // A reference whose record is gone, or hidden now (trip mode, lens), is simply not shown
    val shown = main.items.filter { ref ->
        val (kind, id) = ref.split(':').let { it[0] to it[1].toLong() }
        when (kind) { "matter" -> id in matterById; "deadline" -> id in dl; "delegation" -> id in dg; else -> id in pr }
    }
    fun isDone(ref: String): Boolean {
        val (kind, id) = ref.split(':').let { it[0] to it[1].toLong() }
        return when (kind) {
            "deadline" -> dl[id]?.let { TaskStatus.of(it.status) != TaskStatus.OPEN } ?: false
            "delegation" -> dg[id]?.let { TaskStatus.of(it.status) != TaskStatus.OPEN } ?: false
            "promise" -> pr[id]?.let { TaskStatus.of(it.status) != TaskStatus.OPEN } ?: false
            else -> ref in main.done
        }
    }
    val doneCount = shown.count(::isDone)

    SectionCard(Sym.target, stringResource(R.string.day_main_title), count = null, canvas = Tone.focus) {
        if (shown.isEmpty()) {
            Text(stringResource(R.string.day_main_empty), style = MaterialTheme.typography.bodyMedium, color = Muted)
        } else if (doneCount == shown.size) {
            Text(stringResource(R.string.day_main_all_done), style = MaterialTheme.typography.titleSmall, color = ChartColors.teal, modifier = Modifier.padding(bottom = 4.dp))
        } else {
            Text(stringResource(R.string.day_main_progress, doneCount, shown.size), style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.padding(bottom = 4.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach { ref ->
                val (kind, id) = ref.split(':').let { it[0] to it[1].toLong() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        when (kind) {
                            "deadline" -> dl[id]?.let { DeadlineRow(it, it.matterId?.let(titleOf::get), session) }
                            "delegation" -> dg[id]?.let { DelegationRow(it, it.personId?.let(nameOf::get), it.matterId?.let(titleOf::get), session) }
                            "promise" -> pr[id]?.let { PromiseRow(it, it.personId?.let(nameOf::get), it.matterId?.let(titleOf::get), session) }
                            else -> matterById[id]?.let { m ->
                                // A matter is ticked for today only; it stays open
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = ref in main.done, onCheckedChange = { on -> save(main.copy(done = if (on) main.done + ref else main.done - ref)) })
                                    Text(m.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                                        textDecoration = if (ref in main.done) TextDecoration.LineThrough else null,
                                        modifier = Modifier.weight(1f).clickable { onOpenMatter(m) }.padding(vertical = 8.dp))
                                }
                            }
                        }
                    }
                    IconButton(onClick = { save(main.copy(items = main.items - ref, done = main.done - ref)) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.day_main_remove), tint = Muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (shown.size < DAY_MAIN_LIMIT) QuickChip("+ " + stringResource(R.string.day_main_add), false, Modifier.padding(top = 6.dp)) { picking = true }
    }

    if (picking) {
        // What is due first (overdue, today, tomorrow), then the matters in focus
        val soon = today + 1
        val items = buildList {
            deadlines.filter { TaskStatus.of(it.status) == TaskStatus.OPEN && it.day <= soon }.forEach { add(PickItem("deadline:${it.id}", it.title, Dates.format(it.day))) }
            delegations.filter { TaskStatus.of(it.status) == TaskStatus.OPEN && it.due != null && it.due <= soon }
                .forEach { add(PickItem("delegation:${it.id}", it.what, listOfNotNull(it.personId?.let(nameOf::get), it.due?.let(Dates::format)).joinToString(" · "))) }
            promises.filter { TaskStatus.of(it.status) == TaskStatus.OPEN && it.due != null && it.due <= soon }
                .forEach { add(PickItem("promise:${it.id}", it.what, it.due?.let(Dates::format).orEmpty())) }
            focus.forEach { add(PickItem("matter:${it.id}", it.title, it.roleId?.let(roleNames::get).orEmpty())) }
        }.filter { it.key !in main.items }
        PickSheet(stringResource(R.string.day_main_pick), items, onPick = { ref ->
            picking = false
            save(main.copy(items = (main.items + ref).distinct().take(DAY_MAIN_LIMIT)))
        }, onDismiss = { picking = false }, footer = if (items.isEmpty()) ({
            Text(stringResource(R.string.day_main_nothing), style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.padding(12.dp))
        }) else null)
    }
}
