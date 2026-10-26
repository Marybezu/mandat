package ua.mandat.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
    data object AutoOn : BackupNotice
    data object AutoFailed : BackupNotice
    data object AutoOff : BackupNotice
    data object AutoSaved : BackupNotice
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

    /** Automatic backups: on or off, and when the last one worked or failed. */
    var auto by mutableStateOf<ua.mandat.backup.AutoBackup.Status?>(null)
        private set

    fun refreshAuto() {
        scope.launch {
            val st = try { session.withOpenDb { ua.mandat.backup.AutoBackup.status(it) } } catch (e: Exception) { null }
            withContext(Dispatchers.Main) { auto = st }
        }
    }

    /** Turns automatic backups on: folder chosen, password typed once. */
    fun enableAuto(folder: Uri, password: String) = run(onCrash = { BackupNotice.AutoFailed }) {
        val ok = session.withOpenDb { ua.mandat.backup.AutoBackup.enable(context, it, folder, password) } == true
        refreshAuto()
        if (ok) BackupNotice.AutoOn else BackupNotice.AutoFailed
    }

    fun disableAuto() = run(onCrash = { BackupNotice.AutoOff }) {
        session.withOpenDb { ua.mandat.backup.AutoBackup.disable(context, it) }
        refreshAuto()
        BackupNotice.AutoOff
    }

    /** Makes an automatic copy right now, without waiting a day. */
    fun autoNow() = run(onCrash = { BackupNotice.AutoFailed }) {
        val ok = session.withOpenDb { ua.mandat.backup.AutoBackup.runIfDue(context, it, force = true) } == true
        refreshAuto()
        if (ok) BackupNotice.AutoSaved else BackupNotice.AutoFailed
    }

    /** The EFF long word list (7776 words) for suggested passwords. */
    val words: List<String> by lazy {
        context.assets.open("eff_large_wordlist.txt").bufferedReader().readLines().filter { it.isNotBlank() }
    }

    /** Writes an encrypted copy of everything to [uri], then reads it back to check it. */
    fun save(uri: Uri, password: String) {
        // Delete the file after a failure only if we already started writing into it:
        // the owner may have chosen an existing file to replace, which must stay intact.
        var touched = false
        run(onCrash = { if (touched) deleteQuietly(uri); BackupNotice.SaveFailed }) {
        val json = try {
            session.withOpenDb { BackupData.dump(it) }
        } catch (e: Exception) {
            null
        }
        if (json == null) return@run BackupNotice.SaveFailed
        try {
            val rows = BackupData.summary(json).rows
            val file = BackupCrypto.encrypt(json, password)
            context.contentResolver.openOutputStream(uri, "wt")!!.use {
                touched = true
                it.write(file)
            }
            // Check what actually landed on the storage, not what we meant to write
            val check = BackupCrypto.decrypt(read(uri), password)
            val ok = check.contentEquals(json)
            check.fill(0)
            if (ok) {
                BackupNotice.Saved(rows)
            } else {
                deleteQuietly(uri)
                BackupNotice.SaveFailed
            }
        } catch (e: Exception) {
            // A half-written file must not stay behind looking like a backup
            if (touched) deleteQuietly(uri)
            BackupNotice.SaveFailed
        } finally {
            json.fill(0)
        }
        }
    }

    /** Opens a backup and shows what is in it; nothing is changed yet. */
    /** [startedAt]: the lock generation read on the main thread when the owner chose the file. */
    fun open(uri: Uri, password: String, startedAt: Int) = run(onCrash = { BackupNotice.NotABackup }) {
        try {
            val json = BackupCrypto.decrypt(read(uri), password)
            val summary = try {
                BackupData.summary(json)
            } catch (e: Exception) {
                json.fill(0)
                return@run BackupNotice.Incompatible
            }
            withContext(Dispatchers.Main) {
                // Locked meanwhile: drop the decrypted data instead of keeping it in memory
                if (startedAt == session.generationNow()) pending = PendingRestore(summary, json) else json.fill(0)
            }
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
        run(onCrash = { restore.json.fill(0); BackupNotice.RestoreFailed }) {
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

    private fun run(onCrash: () -> BackupNotice, work: suspend () -> BackupNotice?) {
        if (busy) return
        busy = true
        scope.launch {
            // Also catches out-of-memory and other errors, so the app never crashes here
            val result = try {
                work()
            } catch (e: Throwable) {
                onCrash()
            }
            withContext(Dispatchers.Main) {
                busy = false
                if (result != null) notice = result
            }
        }
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            // The file app may not allow it; nothing else to do
        }
    }

    private fun read(uri: Uri): ByteArray {
        val limit = BackupCrypto.MAX_FILE_BYTES
        // Check the size first when the file app tells it, before reading anything
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                if (it.statSize > limit) throw BackupCrypto.NotABackup()
            }
        } catch (e: BackupCrypto.NotABackup) {
            throw e
        } catch (e: Exception) {
            // Size unknown; the limit below still applies while reading
        }
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
