package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.mandat.data.MoneyEntry
import ua.mandat.data.Span
import ua.mandat.data.formatAmount
import ua.mandat.data.parseAmount
import ua.mandat.data.summarize
import ua.mandat.data.toUah

class MoneyTest {
    @Test fun amounts() {
        assertEquals(125050L, parseAmount("1 250,50"))
        assertEquals(125050L, parseAmount("1250.5"))
        assertEquals(125000L, parseAmount("1250"))
        assertNull(parseAmount("12,345"))
        assertNull(parseAmount("-5"))
        assertNull(parseAmount("0"))
        assertNull(parseAmount("abc"))
        assertEquals("1\u202F250,50", formatAmount(125050))
        assertEquals("100", formatAmount(10000))
    }

    @Test fun rates() {
        assertEquals(4_500L, toUah(100, "EUR", 45.0))
        assertEquals(100L, toUah(100, "UAH", 45.0))
    }

    @Test fun summary() {
        fun e(day: Long, kind: String, cat: String, uah: Long, awaiting: Boolean = false, refunded: Boolean = false) =
            MoneyEntry(day = day, kind = kind, category = cat, amount = uah, currency = "UAH", uah = uah, awaitingRefund = awaiting, refunded = refunded, createdAt = 0, updatedAt = 0)
        val s = summarize(
            listOf(e(10, "INCOME", "SALARY", 1000), e(11, "EXPENSE", "CHARITY", 200), e(12, "EXPENSE", "TRIPS", 300, awaiting = true),
                e(1, "EXPENSE", "TRIPS", 50, awaiting = true), e(2, "EXPENSE", "TRIPS", 70, awaiting = true, refunded = true), e(40, "INCOME", "SALARY", 999)),
            Span(10, 20),
        )
        assertEquals(1000L, s.income)
        assertEquals(500L, s.expense)
        assertEquals(200L, s.charity)
        assertEquals(500L, s.balance)
        assertEquals(350L, s.awaiting)
    }
}
