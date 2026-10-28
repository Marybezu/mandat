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
 * phone PIN/password, once per use, and only while the phone is unlocked. With "PIN only" the
 * passphrase is wrapped again with a key that accepts the PIN or password alone, so a finger
 * forced onto the sensor cannot open it at all, also not outside Mandat's own prompt.
 *
 * File format 2: version, flags (bit 0: PIN only), key alias, IV, wrapped passphrase.
 * Format 1 (older installs) has no flags and always uses the first alias.
 */
class KeyVault(context: Context) {

    private val file = File(context.noBackupFilesDir, WRAPPED_FILE)
    private val invalidMarker = File(context.noBackupFilesDir, INVALID_MARKER)
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private class Wrapped(val pinOnly: Boolean, val alias: String, val iv: ByteArray, val data: ByteArray)

    /** The alias the stored passphrase is wrapped with (the first one when nothing is stored). */
    private fun currentAlias(): String = try { if (file.exists()) readWrapped().alias else KEY_ALIAS } catch (e: Exception) { KEY_ALIAS }

    /** True when a wrapped passphrase exists, i.e. the app was set up before. */
    fun isSetUp(): Boolean = file.exists() && keyStore.containsAlias(currentAlias())

    /** True when part of the key is gone, so the existing data cannot be opened. */
    fun isKeyMissing(): Boolean = file.exists() && !keyStore.containsAlias(currentAlias())

    /** True when the stored passphrase opens only with the phone's PIN or password, never a fingerprint. */
    fun isPinOnly(): Boolean = try { file.exists() && readWrapped().pinOnly } catch (e: Exception) { false }

    /** Step 1 of first setup: a fresh hardware key and a cipher to be confirmed by the owner. */
    fun cipherForSetup(): Cipher {
        // A fresh start: every key this app made before goes
        ownAliases().forEach { keyStore.deleteEntry(it) }
        val key = createKey(KEY_ALIAS, pinOnly = false)
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** Step 2 of first setup, after confirmation: create, wrap and store a new passphrase. */
    fun finishSetup(confirmed: Cipher): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        writeWrapped(Wrapped(false, KEY_ALIAS, confirmed.iv, confirmed.doFinal(passphrase)))
        invalidMarker.delete()
        return passphrase
    }

    /**
     * Step 1 of switching "PIN only" on or off: a new hardware key that accepts only the PIN or
     * password ([pinOnly]) or also a fingerprint, and a cipher to be confirmed with the PIN.
     * Nothing stored changes until [finishRewrap]; [abandonRewrap] removes the new key.
     */
    fun cipherForRewrap(pinOnly: Boolean): Pair<Cipher, String> {
        val alias = KEY_PREFIX + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        val key = createKey(alias, pinOnly)
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) } to alias
    }

    /**
     * Step 2, after the PIN confirmed the new key: the same passphrase is stored wrapped with it,
     * atomically, and only then is the old key deleted. A failure leaves the old file and key as they were.
     */
    fun finishRewrap(confirmed: Cipher, alias: String, pinOnly: Boolean, passphrase: ByteArray) {
        require(passphrase.size == PASSPHRASE_BYTES)
        val old = currentAlias()
        try {
            writeWrapped(Wrapped(pinOnly, alias, confirmed.iv, confirmed.doFinal(passphrase)))
        } catch (e: Exception) {
            abandonRewrap(alias)
            throw e
        }
        if (old != alias && keyStore.containsAlias(old)) keyStore.deleteEntry(old)
    }

    fun abandonRewrap(alias: String) {
        try { if (alias != currentAlias() && keyStore.containsAlias(alias)) keyStore.deleteEntry(alias) } catch (e: Exception) { }
    }

    /** Keys left over from a switch that stopped halfway (the app was closed during the prompt). */
    fun cleanUpOldKeys() {
        try {
            if (!file.exists()) return
            val keep = currentAlias()
            ownAliases().filter { it != keep }.forEach { keyStore.deleteEntry(it) }
        } catch (e: Exception) {
            // Best effort; an extra unused key is harmless
        }
    }

    private fun ownAliases(): List<String> = keyStore.aliases().toList().filter { it == KEY_ALIAS || it.startsWith(KEY_PREFIX) }

    private fun writeWrapped(w: Wrapped) {
        val tmp = File(file.parentFile, "$WRAPPED_FILE.tmp")
        FileOutputStream(tmp).use { stream ->
            val out = DataOutputStream(stream)
            out.writeByte(FORMAT_VERSION)
            out.writeByte(if (w.pinOnly) 1 else 0)
            out.writeUTF(w.alias)
            out.writeByte(w.iv.size)
            out.write(w.iv)
            out.writeInt(w.data.size)
            out.write(w.data)
            out.flush()
            // Make sure it is really on the disk before it replaces anything
            stream.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Could not save the wrapped key")
        }
        syncDirectory(file.parentFile!!)
    }

    /**
     * A cipher to unwrap the passphrase, to be confirmed by the owner.
     * Throws [android.security.keystore.KeyPermanentlyInvalidatedException] if the key was
     * invalidated (for example, the screen lock was removed).
     */
    fun cipherForUnlock(): Cipher {
        val w = readWrapped()
        val key = keyStore.getKey(w.alias, null) as SecretKey
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, w.iv))
        }
    }

    /** After confirmation: the database passphrase. */
    fun finishUnlock(confirmed: Cipher): ByteArray = confirmed.doFinal(readWrapped().data)

    /** Forgets the old key completely, for a fresh start after the owner confirmed it. */
    fun reset() {
        ownAliases().forEach { keyStore.deleteEntry(it) }
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

    private fun readWrapped(): Wrapped =
        DataInputStream(file.inputStream()).use { input ->
            val version = input.readUnsignedByte()
            require(version == 1 || version == FORMAT_VERSION) { "Unknown key file format" }
            val pinOnly = version == FORMAT_VERSION && (input.readUnsignedByte() and 1) == 1
            val alias = if (version == FORMAT_VERSION) input.readUTF() else KEY_ALIAS
            require(alias == KEY_ALIAS || (alias.startsWith(KEY_PREFIX) && alias.length <= 64)) { "Damaged key file" }
            val iv = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            val size = input.readInt()
            require(size in 1..1024) { "Damaged key file" }
            val wrapped = ByteArray(size).also { input.readFully(it) }
            Wrapped(pinOnly, alias, iv, wrapped)
        }

    private fun createKey(alias: String, pinOnly: Boolean): SecretKey = try {
        generate(alias, pinOnly, strongBox = true)
    } catch (e: StrongBoxUnavailableException) {
        generate(alias, pinOnly, strongBox = false)
    }

    private fun generate(alias: String, pinOnly: Boolean, strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // 0 = confirmation is needed for every single use of the key
            // "PIN only": the hardware itself refuses a fingerprint for this key
            .setUserAuthenticationParameters(
                0,
                if (pinOnly) KeyProperties.AUTH_DEVICE_CREDENTIAL
                else KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
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
        /** Keys made when switching "PIN only" carry this prefix and a random part. */
        private const val KEY_PREFIX = "mandat_db_key_r_"
        private const val WRAPPED_FILE = "db_key.bin"
        private const val INVALID_MARKER = "db_key.invalid"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PASSPHRASE_BYTES = 32
        private const val FORMAT_VERSION = 2
    }
}
