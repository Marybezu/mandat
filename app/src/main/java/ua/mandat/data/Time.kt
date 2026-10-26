package ua.mandat.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A date that must not be missed. HARD deadlines (set by law or a court) always carry their
 * basis, written by the owner; Mandat never fills in legal norms itself.
 */
@Entity(tableName = "deadlines")
data class Deadline(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Calendar day (days since 1970-01-01, no time zone). */
    val day: Long,
    /** A key of [DeadlineKind]. */
    val kind: String,
    /** For HARD: the norm of law, court decision or document that sets the date. */
    val basis: String,
    val matterId: Long?,
    /** How many days before to remind (the day itself is always reminded). */
    val remindBefore: Int,
    /** A key of [TaskStatus]. */
    val status: String,
    val createdAt: Long,
    val closedAt: Long?,
)

/** The evening question: what moved forward today. One entry per day. */
@Entity(tableName = "reviews")
data class Review(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Calendar day. */
    val day: Long,
    val progress: String,
    val createdAt: Long,
)

enum class DeadlineKind {
    HARD, SOFT;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.name == key } ?: SOFT
    }
}

/** One thing found by search, ready for display. */
data class SearchHit(val kind: String, val id: Long, val title: String, val detail: String)

@Dao
interface DeadlineDao {
    @Query("SELECT * FROM deadlines")
    fun observeAll(): Flow<List<Deadline>>

    @Query("SELECT * FROM deadlines WHERE status = 'OPEN' ORDER BY day, kind = 'SOFT'")
    fun observeOpen(): Flow<List<Deadline>>

    @Query("SELECT * FROM deadlines WHERE matterId = :matterId ORDER BY status = 'OPEN' DESC, day")
    fun observeForMatter(matterId: Long): Flow<List<Deadline>>

    @Query("SELECT * FROM deadlines WHERE status = 'OPEN'")
    suspend fun open(): List<Deadline>

    @Query("SELECT * FROM deadlines WHERE id = :id")
    suspend fun get(id: Long): Deadline?

    @Insert
    suspend fun insert(d: Deadline): Long

    @Update
    suspend fun update(d: Deadline)

    @Query("DELETE FROM deadlines WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE deadlines SET matterId = NULL WHERE matterId = :matterId")
    suspend fun clearMatter(matterId: Long)
}

@Dao
interface ReviewDao {
    @Query("SELECT * FROM reviews")
    fun observeAll(): Flow<List<Review>>

    @Query("SELECT * FROM reviews ORDER BY day DESC LIMIT 60")
    fun observeRecent(): Flow<List<Review>>

    @Query("SELECT * FROM reviews WHERE day = :day LIMIT 1")
    suspend fun forDay(day: Long): Review?

    @Insert
    suspend fun insert(r: Review): Long

    @Update
    suspend fun update(r: Review)

    @Query("DELETE FROM reviews WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Everything searchable, one row per item; the matching is done in Kotlin (see [search]). */
data class SearchRow(val kind: String, val id: Long, val title: String, val detail: String, val haystack: String)

@Dao
interface SearchDao {
    @Query(
        "SELECT 'matter' AS kind, id, title, nextStep AS detail, " +
            "title || ' ' || nextStep || ' ' || goal || ' ' || description AS haystack FROM matters " +
            "UNION ALL SELECT 'person', id, name, organization, name || ' ' || organization || ' ' || jobTitle || ' ' || notes || ' ' || contact FROM people " +
            "UNION ALL SELECT 'inbox', id, text, '', text FROM inbox " +
            "UNION ALL SELECT 'delegation', id, what, '', what FROM delegations " +
            "UNION ALL SELECT 'promise', id, what, toWhom, what || ' ' || toWhom FROM promises " +
            "UNION ALL SELECT 'deadline', id, title, basis, title || ' ' || basis FROM deadlines " +
            "UNION ALL SELECT 'review', id, progress, '', progress FROM reviews",
    )
    fun everything(): Flow<List<SearchRow>>
}

/**
 * Case-insensitive search that also works for Cyrillic (SQLite LIKE ignores case only for
 * Latin letters). The data is small, so filtering in memory is fine.
 */
fun search(rows: List<SearchRow>, text: String): List<SearchHit> {
    val q = text.trim().lowercase()
    if (q.length < 2) return emptyList()
    return rows.asSequence()
        .filter { it.haystack.lowercase().contains(q) }
        .take(200)
        .map { SearchHit(it.kind, it.id, it.title, it.detail) }
        .toList()
}

/** A hard deadline needs its basis; any deadline needs a title. */
fun cleanDeadline(d: Deadline): Deadline? {
    val title = d.title.trim().take(500)
    if (title.isEmpty()) return null
    val basis = d.basis.trim().take(1_000)
    if (DeadlineKind.of(d.kind) == DeadlineKind.HARD && basis.isEmpty()) return null
    return d.copy(title = title, basis = basis, remindBefore = d.remindBefore.coerceIn(0, 60))
}

/**
 * The days on which Mandat should show a reminder, from today on: each open deadline's day and
 * its "days before" day, and the due day of open delegations and promises. Only days are kept,
 * never what is due, so the reminder itself carries no details.
 */
fun reminderDays(deadlines: List<Deadline>, delegations: List<Delegation>, promises: List<Promise>, today: Long): List<Long> {
    val days = mutableSetOf<Long>()
    for (d in deadlines) {
        if (TaskStatus.of(d.status) != TaskStatus.OPEN) continue
        days += d.day
        if (d.remindBefore > 0) days += d.day - d.remindBefore
    }
    delegations.filter { TaskStatus.of(it.status) == TaskStatus.OPEN }.mapNotNullTo(days) { it.due }
    // A check-in day is a reminder too (only the day is kept, never what is due)
    delegations.filter { TaskStatus.of(it.status) == TaskStatus.OPEN }.mapNotNullTo(days) { it.checkIn }
    promises.filter { TaskStatus.of(it.status) == TaskStatus.OPEN }.mapNotNullTo(days) { it.due }
    // Something already overdue: remind every day (of the stored window) until it is closed
    val future = days.filter { it >= today }
    val overdue = days.any { it < today }
    val daily = if (overdue) (today until today + 14).toList() else emptyList()
    return (future + daily).distinct().sorted()
}
