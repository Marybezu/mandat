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

    /** Today's answer to the evening question while it is being typed; null when not editing. */
    var reviewDraft by mutableStateOf<String?>(null)

    /** Material You colours on or off; changing it recolours the app right away. */
    var dynamicColors by mutableStateOf(Appearance.dynamic(app))
    /** Shown as a visible strip while screenshots are allowed, so the choice is not forgotten. */
    var screenshotsAllowed by mutableStateOf(Appearance.screenshotsAllowed(app))

    /** The "My promises" screen is open. */
    var showPromises by mutableStateOf(false)
    /** The smart list open on its own screen, if any. */
    var smartList by mutableStateOf<SmartList?>(null)
    /** The topics screen, and the topic open in it. */
    var showTopics by mutableStateOf(false)
    /** A delegation just done by someone: offer to thank them. */
    var thankFor by mutableStateOf<ua.mandat.data.Delegation?>(null)
    /** The influence map: null closed, 0 for everyone, otherwise for one matter. */
    var influenceFor by mutableStateOf<Long?>(null)
    /** The one-to-one view for a person in the team. */
    var oneOnOne by mutableStateOf<Long?>(null)
    var topicOpen by mutableStateOf<String?>(null)

    /** The search screen is open. */
    var searching by mutableStateOf(false)

    fun saveDeadlineDraft() {
        val d = deadlineDraft ?: return
        deadlineDraft = null
        write { d.persist(it) }
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
        val days = reminderDays(db.deadlines().open(), db.delegations().open(), db.promises().open(), Dates.today())
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

    /** Opens a matter by id in the editor (read under the write lock, shown on the main thread). */
    fun openMatter(id: Long) {
        val startedAt = generation
        background.launch {
            val m = withOpenDb { db -> db.matters().get(id) }
            withContext(Dispatchers.Main) {
                // Locked meanwhile: do not bring decrypted data back into memory
                if (m != null && startedAt == generation && state is LockState.Open) draft = MatterDraft(EditRequest(m, isNew = false))
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

    /** Opens a person by id in the editor. */
    fun openPerson(id: Long) {
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
    fun startAutoWrite(p: ua.mandat.backup.AutoBackup.Prepared): kotlinx.coroutines.Deferred<ua.mandat.backup.AutoBackup.Result> =
        background.async { ua.mandat.backup.AutoBackup.writePrepared(getApplication(), p) }.also { autoJob = it }

    /**
     * Records a pending automatic copy. Folder work happens outside the lock and is bounded in
     * time, so a stuck folder never holds the database. Returns true if a copy was accepted,
     * false if it failed, null if it is still being written (tried again next time).
     */
    suspend fun reconcileAuto(db: MandatDb): Boolean? {
        val auto = ua.mandat.backup.AutoBackup
        val info = writes.withLock { if (db.isOpen) try { auto.pendingInfo(db) } catch (e: Exception) { null } else null } ?: return null
        val job = autoJob
        val result = if (job == null) null else (withTimeoutOrNull(AUTO_WAIT_MS) { job.await() } ?: run { info.key?.fill(0); return null })
        // A one-element list tells "checked, nothing accepted" apart from "took too long"
        val checked = withTimeoutOrNull(AUTO_WAIT_MS) {
            background.async { listOf(auto.check(getApplication(), info, result)) }.await()
        } ?: return null
        val accepted = checked.first()
        val toDelete = writes.withLock {
            if (db.isOpen) try { auto.record(db, info, accepted) } catch (e: Exception) { null } else null
        } ?: return null
        background.launch { auto.deleteAll(getApplication(), toDelete) }
        return accepted != null
    }
    private var key: ByteArray? = null

    // Not tied to the screen's lifetime: opening and closing always run to the end
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val backups = Backups(app, this)

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
        if (startedAt != generation) {
            passphrase.fill(0)
            return
        }
        state = LockState.Opening
        background.launch {
            val db = try {
                MandatDb.open(getApplication(), passphrase)
            } catch (e: Exception) {
                null
            }
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
                        state = LockState.Open(db)
                        // Record the automatic copy made when Mandat last closed
                        background.launch { reconcileAuto(db) }
                        }
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

    /** Closes the database and wipes the key until the next unlock. True if it was open or opening. */
    fun lock(): Boolean {
        generation++
        backups.cancelRestore()
        val current = state
        val wasInUse = current is LockState.Open || current == LockState.Opening
        if (wasInUse) state = LockState.Locked
        // Anything still being typed is saved first, in the same step as closing,
        // so leaving the app for a moment never loses it
        val openDraft = draft
        val openInbox = inboxDraft
        val openPerson = personDraft
        val openDelegation = delegationDraft
        val openPromise = promiseDraft
        val openDeadline = deadlineDraft
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
                            { persistReview(current.db, openReview) },
                            // Last: only the days on which something is due, for reminders
                            { storeReminderDays(current.db) },
                        ).forEach { save ->
                            try {
                                save()
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
                    }
                    current.db.close()
                    oldKey?.fill(0)
                }
                // The database is closed: a slow folder can only delay writing encrypted bytes
                prepared?.let { startAutoWrite(it) }
            }
        }
        return wasInUse
    }

    companion object {
        private const val PICKER_TIMEOUT_MS = 90 * 1000L
        private const val AUTO_WAIT_MS = 30 * 1000L
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
