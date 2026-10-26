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
 * A community in the owner's district: a town, a village, a part of a city. Matters, events,
 * promises and people can be linked to it, so each community has its own picture and report.
 */
@Entity(tableName = "communities")
data class Community(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** A key of [CommunityKind]. */
    val kind: String,
    /** About how many people live there; null unknown. */
    val population: Int? = null,
    /** The head or the elder, as text. */
    val head: String = "",
    val notes: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

enum class CommunityKind {
    CITY, TOWN, VILLAGE, AREA, OTHER;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.name == key } ?: OTHER
    }
}

/** What is linked to a community: a matter, an event, a promise or a person. */
@Entity(tableName = "community_links", primaryKeys = ["communityId", "kind", "refId"], indices = [Index("refId")])
data class CommunityLink(val communityId: Long, val kind: String, val refId: Long)

object LinkKind {
    const val MATTER = "matter"
    const val EVENT = "event"
    const val PROMISE = "promise"
    const val PERSON = "person"
}

@Dao
interface CommunityDao {
    @Query("SELECT * FROM communities ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Community>>

    @Query("SELECT COUNT(*) FROM communities")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM communities WHERE id = :id")
    suspend fun get(id: Long): Community?

    @Insert
    suspend fun insert(c: Community): Long

    @Update
    suspend fun update(c: Community)

    @Query("DELETE FROM communities WHERE id = :id")
    suspend fun deleteRow(id: Long)

    @Query("SELECT * FROM community_links")
    fun observeLinks(): Flow<List<CommunityLink>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(l: CommunityLink)

    @Query("DELETE FROM community_links WHERE communityId = :communityId AND kind = :kind AND refId = :refId")
    suspend fun unlink(communityId: Long, kind: String, refId: Long)

    @Query("DELETE FROM community_links WHERE kind = :kind AND refId = :refId")
    suspend fun unlinkRef(kind: String, refId: Long)

    @Query("DELETE FROM community_links WHERE communityId = :communityId")
    suspend fun unlinkAll(communityId: Long)

    /** A community goes; what was linked to it stays, only the link is removed. */
    @Transaction
    suspend fun delete(id: Long) {
        unlinkAll(id)
        deleteRow(id)
    }
}

fun cleanCommunity(c: Community): Community? {
    val name = c.name.trim().take(200)
    if (name.isEmpty()) return null
    return c.copy(
        name = name, kind = CommunityKind.of(c.kind).name, population = c.population?.takeIf { it in 1..20_000_000 },
        head = c.head.trim().take(300), notes = c.notes.trim().take(MAX_NOTE_LENGTH),
    )
}

/** The owner's choice: national parliament or a local council. Decides the bodies, positions and some names. */
const val SETTING_MODE = "mode"
const val MODE_RADA = "RADA"
const val MODE_COUNCIL = "COUNCIL"

/** The district section is shown (for those elected in a district). */
const val SETTING_DISTRICT = "district_on"
