package ua.mandat.backup

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The backup file: a small readable header, then the data encrypted with AES-256-GCM.
 * The key comes from the owner's password through Argon2id, which is deliberately slow and
 * memory-hungry so that guessing passwords is very expensive. The header is authenticated
 * too, so any change to the file is detected.
 *
 * Layout: "MANDATBK" | version u8 | kdf u8 | memoryKiB i32 | iterations i32 | parallelism u8 |
 *         salt len u8 | salt | nonce len u8 | nonce | ciphertext with 16-byte tag
 */
object BackupCrypto {

    data class KdfParams(val memoryKiB: Int, val iterations: Int, val parallelism: Int)

    /** 64 MiB, 3 passes: a few seconds on a phone, very costly for an attacker. */
    val DEFAULT = KdfParams(memoryKiB = 64 * 1024, iterations = 3, parallelism = 1)

    const val MIN_PASSWORD_LENGTH = 12

    private val MAGIC = "MANDATBK".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val KDF_ARGON2ID = 1
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val MAX_FILE_BYTES = 256L * 1024 * 1024

    /** The password or the file is wrong; deliberately the same for both. */
    class WrongPasswordOrDamaged : Exception()

    /** Not a Mandat backup, or made by a newer version. */
    class NotABackup : Exception()

    fun encrypt(plain: ByteArray, password: String, params: KdfParams = DEFAULT): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val header = header(params, salt, nonce)
        val key = deriveKey(password, salt, params)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            return header + cipher.doFinal(plain)
        } finally {
            key.fill(0)
        }
    }

    fun decrypt(file: ByteArray, password: String): ByteArray {
        if (file.size > MAX_FILE_BYTES) throw NotABackup()
        val parsed = parse(file)
        val key = deriveKey(password, parsed.salt, parsed.params)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, parsed.nonce))
            cipher.updateAAD(file, 0, parsed.headerSize)
            return cipher.doFinal(file, parsed.headerSize, file.size - parsed.headerSize)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordOrDamaged()
        } finally {
            key.fill(0)
        }
    }

    private class Parsed(val params: KdfParams, val salt: ByteArray, val nonce: ByteArray, val headerSize: Int)

    private fun parse(file: ByteArray): Parsed {
        try {
            val input = DataInputStream(file.inputStream())
            val magic = ByteArray(MAGIC.size).also { input.readFully(it) }
            if (!magic.contentEquals(MAGIC)) throw NotABackup()
            if (input.readUnsignedByte() != VERSION) throw NotABackup()
            if (input.readUnsignedByte() != KDF_ARGON2ID) throw NotABackup()
            val params = KdfParams(input.readInt(), input.readInt(), input.readUnsignedByte())
            // Refuse absurd settings, so a crafted file cannot freeze or crash the phone
            if (params.memoryKiB !in 8 * 1024..1024 * 1024 ||
                params.iterations !in 1..20 ||
                params.parallelism !in 1..8
            ) throw NotABackup()
            val saltSize = input.readUnsignedByte()
            if (saltSize != SALT_BYTES) throw NotABackup()
            val salt = ByteArray(saltSize).also { input.readFully(it) }
            val nonceSize = input.readUnsignedByte()
            if (nonceSize != NONCE_BYTES) throw NotABackup()
            val nonce = ByteArray(nonceSize).also { input.readFully(it) }
            val headerSize = MAGIC.size + 1 + 1 + 4 + 4 + 1 + 1 + saltSize + 1 + nonceSize
            if (file.size < headerSize + TAG_BITS / 8) throw NotABackup()
            return Parsed(params, salt, nonce, headerSize)
        } catch (e: java.io.EOFException) {
            throw NotABackup()
        }
    }

    private fun header(params: KdfParams, salt: ByteArray, nonce: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write(MAGIC)
            writeByte(VERSION)
            writeByte(KDF_ARGON2ID)
            writeInt(params.memoryKiB)
            writeInt(params.iterations)
            writeByte(params.parallelism)
            writeByte(salt.size)
            write(salt)
            writeByte(nonce.size)
            write(nonce)
            flush()
        }
        return bytes.toByteArray()
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
}
