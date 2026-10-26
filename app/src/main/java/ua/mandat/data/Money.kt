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
 * The owner's own money: what came in and what went out. Everyday management, not a declaration;
 * Mandat names no legal rules about it. Amounts are kept in minor units (kopiyky, cents) of their
 * currency, and also in hryvnias at the rate the owner gave, so totals add up without internet.
 */
@Entity(tableName = "money", indices = [Index("day")])
data class MoneyEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    /** A key of [MoneyKind]. */
    val kind: String,
    /** A category key (see [IncomeCategory], [ExpenseCategory]) or the owner's own category name. */
    val category: String,
    /** The amount in minor units of [currency]. */
    val amount: Long,
    /** An ISO code: UAH, USD, EUR or another. */
    val currency: String,
    /** The same amount in kopiyky, at the rate given when it was written down. */
    val uah: Long,
    val note: String = "",
    val matterId: Long? = null,
    /** For charity: a key of [CharityKind]. */
    val charityKind: String? = null,
    /** For charity: to whom (optional). */
    val toWhom: String = "",
    /** An expense to be compensated (a trip, for example), and whether it already was. */
    val awaitingRefund: Boolean = false,
    val refunded: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class MoneyKind { INCOME, EXPENSE }

/** Default income categories; the owner can add own ones. */
enum class IncomeCategory { SALARY, ACTIVITY_FUNDS, REFUNDS, ROYALTIES, PRACTICE, TEACHING, OTHER_IN }

/** Default expense categories; the owner can add own ones. */
enum class ExpenseCategory { HOME, OFFICE, CHARITY, EXPERTS, TRIPS, PHONE, TRANSPORT, OTHER_OUT }

/** Where a personal donation went. */
enum class CharityKind {
    ARMY, FUNDS, CAMPAIGNS, DIRECT, OTHER;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.name == key }
    }
}

/**
 * Aid the owner organises (a matter of the type "fundraising / aid"): what was collected and
 * what was handed over. Kept apart from the personal budget, never mixed with it.
 */
@Entity(tableName = "aid_records", indices = [Index("matterId")])
data class AidRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matterId: Long,
    val day: Long,
    /** True for collected, false for handed over. */
    val collected: Boolean,
    /** Money in minor units (0 when it is things, not money). */
    val amount: Long,
    val currency: String,
    val uah: Long,
    /** What (for things: "20 generators"), from whom or to whom. */
    val what: String = "",
    val party: String = "",
    val createdAt: Long,
)

@Dao
interface MoneyDao {
    @Query("SELECT * FROM money ORDER BY day DESC, id DESC")
    fun observeAll(): Flow<List<MoneyEntry>>

    @Query("SELECT * FROM money WHERE id = :id")
    suspend fun get(id: Long): MoneyEntry?

    @Insert
    suspend fun insert(m: MoneyEntry): Long

    @Update
    suspend fun update(m: MoneyEntry)

    @Query("DELETE FROM money WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE money SET matterId = NULL WHERE matterId = :matterId")
    suspend fun clearMatter(matterId: Long)

    @Query("SELECT * FROM aid_records ORDER BY day DESC, id DESC")
    fun observeAid(): Flow<List<AidRecord>>

    @Query("SELECT * FROM aid_records WHERE matterId = :matterId ORDER BY day DESC, id DESC")
    fun observeAidFor(matterId: Long): Flow<List<AidRecord>>

    @Insert
    suspend fun insertAid(a: AidRecord): Long

    @Update
    suspend fun updateAid(a: AidRecord)

    @Query("DELETE FROM aid_records WHERE id = :id")
    suspend fun deleteAid(id: Long)

    @Query("DELETE FROM aid_records WHERE matterId = :matterId")
    suspend fun deleteAidFor(matterId: Long)
}

/**
 * Reads an amount typed by a person ("1 250,50", "1250.5", "1 250") into minor units;
 * null when it is not a positive number. At most two decimals.
 */
fun parseAmount(text: String): Long? {
    val t = text.trim().replace(" ", "").replace(" ", "").replace("'", "").replace(',', '.')
    if (t.isEmpty() || t.count { it == '.' } > 1) return null
    if (!t.all { it.isDigit() || it == '.' }) return null
    val parts = t.split('.')
    val whole = parts[0].ifEmpty { "0" }
    if (whole.length > 12) return null
    val frac = parts.getOrNull(1).orEmpty()
    if (frac.length > 2) return null
    val minor = whole.toLong() * 100 + frac.padEnd(2, '0').ifEmpty { "00" }.toLong()
    return minor.takeIf { it > 0 }
}

/** Minor units as text: "1 250,50" (or "1 250" when there are no kopiyky). */
fun formatAmount(minor: Long): String {
    val sign = if (minor < 0) "−" else ""
    val abs = kotlin.math.abs(minor)
    val whole = (abs / 100).toString().reversed().chunked(3).joinToString(" ").reversed()
    val frac = abs % 100
    return sign + whole + if (frac == 0L) "" else ",%02d".format(frac)
}

/** Converts minor units at a rate (hryvnias for one unit of the currency). */
fun toUah(minor: Long, currency: String, rate: Double?): Long =
    if (currency == "UAH" || rate == null) minor else Math.round(minor * rate)

/** Trims and checks an entry; null when the amount is not positive or there is no category. */
fun cleanMoney(m: MoneyEntry): MoneyEntry? {
    if (m.amount <= 0 || m.uah <= 0 || m.category.isBlank()) return null
    val charity = m.kind == MoneyKind.EXPENSE.name && m.category == ExpenseCategory.CHARITY.name
    return m.copy(
        category = m.category.trim().take(100), currency = m.currency.trim().uppercase().take(3).ifEmpty { "UAH" },
        note = m.note.trim().take(2_000), toWhom = if (charity) m.toWhom.trim().take(300) else "",
        charityKind = if (charity) CharityKind.of(m.charityKind)?.name else null,
        awaitingRefund = m.kind == MoneyKind.EXPENSE.name && m.awaitingRefund,
        refunded = m.kind == MoneyKind.EXPENSE.name && m.awaitingRefund && m.refunded,
    )
}

/** The owner's own categories, stored one per line in a setting. */
const val SETTING_INCOME_CATEGORIES = "budget_income_categories"
const val SETTING_EXPENSE_CATEGORIES = "budget_expense_categories"
/** A monthly limit for an expense category, in kopiyky: the setting name is this prefix plus the category. */
const val SETTING_LIMIT_PREFIX = "budget_limit_"

fun decodeCategories(text: String?): List<String> =
    text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(30)

/** Totals for a span: money in and out (in kopiyky), personal charity, what waits for compensation. */
data class MoneySummary(val income: Long, val expense: Long, val charity: Long, val awaiting: Long) {
    val balance get() = income - expense
}

fun summarize(entries: List<MoneyEntry>, span: Span): MoneySummary {
    val inSpan = entries.filter { it.day in span }
    return MoneySummary(
        income = inSpan.filter { it.kind == MoneyKind.INCOME.name }.sumOf { it.uah },
        expense = inSpan.filter { it.kind == MoneyKind.EXPENSE.name }.sumOf { it.uah },
        charity = inSpan.filter { it.kind == MoneyKind.EXPENSE.name && it.category == ExpenseCategory.CHARITY.name }.sumOf { it.uah },
        // Waiting for compensation is counted whenever it was spent
        awaiting = entries.filter { it.awaitingRefund && !it.refunded }.sumOf { it.uah },
    )
}
