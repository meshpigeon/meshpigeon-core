@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.meshpigeon.core.link.usb

import dev.meshpigeon.core.transport.LinkCandidate
import dev.meshpigeon.core.transport.LinkDescriptor
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.pointed
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import platform.posix.B115200
import platform.posix.B19200
import platform.posix.B38400
import platform.posix.B57600
import platform.posix.B9600
import platform.posix.CLOCAL
import platform.posix.CREAD
import platform.posix.CS8
import platform.posix.CSIZE
import platform.posix.EINTR
import platform.posix.IGNPAR
import platform.posix.O_NOCTTY
import platform.posix.O_NONBLOCK
import platform.posix.O_RDWR
import platform.posix.POLLERR
import platform.posix.POLLHUP
import platform.posix.POLLIN
import platform.posix.POLLNVAL
import platform.posix.TCSANOW
import platform.posix.cfmakeraw
import platform.posix.cfsetispeed
import platform.posix.cfsetospeed
import platform.posix.close
import platform.posix.closedir
import platform.posix.errno
import platform.posix.open
import platform.posix.opendir
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read
import platform.posix.readdir
import platform.posix.strerror
import platform.posix.tcgetattr
import platform.posix.tcsetattr
import platform.posix.termios
import platform.posix.write

/**
 * The Linux serial port: `open`/`tcgetattr`/`cfmakeraw`/`tcsetattr`, then
 * `poll` and `read`.
 *
 * One source file per native platform rather than one shared between them, for
 * the same reason the TCP socket has one: the C types here do not agree
 * (`tcflag_t` and the baud constants are `UInt` on Linux and `ULong` on macOS,
 * `nfds_t` differs in width), and the compiler refuses a generic numeric
 * conversion it cannot prove equal across a group of platforms. Per platform,
 * each of these conversions is a plain assignment.
 *
 * The configuration is `cfmakeraw` plus 8N1 and ignore-modem-control:
 *
 * * **Raw, not `icanon`.** The firmware sends frames as they happen; line
 *   discipline would hold every byte until a newline, and a radio that reports
 *   without one would look silent.
 * * **`IGNPAR`.** A radio unplugged mid-byte resets the line and shifts garbage
 *   in. Without it, every unplug looks like framing corruption; with it, a real
 *   framing error is still reported.
 * * **`CLOCAL | CREAD`.** No modem control, and keep receiving.
 */
public actual object UsbSerial {
    actual fun open(descriptor: LinkDescriptor.Usb): SerialPort {
        // O_NOCTTY: opening a serial device must not make it *our* controlling
        // terminal, or the write that follows stops the session with SIGTTOU.
        // O_NONBLOCK: the read loop polls with a timeout, so close() is prompt.
        // A blocking read in another thread does not wake on close, and on Linux
        // the descriptor can be reused by then.
        val handle = open(descriptor.devicePath, O_RDWR or O_NOCTTY or O_NONBLOCK)
        if (handle < 0) {
            throw serialError("open ${descriptor.devicePath}", "the port could not be opened")
        }
        try {
            memScoped {
                // `alloc` hands back the struct itself; the C functions want a
                // pointer to it, and this is how you spell that without a
                // second allocation: same memory, two views.
                val attributes: termios = alloc()
                // No type argument: spelled with one, `reinterpret` reads the
                // struct back by value. The type of this value is what selects
                // the pointer.
                val settings: CPointer<termios> = attributes.reinterpret()
                if (tcgetattr(handle, settings) != 0) {
                    throw serialError("open ${descriptor.devicePath}", "it is not a serial port")
                }
                cfmakeraw(settings)
                attributes.c_iflag = attributes.c_iflag or IGNPAR.convert<UInt>()
                attributes.c_cflag = (attributes.c_cflag and CSIZE.convert<UInt>().inv()) or
                    CS8.convert<UInt>() or CLOCAL.convert<UInt>() or CREAD.convert<UInt>()
                attributes.c_cc[VMIN_INDEX] = 0u
                attributes.c_cc[VTIME_INDEX] = 0u
                val speed = baudConstant(descriptor.baudRate)
                cfsetispeed(settings, speed)
                cfsetospeed(settings, speed)
                if (tcsetattr(handle, TCSANOW, settings) != 0) {
                    throw serialError("open ${descriptor.devicePath}", "its settings could not be applied")
                }
            }
            return NativeSerialPort(handle, descriptor)
        } catch (e: Throwable) {
            close(handle)
            throw e
        }
    }

    /** The device nodes that exist: enough to offer a path without a USB library. */
    actual fun ports(): List<LinkCandidate> = serialDeviceNames()
        .map { it to it.substringAfterLast('/') }
        .distinctBy { it.first }
        .sortedBy { it.second }
        .map { (path, name) -> LinkCandidate(LinkDescriptor.Usb(path), name) }

    /** `/dev/ttyACM*` and `/dev/ttyUSB*`, read out of the directory itself. */
    private fun serialDeviceNames(): List<String> {
        val directory = opendir(DEVICE_DIRECTORY) ?: return emptyList()
        try {
            return buildList {
                while (true) {
                    val entry = readdir(directory) ?: break
                    val name = entry.pointed.d_name.toKString()
                    if (name.startsWith("ttyACM") || name.startsWith("ttyUSB")) {
                        add("$DEVICE_DIRECTORY/$name")
                    }
                }
            }
        } finally {
            closedir(directory)
        }
    }
}

private const val DEVICE_DIRECTORY = "/dev"

/**
 * The `c_cc` slots for VMIN and VTIME.
 *
 * They are the POSIX indices (6 and 5) rather than the C macros, because the
 * generated constants are typed per platform and the slots are not.
 */
private const val VMIN_INDEX = 6
private const val VTIME_INDEX = 5

/**
 * The baud rate as the C constant.
 *
 * `cfsetispeed` takes a `speed_t`, which is an opaque integer: the B* values
 * are the POSIX ones. An unmapped rate is refused rather than opening the port
 * at a speed nobody asked for.
 */
private fun baudConstant(baudRate: Int): UInt = when (baudRate) {
    9600 -> B9600.convert()
    19200 -> B19200.convert()
    38400 -> B38400.convert()
    57600 -> B57600.convert()
    115200 -> B115200.convert()
    else -> throw IllegalArgumentException(
        "unsupported baud rate $baudRate: this build knows 9600, 19200, 38400, 57600 and 115200",
    )
}

/**
 * One open port.
 *
 * The read loop polls rather than blocking in `read()`, so closing the link is
 * prompt and a cancelled coroutine does not leave a thread parked in the kernel
 * on a descriptor that may already have been reused.
 */
internal class NativeSerialPort(
    private val handle: Int,
    private val port: LinkDescriptor.Usb,
) : SerialPort {
    private val inboundChannel = Channel<ByteArray>(Channel.BUFFERED)
    private val loop: Job = CoroutineScope(SupervisorJob() + ioDispatcher).launch { readLoop() }

    override val inbound: Flow<ByteArray> = inboundChannel.receiveAsFlow()

    override suspend fun write(bytes: ByteArray) {
        var written = 0
        bytes.usePinned { pinned ->
            while (written < bytes.size) {
                val count = write(handle, pinned.addressOf(written), (bytes.size - written).toULong())
                if (count <= 0) {
                    throw serialError("write to ${port.devicePath}", "the port refused the bytes")
                }
                written += count.toInt()
            }
        }
    }

    override suspend fun close() {
        loop.cancel()
        close(handle)
        inboundChannel.close()
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            // `poll` wants a pointer to a struct that outlives the call, so the
            // whole loop lives inside one `memScoped` block: the allocation is
            // released when the loop returns, and suspending inside the block
            // is safe because this coroutine is the scope's only user.
            memScoped {
                val descriptor: pollfd = alloc()
                val waiting: CPointer<pollfd> = descriptor.reinterpret()
                descriptor.fd = handle
                descriptor.events = POLLIN.toShort()
                descriptor.revents = 0
                val fatal = (POLLERR or POLLHUP or POLLNVAL).toInt()
                val readable = POLLIN.toInt()
                while (true) {
                    val ready = poll(waiting, 1u, POLL_TIMEOUT_MS)
                    if (ready < 0) {
                        // A signal interrupted the wait; go round again.
                        if (errno == EINTR) continue
                        break
                    }
                    if (ready == 0) continue
                    val events = descriptor.revents.toInt()
                    // HUP or ERR with nothing readable means the device went
                    // away. With data still readable it is drained first: an
                    // unplug can leave the last frame in the buffer.
                    if (events and fatal != 0 && events and readable == 0) break
                    val count = buffer.usePinned { pinned ->
                        read(handle, pinned.addressOf(0), buffer.size.toULong())
                    }
                    if (count < 0L) break
                    if (count == 0L) continue
                    inboundChannel.send(buffer.copyOf(count.toInt()))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // A serial error is a dropped link, which completing the flow says.
        } finally {
            inboundChannel.close()
        }
    }
}

/** One read is comfortably larger than a maximum-size frame. */
private const val READ_BUFFER_BYTES = 1024

/** Long enough that a poll costs nothing, short enough that close() is prompt. */
private const val POLL_TIMEOUT_MS = 100

/** A serial failure carrying the platform's own errno string. */
private fun serialError(operation: String, reason: String): Throwable =
    IllegalStateException("$operation failed: $reason (${strerror(errno)?.toKString()}, errno $errno)")