package dev.meshpigeon.core.common.framing

/**
 * CRC-16/CCITT-FALSE — poly 0x1021, init 0xFFFF, no reflection, no final xor.
 * The checksum every MeshPigeon radio frame carries (docs/radio-protocol.md §2).
 *
 * Byte-for-byte the firmware's `crc16_ccitt`, so a frame built here and a frame
 * built by `meshpigeon-firmware` are the same bytes.
 */
public object Crc16 {
    public const val POLYNOMIAL: Int = 0x1021
    public const val INITIAL: Int = 0xFFFF

    /** The CRC-16 of [data], optionally starting from [init]. */
    public fun of(data: ByteArray, init: Int = INITIAL): Int {
        var crc = init and 0xFFFF
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor POLYNOMIAL else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    /** The CRC-16 of `data[from, until)`. */
    public fun of(data: ByteArray, from: Int, until: Int, init: Int = INITIAL): Int =
        of(data.copyOfRange(from, until), init)
}