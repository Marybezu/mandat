package ua.mandat.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Someone the owner works with: a colleague, an official, an expert, or a member of the team. */
@Entity(tableName = "people")
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val organization: String,
    val jobTitle: String,
    val contact: String,
    val notes: String,
    /** A key of [TeamRole] if the person is in the owner's team, otherwise null. */
    val teamRole: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Who this is: a key of [PersonCategory], or null. */
    val category: String? = null,
    /** The owner's own words when the category is OTHER. */
    @ColumnInfo(defaultValue = "") val categoryCustom: String = "",
    /** The owner's own words when the team role is OTHER. */
    @ColumnInfo(defaultValue = "") val teamCustom: String = "",
    /** Influence on decisions and on the owner's agenda: 1 low, 2 medium, 3 high; null unknown. */
    val influence: Int? = null,
    /** General stance towards the owner's work: a key of [Stance], or null. */
    val stance: String? = null,
    /** A group of people (for example "residents of a village") rather than one person. */
    @ColumnInfo(defaultValue = "0") val isGroup: Boolean = false,
    /** For the team: whom this person reports to; null means directly to the owner. */
    val reportsToId: Long? = null,
    /** The last day the owner talked with them (calendar day), or null. */
    val lastContact: Long? = null,
)

/** Who takes part in a matter, and (optionally) where they stand on it. */
@Entity(tableName = "matter_people", primaryKeys = ["matterId", "personId"])
data class MatterPerson(
    val matterId: Long,
    val personId: Long,
    /** A key of [Stance], or null when it does not apply. */
    val stance: String?,
    val note: String,
    /** Who does what in this matter: a key of [Raci], or null. */
    val raci: String? = null,
)

/** Something the owner handed to someone: what, to whom, by when. Seen from the owner's side. */
@Entity(tableName = "delegations")
data class Delegation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val what: String,
    val personId: Long?,
    val matterId: Long?,
    /** Due date as a calendar day (days since 1970-01-01, no time zone), or null. */
    val due: Long?,
    /** A key of [TaskStatus]. */
    val status: String,
    val createdAt: Long,
    val closedAt: Long?,
    /** Why it matters: the intent, so the person can act sensibly when things change. */
    @ColumnInfo(defaultValue = "") val purpose: String = "",
    /** What "done" means. */
    @ColumnInfo(defaultValue = "") val doneMeans: String = "",
    /** How much freedom the person has: a key of [Freedom], or null. */
    val freedom: String? = null,
    /** A check-in day halfway, separate from the due day (calendar day), or null. */
    val checkIn: Long? = null,
    /** The task came back to the owner ("upward delegation"). */
    @ColumnInfo(defaultValue = "0") val returned: Boolean = false,
    /** The owner has thanked the person for it. */
    @ColumnInfo(defaultValue = "0") val thanked: Boolean = false,
)

/** Something the owner promised: to whom, what, public or personal, by when. */
@Entity(tableName = "promises")
data class Promise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val what: String,
    val personId: Long?,
    /** To whom, when it is not one person in Mandat (for example "residents of a community"). */
    val toWhom: String,
    val isPublic: Boolean,
    val matterId: Long?,
    /** Due date as a calendar day (days since 1970-01-01, no time zone), or null. */
    val due: Long?,
    /** A key of [TaskStatus]; for promises FAILED is used too. */
    val status: String,
    val createdAt: Long,
    val closedAt: Long?,
    /** Where it was promised: an interview, a post, a meeting; a link or a date. */
    @ColumnInfo(defaultValue = "") val source: String = "",
    /** Why it was dropped or did not work out. */
    @ColumnInfo(defaultValue = "") val statusNote: String = "",
    /** How widely it was said: a key of [Publicity] (PUBLIC also keeps [isPublic] true). */
    @ColumnInfo(defaultValue = "'PERSONAL'") val publicity: String = "PERSONAL",
    /** Where it came from: a key of [PromiseOrigin], or null when not set. */
    val origin: String? = null,
    /** How risky it is to keep (or to fail): 1 low, 2 medium, 3 high; null unknown. */
    val risk: Int? = null,
) {
    /** The level of publicity; the older yes/no field wins for PUBLIC, so old records stay right. */
    val level: Publicity get() = if (isPublic) Publicity.PUBLIC else Publicity.of(publicity).let { if (it == Publicity.PUBLIC) Publicity.PERSONAL else it }
}

/** How widely a promise was made: in public, to a narrow circle, to one person, or to oneself. */
enum class Publicity {
    PUBLIC, CIRCLE, PERSONAL, SELF;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.name == key } ?: PERSONAL
    }
}

/** Where a promise came from: chosen, forced by circumstances, attributed to the owner, or dug up by the media. */
enum class PromiseOrigin {
    CHOSEN, FORCED, ATTRIBUTED, DUG_UP;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.name == key }
    }
}

/** Roles in the owner's team. Keys are stored, so existing ones never change meaning. */
enum class TeamRole {
    ASSISTANT, VOLUNTEER_ASSISTANT, OFFICE_HEAD, INTERN, SECRETARIAT, LAWYER, ADVISOR, COMMS, ANALYST, OTHER;

    companion object {
        fun of(key: String?) = key?.let { k -> entries.firstOrNull { it.name == k } }
    }
}

/** Who a person is, for everyone (team or not). */
enum class PersonCategory {
    COLLEAGUE_MP, OFFICIAL, EXPERT, JOURNALIST, NGO, BUSINESS, INTERNATIONAL, VOTER, OTHER;

    companion object {
        fun of(key: String?) = key?.let { k -> entries.firstOrNull { it.name == k } }
    }
}

/** Levels of freedom in a delegation (after Management 3.0 and situational leadership). */
enum class Freedom {
    TELL, OPTIONS, DECIDE_INFORM, FULL;

    companion object {
        fun of(key: String?) = key?.let { k -> entries.firstOrNull { it.name == k } }
    }
}

/** Who leads, approves, is consulted, is informed (RACI); only one leads a matter. */
enum class Raci {
    LEAD, APPROVE, CONSULT, INFORM;

    companion object {
        fun of(key: String?) = key?.let { k -> entries.firstOrNull { it.name == k } }
    }
}

enum class Stance {
    ALLY, UNDECIDED, OPPONENT, NEUTRAL;

    companion object {
        fun of(key: String?) = key?.let { k -> entries.firstOrNull { it.name == k } }
    }
}

/**
 * Open, done (kept), cancelled (dropped). FAILED ("did not work out") is used only for promises,
 * because from outside a promise that failed and one that was withdrawn are different things.
 */
enum class TaskStatus {
    OPEN, DONE, FAILED, CANCELLED;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.name == key } ?: OPEN
    }
}

/** A person linked to a matter, ready for display. */
data class MatterPersonRow(
    val personId: Long,
    val name: String,
    val teamRole: String?,
    val stance: String?,
    val note: String,
    val raci: String? = null,
)

/** A matter linked to a person, ready for display. */
data class PersonMatterRow(
    val matterId: Long,
    val title: String,
    val stance: String?,
)

@Dao
interface PersonDao {
    @Query("SELECT * FROM people ORDER BY (teamRole IS NULL), name COLLATE NOCASE")
    fun observeAll(): Flow<List<Person>>

    @Query("SELECT * FROM people WHERE id = :id")
    suspend fun get(id: Long): Person?

    @Query("SELECT * FROM people")
    suspend fun all(): List<Person>

    @Insert
    suspend fun insert(person: Person): Long

    @Update
    suspend fun update(person: Person)

    @Query("DELETE FROM people WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "SELECT p.id AS personId, p.name AS name, p.teamRole AS teamRole, mp.stance AS stance, mp.note AS note, mp.raci AS raci " +
            "FROM matter_people mp JOIN people p ON p.id = mp.personId WHERE mp.matterId = :matterId " +
            "ORDER BY p.name COLLATE NOCASE",
    )
    fun observeForMatter(matterId: Long): Flow<List<MatterPersonRow>>

    @Query(
        "SELECT m.id AS matterId, m.title AS title, mp.stance AS stance " +
            "FROM matter_people mp JOIN matters m ON m.id = mp.matterId WHERE mp.personId = :personId " +
            "ORDER BY m.status = 'ACTIVE' DESC, m.updatedAt DESC",
    )
    fun observeMattersOf(personId: Long): Flow<List<PersonMatterRow>>

    /** All links between people and matters, for the overview of stances. */
    @Query("SELECT * FROM matter_people")
    fun observeLinks(): Flow<List<MatterPerson>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(link: MatterPerson)

    @Query("UPDATE matter_people SET stance = :stance WHERE matterId = :matterId AND personId = :personId")
    suspend fun setStance(matterId: Long, personId: Long, stance: String?)

    @Query("UPDATE matter_people SET raci = :raci WHERE matterId = :matterId AND personId = :personId")
    suspend fun setRaci(matterId: Long, personId: Long, raci: String?)

    /** Only one person leads a matter: choosing a new lead clears the old one. */
    @Query("UPDATE matter_people SET raci = NULL WHERE matterId = :matterId AND raci = 'LEAD'")
    suspend fun clearLead(matterId: Long)

    @Query("UPDATE people SET lastContact = :day WHERE id = :personId")
    suspend fun setLastContact(personId: Long, day: Long)

    /** Moves the last contact forward only, never back to an older day. */
    @Query("UPDATE people SET lastContact = :day WHERE id = :personId AND (lastContact IS NULL OR lastContact < :day)")
    suspend fun touchLastContact(personId: Long, day: Long)

    @Query("DELETE FROM matter_people WHERE matterId = :matterId AND personId = :personId")
    suspend fun unlink(matterId: Long, personId: Long)

    /** People who reported to a removed person now report to the owner directly. */
    @Query("UPDATE people SET reportsToId = NULL WHERE reportsToId = :personId")
    suspend fun clearReportsTo(personId: Long)

    /** Removing a person keeps delegations and promises, just without the person. */
    @Query("DELETE FROM matter_people WHERE personId = :personId")
    suspend fun unlinkPerson(personId: Long)

    @Query("DELETE FROM matter_people WHERE matterId = :matterId")
    suspend fun unlinkMatter(matterId: Long)
}

@Dao
interface DelegationDao {
    @Query("SELECT * FROM delegations")
    fun observeAll(): Flow<List<Delegation>>

    @Query("SELECT * FROM delegations WHERE status = 'OPEN' ORDER BY (due IS NULL), due, createdAt")
    fun observeOpen(): Flow<List<Delegation>>

    @Query("SELECT * FROM delegations WHERE personId = :personId ORDER BY status = 'OPEN' DESC, (due IS NULL), due, createdAt DESC")
    fun observeForPerson(personId: Long): Flow<List<Delegation>>

    @Query("SELECT * FROM delegations WHERE matterId = :matterId ORDER BY status = 'OPEN' DESC, (due IS NULL), due, createdAt DESC")
    fun observeForMatter(matterId: Long): Flow<List<Delegation>>

    @Query("SELECT * FROM delegations WHERE status = 'OPEN'")
    suspend fun open(): List<Delegation>

    @Query("SELECT * FROM delegations WHERE id = :id")
    suspend fun get(id: Long): Delegation?

    @Insert
    suspend fun insert(d: Delegation): Long

    @Update
    suspend fun update(d: Delegation)

    @Query("DELETE FROM delegations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE delegations SET personId = NULL WHERE personId = :personId")
    suspend fun clearPerson(personId: Long)

    @Query("UPDATE delegations SET matterId = NULL WHERE matterId = :matterId")
    suspend fun clearMatter(matterId: Long)
}

@Dao
interface PromiseDao {
    @Query("SELECT * FROM promises WHERE status = 'OPEN' ORDER BY (due IS NULL), due, createdAt")
    fun observeOpen(): Flow<List<Promise>>

    @Query("SELECT * FROM promises WHERE personId = :personId ORDER BY status = 'OPEN' DESC, (due IS NULL), due, createdAt DESC")
    fun observeForPerson(personId: Long): Flow<List<Promise>>

    @Query("SELECT * FROM promises WHERE matterId = :matterId ORDER BY status = 'OPEN' DESC, (due IS NULL), due, createdAt DESC")
    fun observeForMatter(matterId: Long): Flow<List<Promise>>

    @Query("SELECT * FROM promises WHERE status = 'OPEN'")
    suspend fun open(): List<Promise>

    /** All promises ever made, newest first: the owner's own record of what was promised. */
    @Query("SELECT * FROM promises ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Promise>>

    @Query("SELECT * FROM promises WHERE id = :id")
    suspend fun get(id: Long): Promise?

    @Insert
    suspend fun insert(p: Promise): Long

    @Update
    suspend fun update(p: Promise)

    @Query("DELETE FROM promises WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE promises SET personId = NULL WHERE personId = :personId")
    suspend fun clearPerson(personId: Long)

    @Query("UPDATE promises SET matterId = NULL WHERE matterId = :matterId")
    suspend fun clearMatter(matterId: Long)
}

/** Cleans a person before saving; null if there is no name. */
fun cleanPerson(p: Person): Person? {
    val name = p.name.trim().take(200)
    if (name.isEmpty()) return null
    return p.copy(
        name = name,
        organization = p.organization.trim().take(300),
        jobTitle = p.jobTitle.trim().take(300),
        contact = p.contact.trim().take(1_000),
        notes = p.notes.trim().take(MAX_NOTE_LENGTH),
        categoryCustom = if (PersonCategory.of(p.category) == PersonCategory.OTHER) p.categoryCustom.trim().take(100) else "",
        teamCustom = if (TeamRole.of(p.teamRole) == TeamRole.OTHER) p.teamCustom.trim().take(100) else "",
        influence = p.influence?.coerceIn(1, 3),
        // Only someone in the team reports to someone, and never to themselves
        reportsToId = if (p.teamRole != null && p.reportsToId != p.id) p.reportsToId else null,
    )
}

/** Open and past its due day (the due day itself is not yet overdue). Days as in [Delegation.due]. */
fun isOverdue(due: Long?, status: String, today: Long): Boolean =
    due != null && TaskStatus.of(status) == TaskStatus.OPEN && due < today
