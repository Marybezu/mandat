package ua.mandat.data

/**
 * Helpers for an export that Obsidian (or any Markdown notes app) opens as a vault: one note per
 * matter, person and event, linked with [[wikilinks]], so the links between them show as a graph.
 */
object Obsidian {
    /** Characters a file name or a [[link]] cannot hold, on any system or in Obsidian. */
    private val BAD = Regex("[\\\\/:*?\"<>|#^\\[\\]\\n\\r\\t]")

    /** A safe note name: forbidden characters become spaces, at most 80 characters, never empty. */
    fun name(s: String, fallback: String): String {
        val clean = s.replace(BAD, " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').take(80).trim()
        return clean.ifEmpty { fallback }
    }

    /** Gives every key a unique note name: a repeated name gets " (2)", " (3)"… */
    fun <K> unique(names: List<Pair<K, String>>): Map<K, String> {
        val used = HashMap<String, Int>()
        return names.associate { (k, n) ->
            val c = (used[n.lowercase()] ?: 0) + 1
            used[n.lowercase()] = c
            k to if (c == 1) n else "$n ($c)"
        }
    }

    /** A YAML front matter value, quoted so that colons, quotes and leading signs are safe. */
    fun yaml(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    fun link(name: String) = "[[$name]]"
}
