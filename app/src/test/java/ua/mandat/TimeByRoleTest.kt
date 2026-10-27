package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.mandat.data.Event
import ua.mandat.data.Span
import ua.mandat.data.TimeEntry
import ua.mandat.data.minutesByRole

class TimeByRoleTest {
    private fun ev(day: Long, start: Int?, end: Int?, matter: Long?, last: Long? = null) =
        Event(title = "Тест", day = day, endDay = last, startMin = start, endMin = end, kind = "MEETING", matterId = matter, createdAt = 0, updatedAt = 0)

    @Test fun countsEventsAndEntriesByRole() {
        val roleOf = mapOf(1L to 10L, 2L to null)
        val events = listOf(
            ev(100, 600, 690, 1),      // 90 min, role 10
            ev(100, 600, null, 2),     // 60 min, matter without a role
            ev(100, null, null, 1),    // whole day: not counted
            ev(100, 600, 700, 1, 102), // several days: not counted
            ev(200, 600, 700, 1),      // outside the period
        )
        val entries = listOf(
            TimeEntry(day = 101, minutes = 30, matterId = 1, roleId = 99, createdAt = 0), // the matter's current role wins
            TimeEntry(day = 101, minutes = 15, matterId = 7, roleId = 10, createdAt = 0), // unknown matter: the kept role
            TimeEntry(day = 101, minutes = 15, matterId = null, roleId = null, createdAt = 0),
        )
        val out = minutesByRole(Span(100, 110), events, entries, roleOf)
        assertEquals(90 + 30 + 15, out[10L])
        assertEquals(60 + 15, out[0L])
    }
}
