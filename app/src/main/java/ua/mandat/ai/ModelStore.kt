package ua.mandat.ai

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where the on-device model file lives and how it gets there. Mandat has no internet
 * permission: the owner downloads the model in a browser and picks the file here; Mandat copies
 * it into its private storage, checking it against the published SHA-256 as it copies.
 * The model holds no personal data; prompts and answers stay in memory only.
 */
class ModelStore(private val context: Context) {

    sealed interface State {
        data object Missing : State
        data class Ready(val sizeMb: Long) : State
        data class Failed(val reason: String) : State
    }

    /** App-private storage: no other app can read it, and it needs no permission. */
    private val dir: File get() = File(context.filesDir, MODEL_DIR).apply { mkdirs() }

    private fun models(d: File?): List<File> =
        d?.listFiles { f -> f.name.endsWith(".litertlm") && f.length() > MIN_SIZE }?.toList() ?: emptyList()

    fun modelFile(): File? = models(dir).maxByOrNull { it.lastModified() }

    /** Whether a model is installed, kept up to date by import and delete (read once at start). */
    val ready = kotlinx.coroutines.flow.MutableStateFlow(modelFile() != null)

    /** Where the engine keeps its own cache (compiled kernels, weights); removed with the model. */
    val cacheDir: File get() = File(context.cacheDir, "ai").apply { mkdirs() }

    fun state(): State {
        val f = modelFile() ?: return State.Missing
        return State.Ready(f.length() / MB)
    }

    data class ImportResult(val originalDeleted: Boolean?)

    /**
     * Copies a model the user picked with the system file picker.
     * When [deleteOriginal] is set, tries to remove the picked file afterwards
     * (null in the result = not asked, false = the storage didn't allow it).
     */
    suspend fun import(uri: Uri, deleteOriginal: Boolean, onProgress: (Float) -> Unit): ImportResult =
        withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            val total = cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            // A file of any other size cannot be the published model: refused before copying gigabytes
            if (total > 0 && total != E2B_SIZE) error(context.getString(ua.mandat.R.string.ai_wrong_file, E2B_FILE))
            val tmp = File(dir, "import.tmp")
            tmp.delete() // a leftover from a failed copy must not block the space check
            if (total > 0) require(dir.usableSpace > total + 200 * MB) { context.getString(ua.mandat.R.string.ai_no_space) }
            // Only the exact published file is accepted: it is checked by its SHA-256 while copying.
            val sha = java.security.MessageDigest.getInstance("SHA-256")
            try {
            cr.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        // Stops when the screen that started it is gone (for example Mandat locked); the temp file is removed below.
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        copied += n
                        // A source that did not tell the size still stops at the model's size, never fills the phone
                        if (copied > E2B_SIZE) error(context.getString(ua.mandat.R.string.ai_wrong_file, E2B_FILE))
                        if (total > 0) onProgress(copied.toFloat() / total)
                    }
                }
            }
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
            val hex = sha.digest().joinToString("") { "%02x".format(it) }
            if (tmp.length() <= MIN_SIZE || hex != E2B_SHA256) {
                tmp.delete()
                error(context.getString(ua.mandat.R.string.ai_wrong_file, E2B_FILE))
            }
            dir.listFiles { f -> f.name.endsWith(".litertlm") }?.forEach { it.delete() }
            val target = File(dir, "model.litertlm")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            val deleted = if (deleteOriginal) {
                runCatching { DocumentsContract.deleteDocument(cr, uri) }.getOrDefault(false)
            } else {
                null
            }
            ready.value = true
            ImportResult(deleted)
        }

    fun delete() {
        dir.listFiles()?.forEach { it.delete() }
        File(context.cacheDir, "ai").deleteRecursively()
        ready.value = false
    }

    companion object {
        const val E2B_PAGE = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
        const val E2B_FILE = "gemma-4-E2B-it.litertlm"
        /** A fixed revision of the model page, so the download is always the file with [E2B_SHA256]. */
        private const val E2B_REVISION = "1caa527c1e88b55f046fcd8d2b4265606680663d"
        const val E2B_DOWNLOAD = "$E2B_PAGE/resolve/$E2B_REVISION/$E2B_FILE?download=true"
        /** SHA-256 of that file as published (Hugging Face LFS), 2 588 147 712 bytes. */
        private const val E2B_SIZE = 2_588_147_712L
        private const val E2B_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        private const val MODEL_DIR = "models"
        private const val MB = 1024L * 1024L
        private const val MIN_SIZE = 100L * MB
    }
}
