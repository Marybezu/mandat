package ua.mandat.data

/** Where the day's main things are kept (in the encrypted settings). */
const val SETTING_DAY_MAIN = "day_main"

/** At most this many: one to three main things a day is what attention can carry. */
const val DAY_MAIN_LIMIT = 3

/**
 * The day's one to three main things, chosen by the owner: references to records ("deadline:12",
 * "matter:3") and which of them were ticked as done for today. Kept for one day only: on another
 * day the list starts empty.
 */
data class DayMain(val day: Long, val items: List<String>, val done: Set<String>) {
    fun encode(): String = day.toString() + ";" + items.joinToString(",") + ";" + done.joinToString(",")

    companion object {
        private val REF = Regex("^(matter|deadline|delegation|promise):\\d{1,18}$")

        fun decode(raw: String?, today: Long): DayMain {
            val parts = raw?.split(';').orEmpty()
            val day = parts.getOrNull(0)?.toLongOrNull()
            if (day != today) return DayMain(today, emptyList(), emptySet())
            val items = parts.getOrNull(1).orEmpty().split(',').filter { REF.matches(it) }.distinct().take(DAY_MAIN_LIMIT)
            val done = parts.getOrNull(2).orEmpty().split(',').filter { it in items }.toSet()
            return DayMain(today, items, done)
        }
    }
}
