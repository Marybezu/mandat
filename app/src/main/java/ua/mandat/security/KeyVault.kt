package ua.mandat.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.DataInputStream
import android.system.Os
import android.system.OsConstants
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the database passphrase safe.
 *
 * The passphrase is 32 random bytes. It is stored only in encrypted form, wrapped with an
 * AES-256-GCM key that lives in the phone's hardware keystore (StrongBox when available).
 * That key can be used only right after the owner confirms with a strong biometric or the
 * phone PIN/password, once per use, and only while the phone is unlocked.
 */
class KeyVault(context: Context) {

    private val file = File(context.noBackupFilesDir, WRAPPED_FILE)
    private val invalidMarker = File(context.noBackupFilesDir, INVALID_MARKER)
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** True when a wrapped passphrase exists, i.e. the app was set up before. */
    fun isSetUp(): Boolean = file.exists() && keyStore.containsAlias(KEY_ALIAS)

    /** True when part of the key is gone, so the existing data cannot be opened. */
    fun isKeyMissing(): Boolean = file.exists() && !keyStore.containsAlias(KEY_ALIAS)

    /** Step 1 of first setup: a fresh hardware key and a cipher to be confirmed by the owner. */
    fun cipherForSetup(): Cipher {
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        val key = createKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** Step 2 of first setup, after confirmation: create, wrap and store a new passphrase. */
    fun finishSetup(confirmed: Cipher): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        val wrapped = confirmed.doFinal(passphrase)
        val tmp = File(file.parentFile, "$WRAPPED_FILE.tmp")
        FileOutputStream(tmp).use { stream ->
            val out = DataOutputStream(stream)
            out.writeByte(FORMAT_VERSION)
            out.writeByte(confirmed.iv.size)
            out.write(confirmed.iv)
            out.writeInt(wrapped.size)
            out.write(wrapped)
            out.flush()
            // Make sure it is really on the disk before it replaces anything
            stream.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Could not save the wrapped key")
        }
        syncDirectory(file.parentFile!!)
        invalidMarker.delete()
        return passphrase
    }

    /**
     * A cipher to unwrap the passphrase, to be confirmed by the owner.
     * Throws [android.security.keystore.KeyPermanentlyInvalidatedException] if the key was
     * invalidated (for example, the screen lock was removed).
     */
    fun cipherForUnlock(): Cipher {
        val (iv, _) = readWrapped()
        val key = keyStore.getKey(KEY_ALIAS, null) as SecretKey
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
    }

    /** After confirmation: the database passphrase. */
    fun finishUnlock(confirmed: Cipher): ByteArray = confirmed.doFinal(readWrapped().second)

    /** Forgets the old key completely, for a fresh start after the owner confirmed it. */
    fun reset() {
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        file.delete()
        invalidMarker.delete()
    }

    /** Remembers that the hardware key was invalidated, so the warning survives restarts. */
    fun markInvalidated() {
        invalidMarker.createNewFile()
    }

    fun isInvalidated(): Boolean = invalidMarker.exists()

    private fun syncDirectory(dir: File) {
        try {
            val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (e: Exception) {
            // Best effort; the file itself is already synced
        }
    }

    private fun readWrapped(): Pair<ByteArray, ByteArray> =
        DataInputStream(file.inputStream()).use { input ->
            val version = input.readUnsignedByte()
            require(version == FORMAT_VERSION) { "Unknown key file format" }
            val iv = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            val size = input.readInt()
            require(size in 1..1024) { "Damaged key file" }
            val wrapped = ByteArray(size).also { input.readFully(it) }
            iv to wrapped
        }

    private fun createKey(): SecretKey = try {
        generate(strongBox = true)
    } catch (e: StrongBoxUnavailableException) {
        generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // 0 = confirmation is needed for every single use of the key
            .setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            // Adding a new fingerprint must not destroy the data; the PIN still protects it
            .setInvalidatedByBiometricEnrollment(false)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "mandat_db_key_v1"
        private const val WRAPPED_FILE = "db_key.bin"
        private const val INVALID_MARKER = "db_key.invalid"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PASSPHRASE_BYTES = 32
        private const val FORMAT_VERSION = 1
    }
}
