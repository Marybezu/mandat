package ua.mandat.ui

import android.content.Context
import kotlinx.coroutines.flow.first
import ua.mandat.R
import ua.mandat.data.Export
import ua.mandat.data.MandatDb
import ua.mandat.data.Obsidian
import ua.mandat.data.Span
import ua.mandat.data.TaskStatus

/** How much goes into the Obsidian vault. */
enum class VaultScope { BASIC, FULL }

/**
 * A vault for Obsidian: folders of notes with front matter and [[links]] between them, so the
 * graph shows how matters, people, events and the rest connect.
 *
 * BASIC: matters, people, events and diary days. FULL: also delegations, promises and deadlines
 * as notes of their own, media, alliances, communities, texts, budget, the Inbox, and with
 * [allPeople] the whole address book. [span] limits records to a period (null: everything; for
 * BASIC without a period, events a year back and ahead). Without [allPeople] only the people tied
 * to what is exported are included. What is hidden (trip mode, lens) and people known only
 * through it stay out, as in every export.
 */
internal suspend fun obsidianVault(
    context: Context, db: MandatDb, hidden: Set<Long>, onlyHidden: Set<Long>,
    scope: VaultScope = VaultScope.BASIC, span: Span? = null, allPeople: Boolean = false,
): List<Pair<String, String>> {
    fun visible(id: Long?) = id == null || id !in hidden
    fun s(id: Int) = context.getString(id)
    val today = Dates.today()
    val full = scope == VaultScope.FULL
    fun inSpan(day: Long?) = span == null || (day != null && day in span)
    fun msIn(ms: Long?) = ms != null && inSpan(Dates.dayOf(ms))
    val root = OBSIDIAN_ROOT

    // Everything visible, then narrowed to the period
    val roles = db.roles().observeAll().first().associate { it.id to it.name }
    val history = db.history().observeAll().first().filter { visible(it.matterId) }
    val movedIds = history.filter { inSpan(it.day) }.map { it.matterId }.toSet()
    val matters = db.matters().observeAll().first().filter { m ->
        visible(m.id) && (span == null || msIn(m.createdAt) || msIn(m.updatedAt) || m.id in movedIds)
    }
    val events = db.events().observeAll().first().filter { e ->
        visible(e.matterId) && when {
            span != null -> e.day <= span.to && e.lastDay >= span.from
            full -> true
            else -> e.lastDay >= today - 365 && e.day <= today + 365
        }
    }
    fun taskIn(created: Long, closed: Long?, due: Long?) = span == null || msIn(created) || msIn(closed) || (due != null && inSpan(due))
    val delegations = db.delegations().observeAll().first().filter { visible(it.matterId) && taskIn(it.createdAt, it.closedAt, it.due) }
    val promises = db.promises().observeAll().first().filter { visible(it.matterId) && taskIn(it.createdAt, it.closedAt, it.due) }
    val deadlines = db.deadlines().observeAll().first().filter { visible(it.matterId) && taskIn(it.createdAt, it.closedAt, it.day) }
    val eventPeople = db.events().observeLinks().first().groupBy({ it.eventId }, { it.personId })
    val links = db.people().observeLinks().first().filter { visible(it.matterId) }
    val info = if (full) db.info().observeAll().first().filter { visible(it.matterId) && (inSpan(it.day) || msIn(it.updatedAt)) } else emptyList()
    val alliances = if (full) db.info().observeAlliances().first().filter { visible(it.matterId) && (span == null || msIn(it.createdAt) || msIn(it.updatedAt)) } else emptyList()
    val messages = if (full) db.info().observeMessages().first().filter { visible(it.matterId) && (span == null || msIn(it.createdAt) || msIn(it.updatedAt)) } else emptyList()
    val money = if (full) db.money().observeAll().first().filter { visible(it.matterId) && inSpan(it.day) } else emptyList()
    val texts = if (full) db.texts().observeAll().first().filter { visible(it.matterId) && (span == null || msIn(it.createdAt) || msIn(it.updatedAt)) } else emptyList()
    val inbox = if (full) db.inbox().observeAll().first().filter { span == null || msIn(it.createdAt) } else emptyList()
    val communityLinks = if (full) db.communities().observeLinks().first() else emptyList()
    // A community tied to a hidden matter keeps only its visible links
    val commLinksVisible = communityLinks.filter { it.kind != ua.mandat.data.LinkKind.MATTER || it.refId !in hidden }

    // People: everyone (no period, or asked for), or only those tied to what goes in
    val allVisiblePeople = db.people().all().filter { it.id !in onlyHidden }
    val matterIds = matters.map { it.id }.toSet()
    val tied = buildSet {
        links.filter { it.matterId in matterIds }.forEach { add(it.personId) }
        delegations.mapNotNull { it.personId }.forEach { add(it) }
        promises.mapNotNull { it.personId }.forEach { add(it) }
        events.forEach { e -> eventPeople[e.id].orEmpty().forEach { add(it) } }
        alliances.mapNotNull { it.personId }.forEach { add(it) }
    }
    val people = if (allPeople || (span == null && !full) || (span == null && full)) allVisiblePeople else allVisiblePeople.filter { it.id in tied }
    val contacts = if (full && allPeople) db.contacts().all() else emptyList()
    val communities = if (full) db.communities().observeAll().first().let { cs ->
        if (span == null) cs else cs.filter { c ->
            msIn(c.updatedAt) || commLinksVisible.any { l -> l.communityId == c.id && when (l.kind) {
                ua.mandat.data.LinkKind.MATTER -> l.refId in matterIds
                ua.mandat.data.LinkKind.EVENT -> events.any { it.id == l.refId }
                ua.mandat.data.LinkKind.PROMISE -> promises.any { it.id == l.refId }
                else -> false
            } }
        }
    } else emptyList()

    // Note names, unique within each folder
    val fMatters = s(R.string.obs_matters); val fPeople = s(R.string.obs_people); val fEvents = s(R.string.obs_events); val fDiary = s(R.string.obs_diary)
    val fDeleg = s(R.string.delegations); val fProm = s(R.string.promises); val fDead = s(R.string.deadlines); val fInbox = s(R.string.inbox)
    val fMedia = s(R.string.obs_media); val fAll = s(R.string.obs_alliances); val fComm = s(R.string.obs_communities); val fTexts = s(R.string.texts_title)
    val fMoney = s(R.string.obs_budget); val fContacts = s(R.string.obs_contacts); val fMsg = s(R.string.obs_messages)
    val mName = Obsidian.unique(matters.map { it.id to Obsidian.name(it.title, "matter-${it.id}") })
    val pName = Obsidian.unique(people.map { it.id to Obsidian.name(it.name, "person-${it.id}") })
    val eName = Obsidian.unique(events.map { it.id to Obsidian.name(Export.day(it.day) + " " + it.title, "event-${it.id}") })
    val dgName = Obsidian.unique(delegations.map { it.id to Obsidian.name(it.what, "delegation-${it.id}") })
    val prName = Obsidian.unique(promises.map { it.id to Obsidian.name(it.what, "promise-${it.id}") })
    val dlName = Obsidian.unique(deadlines.map { it.id to Obsidian.name(Export.day(it.day) + " " + it.title, "deadline-${it.id}") })
    val cName = Obsidian.unique(communities.map { it.id to Obsidian.name(it.name, "community-${it.id}") })
    // In the full vault each kind is its own folder, so links name the folder too and never clash
    fun lk(folder: String, name: String?) = name?.let { if (full) "[[$folder/$it|$it]]" else Obsidian.link(it) }
    fun pl(id: Long?) = id?.let(pName::get)?.let { lk(fPeople, it) }
    fun ml(id: Long?) = id?.let(mName::get)?.let { lk(fMatters, it) }
    fun el(id: Long) = lk(fEvents, eName[id])
    fun status(st: String) = s(TaskStatus.of(st).delegationLabel())
    fun yamlLine(key: String, value: String?) = if (value.isNullOrBlank()) "" else "$key: ${Obsidian.yaml(value)}\n"
    val out = ArrayList<Pair<String, String>>()
    fun note(folder: String, name: String, tag: String, front: String, body: String) {
        out += "$root$folder/$name.md" to "---\n${front}tags: [mandat/$tag]\n---\n\n$body"
    }

    matters.forEach { m ->
        note(fMatters, mName.getValue(m.id), "matter",
            yamlLine("type", s(ua.mandat.data.MatterType.of(m.type).label())) + yamlLine("role", m.roleId?.let(roles::get)) +
                yamlLine("attention", s(ua.mandat.data.Attention.of(m.attention).label())) + yamlLine("status", s(ua.mandat.data.MatterStatus.of(m.status).label())) +
                "created: ${Export.day(Dates.dayOf(m.createdAt))}\nupdated: ${Export.day(Dates.dayOf(m.updatedAt))}\n",
            buildString {
                if (m.goal.isNotBlank()) append("**").append(s(R.string.matter_goal)).append(":** ").append(m.goal).append("\n\n")
                if (m.nextStep.isNotBlank()) append("**").append(s(R.string.matter_next_step)).append(":** ").append(m.nextStep).append("\n\n")
                if (m.description.isNotBlank()) append(m.description).append("\n\n")
                val ps = links.filter { it.matterId == m.id && it.personId in pName }
                if (ps.isNotEmpty()) {
                    append("## ").append(fPeople).append("\n")
                    ps.forEach { l -> append("- ").append(pl(l.personId)).append(ua.mandat.data.Stance.of(l.stance)?.let { " — " + s(it.label()) } ?: "").append("\n") }
                    append("\n")
                }
                fun section(title: String, rows: List<String>) { if (rows.isNotEmpty()) { append("## ").append(title).append("\n"); rows.forEach { append("- ").append(it).append("\n") }; append("\n") } }
                if (full) {
                    section(fDead, deadlines.filter { it.matterId == m.id }.map { lk(fDead, dlName[it.id])!! })
                    section(fDeleg, delegations.filter { it.matterId == m.id }.map { lk(fDeleg, dgName[it.id])!! })
                    section(fProm, promises.filter { it.matterId == m.id }.map { lk(fProm, prName[it.id])!! })
                } else {
                    section(fDead, deadlines.filter { it.matterId == m.id }.map { Export.day(it.day) + " " + it.title + " (" + status(it.status) + ")" })
                    section(fDeleg, delegations.filter { it.matterId == m.id }.map { d -> d.what + (pl(d.personId)?.let { " → $it" } ?: "") + (d.due?.let { " · " + Export.day(it) } ?: "") + " (" + status(d.status) + ")" })
                    section(fProm, promises.filter { it.matterId == m.id }.map { p -> p.what + (pl(p.personId)?.let { " → $it" } ?: p.toWhom.takeIf { it.isNotBlank() }?.let { " → $it" } ?: "") + " (" + status(p.status) + ")" })
                }
                section(fEvents, events.filter { it.matterId == m.id }.sortedBy { it.day }.map { el(it.id)!! })
                if (full) {
                    section(fMedia, info.filter { it.matterId == m.id }.map { Export.day(it.day) + " " + it.title })
                    section(fMsg, messages.filter { it.matterId == m.id }.map { it.text })
                    section(fTexts, texts.filter { it.matterId == m.id }.map { it.title })
                }
            })
    }
    people.forEach { p ->
        note(fPeople, pName.getValue(p.id), if (p.teamRole != null) "person, mandat/team" else "person",
            yamlLine("organization", p.organization) + yamlLine("position", p.jobTitle),
            buildString {
                if (p.contact.isNotBlank()) append(p.contact).append("\n\n")
                if (p.notes.isNotBlank()) append(p.notes).append("\n\n")
                val ms = links.filter { it.personId == p.id && it.matterId in mName }
                if (ms.isNotEmpty()) {
                    append("## ").append(fMatters).append("\n")
                    ms.forEach { l -> append("- ").append(ml(l.matterId)).append(ua.mandat.data.Stance.of(l.stance)?.let { " — " + s(it.label()) } ?: "").append("\n") }
                }
            })
    }
    events.forEach { e ->
        note(fEvents, eName.getValue(e.id), "event",
            "date: ${Export.day(e.day)}\n" + yamlLine("kind", s(ua.mandat.data.EventKind.of(e.kind).label())) + yamlLine("place", e.place),
            buildString {
                ml(e.matterId)?.let { append("**").append(s(R.string.obs_matter)).append(":** ").append(it).append("\n\n") }
                val who = eventPeople[e.id].orEmpty().mapNotNull { pl(it) }
                if (who.isNotEmpty()) append("**").append(fPeople).append(":** ").append(who.joinToString(", ")).append("\n\n")
                if (e.notes.isNotBlank()) append(e.notes).append("\n\n")
                if (e.outcome.isNotBlank()) append("## ").append(s(R.string.event_outcome)).append("\n").append(e.outcome).append("\n")
            })
    }
    // Diary days, without those linked to what is hidden
    val hiddenReviews = db.reviews().observeLinks().first()
        .filter { (it.kind == "matter" && it.refId in hidden) || (it.kind == "person" && it.refId in onlyHidden) }.map { it.reviewId }.toSet()
    db.reviews().observeAll().first().filter { it.id !in hiddenReviews && it.progress.isNotBlank() && inSpan(it.day) }.forEach { r ->
        note(fDiary, Export.day(r.day), "diary", "date: ${Export.day(r.day)}\n", r.progress + "\n")
    }

    if (full) {
        delegations.forEach { d ->
            note(fDeleg, dgName.getValue(d.id), "delegation", yamlLine("status", status(d.status)) + (d.due?.let { "due: ${Export.day(it)}\n" } ?: ""),
                buildString {
                    append(d.what).append("\n\n")
                    pl(d.personId)?.let { append("**").append(s(R.string.obs_to_whom)).append(":** ").append(it).append("\n") }
                    ml(d.matterId)?.let { append("**").append(s(R.string.obs_matter)).append(":** ").append(it).append("\n") }
                    if (d.purpose.isNotBlank()) append("\n").append(d.purpose).append("\n")
                    if (d.doneMeans.isNotBlank()) append("\n").append(d.doneMeans).append("\n")
                })
        }
        promises.forEach { p ->
            note(fProm, prName.getValue(p.id), "promise", yamlLine("status", status(p.status)) + (p.due?.let { "due: ${Export.day(it)}\n" } ?: "") + yamlLine("publicity", p.level.name.lowercase()),
                buildString {
                    append(p.what).append("\n\n")
                    (pl(p.personId) ?: p.toWhom.takeIf { it.isNotBlank() })?.let { append("**").append(s(R.string.obs_to_whom)).append(":** ").append(it).append("\n") }
                    ml(p.matterId)?.let { append("**").append(s(R.string.obs_matter)).append(":** ").append(it).append("\n") }
                    if (p.source.isNotBlank()) append("\n").append(p.source).append("\n")
                    if (p.statusNote.isNotBlank()) append("\n").append(p.statusNote).append("\n")
                })
        }
        deadlines.forEach { d ->
            note(fDead, dlName.getValue(d.id), "deadline", "date: ${Export.day(d.day)}\n" + yamlLine("status", status(d.status)),
                buildString {
                    append(d.title).append("\n\n")
                    ml(d.matterId)?.let { append("**").append(s(R.string.obs_matter)).append(":** ").append(it).append("\n") }
                    if (d.basis.isNotBlank()) append("\n").append(d.basis).append("\n")
                })
        }
        if (info.isNotEmpty()) out += "$root$fMedia/$fMedia.md" to buildString {
            append("---\ntags: [mandat/media]\n---\n\n")
            info.sortedByDescending { it.day }.forEach { i ->
                append("## ").append(Export.day(i.day)).append(" ").append(i.title).append("\n")
                append(s(ua.mandat.data.InfoKind.of(i.kind).label()))
                ml(i.matterId)?.let { append(" · ").append(it) }
                pl(i.personId)?.let { append(" · ").append(it) }
                append("\n")
                if (i.source.isNotBlank()) append(i.source).append("\n")
                if (i.notes.isNotBlank()) append("\n").append(i.notes).append("\n")
                if (i.result.isNotBlank()) append("\n").append(i.result).append("\n")
                append("\n")
            }
        }
        if (messages.isNotEmpty()) out += "$root$fMedia/$fMsg.md" to buildString {
            append("---\ntags: [mandat/messages]\n---\n\n")
            messages.forEach { k -> append("- ").append(k.text).append(k.proof.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "").append(ml(k.matterId)?.let { " · $it" } ?: "").append("\n") }
        }
        if (alliances.isNotEmpty()) out += "$root$fAll/$fAll.md" to buildString {
            append("---\ntags: [mandat/alliances]\n---\n\n")
            alliances.forEach { a ->
                append("## ").append(pl(a.personId) ?: a.withWhom).append("\n")
                append(a.about).append(" · ").append(s(ua.mandat.data.AllianceStatus.of(a.status).label()))
                ml(a.matterId)?.let { append(" · ").append(it) }
                append("\n")
                if (a.gives.isNotBlank()) append("→ ").append(a.gives).append("\n")
                if (a.gets.isNotBlank()) append("← ").append(a.gets).append("\n")
                if (a.notes.isNotBlank()) append("\n").append(a.notes).append("\n")
                append("\n")
            }
        }
        communities.forEach { c ->
            note(fComm, cName.getValue(c.id), "community", yamlLine("kind", s(ua.mandat.data.CommunityKind.of(c.kind).label())) +
                (c.population?.let { "population: $it\n" } ?: "") + yamlLine("head", c.head),
                buildString {
                    if (c.notes.isNotBlank()) append(c.notes).append("\n\n")
                    val ls = commLinksVisible.filter { it.communityId == c.id }
                    val ms = ls.filter { it.kind == ua.mandat.data.LinkKind.MATTER }.mapNotNull { ml(it.refId) }
                    val ps = ls.filter { it.kind == ua.mandat.data.LinkKind.PERSON }.mapNotNull { pl(it.refId) }
                    val es = ls.filter { it.kind == ua.mandat.data.LinkKind.EVENT }.mapNotNull { l -> eName[l.refId]?.let { el(l.refId) } }
                    val prs = ls.filter { it.kind == ua.mandat.data.LinkKind.PROMISE }.mapNotNull { l -> prName[l.refId]?.let { lk(fProm, it) } }
                    if (ms.isNotEmpty()) append("## ").append(fMatters).append("\n").append(ms.joinToString("\n") { "- $it" }).append("\n\n")
                    if (ps.isNotEmpty()) append("## ").append(fPeople).append("\n").append(ps.joinToString("\n") { "- $it" }).append("\n\n")
                    if (es.isNotEmpty()) append("## ").append(fEvents).append("\n").append(es.joinToString("\n") { "- $it" }).append("\n\n")
                    if (prs.isNotEmpty()) append("## ").append(fProm).append("\n").append(prs.joinToString("\n") { "- $it" }).append("\n")
                })
        }
        texts.forEach { t ->
            note(fTexts, Obsidian.name(t.title, "text-${t.id}") + " " + t.id, "text",
                yamlLine("kind", s(ua.mandat.data.TextKind.of(t.kind).label())) + "updated: ${Export.day(Dates.dayOf(t.updatedAt))}\n",
                (ml(t.matterId)?.let { "**${s(R.string.obs_matter)}:** $it\n\n" } ?: "") + t.body + "\n")
        }
        if (money.isNotEmpty()) out += "$root$fMoney/$fMoney.md" to buildString {
            append("---\ntags: [mandat/budget]\n---\n\n")
            append("| ").append(s(R.string.obs_date)).append(" | ").append(s(R.string.obs_category)).append(" | ").append(s(R.string.obs_sum)).append(" | ").append(s(R.string.obs_note)).append(" |\n|---|---|---|---|\n")
            money.sortedBy { it.day }.forEach { m ->
                fun cell(v: String) = v.replace("|", "/").replace("\n", " ")
                append("| ").append(Export.day(m.day)).append(" | ").append(cell(m.category)).append(" | ")
                    .append(m.amount / 100).append('.').append("%02d".format(kotlin.math.abs(m.amount % 100))).append(" ").append(m.currency).append(" | ")
                    .append(cell(listOf(m.note, m.toWhom).filter { it.isNotBlank() }.joinToString(" · ")) + (ml(m.matterId)?.let { " · $it" } ?: "")).append(" |\n")
            }
        }
        if (inbox.isNotEmpty()) out += "$root$fInbox/$fInbox.md" to buildString {
            append("---\ntags: [mandat/inbox]\n---\n\n")
            inbox.forEach { i -> append("- ").append(Export.day(Dates.dayOf(i.createdAt))).append(" ").append(i.text.replace("\n", " ")).append("\n") }
        }
        if (contacts.isNotEmpty()) out += "$root$fContacts/$fContacts.md" to buildString {
            append("---\ntags: [mandat/contacts]\n---\n\n")
            contacts.groupBy { it.label.ifBlank { "—" } }.toSortedMap().forEach { (label, list) ->
                append("## ").append(label).append("\n")
                list.forEach { c -> append("- **").append(c.name).append("**").append(listOf(c.jobTitle, c.organization, c.phones, c.emails).filter { it.isNotBlank() }.joinToString(" · ").let { if (it.isEmpty()) "" else " — $it" }).append("\n") }
                append("\n")
            }
        }
    }

    // A contents note: what period, and a link to each folder's notes
    out += "${root}Mandat.md" to buildString {
        append("# Mandat\n\n")
        append(span?.let { s(R.string.obs_period) + ": " + Export.day(it.from) + " — " + Export.day(it.to) } ?: s(R.string.obs_period_all)).append("\n\n")
        fun line(folder: String, n: Int) { if (n > 0) append("- ").append(folder).append(": ").append(n).append("\n") }
        line(fMatters, matters.size); line(fPeople, people.size); line(fEvents, events.size)
        if (full) { line(fDeleg, delegations.size); line(fProm, promises.size); line(fDead, deadlines.size); line(fComm, communities.size); line(fTexts, texts.size) }
    }
    return out
}
