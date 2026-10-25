package ua.mandat.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.mandat.data.MandatDb
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
                        lock()
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
    private var generation = 0
    private val writes = Mutex()
    private var key: ByteArray? = null

    // Not tied to the screen's lifetime: opening and closing always run to the end
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val backups = Backups(app, this)

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
                    }
                }
            }
        }
    }

    /** Runs [block] with the open database, never at the same time as a write or a lock. */
    suspend fun <T> withOpenDb(block: (MandatDb) -> T): T? = writes.withLock {
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
        if (current is LockState.Open) {
            val oldKey = key
            key = null
            background.launch {
                writes.withLock {
                    current.db.close()
                    oldKey?.fill(0)
                }
            }
        }
        return wasInUse
    }

    companion object {
        private const val PICKER_TIMEOUT_MS = 3 * 60 * 1000L
    }

    override fun onCleared() {
        lock()
    }
}
