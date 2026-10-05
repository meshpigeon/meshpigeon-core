@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.meshpigeon.core.link.usb

import dev.meshpigeon.core.transport.LinkDescriptor
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.ptsname
import platform.posix.O_NOCTTY
import platform.posix.O_RDWR
import platform.posix.read
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The `termios` code against a real serial device — a pty.
 *
 * This is the test the plan asks for on day one of the USB work, made possible
 * without the hardware: a pty pair is a genuine serial device with a genuine
 * line discipline, so opening one through [UsbSerial] exercises `open`,
 * `tcgetattr`, `cfmakeraw`, `tcsetattr`, `poll` and `read` against the kernel's
 * real behaviour. What it cannot prove is a cable, an enumerating radio or a
 * baud rate a real bridge negotiates — that stays with the hardware.
 */
class TermiosPtyTest {
    /** A pty pair: [slave] is a device path, [master] is the other end. */
    private class Pty(val master: Int, val slavePath: String) {
        fun writeToSlave(bytes: ByteArray) {
            val sent = bytes.usePinned { write(master, it.addressOf(0), bytes.size.toULong()) }
            assertTrue(sent > 0, "nothing was written to the pty master")
        }

        fun readFromSlave(count: Int): ByteArray {
            val buffer = ByteArray(count)
            val read_ = buffer.usePinned { read(master, it.addressOf(0), count.toULong()) }
            assertTrue(read_ > 0, "nothing came back from the pty master")
            return buffer.copyOf(read_.toInt())
        }

        fun close() {
            platform.posix.close(master)
        }
    }

    private fun openPty(): Pty = memScoped {
        val master = platform.posix.posix_openpt(O_RDWR or O_NOCTTY)
        assertTrue(master >= 0, "no pty could be opened (errno ${platform.posix.errno})")
        assertTrue(platform.posix.grantpt(master) == 0, "grantpt failed")
        assertTrue(platform.posix.unlockpt(master) == 0, "unlockpt failed")
        val path = ptsname(master)!!.toKString()
        Pty(master, path)
    }

    @Test
    fun `a port configured raw passes bytes through immediately`() = runBlocking {
        val pty = openPty()
        try {
            val port = UsbSerial.open(LinkDescriptor.Usb(pty.slavePath))
            try {
                // The device "sends" a frame: bytes with no newline in them. In
                // canonical mode a pty would hold every one of them until a
                // newline arrived, and this would time out — which is exactly
                // the bug `cfmakeraw` is here to prevent.
                val frame = byteArrayOf(0x03, 0x91.toByte(), 0x00, 0xff.toByte(), 0x7f)
                val read = async { withTimeout(5_000) { port.inbound.first() } }
                pty.writeToSlave(frame)
                assertContentEquals(frame, read.await())
            } finally {
                port.close()
            }
        } finally {
            pty.close()
        }
    }

    @Test
    fun `what is written to the port arrives at the device`() = runBlocking {
        val pty = openPty()
        try {
            val port = UsbSerial.open(LinkDescriptor.Usb(pty.slavePath))
            try {
                val frame = byteArrayOf(0x01, 0x02, 0x7e, 0x00, 0x11)
                port.write(frame)
                assertContentEquals(frame, pty.readFromSlave(frame.size))
            } finally {
                port.close()
            }
        } finally {
            pty.close()
        }
    }

    @Test
    fun `the port is not left in canonical mode`() = runBlocking {
        val pty = openPty()
        try {
            val port = UsbSerial.open(LinkDescriptor.Usb(pty.slavePath))
            port.close()

            // Read the settings straight off the device node, as the kernel has
            // them: a pty's own line discipline is canonical, and opening it
            // through the link must have changed that.
            memScoped {
                val handle = platform.posix.open(pty.slavePath, O_RDWR or O_NOCTTY)
                assertTrue(handle >= 0)
                val attributes: platform.posix.termios = alloc()
                // No type argument: with one, `reinterpret` reads the struct
                // back by value; the value's type is what selects the pointer.
                val settings: kotlinx.cinterop.CPointer<platform.posix.termios> =
                    attributes.reinterpret()
                assertTrue(platform.posix.tcgetattr(handle, settings) == 0)
                val lflag = attributes.c_lflag
                platform.posix.close(handle)
                // ICANON (2) and ECHO (8) are what made a pty hold bytes back.
                assertTrue(
                    lflag.toLong() and (ICANON or ECHO).toLong() == 0L,
                    "the port is still in canonical mode: c_lflag is $lflag",
                )
            }
        } finally {
            pty.close()
        }
    }

    @Test
    fun `a path that is not a port says so`() {
        val failure = assertFailsWith<IllegalStateException> {
            UsbSerial.open(LinkDescriptor.Usb("/definitely/not/a/port"))
        }
        assertTrue(failure.message.orEmpty().contains("could not be opened"))
    }

    private companion object {
        /** Enough for both flags; their values are the POSIX ones. */
        const val ICANON = 0x0002
        const val ECHO = 0x0008
    }
}