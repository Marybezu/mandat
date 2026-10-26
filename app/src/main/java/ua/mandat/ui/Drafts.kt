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

    suspend fun persist(db: MandatDb) {
        val now = System.currentTimeMillis()
        val edited = ua.mandat.data.cleanPerson(
            person.copy(
                name = name, organization = organization, jobTitle = jobTitle, contact = contact,
                notes = notes, teamRole = teamRole?.name, updatedAt = now,
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

    suspend fun persist(db: MandatDb) {
        val text = what.trim().take(2_000)
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        val closed = if (status == ua.mandat.data.TaskStatus.OPEN) null else (delegation.closedAt ?: now)
        val edited = delegation.copy(what = text, personId = personId, matterId = matterId, due = due, status = status.name, closedAt = closed)
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

    suspend fun persist(db: MandatDb) {
        val text = what.trim().take(2_000)
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        val closed = if (status == ua.mandat.data.TaskStatus.OPEN) null else (promise.closedAt ?: now)
        val edited = promise.copy(
            what = text, personId = personId, toWhom = toWhom.trim().take(300), isPublic = isPublic,
            matterId = matterId, due = due, status = status.name, closedAt = closed,
        )
        if (isNew) db.promises().insert(edited.copy(id = 0, createdAt = now)) else db.promises().update(edited)
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
