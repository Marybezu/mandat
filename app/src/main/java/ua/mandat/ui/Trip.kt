package ua.mandat.ui

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
