package dev.meshpigeon.core.link.tcp

import dev.meshpigeon.core.transport.LinkDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

internal actual object Tcp {
    actual fun connect(descriptor: LinkDescriptor.Tcp): TcpConnection {
        val socket = Socket()
        socket.tcpNoDelay = true // frames are small and latency matters
        socket.connect(InetSocketAddress(descriptor.host, descriptor.port), CONNECT_TIMEOUT_MS.toInt())
        return JvmTcpConnection(socket)
    }
}

private const val CONNECT_TIMEOUT_MS = 8_000L

/**
 * One `java.net.Socket`.
 *
 * The read loop runs on `Dispatchers.IO` and stops at end of stream or error:
 * a dropped link is a normal event here, and it is reported by completing
 * [inbound].
 */
internal class JvmTcpConnection(private val socket: Socket) : TcpConnection {
    private val inboundChannel = Channel<ByteArray>(Channel.BUFFERED)
    private val loop: Job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { readLoop() }

    override val inbound: Flow<ByteArray> = inboundChannel.receiveAsFlow()

    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            socket.getOutputStream().run {
                write(bytes)
                flush()
            }
        }
    }

    override suspend fun close() {
        loop.cancel()
        withContext(Dispatchers.IO) { runCatching { socket.close() } }
        inboundChannel.close()
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            while (true) {
                val count = withContext(Dispatchers.IO) { socket.getInputStream().read(buffer) }
                if (count <= 0) break
                inboundChannel.send(buffer.copyOf(count))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // A socket error is a dropped link, which `inbound` completing says.
        } finally {
            inboundChannel.close()
        }
    }
}

private const val READ_BUFFER_BYTES = 1024