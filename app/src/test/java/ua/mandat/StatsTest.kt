package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.mandat.data.Attention
import ua.mandat.data.Deadline
import ua.mandat.data.DeadlineKind
import ua.mandat.data.Matter
import ua.mandat.data.MatterStatus
import ua.mandat.data.MatterType
import ua.mandat.data.Period
import ua.mandat.data.Promise
import ua.mandat.data.Review
import ua.mandat.data.Role
import ua.mandat.data.Span
import ua.mandat.data.TaskStatus
import ua.mandat.data.mattersStrip
import ua.mandat.data.overview
import ua.mandat.data.spanOf
import java.time.LocalDate
import java.time.ZoneOffset

class StatsTest {
    private val utc = ZoneOffset.UTC
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()
    private fun ms(day: Long) = day * 86_400_000L + 43_200_000L

    private fun matter(created: Long, updated: Long = created, role: Long? = null, status: MatterStatus = MatterStatus.ACTIVE, attention: Attention = Attention.BACKGROUND) =
        Matter(title = "Вигадана справа", type = MatterType.OTHER.name, roleId = role, attention = attention.name,
            nextStep = "", goal = "", description = "", status = status.name, createdAt = ms(created), updatedAt = ms(updated))

    private fun promise(created: Long, status: TaskStatus, public: Boolean = true) =
        Promise(what = "Вигадана обіцянка", personId = null, toWhom = "", isPublic = public, matterId = null, due = null,
            status = status.name, createdAt = ms(created), closedAt = null)

    private fun deadline(day: Long, status: TaskStatus, closed: Long?) =
        Deadline(title = "Вигаданий строк", day = day, kind = DeadlineKind.SOFT.name, basis = "", matterId = null,
            remindBefore = 0, status = status.name, createdAt = 0, closedAt = closed?.let(::ms))

    @Test fun calendarSpans() {
        val today = day(2026, 10, 28) // a Wednesday
        assertEquals(Span(day(2026, 10, 26), day(2026, 11, 1)), spanOf(Period.WEEK, today))
        assertEquals(Span(day(2026, 10, 19), day(2026, 10, 25)), spanOf(Period.WEEK, today, back = 1))
        assertEquals(Span(day(2026, 10, 1), day(2026, 10, 31)), spanOf(Period.MONTH, today))
        assertEquals(Span(day(2026, 9, 1), day(2026, 9, 30)), spanOf(Period.MONTH, today, back = 1))
        assertEquals(Span(day(2025, 1, 1), day(2025, 12, 31)), spanOf(Period.YEAR, today, back = 1))
        assertEquals(Span(day(2021, 3, 15), today), spanOf(Period.CADENCE, today, cadenceStart = day(2021, 3, 15)))
        assertEquals(Span(day(2024, 3, 1), today), spanOf(Period.ALL, today, earliest = day(2024, 3, 1)))
        assertEquals(Span(today, today), spanOf(Period.ALL, today))
    }

    @Test fun overviewCountsOnlyThePeriod() {
        val today = day(2026, 10, 28)
        val span = spanOf(Period.WEEK, today)
        val o = overview(
            span, today, withYears = false,
            roles = listOf(Role(id = 1, name = "Вигадана роль", position = 1), Role(id = 2, name = "Інша роль", position = 2)),
            matters = listOf(
                matter(day(2026, 1, 5), updated = day(2026, 10, 27), role = 1),
                matter(day(2026, 10, 26), role = 1),
                matter(day(2026, 2, 1), role = 2), // active, untouched this week
                matter(day(2026, 3, 1), updated = day(2026, 10, 27), status = MatterStatus.DONE),
            ),
            promises = listOf(
                promise(day(2026, 10, 27), TaskStatus.OPEN),
                promise(day(2026, 10, 27), TaskStatus.DONE),
                promise(day(2026, 10, 27), TaskStatus.OPEN, public = false),
                promise(day(2025, 5, 1), TaskStatus.DONE),
            ),
            delegations = emptyList(),
            deadlines = listOf(
                deadline(day(2026, 10, 27), TaskStatus.DONE, closed = day(2026, 10, 26)),
                deadline(day(2026, 10, 26), TaskStatus.DONE, closed = day(2026, 10, 28)),
                deadline(day(2026, 10, 30), TaskStatus.OPEN, closed = null), // still ahead
            ),
            reviews = listOf(Review(day = day(2026, 10, 27), progress = "x", createdAt = 0)),
            zone = utc,
        )
        assertEquals(3, o.moved)
        assertEquals(1, o.started)
        assertEquals(1, o.finished)
        assertEquals(1, o.evenings)
        assertEquals(2, o.deadlinesDue)
        assertEquals(1, o.deadlinesOnTime)
        assertEquals(1, o.publicPromises.kept)
        assertEquals(1, o.publicPromises.open)
        assertEquals(1, o.personalPromises.total)
        val role2 = o.roles.first { it.roleId == 2L }
        assertEquals(0, role2.moved)
        assertEquals(1, role2.active)
        assertTrue(o.years.isEmpty())
    }

    @Test fun yearsForLongPeriods() {
        val today = day(2026, 10, 28)
        val span = spanOf(Period.ALL, today, earliest = day(2024, 6, 1))
        val o = overview(
            span, today, withYears = true, roles = emptyList(),
            matters = listOf(matter(day(2024, 6, 1))),
            promises = listOf(promise(day(2025, 2, 1), TaskStatus.DONE), promise(day(2025, 3, 1), TaskStatus.FAILED)),
            delegations = emptyList(), deadlines = emptyList(), reviews = emptyList(), zone = utc,
        )
        assertEquals(listOf(2024, 2025, 2026), o.years.map { it.year })
        val y2025 = o.years.first { it.year == 2025 }
        assertEquals(2, y2025.promised)
        assertEquals(1, y2025.kept)
        assertEquals(1, o.years.first { it.year == 2024 }.started)
    }

    @Test fun staleMattersIgnoreSleepingOnes() {
        val today = day(2026, 10, 28)
        val strip = mattersStrip(
            listOf(
                matter(day(2026, 1, 1), attention = Attention.FOCUS),
                matter(day(2026, 1, 1), attention = Attention.SLEEPING),
                matter(day(2026, 10, 20)),
            ),
            today, utc,
        )
        assertEquals(3, strip.active)
        assertEquals(1, strip.focus)
        assertEquals(1, strip.stale)
    }
}
