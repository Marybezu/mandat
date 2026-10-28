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
import kotlinx.coroutines.withTimeoutOrNull
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
    /** The file is an export (.mandatx), not a backup. */
    data object IsExport : BackupNotice
    /** The backup was made by a newer Mandat: update the app first. */
    data object FromNewer : BackupNotice
    data class Restored(val rows: Int) : BackupNotice
    data object RestoreFailed : BackupNotice
    /** Automatic copies are on, but the copy of the current data before replacing it failed: nothing was replaced. */
    data object RestoreNoSafetyCopy : BackupNotice
    data object AutoOn : BackupNotice
    data object AutoFailed : BackupNotice
    data object AutoOff : BackupNotice
    data object AutoSaved : BackupNotice
    data object AutoTooBig : BackupNotice
    /** The chosen file already holds something: Mandat writes a backup only into a new file. */
    data object ExistingFile : BackupNotice
    /** The data is larger than a backup file can hold. */
    data object TooBig : BackupNotice
}

/**
 * True when the chosen file is empty: a new file just made by the save dialog. A file that already
 * holds something (an older copy picked to "replace") is never written over, so a failure halfway
 * can never cost both the new copy and the old one.
 */
fun isNewDocument(context: Context, uri: Uri): Boolean = try {
    context.contentResolver.openInputStream(uri)?.use { it.read() < 0 } ?: sizeIsZero(context, uri)
} catch (e: Exception) {
    // Some file apps cannot read a file just made: then its reported size decides
    sizeIsZero(context, uri)
}

private fun sizeIsZero(context: Context, uri: Uri): Boolean = try {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
        ?.use { c -> c.moveToFirst() && !c.isNull(0) && c.getLong(0) == 0L } == true
} catch (e: Exception) {
    false
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
            val st = try { session.withRealDb { ua.mandat.backup.AutoBackup.status(it) } } catch (e: Exception) { null }
            withContext(Dispatchers.Main) { auto = st }
        }
    }

    /** Turns automatic backups on: folder chosen, password typed once; the first copy is checked with it. */
    fun enableAuto(folder: Uri, password: String) = run(onCrash = { BackupNotice.AutoFailed }) {
        val auto = ua.mandat.backup.AutoBackup
        val db = (session.state as? LockState.Open)?.db ?: return@run BackupNotice.AutoFailed
        val prepared = session.withRealDb {
            auto.setUp(context, it, folder, password)
            auto.prepareIfDue(it, force = true)
        }
        if (prepared == null) {
            val tooBig = session.withRealDb { auto.status(it).failReason == auto.REASON_TOO_BIG } == true
            session.withRealDb { auto.disable(context, it) }
            refreshAuto()
            return@run if (tooBig) BackupNotice.AutoTooBig else BackupNotice.AutoFailed
        }
        // Written without the database, then checked with the password the owner typed
        val job = session.startAutoWrite(prepared)
        val result = withTimeoutOrNull(60_000) { job.await() }
        val ok = result?.ok == true && result.uri != null && auto.opensWith(context, result.uri, password)
        if (ok) {
            session.reconcileAuto(db)
        } else {
            // Off first, so nothing counts the discarded file as a good copy
            session.withRealDb { auto.disable(context, it) }
            if (result != null) result.uri?.let { auto.discard(context, it) }
            // Still writing after a minute: remove the file once it is finished
            else scope.launch { job.await().uri?.let { auto.discard(context, it) } }
        }
        refreshAuto()
        if (ok) BackupNotice.AutoOn else BackupNotice.AutoFailed
    }

    fun disableAuto() = run(onCrash = { BackupNotice.AutoOff }) {
        session.withRealDb { ua.mandat.backup.AutoBackup.disable(context, it) }
        refreshAuto()
        BackupNotice.AutoOff
    }

    /** Makes an automatic copy right now, without waiting a day. */
    fun autoNow() = run(onCrash = { BackupNotice.AutoFailed }) {
        val auto = ua.mandat.backup.AutoBackup
        val db = (session.state as? LockState.Open)?.db ?: return@run BackupNotice.AutoFailed
        // An earlier copy still waiting to be recorded comes first
        session.reconcileAuto(db)
        val prepared = session.withRealDb { auto.prepareIfDue(it, force = true) }
        if (prepared == null) {
            refreshAuto()
            val tooBig = session.withRealDb { auto.status(it).failReason == auto.REASON_TOO_BIG } == true
            return@run if (tooBig) BackupNotice.AutoTooBig else BackupNotice.AutoFailed
        }
        withTimeoutOrNull(60_000) { session.startAutoWrite(prepared).await() }
        val ok = session.reconcileAuto(db) == true
        refreshAuto()
        if (ok) BackupNotice.AutoSaved else BackupNotice.AutoFailed
    }

    /** The EFF long word list (7776 words) for suggested passwords. */
    val words: List<String> by lazy {
        context.assets.open("eff_large_wordlist.txt").bufferedReader().readLines().filter { it.isNotBlank() }
    }

    /** Writes an encrypted copy of everything to [uri], then reads it back to check it. */
    fun save(uri: Uri, password: String) {
        // Only a new, empty file is written; a file Mandat started writing into is removed after a failure
        var touched = false
        run(onCrash = { if (touched) deleteQuietly(uri); BackupNotice.SaveFailed }) {
        if (!isNewDocument(context, uri)) return@run BackupNotice.ExistingFile
        val json = try {
            session.withRealDb { BackupData.dump(it) }
        } catch (e: Exception) {
            null
        }
        if (json == null) return@run BackupNotice.SaveFailed
        // Said plainly before anything is written, rather than a copy that could not be opened again
        if (json.size > BackupCrypto.MAX_FILE_BYTES - 64) {
            json.fill(0)
            deleteQuietly(uri)
            return@run BackupNotice.TooBig
        }
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
                // Remembered for the reminder to make a copy (only the time, on this phone)
                session.withRealDb { it.settings().put(ua.mandat.data.Setting(ua.mandat.backup.AutoBackup.KEY_MANUAL, System.currentTimeMillis().toString())) }
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
            if (BackupCrypto.startsWithExportMark(json)) {
                json.fill(0)
                return@run BackupNotice.IsExport
            }
            val summary = try {
                BackupData.summary(json)
            } catch (e: Exception) {
                json.fill(0)
                return@run BackupNotice.Incompatible
            }
            // Said before asking to replace anything: a newer copy needs a newer Mandat
            if (summary.schema > ua.mandat.data.DB_VERSION) {
                json.fill(0)
                return@run BackupNotice.FromNewer
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
                // With automatic copies on, the current data is copied first (encrypted, to that folder),
                // so replacing it can be undone; if that copy fails, nothing is replaced
                val auto = ua.mandat.backup.AutoBackup
                val db = (session.state as? LockState.Open)?.db ?: return@run BackupNotice.RestoreFailed
                if (session.withRealDb { auto.status(it).enabled } == true) {
                    session.reconcileAuto(db)
                    val prepared = session.withRealDb { auto.prepareIfDue(it, force = true) }
                    val written = prepared != null && withTimeoutOrNull(60_000) { session.startAutoWrite(prepared).await() } != null
                    val ok = written && session.reconcileAuto(db) == true
                    refreshAuto()
                    if (!ok) return@run BackupNotice.RestoreNoSafetyCopy
                }
                session.withRealDb { BackupData.restore(it, restore.json) }
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
