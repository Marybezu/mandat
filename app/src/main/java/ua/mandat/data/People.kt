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
)

/** Who takes part in a matter, and (optionally) where they stand on it. */
@Entity(tableName = "matter_people", primaryKeys = ["matterId", "personId"])
data class MatterPerson(
    val matterId: Long,
    val personId: Long,
    /** A key of [Stance], or null when it does not apply. */
    val stance: String?,
    val note: String,
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
)

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

    @Insert
    suspend fun insert(person: Person): Long

    @Update
    suspend fun update(person: Person)

    @Query("DELETE FROM people WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "SELECT p.id AS personId, p.name AS name, p.teamRole AS teamRole, mp.stance AS stance, mp.note AS note " +
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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(link: MatterPerson)

    @Query("UPDATE matter_people SET stance = :stance WHERE matterId = :matterId AND personId = :personId")
    suspend fun setStance(matterId: Long, personId: Long, stance: String?)

    @Query("DELETE FROM matter_people WHERE matterId = :matterId AND personId = :personId")
    suspend fun unlink(matterId: Long, personId: Long)

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
    )
}

/** Open and past its due day (the due day itself is not yet overdue). Days as in [Delegation.due]. */
fun isOverdue(due: Long?, status: String, today: Long): Boolean =
    due != null && TaskStatus.of(status) == TaskStatus.OPEN && due < today
