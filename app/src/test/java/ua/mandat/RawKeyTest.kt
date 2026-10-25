package ua.mandat

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.mandat.data.sqlCipherRawKey

class RawKeyTest {
    @Test fun rawKeyHasSqlCipherForm() {
        val secret = ByteArray(32) { it.toByte() }
        val key = String(sqlCipherRawKey(secret), Charsets.US_ASCII)
        assertEquals(67, key.length)
        assertEquals("x'000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F'", key)
    }

    @Test fun highBytesAreEncodedCorrectly() {
        val secret = ByteArray(32) { 0xFF.toByte() }
        val key = String(sqlCipherRawKey(secret), Charsets.US_ASCII)
        assertEquals("x'" + "FF".repeat(32) + "'", key)
    }
}
