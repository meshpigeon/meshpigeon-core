@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.meshpigeon.core.link.tcp

import dev.meshpigeon.core.transport.LinkDescriptor
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.read
import platform.posix.socket
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.strerror
import platform.posix.write

/**
 * The linux TCP socket: `socket`/`connect`/`read`/`write`.
 *
 * One source file per native platform rather than one shared between them: the
 * C types around a socket do not agree (a size argument is a ULong here and a
 * UInt on Winsock, the address struct is a plain field here and a union there),
 * and the compiler refuses a generic numeric conversion it cannot prove equal
 * across a group of platforms. Per platform, each of these conversions is a
 * plain assignment.
 *
 * The compiler also warns that `reinterpret()`'s type argument is inferred as
 * an intersection (KT-71420). That is the price of asking for the pointer form
 * without naming the type; the alternative — naming it — selects the by-value
 * form and hands `connect()` a pointer to a temporary. It is left as it is, with
 * the value's type carrying the intent.
 */
internal actual object Tcp {
    actual fun connect(descriptor: LinkDescriptor.Tcp): TcpConnection {
        // Allocated on the native heap rather than inside a `memScoped` block:
        // the kernel reads the address during connect(), and scoped memory is
        // gone the moment the block returns — which shows up as "address family
        // not supported" rather than as the use-after-free it is.
        val addr = nativeHeap.alloc<sockaddr_in>()
        try {
            addr.sin_family = AF_INET.convert()
            addr.sin_port = hostToNetworkShort(descriptor.port.toUShort())
            addr.sin_addr.s_addr = ipv4InAddr(descriptor.host)
            // No type argument: spelled with one, `reinterpret` reads the
            // struct back by value and hands out a temporary. The type of this
            // value is what selects the pointer.
            val address: CPointer<sockaddr> = addr.reinterpret()
            val handle = socket(AF_INET, SOCK_STREAM, 0)
            if (handle < 0) throw socketError("socket", "could not create a socket")
            if (connect(handle, address, sizeOf<sockaddr_in>().toUInt()) != 0) {
                val failure = socketError(
                    "connect to ${descriptor.host}:${descriptor.port}",
                    "the peer refused or is unreachable",
                )
                close(handle)
                throw failure
            }
            return NativeTcpConnection(handle)
        } finally {
            nativeHeap.free(addr)
        }
    }
}

/**
 * One socket.
 *
 * The read loop runs on the IO dispatcher and stops at end of stream or error: a
 * dropped link is a normal event here, and it is reported by completing
 * [inbound].
 */
internal class NativeTcpConnection(private val descriptor: Int) : TcpConnection {
    private val inboundChannel = Channel<ByteArray>(Channel.BUFFERED)
    private val loop: Job = CoroutineScope(SupervisorJob() + ioDispatcher).launch { readLoop() }

    override val inbound: Flow<ByteArray> = inboundChannel.receiveAsFlow()

    override suspend fun write(bytes: ByteArray) {
        var written = 0
        bytes.usePinned { pinned ->
            while (written < bytes.size) {
                val count = write(
                    descriptor,
                    pinned.addressOf(written),
                    (bytes.size - written).toULong(),
                ).toInt()
                if (count <= 0) throw socketError("write", "the socket refused the bytes")
                written += count
            }
        }
    }

    override suspend fun close() {
        loop.cancel()
        close(descriptor)
        inboundChannel.close()
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            while (true) {
                val count = buffer.usePinned { pinned ->
                    read(descriptor, pinned.addressOf(0), buffer.size.toULong())
                }
                if (count <= 0L) break
                inboundChannel.send(buffer.copyOf(count.toInt()))
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

/** One read is comfortably larger than a maximum-size frame. */
private const val READ_BUFFER_BYTES = 1024

/** A socket failure carrying the platform's own errno string. */
private fun socketError(operation: String, reason: String): Throwable =
    IllegalStateException("$operation failed: $reason (${strerror(errno)}, errno $errno)")
