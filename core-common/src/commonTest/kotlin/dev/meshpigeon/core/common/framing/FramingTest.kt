package dev.meshpigeon.core.common.framing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FramingTest {
    // "123456789" is the CRC-16/CCITT-FALSE check value (0x29B1).
    @Test
    fun crc16_matches_the_ccitt_false_check_value() {
        assertEquals(0x29B1, Crc16.of("123456789".encodeToByteArray()))
    }

    @Test
    fun crc16_of_nothing_is_the_initial_value() {
        assertEquals(Crc16.INITIAL, Crc16.of(ByteArray(0)))
    }

    @Test
    fun cobs_round_trips_every_single_byte_value() {
        for (value in 0..255) {
            val src = byteArrayOf(value.toByte())
            assertContentEquals(src, Cobs.decode(Cobs.encode(src)), "byte $value")
        }
    }

    @Test
    fun cobs_round_trips_a_body_with_every_byte_value() {
        val src = ByteArray(512) { it.toByte() }
        assertContentEquals(src, Cobs.decode(Cobs.encode(src)))
    }

    @Test
    fun cobs_encoded_body_never_contains_a_zero() {
        val src = ByteArray(300) { if (it % 3 == 0) 0 else it.toByte() }
        assertTrue(Cobs.encode(src).none { it == 0.toByte() })
    }

    @Test
    fun cobs_round_trips_the_canonical_example() {
        // 00 11 22 00 00 33: each zero closes a run and starts a new block.
        val data = byteArrayOf(0x00, 0x11, 0x22, 0x00, 0x00, 0x33)
        val encoded = byteArrayOf(0x01, 0x03, 0x11, 0x22, 0x01, 0x02, 0x33)
        assertContentEquals(encoded, Cobs.encode(data))
        assertContentEquals(data, Cobs.decode(encoded))
    }

    @Test
    fun cobs_rejects_an_interior_zero() {
        assertNull(Cobs.decode(byteArrayOf(0x02, 0x11, 0x00, 0x33)))
    }

    @Test
    fun cobs_rejects_a_truncated_run() {
        assertNull(Cobs.decode(byteArrayOf(0x05, 0x11)))
    }

    @Test
    fun cobs_rejects_a_body_that_would_exceed_the_cap() {
        val src = ByteArray(600) { it.toByte() }
        assertNull(Cobs.decode(Cobs.encode(src), maxDecoded = 100))
    }

    @Test
    fun cobs_decoding_an_empty_body_is_malformed() {
        assertNull(Cobs.decode(ByteArray(0)))
    }

    @Test
    fun encode_appends_the_crc_little_endian_then_stuffs_it() {
        val envelope = "meshpigeon".encodeToByteArray()
        val frame = Framing.encode(envelope)
        val decoded = Cobs.decode(frame.copyOfRange(0, frame.size - 1))
        assertContentEquals(envelope, decoded?.copyOfRange(0, envelope.size))
        val crc = Crc16.of(envelope)
        assertEquals(crc and 0xFF, decoded!![envelope.size].toInt() and 0xFF)
        assertEquals((crc shr 8) and 0xFF, decoded[envelope.size + 1].toInt() and 0xFF)
        assertEquals(Framing.DELIMITER, frame.last())
    }

    @Test
    fun encode_then_decode_is_the_identity() {
        val envelope = ByteArray(64) { (it * 7).toByte() }
        assertContentEquals(envelope, Framing.decode(Framing.encode(envelope))?.envelope)
    }

    @Test
    fun encode_rejects_an_oversized_envelope() {
        val tooBig = ByteArray(Framing.MAX_ENVELOPE_BYTES + 1)
        val thrown = runCatching { Framing.encode(tooBig) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException, "expected IllegalArgumentException, got $thrown")
    }

    @Test
    fun a_frame_of_the_largest_legal_envelope_still_fits_the_wire_bound() {
        val frame = Framing.encode(ByteArray(Framing.MAX_ENVELOPE_BYTES) { 0x7F })
        assertTrue(frame.size <= Framing.MAX_WIRE_BYTES, "frame of ${frame.size} exceeds the wire bound")
    }

    @Test
    fun decode_drops_a_frame_with_a_corrupt_crc() {
        val frame = Framing.encode("payload".encodeToByteArray())
        frame[3] = (frame[3].toInt() xor 0xFF).toByte()
        assertNull(Framing.decode(frame))
    }
}

class FrameReaderTest {
    @Test
    fun reads_a_frame_delivered_one_byte_at_a_time() {
        val envelope = "one byte at a time".encodeToByteArray()
        val wire = Framing.encode(envelope)
        val reader = FrameReader()
        val frames = wire.flatMap { reader.feed(byteArrayOf(it)) }
        assertEquals(listOf(FrameEvent.Decoded(DecodedFrame(envelope))), frames)
    }

    @Test
    fun reads_several_frames_from_one_chunk() {
        val reader = FrameReader()
        val chunk = Framing.encode("first".encodeToByteArray()) + Framing.encode("second".encodeToByteArray())
        val frames = reader.feed(chunk).filterIsInstance<FrameEvent.Decoded>()
        assertEquals(listOf("first", "second"), frames.map { it.frame.envelope.decodeToString() })
    }

    @Test
    fun an_idle_delimiter_is_not_an_event() {
        assertTrue(FrameReader().feed(byteArrayOf(0, 0, 0)).isEmpty())
    }

    @Test
    fun a_body_that_is_not_a_frame_is_dropped_and_the_stream_self_heals() {
        val reader = FrameReader()
        // The ESP-IDF Wi-Fi driver writes log text to the same port; that text is
        // not a frame, and it must not take the frame after it with it.
        val chunk = "I (312) wifi:mode : sta".encodeToByteArray() + 0x00 +
            Framing.encode("real".encodeToByteArray())
        val events = reader.feed(chunk)
        assertEquals(FrameEvent.Dropped(FrameDrop.COBS), events[0])
        assertEquals(FrameEvent.Decoded(DecodedFrame("real".encodeToByteArray())), events[1])
    }

    @Test
    fun a_bad_crc_drops_only_that_frame() {
        val reader = FrameReader()
        val corrupt = Framing.encode("bad".encodeToByteArray()).also {
            it[2] = (it[2].toInt() xor 0xFF).toByte()
        }
        val events = reader.feed(corrupt + Framing.encode("good".encodeToByteArray()))
        assertEquals(FrameEvent.Dropped(FrameDrop.CRC), events[0])
        assertEquals(FrameEvent.Decoded(DecodedFrame("good".encodeToByteArray())), events[1])
    }

    @Test
    fun an_overrunning_body_is_dropped_and_never_buffered() {
        val reader = FrameReader()
        val events = reader.feed(ByteArray(Framing.MAX_WIRE_BYTES + 64) { 0x41 } + 0x00)
        assertEquals(listOf(FrameEvent.Dropped(FrameDrop.OVERFLOW)), events)
        assertEquals(0, reader.bufferedBytes)
    }

    @Test
    fun a_split_frame_survives_a_reset_boundary() {
        val reader = FrameReader()
        val wire = Framing.encode("split".encodeToByteArray())
        assertTrue(reader.feed(wire.copyOfRange(0, 3)).isEmpty())
        reader.reset()
        // After a reconnect the partial frame is gone; the next frame still reads.
        val events = reader.feed(Framing.encode("after".encodeToByteArray()))
        assertEquals(listOf(FrameEvent.Decoded(DecodedFrame("after".encodeToByteArray()))), events)
    }

    @Test
    fun an_empty_chunk_is_free() {
        assertTrue(FrameReader().feed(ByteArray(0)).isEmpty())
    }
}