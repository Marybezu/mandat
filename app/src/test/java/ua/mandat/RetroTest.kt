package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.mandat.data.FIELD_ATTENTION
import ua.mandat.data.FIELD_STATUS
import ua.mandat.data.Matter
import ua.mandat.data.MatterChange
import ua.mandat.data.Retro
import ua.mandat.data.Span
import ua.mandat.data.changesOf
import java.time.LocalDate
import java.time.ZoneOffset

class RetroTest {
    private val utc = ZoneOffset.UTC
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()
    private fun ms(day: Long) = day * 86_400_000L
    private fun matter(id: Long, created: Long, attention: String, status: String = "ACTIVE", updated: Long = created) =
        Matter(id = id, title = "Тест $id", type = "OTHER", roleId = null, attention = attention, nextStep = "", goal = "", description = "",
            status = status, createdAt = ms(created), updatedAt = ms(updated))

    @Test fun journalRecordsOnlyChanges() {
        val a = matter(1, 0, "FOCUS")
        assertEquals(2, changesOf(null, a, 1, 0, 0).size) // a new matter: attention and status (role stays null)
        assertTrue(changesOf(a, a, 1, 0, 0).isEmpty())
        assertEquals(listOf(FIELD_ATTENTION), changesOf(a, a.copy(attention = "SLEEPING"), 1, 0, 0).map { it.field })
    }

    @Test fun focusFollowsTheJournal() {
        val jan = day(2026, 1, 10)
        val mar = day(2026, 3, 5)
        val m = matter(1, jan, "SLEEPING")
        val history = mapOf(1L to listOf(
            MatterChange(matterId = 1, day = jan, at = 0, field = FIELD_ATTENTION, oldValue = null, newValue = "FOCUS"),
            MatterChange(matterId = 1, day = mar, at = 0, field = FIELD_ATTENTION, oldValue = "FOCUS", newValue = "SLEEPING"),
        ))
        val months = Retro.focusByMonth(Span(day(2026, 1, 1), day(2026, 4, 30)), listOf(m), history, utc)
        assertEquals(listOf(listOf(1L), listOf(1L), listOf(1L), emptyList()), months.map { it.matterIds })
        assertTrue(months.none { it.approximate })
    }

    @Test fun finishedFromJournalOrLastChange() {
        val m = matter(1, day(2026, 1, 1), "BACKGROUND", status = "DONE", updated = day(2026, 2, 20))
        assertEquals(day(2026, 2, 20), Retro.finishedOn(m, emptyList(), utc))
        val done = MatterChange(matterId = 1, day = day(2026, 2, 3), at = 0, field = FIELD_STATUS, oldValue = "ACTIVE", newValue = "DONE")
        assertEquals(day(2026, 2, 3), Retro.finishedOn(m, listOf(done), utc))
        assertEquals(mapOf(1L to 33L), Retro.durations(Span(day(2026, 2, 1), day(2026, 2, 28)), listOf(m), mapOf(1L to listOf(done)), utc))
    }

    @Test fun monthsAreClipped() {
        val ms = Retro.months(Span(day(2026, 1, 15), day(2026, 3, 10)))
        assertEquals(3, ms.size)
        assertEquals(day(2026, 1, 15), ms[0].from)
        assertEquals(day(2026, 3, 10), ms[2].to)
    }
}
