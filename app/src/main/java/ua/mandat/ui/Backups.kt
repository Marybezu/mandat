package ua.mandat.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.mandat.backup.BackupCrypto
import ua.mandat.backup.BackupData
import java.io.ByteArrayOutputStream

/** What the owner sees after a backup or restore attempt. */
sealed interface BackupNotice {
    data class Saved(val rows: Int) : BackupNotice
    data object SaveFailed : BackupNotice
    data object WrongPassword : BackupNotice
    data object NotABackup : BackupNotice
    data object Incompatible : BackupNotice
    data class Restored(val rows: Int) : BackupNotice
    data object RestoreFailed : BackupNotice
}

/** A decrypted backup waiting for the owner's "Replace" confirmation. Kept in memory only. */
class PendingRestore(val summary: BackupData.Summary, val json: ByteArray)

class Backups(private val context: Context, private val session: Session) {

    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<BackupNotice?>(null)
    var pending by mutableStateOf<PendingRestore?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Writes an encrypted copy of everything to [uri], then reads it back to check it. */
    fun save(uri: Uri, password: String) = run {
        val json = session.withOpenDb { BackupData.dump(it) } ?: return@run BackupNotice.SaveFailed
        try {
            val rows = BackupData.summary(json).rows
            val file = BackupCrypto.encrypt(json, password)
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(file) }
            // Check what actually landed on the storage, not what we meant to write
            val check = BackupCrypto.decrypt(read(uri), password)
            val ok = check.contentEquals(json)
            check.fill(0)
            if (ok) BackupNotice.Saved(rows) else BackupNotice.SaveFailed
        } catch (e: Exception) {
            BackupNotice.SaveFailed
        } finally {
            json.fill(0)
        }
    }

    /** Opens a backup and shows what is in it; nothing is changed yet. */
    fun open(uri: Uri, password: String) = run {
        try {
            val json = BackupCrypto.decrypt(read(uri), password)
            val summary = try {
                BackupData.summary(json)
            } catch (e: Exception) {
                json.fill(0)
                return@run BackupNotice.Incompatible
            }
            withContext(Dispatchers.Main) { pending = PendingRestore(summary, json) }
            null
        } catch (e: BackupCrypto.WrongPasswordOrDamaged) {
            BackupNotice.WrongPassword
        } catch (e: BackupCrypto.NotABackup) {
            BackupNotice.NotABackup
        } catch (e: Exception) {
            BackupNotice.NotABackup
        }
    }

    /** The owner confirmed: replace all current data with the pending backup. */
    fun confirmRestore() {
        val restore = pending ?: return
        pending = null
        run {
            try {
                session.withOpenDb { BackupData.restore(it, restore.json) }
                    ?.let { BackupNotice.Restored(restore.summary.rows) }
                    ?: BackupNotice.RestoreFailed
            } catch (e: BackupData.Incompatible) {
                BackupNotice.Incompatible
            } catch (e: Exception) {
                BackupNotice.RestoreFailed
            } finally {
                restore.json.fill(0)
            }
        }
    }

    fun cancelRestore() {
        pending?.json?.fill(0)
        pending = null
    }

    private fun run(work: suspend () -> BackupNotice?) {
        if (busy) return
        busy = true
        scope.launch {
            val result = work()
            withContext(Dispatchers.Main) {
                busy = false
                if (result != null) notice = result
            }
        }
    }

    private fun read(uri: Uri): ByteArray {
        val limit = 256L * 1024 * 1024
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) throw BackupCrypto.NotABackup()
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
