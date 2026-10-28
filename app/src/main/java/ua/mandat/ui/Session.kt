package ua.mandat.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import androidx.room.withTransaction
import ua.mandat.R
import ua.mandat.data.MandatDb
import ua.mandat.data.reminderDays
import ua.mandat.notify.Reminders
import ua.mandat.data.sqlCipherRawKey

/** Touches and typing anywhere (also in sheets and dialogs, which are windows of their own) reach the idle timer here. */
object Idle {
    @Volatile var onTouch: () -> Unit = {}
    fun touch() = onTouch()
}

sealed interface LockState {
    data object NeedsScreenLock : LockState
    data object FirstRun : LockState
    data object Locked : LockState
    data object Opening : LockState
    data object KeyLost : LockState
    data class Open(val db: MandatDb) : LockState
}

/**
 * Holds the open database only while the app is in use; everything else stays encrypted.
 * Survives screen rotation, so the unlock prompt is not shown twice.
 */
class Session(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf<LockState>(LockState.Locked)
        private set

    var lastError by mutableStateOf(false)

    /** The matter open in the editor, if any (memory only; survives turning the phone). */
    var draft by mutableStateOf<MatterDraft?>(null)

    /** The person open in the editor, if any. */
    var personDraft by mutableStateOf<PersonDraft?>(null)

    /** A delegation or promise open in its dialog, if any. */
    var delegationDraft by mutableStateOf<DelegationDraft?>(null)
    var promiseDraft by mutableStateOf<PromiseDraft?>(null)
    var deadlineDraft by mutableStateOf<DeadlineDraft?>(null)
    var eventDraft by mutableStateOf<EventDraft?>(null)
    var contactDraft by mutableStateOf<ContactDraft?>(null)
    var infoDraft by mutableStateOf<InfoDraft?>(null)
    var messageDraft by mutableStateOf<MessageDraft?>(null)
    var allianceDraft by mutableStateOf<AllianceDraft?>(null)
    var moneyDraft by mutableStateOf<MoneyDraft?>(null)
    var communityDraft by mutableStateOf<CommunityDraft?>(null)
    /** The district screen, and the community open in it. */
    var showDistrict by mutableStateOf(false)
    var communityOpen by mutableStateOf<Long?>(null)
    /** The budget and diary screens. */
    var showBudget by mutableStateOf(false)
    var showDiary by mutableStateOf(false)
    /** The media and information screen, and the alliances screen. */
    var showInfo by mutableStateOf(false)
    var showAlliances by mutableStateOf(false)

    /** Today's answer to the evening question while it is being typed; null when not editing. */
    private var reviewDraftText by mutableStateOf<String?>(null)
    var reviewDraft: String?
        get() = reviewDraftText
        set(v) {
            // The day typing began: an entry started before midnight belongs to that evening
            if (v == null) reviewDraftDay = null else if (reviewDraftText == null) reviewDraftDay = Dates.today()
            reviewDraftText = v
        }
    /** The evening the entry being typed belongs to. */
    var reviewDraftDay: Long? = null
    /** Tonight's entry was hidden when typing began: the new text is added under it, never over it. */
    var reviewAppend = false

    /** Material You colours on or off; changing it recolours the app right away. */
    var dynamicColors by mutableStateOf(Appearance.dynamic(app))

    init {
    }
    /** Shown as a visible strip while screenshots are allowed, so the choice is not forgotten. */
    var screenshotsAllowed by mutableStateOf(Appearance.screenshotsAllowed(app))

    /** The "My promises" screen is open. */
    var showPromises by mutableStateOf(false)
    /** The smart list open on its own screen, if any. */
    var smartList by mutableStateOf<SmartList?>(null)
    /** The topics screen, and the topic open in it. */
    var showTopics by mutableStateOf(false)
    /** Sorting out old overdue items, and the evenings of a period (from the dashboard). */
    var showTriage by mutableStateOf(false)
    /** A list behind a dashboard tile: what kind and which period. */
    var periodView by mutableStateOf<Pair<PeriodKind, ua.mandat.data.Span>?>(null)
    /** Looking back over months: directions, past priorities, flow. */
    var showRetro by mutableStateOf(false)
    /** The map of links between matters and people. */
    var showGraph by mutableStateOf(false)
    /** What one role's time went to (0: no role), for a period. */
    var roleTime by mutableStateOf<Pair<Long, ua.mandat.data.Span>?>(null)
    /** A request from elsewhere (for example the dashboard) to show a tab, and a filter for Matters. */
    var tabRequest by mutableStateOf<String?>(null)
    var mattersRequest by mutableStateOf<Pair<Int, String>?>(null)
    /** A delegation just done by someone: offer to thank them. */
    var thankFor by mutableStateOf<ua.mandat.data.Delegation?>(null)
    /** The influence map: null closed, 0 for everyone, otherwise for one matter. */
    var influenceFor by mutableStateOf<Long?>(null)
    /** The one-to-one view for a person in the team. */
    var oneOnOne by mutableStateOf<Long?>(null)
    var topicOpen by mutableStateOf<String?>(null)

    /** What taking in a colleague's packet added, for one message afterwards. */
    var packetResult by mutableStateOf<ua.mandat.data.Packet.Result?>(null)

    /** The search screen is open. */
    var searching by mutableStateOf(false)
    /** A word to search for when the search screen opens (from the word cloud). */
    var searchSeed by mutableStateOf<String?>(null)

    fun saveDeadlineDraft() {
        val d = deadlineDraft ?: return
        deadlineDraft = null
        write(onFail = { if (deadlineDraft == null) deadlineDraft = d }) { d.persist(it) }
    }

    fun saveInfoDraft() {
        val d = infoDraft ?: return
        infoDraft = null
        write(onFail = { if (infoDraft == null) infoDraft = d }) { d.persist(it) }
    }

    fun saveMessageDraft() {
        val d = messageDraft ?: return
        messageDraft = null
        write(onFail = { if (messageDraft == null) messageDraft = d }) { d.persist(it) }
    }

    /** The text being written, and whether the list of texts is open. */
    var textDraft by mutableStateOf<TextDraft?>(null)
    var showTexts by mutableStateOf(false)
    /** Pasted text being split by the on-device AI. */
    var showAiSplit by mutableStateOf(false)
    var aiSplitText by mutableStateOf("")
    /** The AI Center, open over the tab named here (null: closed). */
    var aiCenter by mutableStateOf<String?>(null)
    /** Sorting the Inbox with the AI. */
    var showInboxSort by mutableStateOf(false)
    /** The text editor opens with its AI help menu shown. */
    var aiTextHelp by mutableStateOf(false)
    /** A request from elsewhere (the AI Center) to open the settings. */
    var optionsRequest by mutableStateOf(false)
    /** A text to export: Export opens with it chosen. */
    var exportText by mutableStateOf<Long?>(null)
    /** A person whose brief to export. */
    var exportBrief by mutableStateOf<Long?>(null)

    fun saveTextDraft() {
        val d = textDraft ?: return
        textDraft = null
        write(onFail = { if (textDraft == null) textDraft = d }) { d.persist(it) }
    }

    fun saveCommunityDraft() {
        val d = communityDraft ?: return
        communityDraft = null
        write(onFail = { if (communityDraft == null) communityDraft = d }) { d.persist(it) }
    }

    fun saveMoneyDraft() {
        val d = moneyDraft ?: return
        moneyDraft = null
        write(onFail = { if (moneyDraft == null) moneyDraft = d }) { d.persist(it) }
    }

    fun saveAllianceDraft() {
        val d = allianceDraft ?: return
        allianceDraft = null
        write(onFail = { if (allianceDraft == null) allianceDraft = d }) { d.persist(it) }
    }

    /** Opens a media item or an alliance by id (from search). */
    fun openInfoItem(kind: String, id: Long) {
        val startedAt = generation
        background.launch {
            val found: Any? = withOpenDb { db -> if (kind == "alliance") db.info().getAlliance(id) else db.info().get(id) }
            withContext(Dispatchers.Main) {
                if (startedAt != generation || state !is LockState.Open) return@withContext
                when (found) {
                    is ua.mandat.data.InfoItem -> if (!isHidden(found.matterId) && !hidden.value.itemAt(found.matterId, found.updatedAt, found.title, found.source, found.notes, found.result)) infoDraft = InfoDraft(found, isNew = false)
                    is ua.mandat.data.Alliance -> if (!isHidden(found.matterId) && !hidden.value.itemAt(found.matterId, found.updatedAt, found.withWhom, found.about, found.gives, found.gets, found.notes)) allianceDraft = AllianceDraft(found, isNew = false)
                }
            }
        }
    }

    fun saveEventDraft() {
        val d = eventDraft ?: return
        eventDraft = null
        write(onFail = { if (eventDraft == null) eventDraft = d }) { d.persist(it) }
    }

    /** Opens an event in its dialog, with the people taking part. */
    fun openEvent(id: Long) {
        val startedAt = generation
        background.launch {
            val found = withOpenDb { db -> db.events().get(id)?.let { it to db.events().peopleOf(id).toSet() } }
            withContext(Dispatchers.Main) {
                if (found != null && startedAt == generation && state is LockState.Open && !isHidden(found.first.matterId) &&
                    !found.first.let { e -> hidden.value.itemAt(e.matterId, e.updatedAt, e.title, e.place, e.notes, e.outcome) }) eventDraft = EventDraft(found.first, isNew = false, found.second)
            }
        }
    }

    /** Matters kept off the screen in trip mode (ids only); worked out before the screens open. */
    val hiddenMatters = kotlinx.coroutines.flow.MutableStateFlow<Set<Long>>(emptySet())
    /** Everything hidden along with those matters (people, diary days, communities, titles); set together with them. */
    val hidden = kotlinx.coroutines.flow.MutableStateFlow(Hidden.NONE)
    /** Trip mode is on (also keeps the phone's calendar events off the screen). */
    val tripOn = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** The lens: look at everything through one role (0 = matters without a role), or null for all. */
    val lensRole = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    private suspend fun computeHidden(db: MandatDb): Pair<Boolean, Hidden> {
        val on = db.settings().get(ua.mandat.ui.SETTING_TRIP)?.value == "1"
        val trip = if (on) db.matters().sensitiveIds().toSet() else emptySet()
        val lens = lensRole.value?.let { db.matters().outsideRole(it).toSet() } ?: emptySet()
        val since = tripSince(db.settings().get(SETTING_TRIP_SINCE)?.value)
        return on to observeHidden(db, trip + lens, byName = trip, since = since).first()
    }

    /** Sets what is hidden: the matters and everything worked out from them, in one step. */
    fun setHidden(on: Boolean, h: Hidden) {
        tripOn.value = on
        hidden.value = h
        hiddenMatters.value = h.matters
    }

    /** True when a record tied to [matterId] must not open now (hidden by trip mode or the lens). */
    private fun isHidden(matterId: Long?): Boolean = hidden.value.matter(matterId) || (matterId != null && matterId in hiddenMatters.value)

    /** Sorting out people without a category. */
    var showCategorize by mutableStateOf(false)

    /** The tab shown, kept here so it survives locking. */
    var lastTab by mutableStateOf<String?>(null)

    /** Where the owner was when Mandat locked: numbers only. Used once, within a quarter of an hour. */
    class Resume(val at: Long, val matterId: Long?, val personId: Long?, val eventId: Long?, val contactId: Long?, val directory: Boolean, val task: Pair<String, Long>?, val screens: Screens)
    private var resume: Resume? = null

    /** Which full screens were open (flags and ids, never text); reopened only within the resume window. */
    class Screens(
        val diary: Boolean, val retro: Boolean, val budget: Boolean, val info: Boolean, val texts: Boolean,
        val triage: Boolean, val topics: Boolean, val promises: Boolean, val district: Boolean, val alliances: Boolean,
        val graph: Boolean, val categorize: Boolean, val export: Boolean, val oneOnOne: Long?, val smartList: SmartList?,
        val periodView: Pair<PeriodKind, ua.mandat.data.Span>?, val roleTime: Pair<Long, ua.mandat.data.Span>?,
        val influenceFor: Long?, val communityOpen: Long?,
    )

    /** Notes which full screens are open and closes them all (on lock). */
    private fun closeScreens(): Screens {
        val sc = Screens(
            showDiary, showRetro, showBudget, showInfo, showTexts, showTriage, showTopics, showPromises, showDistrict, showAlliances,
            showGraph, showCategorize, showExport, oneOnOne, smartList, periodView, roleTime, influenceFor, communityOpen,
        )
        showDiary = false; showRetro = false; showBudget = false; showInfo = false; showTexts = false
        showTriage = false; showTopics = false; showPromises = false; showDistrict = false; showAlliances = false
        showGraph = false; showCategorize = false; showExport = false; oneOnOne = null; smartList = null
        periodView = null; roleTime = null; influenceFor = null; communityOpen = null
        // A topic's name is content: never kept while locked
        topicOpen = null
        packetResult = null
        exportText = null; exportBrief = null
        tabRequest = null; mattersRequest = null; optionsRequest = false
        return sc
    }

    private fun reopenScreens(sc: Screens) {
        val h = hidden.value
        showDiary = sc.diary; showRetro = sc.retro; showBudget = sc.budget; showInfo = sc.info; showTexts = sc.texts
        showTriage = sc.triage; showTopics = sc.topics; showPromises = sc.promises; showDistrict = sc.district; showAlliances = sc.alliances
        showGraph = sc.graph; showCategorize = sc.categorize; showExport = sc.export
        oneOnOne = sc.oneOnOne?.takeIf { it !in h.people }
        smartList = sc.smartList; periodView = sc.periodView; roleTime = sc.roleTime
        influenceFor = sc.influenceFor?.takeIf { it == 0L || it !in h.matters }
        communityOpen = sc.communityOpen?.takeIf { it !in h.communities }
    }

    private fun applyResume() {
        val r = resume ?: return
        resume = null
        if (System.currentTimeMillis() - r.at > RESUME_WINDOW_MS) {
            lastTab = null
            return
        }
        reopenScreens(r.screens)
        if (r.directory) showDirectory = true
        r.matterId?.let { openMatter(it) }
        r.personId?.let { openPerson(it) }
        r.eventId?.let { openEvent(it) }
        r.task?.let { (kind, id) -> openTask(kind, id) }
        r.contactId?.let { id ->
            val startedAt = generation
            background.launch {
                val c = withOpenDb { db -> db.contacts().get(id) }
                withContext(Dispatchers.Main) {
                    if (c != null && startedAt == generation && state is LockState.Open) contactDraft = ContactDraft(c)
                }
            }
        }
    }

    /** The address book screen is open. */
    var showDirectory by mutableStateOf(false)

    /** A .vcf file read and waiting for the owner's choice (memory only; dropped on lock). */
    var vcfImport by mutableStateOf<VcfImport?>(null)
    /** Reading a .vcf file is under way, or it failed (a message key). */
    var vcfBusy by mutableStateOf(false)
    var vcfError by mutableStateOf<Int?>(null)
    /** After an import: how many were added, and the file to offer for deletion. */
    var vcfDone by mutableStateOf<Pair<Int, android.net.Uri?>?>(null)

    /** Reads a .vcf file chosen by the owner, leaving out entries already known. Nothing is saved yet. */
    fun readVcf(uri: android.net.Uri) {
        val startedAt = generation
        vcfBusy = true
        vcfError = null
        background.launch {
            val app = getApplication<Application>()
            val result: Any = try {
                val size = app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                if (size > MAX_VCF_BYTES) R.string.vcf_too_big
                else app.contentResolver.openInputStream(uri)?.use { input ->
                    // Never more than the limit, even if the size was not known in advance
                    val limited = object : java.io.FilterInputStream(input) {
                        var count = 0L
                        override fun read(): Int = super.read().also { if (it >= 0 && ++count > MAX_VCF_BYTES) throw java.io.IOException() }
                        override fun read(b: ByteArray, off: Int, len: Int): Int =
                            super.read(b, off, len).also { if (it > 0) { count += it; if (count > MAX_VCF_BYTES) throw java.io.IOException() } }
                    }
                    ua.mandat.data.VCard.read(limited.bufferedReader(Charsets.UTF_8), stop = { startedAt != generation })
                } ?: R.string.vcf_failed
            } catch (e: Exception) {
                R.string.vcf_failed
            } catch (e: OutOfMemoryError) {
                R.string.vcf_too_big
            }
            val plan = (result as? ua.mandat.data.VCard.Result)?.let { r ->
                withOpenDb { db -> ua.mandat.data.planImport(r.contacts, shownContacts(db), shownPeople(db)) }?.let { it to r }
            }
            withContext(Dispatchers.Main) {
                if (startedAt != generation || state !is LockState.Open) return@withContext
                vcfBusy = false
                when {
                    result is Int -> vcfError = result
                    plan == null -> vcfError = R.string.vcf_failed
                    plan.second.contacts.isEmpty() -> vcfError = R.string.vcf_empty
                    else -> vcfImport = VcfImport(uri, plan.first, plan.second.tooMany)
                }
            }
        }
    }

    /** Export and import screen. */
    var showExport by mutableStateOf(false)

    /** Contacts read from another file (CSV) go through the same preview as a .vcf. */
    fun previewContacts(list: List<ua.mandat.data.Contact>, source: android.net.Uri?) {
        val startedAt = generation
        background.launch {
            val plan = withOpenDb { db -> ua.mandat.data.planImport(list, shownContacts(db), shownPeople(db)) }
            withContext(Dispatchers.Main) {
                if (plan == null || startedAt != generation || state !is LockState.Open) return@withContext
                vcfImport = VcfImport(source, plan, list.size >= ua.mandat.data.VCard.MAX_CONTACTS)
                showExport = false
                showDirectory = true
            }
        }
    }

    /** Saves the chosen entries in one transaction, with the group given by the owner. */
    fun importVcf(chosen: List<ua.mandat.data.Contact>, label: String) {
        val job = vcfImport ?: return
        vcfImport = null
        val clean = label.trim().take(100)
        write { db ->
            db.withTransaction {
                chosen.chunked(500).forEach { part -> db.contacts().insertAll(part.map { it.copy(label = clean) }) }
            }
            withContext(Dispatchers.Main) { vcfDone = chosen.size to job.uri }
        }
    }

    fun saveReviewDraft() {
        val text = reviewDraft
        val append = reviewAppend
        val day = reviewDraftDay
        reviewDraft = null
        reviewAppend = false
        write(onFail = { if (reviewDraft == null) { reviewDraft = text; reviewDraftDay = day; reviewAppend = append } }) { persistReview(it, text, append, day) }
    }

    /** Recomputes the reminder days now (for example right after reminders are switched on). */
    fun refreshReminders() {
        background.launch {
            withOpenDb { db -> storeReminderDays(db) }
        }
    }

    private suspend fun storeReminderDays(db: MandatDb, tripNow: Boolean = tripOn.value) {
        if (!Reminders.isOn(getApplication())) return
        val today = Dates.today()
        // What trip mode hides gives no reminder either: a reminder with nothing to show would tell.
        // Only trip mode counts here, never the lens: looking through one role keeps the others' reminders
        val sensitive = if (tripNow) db.matters().sensitiveIds().toSet() else emptySet()
        val names = if (sensitive.isEmpty()) ua.mandat.data.HiddenNames.NONE
            else ua.mandat.data.HiddenNames(db.matters().observeAllTitles().first().filter { it.id in sensitive }.map { it.title })
        val h = Hidden(sensitive, emptySet(), emptySet(), emptySet(), names)
        val days = reminderDays(
            db.deadlines().open().filterNot { h.item(it.matterId, it.title, it.basis) },
            db.delegations().open().filterNot { h.item(it.matterId, it.what, it.purpose) },
            db.promises().open().filterNot { h.item(it.matterId, it.what, it.toWhom) },
            today,
            db.events().withReminder(today).filterNot { h.item(it.matterId, it.title, it.notes) },
        )
        Reminders.storeDays(getApplication(), days)
    }

    fun savePersonDraft() {
        val d = personDraft ?: return
        personDraft = null
        write(onFail = { if (personDraft == null) personDraft = d }) { d.persist(it) }
    }

    fun saveDelegationDraft() {
        val d = delegationDraft ?: return
        delegationDraft = null
        write(onFail = { if (delegationDraft == null) delegationDraft = d }) { d.persist(it) }
    }

    fun savePromiseDraft() {
        val d = promiseDraft ?: return
        promiseDraft = null
        write(onFail = { if (promiseDraft == null) promiseDraft = d }) { d.persist(it) }
    }

    /** Reads under the write lock and shows the result on the main thread, unless Mandat was locked meanwhile. */
    fun <T : Any> readThenShow(read: suspend (MandatDb) -> T?, show: (T) -> Unit) {
        val startedAt = generation
        background.launch {
            val v = withOpenDb(read)
            withContext(Dispatchers.Main) { if (v != null && startedAt == generation && state is LockState.Open) show(v) }
        }
    }

    /** Opens a matter by id in the editor (read under the write lock, shown on the main thread). */
    fun openMatter(id: Long, nextStep: String? = null) {
        // Following a link from another editor: what was typed there is saved first
        saveOpenEditors()
        val startedAt = generation
        background.launch {
            val m = withOpenDb { db -> db.matters().get(id) }
            withContext(Dispatchers.Main) {
                // Locked meanwhile: do not bring decrypted data back into memory
                // Hidden (trip mode, lens): never opened, whatever link led here
                if (m != null && startedAt == generation && state is LockState.Open && !isHidden(m.id)) draft = MatterDraft(EditRequest(m, isNew = false)).also { d ->
                    // A suggested next step is put in the field, like typing: it is saved with the matter (also on lock)
                    if (!nextStep.isNullOrBlank()) d.nextStep = nextStep
                }
            }
        }
    }

    /** Opens a delegation, promise or deadline by id in its dialog. */
    fun openTask(kind: String, id: Long) {
        val startedAt = generation
        background.launch {
            val item: Any? = withOpenDb { db ->
                when (kind) {
                    "delegation" -> db.delegations().get(id)
                    "promise" -> db.promises().get(id)
                    "deadline" -> db.deadlines().get(id)
                    else -> null
                }
            }
            withContext(Dispatchers.Main) {
                if (startedAt != generation || state !is LockState.Open) return@withContext
                when (item) {
                    // Not tied to a hidden matter, and not naming one either (any link leads to the same check)
                    is ua.mandat.data.Delegation -> if (!isHidden(item.matterId) && !hidden.value.itemAt(item.matterId, item.createdAt, item.what, item.purpose, item.doneMeans)) delegationDraft = DelegationDraft(item, isNew = false)
                    is ua.mandat.data.Promise -> if (!isHidden(item.matterId) && !hidden.value.itemAt(item.matterId, item.createdAt, item.what, item.toWhom, item.source, item.statusNote)) promiseDraft = PromiseDraft(item, isNew = false)
                    is ua.mandat.data.Deadline -> if (!isHidden(item.matterId) && !hidden.value.itemAt(item.matterId, item.createdAt, item.title, item.basis)) deadlineDraft = DeadlineDraft(item, isNew = false)
                }
            }
        }
    }

    /** Saves an open matter or person editor before another one opens (like leaving the app does). */
    private fun saveOpenEditors() {
        if (draft != null) saveDraft()
        if (personDraft != null) savePersonDraft()
    }

    /** Opens a person by id in the editor. */
    fun openPerson(id: Long) {
        saveOpenEditors()
        val startedAt = generation
        background.launch {
            val p = withOpenDb { db -> db.people().get(id) }
            withContext(Dispatchers.Main) {
                if (p != null && startedAt == generation && state is LockState.Open && !hidden.value.person(p)) personDraft = PersonDraft(p, isNew = false)
            }
        }
    }

    /** The text of an open "add to Inbox" dialog; null when the dialog is closed. */
    var inboxDraft by mutableStateOf<String?>(null)

    /** Saves the open matter and closes the editor. */
    fun saveDraft() {
        val d = draft ?: return
        draft = null
        write(onFail = { if (draft == null) draft = d }) { d.persist(it) }
    }

    /** Saves the open Inbox note and closes the dialog. */
    fun saveInboxDraft() {
        val text = inboxDraft
        inboxDraft = null
        write(onFail = { if (inboxDraft == null) inboxDraft = text }) { persistInboxDraft(it, text) }
    }

    /** Text shared from another app, waiting for the owner to confirm adding it to the Inbox. */
    var sharedText by mutableStateOf<String?>(null)

    /** A phone-lock prompt (unlock, or a confirmation) is on screen now. */
    var prompting = false

    /** What that prompt is for; its answer goes there. */
    var purpose: PromptPurpose = PromptPurpose.Unlock

    /** How the last "PIN only" switch ended (true done, false not done), for one message. */
    var pinOnlyResult by mutableStateOf<Boolean?>(null)

    /** The 32 raw key bytes, only while the owner's own database is open (to wrap them again). The caller clears them. */
    fun rawKeyForRewrap(): ByteArray? {
        if (state !is LockState.Open) return null
        val k = key ?: return null
        return try { ua.mandat.data.rawFromSqlCipherKey(k) } catch (e: Exception) { null }
    }

    /** The prompt on screen sets up a new key (first run) rather than opening the existing one. */
    var pendingSetup = false

    /** The lock generation when the current prompt was shown. */
    var promptGeneration = 0

    /**
     * The system file picker is on screen (to save or open a backup). Leaving for it must not
     * lock Mandat, or the backup could not be made. Cleared as soon as the picker returns.
     */
    var pickingFile: Boolean
        get() = picking
        set(value) = hold(value, PICKER_TIMEOUT_MS)

    /** For a phone-lock prompt: typing a long password may take longer than picking a file. */
    fun holdForPrompt() = hold(true, PROMPT_TIMEOUT_MS)

    private fun hold(value: Boolean, timeout: Long) {
            picking = value
            pickerTimeout?.cancel()
            // Safety net: if the owner never comes back from the picker, lock anyway
            if (value) pickerTimeout = background.launch {
                delay(timeout)
                withContext(Dispatchers.Main) {
                    if (picking) {
                        picking = false
                        if (lock()) autoPrompt = true
                    }
                }
            }
        }
    private var picking = false
    private var pickerTimeout: Job? = null

    /** Show the unlock prompt by itself once after opening the app, not again after a cancel. */
    var autoPrompt = true

    // Every lock starts a new generation; a database that finishes opening for an old
    // generation (the owner already left the app) is closed straight away.
    @Volatile private var generation = 0
    private val writes = Mutex()
    /** The automatic copy being written (or written last), shared by closing and "copy now". */
    @Volatile private var autoJob: kotlinx.coroutines.Deferred<ua.mandat.backup.AutoBackup.Result>? = null

    /** Writes prepared encrypted bytes to the folder, without the database. */
    fun startAutoWrite(p: ua.mandat.backup.AutoBackup.Prepared, lazy: Boolean = false): kotlinx.coroutines.Deferred<ua.mandat.backup.AutoBackup.Result> =
        background.async(start = if (lazy) kotlinx.coroutines.CoroutineStart.LAZY else kotlinx.coroutines.CoroutineStart.DEFAULT) {
            ua.mandat.backup.AutoBackup.writePrepared(getApplication(), p)
        }.also { autoJob = it }

    /**
     * Records a pending automatic copy. Folder work happens outside the lock and is bounded in
     * time, so a stuck folder never holds the database. Returns true if a copy was accepted,
     * false if it failed, null if it is still being written (tried again next time).
     */
    suspend fun reconcileAuto(db: MandatDb): Boolean? {
        val auto = ua.mandat.backup.AutoBackup
        val app = getApplication<Application>()
        // Old copies that were chosen for removal but not yet removed (Mandat stopped meanwhile)
        val leftovers = writes.withLock { if (db.isOpen) try { auto.toDelete(db) } catch (e: Exception) { null } else null }
        if (!leftovers.isNullOrEmpty()) finishDeleting(db, leftovers)
        val info = writes.withLock { if (db.isOpen) try { auto.pendingInfo(db) } catch (e: Exception) { null } else null } ?: return null
        val job = autoJob
        val result = if (job == null) null else withTimeoutOrNull(AUTO_WAIT_MS) { job.await() }
        if (job != null && result == null) {
            info.key?.fill(0)
            // Still being written: try again next time, unless the folder has not answered for a day
            if (System.currentTimeMillis() - info.createdAt > AUTO_STUCK_MS) {
                writes.withLock { if (db.isOpen) try { auto.record(db, info, null, auto.REASON_STUCK) } catch (e: Exception) { null } }
                return false
            }
            return null
        }
        val checked = withTimeoutOrNull(AUTO_WAIT_MS) {
            background.async { auto.check(app, info, result) }.await()
        } ?: return null
        // The file could not be read just now: leave it pending (never delete a copy for that),
        // but after a day say so, so that automatic backups never stop silently
        if (checked.unknown) {
            if (System.currentTimeMillis() - info.createdAt > AUTO_STUCK_MS) {
                writes.withLock { if (db.isOpen) try { auto.record(db, info, null, auto.REASON_STUCK) } catch (e: Exception) { null } }
                return false
            }
            return null
        }
        val toDelete = writes.withLock {
            if (db.isOpen) try { auto.record(db, info, checked.accepted) } catch (e: Exception) { null } else null
        } ?: return null
        if (toDelete.isNotEmpty()) finishDeleting(db, toDelete)
        return checked.accepted != null
    }

    /** Deletes Mandat's own old copies outside the lock, then forgets them. */
    private fun finishDeleting(db: MandatDb, uris: List<android.net.Uri>) {
        background.launch {
            ua.mandat.backup.AutoBackup.deleteAll(getApplication(), uris)
            writes.withLock { if (db.isOpen) try { ua.mandat.backup.AutoBackup.clearToDelete(db, uris) } catch (e: Exception) { } }
        }
    }
    private var key: ByteArray? = null

    // Not tied to the screen's lifetime: opening and closing always run to the end
    // An unexpected failure in one background step ends that step only, never the whole app
    // (and nothing about the data is logged)
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> })

    val backups = Backups(app, this)

    /** The optional on-device model; its memory is freed on lock and after five idle minutes. */
    val ai = ua.mandat.ai.LocalAi(app, ua.mandat.ai.ModelStore(app), background)

    /** Copying the model file: progress (null when not copying), the last message, and what is installed. */
    var modelImport by mutableStateOf<Float?>(null)
    var modelNote by mutableStateOf<String?>(null)
    var modelState by mutableStateOf(ai.models.state())

    /** Copies a picked model file in the Session's own scope; it goes on until done, also after a lock. */
    fun importModel(uri: android.net.Uri, deleteOriginal: Boolean) {
        if (modelImport != null) return
        modelImport = 0f
        val app = getApplication<Application>()
        background.launch {
            val note = try {
                val r = ai.models.import(uri, deleteOriginal) { p -> modelImport = p }
                when (r.originalDeleted) { true -> app.getString(R.string.ai_original_deleted); false -> app.getString(R.string.ai_original_kept); null -> null }
            } catch (e: Throwable) {
                app.getString(R.string.ai_failed, e.message ?: "")
            }
            withContext(Dispatchers.Main) {
                modelImport = null
                modelNote = note
                modelState = ai.models.state()
            }
        }
    }

    // Turning the screen off always locks Mandat, even while the file picker is open
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                picking = false
                pickerTimeout?.cancel()
                // Mandat's own copy does not wait on the clipboard until the next unlock
                AiClipboard.clearIfOurs(context, screenOff = true)
                if (lock()) autoPrompt = true
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            app,
            screenOff,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun moveTo(newState: LockState) {
        state = newState
    }

    private var idleJob: Job? = null

    /** Work under way that an automatic lock must not cut off (an export being written). */
    @Volatile var longWork = 0

    /**
     * A confirmed step waiting to be run by its screen ("export", "backup"…). Kept here, so a step
     * confirmed while the phone was turned runs on the new screen, not on the one that is gone.
     */
    var confirmed by mutableStateOf<String?>(null)
    init { Idle.onTouch = { android.os.Handler(android.os.Looper.getMainLooper()).post { touched() } } }

    /**
     * A touch on the screen: the idle timer starts again. After the chosen minutes without one,
     * open Mandat locks itself (not while a file is being picked, a prompt is shown or the model is copied).
     */
    fun touched() {
        idleJob?.cancel()
        if (state !is LockState.Open) return
        val minutes = Appearance.autoLockMinutes(getApplication())
        if (minutes <= 0) return
        idleJob = background.launch {
            delay(minutes * 60_000L)
            withContext(Dispatchers.Main) {
                if (state !is LockState.Open) return@withContext
                // Not in the middle of work that the lock would cut off (a copy, an export, an import, an AI answer)
                if (picking || prompting || modelImport != null || longWork > 0 || backups.busy || vcfBusy || ai.busy) touched()
                else {
                    // What Mandat copied leaves the clipboard with the lock, as with the lock button
                    AiClipboard.clearIfOurs(getApplication())
                    if (lock()) autoPrompt = false
                }
            }
        }
    }

    /** The current lock generation; taken before the unlock prompt is shown. */
    fun generationNow(): Int = generation

    /**
     * Opens the database with the key from the unlock prompt. If the owner left the app in the
     * meantime (the generation changed), the database is closed again straight away.
     * Runs in its own scope, so leaving the app can never cancel it halfway and leave it open.
     */
    fun open(secret: ByteArray, startedAt: Int) {
        val passphrase = sqlCipherRawKey(secret)
        secret.fill(0)
        // Only from the lock (or first) screen: never over a database that is already open
        if (startedAt != generation || state is LockState.Open || state == LockState.Opening) {
            passphrase.fill(0)
            return
        }
        state = LockState.Opening
        background.launch {
            val opened = try {
                MandatDb.open(getApplication(), passphrase)
            } catch (e: Throwable) {
                // Any failure (also of the native library) leaves Mandat locked, never crashed
                null
            }
            // Trip mode is applied before any screen shows data, so nothing hidden flashes up.
            // If it cannot be read, Mandat does not open at all rather than show everything.
            var db2 = opened
            val hidden = try {
                opened?.let { computeHidden(it) } ?: (false to Hidden.NONE)
            } catch (e: Throwable) {
                try { opened?.close() } catch (x: Exception) { }
                db2 = null
                false to Hidden.NONE
            }
            val db = db2
            withContext(Dispatchers.Main) {
                when {
                    db == null -> {
                        passphrase.fill(0)
                        if (startedAt == generation) {
                            lastError = true
                            state = LockState.Locked
                        }
                    }
                    startedAt != generation -> background.launch {
                        db.close()
                        passphrase.fill(0)
                    }
                    else -> {
                        // Kept only while open: the database may open extra connections with it
                        key = passphrase
                        lastError = false
                        setHidden(hidden.first, hidden.second)
                        state = LockState.Open(db)
                        // Back in the foreground: Mandat's own copy left on the clipboard is taken off
                        AiClipboard.clearIfOurs(getApplication())
                        applyResume()
                        touched()
                        // Record the automatic copy made when Mandat last closed
                        background.launch { reconcileAuto(db) }
                    }
                }
            }
        }
    }

    /** Work started from a screen that must finish even if the screen closes (on the main thread, like the screen). */
    fun detached(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        background.launch(Dispatchers.Main) { block() }
    }

    /** Runs [block] with the open database, never at the same time as a write or a lock. */
    suspend fun <T> withOpenDb(block: suspend (MandatDb) -> T): T? = writes.withLock {
        val db = (state as? LockState.Open)?.db
        if (db == null || !db.isOpen) null else block(db)
    }

    /**
     * Text typed on a screen outside the drafts (the evening "unload" field, a pasted letter, a new
     * role's name…): the lock saves it where it belongs, or to the Inbox. Main thread only.
     */
    val keptOnLock = LinkedHashMap<String, suspend (MandatDb) -> Unit>()

    // Compared only with what is shown now: a count of "already there" must not give away a hidden entry
    private suspend fun shownPeople(db: MandatDb) = hidden.value.let { h -> db.people().all().filterNot { h.person(it) } }
    private suspend fun shownContacts(db: MandatDb) = hidden.value.let { h ->
        db.contacts().all().filterNot { h.named(it.createdAt, it.name + " " + it.organization + " " + it.jobTitle + " " + it.notes + " " + it.label) }
    }

    /** A save failed: one short notice says so (its content stays in the editor when it can). */
    var notSaved by mutableStateOf(false)

    /**
     * Saves a change in the background; a lock waits until it is written. When it cannot be written,
     * the owner is told, and [onFail] puts the record back into its editor if Mandat is still open.
     */
    fun write(onFail: (() -> Unit)? = null, block: suspend (MandatDb) -> Unit) {
        val db = (state as? LockState.Open)?.db
        if (db == null) {
            if (onFail != null) notSaved = true
            return
        }
        val startedAt = generation
        background.launch {
            val ok = writes.withLock {
                if (!db.isOpen) false
                else try {
                    block(db)
                    true
                } catch (e: Exception) {
                    // Not written; nothing about the content is logged
                    false
                }
            }
            if (!ok) withContext(Dispatchers.Main) {
                notSaved = true
                if (startedAt == generation && state is LockState.Open) onFail?.invoke()
            }
        }
    }

    /** Runs [block] with the owner's database (for backups). */
    suspend fun <T> withRealDb(block: suspend (MandatDb) -> T): T? = withOpenDb(block)

    /** Closes the database and wipes the key until the next unlock. True if it was open or opening. */
    fun lock(): Boolean {
        generation++
        idleJob?.cancel()
        // A step confirmed just before the lock never runs after it without a new confirmation
        confirmed = null
        backups.cancelRestore()
        background.launch { runCatching { ai.stopAndRelease() } }
        // Text pasted for the AI to split is kept in the Inbox, not lost
        val openSplit = aiSplitText.takeIf { showAiSplit && it.isNotBlank() }
        showAiSplit = false
        aiSplitText = ""
        aiCenter = null
        searchSeed = null
        showInboxSort = false
        aiTextHelp = false
        val current = state
        val wasInUse = current is LockState.Open || current == LockState.Opening
        if (wasInUse) state = LockState.Locked
        // Anything still being typed is saved first, in the same step as closing,
        // so leaving the app for a moment never loses it
        val openDraft = draft
        // Full screens close too; they come back only within the resume window
        val screens = closeScreens()
        // Titles of hidden matters are content: only the numbers stay while locked (worked out again on unlock)
        val hiddenAtLock = hidden.value
        val tripAtLock = tripOn.value
        hidden.value = Hidden(hiddenAtLock.matters, hiddenAtLock.people, hiddenAtLock.reviews, hiddenAtLock.communities, ua.mandat.data.HiddenNames.NONE)
        // Where the owner was: only the tab and the numbers of open cards (never their content),
        // so the same card opens again after unlocking
        // Only an open Mandat leaves a resume point; locking again (screen off after leaving) keeps the one there is
        if (current is LockState.Open) resume = Resume(
            at = System.currentTimeMillis(),
            matterId = draft?.request?.takeIf { !it.isNew }?.matter?.id,
            personId = personDraft?.takeIf { !it.isNew }?.person?.id,
            eventId = eventDraft?.takeIf { !it.isNew }?.event?.id,
            contactId = contactDraft?.takeIf { !it.isNew }?.contact?.id,
            directory = showDirectory,
            task = delegationDraft?.takeIf { !it.isNew }?.let { "delegation" to it.delegation.id }
                ?: promiseDraft?.takeIf { !it.isNew }?.let { "promise" to it.promise.id }
                ?: deadlineDraft?.takeIf { !it.isNew }?.let { "deadline" to it.deadline.id },
            screens = screens,
        )
        val openInbox = inboxDraft
        val openPerson = personDraft
        val openDelegation = delegationDraft
        val openPromise = promiseDraft
        val openDeadline = deadlineDraft
        val openEvent = eventDraft
        eventDraft = null
        val openContact = contactDraft
        contactDraft = null
        val openInfo = infoDraft
        val openMessage = messageDraft
        val openAlliance = allianceDraft
        infoDraft = null
        messageDraft = null
        allianceDraft = null
        val openMoney = moneyDraft
        moneyDraft = null
        val openCommunity = communityDraft
        communityDraft = null
        val openText = textDraft
        textDraft = null
        // A .vcf being imported is decrypted personal data: it leaves memory with the lock
        vcfImport = null
        vcfDone = null
        vcfBusy = false
        vcfError = null
        showDirectory = false
        val kept = keptOnLock.values.toList()
        keptOnLock.clear()
        val openReview = reviewDraft
        val openReviewAppend = reviewAppend
        val openReviewDay = reviewDraftDay
        reviewAppend = false
        deadlineDraft = null
        reviewDraft = null
        searching = false
        // Text shared while Mandat was open is content too: it does not wait behind the lock
        // (one shared to a locked Mandat waits for the unlock, as before)
        if (current is LockState.Open) sharedText = null
        thankFor = null
        draft = null
        inboxDraft = null
        personDraft = null
        delegationDraft = null
        promiseDraft = null
        if (current is LockState.Open) {
            val oldKey = key
            key = null
            background.launch {
                var prepared: ua.mandat.backup.AutoBackup.Prepared? = null
                var autoWrite: kotlinx.coroutines.Deferred<ua.mandat.backup.AutoBackup.Result>? = null
                writes.withLock {
                    if (current.db.isOpen) {
                        // Each draft on its own: one failure must not lose the others
                        listOf<suspend () -> Unit>(
                            { openDraft?.persist(current.db) },
                            { persistInboxDraft(current.db, openInbox) },
                            { openPerson?.persist(current.db) },
                            { openDelegation?.persist(current.db) },
                            { openPromise?.persist(current.db) },
                            { openDeadline?.persist(current.db) },
                            { openEvent?.persist(current.db) },
                            { openInfo?.persist(current.db) },
                            { openMessage?.persist(current.db) },
                            { openAlliance?.persist(current.db) },
                            { openMoney?.persist(current.db) },
                            { openCommunity?.persist(current.db) },
                            { openText?.persist(current.db) },
                            { persistInboxDraft(current.db, openSplit) },
                            { openContact?.takeIf { !it.isNew || it.touched }?.persist(current.db) },
                            { persistReview(current.db, openReview, openReviewAppend, openReviewDay) },
                            // Last: only the days on which something is due, for reminders
                            { storeReminderDays(current.db, tripAtLock) },
                        ).forEach { save ->
                            try {
                                save()
                            } catch (e: Exception) {
                                // Not saved; nothing about the content is logged
                            }
                        }
                        kept.forEach { save ->
                            try {
                                save(current.db)
                            } catch (e: Exception) {
                                // Not saved; nothing about the content is logged
                            }
                        }
                        // Automatic backup, if turned on and due: only encrypted here, written after closing
                        prepared = try {
                            ua.mandat.backup.AutoBackup.prepareIfDue(current.db)
                        } catch (e: Throwable) {
                            null
                        }
                        // Registered before the lock is released, so the next unlock always waits for it
                        prepared?.let { autoWrite = startAutoWrite(it, lazy = true) }
                    }
                    // Whatever happens while closing, the database is closed and the key wiped
                    try {
                        if (current.db.isOpen) MandatDb.checkpoint(current.db)
                    } catch (e: Throwable) {
                        // The write-ahead file stays encrypted; it is merged on the next opening
                    } finally {
                        try { current.db.close() } catch (e: Throwable) { }
                        oldKey?.fill(0)
                    }
                }
                // The database is closed: a slow folder can only delay writing encrypted bytes
                autoWrite?.start()
            }
        }
        return wasInUse
    }

    companion object {
        private const val PICKER_TIMEOUT_MS = 2 * 60 * 1000L
        private const val PROMPT_TIMEOUT_MS = 120 * 1000L
        private const val RESUME_WINDOW_MS = 15 * 60 * 1000L
        /** A larger .vcf is refused (6 000 contacts without photos are a few MB; with photos tens of MB). */
        const val MAX_VCF_BYTES = 50L * 1024 * 1024
        private const val AUTO_WAIT_MS = 30 * 1000L
        private const val AUTO_STUCK_MS = 24 * 60 * 60 * 1000L
    }

    override fun onCleared() {
        try {
            getApplication<Application>().unregisterReceiver(screenOff)
        } catch (e: IllegalArgumentException) {
            // Was not registered
        }
        lock()
    }
}
