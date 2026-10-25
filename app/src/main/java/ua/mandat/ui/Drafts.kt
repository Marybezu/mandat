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
