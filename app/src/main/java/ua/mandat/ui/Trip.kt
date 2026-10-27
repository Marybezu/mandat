package ua.mandat.ui

import androidx.compose.foundation.horizontalScroll

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Trip mode: matters marked sensitive, and everything linked to them, are left out of every
 * screen, list, chart and search while it is on. The data itself stays in the encrypted database;
 * this only keeps it off the screen. Anyone who can make the owner unlock Mandat can turn it off.
 */
fun <T> Flow<List<T>>.hiding(session: Session?, matterOf: (T) -> Long?): Flow<List<T>> =
    if (session == null) this
    else combine(session.hiddenMatters) { list, hidden ->
        if (hidden.isEmpty()) list else list.filter { item -> matterOf(item)?.let { it !in hidden } ?: true }
    }

/** The setting that turns trip mode on (in the encrypted database). */
const val SETTING_TRIP = "trip_mode"

/**
 * The lens: a strip at the top showing which role the whole app is looked through, with a picker.
 * Matters of other roles (and what is linked to them) step aside; items without a matter stay.
 */
@androidx.compose.runtime.Composable
fun LensBar(db: ua.mandat.data.MandatDb, session: Session, full: Boolean) {
    val lens by session.lensRole.collectAsStateWithLifecycle()
    val roles by androidx.compose.runtime.remember(db) { db.roles().observeAll() }.collectAsStateWithLifecycle(emptyList())
    if (roles.isEmpty()) return
    val noRole = androidx.compose.ui.res.stringResource(ua.mandat.R.string.dash_no_role)
    if (!full) {
        // Elsewhere only a reminder that the lens is on, with a way to turn it off
        val name = lens?.let { id -> if (id == 0L) noRole else roles.firstOrNull { it.id == id }?.name } ?: return
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = 2.dp),
        ) {
            QuickChip("✕ " + androidx.compose.ui.res.stringResource(ua.mandat.R.string.lens_on, name), true) { session.lensRole.value = null }
        }
        return
    }
    // Every role as a chip in one row that scrolls sideways; the chosen one is filled
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = Space.screen, vertical = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        QuickChip(androidx.compose.ui.res.stringResource(ua.mandat.R.string.lens_all), lens == null) { session.lensRole.value = null }
        roles.forEach { r -> QuickChip(shortRole(r.name), lens == r.id) { session.lensRole.value = if (lens == r.id) null else r.id } }
        QuickChip(noRole, lens == 0L) { session.lensRole.value = if (lens == 0L) null else 0L }
    }
}

/** A role name short enough for a chip: the part before a dash, at most 24 characters. */
fun shortRole(name: String): String {
    val head = name.substringBefore(" — ").substringBefore(" - ").trim()
    return if (head.length <= 24) head else head.take(23).trimEnd() + "…"
}
