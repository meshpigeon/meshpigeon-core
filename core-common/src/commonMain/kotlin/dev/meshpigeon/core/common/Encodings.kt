package dev.meshpigeon.core.common

private const val HEX_DIGITS = "0123456789abcdef"

/** Hex, lowercase, no prefix — the form `mp` shows and `packet hex` prints. */
public object Hex {
    /** Encodes [bytes] as lowercase hex. */
    public fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(HEX_DIGITS[value shr 4])
            out.append(HEX_DIGITS[value and 0x0F])
        }
        return out.toString()
    }

    /** Decodes lowercase or uppercase hex, or returns `null` if it is not hex. */
    public fun decode(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = digit(text[i * 2]) ?: return null
            val lo = digit(text[i * 2 + 1]) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }
}

private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/**
 * Standard base64 with padding, for share links and QR payloads.
 *
 * Hand-rolled rather than taken from a library because the same bytes must come
 * out on every target, including the ones Kotlin's own `kotlin.io.encoding`
 * does not cover yet.
 */
public object Base64 {
    /** Encodes [bytes] as padded standard base64. */
    public fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val chunk = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(BASE64_ALPHABET[(chunk shr 18) and 0x3F])
            out.append(BASE64_ALPHABET[(chunk shr 12) and 0x3F])
            out.append(BASE64_ALPHABET[(chunk shr 6) and 0x3F])
            out.append(BASE64_ALPHABET[chunk and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val chunk = (bytes[i].toInt() and 0xFF) shl 16
                out.append(BASE64_ALPHABET[(chunk shr 18) and 0x3F])
                out.append(BASE64_ALPHABET[(chunk shr 12) and 0x3F])
                out.append("==")
            }
            2 -> {
                val chunk = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(BASE64_ALPHABET[(chunk shr 18) and 0x3F])
                out.append(BASE64_ALPHABET[(chunk shr 12) and 0x3F])
                out.append(BASE64_ALPHABET[(chunk shr 6) and 0x3F])
                out.append('=')
            }
        }
        return out.toString()
    }

    /** Decodes base64, tolerating missing padding, or returns `null`. */
    public fun decode(text: String): ByteArray? {
        val symbols = IntArray(text.length)
        var count = 0
        for (c in text) {
            when (c) {
                '=' -> continue
                else -> symbols[count++] = symbol(c) ?: return null
            }
        }
        val out = ByteArray(count * 6 / 8)
        var bits = 0
        var accumulator = 0
        var written = 0
        for (i in 0 until count) {
            accumulator = (accumulator shl 6) or symbols[i]
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[written++] = ((accumulator shr bits) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun symbol(c: Char): Int? = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> null
    }
}