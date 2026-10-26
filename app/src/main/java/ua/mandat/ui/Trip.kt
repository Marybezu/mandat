package ua.mandat.ui

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
fun LensBar(db: ua.mandat.data.MandatDb, session: Session) {
    val lens by session.lensRole.collectAsStateWithLifecycle()
    val roles by androidx.compose.runtime.remember(db) { db.roles().observeAll() }.collectAsStateWithLifecycle(emptyList())
    var picking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (roles.isEmpty()) return
    val noRole = androidx.compose.ui.res.stringResource(ua.mandat.R.string.dash_no_role)
    val name = lens?.let { id -> if (id == 0L) noRole else roles.firstOrNull { it.id == id }?.name }
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier.fillMaxWidth()
            .background(if (lens != null) ChartColors.focus.copy(alpha = 0.25f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { picking = true }.padding(horizontal = Space.screen, vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            if (name != null) androidx.compose.ui.res.stringResource(ua.mandat.R.string.lens_on, name) else androidx.compose.ui.res.stringResource(ua.mandat.R.string.lens_off),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = if (lens != null) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else Muted,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = androidx.compose.ui.Modifier.weight(1f),
        )
        if (lens != null) androidx.compose.material3.TextButton(onClick = { session.lensRole.value = null }) { androidx.compose.material3.Text("✕") }
        else androidx.compose.material3.Text("▾", color = Muted)
    }
    if (picking) {
        PickTextDialog(
            listOf(0L to noRole) + roles.map { it.id to it.name },
            onPick = { id -> picking = false; session.lensRole.value = id },
            onCancel = { picking = false },
        )
    }
}
