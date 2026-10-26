package ua.mandat.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import ua.mandat.data.MandatDb
import ua.mandat.data.Setting

/**
 * Optional automatic backups: once a day, when Mandat closes, an encrypted copy goes to a
 * folder the owner chose; the last [KEEP] are kept. The file is exactly like a manual backup
 * and opens with the same password.
 *
 * The key is derived from the password once, at setup, and kept inside the encrypted
 * database (never in plain preferences, never in a backup file), so it is protected exactly
 * like the data it protects. The password itself is never stored.
 */
object AutoBackup {
    const val PREFIX = "auto_backup_"
    private const val KEY_FOLDER = PREFIX + "folder"
    private const val KEY_SALT = PREFIX + "salt"
    private const val KEY_KEY = PREFIX + "key"
    private const val KEY_LAST_OK = PREFIX + "last_ok"
    private const val KEY_LAST_FAIL = PREFIX + "last_fail"

    const val KEEP = 7
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val FILE_PREFIX = "archive-auto-"

    data class Status(val folder: Uri?, val lastOk: Long?, val lastFail: Long?) {
        val enabled get() = folder != null
        /** Folders of other apps (cloud drives) may send the encrypted copy elsewhere. */
        val maySync get() = folder != null && folder.authority != "com.android.externalstorage.documents"
    }

    suspend fun status(db: MandatDb): Status {
        val s = db.settings()
        return Status(
            folder = s.get(KEY_FOLDER)?.value?.let(Uri::parse),
            lastOk = s.get(KEY_LAST_OK)?.value?.toLongOrNull(),
            lastFail = s.get(KEY_LAST_FAIL)?.value?.toLongOrNull(),
        )
    }

    /**
     * Turns automatic backups on for [folder] with [password]: keeps access to the folder,
     * derives the key, makes the first copy and checks that it opens with the password.
     */
    suspend fun enable(context: Context, db: MandatDb, folder: Uri, password: String): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(folder, flags)
        val (salt, key) = BackupCrypto.newAutoKey(password)
        try {
            val s = db.settings()
            s.put(Setting(KEY_FOLDER, folder.toString()))
            s.put(Setting(KEY_SALT, salt.hex()))
            s.put(Setting(KEY_KEY, key.hex()))
            val written = write(context, db, folder, key, salt) ?: run {
                disable(context, db)
                return false
            }
            // The first copy must open with the password the owner typed, not only with the key
            val ok = try {
                BackupCrypto.decrypt(read(context, written), password).also { it.fill(0) }
                true
            } catch (e: Exception) {
                false
            }
            if (!ok) disable(context, db)
            return ok
        } finally {
            key.fill(0)
        }
    }

    /** Turns automatic backups off: forgets the key and the folder (files already made stay). */
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

    /** Called while Mandat closes: makes a copy if more than a day has passed (or [force]). */
    suspend fun runIfDue(context: Context, db: MandatDb, force: Boolean = false): Boolean {
        val s = db.settings()
        val folder = s.get(KEY_FOLDER)?.value?.let(Uri::parse) ?: return false
        val last = s.get(KEY_LAST_OK)?.value?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        if (!force && now - last in 0 until INTERVAL_MS) return false
        val salt = s.get(KEY_SALT)?.value?.unhex() ?: return false
        val key = s.get(KEY_KEY)?.value?.unhex() ?: return false
        return try {
            write(context, db, folder, key, salt) != null
        } finally {
            key.fill(0)
        }
    }

    /** Writes one copy, checks it, removes copies beyond [KEEP]; records success or failure. */
    private suspend fun write(context: Context, db: MandatDb, folder: Uri, key: ByteArray, salt: ByteArray): Uri? {
        val s = db.settings()
        val now = System.currentTimeMillis()
        val json = BackupData.dump(db)
        var created: Uri? = null
        try {
            val file = BackupCrypto.encryptWithKey(json, key, salt)
            val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
            val name = FILE_PREFIX + java.time.LocalDate.now() + ".bin"
            created = DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", name)
                ?: throw IllegalStateException()
            context.contentResolver.openOutputStream(created, "wt")!!.use { it.write(file) }
            // Check what actually landed in the folder
            val check = BackupCrypto.decryptWithKey(read(context, created), key)
            val same = check.contentEquals(json)
            check.fill(0)
            if (!same) throw IllegalStateException()
            removeOld(context, folder)
            s.put(Setting(KEY_LAST_OK, now.toString()))
            return created
        } catch (e: Exception) {
            // A half-written copy must not stay behind looking like a backup
            created?.let { deleteQuietly(context, it) }
            s.put(Setting(KEY_LAST_FAIL, now.toString()))
            return null
        } finally {
            json.fill(0)
        }
    }

    /** Keeps the newest [KEEP] automatic copies in the folder; other files are never touched. */
    private fun removeOld(context: Context, folder: Uri) {
        val parentId = DocumentsContract.getTreeDocumentId(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, parentId)
        val found = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (name.startsWith(FILE_PREFIX) && name.endsWith(".bin")) found += id to name
            }
        }
        // Names carry the date, so sorting by name sorts by age
        found.sortedByDescending { it.second }.drop(KEEP).forEach { (id, _) ->
            deleteQuietly(context, DocumentsContract.buildDocumentUriUsingTree(folder, id))
        }
    }

    private fun read(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > BackupCrypto.MAX_FILE_BYTES) throw BackupCrypto.NotABackup()
            bytes
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
