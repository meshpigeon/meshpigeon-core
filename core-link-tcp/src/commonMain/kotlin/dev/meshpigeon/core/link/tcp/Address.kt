package dev.meshpigeon.core.link.tcp

/**
 * Address handling, shared by every platform that speaks TCP.
 *
 * Addresses are IPv4 literals, plus `localhost`. Name resolution is deliberately
 * absent: `getaddrinfo` is missing from Kotlin/Native's MinGW POSIX surface, and
 * a resolver that works on some platforms and not others is worse than an honest
 * limitation. The mDNS lookup a real board advertises arrives with link
 * discovery, not with the transport.
 */

/**
 * Parses a dotted quad into network byte order.
 *
 * @throws IllegalArgumentException for anything that is not an IPv4 literal, or
 *   `localhost` — the one name worth resolving without a resolver.
 */
internal fun ipv4Address(host: String): UInt {
    if (host.equals("localhost", ignoreCase = true)) return 0x7F000001u
    val parts = host.split('.')
    require(parts.size == 4) { "'$host' is not an IPv4 address, and name resolution is not supported yet" }
    var address = 0u
    for (part in parts) {
        val octet = part.toUIntOrNull() ?: throw IllegalArgumentException("'$host' is not an IPv4 address")
        require(octet <= 255u) { "'$host' is not an IPv4 address" }
        address = (address shl 8) or octet
    }
    return address
}

/**
 * The address as `in_addr.s_addr` holds it.
 *
 * `s_addr` is a `UInt` in the struct, and on the little-endian targets this
 * project ships that means storing the byte-swapped value is what puts
 * 127.0.0.1 in memory as `7f 00 00 01`. Getting this backwards produces a
 * connect that times out against an address nobody is listening on, which is
 * what `SockaddrLayoutTest` exists to prevent.
 */
internal fun ipv4InAddr(host: String): UInt = ipv4Address(host).byteSwap()

/** The four bytes of a 32-bit value, in the other order. */
private fun UInt.byteSwap(): UInt =
    ((this and 0xFFu) shl 24) or ((this and 0xFF00u) shl 8) or ((this shr 8) and 0xFF00u) or (this shr 24)

/** A dotted quad as four network-order bytes. */
internal fun ipv4Octets(host: String): ByteArray {
    val address = ipv4Address(host)
    return ByteArray(4) { index -> (address shr ((3 - index) * 8)).toByte() }
}

/** `htons`: every target this ships is little-endian, so the port is swapped. */
internal fun hostToNetworkShort(port: UShort): UShort =
    (((port.toInt() shl 8) or (port.toInt() shr 8)) and 0xFFFF).toUShort()
