package dev.meshpigeon.core.link.tcp

import dev.meshpigeon.core.transport.LinkDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How a host and a port become the bytes a `sockaddr_in` holds.
 *
 * The address is the field that bites: `in_addr.s_addr` is a `UInt`, and on the
 * little-endian targets this project ships, storing the obvious value leaves
 * `01 00 00 7f` in memory where the kernel expects `7f 00 00 01`. The symptom is
 * a connect that times out against an address nobody is listening on, which is
 * the sort of bug that costs an afternoon — hence this test.
 */
class SockaddrLayoutTest {
    @Test
    fun an_address_is_held_in_network_order() {
        // 127.0.0.1, byte for byte, as it must sit in memory.
        assertEquals(0x7F000001u, ipv4Address("127.0.0.1"))
        // And therefore stored swapped, because s_addr is read little-endian:
        // 0x0100007F in a little-endian register is 7f 00 00 01 in memory.
        assertEquals(0x0100007Fu, ipv4InAddr("127.0.0.1"))
    }

    @Test
    fun every_octet_lands_in_its_own_place() {
        // 192.168.1.42 must not become 2a.01.a8.c0 anywhere.
        assertEquals(0xC0A8012Au, ipv4Address("192.168.1.42"))
        assertEquals(0x2A01A8C0u, ipv4InAddr("192.168.1.42"))
    }

    @Test
    fun localhost_and_the_loopback_literal_agree() {
        assertEquals(ipv4InAddr("127.0.0.1"), ipv4InAddr("localhost"))
    }

    @Test
    fun a_name_it_cannot_resolve_is_refused_rather_than_guessed() {
        val failure = runCatching { ipv4InAddr("radio.local") }.exceptionOrNull()
        assertEquals(true, failure is IllegalArgumentException)
    }

    @Test
    fun the_port_is_swapped_for_network_order() {
        // 4448 = 0x1160, so the wire sees 0x6011; 5000 = 0x1388 -> 0x8813.
        assertEquals(0x6011.toUShort(), hostToNetworkShort(4448u))
        assertEquals(0x8813.toUShort(), hostToNetworkShort(5000u))
        // 8801 is the port the simulator listens on.
        assertEquals(0x6122.toUShort(), hostToNetworkShort(8801u))
    }

    @Test
    fun the_descriptor_is_what_the_session_passes_down() {
        val descriptor = LinkDescriptor.Tcp("10.0.0.5", 5000)
        assertEquals(0x0A000005u, ipv4Address(descriptor.host))
        assertEquals(0x8813, hostToNetworkShort(descriptor.port.toUShort()).toInt())
    }
}
