package ua.mandat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.mandat.backup.BackupCrypto

class BackupCryptoTest {
    // Small settings so the tests run fast; the app uses BackupCrypto.DEFAULT
    private val fast = BackupCrypto.KdfParams(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)
    private val fastProfiles = listOf(fast)

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun argon2idMatchesTheReferenceImplementation() {
        // Computed with Python "cryptography" (OpenSSL Argon2id)
        val key = BackupCrypto.deriveKey("test-password-123", ByteArray(16) { it.toByte() }, fast)
        assertArrayEquals(hex("6b8f464729e68a660b67cb3bdb5a030fb4ac08b0c95a48c2d452173f3598c6e2"), key)
    }

    @Test fun opensAFileMadeByTheRecoveryScriptFormat() {
        // Made with scripts/decrypt_backup.py's format in Python
        val file = hex(
            "000102030405060708090a0b0c0d0e0f6465666768696a6b6c6d6e6f4007f6ebea39a9c3cd59d8e27357" +
                "aecca63ec653b749837261577db0b4469e17b629ed3a",
        )
        val plain = BackupCrypto.decrypt(file, "Тестовий пароль 2026", fastProfiles)
        assertEquals("{\"hello\":\"світ\"}", String(plain, Charsets.UTF_8))
    }

    @Test fun roundTrip() {
        val data = "Вигадана нотатка для перевірки".toByteArray()
        val file = BackupCrypto.encrypt(data, "довгий тестовий пароль", fast)
        assertArrayEquals(data, BackupCrypto.decrypt(file, "довгий тестовий пароль", fastProfiles))
    }

    @Test fun fileHasNoReadableMarker() {
        val file = BackupCrypto.encrypt("x".toByteArray(), "довгий тестовий пароль", fast)
        val text = String(file, Charsets.ISO_8859_1)
        assertFalse(text.contains("MANDAT", ignoreCase = true))
        assertEquals(16 + 12 + 1 + 16, file.size)
    }

    @Test(expected = BackupCrypto.WrongPasswordOrDamaged::class)
    fun wrongPasswordIsRefused() {
        val file = BackupCrypto.encrypt("x".toByteArray(), "правильний пароль 1", fast)
        BackupCrypto.decrypt(file, "неправильний пароль 2", fastProfiles)
    }

    @Test(expected = BackupCrypto.WrongPasswordOrDamaged::class)
    fun changedDataIsDetected() {
        val file = BackupCrypto.encrypt("дані для перевірки".toByteArray(), "правильний пароль 1", fast)
        file[file.size - 20] = (file[file.size - 20].toInt() xor 1).toByte()
        BackupCrypto.decrypt(file, "правильний пароль 1", fastProfiles)
    }

    @Test(expected = BackupCrypto.WrongPasswordOrDamaged::class)
    fun changedNonceIsDetected() {
        val file = BackupCrypto.encrypt("дані".toByteArray(), "правильний пароль 1", fast)
        file[20] = (file[20].toInt() xor 1).toByte()
        BackupCrypto.decrypt(file, "правильний пароль 1", fastProfiles)
    }

    @Test(expected = BackupCrypto.NotABackup::class)
    fun tinyFilesAreRefused() {
        BackupCrypto.decrypt(ByteArray(20), "будь-який пароль", fastProfiles)
    }

    @Test fun suggestedPasswordHasSixWords() {
        val words = (0 until 2048).map { "word$it" }
        val password = BackupCrypto.suggestPassword(words)
        assertEquals(6, password.split("-").size)
        assertFalse(BackupCrypto.isTooSimple(password))
    }

    @Test fun obviouslyWeakPasswordsAreRefused() {
        assertTrue(BackupCrypto.isTooSimple("aaaaaaaaaaaa"))
        assertTrue(BackupCrypto.isTooSimple("123456789012"))
        assertTrue(BackupCrypto.isTooSimple("short"))
        assertTrue(BackupCrypto.isTooSimple("password12345678"))
        assertTrue(BackupCrypto.isTooSimple("qwertyuiop123456"))
        assertTrue(BackupCrypto.isTooSimple("1234567890123456"))
        assertFalse(BackupCrypto.isTooSimple("три слова разом добре"))
    }
}
