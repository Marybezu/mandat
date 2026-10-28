package ua.mandat.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Something in the owner's own calendar: a meeting, a session, a trip, a reception of citizens.
 * Kept only inside Mandat's encrypted database. Days are calendar days (no time zone), so a
 * trip abroad does not move the date; the time of day is minutes after midnight.
 */
@Entity(tableName = "events", indices = [Index("day")])
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** First day (days since 1970-01-01). */
    val day: Long,
    /** Last day for something that lasts several days (a trip); null for one day. */
    val endDay: Long? = null,
    /** Start in minutes after midnight; null means the whole day. */
    val startMin: Int? = null,
    val endMin: Int? = null,
    /** A key of [EventKind]. */
    val kind: String,
    val place: String = "",
    val matterId: Long? = null,
    val notes: String = "",
    /** What came out of it, written afterwards. */
    val outcome: String = "",
    /** Remind on the day (the reminder itself says nothing about what). */
    val remind: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val lastDay get() = endDay?.takeIf { it > day } ?: day
    fun covers(d: Long) = d in day..lastDay
}

/** Who takes part in an event. */
@Entity(tableName = "event_people", primaryKeys = ["eventId", "personId"], indices = [Index("personId")])
data class EventPerson(val eventId: Long, val personId: Long)

enum class EventKind {
    MEETING, SESSION, COMMITTEE, TRIP, RECEPTION, CALL, MEDIA, OTHER;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.name == key } ?: OTHER
    }
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events")
    fun observeAll(): Flow<List<Event>>

    /** Events that touch the days from..to (a trip that started earlier is included). */
    @Query("SELECT * FROM events WHERE day <= :to AND COALESCE(endDay, day) >= :from ORDER BY day, startMin IS NOT NULL, startMin")
    fun observeRange(from: Long, to: Long): Flow<List<Event>>

    @Query("SELECT * FROM events WHERE matterId = :matterId ORDER BY day DESC")
    fun observeForMatter(matterId: Long): Flow<List<Event>>

    @Query("SELECT e.* FROM events e JOIN event_people p ON p.eventId = e.id WHERE p.personId = :personId ORDER BY e.day DESC")
    fun observeForPerson(personId: Long): Flow<List<Event>>

    @Query("SELECT * FROM events WHERE remind = 1 AND COALESCE(endDay, day) >= :from")
    suspend fun withReminder(from: Long): List<Event>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun get(id: Long): Event?

    @Insert
    suspend fun insert(e: Event): Long

    @Update
    suspend fun update(e: Event)

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteRow(id: Long)

    @Query("SELECT * FROM event_people")
    fun observeLinks(): Flow<List<EventPerson>>

    @Query("SELECT personId FROM event_people WHERE eventId = :eventId")
    suspend fun peopleOf(eventId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(link: EventPerson)

    @Query("DELETE FROM event_people WHERE eventId = :eventId")
    suspend fun unlinkAll(eventId: Long)

    @Query("DELETE FROM event_people WHERE personId = :personId")
    suspend fun unlinkPerson(personId: Long)

    @Query("UPDATE events SET matterId = NULL WHERE matterId = :matterId")
    suspend fun clearMatter(matterId: Long)

    @Transaction
    suspend fun delete(id: Long) {
        unlinkAll(id)
        deleteRow(id)
    }

    @Query("SELECT id FROM people WHERE id IN (:ids)")
    suspend fun existingPeople(ids: Collection<Long>): List<Long>

    /** Saves the people of an event: exactly the given ones that still exist (one may have been deleted meanwhile). */
    @Transaction
    suspend fun setPeople(eventId: Long, people: Collection<Long>) {
        unlinkAll(eventId)
        if (people.isEmpty()) return
        existingPeople(people).forEach { link(EventPerson(eventId, it)) }
    }
}

/** 1900-01-01 and 2200-01-01 as calendar days: an event outside them (a broken file) is not saved. */
const val EVENT_MIN_DAY = -25_567L
const val EVENT_MAX_DAY = 84_006L
/** The longest event in days; a longer one keeps only its first day, so no screen walks thousands of years. */
const val EVENT_MAX_SPAN = 366L

/** Trims an event before saving; null when there is no title or the day is impossible. The end never comes before the start. */
fun cleanEvent(e: Event): Event? {
    val title = e.title.trim().take(500)
    if (title.isEmpty()) return null
    if (e.day !in EVENT_MIN_DAY..EVENT_MAX_DAY) return null
    val start = e.startMin?.coerceIn(0, 24 * 60 - 1)
    val end = if (start == null) null else e.endMin?.coerceIn(0, 24 * 60)
    val endDay = e.endDay?.takeIf { it > e.day && it - e.day <= EVENT_MAX_SPAN }
    return e.copy(
        title = title, place = e.place.trim().take(500), notes = e.notes.trim().take(MAX_NOTE_LENGTH),
        outcome = e.outcome.trim().take(MAX_NOTE_LENGTH), startMin = start,
        // On one day the end must be after the start
        endMin = if (end != null && start != null && endDay == null && end <= start) null else end,
        endDay = endDay,
    )
}
