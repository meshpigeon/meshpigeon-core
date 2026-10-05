package dev.meshpigeon.core.common.framing

/**
 * The radio frame: `COBS( envelope ‖ crc16_LE ) 0x00`
 * (docs/radio-protocol.md §2), identical on USB CDC, BLE NUS and Wi-Fi TCP.
 *
 * This is common code on purpose — the previous framing implementation lived in
 * the Android app on `java.io.ByteArrayOutputStream`, so it only ever ran on one
 * platform. Here it runs natively and in tests on every target.
 */
public object Framing {
    /**
     * The largest envelope the firmware will decode or encode: exactly 512
     * bytes — the largest legal `Pong` echoing a 500-byte `Ping` under a
     * 5-byte varint id. A future envelope larger than this makes its answer
     * vanish with no error, so the boundary is a constant, not a round number.
     */
    public const val MAX_ENVELOPE_BYTES: Int = 512

    /** Envelope plus its two CRC bytes. */
    public const val MAX_DECODED_BYTES: Int = MAX_ENVELOPE_BYTES + 2

    /** A decoded frame plus COBS overhead plus the delimiter. */
    public const val MAX_WIRE_BYTES: Int =
        MAX_DECODED_BYTES + (MAX_DECODED_BYTES + 253) / 254 + 1

    /** A raw on-air packet the radio accepts: the Semtech SX12xx silicon cap. */
    public const val MAX_RAW_PACKET_BYTES: Int = 255

    /** The delimiter that ends every frame. */
    public const val DELIMITER: Byte = 0x00

    /**
     * Frames [envelope]: CRC-16 (little-endian) appended, COBS-encoded, then
     * the `0x00` delimiter.
     *
     * @throws IllegalArgumentException if the envelope is larger than
     *   [MAX_ENVELOPE_BYTES]; the radio could not decode it either.
     */
    public fun encode(envelope: ByteArray): ByteArray {
        require(envelope.size <= MAX_ENVELOPE_BYTES) {
            "envelope of ${envelope.size} bytes exceeds the ${MAX_ENVELOPE_BYTES}-byte frame limit"
        }
        val crc = Crc16.of(envelope)
        val decoded = ByteArray(envelope.size + 2)
        envelope.copyInto(decoded)
        decoded[envelope.size] = (crc and 0xFF).toByte()
        decoded[envelope.size + 1] = ((crc shr 8) and 0xFF).toByte()
        return Cobs.encode(decoded) + DELIMITER
    }

    /**
     * Decodes one delimiter-terminated wire frame: [wire] must end with `0x00`.
     * Returns `null` if it is not a frame this side can trust.
     */
    public fun decode(wire: ByteArray): DecodedFrame? {
        if (wire.isEmpty() || wire[wire.size - 1] != DELIMITER) return null
        val decoded = Cobs.decode(wire.copyOfRange(0, wire.size - 1), MAX_DECODED_BYTES) ?: return null
        if (decoded.size < 2 || decoded.size > MAX_DECODED_BYTES) return null
        val body = decoded.copyOfRange(0, decoded.size - 2)
        val expected = Crc16.of(body)
        val actual = (decoded[decoded.size - 2].toInt() and 0xFF) or
            ((decoded[decoded.size - 1].toInt() and 0xFF) shl 8)
        return if (expected == actual) DecodedFrame(body) else null
    }
}

/** A decoded frame: the verified envelope, with the CRC stripped. */
public class DecodedFrame(public val envelope: ByteArray) {
    public val size: Int get() = envelope.size

    override fun equals(other: Any?): Boolean =
        this === other || (other is DecodedFrame && envelope.contentEquals(other.envelope))

    override fun hashCode(): Int = envelope.contentHashCode()

    override fun toString(): String = "DecodedFrame(${envelope.size} bytes)"
}

/** Why a delimiter-terminated body was not usable. */
public enum class FrameDrop {
    /** An idle delimiter: nothing accumulated since the last one. */
    EMPTY,

    /** Longer than any legal frame; thrown away up to the next delimiter. */
    OVERFLOW,

    /** Interior zero or a truncated run: not COBS. */
    COBS,

    /** Decoded, but the CRC-16 did not match. */
    CRC,

    /** Decoded to more bytes than any legal frame. */
    TOO_LONG,
}

/** What one frame's worth of input produced: a frame, or a reason it was dropped. */
public sealed interface FrameEvent {
    public val wireBytes: Int

    /** A verified envelope. */
    public data class Decoded(public val frame: DecodedFrame) : FrameEvent {
        override val wireBytes: Int get() = frame.size + 3
    }

    /** A body that was not a frame. The next delimiter self-heals the stream. */
    public data class Dropped(public val reason: FrameDrop) : FrameEvent {
        override val wireBytes: Int get() = 0
    }
}

/**
 * Streaming frame reader: feed it whatever the link produced, in any chunking,
 * and it returns the frames that completed. A body that is not a frame is
 * dropped and the reader resynchronises at the next delimiter.
 */
public class FrameReader {
    private var wire = ByteArray(Framing.MAX_WIRE_BYTES)
    private var length = 0
    private var overflowed = false

    /** Bytes currently held for an incomplete frame. */
    public val bufferedBytes: Int get() = length

    /** Forgets any partial frame; used when a link reconnects mid-frame. */
    public fun reset() {
        length = 0
        overflowed = false
    }

    /** Feeds [chunk] and returns everything it completed. */
    public fun feed(chunk: ByteArray): List<FrameEvent> {
        if (chunk.isEmpty()) return emptyList()
        val events = ArrayList<FrameEvent>(1)
        for (byte in chunk) {
            if (byte == Framing.DELIMITER) {
                when {
                    overflowed -> events += FrameEvent.Dropped(FrameDrop.OVERFLOW)
                    length == 0 -> Unit // idle delimiter: nothing accumulated
                    else -> events += decodeBuffered()
                }
                reset()
                continue
            }
            if (overflowed) continue // wait for the delimiter that ends the overrun
            if (length == wire.size) {
                // A body longer than any legal frame: stop storing, drop at the
                // delimiter, so a peer cannot make this grow.
                overflowed = true
                continue
            }
            wire[length++] = byte
        }
        return events
    }

    private fun decodeBuffered(): FrameEvent {
        val decoded = Cobs.decode(wire.copyOf(length), Framing.MAX_DECODED_BYTES)
            ?: return FrameEvent.Dropped(FrameDrop.COBS)
        if (decoded.size < 2 || decoded.size > Framing.MAX_DECODED_BYTES) {
            return FrameEvent.Dropped(FrameDrop.TOO_LONG)
        }
        val body = decoded.copyOfRange(0, decoded.size - 2)
        val expected = Crc16.of(body)
        val actual = (decoded[decoded.size - 2].toInt() and 0xFF) or
            ((decoded[decoded.size - 1].toInt() and 0xFF) shl 8)
        return if (expected == actual) {
            FrameEvent.Decoded(DecodedFrame(body))
        } else {
            FrameEvent.Dropped(FrameDrop.CRC)
        }
    }
}