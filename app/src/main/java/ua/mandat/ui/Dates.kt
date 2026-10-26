package ua.mandat.ui

import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date

/** Dates are stored as the start of the day in the phone's time zone. */
object Dates {
    fun today(): Long = startOfDay(LocalDate.now())

    fun startOfDay(date: LocalDate): Long = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** The date picker gives midnight UTC of the chosen day; keep the same calendar day locally. */
    fun fromPicker(utcMillis: Long): Long = startOfDay(Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate())

    /** Back to the picker's form, so it opens on the stored day. */
    fun toPicker(stored: Long): Long =
        Instant.ofEpochMilli(stored).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun format(stored: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(stored))

    fun addDays(stored: Long, days: Long): Long =
        startOfDay(Instant.ofEpochMilli(stored).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(days))
}
