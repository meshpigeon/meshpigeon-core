package dev.meshpigeon.core.link.usb

import dev.meshpigeon.core.transport.LinkCandidate
import dev.meshpigeon.core.transport.LinkDescriptor
import kotlinx.coroutines.flow.Flow

/**
 * A live serial port: bytes in, bytes out, and the end of both.
 *
 * This is public because it is the one seam a client has to be able to
 * replace. On Linux and macOS it is `termios`; on the JVM it is a file stream;
 * **on Android it is `UsbDeviceConnection`** — the app holds a claimed
 * interface, not a device path, so it opens a port from its own streams and
 * hands them here. That is the whole reason the port is an interface rather
 * than a path.
 *
 * The platform owns its own read loop and reports what arrives as a flow that
 * completes when the port closes, so an unplugged cable announces itself the
 * same way a dropped socket does.
 */
public interface SerialPort {
    /** Bytes as they arrive, in whatever chunking the device produced. */
    public val inbound: Flow<ByteArray>

    /** Writes every byte, or throws. */
    public suspend fun write(bytes: ByteArray)

    /** Closes the port; [inbound] then completes. */
    public suspend fun close()
}

/**
 * This machine's serial ports.
 *
 * A radio on USB enumerates as `/dev/ttyACM<n>` on Linux and
 * `/dev/cu.usbmodem<n>` on macOS; [ports] is what `/link add` offers and what
 * [UsbRadioLink.discover] returns.
 */
public expect object UsbSerial {
    /** Opens the port at [descriptor], or throws with the platform's reason. */
    public fun open(descriptor: LinkDescriptor.Usb): SerialPort

    /**
     * The ports this machine has.
     *
     * A real enumeration needs the USB bus and permissions, so on Linux and
     * macOS this is the device nodes that exist; that is enough to offer
     * `/dev/ttyACM*` and `/dev/ttyUSB*` without a USB library, and it is what
     * `meshpigeon-sim` over a pty looks like too.
     */
    public fun ports(): List<LinkCandidate>
}