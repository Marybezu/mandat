package ua.mandat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import ua.mandat.backup.BackupCrypto

class BackupCryptoTest {
    // Small settings so the tests run fast; the app uses BackupCrypto.DEFAULT
    private val fast = BackupCrypto.KdfParams(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun argon2idMatchesTheReferenceImplementation() {
        // Computed with Python "cryptography" (OpenSSL Argon2id)
        val key = BackupCrypto.deriveKey("test-password-123", ByteArray(16) { it.toByte() }, fast)
        assertArrayEquals(hex("6b8f464729e68a660b67cb3bdb5a030fb4ac08b0c95a48c2d452173f3598c6e2"), key)
    }

    @Test fun opensAFileMadeByTheRecoveryScriptFormat() {
        val file = hex(
            "4d414e444154424b010100002000000000010110000102030405060708090a0b0c0d0e0f0c6465666768696a" +
                "6b6c6d6e6f4007f6ebea39a9c3cd59d8e27357aecca63ec653298eca9099ff507061d777dbf33f7b38",
        )
        val plain = BackupCrypto.decrypt(file, "Тестовий пароль 2026")
        assertEquals("{\"hello\":\"світ\"}", String(plain, Charsets.UTF_8))
    }

    @Test fun roundTrip() {
        val data = "Вигадана нотатка для перевірки".toByteArray()
        val file = BackupCrypto.encrypt(data, "довгий тестовий пароль", fast)
        assertArrayEquals(data, BackupCrypto.decrypt(file, "довгий тестовий пароль"))
    }

    @Test(expected = BackupCrypto.WrongPasswordOrDamaged::class)
    fun wrongPasswordIsRefused() {
        val file = BackupCrypto.encrypt("x".toByteArray(), "правильний пароль 1", fast)
        BackupCrypto.decrypt(file, "неправильний пароль 2")
    }

    @Test(expected = BackupCrypto.WrongPasswordOrDamaged::class)
    fun changedDataIsDetected() {
        val file = BackupCrypto.encrypt("дані".toByteArray(), "правильний пароль 1", fast)
        file[file.size - 20] = (file[file.size - 20].toInt() xor 1).toByte()
        BackupCrypto.decrypt(file, "правильний пароль 1")
    }

    @Test(expected = BackupCrypto.WrongPasswordOrDamaged::class)
    fun changedHeaderIsDetected() {
        val file = BackupCrypto.encrypt("дані".toByteArray(), "правильний пароль 1", fast)
        file[25] = (file[25].toInt() xor 1).toByte() // inside the salt
        BackupCrypto.decrypt(file, "правильний пароль 1")
    }

    @Test(expected = BackupCrypto.NotABackup::class)
    fun otherFilesAreRefused() {
        BackupCrypto.decrypt("not a backup at all, just text".toByteArray(), "будь-який пароль")
    }

    @Test(expected = BackupCrypto.NotABackup::class)
    fun absurdSettingsAreRefused() {
        val file = BackupCrypto.encrypt("x".toByteArray(), "правильний пароль 1", fast)
        // memory field = 0x7FFFFFFF KiB
        file[10] = 0x7F; file[11] = 0xFF.toByte(); file[12] = 0xFF.toByte(); file[13] = 0xFF.toByte()
        BackupCrypto.decrypt(file, "правильний пароль 1")
    }
}
