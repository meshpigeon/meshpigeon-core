package dev.meshpigeon.core.common

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HexTest {
    @Test
    fun encodes_lowercase_without_a_prefix() {
        assertEquals("00ff10ab", Hex.encode(byteArrayOf(0, -1, 0x10, -0x55)))
    }

    @Test
    fun decodes_either_case() {
        assertContentEquals(byteArrayOf(0, -1, 0x10, -0x55), Hex.decode("00FF10ab"))
    }

    @Test
    fun round_trips() {
        val bytes = ByteArray(256) { it.toByte() }
        assertContentEquals(bytes, Hex.decode(Hex.encode(bytes)))
    }

    @Test
    fun rejects_odd_length_and_non_hex() {
        assertNull(Hex.decode("abc"))
        assertNull(Hex.decode("zz"))
    }
}

class Base64Test {
    @Test
    fun matches_the_rfc_4648_vectors() {
        assertEquals("", Base64.encode(ByteArray(0)))
        assertEquals("Zg==", Base64.encode("f".encodeToByteArray()))
        assertEquals("Zm8=", Base64.encode("fo".encodeToByteArray()))
        assertEquals("Zm9v", Base64.encode("foo".encodeToByteArray()))
        assertEquals("Zm9vYg==", Base64.encode("foob".encodeToByteArray()))
        assertEquals("Zm9vYmE=", Base64.encode("fooba".encodeToByteArray()))
        assertEquals("Zm9vYmFy", Base64.encode("foobar".encodeToByteArray()))
    }

    @Test
    fun round_trips_every_byte_value() {
        val bytes = ByteArray(256) { it.toByte() }
        assertContentEquals(bytes, Base64.decode(Base64.encode(bytes)))
    }

    @Test
    fun decodes_without_padding() {
        assertContentEquals("foobar".encodeToByteArray(), Base64.decode("Zm9vYmFy"))
        assertContentEquals("f".encodeToByteArray(), Base64.decode("Zg"))
    }

    @Test
    fun rejects_non_base64() {
        assertNull(Base64.decode("not base64!"))
    }
}

private class FixedClock(private val ms: Long) : Clock {
    override fun nowMs(): Long = ms
    override fun monotonicMs(): Long = ms
}

class IdsTest {
    @Test
    fun new_ids_look_like_uuid_v7() {
        val id = Ids.newId(FixedClock(0x0123456789AB))
        assertEquals(36, id.length)
        assertEquals("01234567-89ab-7", id.take(15))
        assertEquals('-', id[8])
        assertEquals('-', id[13])
        assertEquals('-', id[18])
        assertEquals('-', id[23])
    }

    @Test
    fun the_version_and_variant_nibbles_are_fixed() {
        repeat(32) {
            val id = Ids.newId()
            assertEquals('7', id[14], "version nibble in $id")
            assertTrue(id[19] in "89ab", "variant nibble in $id")
        }
    }

    @Test
    fun the_timestamp_prefix_orders_ids_by_time() {
        val early = Ids.newId(FixedClock(1_000_000L))
        val late = Ids.newId(FixedClock(1_000_001L))
        assertTrue(early < late, "$early should sort before $late")
    }

    @Test
    fun ids_are_unique() {
        val ids = (1..2_000).map { Ids.newId() }.toSet()
        assertEquals(2_000, ids.size)
    }

    @Test
    fun ids_with_a_different_clock_differ() {
        assertNotEquals(Ids.newId(FixedClock(1)), Ids.newId(FixedClock(2)))
    }
}

class PlatformClockTest {
    @Test
    fun the_platform_clock_is_readable() {
        val clock = Clock.System
        assertTrue(clock.nowMs() > 1_600_000_000_000L, "nowMs() = ${clock.nowMs()}")
        assertTrue(clock.monotonicMs() >= 0, "monotonicMs() = ${clock.monotonicMs()}")
        assertTrue(clock.monotonicMs() >= clock.monotonicMs() - 1)
    }

    @Test
    fun platform_random_fills_the_buffer() {
        assertEquals(32, platformRandomBytes(32).size)
    }
}