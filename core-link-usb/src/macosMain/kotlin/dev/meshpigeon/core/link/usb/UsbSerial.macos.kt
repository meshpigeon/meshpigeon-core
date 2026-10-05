@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.meshpigeon.core.link.usb

import dev.meshpigeon.core.transport.LinkCandidate
import dev.meshpigeon.core.transport.LinkDescriptor
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
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
import platform.posix.B110
import platform.posix.B115200
import platform.posix.B14400
import platform.posix.B19200
import platform.posix.B38400
import platform.posix.B4800
import platform.posix.B57600
import platform.posix.B7200
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
 * The macOS serial port: the same `termios` dance as Linux, spelled with the
 * types this platform uses (`tcflag_t` and the baud constants are `ULong` here
 * and `UInt` on Linux, `nfds_t` differs in width), which is why there is one
 * file per platform rather than one shared between them.
 *
 * The configuration is the same and for the same reasons: raw, 8N1, ignore
 * modem control, ignore parity errors. The comment on the Linux file explains
 * each of those; see it rather than repeating it here.
 *
 * The port names differ too, which is the only macOS-specific part of
 * [ports]: a USB-CDC radio is `/dev/cu.usbmodem*`, and the `cu.` (call-out)
 * node is the one that does not wait for a carrier.
 */
public actual object UsbSerial {
    actual fun open(descriptor: LinkDescriptor.Usb): SerialPort {
        val handle = open(descriptor.devicePath, O_RDWR or O_NOCTTY or O_NONBLOCK)
        if (handle < 0) {
            throw serialError("open ${descriptor.devicePath}", "the port could not be opened")
        }
        try {
            memScoped {
                val attributes: termios = alloc()
                val settings: CPointer<termios> = attributes.reinterpret()
                if (tcgetattr(handle, settings) != 0) {
                    throw serialError("open ${descriptor.devicePath}", "it is not a serial port")
                }
                cfmakeraw(settings)
                attributes.c_iflag = attributes.c_iflag or IGNPAR.convert<ULong>()
                attributes.c_cflag = (attributes.c_cflag and CSIZE.convert<ULong>().inv()) or
                    CS8.convert<ULong>() or CLOCAL.convert<ULong>() or CREAD.convert<ULong>()
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

    actual fun ports(): List<LinkCandidate> = serialDeviceNames()
        .map { it to it.substringAfterLast('/') }
        .distinctBy { it.first }
        .sortedBy { it.second }
        .map { (path, name) -> LinkCandidate(LinkDescriptor.Usb(path), name) }

    /** `/dev/cu.usbmodem*` and `/dev/cu.usbserial*`: the call-out nodes. */
    private fun serialDeviceNames(): List<String> {
        val directory = opendir(DEVICE_DIRECTORY) ?: return emptyList()
        try {
            return buildList {
                while (true) {
                    val entry = readdir(directory) ?: break
                    val name = entry.pointed.d_name.toKString()
                    if (name.startsWith("cu.usbmodem") || name.startsWith("cu.usbserial")) {
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

/** The POSIX `c_cc` indices for VMIN and VTIME. */
private const val VMIN_INDEX = 6
private const val VTIME_INDEX = 5

/** `speed_t` is an `unsigned long` here, unlike Linux's `unsigned int`. */
private fun baudConstant(baudRate: Int): ULong = when (baudRate) {
    4800 -> B4800.convert()
    9600 -> B9600.convert()
    14400 -> B14400.convert()
    19200 -> B19200.convert()
    38400 -> B38400.convert()
    57600 -> B57600.convert()
    115200 -> B115200.convert()
    7200 -> B7200.convert()
    110 -> B110.convert()
    else -> throw IllegalArgumentException(
        "unsupported baud rate $baudRate: this build knows the common rates up to 115200",
    )
}

/** One open port; the loop is the same as the Linux one, with this platform's types. */
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
            memScoped {
                val descriptor: pollfd = alloc()
                val waiting: CPointer<pollfd> = descriptor.reinterpret()
                descriptor.fd = handle
                descriptor.events = POLLIN.toShort()
                descriptor.revents = 0
                val fatal = (POLLERR or POLLHUP or POLLNVAL).toInt()
                val readable = POLLIN.toInt()
                while (true) {
                    val ready = poll(waiting, 1u.convert(), POLL_TIMEOUT_MS)
                    if (ready < 0) {
                        if (errno == EINTR) continue
                        break
                    }
                    if (ready == 0) continue
                    val events = descriptor.revents.toInt()
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