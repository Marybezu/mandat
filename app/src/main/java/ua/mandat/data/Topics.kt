package ua.mandat.data

/**
 * Topics that run through everything (heating, roads, shelters): a name and a few word stems.
 * Anything whose text contains one of the stems belongs to the topic, in every section.
 * Kept in the encrypted settings; suggested from frequent words, added only by the owner.
 */
data class Topic(val name: String, val keys: List<String>)

const val SETTING_TOPICS = "topics"

fun encodeTopics(list: List<Topic>): String =
    list.joinToString("\n") { t -> t.name.replace('\n', ' ').replace('\t', ' ') + "\t" + t.keys.joinToString(",") }

fun decodeTopics(raw: String?): List<Topic> =
    raw.orEmpty().lines().mapNotNull { line ->
        val parts = line.split('\t')
        val name = parts.getOrNull(0)?.trim().orEmpty()
        if (name.isEmpty()) null
        else Topic(name, parts.getOrNull(1).orEmpty().split(',').map { it.trim().lowercase() }.filter { it.length >= 3 }.ifEmpty { keysFor(name) })
    }

/** Stems from the words the owner typed (a name or a comma-separated list). */
fun keysFor(text: String): List<String> =
    text.split(',').flatMap { words(it) }.filter { it.length >= 3 }.map(::stem).distinct()

fun matches(text: String, topic: Topic): Boolean {
    val ws = words(text)
    return topic.keys.any { k -> ws.any { it.startsWith(k) } }
}

private val STOP = setOf(
    "справ", "тестов", "наступн", "крок", "щодо", "через", "після", "перед", "також", "можна", "треба", "потрібн",
    "доруч", "обіцянк", "строк", "зустріч", "лист", "відповід", "питанн", "робот", "підготуват", "зробит", "питан",
    "україн", "депутат", "запис", "вхідн", "мешканц", "пояснен", "громадян", "засідан", "комітет", "сьогодн", "завтра", "тижн", "місяц", "року", "рік",
)

/**
 * Frequent words that could become topics: stems that appear in at least [minTexts] texts and
 * are not yet a topic, with the most common written form for display. Most frequent first.
 */
fun suggestTopics(texts: List<String>, existing: List<Topic>, minTexts: Int = 3, limit: Int = 8): List<Pair<String, Int>> {
    val taken = existing.flatMap { it.keys }.toSet()
    val counts = mutableMapOf<String, Int>()
    val forms = mutableMapOf<String, MutableMap<String, Int>>()
    for (text in texts) {
        val seen = mutableSetOf<String>()
        for (w in words(text)) {
            if (w.length < 5 || w.any { it.isDigit() }) continue
            val s = stem(w)
            if (s.length < 4 || STOP.any { s.startsWith(it) } || taken.any { s.startsWith(it) || it.startsWith(s) }) continue
            forms.getOrPut(s) { mutableMapOf() }.merge(w, 1, Int::plus)
            if (seen.add(s)) counts.merge(s, 1, Int::plus)
        }
    }
    return counts.filter { it.value >= minTexts }.entries.sortedByDescending { it.value }.take(limit)
        .map { (s, n) -> (forms[s]?.maxByOrNull { it.value }?.key ?: s) to n }
}
