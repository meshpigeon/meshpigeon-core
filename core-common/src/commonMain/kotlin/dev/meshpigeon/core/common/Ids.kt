package dev.meshpigeon.core.common

/**
 * Identifiers. Every entity key in the database is a UUIDv7 string
 * (docs/db-schema.md), and this is the one place one is minted.
 *
 * Why UUIDv7 and not a random v4: the high 48 bits are a millisecond
 * timestamp, so a B-tree insert lands at the right edge of the index instead of
 * fragmenting it, and `(created_at, id)` is a stable total order.
 */
public object Ids {
    private const val HEX = "0123456789abcdef"

    /** A new UUIDv7, lowercased, hyphenated — the canonical text form. */
    public fun newId(clock: Clock = Clock.System): String {
        val ms = clock.nowMs()
        val random = platformRandomBytes(10)
        val bytes = ByteArray(16)
        // 48-bit big-endian timestamp.
        bytes[0] = (ms ushr 40).toByte()
        bytes[1] = (ms ushr 32).toByte()
        bytes[2] = (ms ushr 24).toByte()
        bytes[3] = (ms ushr 16).toByte()
        bytes[4] = (ms ushr 8).toByte()
        bytes[5] = ms.toByte()
        // version 7 in the high nibble, 12 bits of entropy.
        bytes[6] = (0x70 or (random[0].toInt() and 0x0F)).toByte()
        bytes[7] = random[1]
        // variant 10xx, then 62 bits of entropy.
        bytes[8] = (0x80 or (random[2].toInt() and 0x3F)).toByte()
        random.copyInto(bytes, destinationOffset = 9, startIndex = 3, endIndex = 10)

        val out = StringBuilder(36)
        for (i in bytes.indices) {
            if (i == 4 || i == 6 || i == 8 || i == 10) out.append('-')
            val value = bytes[i].toInt() and 0xFF
            out.append(HEX[value shr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }
}