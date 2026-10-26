package ua.mandat.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The periods of the Review dashboard. */
enum class Period { WEEK, MONTH, YEAR, CADENCE, ALL }

/** Calendar days from [from] to [to], both included (days since 1970-01-01). */
data class Span(val from: Long, val to: Long) {
    operator fun contains(day: Long) = day in from..to
}

/** Where the cadence start date is kept (a calendar day, inside the encrypted database). */
const val SETTING_CADENCE_START = "cadence_start"

/**
 * The days a period covers. Week is the last seven days, month and year are calendar ones; [back] steps to earlier ones
 * (1 = the previous week, month or year). Cadence runs from its start day, everything from the
 * earliest record; both end today.
 */
fun spanOf(period: Period, today: Long, back: Int = 0, cadenceStart: Long? = null, earliest: Long? = null): Span {
    val d = LocalDate.ofEpochDay(today)
    val b = back.coerceAtLeast(0).toLong()
    return when (period) {
        // The last seven days (so a Monday is never empty); [back] steps a week earlier
        Period.WEEK -> Span(today - 6 - 7 * b, today - 7 * b)
        Period.MONTH -> {
            val start = d.withDayOfMonth(1).minusMonths(b)
            Span(start.toEpochDay(), start.plusMonths(1).minusDays(1).toEpochDay())
        }
        Period.YEAR -> {
            val start = d.withDayOfYear(1).minusYears(b)
            Span(start.toEpochDay(), start.plusYears(1).minusDays(1).toEpochDay())
        }
        Period.CADENCE -> Span(minOf(cadenceStart ?: earliest ?: today, today), today)
        Period.ALL -> Span(minOf(listOfNotNull(earliest, cadenceStart, today).min(), today), today)
    }
}

/** Kept, still open, did not work out, dropped. */
data class Tally(val kept: Int = 0, val open: Int = 0, val failed: Int = 0, val dropped: Int = 0) {
    val total get() = kept + open + failed + dropped
}

/** How many matters of a role moved in the period, and how many are active now. */
data class RoleMove(val roleId: Long?, val moved: Int, val active: Int)

/** One year of a long period. */
data class YearRow(val year: Int, val started: Int, val promised: Int, val kept: Int, val deadlinesDone: Int)

/** Everything the Review dashboard shows for one period. Plain facts, never scores of people. */
data class Overview(
    /** Matters touched in the period (created, changed or closed). */
    val moved: Int,
    val started: Int,
    val finished: Int,
    val evenings: Int,
    /** Deadlines whose day fell in the period, up to today (cancelled ones not counted). */
    val deadlinesDue: Int,
    val deadlinesOnTime: Int,
    val delegationsGiven: Int,
    val delegationsDone: Int,
    /** Promises made in the period, by their state now. */
    val publicPromises: Tally,
    val personalPromises: Tally,
    /** All public promises ever made, by their state now: always shown as the background. */
    val publicAll: Tally,
    /** Promises (public and personal) kept in the period, by the day they were closed. */
    val keptInSpan: Int,
    val roles: List<RoleMove>,
    /** Only for cadence and everything: one row per year. */
    val years: List<YearRow>,
) {
    val isEmpty get() = moved == 0 && evenings == 0 && deadlinesDue == 0 && delegationsGiven == 0 &&
        delegationsDone == 0 && publicPromises.total == 0 && personalPromises.total == 0 && keptInSpan == 0
}

/** The first day anything was recorded, or null when there is nothing yet. */
fun earliestDay(
    matters: List<Matter>, promises: List<Promise>, delegations: List<Delegation>,
    deadlines: List<Deadline>, reviews: List<Review>, zone: ZoneId = ZoneId.systemDefault(),
): Long? {
    fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()
    return (matters.map { day(it.createdAt) } + promises.map { day(it.createdAt) } +
        delegations.map { day(it.createdAt) } + deadlines.map { day(it.createdAt) } + reviews.map { it.day })
        .minOrNull()
}

fun overview(
    span: Span,
    today: Long,
    withYears: Boolean,
    roles: List<Role>,
    matters: List<Matter>,
    promises: List<Promise>,
    delegations: List<Delegation>,
    deadlines: List<Deadline>,
    reviews: List<Review>,
    zone: ZoneId = ZoneId.systemDefault(),
): Overview {
    fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()
    fun yearOf(day: Long) = LocalDate.ofEpochDay(day).year

    val moved = matters.filter { day(it.updatedAt) in span || day(it.createdAt) in span }
    val due = deadlines.filter {
        it.day in span && it.day <= today && TaskStatus.of(it.status) != TaskStatus.CANCELLED
    }
    val onTime = due.count {
        TaskStatus.of(it.status) == TaskStatus.DONE && it.closedAt != null && day(it.closedAt) <= it.day
    }

    fun tally(list: List<Promise>) = Tally(
        kept = list.count { TaskStatus.of(it.status) == TaskStatus.DONE },
        open = list.count { TaskStatus.of(it.status) == TaskStatus.OPEN },
        failed = list.count { TaskStatus.of(it.status) == TaskStatus.FAILED },
        dropped = list.count { TaskStatus.of(it.status) == TaskStatus.CANCELLED },
    )
    val made = promises.filter { day(it.createdAt) in span }

    val activeByRole = matters.filter { MatterStatus.of(it.status) == MatterStatus.ACTIVE }.groupBy { it.roleId }
    val movedByRole = moved.groupBy { it.roleId }
    val roleIds = roles.map<Role, Long?> { it.id } + listOf(null)
    val roleMoves = roleIds
        .map { RoleMove(it, movedByRole[it]?.size ?: 0, activeByRole[it]?.size ?: 0) }
        .filter { it.moved > 0 || it.active > 0 }

    val years = if (!withYears) emptyList() else (yearOf(span.from)..yearOf(today)).map { y ->
        val promisedThatYear = promises.filter { yearOf(day(it.createdAt)) == y }
        YearRow(
            year = y,
            started = matters.count { yearOf(day(it.createdAt)) == y },
            promised = promisedThatYear.size,
            kept = promisedThatYear.count { TaskStatus.of(it.status) == TaskStatus.DONE },
            deadlinesDone = deadlines.count { yearOf(it.day) == y && TaskStatus.of(it.status) == TaskStatus.DONE },
        )
    }

    return Overview(
        moved = moved.size,
        started = matters.count { day(it.createdAt) in span },
        finished = matters.count { MatterStatus.of(it.status) == MatterStatus.DONE && day(it.updatedAt) in span },
        evenings = reviews.count { it.day in span },
        deadlinesDue = due.size,
        deadlinesOnTime = onTime,
        delegationsGiven = delegations.count { day(it.createdAt) in span },
        delegationsDone = delegations.count {
            TaskStatus.of(it.status) == TaskStatus.DONE && it.closedAt != null && day(it.closedAt) in span
        },
        publicPromises = tally(made.filter { it.isPublic }),
        personalPromises = tally(made.filter { !it.isPublic }),
        publicAll = tally(promises.filter { it.isPublic }),
        keptInSpan = promises.count { TaskStatus.of(it.status) == TaskStatus.DONE && it.closedAt != null && day(it.closedAt) in span },
        roles = roleMoves,
        years = years,
    )
}

/** Matters active but untouched for at least this many days count as "without movement". */
const val STALE_DAYS = 30

/** Facts for the strip above the matters list. */
data class MattersStrip(val active: Int, val focus: Int, val stale: Int)

fun mattersStrip(active: List<Matter>, today: Long, zone: ZoneId = ZoneId.systemDefault()): MattersStrip {
    fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()
    val live = active.filter { MatterStatus.of(it.status) == MatterStatus.ACTIVE }
    return MattersStrip(
        active = live.size,
        focus = live.count { Attention.of(it.attention) == Attention.FOCUS },
        stale = live.count { Attention.of(it.attention) != Attention.SLEEPING && today - day(it.updatedAt) >= STALE_DAYS },
    )
}

fun isStale(m: Matter, today: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
    MatterStatus.of(m.status) == MatterStatus.ACTIVE && Attention.of(m.attention) != Attention.SLEEPING &&
        today - Instant.ofEpochMilli(m.updatedAt).atZone(zone).toLocalDate().toEpochDay() >= STALE_DAYS
