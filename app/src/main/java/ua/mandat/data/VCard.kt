package ua.mandat.data

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Reads a .vcf file (vCard 2.1, 3.0 and 4.0) line by line, so files with thousands of contacts
 * and photos do not fill the memory. Photos, sounds and keys are skipped without being kept.
 * Only the basic fields are taken: name, phones, e-mails, organisation, title, note, groups.
 */
object VCard {
    /** At most this many contacts are read from one file. */
    const val MAX_CONTACTS = 20_000
    private const val MAX_VALUE = 10_000
    private val SKIPPED = setOf("PHOTO", "LOGO", "SOUND", "KEY", "X-ANDROID-CUSTOM", "AGENT")

    class Result(val contacts: List<Contact>, val categories: Map<Int, List<String>>, val tooMany: Boolean)

    fun read(reader: BufferedReader, now: Long = System.currentTimeMillis()): Result {
        val out = ArrayList<Contact>()
        val categories = HashMap<Int, List<String>>()
        var card: Card? = null
        var tooMany = false

        fun handle(line: String) {
            val p = parse(line) ?: return
            when {
                p.name == "BEGIN" && p.value.equals("VCARD", true) -> card = Card()
                p.name == "END" && p.value.equals("VCARD", true) -> {
                    val c = card ?: return
                    card = null
                    val contact = c.toContact(now) ?: return
                    if (out.size >= MAX_CONTACTS) { tooMany = true; return }
                    if (c.categories.isNotEmpty()) categories[out.size] = c.categories
                    out += contact
                }
                else -> card?.add(p)
            }
        }

        // Unfolding: a line that starts with a space or tab continues the previous one (3.0, 4.0);
        // in quoted-printable a line that ends with "=" continues on the next (2.1)
        val current = StringBuilder()
        var skipping = false
        var qpOpen = false
        while (!tooMany) {
            val raw = reader.readLine() ?: break
            if (qpOpen) {
                if (!skipping && current.length < MAX_VALUE) {
                    current.setLength(current.length - 1) // the soft break "="
                    current.append(raw)
                }
                qpOpen = raw.endsWith("=")
                continue
            }
            if (raw.startsWith(" ") || raw.startsWith("\t")) {
                if (!skipping && current.isNotEmpty() && current.length < MAX_VALUE) current.append(raw, 1, raw.length)
                continue
            }
            if (current.isNotEmpty()) handle(current.toString())
            current.setLength(0)
            val name = raw.substringBefore(':').substringBefore(';').substringAfterLast('.').trim().uppercase()
            skipping = name in SKIPPED
            if (skipping) continue
            current.append(raw, 0, minOf(raw.length, MAX_VALUE))
            qpOpen = isQp(raw) && raw.endsWith("=")
        }
        if (current.isNotEmpty() && !tooMany) handle(current.toString())
        return Result(out, categories, tooMany)
    }

    private fun isQp(s: CharSequence): Boolean {
        val head = s.substring(0, minOf(s.length, s.indexOf(':').let { if (it < 0) s.length else it }))
        return head.contains("QUOTED-PRINTABLE", ignoreCase = true)
    }

    class Prop(val name: String, val params: Map<String, String>, val types: Set<String>, val value: String)

    /** One unfolded line: NAME;PARAMS:VALUE with the value decoded. */
    fun parse(line: String): Prop? {
        // The value starts at the first ":" outside quotes
        var quoted = false
        var colon = -1
        for (i in line.indices) {
            val ch = line[i]
            if (ch == '"') quoted = !quoted
            else if (ch == ':' && !quoted) { colon = i; break }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon).split(';')
        val name = head[0].substringAfterLast('.').trim().uppercase()
        val params = HashMap<String, String>()
        val types = HashSet<String>()
        for (part in head.drop(1)) {
            val k = part.substringBefore('=').trim().uppercase()
            val v = if ('=' in part) part.substringAfter('=').trim().trim('"') else ""
            if ('=' !in part) types += k // vCard 2.1: ";HOME;VOICE" or ";QUOTED-PRINTABLE"
            else if (k == "TYPE") v.split(',').forEach { types += it.trim().uppercase() }
            else params[k] = v
        }
        val raw = line.substring(colon + 1)
        val qp = params["ENCODING"].equals("QUOTED-PRINTABLE", true) || "QUOTED-PRINTABLE" in types
        val charset = params["CHARSET"]?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        val value = if (qp) decodeQp(raw, charset) else raw
        return Prop(name, params, types, value)
    }

    fun decodeQp(s: String, charset: Charset): String {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '=' && i + 2 < s.length) {
                val b = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (b != null) { bytes.write(b); i += 3; continue }
            }
            if (ch == '=' && i >= s.length - 2) { i++; continue } // a stray soft break at the end
            val enc = ch.toString().toByteArray(charset)
            bytes.write(enc, 0, enc.size)
            i++
        }
        return String(bytes.toByteArray(), charset)
    }

    /** vCard 3.0/4.0 escapes: \n, \, \; \\ */
    fun unescape(s: String): String {
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n', 'N' -> b.append('\n')
                    else -> b.append(n)
                }
                i += 2
            } else { b.append(ch); i++ }
        }
        return b.toString()
    }

    /** Splits a structured value at ";" that are not escaped. */
    fun components(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) { cur.append(ch).append(s[i + 1]); i += 2; continue }
            if (ch == ';') { out += unescape(cur.toString()); cur.setLength(0) } else cur.append(ch)
            i++
        }
        out += unescape(cur.toString())
        return out
    }

    private class Card {
        var fn = ""
        var n = ""
        val phones = ArrayList<String>()
        val emails = ArrayList<String>()
        var org = ""
        var title = ""
        var note = ""
        val categories = ArrayList<String>()

        fun add(p: Prop) {
            when (p.name) {
                "FN" -> fn = unescape(p.value).trim()
                "N" -> {
                    val c = components(p.value).map { it.trim() }
                    // Family;Given;Middle;Prefix;Suffix → "Given Middle Family"
                    n = listOfNotNull(c.getOrNull(1), c.getOrNull(2), c.getOrNull(0)).filter { it.isNotEmpty() }.joinToString(" ")
                }
                "TEL" -> p.value.removePrefix("tel:").trim().takeIf { it.isNotEmpty() }?.let { phones += it }
                "EMAIL" -> unescape(p.value).trim().takeIf { it.isNotEmpty() }?.let { emails += it }
                "ORG" -> org = components(p.value).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
                "TITLE" -> title = unescape(p.value).trim()
                "NOTE" -> note = unescape(p.value).trim()
                "CATEGORIES" -> p.value.split(',').map { unescape(it).trim() }.filter { it.isNotEmpty() && !it.equals("myContacts", true) && !it.equals("starred", true) }
                    .forEach { categories += it }
            }
        }

        fun toContact(now: Long): Contact? = cleanContact(
            Contact(
                name = fn.ifEmpty { n }, phones = phones.joinToString("\n"), emails = emails.joinToString("\n"),
                organization = org, jobTitle = title, notes = note, sortKey = "", createdAt = now,
            ),
        )
    }
}
