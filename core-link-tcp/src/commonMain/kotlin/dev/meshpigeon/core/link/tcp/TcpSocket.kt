package dev.meshpigeon.core.link.tcp

import dev.meshpigeon.core.transport.LinkDescriptor
import kotlinx.coroutines.flow.Flow

/**
 * A live TCP connection: bytes in, bytes out, and the end of both.
 *
 * The platform owns its own read loop and reports what arrives as a flow that
 * completes when the connection closes — which is exactly how a link drop
 * announces itself, and it keeps numbers out of the platform-facing API (an
 * `Int` or `Long` in a shared native source set is the one thing the compiler
 * will not accept across a platform group).
 */
internal interface TcpConnection {
    /** Bytes as they arrive, in whatever chunking the network produced. */
    val inbound: Flow<ByteArray>

    /** Writes every byte, or throws. */
    suspend fun write(bytes: ByteArray)

    /** Closes the connection; [inbound] then completes. */
    suspend fun close()
}

/** The platform's TCP connections. */
internal expect object Tcp {
    /** Opens a connection, or throws with the platform's reason. */
    fun connect(descriptor: LinkDescriptor.Tcp): TcpConnection
}