package ua.mandat.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import ua.mandat.data.MandatDb
import androidx.room.withTransaction
import ua.mandat.data.Setting
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Optional automatic backups: once a day, when Mandat closes, an encrypted copy goes to a
 * folder the owner chose; copies of the last [KEEP] days are kept. Each file is exactly like a
 * manual backup and opens with the password set for automatic backups.
 *
 * The key is derived from the password once, at setup, and kept inside the encrypted database
 * (never in plain preferences, never in a backup file). The password itself is never stored.
 *
 * Three steps, so that a slow or stuck folder can never keep the database open:
 * 1. [prepareIfDue] with the database open: encrypt everything, note the file as pending;
 * 2. [writePrepared] after the database is closed: write the encrypted bytes, read them back;
 * 3. [reconcile] at the next unlock: record the result and remove Mandat's own old copies.
 * Only files Mandat created and recorded itself are ever deleted.
 */
object AutoBackup {
    const val PREFIX = "auto_backup_"
    private const val KEY_FOLDER = PREFIX + "folder"
    private const val KEY_SALT = PREFIX + "salt"
    private const val KEY_KEY = PREFIX + "key"
    private const val KEY_LAST_OK = PREFIX + "last_ok"
    private const val KEY_LAST_FAIL = PREFIX + "last_fail"
    private const val KEY_FAIL_REASON = PREFIX + "fail_reason"
    private const val KEY_FILES = PREFIX + "files"
    private const val KEY_PENDING = PREFIX + "pending"

    const val KEEP = 7
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val FILE_PREFIX = "archive-auto-"
    private val NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

    const val REASON_TOO_BIG = "too_big"
    private const val REASON_WRITE = "write"

    data class Status(val folder: Uri?, val lastOk: Long?, val lastFail: Long?, val failReason: String?) {
        val enabled get() = folder != null
    }

    /** Encrypted bytes waiting to be written, and the name they will have. */
    class Prepared(val folder: Uri, val name: String, val createdAt: Long, val bytes: ByteArray)

    /** What happened to a [Prepared] copy (kept in memory until the next unlock). */
    data class Result(val name: String, val uri: Uri?, val ok: Boolean)

    /** Folders of other apps (cloud drives) may send the encrypted copy elsewhere. */
    fun maySync(folder: Uri) = folder.authority != "com.android.externalstorage.documents"

    suspend fun status(db: MandatDb): Status {
        val s = db.settings()
        return Status(
            folder = s.get(KEY_FOLDER)?.value?.let(Uri::parse),
            lastOk = s.get(KEY_LAST_OK)?.value?.toLongOrNull(),
            lastFail = s.get(KEY_LAST_FAIL)?.value?.toLongOrNull(),
            failReason = s.get(KEY_FAIL_REASON)?.value,
        )
    }

    /** Remembers the folder and the key from [password]; nothing is written yet. */
    suspend fun setUp(context: Context, db: MandatDb, folder: Uri, password: String) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(folder, flags)
        val (salt, key) = BackupCrypto.newAutoKey(password)
        try {
            val s = db.settings()
            s.deletePrefix(PREFIX)
            s.put(Setting(KEY_FOLDER, folder.toString()))
            s.put(Setting(KEY_SALT, salt.hex()))
            s.put(Setting(KEY_KEY, key.hex()))
        } finally {
            key.fill(0)
        }
    }

    /** Turns automatic backups off: forgets the key, the folder and the list (files already made stay). */
    suspend fun disable(context: Context, db: MandatDb) {
        val folder = db.settings().get(KEY_FOLDER)?.value?.let(Uri::parse)
        db.settings().deletePrefix(PREFIX)
        if (folder != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(folder, flags)
            } catch (e: Exception) {
                // Already gone
            }
        }
    }

    /**
     * Step 1, with the database open: if a copy is due (or [force]), encrypts everything and notes
     * the coming file as pending. No file access here, so nothing can hang while the database is open.
     */
    suspend fun prepareIfDue(db: MandatDb, force: Boolean = false): Prepared? {
        val s = db.settings()
        val folder = s.get(KEY_FOLDER)?.value?.let(Uri::parse) ?: return null
        // The previous copy has not been recorded yet: it will be at the next unlock
        if (s.get(KEY_PENDING) != null) return null
        val now = System.currentTimeMillis()
        val last = s.get(KEY_LAST_OK)?.value?.toLongOrNull() ?: 0L
        if (!force && now - last in 0 until INTERVAL_MS) return null
        val salt = s.get(KEY_SALT)?.value?.unhex() ?: return null
        val key = s.get(KEY_KEY)?.value?.unhex() ?: return null
        val json = BackupData.dump(db)
        try {
            if (json.size > BackupCrypto.MAX_FILE_BYTES - 64) {
                fail(db, REASON_TOO_BIG, now)
                return null
            }
            val bytes = BackupCrypto.encryptWithKey(json, key, salt)
            val name = FILE_PREFIX + LocalDateTime.now().format(NAME_TIME) + ".bin"
            s.put(Setting(KEY_PENDING, "$name\t$now"))
            return Prepared(folder, name, now, bytes)
        } finally {
            json.fill(0)
            key.fill(0)
        }
    }

    /**
     * Step 2, without the database: writes the encrypted bytes and reads them back to compare.
     * Anything that goes wrong (including running out of memory) removes the file again.
     */
    fun writePrepared(context: Context, p: Prepared): Result {
        var uri: Uri? = null
        try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(p.folder, DocumentsContract.getTreeDocumentId(p.folder))
            uri = DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", p.name)
                ?: return Result(p.name, null, false)
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(p.bytes) }
            val back = read(context, uri)
            if (!back.contentEquals(p.bytes)) throw IllegalStateException()
            return Result(p.name, uri, true)
        } catch (e: Throwable) {
            uri?.let { deleteQuietly(context, it) }
            return Result(p.name, uri, false)
        } finally {
            p.bytes.fill(0)
        }
    }

    /** True if the copy at [uri] opens with [password] (checked once, when backups are turned on). */
    fun opensWith(context: Context, uri: Uri, password: String): Boolean =
        try {
            BackupCrypto.decrypt(read(context, uri), password).fill(0)
            true
        } catch (e: Throwable) {
            false
        }

    /** A copy noted as pending, read under the lock; the key is only for checking an unknown result. */
    class PendingInfo(val name: String, val createdAt: Long, val folder: Uri, val key: ByteArray?)

    /** Step 3a, under the lock: what is pending, if anything. No file access. */
    suspend fun pendingInfo(db: MandatDb): PendingInfo? {
        val s = db.settings()
        val pending = s.get(KEY_PENDING)?.value ?: return null
        val folder = s.get(KEY_FOLDER)?.value?.let(Uri::parse)
        if (folder == null) {
            s.deletePrefix(KEY_PENDING)
            return null
        }
        return PendingInfo(
            name = pending.substringBefore('\t'),
            createdAt = pending.substringAfter('\t').toLongOrNull() ?: System.currentTimeMillis(),
            folder = folder,
            key = s.get(KEY_KEY)?.value?.unhex(),
        )
    }

    /**
     * Step 3b, without the lock (it touches the folder and may be slow): the accepted copy, if any.
     * With no known result (Mandat was stopped meanwhile) the file is found by name and checked
     * with the key; a damaged file is removed.
     */
    fun check(context: Context, info: PendingInfo, result: Result?): Uri? {
        try {
            if (result != null && result.name == info.name) return result.uri.takeIf { result.ok }
            val uri = try { findByName(context, info.folder, info.name) } catch (e: Exception) { null } ?: return null
            val key = info.key ?: return null
            val ok = try {
                BackupCrypto.decryptWithKey(read(context, uri), key).fill(0)
                true
            } catch (e: Throwable) {
                false
            }
            if (!ok) deleteQuietly(context, uri)
            return uri.takeIf { ok }
        } finally {
            info.key?.fill(0)
        }
    }

    /**
     * Step 3c, under the lock, in one transaction: records the result and decides which of Mandat's
     * own old copies to remove. Returns their addresses, to be deleted afterwards without the lock.
     */
    suspend fun record(db: MandatDb, info: PendingInfo, accepted: Uri?): List<Uri> {
        var toDelete = emptyList<Uri>()
        db.withTransaction {
            val s = db.settings()
            // Something else already handled it
            if (s.get(KEY_PENDING)?.value?.substringBefore('\t') != info.name) return@withTransaction
            s.deletePrefix(KEY_PENDING)
            if (accepted == null) {
                fail(db, REASON_WRITE, info.createdAt)
                return@withTransaction
            }
            val files = files(s) + (accepted.toString() to info.createdAt)
            val kept = trimmed(files, keepNewest = accepted.toString())
            toDelete = files.filter { f -> kept.none { it.first == f.first } }.map { Uri.parse(it.first) }
            s.put(Setting(KEY_FILES, kept.joinToString("\n") { "${it.first}\t${it.second}" }))
            s.put(Setting(KEY_LAST_OK, info.createdAt.toString()))
            s.deletePrefix(KEY_FAIL_REASON)
        }
        return toDelete
    }

    /** Removes old copies chosen by [record]; never fails the backup itself. */
    fun deleteAll(context: Context, uris: List<Uri>) = uris.forEach { deleteQuietly(context, it) }

    /** Removes a file written during set-up when the password check did not pass. */
    fun discard(context: Context, uri: Uri) = deleteQuietly(context, uri)

    /** One copy per day (the newest) for the last [KEEP] days; the new copy always stays. */
    private fun trimmed(files: List<Pair<String, Long>>, keepNewest: String): List<Pair<String, Long>> {
        val zone = java.time.ZoneId.systemDefault()
        fun dayOf(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val newestPerDay = files.groupBy { dayOf(it.second) }.values.map { day -> day.maxBy { it.second } }
        val kept = newestPerDay.sortedByDescending { it.second }.take(KEEP).toMutableList()
        if (kept.none { it.first == keepNewest }) files.firstOrNull { it.first == keepNewest }?.let { kept += it }
        return kept
    }

    /** Mandat's own copies: document address and when each was made. */
    private suspend fun files(s: ua.mandat.data.SettingDao): List<Pair<String, Long>> =
        s.get(KEY_FILES)?.value.orEmpty().lines().mapNotNull { line ->
            val parts = line.split('\t')
            val t = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            parts[0].takeIf { it.isNotBlank() }?.let { it to t }
        }

    private suspend fun fail(db: MandatDb, reason: String, at: Long) {
        db.settings().put(Setting(KEY_LAST_FAIL, at.toString()))
        db.settings().put(Setting(KEY_FAIL_REASON, reason))
    }

    private fun findByName(context: Context, folder: Uri, name: String): Uri? {
        val parentId = DocumentsContract.getTreeDocumentId(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, parentId)
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(folder, c.getString(0) ?: continue)
            }
        }
        return null
    }

    /** Reads a file, refusing anything larger than a backup can be. */
    private fun read(context: Context, uri: Uri): ByteArray {
        val limit = BackupCrypto.MAX_FILE_BYTES
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val out = java.io.ByteArrayOutputStream()
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

    private fun deleteQuietly(context: Context, uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            // The folder's app may not allow it
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun String.unhex(): ByteArray? =
        if (length % 2 != 0) null else try { chunked(2).map { it.toInt(16).toByte() }.toByteArray() } catch (e: Exception) { null }
}
