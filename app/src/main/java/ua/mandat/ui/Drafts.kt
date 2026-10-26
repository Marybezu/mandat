package ua.mandat.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.withTransaction
import ua.mandat.data.Attention
import ua.mandat.data.InboxItem
import ua.mandat.data.MandatDb
import ua.mandat.data.MatterStatus
import ua.mandat.data.MatterType
import ua.mandat.data.cleanInboxText
import ua.mandat.data.cleanMatter

/**
 * A matter being edited. Lives in the Session (memory only), so turning the phone does not lose
 * it, and it is saved automatically if Mandat locks while the editor is open.
 */
class MatterDraft(val request: EditRequest) {
    var title by mutableStateOf(request.matter.title)
    var type by mutableStateOf(MatterType.of(request.matter.type))
    var roleId by mutableStateOf(request.matter.roleId)
    var attention by mutableStateOf(Attention.of(request.matter.attention))
    var nextStep by mutableStateOf(request.matter.nextStep)
    var goal by mutableStateOf(request.matter.goal)
    var description by mutableStateOf(request.matter.description)
    var status by mutableStateOf(MatterStatus.of(request.matter.status))

    /** Writes the draft in one transaction (with removing the Inbox note it came from). */
    suspend fun persist(db: MandatDb) {
        val now = System.currentTimeMillis()
        val edited = cleanMatter(
            request.matter.copy(
                title = title, type = type.name, roleId = roleId, attention = attention.name,
                nextStep = nextStep, goal = goal, description = description, status = status.name,
                updatedAt = now,
            ),
        ) ?: return
        val fromInbox = request.fromInboxId
        db.withTransaction {
            if (request.isNew) {
                db.matters().insert(edited.copy(id = 0, createdAt = now))
                if (fromInbox != null) db.inbox().delete(fromInbox)
            } else {
                db.matters().update(edited)
            }
        }
    }
}

/** Saves an unfinished Inbox note, so leaving the app does not lose it. */
suspend fun persistInboxDraft(db: MandatDb, text: String?) {
    val clean = text?.let { cleanInboxText(it) } ?: return
    db.inbox().insert(InboxItem(text = clean, createdAt = System.currentTimeMillis()))
}

/** A person being edited; same rules as [MatterDraft]. */
class PersonDraft(val person: ua.mandat.data.Person, val isNew: Boolean) {
    var name by mutableStateOf(person.name)
    var organization by mutableStateOf(person.organization)
    var jobTitle by mutableStateOf(person.jobTitle)
    var contact by mutableStateOf(person.contact)
    var notes by mutableStateOf(person.notes)
    var teamRole by mutableStateOf(ua.mandat.data.TeamRole.of(person.teamRole))
    var category by mutableStateOf(ua.mandat.data.PersonCategory.of(person.category))
    var categoryCustom by mutableStateOf(person.categoryCustom)
    var teamCustom by mutableStateOf(person.teamCustom)
    var influence by mutableStateOf(person.influence)
    var stance by mutableStateOf(ua.mandat.data.Stance.of(person.stance))
    var isGroup by mutableStateOf(person.isGroup)
    var reportsToId by mutableStateOf(person.reportsToId)
    var lastContact by mutableStateOf(person.lastContact)

    suspend fun persist(db: MandatDb) {
        val now = System.currentTimeMillis()
        val edited = ua.mandat.data.cleanPerson(
            person.copy(
                name = name, organization = organization, jobTitle = jobTitle, contact = contact,
                notes = notes, teamRole = teamRole?.name, updatedAt = now,
                category = category?.name, categoryCustom = categoryCustom, teamCustom = teamCustom,
                influence = influence, stance = stance?.name, isGroup = isGroup,
                reportsToId = reportsToId, lastContact = lastContact,
            ),
        ) ?: return
        if (isNew) db.people().insert(edited.copy(id = 0, createdAt = now)) else db.people().update(edited)
    }
}

/** A delegation being edited in its dialog. */
class DelegationDraft(val delegation: ua.mandat.data.Delegation, val isNew: Boolean) {
    var what by mutableStateOf(delegation.what)
    var personId by mutableStateOf(delegation.personId)
    var matterId by mutableStateOf(delegation.matterId)
    var due by mutableStateOf(delegation.due)
    var status by mutableStateOf(ua.mandat.data.TaskStatus.of(delegation.status))
    var purpose by mutableStateOf(delegation.purpose)
    var doneMeans by mutableStateOf(delegation.doneMeans)
    var freedom by mutableStateOf(ua.mandat.data.Freedom.of(delegation.freedom))
    var checkIn by mutableStateOf(delegation.checkIn)
    var returned by mutableStateOf(delegation.returned)

    suspend fun persist(db: MandatDb) {
        val text = what.trim().take(2_000)
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        val closed = if (status == ua.mandat.data.TaskStatus.OPEN) null else (delegation.closedAt ?: now)
        val edited = delegation.copy(
            what = text, personId = personId, matterId = matterId, due = due, status = status.name, closedAt = closed,
            purpose = purpose.trim().take(2_000), doneMeans = doneMeans.trim().take(2_000), freedom = freedom?.name,
            checkIn = checkIn, returned = returned,
        )
        if (isNew) db.delegations().insert(edited.copy(id = 0, createdAt = now)) else db.delegations().update(edited)
    }
}

/** A promise being edited in its dialog. */
class PromiseDraft(val promise: ua.mandat.data.Promise, val isNew: Boolean) {
    var what by mutableStateOf(promise.what)
    var personId by mutableStateOf(promise.personId)
    var toWhom by mutableStateOf(promise.toWhom)
    var isPublic by mutableStateOf(promise.isPublic)
    var matterId by mutableStateOf(promise.matterId)
    var due by mutableStateOf(promise.due)
    var status by mutableStateOf(ua.mandat.data.TaskStatus.of(promise.status))
    var source by mutableStateOf(promise.source)
    var statusNote by mutableStateOf(promise.statusNote)
    /** The day it was promised; old promises can be written down later with their real date. */
    var madeOn by mutableStateOf(if (promise.createdAt == 0L) Dates.today() else Dates.dayOf(promise.createdAt))

    suspend fun persist(db: MandatDb) {
        val text = what.trim().take(2_000)
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        val closed = if (status == ua.mandat.data.TaskStatus.OPEN) null else (promise.closedAt ?: now)
        val edited = promise.copy(
            what = text, personId = personId, toWhom = if (personId != null) "" else toWhom.trim().take(300), isPublic = isPublic,
            matterId = matterId, due = due, status = status.name, closedAt = closed,
            source = source.trim().take(1_000),
            statusNote = if (status == ua.mandat.data.TaskStatus.OPEN || status == ua.mandat.data.TaskStatus.DONE) "" else statusNote.trim().take(2_000),
        )
        // Keep the exact moment unless the owner picked another day (then noon of that day)
        val original = if (isNew) now else promise.createdAt
        val made = if (Dates.dayOf(original) == madeOn) original else Dates.noonOf(madeOn)
        if (isNew) db.promises().insert(edited.copy(id = 0, createdAt = made)) else db.promises().update(edited.copy(createdAt = made))
    }
}

fun newDelegation(personId: Long? = null, matterId: Long? = null) = DelegationDraft(
    ua.mandat.data.Delegation(
        what = "", personId = personId, matterId = matterId, due = null,
        status = ua.mandat.data.TaskStatus.OPEN.name, createdAt = 0, closedAt = null,
    ),
    isNew = true,
)

fun newPromise(personId: Long? = null, matterId: Long? = null) = PromiseDraft(
    ua.mandat.data.Promise(
        what = "", personId = personId, toWhom = "", isPublic = false, matterId = matterId, due = null,
        status = ua.mandat.data.TaskStatus.OPEN.name, createdAt = 0, closedAt = null,
    ),
    isNew = true,
)

fun newPerson() = PersonDraft(
    ua.mandat.data.Person(
        name = "", organization = "", jobTitle = "", contact = "", notes = "", teamRole = null,
        createdAt = 0, updatedAt = 0,
    ),
    isNew = true,
)

/** A deadline being edited in its dialog. */
class DeadlineDraft(val deadline: ua.mandat.data.Deadline, val isNew: Boolean) {
    var title by mutableStateOf(deadline.title)
    var day by mutableStateOf(deadline.day)
    var kind by mutableStateOf(ua.mandat.data.DeadlineKind.of(deadline.kind))
    var basis by mutableStateOf(deadline.basis)
    var matterId by mutableStateOf(deadline.matterId)
    var remindBefore by mutableStateOf(deadline.remindBefore)
    var status by mutableStateOf(ua.mandat.data.TaskStatus.of(deadline.status))

    fun valid() = title.isNotBlank() && (kind == ua.mandat.data.DeadlineKind.SOFT || basis.isNotBlank())

    suspend fun persist(db: MandatDb) {
        val now = System.currentTimeMillis()
        val closed = if (status == ua.mandat.data.TaskStatus.OPEN) null else (deadline.closedAt ?: now)
        val edited = ua.mandat.data.cleanDeadline(
            deadline.copy(
                title = title, day = day, kind = kind.name,
                // A soft deadline keeps no hidden basis
                basis = if (kind == ua.mandat.data.DeadlineKind.HARD) basis else "", matterId = matterId,
                remindBefore = remindBefore, status = status.name, closedAt = closed,
            ),
        ) ?: return
        if (isNew) db.deadlines().insert(edited.copy(id = 0, createdAt = now)) else db.deadlines().update(edited)
    }
}

fun newDeadline(matterId: Long? = null) = DeadlineDraft(
    ua.mandat.data.Deadline(
        title = "", day = Dates.today(), kind = ua.mandat.data.DeadlineKind.SOFT.name, basis = "",
        matterId = matterId, remindBefore = 1, status = ua.mandat.data.TaskStatus.OPEN.name,
        createdAt = 0, closedAt = null,
    ),
    isNew = true,
)

/** Saves today's answer to the evening question (one entry per day). */
suspend fun persistReview(db: MandatDb, text: String?) {
    val clean = text?.trim()?.take(ua.mandat.data.MAX_NOTE_LENGTH)?.ifEmpty { null } ?: return
    val today = Dates.today()
    val existing = db.reviews().forDay(today)
    if (existing == null) {
        db.reviews().insert(ua.mandat.data.Review(day = today, progress = clean, createdAt = System.currentTimeMillis()))
    } else {
        db.reviews().update(existing.copy(progress = clean))
    }
}

/** An event being edited in its dialog; the people taking part are saved with it. */
class EventDraft(val event: ua.mandat.data.Event, val isNew: Boolean, people: Set<Long> = emptySet()) {
    var title by mutableStateOf(event.title)
    var day by mutableStateOf(event.day)
    var endDay by mutableStateOf(event.endDay)
    var startMin by mutableStateOf(event.startMin)
    var endMin by mutableStateOf(event.endMin)
    var kind by mutableStateOf(ua.mandat.data.EventKind.of(event.kind))
    var place by mutableStateOf(event.place)
    var matterId by mutableStateOf(event.matterId)
    var notes by mutableStateOf(event.notes)
    var outcome by mutableStateOf(event.outcome)
    var remind by mutableStateOf(event.remind)
    var people by mutableStateOf(people)

    fun valid() = title.isNotBlank()

    fun current(now: Long) = event.copy(
        title = title, day = day, endDay = endDay, startMin = startMin, endMin = endMin, kind = kind.name,
        place = place, matterId = matterId, notes = notes, outcome = outcome, remind = remind, updatedAt = now,
    )

    suspend fun persist(db: MandatDb) {
        val now = System.currentTimeMillis()
        val edited = ua.mandat.data.cleanEvent(current(now)) ?: return
        db.withTransaction {
            val id = if (isNew) db.events().insert(edited.copy(id = 0, createdAt = now)) else edited.id.also { db.events().update(edited) }
            db.events().setPeople(id, people)
            // A meeting that has happened is the last contact with the people in it
            if (edited.day <= Dates.today()) people.forEach { db.people().touchLastContact(it, edited.day) }
        }
    }
}

fun newEvent(day: Long = Dates.today(), matterId: Long? = null, personId: Long? = null) = EventDraft(
    ua.mandat.data.Event(
        title = "", day = day, kind = ua.mandat.data.EventKind.MEETING.name, matterId = matterId,
        createdAt = 0, updatedAt = 0,
    ),
    isNew = true,
    people = setOfNotNull(personId),
)
