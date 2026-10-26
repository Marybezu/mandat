package ua.mandat.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Public communication, done with integrity: a public discussion, criticism of an opponent's
 * public actions, an answer to an attack, or owning up to a mistake. Mandat helps to keep it
 * honest (sources, actions not persons, proportion); it has nothing for bots, anonymous leaks,
 * fakes, deepfakes or digging into private lives.
 */
@Entity(tableName = "info_items", indices = [Index("day")])
data class InfoItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** A key of [InfoKind]. */
    val kind: String,
    val title: String,
    /** The day it happened or appeared (calendar day). */
    val day: Long,
    /** Where: a link, a programme, a post. */
    val source: String = "",
    /** Who is behind it or who it is about (a person in Mandat), if anyone. */
    val personId: Long? = null,
    val matterId: Long? = null,
    /** How far it spread: 1 little, 2 noticeable, 3 wide; null unknown. */
    val reach: Int? = null,
    /** For an attack: a key of [AttackStage]. */
    val stage: String? = null,
    /** For an attack: a key of [AttackDecision]. */
    val decision: String? = null,
    /** Facts, assessment, the answer given. */
    val notes: String = "",
    /** What came out of it. */
    val result: String = "",
    /** The soft check before publishing: keys of [IntegrityCheck] that were ticked, comma-separated. */
    val checks: String = "",
    /** True when finished (an attack dealt with, a discussion over). */
    val closed: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class InfoKind {
    DISCUSSION, CRITIQUE, ATTACK, CORRECTION;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.name == key } ?: DISCUSSION
    }
}

/** Dealing with an attack: spotted → assessed → decided → acted → result. */
enum class AttackStage {
    SPOTTED, ASSESSED, DECIDED, ACTED, RESULT;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.name == key } ?: SPOTTED
    }
}

/** What to do about an attack. Not answering ("do not feed the bots") is a real choice. */
enum class AttackDecision {
    IGNORE, ANSWER, REFUTE, ALLIES, LEGAL;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.name == key }
    }
}

/** A short reminder before publishing; it never blocks anything. */
enum class IntegrityCheck { SOURCE, ACTIONS, FACTS, SIGN, PROPORTION }

fun checksOf(s: String): Set<IntegrityCheck> = s.split(',').mapNotNull { k -> IntegrityCheck.entries.firstOrNull { it.name == k.trim() } }.toSet()
fun checksText(set: Set<IntegrityCheck>): String = IntegrityCheck.entries.filter { it in set }.joinToString(",") { it.name }

/** A key message with the facts behind it: what the owner says on a topic, and why it is true. */
@Entity(tableName = "key_messages")
data class KeyMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    /** Facts and sources that back it. */
    val proof: String = "",
    /** For whom it is meant (voters, colleagues, media). */
    val audience: String = "",
    val matterId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * A situational alliance: with whom, about what, until when, and who gives and gets what.
 * Not only in the media; written down so that it stays fair and is not forgotten.
 */
@Entity(tableName = "alliances")
data class Alliance(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** With whom: a faction, a group, an organisation, people. */
    val withWhom: String,
    /** A main contact person in Mandat, if any. */
    val personId: Long? = null,
    /** About what. */
    val about: String,
    val matterId: Long? = null,
    /** Until when it is meant to hold (calendar day), or null. */
    val untilDay: Long? = null,
    /** What the owner brings. */
    val gives: String = "",
    /** What the owner gets. */
    val gets: String = "",
    /** A key of [AllianceStatus]. */
    val status: String,
    val notes: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

enum class AllianceStatus {
    ACTIVE, ENDED, BROKEN;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.name == key } ?: ACTIVE
    }
}

@Dao
interface InfoDao {
    @Query("SELECT * FROM info_items ORDER BY day DESC, id DESC")
    fun observeAll(): Flow<List<InfoItem>>

    @Query("SELECT * FROM info_items WHERE matterId = :matterId ORDER BY day DESC")
    fun observeForMatter(matterId: Long): Flow<List<InfoItem>>

    @Query("SELECT * FROM info_items WHERE id = :id")
    suspend fun get(id: Long): InfoItem?

    @Insert
    suspend fun insert(i: InfoItem): Long

    @Update
    suspend fun update(i: InfoItem)

    @Query("DELETE FROM info_items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE info_items SET matterId = NULL WHERE matterId = :matterId")
    suspend fun clearMatter(matterId: Long)

    @Query("UPDATE info_items SET personId = NULL WHERE personId = :personId")
    suspend fun clearPerson(personId: Long)

    @Query("SELECT * FROM key_messages ORDER BY updatedAt DESC")
    fun observeMessages(): Flow<List<KeyMessage>>

    @Insert
    suspend fun insertMessage(m: KeyMessage): Long

    @Update
    suspend fun updateMessage(m: KeyMessage)

    @Query("DELETE FROM key_messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("UPDATE key_messages SET matterId = NULL WHERE matterId = :matterId")
    suspend fun clearMessageMatter(matterId: Long)

    @Query("SELECT * FROM alliances ORDER BY status = 'ACTIVE' DESC, updatedAt DESC")
    fun observeAlliances(): Flow<List<Alliance>>

    @Query("SELECT * FROM alliances WHERE matterId = :matterId ORDER BY updatedAt DESC")
    fun observeAlliancesForMatter(matterId: Long): Flow<List<Alliance>>

    @Query("SELECT * FROM alliances WHERE id = :id")
    suspend fun getAlliance(id: Long): Alliance?

    @Insert
    suspend fun insertAlliance(a: Alliance): Long

    @Update
    suspend fun updateAlliance(a: Alliance)

    @Query("DELETE FROM alliances WHERE id = :id")
    suspend fun deleteAlliance(id: Long)

    @Query("UPDATE alliances SET matterId = NULL WHERE matterId = :matterId")
    suspend fun clearAllianceMatter(matterId: Long)

    @Query("UPDATE alliances SET personId = NULL WHERE personId = :personId")
    suspend fun clearAlliancePerson(personId: Long)
}

/** Trims the free text; null when there is no title. */
fun cleanInfo(i: InfoItem): InfoItem? {
    val title = i.title.trim().take(500)
    if (title.isEmpty()) return null
    val kind = InfoKind.of(i.kind)
    return i.copy(
        kind = kind.name, title = title, source = i.source.trim().take(1_000), notes = i.notes.trim().take(MAX_NOTE_LENGTH),
        result = i.result.trim().take(MAX_NOTE_LENGTH), reach = i.reach?.coerceIn(1, 3),
        // Stage and decision belong to attacks only
        stage = if (kind == InfoKind.ATTACK) AttackStage.of(i.stage).name else null,
        decision = if (kind == InfoKind.ATTACK) AttackDecision.of(i.decision)?.name else null,
        checks = checksText(checksOf(i.checks)),
    )
}

fun cleanMessage(m: KeyMessage): KeyMessage? {
    val text = m.text.trim().take(2_000)
    if (text.isEmpty()) return null
    return m.copy(text = text, proof = m.proof.trim().take(MAX_NOTE_LENGTH), audience = m.audience.trim().take(300))
}

fun cleanAlliance(a: Alliance): Alliance? {
    val with = a.withWhom.trim().take(300)
    val about = a.about.trim().take(1_000)
    if (with.isEmpty() || about.isEmpty()) return null
    return a.copy(
        withWhom = with, about = about, gives = a.gives.trim().take(2_000), gets = a.gets.trim().take(2_000),
        notes = a.notes.trim().take(MAX_NOTE_LENGTH), status = AllianceStatus.of(a.status).name,
    )
}
