package ua.mandat.data

/**
 * SQLCipher "raw key" form x'…': the 32 random bytes are used as the key directly,
 * without the slow password stretching that only makes sense for human passwords.
 */
fun sqlCipherRawKey(secret: ByteArray): ByteArray {
    require(secret.size == 32) { "Unexpected key size" }
    val hex = "0123456789ABCDEF"
    val out = ByteArray(3 + secret.size * 2)
    out[0] = 'x'.code.toByte()
    out[1] = '\''.code.toByte()
    secret.forEachIndexed { i, b ->
        val v = b.toInt() and 0xFF
        out[2 + i * 2] = hex[v ushr 4].code.toByte()
        out[3 + i * 2] = hex[v and 0x0F].code.toByte()
    }
    out[out.size - 1] = '\''.code.toByte()
    return out
}

/** The 32 bytes back from the x'…' form (to wrap them again with a new hardware key). */
fun rawFromSqlCipherKey(key: ByteArray): ByteArray {
    require(key.size == 67 && key[0] == 'x'.code.toByte() && key[1] == '\''.code.toByte() && key[66] == '\''.code.toByte()) { "Unexpected key form" }
    fun v(b: Byte): Int = when (val c = b.toInt().toChar()) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("Unexpected key form")
    }
    return ByteArray(32) { i -> ((v(key[2 + i * 2]) shl 4) or v(key[3 + i * 2])).toByte() }
}
