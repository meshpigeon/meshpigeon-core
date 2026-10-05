package dev.meshpigeon.core.common.framing

/**
 * COBS (consistent overhead byte stuffing), the byte-stuffing layer of every
 * radio frame: `0x00` can never appear inside a frame, so it is free to be the
 * delimiter.
 *
 * Mirrors the firmware's `cobs_encode`/`cobs_decode`: a run of at most 254 data
 * bytes per code byte, `0xFF` closing a full run rather than wrapping to zero.
 */
public object Cobs {
    /** The longest data run one code byte can describe. */
    public const val MAX_RUN: Int = 0xFE

    /** COBS-encodes [src]. The result contains no `0x00` and has no delimiter. */
    public fun encode(src: ByteArray): ByteArray {
        // Worst case: one code byte per 254 data bytes, plus the final one.
        val out = ByteArray(src.size + src.size / 254 + 2)
        var written = 1 // out[0] is the first (placeholder) code byte
        var codeIndex = 0
        var code = 1
        for (byte in src) {
            if (byte.toInt() == 0) {
                closeRun(out, codeIndex, code, written)
                codeIndex = written
                written++
                code = 1
                continue
            }
            out[written++] = byte
            code++
            if (code == 0xFF) {
                closeRun(out, codeIndex, code, written)
                codeIndex = written
                written++
                code = 1
            }
        }
        closeRun(out, codeIndex, code, written)
        return out.copyOf(written)
    }

    /**
     * Decodes [src], or returns `null` when it is malformed: an interior zero,
     * a truncated run, or a body that would expand past [maxDecoded].
     *
     * The cap is load-bearing, not advisory: a peer that never emits a
     * delimiter must not be able to make the reader allocate without bound.
     */
    public fun decode(src: ByteArray, maxDecoded: Int = Int.MAX_VALUE): ByteArray? {
        if (src.isEmpty()) return null
        val out = ByteArray(minOf(src.size + 3, maxDecoded))
        var written = 0
        var i = 0
        while (i < src.size) {
            val code = src[i].toInt() and 0xFF
            i++
            if (code == 0) return null // interior zero: malformed
            val run = code - 1
            if (src.size - i < run) return null // truncated run
            if (written + run > out.size) return null
            src.copyInto(out, written, i, i + run)
            written += run
            i += run
            if (code < 0xFF && i < src.size) {
                if (written >= out.size) return null
                out[written++] = 0
            }
        }
        return out.copyOf(written)
    }

    private fun closeRun(out: ByteArray, codeIndex: Int, code: Int, written: Int) {
        out[codeIndex] = code.toByte()
    }
}