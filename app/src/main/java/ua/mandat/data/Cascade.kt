package ua.mandat.data

import androidx.room.Dao
import androidx.room.Query

/**
 * Deleting a matter together with everything tied to it (chosen by the owner, the default for a
 * sensitive matter): otherwise its tasks, events and notes would stay without a matter and, no
 * longer hidden by trip mode, show on every screen.
 */
@Dao
interface CascadeDao {
    @Query("DELETE FROM event_people WHERE eventId IN (SELECT id FROM events WHERE matterId = :m)")
    suspend fun eventPeople(m: Long)

    @Query("DELETE FROM community_links WHERE kind = 'event' AND refId IN (SELECT id FROM events WHERE matterId = :m)")
    suspend fun eventCommunities(m: Long)

    @Query("DELETE FROM community_links WHERE kind = 'promise' AND refId IN (SELECT id FROM promises WHERE matterId = :m)")
    suspend fun promiseCommunities(m: Long)

    @Query("DELETE FROM events WHERE matterId = :m")
    suspend fun events(m: Long)

    @Query("DELETE FROM delegations WHERE matterId = :m")
    suspend fun delegations(m: Long)

    @Query("DELETE FROM promises WHERE matterId = :m")
    suspend fun promises(m: Long)

    @Query("DELETE FROM deadlines WHERE matterId = :m")
    suspend fun deadlines(m: Long)

    @Query("DELETE FROM info_items WHERE matterId = :m")
    suspend fun info(m: Long)

    @Query("DELETE FROM key_messages WHERE matterId = :m")
    suspend fun messages(m: Long)

    @Query("DELETE FROM alliances WHERE matterId = :m")
    suspend fun alliances(m: Long)

    @Query("DELETE FROM money WHERE matterId = :m")
    suspend fun money(m: Long)

    @Query("DELETE FROM time_entries WHERE matterId = :m")
    suspend fun time(m: Long)

    @Query("DELETE FROM text_versions WHERE textId IN (SELECT id FROM texts WHERE matterId = :m)")
    suspend fun textVersions(m: Long)

    @Query("DELETE FROM texts WHERE matterId = :m")
    suspend fun texts(m: Long)

    /** Everything tied to [m] goes; call inside the transaction that deletes the matter, before it. */
    suspend fun everythingOf(m: Long) {
        eventPeople(m); eventCommunities(m); promiseCommunities(m)
        events(m); delegations(m); promises(m); deadlines(m)
        info(m); messages(m); alliances(m); money(m); time(m)
        textVersions(m); texts(m)
    }
}
