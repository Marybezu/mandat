package ua.mandat.backup

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The backup file. It has no readable header or marker at all: to anyone without the password
 * it looks like random bytes, so it does not reveal that it comes from Mandat.
 *
 * Layout: salt (16) | nonce (12) | AES-256-GCM ciphertext with 16-byte tag.
 * The key comes from the password through Argon2id with fixed settings (see [PROFILES]);
 * salt and nonce are authenticated as associated data, so any change to the file is detected.
 * If the settings ever change, a new profile is added and old files still open.
 */
object BackupCrypto {

    data class KdfParams(val memoryKiB: Int, val iterations: Int, val parallelism: Int)

    /** 64 MiB, 3 passes: a few seconds on a phone, very costly for an attacker. */
    val DEFAULT = KdfParams(memoryKiB = 64 * 1024, iterations = 3, parallelism = 1)

    /** Settings tried when opening a file, newest first. Only fixed values: nothing is read from the file. */
    internal val PROFILES = listOf(DEFAULT)

    const val MIN_PASSWORD_LENGTH = 16
    const val MAX_FILE_BYTES = 16L * 1024 * 1024

    /**
     * The first bytes inside an export file (.mandatx), after decryption. A backup holds JSON
     * instead, so each kind of file is recognised and never taken for the other.
     */
    val EXPORT_MARK: ByteArray = "MANDATX1".toByteArray(Charsets.US_ASCII)

    fun startsWithExportMark(plain: ByteArray): Boolean =
        plain.size >= EXPORT_MARK.size && EXPORT_MARK.indices.all { plain[it] == EXPORT_MARK[it] }

    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16

    /** The password or the file is wrong; deliberately the same for both. */
    class WrongPasswordOrDamaged : Exception()

    /** Too small or too large to be a backup. */
    class NotABackup : Exception()

    fun encrypt(plain: ByteArray, password: String, params: KdfParams = DEFAULT): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val header = salt + nonce
        val key = deriveKey(password, salt, params)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            cipher.updateAAD(header)
            return header + cipher.doFinal(plain)
        } finally {
            key.fill(0)
        }
    }

    fun decrypt(file: ByteArray, password: String, profiles: List<KdfParams> = PROFILES): ByteArray {
        val headerSize = SALT_BYTES + NONCE_BYTES
        if (file.size < headerSize + TAG_BYTES || file.size > MAX_FILE_BYTES) throw NotABackup()
        val salt = file.copyOfRange(0, SALT_BYTES)
        val nonce = file.copyOfRange(SALT_BYTES, headerSize)
        for (params in profiles) {
            val key = deriveKey(password, salt, params)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
                cipher.updateAAD(file, 0, headerSize)
                return cipher.doFinal(file, headerSize, file.size - headerSize)
            } catch (e: AEADBadTagException) {
                // Try the next profile
            } finally {
                key.fill(0)
            }
        }
        throw WrongPasswordOrDamaged()
    }

    /**
     * For automatic backups: a fresh salt and the key from the password, computed once when the
     * owner turns them on. Files made with it have the same layout and open with the password.
     */
    fun newAutoKey(password: String): Pair<ByteArray, ByteArray> {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        return salt to deriveKey(password, salt, DEFAULT)
    }

    /** Same file layout as [encrypt], with a key derived earlier; a fresh nonce every time. */
    fun encryptWithKey(plain: ByteArray, key: ByteArray, salt: ByteArray): ByteArray {
        require(key.size == 32 && salt.size == SALT_BYTES)
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val header = salt + nonce
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    /** Opens a file with a key derived earlier (to check an automatic backup right after writing it). */
    fun decryptWithKey(file: ByteArray, key: ByteArray): ByteArray {
        val headerSize = SALT_BYTES + NONCE_BYTES
        if (file.size < headerSize + TAG_BYTES || file.size > MAX_FILE_BYTES) throw NotABackup()
        val nonce = file.copyOfRange(SALT_BYTES, headerSize)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(file, 0, headerSize)
        return try {
            cipher.doFinal(file, headerSize, file.size - headerSize)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordOrDamaged()
        }
    }

    internal fun deriveKey(password: String, salt: ByteArray, params: KdfParams): ByteArray {
        val passwordBytes = java.text.Normalizer.normalize(password, java.text.Normalizer.Form.NFC)
            .toByteArray(Charsets.UTF_8)
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(params.memoryKiB)
                .withIterations(params.iterations)
                .withParallelism(params.parallelism)
                .build(),
        )
        val key = ByteArray(32)
        generator.generateBytes(passwordBytes, key)
        passwordBytes.fill(0)
        return key
    }

    /**
     * A password of six random words from the EFF list (about 77 bits): strong, and easy to
     * write down and type. [words] is the list; every word is equally likely.
     */
    fun suggestPassword(words: List<String>, count: Int = 6): String {
        require(words.size >= 1024)
        val random = SecureRandom()
        return (1..count).joinToString("-") { words[random.nextInt(words.size)] }
    }

    /**
     * Refuses passwords that are obviously weak: too short, too few different characters,
     * only digits, or built from common words and keyboard rows ("password1234", "qwertyuiop12").
     * A self-made password needs [MIN_PASSWORD_LENGTH] characters; the suggested one is always fine.
     */
    fun isTooSimple(password: String): Boolean {
        if (password.length < MIN_PASSWORD_LENGTH) return true
        if (password.toSet().size < 6) return true
        if (password.all { it.isDigit() } && password.length < 24) return true
        val lower = password.lowercase()
        val withoutCommon = COMMON_PARTS.fold(lower) { acc, part -> acc.replace(part, "") }
        // What is left after removing common parts and digits must still be substantial
        return withoutCommon.filterNot { it.isDigit() }.length < 8
    }

    private val COMMON_PARTS = listOf(
        "password", "passw0rd", "qwertyuiop", "asdfghjkl", "zxcvbnm", "йцукенгшщзхї", "фівапролджє",
        "ячсмитьбю", "1234567890", "0987654321", "admin", "welcome", "letmein", "iloveyou",
        "пароль", "mandat", "мандат",
    )
}
