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
    var sensitive by mutableStateOf(request.matter.sensitive)
    var votesNeeded by mutableStateOf(request.matter.votesNeeded)

    /** Writes the draft in one transaction (with removing the Inbox note it came from). */
    suspend fun persist(db: MandatDb) {
        val now = System.currentTimeMillis()
        val edited = cleanMatter(
            request.matter.copy(
                title = title, type = type.name, roleId = roleId, attention = attention.name,
                nextStep = nextStep, goal = goal, description = description, status = status.name,
                updatedAt = now, sensitive = sensitive, votesNeeded = votesNeeded?.takeIf { it in 1..10_000 },
            ),
        ) ?: return
        val fromInbox = request.fromInboxId
        val today = Dates.today()
        db.withTransaction {
            if (request.isNew) {
                val id = db.matters().insert(edited.copy(id = 0, createdAt = now))
                db.history().insertAll(ua.mandat.data.changesOf(null, edited, id, now, today))
                if (fromInbox != null) db.inbox().delete(fromInbox)
            } else {
                // The saved version, not the one the editor opened with: another save may have come between
                val before = db.matters().get(edited.id)
                db.matters().update(edited)
                db.history().insertAll(ua.mandat.data.changesOf(before, edited, edited.id, now, today))
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
    var more by mutableStateOf(person.moreCategories.lines().filter { it.isNotBlank() }.toSet())

    suspend fun persist(db: MandatDb) {
        val now = System.currentTimeMillis()
        val edited = ua.mandat.data.cleanPerson(
            person.copy(
                name = name, organization = organization, jobTitle = jobTitle, contact = contact,
                notes = notes, teamRole = teamRole?.name, updatedAt = now,
                category = category?.name, categoryCustom = categoryCustom, teamCustom = teamCustom,
                influence = influence, stance = stance?.name, isGroup = isGroup,
                reportsToId = reportsToId, lastContact = lastContact, moreCategories = more.joinToString("\n"),
            ),
        ) ?: return
        if (isNew) db.people().insert(edited.copy(id = 0, createdAt = now)) else db.people().update(edited)
    }
}

/** A delegation being edited in its dialog. */
class DelegationDraft(val delegation: ua.mandat.data.Delegation, val isNew: Boolean) {
    /** Something was typed or chosen since the sheet opened: closing it asks before throwing that away. */
    fun changed(): Boolean = what != delegation.what || purpose != delegation.purpose || doneMeans != delegation.doneMeans || personId != delegation.personId || matterId != delegation.matterId || due != delegation.due

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
    /** Something was typed or chosen since the sheet opened: closing it asks before throwing that away. */
    fun changed(): Boolean = what != promise.what || toWhom != promise.toWhom || source != promise.source || statusNote != promise.statusNote || personId != promise.personId || matterId != promise.matterId || due != promise.due

    var what by mutableStateOf(promise.what)
    var personId by mutableStateOf(promise.personId)
    var toWhom by mutableStateOf(promise.toWhom)
    var publicity by mutableStateOf(promise.level)
    var origin by mutableStateOf(ua.mandat.data.PromiseOrigin.of(promise.origin))
    var risk by mutableStateOf(promise.risk)
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
            what = text, personId = personId, toWhom = if (personId != null) "" else toWhom.trim().take(300),
            isPublic = publicity == ua.mandat.data.Publicity.PUBLIC, publicity = publicity.name, origin = origin?.name, risk = risk?.coerceIn(1, 3),
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
    /** Something was typed or chosen since the sheet opened: closing it asks before throwing that away. */
    fun changed(): Boolean = title != deadline.title || basis != deadline.basis || day != deadline.day || matterId != deadline.matterId

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

/**
 * Saves today's answer to the evening question (one entry per day). With [append] (tonight's entry
 * was hidden when typing began) the text goes under what is there, so nothing hidden is overwritten.
 */
suspend fun persistReview(db: MandatDb, text: String?, append: Boolean = false) {
    val clean = text?.trim()?.take(ua.mandat.data.MAX_NOTE_LENGTH)?.ifEmpty { null } ?: return
    val today = Dates.today()
    val existing = db.reviews().forDay(today)
    if (existing == null) {
        db.reviews().insert(ua.mandat.data.Review(day = today, progress = clean, createdAt = System.currentTimeMillis()))
    } else {
        val joined = if (append && existing.progress.isNotBlank()) (existing.progress.trimEnd() + "\n\n" + clean).take(ua.mandat.data.MAX_NOTE_LENGTH) else clean
        db.reviews().update(existing.copy(progress = joined))
    }
}

/** An event being edited in its dialog; the people taking part are saved with it. */
class EventDraft(val event: ua.mandat.data.Event, val isNew: Boolean, people: Set<Long> = emptySet(), val communityId: Long? = null) {
    /** Something was typed or chosen since the sheet opened: closing it asks before throwing that away. */
    fun changed(): Boolean = title != event.title || notes != event.notes || outcome != event.outcome || place != event.place || day != event.day || startMin != event.startMin || matterId != event.matterId

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
            communityId?.let { db.communities().link(ua.mandat.data.CommunityLink(it, ua.mandat.data.LinkKind.EVENT, id)) }
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

/** An address-book entry being edited; saved like the other drafts if Mandat locks meanwhile. */
class ContactDraft(val contact: ua.mandat.data.Contact) {
    val isNew get() = contact.id == 0L

    /** Something was typed; a new entry that was only looked at is not saved on lock. */
    val touched get() = name != contact.name || phones != contact.phones || emails != contact.emails ||
        organization != contact.organization || jobTitle != contact.jobTitle || label != contact.label || notes != contact.notes
    var name by mutableStateOf(contact.name)
    var phones by mutableStateOf(contact.phones)
    var emails by mutableStateOf(contact.emails)
    var organization by mutableStateOf(contact.organization)
    var jobTitle by mutableStateOf(contact.jobTitle)
    var label by mutableStateOf(contact.label)
    var notes by mutableStateOf(contact.notes)

    fun edited(): ua.mandat.data.Contact? = ua.mandat.data.cleanContact(
        contact.copy(
            name = name, phones = phones, emails = emails, organization = organization, jobTitle = jobTitle, label = label, notes = notes,
            createdAt = if (isNew) System.currentTimeMillis() else contact.createdAt,
        ),
    )

    suspend fun persist(db: MandatDb) {
        val e = edited() ?: return
        if (isNew) db.contacts().insert(e.copy(id = 0)) else db.contacts().update(e)
    }
}

/** A media or information item being edited. */
class InfoDraft(val item: ua.mandat.data.InfoItem, val isNew: Boolean) {
    /** Something was typed or chosen since the sheet opened: closing it asks before throwing that away. */
    fun changed(): Boolean = title != item.title || notes != item.notes || result != item.result || source != item.source || matterId != item.matterId

    var kind by mutableStateOf(ua.mandat.data.InfoKind.of(item.kind))
    var title by mutableStateOf(item.title)
    var day by mutableStateOf(item.day)
    var source by mutableStateOf(item.source)
    var personId by mutableStateOf(item.personId)
    var matterId by mutableStateOf(item.matterId)
    var reach by mutableStateOf(item.reach)
    var stage by mutableStateOf(ua.mandat.data.AttackStage.of(item.stage))
    var decision by mutableStateOf(ua.mandat.data.AttackDecision.of(item.decision))
    var notes by mutableStateOf(item.notes)
    var result by mutableStateOf(item.result)
    var checks by mutableStateOf(ua.mandat.data.checksOf(item.checks))
    var closed by mutableStateOf(item.closed)

    suspend fun persist(db: MandatDb) {
        val now = System.currentTimeMillis()
        val edited = ua.mandat.data.cleanInfo(
            item.copy(
                kind = kind.name, title = title, day = day, source = source, personId = personId, matterId = matterId, reach = reach,
                stage = stage.name, decision = decision?.name, notes = notes, result = result,
                checks = ua.mandat.data.checksText(checks), closed = closed, updatedAt = now,
            ),
        ) ?: return
        if (isNew) db.info().insert(edited.copy(id = 0, createdAt = now)) else db.info().update(edited)
    }
}

fun newInfo(kind: ua.mandat.data.InfoKind = ua.mandat.data.InfoKind.DISCUSSION, matterId: Long? = null) = InfoDraft(
    ua.mandat.data.InfoItem(kind = kind.name, title = "", day = Dates.today(), matterId = matterId, createdAt = 0, updatedAt = 0),
    isNew = true,
)

/** A key message being edited. */
class MessageDraft(val message: ua.mandat.data.KeyMessage, val isNew: Boolean) {
    /** Something was typed or chosen since the sheet opened: closing it asks before throwing that away. */
    fun changed(): Boolean = text != message.text || proof != message.proof || audience != message.audience || matterId != message.matterId

    var text by mutableStateOf(message.text)
    var proof by mutableStateOf(message.proof)
    var audience by mutableStateOf(message.audience)
    var matterId by mutableStateOf(message.matterId)

    suspend fun persist(db: MandatDb) {
        val now = System.currentTimeMillis()
        val edited = ua.mandat.data.cleanMessage(message.copy(text = text, proof = proof, audience = audience, matterId = matterId, updatedAt = now)) ?: return
        if (isNew) db.info().insertMessage(edited.copy(id = 0, createdAt = now)) else db.info().updateMessage(edited)
    }
}

fun newMessage(matterId: Long? = null) = MessageDraft(ua.mandat.data.KeyMessage(text = "", matterId = matterId, createdAt = 0, updatedAt = 0), isNew = true)

/** A situational alliance being edited. */
class AllianceDraft(val alliance: ua.mandat.data.Alliance, val isNew: Boolean) {
    /** Something was typed or chosen since the sheet opened: closing it asks before throwing that away. */
    fun changed(): Boolean = withWhom != alliance.withWhom || about != alliance.about || gives != alliance.gives || gets != alliance.gets || notes != alliance.notes || matterId != alliance.matterId

    var withWhom by mutableStateOf(alliance.withWhom)
    var personId by mutableStateOf(alliance.personId)
    var about by mutableStateOf(alliance.about)
    var matterId by mutableStateOf(alliance.matterId)
    var untilDay by mutableStateOf(alliance.untilDay)
    var gives by mutableStateOf(alliance.gives)
    var gets by mutableStateOf(alliance.gets)
    var status by mutableStateOf(ua.mandat.data.AllianceStatus.of(alliance.status))
    var notes by mutableStateOf(alliance.notes)

    fun valid() = withWhom.isNotBlank() && about.isNotBlank()

    suspend fun persist(db: MandatDb) {
        val now = System.currentTimeMillis()
        val edited = ua.mandat.data.cleanAlliance(
            alliance.copy(
                withWhom = withWhom, personId = personId, about = about, matterId = matterId, untilDay = untilDay,
                gives = gives, gets = gets, status = status.name, notes = notes, updatedAt = now,
            ),
        ) ?: return
        if (isNew) db.info().insertAlliance(edited.copy(id = 0, createdAt = now)) else db.info().updateAlliance(edited)
    }
}

fun newAlliance(matterId: Long? = null) = AllianceDraft(
    ua.mandat.data.Alliance(withWhom = "", about = "", matterId = matterId, status = ua.mandat.data.AllianceStatus.ACTIVE.name, createdAt = 0, updatedAt = 0),
    isNew = true,
)

/** A money entry being edited: the amount is kept as typed until it is saved. */
class MoneyDraft(val entry: ua.mandat.data.MoneyEntry, val isNew: Boolean) {
    /** Something was typed or chosen since the sheet opened: closing it asks before throwing that away. */
    fun changed(): Boolean = note != entry.note || category != entry.category || toWhom != entry.toWhom || matterId != entry.matterId || (amountText.isNotBlank() && amountText != (if (entry.amount > 0) ua.mandat.data.formatAmount(entry.amount).replace(" ", "") else ""))

    var kind by mutableStateOf(entry.kind)
    var day by mutableStateOf(entry.day)
    var category by mutableStateOf(entry.category)
    var amountText by mutableStateOf(if (entry.amount > 0) ua.mandat.data.formatAmount(entry.amount).replace(" ", "") else "")
    var currency by mutableStateOf(entry.currency)
    /** Hryvnias for one unit of the currency, as typed. */
    var rateText by mutableStateOf(
        if (entry.currency != "UAH" && entry.amount > 0) "%.4f".format(java.util.Locale.ROOT, entry.uah.toDouble() / entry.amount).trimEnd('0').trimEnd('.') else "",
    )
    var note by mutableStateOf(entry.note)
    var matterId by mutableStateOf(entry.matterId)
    var charityKind by mutableStateOf(ua.mandat.data.CharityKind.of(entry.charityKind))
    var toWhom by mutableStateOf(entry.toWhom)
    var awaitingRefund by mutableStateOf(entry.awaitingRefund)
    var refunded by mutableStateOf(entry.refunded)

    fun rate(): Double? = rateText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it < 10_000 }
    fun amount(): Long? = ua.mandat.data.parseAmount(amountText)
    fun valid() = amount() != null && category.isNotBlank() && currency.matches(Regex("[A-Z]{3}")) && (currency == "UAH" || rate() != null)

    suspend fun persist(db: MandatDb) {
        val a = amount() ?: return
        if (!currency.matches(Regex("[A-Z]{3}")) || (currency != "UAH" && rate() == null)) return
        val now = System.currentTimeMillis()
        val edited = ua.mandat.data.cleanMoney(
            entry.copy(
                kind = kind, day = day, category = category, amount = a, currency = currency, uah = ua.mandat.data.toUah(a, currency, rate()),
                note = note, matterId = matterId, charityKind = charityKind?.name, toWhom = toWhom,
                awaitingRefund = awaitingRefund, refunded = refunded, updatedAt = now,
            ),
        ) ?: return
        if (isNew) db.money().insert(edited.copy(id = 0, createdAt = now)) else db.money().update(edited)
    }
}

fun newMoney(kind: ua.mandat.data.MoneyKind, category: String? = null) = MoneyDraft(
    ua.mandat.data.MoneyEntry(
        day = Dates.today(), kind = kind.name,
        category = category ?: (if (kind == ua.mandat.data.MoneyKind.INCOME) ua.mandat.data.IncomeCategory.SALARY.name else ua.mandat.data.ExpenseCategory.HOME.name),
        amount = 0, currency = "UAH", uah = 0, createdAt = 0, updatedAt = 0,
    ),
    isNew = true,
)

/** A community being edited. */
class CommunityDraft(val community: ua.mandat.data.Community, val isNew: Boolean) {
    /** Something was typed or chosen since the sheet opened: closing it asks before throwing that away. */
    fun changed(): Boolean = name != community.name || head != community.head || notes != community.notes || population != community.population?.toString().orEmpty()

    var name by mutableStateOf(community.name)
    var kind by mutableStateOf(ua.mandat.data.CommunityKind.of(community.kind))
    var population by mutableStateOf(community.population?.toString().orEmpty())
    var head by mutableStateOf(community.head)
    var notes by mutableStateOf(community.notes)

    suspend fun persist(db: MandatDb) {
        val now = System.currentTimeMillis()
        val edited = ua.mandat.data.cleanCommunity(
            community.copy(name = name, kind = kind.name, population = population.filter { it.isDigit() }.take(9).toIntOrNull(), head = head, notes = notes, updatedAt = now),
        ) ?: return
        if (isNew) db.communities().insert(edited.copy(id = 0, createdAt = now)) else db.communities().update(edited)
    }
}

fun newCommunity() = CommunityDraft(
    ua.mandat.data.Community(name = "", kind = ua.mandat.data.CommunityKind.VILLAGE.name, createdAt = 0, updatedAt = 0),
    isNew = true,
)

/**
 * A text being written. Saving with a changed body keeps the previous body as a version (the
 * newest [ua.mandat.data.TEXT_VERSIONS_KEPT]), so nothing written is lost by an edit.
 */
class TextDraft(val doc: ua.mandat.data.TextDoc, val isNew: Boolean) {
    /** Something was typed or chosen since the sheet opened: closing it asks before throwing that away. */
    fun changed(): Boolean = title != doc.title || body != doc.body || kind.name != doc.kind || matterId != doc.matterId

    var title by mutableStateOf(doc.title)
    var kind by mutableStateOf(ua.mandat.data.TextKind.of(doc.kind))
    var body by mutableStateOf(doc.body)
    var matterId by mutableStateOf(doc.matterId)

    fun valid() = title.isNotBlank()

    suspend fun persist(db: MandatDb): Long? {
        val now = System.currentTimeMillis()
        val edited = ua.mandat.data.cleanText(doc.copy(title = title, kind = kind.name, body = body, matterId = matterId, updatedAt = now)) ?: return null
        return db.withTransaction {
            if (isNew) db.texts().insert(edited.copy(id = 0, createdAt = now))
            else {
                val saved = db.texts().get(edited.id)
                if (saved != null && saved.body != edited.body && saved.body.isNotBlank()) {
                    db.texts().insertVersion(ua.mandat.data.TextVersion(textId = edited.id, body = saved.body, savedAt = saved.updatedAt))
                    db.texts().trimVersions(edited.id, ua.mandat.data.TEXT_VERSIONS_KEPT)
                }
                // Deleted meanwhile (or a backup restored): the writing is kept as a new text
                if (saved == null) db.texts().insert(edited.copy(id = 0, createdAt = now))
                else { db.texts().update(edited); edited.id }
            }
        }
    }
}

fun newText(matterId: Long? = null, kind: ua.mandat.data.TextKind = ua.mandat.data.TextKind.OTHER, title: String = "", body: String = "") = TextDraft(
    ua.mandat.data.TextDoc(title = title, kind = kind.name, body = body, matterId = matterId, createdAt = 0, updatedAt = 0), isNew = true,
)
