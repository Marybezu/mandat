package ua.mandat.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Turns Mandat's records into open formats other apps understand: vCard for contacts, iCalendar
 * for events, CSV for tables, Markdown for notes and dossiers. Text only, built in memory;
 * nothing is written to the phone here.
 */
object Export {

    // ── vCard 3.0 ──

    private fun vEsc(s: String) = s.replace("\r\n", "\n").replace('\r', '\n').replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")

    /** Lines longer than 75 bytes are folded, as the format asks. */
    private fun fold(line: String): String {
        val out = StringBuilder()
        var count = 0
        var i = 0
        while (i < line.length) {
            // One whole character at a time, so an emoji (two chars) is never split
            val cp = line.codePointAt(i)
            val n = Character.charCount(cp)
            val len = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
            if (count + len > 74) { out.append("\r\n "); count = 1 }
            out.appendCodePoint(cp)
            count += len
            i += n
        }
        return out.toString()
    }

    data class VContact(val name: String, val phones: List<String>, val emails: List<String>, val org: String, val title: String,
                        val note: String, val categories: List<String>)

    fun vcard(list: List<VContact>): String = buildString {
        for (c in list) {
            append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
            append(fold("FN:" + vEsc(c.name))).append("\r\n")
            append(fold("N:" + vEsc(c.name) + ";;;;")).append("\r\n")
            c.phones.forEach { append(fold("TEL;TYPE=CELL:" + vEsc(it))).append("\r\n") }
            c.emails.forEach { append(fold("EMAIL:" + vEsc(it))).append("\r\n") }
            if (c.org.isNotBlank()) append(fold("ORG:" + vEsc(c.org))).append("\r\n")
            if (c.title.isNotBlank()) append(fold("TITLE:" + vEsc(c.title))).append("\r\n")
            if (c.note.isNotBlank()) append(fold("NOTE:" + vEsc(c.note))).append("\r\n")
            if (c.categories.isNotEmpty()) append(fold("CATEGORIES:" + c.categories.joinToString(",") { vEsc(it) })).append("\r\n")
            append("END:VCARD\r\n")
        }
    }

    fun personToV(p: Person, categories: List<String>) = VContact(
        p.name, phonesIn(p.contact), emailsIn(p.contact), p.organization, p.jobTitle, p.notes, categories,
    )

    fun contactToV(c: Contact) = VContact(
        c.name, c.phones.lines().filter { it.isNotBlank() }, c.emails.lines().filter { it.isNotBlank() }, c.organization, c.jobTitle,
        c.notes, listOfNotNull(c.label.ifBlank { null }),
    )

    // ── iCalendar ──

    private val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val dateOnly = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun ics(events: List<Event>, kindName: (String) -> String, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): String = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Mandat//UK\r\nCALSCALE:GREGORIAN\r\n")
        for (e in events) {
            append("BEGIN:VEVENT\r\n")
            append("UID:mandat-").append(e.id).append('-').append(e.createdAt).append("@local\r\n")
            append("DTSTAMP:").append(Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).format(utc)).append("\r\n")
            if (e.startMin == null) {
                append("DTSTART;VALUE=DATE:").append(LocalDate.ofEpochDay(e.day).format(dateOnly)).append("\r\n")
                append("DTEND;VALUE=DATE:").append(LocalDate.ofEpochDay(e.lastDay + 1).format(dateOnly)).append("\r\n")
            } else {
                val start = LocalDate.ofEpochDay(e.day).atStartOfDay().plusMinutes(e.startMin.toLong())
                val end = LocalDate.ofEpochDay(e.lastDay).atStartOfDay().plusMinutes((e.endMin ?: (e.startMin + 60)).toLong())
                append("DTSTART:").append(start.atZone(zone).withZoneSameInstant(ZoneOffset.UTC).format(utc)).append("\r\n")
                append("DTEND:").append(maxOf(end, start.plusMinutes(15)).atZone(zone).withZoneSameInstant(ZoneOffset.UTC).format(utc)).append("\r\n")
            }
            append(fold("SUMMARY:" + vEsc(e.title))).append("\r\n")
            if (e.place.isNotBlank()) append(fold("LOCATION:" + vEsc(e.place))).append("\r\n")
            val desc = listOf(e.notes, e.outcome.ifBlank { "" }.let { if (it.isEmpty()) "" else "✓ $it" }).filter { it.isNotBlank() }.joinToString("\n")
            if (desc.isNotBlank()) append(fold("DESCRIPTION:" + vEsc(desc))).append("\r\n")
            append(fold("CATEGORIES:" + vEsc(kindName(e.kind)))).append("\r\n")
            append("END:VEVENT\r\n")
        }
        append("END:VCALENDAR\r\n")
    }

    // ── CSV ──

    private val plainNumber = Regex("-?\\d+(\\.\\d+)?")

    /**
     * One CSV field: quoted when needed; a leading =,+,-,@ is neutralised so spreadsheets never run
     * it as a formula. A plain number such as -12.00 stays a number.
     */
    fun csvField(s: String): String {
        val safe = if (s.isNotEmpty() && s[0] in "=+-@\t\r" && !plainNumber.matches(s)) "'$s" else s
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' || it == ';' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    /** A table with a header row; UTF-8 with a byte-order mark so spreadsheet apps read Cyrillic right. */
    fun csv(header: List<String>, rows: List<List<String>>): String = buildString {
        append('﻿')
        append(header.joinToString(",") { csvField(it) }).append("\r\n")
        rows.forEach { r -> append(r.joinToString(",") { csvField(it) }).append("\r\n") }
    }

    fun day(d: Long?): String = d?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty()
    fun moment(ms: Long?): String = ms?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()).withNano(0).toString().replace('T', ' ') }.orEmpty()
    fun money(minor: Long): String = (if (minor < 0) "-" else "") + (kotlin.math.abs(minor) / 100).toString() + "." + "%02d".format(kotlin.math.abs(minor) % 100)

    // ── Markdown ──

    /** Escapes characters that would change the meaning of Markdown, including links, images and HTML. */
    fun md(s: String): String {
        val out = StringBuilder(s.length + 8)
        for (ch in s) {
            if (ch in MD_SPECIAL) out.append('\\')
            out.append(ch)
        }
        // A line that would start a heading underline, a list or a quote stays plain text
        return out.toString().lineSequence().joinToString("\n") { l ->
            if (l.isNotEmpty() && (l[0] in "-+=" || Regex("^\\d+[.)]").containsMatchIn(l))) "\\$l" else l
        }
    }

    private const val MD_SPECIAL = "\\*_#`<>[]!"
}

/**
 * Reads events from an .ics file: title, day and time (turned into this phone's time), place,
 * notes. Recurring rules are not expanded: only the first time is taken.
 */
object IcsReader {
    data class Item(val title: String, val day: Long, val endDay: Long?, val startMin: Int?, val endMin: Int?, val place: String, val notes: String)

    const val MAX_EVENTS = 5_000

    fun read(text: String, zone: ZoneId = ZoneId.systemDefault()): List<Item> {
        val out = ArrayList<Item>()
        var cur: MutableMap<String, Pair<String, String>>? = null
        for (l in unfold(text)) {
            if (out.size >= MAX_EVENTS) break
            when {
                l.equals("BEGIN:VEVENT", true) -> cur = HashMap()
                l.equals("END:VEVENT", true) -> { cur?.let { toItem(it, zone)?.let(out::add) }; cur = null }
                cur != null && ':' in l -> {
                    val head = l.substringBefore(':')
                    val name = head.substringBefore(';').uppercase()
                    if (name !in cur) cur[name] = head to l.substringAfter(':')
                }
            }
        }
        return out
    }

    /** Longest logical line kept; anything beyond is dropped, so a hostile file cannot grow one line forever. */
    private const val MAX_LINE = 100_000

    /** Joins continuation lines (starting with a space or tab) in one pass, in linear time. */
    internal fun unfold(text: String): Sequence<String> = sequence {
        val cur = StringBuilder()
        var has = false
        for (raw in text.lineSequence()) {
            val l = raw.removeSuffix("\r")
            if ((l.startsWith(" ") || l.startsWith("\t")) && has) {
                if (cur.length < MAX_LINE) cur.append(l, 1, minOf(l.length, 1 + MAX_LINE - cur.length))
            } else {
                if (has) yield(cur.toString())
                cur.setLength(0)
                cur.append(l, 0, minOf(l.length, MAX_LINE))
                has = true
            }
        }
        if (has) yield(cur.toString())
    }

    private fun unescape(s: String) = VCard.unescape(s).take(MAX_NOTE_LENGTH)

    /** A date or date-time with its parameters: the calendar day and minutes after midnight (null for a whole day). */
    private fun when_(head: String, value: String, zone: ZoneId): Pair<Long, Int?>? = try {
        val v = value.trim()
        if (v.length == 8 || head.contains("VALUE=DATE", true) && !v.contains('T')) {
            LocalDate.parse(v.take(8), DateTimeFormatter.BASIC_ISO_DATE).toEpochDay() to null
        } else {
            val local = LocalDateTime.parse(v.take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            val tz = Regex("TZID=([^;:]+)").find(head)?.groupValues?.get(1)?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            val at = when {
                v.endsWith("Z") -> local.atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDateTime()
                tz != null -> local.atZone(tz).withZoneSameInstant(zone).toLocalDateTime()
                else -> local
            }
            at.toLocalDate().toEpochDay() to (at.hour * 60 + at.minute)
        }
    } catch (e: Exception) {
        null
    }

    private fun toItem(p: Map<String, Pair<String, String>>, zone: ZoneId): Item? {
        val (sh, sv) = p["DTSTART"] ?: return null
        val start = when_(sh, sv, zone) ?: return null
        val end = p["DTEND"]?.let { (h, v) -> when_(h, v, zone) }
        val title = unescape(p["SUMMARY"]?.second.orEmpty()).trim().take(500).ifEmpty { "—" }
        val (endDay, endMin) = when {
            end == null -> null to null
            start.second == null -> (end.first - 1).takeIf { it > start.first } to null // DTEND of a date is exclusive
            else -> end.first.takeIf { it > start.first } to end.second
        }
        return Item(title, start.first, endDay, start.second, if (start.second == null) null else endMin,
            unescape(p["LOCATION"]?.second.orEmpty()).take(500), unescape(p["DESCRIPTION"]?.second.orEmpty()))
    }
}

/** Reads a CSV of contacts (comma or semicolon), recognising common header names in Ukrainian and English. */
object ContactCsv {
    fun rows(text: String): List<List<String>> {
        val t = text.removePrefix("﻿")
        val firstLine = t.lineSequence().firstOrNull().orEmpty()
        val sep = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
        val out = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < t.length) {
            val ch = t[i]
            when {
                quoted && ch == '"' && i + 1 < t.length && t[i + 1] == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == sep -> { if (row.size < MAX_COLUMNS) row += cell.toString(); cell.setLength(0) }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && i + 1 < t.length && t[i + 1] == '\n') i++
                    row += cell.toString(); cell.setLength(0)
                    if (row.any { it.isNotBlank() }) out += row
                    row = ArrayList()
                    if (out.size > VCard.MAX_CONTACTS) break
                }
                else -> if (cell.length < 5_000) cell.append(ch)
            }
            i++
        }
        row += cell.toString()
        if (row.any { it.isNotBlank() }) out += row
        return out
    }

    /** More columns than any real contact table has; the rest of a row is ignored. */
    const val MAX_COLUMNS = 200

    private val NAME = listOf("name", "full name", "ім'я", "імʼя", "піб", "прізвище та ім'я", "назва")
    private val PHONE = listOf("phone", "mobile", "tel", "телефон", "мобільний", "номер")
    private val EMAIL = listOf("email", "e-mail", "mail", "пошта", "електронна пошта")
    private val ORG = listOf("organization", "organisation", "company", "організація", "компанія", "установа")
    private val TITLE = listOf("title", "job title", "position", "посада")
    private val NOTE = listOf("note", "notes", "примітка", "нотатки", "коментар")
    private val GROUP = listOf("group", "label", "category", "група", "категорія", "список")

    fun contacts(text: String, now: Long = System.currentTimeMillis()): List<Contact> {
        val all = rows(text)
        if (all.size < 2) return emptyList()
        val header = all.first().map { it.trim().lowercase() }
        fun col(names: List<String>) = header.indexOfFirst { h -> names.any { h == it || h.startsWith("$it ") } }
        val iName = col(NAME).takeIf { it >= 0 } ?: 0
        val iPhones = header.indices.filter { h -> PHONE.any { header[h] == it || header[h].startsWith("$it ") || header[h].startsWith(it) } }
        val iEmails = header.indices.filter { h -> EMAIL.any { header[h] == it || header[h].startsWith(it) } }
        val iOrg = col(ORG); val iTitle = col(TITLE); val iNote = col(NOTE); val iGroup = col(GROUP)
        fun at(r: List<String>, i: Int) = if (i in r.indices) r[i].trim() else ""
        return all.drop(1).mapNotNull { r ->
            cleanContact(
                Contact(
                    name = at(r, iName), phones = iPhones.map { at(r, it) }.filter { it.isNotEmpty() }.joinToString("\n"),
                    emails = iEmails.map { at(r, it) }.filter { it.isNotEmpty() }.joinToString("\n"),
                    organization = at(r, iOrg), jobTitle = at(r, iTitle), notes = at(r, iNote), label = at(r, iGroup),
                    sortKey = "", createdAt = now,
                ),
            )
        }.take(VCard.MAX_CONTACTS)
    }
}

/** Splits a text file into Inbox notes at blank lines (at most [max]). */
fun notesFromText(text: String, max: Int = 500): List<String> =
    text.removePrefix("﻿").replace("\r\n", "\n").split(Regex("\n\\s*\n")).mapNotNull { cleanInboxText(it) }.take(max)
