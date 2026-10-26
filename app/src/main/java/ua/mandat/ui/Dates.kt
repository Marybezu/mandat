package ua.mandat.ui

import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * Due dates are stored as a calendar day (days since 1970-01-01), with no time zone,
 * so a date stays the same day after travelling or a time-zone change.
 */
object Dates {
    private const val DAY_MS = 86_400_000L

    fun today(): Long = LocalDate.now().toEpochDay()

    /** The date picker gives midnight UTC of the chosen day. */
    fun fromPicker(utcMillis: Long): Long = Math.floorDiv(utcMillis, DAY_MS)

    fun toPicker(day: Long): Long = day * DAY_MS

    fun format(day: Long): String {
        val local = LocalDate.ofEpochDay(day).atStartOfDay(ZoneId.systemDefault()).toInstant()
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(local))
    }

    fun addDays(day: Long, days: Long): Long = day + days
}
