package ua.mandat.ui

import android.content.Context
import kotlinx.coroutines.flow.first
import ua.mandat.R
import ua.mandat.data.Export
import ua.mandat.data.MandatDb
import ua.mandat.data.Obsidian
import ua.mandat.data.TaskStatus

/**
 * A vault for Obsidian: folders of notes for matters, people, events (the last year and what is
 * ahead) and diary days, each with front matter and [[links]] to the others. What is hidden (trip
 * mode, lens) and people known only through it stay out, as in every export.
 */
internal suspend fun obsidianVault(context: Context, db: MandatDb, hidden: Set<Long>, onlyHidden: Set<Long>): List<Pair<String, String>> {
    fun visible(id: Long?) = id == null || id !in hidden
    val today = Dates.today()
    val root = OBSIDIAN_ROOT
    val fMatters = context.getString(R.string.obs_matters); val fPeople = context.getString(R.string.obs_people)
    val fEvents = context.getString(R.string.obs_events); val fDiary = context.getString(R.string.obs_diary)
    val roles = db.roles().observeAll().first().associate { it.id to it.name }
    val matters = db.matters().observeAll().first().filter { visible(it.id) }
    val people = db.people().all().filter { it.id !in onlyHidden }
    val events = db.events().observeAll().first().filter { visible(it.matterId) && it.lastDay >= today - 365 && it.day <= today + 365 }
    val eventPeople = db.events().observeLinks().first().groupBy({ it.eventId }, { it.personId })
    val links = db.people().observeLinks().first().filter { visible(it.matterId) }
    val delegations = db.delegations().observeAll().first().filter { visible(it.matterId) }
    val promises = db.promises().observeAll().first().filter { visible(it.matterId) }
    val deadlines = db.deadlines().observeAll().first().filter { visible(it.matterId) }

    val mName = Obsidian.unique(matters.map { it.id to Obsidian.name(it.title, "matter-${it.id}") })
    val pName = Obsidian.unique(people.map { it.id to Obsidian.name(it.name, "person-${it.id}") })
    val eName = Obsidian.unique(events.map { it.id to Obsidian.name(Export.day(it.day) + " " + it.title, "event-${it.id}") })
    fun pl(id: Long?) = id?.let(pName::get)?.let(Obsidian::link)
    fun ml(id: Long?) = id?.let(mName::get)?.let(Obsidian::link)
    fun status(s: String) = context.getString(TaskStatus.of(s).delegationLabel())
    val out = ArrayList<Pair<String, String>>()

    matters.forEach { m ->
        out += "$root$fMatters/${mName[m.id]}.md" to buildString {
            append("---\n")
            append("type: ").append(Obsidian.yaml(context.getString(ua.mandat.data.MatterType.of(m.type).label()))).append("\n")
            m.roleId?.let(roles::get)?.let { append("role: ").append(Obsidian.yaml(it)).append("\n") }
            append("attention: ").append(Obsidian.yaml(context.getString(ua.mandat.data.Attention.of(m.attention).label()))).append("\n")
            append("status: ").append(Obsidian.yaml(context.getString(ua.mandat.data.MatterStatus.of(m.status).label()))).append("\n")
            append("created: ").append(Export.day(Dates.dayOf(m.createdAt))).append("\n")
            append("updated: ").append(Export.day(Dates.dayOf(m.updatedAt))).append("\n")
            append("tags: [mandat/matter]\n---\n\n")
            if (m.goal.isNotBlank()) append("**").append(context.getString(R.string.matter_goal)).append(":** ").append(m.goal).append("\n\n")
            if (m.nextStep.isNotBlank()) append("**").append(context.getString(R.string.matter_next_step)).append(":** ").append(m.nextStep).append("\n\n")
            if (m.description.isNotBlank()) append(m.description).append("\n\n")
            val ps = links.filter { it.matterId == m.id && it.personId in pName }
            if (ps.isNotEmpty()) {
                append("## ").append(context.getString(R.string.obs_people)).append("\n")
                ps.forEach { l -> append("- ").append(pl(l.personId)).append(ua.mandat.data.Stance.of(l.stance)?.let { " — " + context.getString(it.label()) } ?: "").append("\n") }
                append("\n")
            }
            val dls = deadlines.filter { it.matterId == m.id }
            if (dls.isNotEmpty()) { append("## ").append(context.getString(R.string.deadlines)).append("\n"); dls.forEach { append("- ").append(Export.day(it.day)).append(" ").append(it.title).append(" (").append(status(it.status)).append(")\n") }; append("\n") }
            val dgs = delegations.filter { it.matterId == m.id }
            if (dgs.isNotEmpty()) { append("## ").append(context.getString(R.string.delegations)).append("\n"); dgs.forEach { d -> append("- ").append(d.what).append(pl(d.personId)?.let { " → $it" } ?: "").append(d.due?.let { " · " + Export.day(it) } ?: "").append(" (").append(status(d.status)).append(")\n") }; append("\n") }
            val prs = promises.filter { it.matterId == m.id }
            if (prs.isNotEmpty()) { append("## ").append(context.getString(R.string.promises)).append("\n"); prs.forEach { p -> append("- ").append(p.what).append(pl(p.personId)?.let { " → $it" } ?: p.toWhom.takeIf { it.isNotBlank() }?.let { " → $it" } ?: "").append(" (").append(status(p.status)).append(")\n") }; append("\n") }
            val evs = events.filter { it.matterId == m.id }.sortedBy { it.day }
            if (evs.isNotEmpty()) { append("## ").append(context.getString(R.string.obs_events)).append("\n"); evs.forEach { append("- ").append(Obsidian.link(eName.getValue(it.id))).append("\n") } }
        }
    }
    people.forEach { p ->
        out += "$root$fPeople/${pName[p.id]}.md" to buildString {
            append("---\n")
            if (p.organization.isNotBlank()) append("organization: ").append(Obsidian.yaml(p.organization)).append("\n")
            if (p.jobTitle.isNotBlank()) append("position: ").append(Obsidian.yaml(p.jobTitle)).append("\n")
            append("tags: [mandat/person").append(if (p.teamRole != null) ", mandat/team" else "").append("]\n---\n\n")
            if (p.contact.isNotBlank()) append(p.contact).append("\n\n")
            if (p.notes.isNotBlank()) append(p.notes).append("\n\n")
            val ms = links.filter { it.personId == p.id && it.matterId in mName }
            if (ms.isNotEmpty()) {
                append("## ").append(context.getString(R.string.obs_matters)).append("\n")
                ms.forEach { l -> append("- ").append(ml(l.matterId)).append(ua.mandat.data.Stance.of(l.stance)?.let { " — " + context.getString(it.label()) } ?: "").append("\n") }
            }
        }
    }
    events.forEach { e ->
        out += "$root$fEvents/${eName[e.id]}.md" to buildString {
            append("---\n")
            append("date: ").append(Export.day(e.day)).append("\n")
            append("kind: ").append(Obsidian.yaml(context.getString(ua.mandat.data.EventKind.of(e.kind).label()))).append("\n")
            if (e.place.isNotBlank()) append("place: ").append(Obsidian.yaml(e.place)).append("\n")
            append("tags: [mandat/event]\n---\n\n")
            ml(e.matterId)?.let { append("**").append(context.getString(R.string.obs_matter)).append(":** ").append(it).append("\n\n") }
            val who = eventPeople[e.id].orEmpty().mapNotNull { pl(it) }
            if (who.isNotEmpty()) append("**").append(context.getString(R.string.obs_people)).append(":** ").append(who.joinToString(", ")).append("\n\n")
            if (e.notes.isNotBlank()) append(e.notes).append("\n\n")
            if (e.outcome.isNotBlank()) append("## ").append(context.getString(R.string.event_outcome)).append("\n").append(e.outcome).append("\n")
        }
    }
    // Diary days, without the ones linked to what is hidden
    val hiddenReviews = db.reviews().observeLinks().first()
        .filter { (it.kind == "matter" && it.refId in hidden) || (it.kind == "person" && it.refId in onlyHidden) }.map { it.reviewId }.toSet()
    db.reviews().observeAll().first().filter { it.id !in hiddenReviews && it.progress.isNotBlank() }.forEach { r ->
        out += "$root$fDiary/${Export.day(r.day)}.md" to "---\ndate: ${Export.day(r.day)}\ntags: [mandat/diary]\n---\n\n${r.progress}\n"
    }
    return out
}
