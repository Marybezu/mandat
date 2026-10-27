package ua.mandat.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Looking back over a period: directions, past priorities and how things moved, month by month.
 * Built from the journal of changes ([MatterChange]); for the time before the journal existed it
 * falls back on what is known (creation, last change, current state) and says it is approximate.
 */
object Retro {

    /** Calendar months that overlap [span], each clipped to it. */
    fun months(span: Span): List<Span> {
        val out = ArrayList<Span>()
        var d = LocalDate.ofEpochDay(span.from).withDayOfMonth(1)
        val end = LocalDate.ofEpochDay(span.to)
        while (!d.isAfter(end)) {
            val from = maxOf(d.toEpochDay(), span.from)
            val to = minOf(d.plusMonths(1).minusDays(1).toEpochDay(), span.to)
            out += Span(from, to)
            d = d.plusMonths(1)
        }
        return out
    }

    private fun dayOf(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()

    /**
     * A field's value at the end of [day]: the last journal entry on or before it; before the first
     * entry, that entry's old value; with no entries at all, the current value (approximate).
     */
    fun valueAt(changes: List<MatterChange>, field: String, day: Long, current: String?): Pair<String?, Boolean> {
        val mine = changes.filter { it.field == field }
        if (mine.isEmpty()) return current to true
        val last = mine.lastOrNull { it.day <= day }
        if (last != null) return last.newValue to false
        return (mine.first().oldValue ?: current) to (mine.first().oldValue == null)
    }

    /** The day a matter was finished: from the journal, or else its last change if it is done now. */
    fun finishedOn(m: Matter, changes: List<MatterChange>, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val done = changes.lastOrNull { it.field == FIELD_STATUS && it.newValue == MatterStatus.DONE.name }
        if (done != null) return done.day
        return if (MatterStatus.of(m.status) == MatterStatus.DONE) dayOf(m.updatedAt, zone) else null
    }

    data class FocusMonth(val month: Span, val matterIds: List<Long>, val approximate: Boolean)

    /** Matters that were in focus at some point of each month (and active at that time). */
    fun focusByMonth(span: Span, matters: List<Matter>, history: Map<Long, List<MatterChange>>, zone: ZoneId = ZoneId.systemDefault()): List<FocusMonth> =
        months(span).map { month ->
            var approx = false
            val ids = matters.filter { m ->
                if (dayOf(m.createdAt, zone) > month.to) return@filter false
                val ch = history[m.id].orEmpty()
                val (attStart, a1) = valueAt(ch, FIELD_ATTENTION, month.from - 1, m.attention)
                val (attEnd, a2) = valueAt(ch, FIELD_ATTENTION, month.to, m.attention)
                val (stEnd, a3) = valueAt(ch, FIELD_STATUS, month.to, m.status)
                val into = ch.any { it.field == FIELD_ATTENTION && it.newValue == Attention.FOCUS.name && it.day in month }
                val finished = finishedOn(m, ch, zone)
                val activeThen = stEnd == MatterStatus.ACTIVE.name || (finished != null && finished in month)
                val inFocus = (attStart == Attention.FOCUS.name || attEnd == Attention.FOCUS.name || into) && activeThen
                if (inFocus && (a1 || a2 || a3)) approx = true
                inFocus
            }.map { it.id }
            FocusMonth(month, ids, approx)
        }

    data class MonthFlow(val month: Span, val started: Int, val finished: Int, val dueDeadlines: Int, val onTime: Int)

    /** New and finished matters, and deadlines met on time, for each month. */
    fun flowByMonth(span: Span, today: Long, matters: List<Matter>, history: Map<Long, List<MatterChange>>, deadlines: List<Deadline>, zone: ZoneId = ZoneId.systemDefault()): List<MonthFlow> =
        months(span).map { month ->
            val due = deadlines.filter { it.day in month && it.day <= today && TaskStatus.of(it.status) != TaskStatus.CANCELLED }
            MonthFlow(
                month,
                started = matters.count { dayOf(it.createdAt, zone) in month },
                finished = matters.count { m -> finishedOn(m, history[m.id].orEmpty(), zone)?.let { it in month } == true },
                dueDeadlines = due.size,
                onTime = due.count { TaskStatus.of(it.status) == TaskStatus.DONE && it.closedAt != null && dayOf(it.closedAt, zone) <= it.day },
            )
        }

    /** Days from start to finish of each matter finished within [span]. */
    fun durations(span: Span, matters: List<Matter>, history: Map<Long, List<MatterChange>>, zone: ZoneId = ZoneId.systemDefault()): Map<Long, Long> =
        matters.mapNotNull { m ->
            val f = finishedOn(m, history[m.id].orEmpty(), zone) ?: return@mapNotNull null
            if (f !in span) null else m.id to (f - dayOf(m.createdAt, zone)).coerceAtLeast(0)
        }.toMap()

    /** Minutes by role for each month (see [minutesByRole]). */
    fun timeByMonth(span: Span, events: List<Event>, entries: List<TimeEntry>, roleOfMatter: Map<Long, Long?>): List<Pair<Span, Map<Long, Int>>> =
        months(span).map { it to minutesByRole(it, events, entries, roleOfMatter) }

    /** People met most often in [span], by the number of events together. */
    fun topPeople(span: Span, events: List<Event>, links: List<EventPerson>, limit: Int = 5): List<Pair<Long, Int>> {
        val inSpan = events.filter { it.day in span }.map { it.id }.toSet()
        return links.filter { it.eventId in inSpan }.groupingBy { it.personId }.eachCount()
            .entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
    }
}
