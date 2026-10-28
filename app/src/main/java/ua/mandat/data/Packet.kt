package ua.mandat.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * A packet: chosen matters with their people, delegations, promises, deadlines and events, to
 * hand to a colleague's Mandat (an assistant, a member of a working group). It travels inside
 * Mandat's own encrypted file and is taken in by adding to what is there: matching people and
 * matters are recognised, nothing already there is changed or deleted, and the same task or
 * event is never added twice. Private notes on people stay out.
 *
 * Every record carries its id in the sender's Mandat only to tie the records together inside
 * the packet; the receiver's ids are new.
 */
object Packet {
    const val FILE = "packet.json"
    const val FORMAT = 1
    private const val MAX_ITEMS = 20_000
    /** 1900-01-01 and 2200-01-01 as calendar days. */
    private const val MIN_DAY = -25_567L
    private const val MAX_DAY = 84_006L

    class P(
        val matters: List<Matter>, val people: List<Person>, val links: List<MatterPerson>,
        val delegations: List<Delegation>, val promises: List<Promise>, val deadlines: List<Deadline>,
        val events: List<Event>, val eventPeople: List<EventPerson>, val roleNames: Map<Long, String>,
    ) {
        val tasks get() = delegations.size + promises.size + deadlines.size
        val isEmpty get() = matters.isEmpty() && tasks == 0 && events.isEmpty()
    }

    // ── Writing ──

    fun encode(p: P): String = JSONObject().apply {
        put("format", FORMAT)
        put("roles", JSONObject().apply { p.roleNames.forEach { (id, n) -> put(id.toString(), n) } })
        put("matters", JSONArray().apply { p.matters.forEach { m -> put(JSONObject().apply {
            put("id", m.id); put("title", m.title); put("type", m.type); put("attention", m.attention); put("status", m.status)
            put("nextStep", m.nextStep); put("goal", m.goal); put("description", m.description); m.roleId?.let { put("role", it) }
        }) } })
        put("people", JSONArray().apply { p.people.forEach { x -> put(JSONObject().apply {
            put("id", x.id); put("name", x.name); put("organization", x.organization); put("jobTitle", x.jobTitle); put("contact", x.contact)
        }) } })
        put("links", JSONArray().apply { p.links.forEach { l -> put(JSONObject().apply {
            put("matter", l.matterId); put("person", l.personId); l.stance?.let { put("stance", it) }; l.raci?.let { put("raci", it) }
        }) } })
        put("delegations", JSONArray().apply { p.delegations.forEach { d -> put(JSONObject().apply {
            put("what", d.what); d.personId?.let { put("person", it) }; d.matterId?.let { put("matter", it) }; d.due?.let { put("due", it) }
            put("status", d.status); put("purpose", d.purpose); put("doneMeans", d.doneMeans)
        }) } })
        put("promises", JSONArray().apply { p.promises.forEach { x -> put(JSONObject().apply {
            put("what", x.what); x.personId?.let { put("person", it) }; put("toWhom", x.toWhom); put("publicity", x.level.name)
            x.matterId?.let { put("matter", it) }; x.due?.let { put("due", it) }; put("status", x.status); put("source", x.source)
        }) } })
        put("deadlines", JSONArray().apply { p.deadlines.forEach { d -> put(JSONObject().apply {
            put("title", d.title); put("day", d.day); put("kind", d.kind); put("basis", d.basis); d.matterId?.let { put("matter", it) }; put("status", d.status)
        }) } })
        put("events", JSONArray().apply { p.events.forEach { e -> put(JSONObject().apply {
            put("id", e.id); put("title", e.title); put("day", e.day); e.endDay?.let { put("endDay", it) }; e.startMin?.let { put("start", it) }
            e.endMin?.let { put("end", it) }; put("kind", e.kind); put("place", e.place); e.matterId?.let { put("matter", it) }
            put("notes", e.notes); put("outcome", e.outcome)
        }) } })
        put("eventPeople", JSONArray().apply { p.eventPeople.forEach { l -> put(JSONObject().apply { put("event", l.eventId); put("person", l.personId) }) } })
    }.toString()

    // ── Reading ──

    /** Reads a packet; null when it is not one or is damaged. Text lengths are cut as everywhere in Mandat. */
    fun decode(json: String): P? = runCatching {
        val o = JSONObject(json)
        if (o.optInt("format") != FORMAT) return null
        val now = System.currentTimeMillis()
        fun arr(k: String): List<JSONObject> = o.optJSONArray(k)?.let { a -> (0 until minOf(a.length(), MAX_ITEMS)).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        fun JSONObject.s(k: String, max: Int) = optString(k, "").trim().take(max)
        fun JSONObject.l(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
        /** A calendar day only within 1900–2200: anything else is dropped, so no screen can break on it. */
        fun JSONObject.d(k: String): Long? = l(k)?.takeIf { it in MIN_DAY..MAX_DAY }
        fun closed(status: String) = if (TaskStatus.of(status) == TaskStatus.OPEN) null else now
        fun JSONObject.i(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
        val roles = o.optJSONObject("roles")?.let { r -> r.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { it to r.optString(k).take(200) } }.toMap() }.orEmpty()
        P(
            matters = arr("matters").mapNotNull { m ->
                val id = m.l("id") ?: return@mapNotNull null
                Matter(id = id, title = m.s("title", 300), type = MatterType.of(m.optString("type")).name, roleId = m.l("role"),
                    attention = Attention.of(m.optString("attention")).name, nextStep = m.s("nextStep", 1000), goal = m.s("goal", 2000),
                    description = m.s("description", 20_000), status = MatterStatus.of(m.optString("status")).name, createdAt = now, updatedAt = now)
                    .takeIf { it.title.isNotBlank() }
            }.distinctBy { it.id },
            people = arr("people").mapNotNull { x ->
                val id = x.l("id") ?: return@mapNotNull null
                Person(id = id, name = x.s("name", 200), organization = x.s("organization", 300), jobTitle = x.s("jobTitle", 300),
                    contact = x.s("contact", 2000), notes = "", teamRole = null, createdAt = now, updatedAt = now).takeIf { it.name.isNotBlank() }
            }.distinctBy { it.id },
            links = arr("links").mapNotNull { l ->
                MatterPerson(matterId = l.l("matter") ?: return@mapNotNull null, personId = l.l("person") ?: return@mapNotNull null,
                    stance = l.optString("stance").takeIf { s -> Stance.of(s) != null }, note = "", raci = Raci.of(l.optString("raci"))?.name)
            },
            delegations = arr("delegations").mapNotNull { d ->
                Delegation(what = d.s("what", 2000), personId = d.l("person"), matterId = d.l("matter"), due = d.d("due"),
                    status = TaskStatus.of(d.optString("status")).name, createdAt = now, closedAt = closed(d.optString("status")),
                    purpose = d.s("purpose", 2000), doneMeans = d.s("doneMeans", 2000)).takeIf { it.what.isNotBlank() }
            },
            promises = arr("promises").mapNotNull { x ->
                val level = Publicity.of(x.optString("publicity"))
                Promise(what = x.s("what", 2000), personId = x.l("person"), toWhom = x.s("toWhom", 300), isPublic = level == Publicity.PUBLIC,
                    matterId = x.l("matter"), due = x.d("due"), status = TaskStatus.of(x.optString("status")).name, createdAt = now, closedAt = closed(x.optString("status")),
                    source = x.s("source", 1000), publicity = level.name).takeIf { it.what.isNotBlank() }
            },
            deadlines = arr("deadlines").mapNotNull { d ->
                Deadline(title = d.s("title", 500), day = d.d("day") ?: return@mapNotNull null, kind = DeadlineKind.of(d.optString("kind")).name,
                    basis = d.s("basis", 1000), matterId = d.l("matter"), remindBefore = 0, status = TaskStatus.of(d.optString("status")).name,
                    createdAt = now, closedAt = closed(d.optString("status"))).takeIf { it.title.isNotBlank() }
            },
            events = arr("events").mapNotNull { e ->
                Event(id = e.l("id") ?: return@mapNotNull null, title = e.s("title", 500), day = e.d("day") ?: return@mapNotNull null,
                    endDay = e.d("endDay")?.takeIf { end -> e.d("day")?.let { end >= it && end - it <= 366 } == true },
                    startMin = e.i("start")?.takeIf { it in 0..1439 }, endMin = e.i("end")?.takeIf { it in 0..1440 }, kind = EventKind.of(e.optString("kind")).name,
                    place = e.s("place", 300), matterId = e.l("matter"), notes = e.s("notes", 20_000), outcome = e.s("outcome", 20_000),
                    createdAt = now, updatedAt = now).takeIf { it.title.isNotBlank() }
            }.distinctBy { it.id },
            eventPeople = arr("eventPeople").mapNotNull { l -> EventPerson(l.l("event") ?: return@mapNotNull null, l.l("person") ?: return@mapNotNull null) },
            roleNames = roles,
        )
    }.getOrNull()

    // ── Matching ──

    private fun key(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

    /** People here with the same name (a possible match), and matters here with the same title. */
    fun samePersonName(a: Person, b: Person): Boolean = key(a.name) == key(b.name)
    fun sameMatter(a: Matter, b: Matter): Boolean = key(a.title) == key(b.title)

    /**
     * Possible matches for the owner to confirm. A person is taken as the same one without asking
     * only when the name and a non-empty organisation both agree; a matter, never by itself: the
     * owner decides (two "Budget" matters are not always one).
     */
    class Plan(val personCandidates: Map<Long, List<Person>>, val matterCandidates: Map<Long, List<Matter>>, val personDefault: Map<Long, Long?>, val matterDefault: Map<Long, Long?>)

    fun plan(p: P, people: List<Person>, matters: List<Matter>): Plan {
        val pc = p.people.associate { x -> x.id to people.filter { samePersonName(it, x) } }.filterValues { it.isNotEmpty() }
        val mc = p.matters.associate { m -> m.id to matters.filter { sameMatter(it, m) } }.filterValues { it.isNotEmpty() }
        val pd = p.people.associate { x ->
            x.id to pc[x.id]?.singleOrNull { it.organization.isNotBlank() && x.organization.isNotBlank() && key(it.organization) == key(x.organization) }?.id
        }
        return Plan(pc, mc, pd, p.matters.associate { it.id to null })
    }

    /** What was added, for the message afterwards. */
    data class Result(val matters: Int, val people: Int, val tasks: Int, val events: Int, val skipped: Int, val failed: Boolean = false)

    /**
     * Adds the packet in one transaction: new people and matters are created, matching ones are
     * used as they are; tasks and events that are already here (same text, matter and date) are
     * skipped. Nothing existing is changed or deleted.
     */
    suspend fun apply(
        db: MandatDb, p: P, personChoice: Map<Long, Long?>, matterChoice: Map<Long, Long?>,
        // What is hidden now (trip mode, lens): never compared with, so "skipped" cannot tell that it exists
        hidden: (Long?, String) -> Boolean = { _, _ -> false },
    ): Result = db.withTransaction {
        // Only choices that point to records really here count; anything else is added as new
        val herePeople = db.people().all().map { it.id }.toSet()
        val hereMatters = db.matters().observeAll().first().map { it.id }.toSet()
        val roles = db.roles().observeAll().first()
        val now = System.currentTimeMillis()
        val personId = HashMap<Long, Long>()
        var addedPeople = 0
        p.people.forEach { x ->
            val found = personChoice[x.id]?.takeIf { it in herePeople }
            personId[x.id] = found ?: run {
                val clean = cleanPerson(x.copy(id = 0, createdAt = now, updatedAt = now)) ?: return@forEach
                addedPeople++
                db.people().insert(clean)
            }
        }
        val matterId = HashMap<Long, Long>()
        var addedMatters = 0
        p.matters.forEach { m ->
            val found = matterChoice[m.id]?.takeIf { it in hereMatters }
            matterId[m.id] = found ?: run {
                // A role is kept only when the receiver has one with the same name
                val role = m.roleId?.let(p.roleNames::get)?.let { n -> roles.firstOrNull { key(it.name) == key(n) }?.id }
                val clean = cleanMatter(m.copy(id = 0, roleId = role, createdAt = now, updatedAt = now)) ?: return@forEach
                addedMatters++
                db.matters().insert(clean).also { id -> db.history().insertAll(changesOf(null, clean, id, now, java.time.LocalDate.now().toEpochDay())) }
            }
        }
        // Only one person leads a matter: a lead from the packet is kept only where none is set yet
        val led = db.people().observeLinks().first().filter { it.raci == Raci.LEAD.name }.map { it.matterId }.toHashSet()
        p.links.forEach { l ->
            val m = matterId[l.matterId] ?: return@forEach
            val pp = personId[l.personId] ?: return@forEach
            val raci = Raci.of(l.raci)?.takeIf { it != Raci.LEAD || led.add(m) }?.name
            db.people().link(l.copy(matterId = m, personId = pp, raci = raci))
        }
        var tasks = 0
        var skipped = 0
        val haveD = db.delegations().observeAll().first().filterNot { hidden(it.matterId, it.what) }.map { Triple(key(it.what), it.matterId, it.due) }.toHashSet()
        p.delegations.forEach { d ->
            val m = d.matterId?.let(matterId::get)
            if (!haveD.add(Triple(key(d.what), m, d.due))) { skipped++; return@forEach }
            db.delegations().insert(d.copy(personId = d.personId?.let(personId::get), matterId = m, createdAt = now)); tasks++
        }
        val haveP = db.promises().observeAll().first().filterNot { hidden(it.matterId, it.what) }.map { Triple(key(it.what), it.matterId, it.due) }.toHashSet()
        p.promises.forEach { x ->
            val m = x.matterId?.let(matterId::get)
            if (!haveP.add(Triple(key(x.what), m, x.due))) { skipped++; return@forEach }
            db.promises().insert(x.copy(personId = x.personId?.let(personId::get), matterId = m, createdAt = now)); tasks++
        }
        val haveDl = db.deadlines().observeAll().first().filterNot { hidden(it.matterId, it.title) }.map { Triple(key(it.title), it.matterId, it.day) }.toHashSet()
        p.deadlines.forEach { d ->
            val m = d.matterId?.let(matterId::get)
            if (!haveDl.add(Triple(key(d.title), m, d.day))) { skipped++; return@forEach }
            val clean = cleanDeadline(d.copy(matterId = m, createdAt = now))
            if (clean == null) skipped++ else { db.deadlines().insert(clean); tasks++ }
        }
        var events = 0
        val haveE = db.events().observeAll().first().filterNot { hidden(it.matterId, it.title + " " + it.notes) }.map { Triple(key(it.title), it.day, it.startMin) }.toHashSet()
        val eventId = HashMap<Long, Long>()
        p.events.forEach { e ->
            if (!haveE.add(Triple(key(e.title), e.day, e.startMin))) { skipped++; return@forEach }
            val clean = cleanEvent(e.copy(id = 0, matterId = e.matterId?.let(matterId::get), createdAt = now, updatedAt = now)) ?: run { skipped++; return@forEach }
            eventId[e.id] = db.events().insert(clean); events++
        }
        p.eventPeople.forEach { l ->
            val e = eventId[l.eventId] ?: return@forEach
            val pp = personId[l.personId] ?: return@forEach
            db.events().link(EventPerson(e, pp))
        }
        Result(addedMatters, addedPeople, tasks, events, skipped)
    }
}
