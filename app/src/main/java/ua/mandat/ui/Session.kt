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
import androidx.room.withTransaction
import ua.mandat.R
import ua.mandat.data.MandatDb
import ua.mandat.data.reminderDays
import ua.mandat.notify.Reminders
import ua.mandat.data.sqlCipherRawKey

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
    var reviewDraft by mutableStateOf<String?>(null)

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

    /** The search screen is open. */
    var searching by mutableStateOf(false)
    /** A word to search for when the search screen opens (from the word cloud). */
    var searchSeed by mutableStateOf<String?>(null)

    fun saveDeadlineDraft() {
        val d = deadlineDraft ?: return
        deadlineDraft = null
        write { d.persist(it) }
    }

    fun saveInfoDraft() {
        val d = infoDraft ?: return
        infoDraft = null
        write { d.persist(it) }
    }

    fun saveMessageDraft() {
        val d = messageDraft ?: return
        messageDraft = null
        write { d.persist(it) }
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
        write { d.persist(it) }
    }

    fun saveCommunityDraft() {
        val d = communityDraft ?: return
        communityDraft = null
        write { d.persist(it) }
    }

    fun saveMoneyDraft() {
        val d = moneyDraft ?: return
        moneyDraft = null
        write { d.persist(it) }
    }

    fun saveAllianceDraft() {
        val d = allianceDraft ?: return
        allianceDraft = null
        write { d.persist(it) }
    }

    /** Opens a media item or an alliance by id (from search). */
    fun openInfoItem(kind: String, id: Long) {
        val startedAt = generation
        background.launch {
            val found: Any? = withOpenDb { db -> if (kind == "alliance") db.info().getAlliance(id) else db.info().get(id) }
            withContext(Dispatchers.Main) {
                if (startedAt != generation || state !is LockState.Open) return@withContext
                when (found) {
                    is ua.mandat.data.InfoItem -> infoDraft = InfoDraft(found, isNew = false)
                    is ua.mandat.data.Alliance -> allianceDraft = AllianceDraft(found, isNew = false)
                }
            }
        }
    }

    fun saveEventDraft() {
        val d = eventDraft ?: return
        eventDraft = null
        write { d.persist(it) }
    }

    /** Opens an event in its dialog, with the people taking part. */
    fun openEvent(id: Long) {
        val startedAt = generation
        background.launch {
            val found = withOpenDb { db -> db.events().get(id)?.let { it to db.events().peopleOf(id).toSet() } }
            withContext(Dispatchers.Main) {
                if (found != null && startedAt == generation && state is LockState.Open) eventDraft = EventDraft(found.first, isNew = false, found.second)
            }
        }
    }

    /** Matters kept off the screen in trip mode (ids only); worked out before the screens open. */
    val hiddenMatters = kotlinx.coroutines.flow.MutableStateFlow<Set<Long>>(emptySet())
    /** Trip mode is on (also keeps the phone's calendar events off the screen). */
    val tripOn = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** The lens: look at everything through one role (0 = matters without a role), or null for all. */
    val lensRole = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    private suspend fun computeHidden(db: MandatDb): Pair<Boolean, Set<Long>> {
        val on = db.settings().get(ua.mandat.ui.SETTING_TRIP)?.value == "1"
        val trip = if (on) db.matters().sensitiveIds().toSet() else emptySet()
        val lens = lensRole.value?.let { db.matters().outsideRole(it).toSet() } ?: emptySet()
        return on to (trip + lens)
    }

    /** Sorting out people without a category. */
    var showCategorize by mutableStateOf(false)

    /** The tab shown, kept here so it survives locking. */
    var lastTab by mutableStateOf<String?>(null)

    /** Where the owner was when Mandat locked: numbers only. Used once, within a quarter of an hour. */
    class Resume(val at: Long, val matterId: Long?, val personId: Long?, val eventId: Long?, val contactId: Long?, val directory: Boolean, val task: Pair<String, Long>?)
    private var resume: Resume? = null

    private fun applyResume() {
        val r = resume ?: return
        resume = null
        if (System.currentTimeMillis() - r.at > RESUME_WINDOW_MS) {
            lastTab = null
            return
        }
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
                withOpenDb { db -> ua.mandat.data.planImport(r.contacts, db.contacts().all(), db.people().all()) }?.let { it to r }
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
            val plan = withOpenDb { db -> ua.mandat.data.planImport(list, db.contacts().all(), db.people().all()) }
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
        reviewDraft = null
        write { persistReview(it, text) }
    }

    /** Recomputes the reminder days now (for example right after reminders are switched on). */
    fun refreshReminders() {
        background.launch {
            withOpenDb { db -> storeReminderDays(db) }
        }
    }

    private suspend fun storeReminderDays(db: MandatDb) {
        if (!Reminders.isOn(getApplication())) return
        val today = Dates.today()
        val days = reminderDays(db.deadlines().open(), db.delegations().open(), db.promises().open(), today, db.events().withReminder(today))
        Reminders.storeDays(getApplication(), days)
    }

    fun savePersonDraft() {
        val d = personDraft ?: return
        personDraft = null
        write { d.persist(it) }
    }

    fun saveDelegationDraft() {
        val d = delegationDraft ?: return
        delegationDraft = null
        write { d.persist(it) }
    }

    fun savePromiseDraft() {
        val d = promiseDraft ?: return
        promiseDraft = null
        write { d.persist(it) }
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
                if (m != null && startedAt == generation && state is LockState.Open) draft = MatterDraft(EditRequest(m, isNew = false)).also { d ->
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
                    is ua.mandat.data.Delegation -> delegationDraft = DelegationDraft(item, isNew = false)
                    is ua.mandat.data.Promise -> promiseDraft = PromiseDraft(item, isNew = false)
                    is ua.mandat.data.Deadline -> deadlineDraft = DeadlineDraft(item, isNew = false)
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
                if (p != null && startedAt == generation && state is LockState.Open) personDraft = PersonDraft(p, isNew = false)
            }
        }
    }

    /** The text of an open "add to Inbox" dialog; null when the dialog is closed. */
    var inboxDraft by mutableStateOf<String?>(null)

    /** Saves the open matter and closes the editor. */
    fun saveDraft() {
        val d = draft ?: return
        draft = null
        write { d.persist(it) }
    }

    /** Saves the open Inbox note and closes the dialog. */
    fun saveInboxDraft() {
        val text = inboxDraft
        inboxDraft = null
        write { persistInboxDraft(it, text) }
    }

    /** Text shared from another app, waiting for the owner to confirm adding it to the Inbox. */
    var sharedText by mutableStateOf<String?>(null)

    /** An unlock prompt is on screen now. */
    var prompting = false

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
        set(value) {
            picking = value
            pickerTimeout?.cancel()
            // Safety net: if the owner never comes back from the picker, lock anyway
            if (value) pickerTimeout = background.launch {
                delay(PICKER_TIMEOUT_MS)
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
        // Never with the demo's database
        if (db === demoDb || demo || demoOpening) return null
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
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val backups = Backups(app, this)

    /** The optional on-device model; its memory is freed on lock and after five idle minutes. */
    val ai = ua.mandat.ai.LocalAi(app, ua.mandat.ai.ModelStore(app), background)

    // Turning the screen off always locks Mandat, even while the file picker is open
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                picking = false
                pickerTimeout?.cancel()
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
        // Only from the lock (or first) screen: never over an open demo or another open database
        if (startedAt != generation || demo || demoOpening || state is LockState.Open || state == LockState.Opening) {
            passphrase.fill(0)
            return
        }
        state = LockState.Opening
        background.launch {
            val opened = try {
                MandatDb.open(getApplication(), passphrase)
            } catch (e: Exception) {
                null
            }
            // Trip mode is applied before any screen shows data, so nothing hidden flashes up.
            // If it cannot be read, Mandat does not open at all rather than show everything.
            var db2 = opened
            val hidden = try {
                opened?.let { computeHidden(it) } ?: (false to emptySet())
            } catch (e: Exception) {
                try { opened?.close() } catch (x: Exception) { }
                db2 = null
                false to emptySet()
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
                        tripOn.value = hidden.first
                        hiddenMatters.value = hidden.second
                        state = LockState.Open(db)
                        // Back in the foreground: Mandat's own copy left on the clipboard is taken off
                        AiClipboard.clearIfOurs(getApplication())
                        applyResume()
                        // Record the automatic copy made when Mandat last closed
                        background.launch { reconcileAuto(db) }
                    }
                }
            }
        }
    }

    /** Runs [block] with the open database, never at the same time as a write or a lock. */
    suspend fun <T> withOpenDb(block: suspend (MandatDb) -> T): T? = writes.withLock {
        val db = (state as? LockState.Open)?.db
        if (db == null || !db.isOpen) null else block(db)
    }

    /** Saves a change in the background; a lock waits until it is written. */
    fun write(block: suspend (MandatDb) -> Unit) {
        val db = (state as? LockState.Open)?.db ?: return
        background.launch {
            writes.withLock {
                if (db.isOpen) {
                    try {
                        block(db)
                    } catch (e: Exception) {
                        // Not written; nothing about the content is logged
                    }
                }
            }
        }
    }

    /** The demo is open: invented records in an in-memory database, not the owner's data. */
    var demo by mutableStateOf(false)
        private set
    /** The demo is being opened (set before loading, so a lock meanwhile treats it as the demo). */
    @Volatile private var demoOpening = false
    /** The demo's own database while it is open, so backups can tell it from the owner's. */
    @Volatile private var demoDb: MandatDb? = null
    /** Where closing the demo returns: the first screen or the lock screen. */
    private var demoReturn: LockState = LockState.Locked

    /** Runs [block] with the owner's own database only; never with the demo (for backups). */
    suspend fun <T> withRealDb(block: suspend (MandatDb) -> T): T? = withOpenDb { db -> if (demo || demoOpening || db === demoDb) null else block(db) }

    /**
     * Opens the demo: the invented records shipped with the app go into an encrypted database that
     * lives only in memory, with a random key. Backups, reminders and the resume point are never
     * touched, and closing it (the banner, or leaving the app) leaves nothing behind.
     */
    fun openDemo() {
        val before = state
        if (before is LockState.Open || before == LockState.Opening || prompting) return
        // A new generation: an unlock answer arriving now is for the old one and is refused
        generation++
        val startedAt = generation
        demoOpening = true
        demoReturn = if (before == LockState.FirstRun) LockState.FirstRun else LockState.Locked
        state = LockState.Opening
        background.launch {
            val passphrase = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val db = try {
                val app = getApplication<Application>()
                val gz = app.assets.open(ua.mandat.data.DemoData.ASSET).use { it.readBytes() }
                val json = ua.mandat.data.DemoData.prepare(gz, Dates.today())
                MandatDb.openDemo(app, passphrase).also { d ->
                    try { ua.mandat.backup.BackupData.restore(d, json) } catch (e: Exception) { d.close(); throw e }
                }
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                when {
                    db == null -> { passphrase.fill(0); if (startedAt == generation) { demoOpening = false; state = before } }
                    startedAt != generation -> background.launch { db.close(); passphrase.fill(0) }
                    else -> {
                        key = passphrase
                        demoDb = db
                        demoOpening = false
                        demo = true
                        tripOn.value = false
                        hiddenMatters.value = emptySet()
                        state = LockState.Open(db)
                    }
                }
            }
        }
    }

    /** Closes the database and wipes the key until the next unlock. True if it was open or opening. */
    fun lock(): Boolean {
        generation++
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
        // Closing the demo: nothing of it is kept (no backup, no reminder days, no resume point)
        val wasDemo = demo || demoOpening
        demo = false
        demoOpening = false
        val wasInUse = current is LockState.Open || current == LockState.Opening
        // Closing the demo returns where it was opened from (the first screen before set-up)
        if (wasInUse) state = if (wasDemo) demoReturn else LockState.Locked
        // Anything still being typed is saved first, in the same step as closing,
        // so leaving the app for a moment never loses it
        val openDraft = draft
        // Where the owner was: only the tab and the numbers of open cards (never their content),
        // so the same card opens again after unlocking
        // The demo never leaves a resume point: its numbers belong to invented records
        if (!wasDemo) resume = Resume(
            at = System.currentTimeMillis(),
            matterId = draft?.request?.takeIf { !it.isNew }?.matter?.id,
            personId = personDraft?.takeIf { !it.isNew }?.person?.id,
            eventId = eventDraft?.takeIf { !it.isNew }?.event?.id,
            contactId = contactDraft?.takeIf { !it.isNew }?.contact?.id,
            directory = showDirectory,
            task = delegationDraft?.takeIf { !it.isNew }?.let { "delegation" to it.delegation.id }
                ?: promiseDraft?.takeIf { !it.isNew }?.let { "promise" to it.promise.id }
                ?: deadlineDraft?.takeIf { !it.isNew }?.let { "deadline" to it.deadline.id },
        ).takeIf { current is LockState.Open }
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
        val openReview = reviewDraft
        deadlineDraft = null
        reviewDraft = null
        searching = false
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
                            { persistReview(current.db, openReview) },
                            // Last: only the days on which something is due, for reminders (never from the demo)
                            { if (!wasDemo) storeReminderDays(current.db) },
                        ).forEach { save ->
                            try {
                                save()
                            } catch (e: Exception) {
                                // Not saved; nothing about the content is logged
                            }
                        }
                        // Automatic backup, if turned on and due: only encrypted here, written after closing
                        prepared = if (wasDemo) null else try {
                            ua.mandat.backup.AutoBackup.prepareIfDue(current.db)
                        } catch (e: Throwable) {
                            null
                        }
                        // Registered before the lock is released, so the next unlock always waits for it
                        prepared?.let { autoWrite = startAutoWrite(it, lazy = true) }
                    }
                    current.db.close()
                    if (current.db === demoDb) demoDb = null
                    oldKey?.fill(0)
                }
                // The database is closed: a slow folder can only delay writing encrypted bytes
                autoWrite?.start()
            }
        }
        return wasInUse
    }

    companion object {
        private const val PICKER_TIMEOUT_MS = 90 * 1000L
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
