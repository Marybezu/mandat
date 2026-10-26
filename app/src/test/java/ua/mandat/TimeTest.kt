package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ua.mandat.data.Deadline
import ua.mandat.data.DeadlineKind
import ua.mandat.data.Delegation
import ua.mandat.data.TaskStatus
import ua.mandat.data.cleanDeadline
import ua.mandat.data.SearchRow
import ua.mandat.data.search
import ua.mandat.data.reminderDays

class TimeTest {
    private fun deadline(day: Long, before: Int = 0, kind: DeadlineKind = DeadlineKind.SOFT, basis: String = "", status: TaskStatus = TaskStatus.OPEN) =
        Deadline(title = "Вигаданий строк", day = day, kind = kind.name, basis = basis, matterId = null,
            remindBefore = before, status = status.name, createdAt = 0, closedAt = null)

    @Test fun hardDeadlineNeedsBasis() {
        assertNull(cleanDeadline(deadline(10, kind = DeadlineKind.HARD)))
        assertNotNull(cleanDeadline(deadline(10, kind = DeadlineKind.HARD, basis = "вигадана підстава")))
        assertNotNull(cleanDeadline(deadline(10)))
    }

    @Test fun reminderDaysIncludeDayBeforeAndSkipClosed() {
        val days = reminderDays(
            listOf(deadline(20, before = 3), deadline(25, status = TaskStatus.DONE)),
            listOf(Delegation(what = "x", personId = null, matterId = null, due = 22, status = TaskStatus.OPEN.name, createdAt = 0, closedAt = null)),
            emptyList(),
            today = 10,
        )
        assertEquals(listOf(17L, 20L, 22L), days)
    }

    @Test fun overdueRemindsEveryDayOfTheWindow() {
        assertEquals((10L..23L).toList(), reminderDays(listOf(deadline(5)), emptyList(), emptyList(), today = 10))
    }

    @Test fun searchIgnoresCaseAlsoForCyrillic() {
        val rows = listOf(SearchRow("person", 1, "Вигадана Особа", "", "Вигадана Особа Установа", null))
        assertEquals(1, search(rows, "вигадана").size)
        assertEquals(1, search(rows, "УСТАНОВА").size)
        assertEquals(0, search(rows, "інше").size)
        assertEquals(0, search(rows, "в").size)
    }
}
