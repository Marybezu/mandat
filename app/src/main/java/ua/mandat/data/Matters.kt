package ua.mandat.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A "hat": a position or line of work the owner chooses, such as a committee or a district. */
@Entity(tableName = "roles")
data class Role(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int,
)

/** The central unit of work. Stored values of type, attention and status are stable keys. */
@Entity(tableName = "matters")
data class Matter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val type: String,
    val roleId: Long?,
    val attention: String,
    val nextStep: String,
    val goal: String,
    val description: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Small settings kept inside the encrypted database (not in plain app preferences). */
@Entity(tableName = "settings")
data class Setting(
    @PrimaryKey val key: String,
    val value: String,
)

enum class MatterType {
    CAMPAIGN, BILL, INVESTIGATION, COURT, DISTRICT, INTERNATIONAL, WORKING_GROUP, INQUIRY, PUBLIC_COMMS, OTHER;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.name == key } ?: OTHER
    }
}

enum class Attention {
    FOCUS, BACKGROUND, SLEEPING;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.name == key } ?: BACKGROUND
    }
}

enum class MatterStatus {
    ACTIVE, DONE, DROPPED;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.name == key } ?: ACTIVE
    }
}

/** More than this in focus and focus is gone (Covey's "big rocks"); Mandat says so gently. */
const val FOCUS_LIMIT = 5

const val SETTING_ROLES_ASKED = "roles_asked"

/** Just enough of a matter to pick it from a list. */
data class MatterTitle(val id: Long, val title: String)

@Dao
interface RoleDao {
    @Query("SELECT * FROM roles ORDER BY position, name")
    fun observeAll(): Flow<List<Role>>

    @Insert
    suspend fun insert(role: Role): Long

    @Query("UPDATE roles SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM roles WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COALESCE(MAX(position), 0) FROM roles")
    suspend fun maxPosition(): Int
}

@Dao
interface MatterDao {
    @Query("SELECT * FROM matters WHERE status = 'ACTIVE' ORDER BY updatedAt DESC")
    fun observeActive(): Flow<List<Matter>>

    @Query("SELECT * FROM matters WHERE status != 'ACTIVE' ORDER BY updatedAt DESC")
    fun observeClosed(): Flow<List<Matter>>

    @Query("SELECT * FROM matters WHERE status = 'ACTIVE' AND attention = 'FOCUS' ORDER BY updatedAt DESC")
    fun observeFocus(): Flow<List<Matter>>

    @Query("SELECT * FROM matters WHERE id = :id")
    suspend fun get(id: Long): Matter?

    @Query("SELECT id, title FROM matters WHERE status = 'ACTIVE' ORDER BY title COLLATE NOCASE")
    fun observeActiveTitles(): Flow<List<MatterTitle>>

    @Insert
    suspend fun insert(matter: Matter): Long

    @Update
    suspend fun update(matter: Matter)

    @Query("DELETE FROM matters WHERE id = :id")
    suspend fun delete(id: Long)

    /** A deleted role leaves its matters without a role, never deletes them. */
    @Query("UPDATE matters SET roleId = NULL WHERE roleId = :roleId")
    suspend fun clearRole(roleId: Long)
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings WHERE `key` = :key")
    fun observe(key: String): Flow<Setting?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: Setting)
}

/** Cleans a matter before saving: trims fields, refuses an empty title, limits lengths. */
fun cleanMatter(m: Matter): Matter? {
    val title = m.title.trim().take(300)
    if (title.isEmpty()) return null
    return m.copy(
        title = title,
        nextStep = m.nextStep.trim().take(2_000),
        goal = m.goal.trim().take(2_000),
        description = m.description.trim().take(MAX_NOTE_LENGTH),
    )
}

/** An Inbox note turned into a matter: the first line becomes the title, the rest the description. */
fun matterFromNote(note: String, now: Long): Matter {
    val lines = note.trim().lines()
    val first = lines.firstOrNull().orEmpty().trim()
    val title = if (first.length <= 120) first else first.take(117).trimEnd() + "…"
    val rest = if (first.length <= 120) lines.drop(1).joinToString("\n").trim() else note.trim()
    return Matter(
        title = title,
        type = MatterType.OTHER.name,
        roleId = null,
        attention = Attention.BACKGROUND.name,
        nextStep = "",
        goal = "",
        description = rest,
        status = MatterStatus.ACTIVE.name,
        createdAt = now,
        updatedAt = now,
    )
}
