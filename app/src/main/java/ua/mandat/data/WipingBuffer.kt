package ua.mandat.data

/**
 * An in-memory byte stream for plain text that clears every copy it made: each time it grows the
 * old array is wiped, and [wipe] clears the last one. Nothing is left for the garbage collector.
 */
class WipingBuffer(initial: Int = 64 * 1024) : java.io.OutputStream() {
    private var buf = ByteArray(maxOf(16, initial))
    var size = 0
        private set

    private fun ensure(extra: Int) {
        val need = size + extra
        if (need < 0) throw OutOfMemoryError()
        if (need <= buf.size) return
        var cap = buf.size
        while (cap < need) cap = if (cap > Int.MAX_VALUE / 2) need else cap * 2
        val bigger = buf.copyOf(cap)
        buf.fill(0)
        buf = bigger
    }

    override fun write(b: Int) {
        ensure(1)
        buf[size++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
        ensure(len)
        System.arraycopy(b, off, buf, size, len)
        size += len
    }

    /** A copy of what was written; the caller clears it after use. */
    fun toByteArray(): ByteArray = buf.copyOf(size)

    fun wipe() {
        buf.fill(0)
        size = 0
    }
}
