package ua.mandat.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.mandat.data.MandatDb

sealed interface LockState {
    data object NeedsScreenLock : LockState
    data object FirstRun : LockState
    data object Locked : LockState
    data object Opening : LockState
    data object KeyLost : LockState
    data class Open(val db: MandatDb) : LockState
}

/** Holds the open database only while the app is in use; everything else stays encrypted. */
class Session(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf<LockState>(LockState.Locked)
        private set

    var lastError by mutableStateOf(false)

    fun setState(newState: LockState) {
        state = newState
    }

    fun open(passphrase: ByteArray) {
        state = LockState.Opening
        viewModelScope.launch {
            state = try {
                val db = withContext(Dispatchers.IO) { MandatDb.open(getApplication(), passphrase) }
                lastError = false
                LockState.Open(db)
            } catch (e: Exception) {
                lastError = true
                LockState.Locked
            }
            // The passphrase is not wiped here on purpose: the database may open extra
            // connections with it while unlocked. It is dropped with the database on lock.
        }
    }

    /** Closes the database and forgets the key until the next unlock. True if it was open. */
    fun lock(): Boolean {
        val current = state
        if (current is LockState.Open) {
            current.db.close()
            state = LockState.Locked
            return true
        }
        return false
    }

    override fun onCleared() {
        lock()
    }
}
