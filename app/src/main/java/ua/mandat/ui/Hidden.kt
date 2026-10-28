package ua.mandat.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import ua.mandat.data.Hiding
import ua.mandat.data.HiddenNames
import ua.mandat.data.MandatDb

/**
 * Everything kept off the screen while something is hidden (trip mode, lens), worked out once
 * and used by every screen: the matters, the people known only through them, the diary days
 * linked to them or naming them, the communities tied only to them, and their titles for
 * recognising free text that names them.
 */
class Hidden(
    val matters: Set<Long>,
    val people: Set<Long>,
    val reviews: Set<Long>,
    val communities: Set<Long>,
    val names: HiddenNames,
    /** When trip mode was turned on: what was written or changed since then is never hidden by its words alone. */
    val since: Long = Long.MAX_VALUE,
) {
    val isEmpty: Boolean get() = matters.isEmpty()

    /** True for an item tied to a hidden matter. */
    fun matter(id: Long?): Boolean = id != null && id in matters

    /** True for an item tied to a hidden matter, or not tied to one but naming it in its text. */
    fun item(matterId: Long?, vararg text: String): Boolean = matter(matterId) || (matterId == null && names.namedAny(*text))

    /** The same, for a record written or last changed at [at]: one written in trip mode stays in view. */
    fun itemAt(matterId: Long?, at: Long, vararg text: String): Boolean = matter(matterId) || (matterId == null && at < since && names.namedAny(*text))

    /** Free text written at [at] names a hidden matter (and was written before trip mode was turned on). */
    fun named(at: Long, text: String): Boolean = at < since && names.named(text)

    companion object {
        val NONE = Hidden(emptySet(), emptySet(), emptySet(), emptySet(), HiddenNames.NONE)
    }
}

/**
 * Follows the database: what is hidden along with [matters]. Free text is checked for the titles of
 * [byName] only (the matters trip mode hides): the lens leaves records without a matter in view.
 */
fun observeHidden(db: MandatDb, matters: Set<Long>, byName: Set<Long> = matters, since: Long = Long.MAX_VALUE): Flow<Hidden> {
    if (matters.isEmpty()) return flowOf(Hidden.NONE)
    val h = db.hiding()
    val sources: List<Flow<List<Any>>> = listOf(
        db.matters().observeAllTitles(), h.personMatter(), db.reviews().observeAll(), db.reviews().observeLinks(),
        db.communities().observeLinks(), h.eventMatter(), h.promiseMatter(),
    )
    return combine(sources) { arr ->
        @Suppress("UNCHECKED_CAST") val titles = arr[0] as List<ua.mandat.data.MatterTitle>
        @Suppress("UNCHECKED_CAST") val personMatter = arr[1] as List<ua.mandat.data.IdRef>
        @Suppress("UNCHECKED_CAST") val reviews = arr[2] as List<ua.mandat.data.Review>
        @Suppress("UNCHECKED_CAST") val reviewLinks = arr[3] as List<ua.mandat.data.ReviewLink>
        @Suppress("UNCHECKED_CAST") val commLinks = arr[4] as List<ua.mandat.data.CommunityLink>
        @Suppress("UNCHECKED_CAST") val eventMatter = (arr[5] as List<ua.mandat.data.IdRef>).associate { it.a to it.b }
        @Suppress("UNCHECKED_CAST") val promiseMatter = (arr[6] as List<ua.mandat.data.IdRef>).associate { it.a to it.b }
        val names = HiddenNames(titles.filter { it.id in byName }.map { it.title })
        val people = Hiding.people(matters, personMatter)
        Hidden(
            matters = matters,
            people = people,
            reviews = Hiding.reviews(matters, people, names, reviews, reviewLinks, since),
            communities = Hiding.communities(matters, people, commLinks, eventMatter, promiseMatter),
            names = names,
            since = since,
        )
    }.flowOn(kotlinx.coroutines.Dispatchers.Default) // worked out away from the screen thread, also with years of records
}

/** Search rows that may be shown now: nothing hidden and nothing tied to it or naming it. */
fun visibleRows(rows: List<ua.mandat.data.SearchRow>, h: Hidden): List<ua.mandat.data.SearchRow> =
    if (h.isEmpty) rows else rows.filter { r ->
        when (r.kind) {
            "person" -> r.id !in h.people && !h.named(r.at, r.haystack)
            "review" -> r.id !in h.reviews
            "community" -> r.id !in h.communities && !h.named(r.at, r.haystack)
            "contact" -> !h.named(r.at, r.haystack)
            else -> !h.itemAt(r.matterId, r.at, r.haystack)
        }
    }

/** The words of a person's card that could name a matter. */
fun personText(p: ua.mandat.data.Person): String = p.name + " " + p.organization + " " + p.jobTitle + " " + p.contact + " " + p.notes

/** Hidden now: known only through hidden matters, or the card names one (written before trip mode). */
fun Hidden.person(p: ua.mandat.data.Person): Boolean = p.id in people || named(p.updatedAt, personText(p))

/** Hidden now: tied only to hidden things, or its card names a hidden matter. */
fun Hidden.community(c: ua.mandat.data.Community): Boolean = c.id in communities || named(c.updatedAt, c.name + " " + c.head + " " + c.notes)

/** Leaves out people known only through hidden matters, or whose card names one. */
fun Flow<List<ua.mandat.data.Person>>.hidingPeople(session: Session?): Flow<List<ua.mandat.data.Person>> =
    if (session == null) this
    else combine(session.hidden) { list, h -> if (h.isEmpty) list else list.filter { !h.person(it) } }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)

/** Leaves out diary days tied to hidden matters or naming them. */
fun Flow<List<ua.mandat.data.Review>>.hidingReviews(session: Session?): Flow<List<ua.mandat.data.Review>> =
    if (session == null) this
    else combine(session.hidden) { list, h -> if (h.reviews.isEmpty()) list else list.filter { it.id !in h.reviews } }

/** Leaves out communities tied only to hidden things, or whose card names a hidden matter. */
fun Flow<List<ua.mandat.data.Community>>.hidingCommunities(session: Session?): Flow<List<ua.mandat.data.Community>> =
    if (session == null) this
    else combine(session.hidden) { list, h -> if (h.isEmpty) list else list.filter { !h.community(it) } }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)

/** Leaves out free text (not tied to a matter) that names a hidden matter. */
fun <T> Flow<List<T>>.hidingNamed(session: Session?, matterOf: (T) -> Long?, at: ((T) -> Long)? = null, text: (T) -> String): Flow<List<T>> =
    if (session == null) this
    else combine(session.hidden) { list, h ->
        if (h.isEmpty) list else list.filter { !h.itemAt(matterOf(it), at?.invoke(it) ?: 0L, text(it)) }
    }
        .flowOn(kotlinx.coroutines.Dispatchers.Default) // matching titles in many texts, away from the screen thread
