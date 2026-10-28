package ua.mandat.data

/**
 * The words that came up most in a set of texts, for "what the work was about". Short words and
 * common function words are left out; forms of one word (by their first letters) count together
 * and are shown in their most frequent form. Counted on the phone, without any AI.
 */
object Words {
    private val WORD = Regex("[\\p{L}'’ʼ-]{4,}")

    /** Function words and words every record has; Ukrainian and English. */
    private val STOP = setOf(
        "щодо", "який", "яка", "яке", "які", "якого", "якої", "яких", "цього", "цієї", "цей", "ця", "це", "того", "тому", "також", "після", "перед",
        "між", "через", "може", "можна", "треба", "буде", "були", "було", "була", "бути", "немає", "вони", "його", "їхні", "наші", "наша", "наше",
        "свою", "своє", "свої", "своїх", "дуже", "лише", "тільки", "якщо", "коли", "тоді", "зараз", "сьогодні", "завтра", "вчора", "день", "дня",
        "тиждень", "тижня", "року", "році", "рік", "місяць", "місяця", "про", "для", "під", "над", "або", "чи", "але", "та", "щоб", "весь", "всіх",
        "усіх", "інші", "інших", "інша", "інше", "питання", "питань", "справа", "справи", "справ", "зустріч", "зустрічі", "з'ясувати", "обговорити",
        "підготувати", "зробити", "написати", "надіслати", "провести", "взяти", "участь", "тестова", "тестовий", "тестове", "with", "from", "this",
        "that", "about", "have", "will", "were", "been", "their", "there", "which", "into",
    )

    fun top(texts: List<String>, limit: Int = 30): List<Pair<String, Int>> {
        val forms = HashMap<String, HashMap<String, Int>>()
        texts.forEach { t ->
            WORD.findAll(t.lowercase()).forEach { m ->
                val w = m.value.trim('-', '\'', '’', 'ʼ')
                if (w.length >= 4 && w !in STOP && w.none { it.isDigit() }) {
                    val stem = w.take(if (w.length > 7) 6 else 5)
                    val f = forms.getOrPut(stem) { HashMap() }
                    f[w] = (f[w] ?: 0) + 1
                }
            }
        }
        return forms.values.map { f -> f.maxByOrNull { it.value }!!.key to f.values.sum() }
            .filter { it.second > 1 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(limit)
    }

    /** The stems of a title's meaningful words. */
    private fun stems(t: String): Set<String> =
        WORD.findAll(t.lowercase()).map { it.value.trim('-', '\'', '’', 'ʼ') }.filter { it.length >= 4 && it !in STOP }
            .map { it.take(if (it.length > 7) 6 else 5) }.toSet()

    /**
     * Matters whose titles share most of their meaningful words with [title]: at least two words,
     * covering at least 60 % of the shorter title. For "this looks like a matter you already have".
     */
    fun similar(title: String, others: List<MatterTitle>, limit: Int = 2): List<MatterTitle> {
        val a = stems(title)
        if (a.size < 2) return emptyList()
        return others.map { it to stems(it.title) }
            .map { (m, b) -> Triple(m, a.intersect(b).size, minOf(a.size, b.size)) }
            .filter { (_, common, shorter) -> common >= 2 && shorter > 0 && common * 10 >= shorter * 6 }
            .sortedByDescending { it.second }
            .take(limit).map { it.first }
    }
}
