package ua.mandat.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Recognises a hidden matter's title in free text, also in other word forms: each word of the
 * title is cut to its stem ("ремонт школи" → "ремо", "школ"), and the text names the matter when
 * every stem starts one of its words ("щодо ремонту школи"). Leaving out a little too much is
 * the safe side while something is hidden.
 */
class HiddenNames(titles: Collection<String>) {
    private val stems: List<List<String>> = titles.map(::stemsOf).filter { it.isNotEmpty() }

    val isEmpty: Boolean get() = stems.isEmpty()

    /** True when [text] names one of the hidden titles. */
    fun named(text: String): Boolean {
        if (stems.isEmpty() || text.isBlank()) return false
        val words = wordsOf(text)
        return stems.any { title -> title.all { s -> words.any { w ->
            when {
                s.all(Char::isDigit) -> w == s
                // A short stem ("пар" from "Парк") matches only words of up to six letters ("парку", "парків";
                // also "партія": hiding a little too much is the safe side)
                s.length == 3 -> w.startsWith(s) && w.length <= 6
                else -> w.startsWith(s)
            }
        } } }
    }

    /** True when any of [texts] names a hidden title. */
    fun namedAny(vararg texts: String): Boolean = texts.any(::named)

    companion object {
        val NONE = HiddenNames(emptyList())
        private val WORD = Regex("[\\p{L}\\p{N}]+")

        fun wordsOf(text: String): List<String> = WORD.findAll(text.lowercase()).map { it.value }.toList()

        /** Words of three letters or more, cut to a stem; numbers (any length) stay whole and must match exactly. */
        fun stemsOf(title: String): List<String> = wordsOf(title).filter { (it.length >= 3 && it !in SMALL) || it.all(Char::isDigit) }.map { w ->
            when {
                w.all(Char::isDigit) || w.length == 3 -> w
                w.length == 4 -> w.take(3) // "вода" → "вод": also "води", "воді"
                else -> w.take(maxOf(4, w.length - 2))
            }
        }.distinct()

        /** Short words that say nothing about a matter and are often left out when it is named. */
        private val SMALL = setOf("для", "про", "від", "під", "при", "між", "над", "без", "або", "щодо", "and", "the", "for")
    }
}

/** A pair of ids read for working out what is hidden. */
data class IdRef(val a: Long, val b: Long?)

/** What is hidden besides the matters themselves: worked out from the links only. */
object Hiding {
    /** People every one of whose links (matters, tasks, events, media, alliances) leads to a hidden matter. */
    fun people(hidden: Set<Long>, personMatter: List<IdRef>): Set<Long> =
        if (hidden.isEmpty()) emptySet()
        else personMatter.groupBy({ it.a }, { it.b }).filterValues { ms -> ms.all { it != null && it in hidden } }.keys

    /** Diary days linked to a hidden matter or a hidden person, or whose text names a hidden matter. */
    fun reviews(hidden: Set<Long>, people: Set<Long>, names: HiddenNames, reviews: List<Review>, links: List<ReviewLink>, since: Long = Long.MAX_VALUE): Set<Long> {
        if (hidden.isEmpty()) return emptySet()
        val out = HashSet<Long>()
        links.forEach { l ->
            if ((l.kind == "matter" && l.refId in hidden) || (l.kind == "person" && l.refId in people)) out += l.reviewId
        }
        if (!names.isEmpty) reviews.forEach { r -> if (r.id !in out && r.createdAt < since && names.named(r.progress)) out += r.id }
        return out
    }

    /** Communities every one of whose links leads to something hidden (the name could tell where). */
    fun communities(hidden: Set<Long>, people: Set<Long>, links: List<CommunityLink>, eventMatter: Map<Long, Long?>, promiseMatter: Map<Long, Long?>): Set<Long> {
        if (hidden.isEmpty()) return emptySet()
        fun linkHidden(l: CommunityLink) = when (l.kind) {
            LinkKind.MATTER -> l.refId in hidden
            LinkKind.EVENT -> eventMatter[l.refId]?.let { it in hidden } ?: false
            LinkKind.PROMISE -> promiseMatter[l.refId]?.let { it in hidden } ?: false
            LinkKind.PERSON -> l.refId in people
            else -> false
        }
        return links.groupBy { it.communityId }.filterValues { ls -> ls.isNotEmpty() && ls.all(::linkHidden) }.keys
    }
}

@Dao
interface HidingDao {
    /** Every person–matter tie (matter may be null: a task or event without a matter). */
    @Query(
        "SELECT personId AS a, matterId AS b FROM matter_people " +
            "UNION ALL SELECT personId, matterId FROM delegations WHERE personId IS NOT NULL " +
            "UNION ALL SELECT personId, matterId FROM promises WHERE personId IS NOT NULL " +
            "UNION ALL SELECT ep.personId, e.matterId FROM event_people ep LEFT JOIN events e ON e.id = ep.eventId " +
            "UNION ALL SELECT personId, matterId FROM alliances WHERE personId IS NOT NULL " +
            "UNION ALL SELECT personId, matterId FROM info_items WHERE personId IS NOT NULL",
    )
    fun personMatter(): Flow<List<IdRef>>

    @Query("SELECT id AS a, matterId AS b FROM events")
    fun eventMatter(): Flow<List<IdRef>>

    @Query("SELECT id AS a, matterId AS b FROM promises")
    fun promiseMatter(): Flow<List<IdRef>>
}
