package ua.mandat.data


import org.json.JSONObject

/**
 * The demo: an invented MP's records, shipped with the app (nothing personal in it), opened in an
 * encrypted database that lives only in memory, never next to the owner's data. Dates are moved
 * so that the demo's "today" is the real today.
 */
object DemoData {
    const val ASSET = "demo.bin" // gzip inside; a neutral name, so no build tool treats it specially

    /** Columns holding a calendar day, and columns holding a moment in milliseconds. */
    private val DAY_COLUMNS = setOf("day", "due", "endDay", "checkIn", "lastContact", "stanceSince", "untilDay")
    private val MS_COLUMNS = setOf("at", "closedAt", "createdAt", "updatedAt", "savedAt")
    /** Settings that hold a calendar day. */
    private val DAY_SETTINGS = setOf(SETTING_CADENCE_START)
    private const val DAY_MS = 86_400_000L

    /** Reads the gzipped demo document and moves every date by the days since it was made. */
    fun prepare(gz: ByteArray, today: Long): ByteArray {
        val json = java.util.zip.GZIPInputStream(gz.inputStream()).use { it.readBytes() }
        return shift(json, today)
    }

    /** Moves all day and moment columns (and day settings) so the document's "today" becomes [today]. */
    fun shift(json: ByteArray, today: Long): ByteArray {
        val root = JSONObject(String(json, Charsets.UTF_8))
        val offset = today - root.optLong("today", today)
        if (offset == 0L) return json
        val tables = root.getJSONObject("tables")
        tables.keys().forEach { name ->
            val t = tables.getJSONObject(name)
            val columns = t.getJSONArray("columns")
            val rows = t.getJSONArray("rows")
            val days = (0 until columns.length()).filter { columns.getString(it) in DAY_COLUMNS }
            val moments = (0 until columns.length()).filter { columns.getString(it) in MS_COLUMNS }
            val keyCol = if (name == "settings") (0 until columns.length()).firstOrNull { columns.getString(it) == "key" } else null
            val valueCol = if (name == "settings") (0 until columns.length()).firstOrNull { columns.getString(it) == "value" } else null
            for (r in 0 until rows.length()) {
                val row = rows.getJSONArray(r)
                days.forEach { i -> if (!row.isNull(i)) row.put(i, row.getLong(i) + offset) }
                moments.forEach { i -> if (!row.isNull(i)) row.put(i, row.getLong(i) + offset * DAY_MS) }
                if (keyCol != null && valueCol != null && row.optString(keyCol) in DAY_SETTINGS) {
                    row.optString(valueCol).toLongOrNull()?.let { row.put(valueCol, (it + offset).toString()) }
                }
            }
        }
        root.put("today", today)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

}
