package ua.mandat.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** What a text is; the stored value is a stable key. */
enum class TextKind {
    LETTER, SPEECH, COLUMN, NOTE, DRAFT, REPORT, OTHER,
    /** The owner's own template: offered for new texts, not listed among them. */
    TEMPLATE;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.name == key } ?: OTHER
    }
}

/**
 * A longer piece of writing: a speech, a column, an explanatory note, a draft, a report. The body
 * is plain text with light marks: "# " and "## " start headings, "- " a list item.
 */
@Entity(tableName = "texts")
data class TextDoc(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val kind: String,
    val body: String,
    val matterId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** An earlier state of a text, kept when it is saved with changes (the newest few only). */
@Entity(tableName = "text_versions", indices = [Index("textId")])
data class TextVersion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val textId: Long,
    val body: String,
    val savedAt: Long,
)

@Dao
interface TextDao {
    @Query("SELECT * FROM texts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<TextDoc>>

    @Query("SELECT * FROM texts WHERE matterId = :matterId ORDER BY updatedAt DESC")
    fun observeForMatter(matterId: Long): Flow<List<TextDoc>>

    @Query("SELECT * FROM texts WHERE id = :id")
    suspend fun get(id: Long): TextDoc?

    @Insert
    suspend fun insert(t: TextDoc): Long

    @Update
    suspend fun update(t: TextDoc)

    @Query("DELETE FROM texts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE texts SET matterId = NULL WHERE matterId = :matterId")
    suspend fun clearMatter(matterId: Long)

    @Query("SELECT * FROM text_versions WHERE textId = :textId ORDER BY savedAt DESC")
    fun observeVersions(textId: Long): Flow<List<TextVersion>>

    @Insert
    suspend fun insertVersion(v: TextVersion): Long

    /** Keeps only the newest [keep] versions of a text. */
    @Query("DELETE FROM text_versions WHERE textId = :textId AND id NOT IN (SELECT id FROM text_versions WHERE textId = :textId ORDER BY savedAt DESC LIMIT :keep)")
    suspend fun trimVersions(textId: Long, keep: Int)

    @Query("DELETE FROM text_versions WHERE textId = :textId")
    suspend fun deleteVersions(textId: Long)
}

const val MAX_TEXT_LENGTH = 200_000
const val TEXT_VERSIONS_KEPT = 20

/** Cleans a text before saving: trims the title, refuses an empty one, limits lengths. */
fun cleanText(t: TextDoc): TextDoc? {
    val title = t.title.trim().take(300)
    if (title.isEmpty()) return null
    val kind = TextKind.of(t.kind)
    // A template belongs to no matter: it is offered everywhere, so it must carry nothing hidden
    return t.copy(title = title, body = t.body.take(MAX_TEXT_LENGTH), kind = kind.name, matterId = if (kind == TextKind.TEMPLATE) null else t.matterId)
}

/** Words in a text (marks and punctuation not counted). */
fun wordCount(body: String): Int = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’ʼ-]*").findAll(body).count()

/** About how long reading it aloud takes, at a calm pace of 120 words a minute. */
fun speakingMinutes(words: Int): Int = if (words == 0) 0 else maxOf(1, (words + 60) / 120)
