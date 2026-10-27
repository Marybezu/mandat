package ua.mandat.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Time given to a matter outside calendar events, added with one tap (+15, +30, +60 minutes).
 * [roleId] is the matter's role at that moment, kept when the matter is deleted.
 */
@Entity(tableName = "time_entries")
data class TimeEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val minutes: Int,
    val matterId: Long?,
    val roleId: Long?,
    val createdAt: Long,
)

@Dao
interface TimeDao {
    @Query("SELECT * FROM time_entries ORDER BY day DESC, createdAt DESC")
    fun observeAll(): Flow<List<TimeEntry>>

    @Query("SELECT * FROM time_entries WHERE matterId = :matterId ORDER BY day DESC, createdAt DESC")
    fun observeForMatter(matterId: Long): Flow<List<TimeEntry>>

    @Insert
    suspend fun insert(entry: TimeEntry): Long

    @Query("DELETE FROM time_entries WHERE id = :id")
    suspend fun delete(id: Long)

    /** A deleted matter leaves its time to the role it belonged to. */
    @Query("UPDATE time_entries SET roleId = (SELECT roleId FROM matters WHERE id = :matterId), matterId = NULL WHERE matterId = :matterId")
    suspend fun clearMatter(matterId: Long)

    @Query("UPDATE time_entries SET roleId = NULL WHERE roleId = :roleId")
    suspend fun clearRole(roleId: Long)

    /** Desired shares of time by role, as settings "timeshare_<roleId>" = percent. */
    @Query("SELECT * FROM settings WHERE substr(`key`, 1, 10) = 'timeshare_'")
    fun observeShares(): Flow<List<Setting>>
}

const val TIME_SHARE_PREFIX = "timeshare_"

/** Minutes of one timed, single-day event; whole-day and multi-day events are not counted. */
fun eventMinutes(e: Event): Int {
    val start = e.startMin ?: return 0
    if (e.lastDay != e.day) return 0
    val end = e.endMin ?: (start + 60)
    return (end - start).coerceIn(0, 12 * 60)
}

/**
 * Minutes by role in [span]: timed events (through their matter's role) and entries added by
 * hand. Key 0 means "no role" (an event without a matter, or a matter without a role).
 */
fun minutesByRole(span: Span, events: List<Event>, entries: List<TimeEntry>, roleOfMatter: Map<Long, Long?>): Map<Long, Int> {
    val out = HashMap<Long, Int>()
    events.filter { it.day in span }.forEach { e ->
        val m = eventMinutes(e)
        if (m > 0) {
            val role = e.matterId?.let { roleOfMatter[it] } ?: 0L
            out[role] = (out[role] ?: 0) + m
        }
    }
    entries.filter { it.day in span }.forEach { t ->
        // The matter's current role when the matter is known, otherwise the role kept with the entry
        val role = if (t.matterId != null && roleOfMatter.containsKey(t.matterId)) roleOfMatter[t.matterId] ?: 0L else t.roleId ?: 0L
        out[role] = (out[role] ?: 0) + t.minutes.coerceAtLeast(0)
    }
    return out
}
