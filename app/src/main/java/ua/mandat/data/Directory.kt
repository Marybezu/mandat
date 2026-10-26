package ua.mandat.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * The address book: everyone imported or added, with only the basic fields. Thousands of entries
 * are fine here; dashboards and "People" stay for those the owner actually works with.
 * Someone moves to "People" when the owner links them to a matter, an event or a task.
 */
@Entity(tableName = "contacts", indices = [Index("sortKey")])
data class Contact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Phone numbers, one per line. */
    val phones: String = "",
    /** E-mail addresses, one per line. */
    val emails: String = "",
    val organization: String = "",
    val jobTitle: String = "",
    val notes: String = "",
    /** A group given on import or by hand (for example "Party", "Community"). */
    val label: String = "",
    /** The name in lower case, for sorting and the alphabet index. */
    val sortKey: String,
    val createdAt: Long,
)

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY sortKey")
    fun observeAll(): Flow<List<Contact>>

    @Query("SELECT COUNT(*) FROM contacts")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM contacts")
    suspend fun all(): List<Contact>

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun get(id: Long): Contact?

    @Insert
    suspend fun insert(c: Contact): Long

    @Insert
    suspend fun insertAll(list: List<Contact>)

    @Update
    suspend fun update(c: Contact)

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM contacts WHERE label = :label")
    suspend fun deleteLabel(label: String)

    /** Moves a contact to People in one step: the new person appears and the entry leaves the book. */
    @Transaction
    suspend fun moveToPeople(people: PersonDao, c: Contact, now: Long): Long {
        val id = people.insert(contactToPerson(c, now))
        delete(c.id)
        return id
    }
}

fun contactToPerson(c: Contact, now: Long) = Person(
    name = c.name, organization = c.organization, jobTitle = c.jobTitle,
    contact = (c.phones.lines() + c.emails.lines()).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"),
    notes = c.notes, teamRole = null, createdAt = now, updatedAt = now,
)

fun sortKeyOf(name: String) = name.trim().lowercase()

/** Trims a contact; null when it has neither a name nor a phone nor an e-mail. */
fun cleanContact(c: Contact): Contact? {
    val phones = c.phones.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(20).joinToString("\n")
    val emails = c.emails.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(20).joinToString("\n")
    val name = c.name.trim().take(300).ifEmpty { phones.lineSequence().firstOrNull() ?: emails.lineSequence().firstOrNull() ?: "" }
    if (name.isEmpty()) return null
    return c.copy(
        name = name, phones = phones.take(2_000), emails = emails.take(2_000),
        organization = c.organization.trim().take(300), jobTitle = c.jobTitle.trim().take(300),
        notes = c.notes.trim().take(5_000), label = c.label.trim().take(100), sortKey = sortKeyOf(name),
    )
}

/**
 * A phone number in international form for calls and messengers: digits with a leading "+".
 * A national Ukrainian number (0XX XXX XX XX) gets +38; anything too short is not a phone.
 */
fun normalizePhone(raw: String): String? {
    val t = raw.trim()
    val digits = t.filter { it.isDigit() }
    if (digits.length < 5 || digits.length > 15) return null
    return when {
        t.startsWith("+") -> "+$digits"
        digits.startsWith("00") -> "+" + digits.drop(2)
        digits.length == 10 && digits.startsWith("0") -> "+38$digits"
        digits.length == 12 && digits.startsWith("380") -> "+$digits"
        else -> digits
    }
}

private val PHONE = Regex("""\+?\d[\d\s\-().]{5,}\d""")

/** The phone numbers written somewhere in free text (for example a person's contact field). */
fun phonesIn(text: String): List<String> =
    PHONE.findAll(text).mapNotNull { normalizePhone(it.value) }.distinct().toList()

private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

fun emailsIn(text: String): List<String> = EMAIL.findAll(text).map { it.value }.distinct().toList()

/** What an import would do: new entries, and how many were skipped as already known. */
class ImportPlan(val fresh: List<Contact>, val duplicates: Int)

/**
 * Leaves out entries already in the book or in People (same phone, or same name when there is
 * no phone), and repeats inside the file itself.
 */
fun planImport(incoming: List<Contact>, book: List<Contact>, people: List<Person>): ImportPlan {
    val phones = HashSet<String>()
    val names = HashSet<String>()
    book.forEach { c -> c.phones.lines().mapNotNullTo(phones) { normalizePhone(it) }; names += sortKeyOf(c.name) }
    people.forEach { p -> phonesIn(p.contact).forEach { phones += it }; names += sortKeyOf(p.name) }
    val fresh = ArrayList<Contact>()
    var dup = 0
    for (c in incoming) {
        val own = c.phones.lines().mapNotNull { normalizePhone(it) }
        val key = sortKeyOf(c.name)
        val known = if (own.isNotEmpty()) own.any { it in phones } else key in names
        if (known) { dup++; continue }
        phones += own
        names += key
        fresh += c
    }
    return ImportPlan(fresh, dup)
}
