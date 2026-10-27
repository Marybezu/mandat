package ua.mandat.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One change of a matter, kept so the past can be looked back on: when it got into focus or out
 * of it, when it was paused or finished, when it moved to another role. Only these few fields.
 */
@Entity(tableName = "matter_history", indices = [Index("matterId")])
data class MatterChange(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matterId: Long,
    val day: Long,
    val at: Long,
    /** One of [FIELD_ATTENTION], [FIELD_STATUS], [FIELD_ROLE]. */
    val field: String,
    val oldValue: String?,
    val newValue: String?,
)

const val FIELD_ATTENTION = "attention"
const val FIELD_STATUS = "status"
const val FIELD_ROLE = "role"

@Dao
interface HistoryDao {
    @Query("SELECT * FROM matter_history ORDER BY at")
    fun observeAll(): Flow<List<MatterChange>>

    @Insert
    suspend fun insertAll(changes: List<MatterChange>)

    @Query("DELETE FROM matter_history WHERE matterId = :matterId")
    suspend fun deleteFor(matterId: Long)
}

/** What changed between the saved [old] matter (null for a new one) and [new], as journal rows. */
fun changesOf(old: Matter?, new: Matter, matterId: Long, at: Long, day: Long): List<MatterChange> {
    val out = ArrayList<MatterChange>(3)
    fun add(field: String, before: String?, after: String?) {
        if (before != after) out += MatterChange(matterId = matterId, day = day, at = at, field = field, oldValue = before, newValue = after)
    }
    add(FIELD_ATTENTION, old?.attention, new.attention)
    add(FIELD_STATUS, old?.status, new.status)
    add(FIELD_ROLE, old?.roleId?.toString(), new.roleId?.toString())
    return out
}
