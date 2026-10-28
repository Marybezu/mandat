package ua.mandat.data

/**
 * Helpers for an export that Obsidian (or any Markdown notes app) opens as a vault: one note per
 * matter, person and event, linked with [[wikilinks]], so the links between them show as a graph.
 */
object Obsidian {
    /** Characters a file name or a [[link]] cannot hold, on any system or in Obsidian. */
    private val BAD = Regex("[\\\\/:*?\"<>|#^\\[\\]\\p{Cntrl}]")

    /** A safe note name: forbidden characters become spaces, at most 80 characters, never empty. */
    fun name(s: String, fallback: String): String {
        // No leading dots (Obsidian hides such files), no trailing ones; cut by whole characters, never inside an emoji
        val spaced = s.replace(BAD, " ").replace(Regex("\\s+"), " ").trim().trimStart('.').trimEnd('.').trim()
        val cut = if (spaced.codePointCount(0, spaced.length) > 80) spaced.substring(0, spaced.offsetByCodePoints(0, 80)) else spaced
        return cut.trim().trimEnd('.').trim().ifEmpty { fallback }
    }

    /** Gives every key a unique note name: a repeated name gets " (2)", " (3)"… */
    fun <K> unique(names: List<Pair<K, String>>): Map<K, String> {
        // Every name given out is taken, the numbered ones too, so "A", "A", "A (2)" never repeat
        val taken = HashSet<String>()
        return names.associate { (k, n) ->
            var candidate = n
            var c = 1
            while (!taken.add(candidate.lowercase())) { c++; candidate = "$n ($c)" }
            k to candidate
        }
    }

    /** A YAML front matter value, quoted so that colons, quotes and leading signs are safe. */
    fun yaml(s: String): String = "\"" + s.replace(Regex("\\p{Cntrl}"), " ").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun link(name: String) = "[[$name]]"

    private val TILDE_FENCE = Regex("(?m)^([ \\t]*)~~~")

    /**
     * Makes a note's text inert before it reaches Obsidian: text that came from elsewhere (a shared
     * note, a calendar file, a colleague's packet) could hold a picture loaded from the internet
     * when the vault opens, HTML, or a code block that a plugin such as Dataview would run. Mandat's
     * own markup uses none of these, so the whole body is treated; the front matter is quoted already.
     */
    fun neutralize(note: String): String {
        val bodyStart = if (note.startsWith("---\n")) note.indexOf("\n---\n", 3).let { if (it < 0) 0 else it + 5 } else 0
        val body = note.substring(bodyStart)
            .replace("`", "\\`")
            .replace("<", "&lt;")
            .replace("![", "!\\[")
            .replace(TILDE_FENCE, "$1\\\\~~~")
        return note.substring(0, bodyStart) + body
    }
}
