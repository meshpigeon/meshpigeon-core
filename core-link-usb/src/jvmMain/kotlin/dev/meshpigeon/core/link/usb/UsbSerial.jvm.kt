package dev.meshpigeon.core.link.usb

import dev.meshpigeon.core.transport.LinkCandidate
import dev.meshpigeon.core.transport.LinkDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * The JVM serial port: a device node opened as two streams.
 *
 * There is no `termios` here, so the line discipline is whatever the kernel
 * defaults a port to. That is the right trade for the two callers this serves —
 * a desktop JVM that opens a port another process has already configured, and
 * Android, which passes its own endpoint streams in rather than using this at
 * all — and the wrong one for a raw `mp` on the JVM, which is why the CLI ships
 * as a native binary where `UsbSerial.linux.kt` does the real work.
 */
public actual object UsbSerial {
    actual fun open(descriptor: LinkDescriptor.Usb): SerialPort {
        val node = File(descriptor.devicePath)
        if (!node.exists()) {
            throw IllegalStateException("${descriptor.devicePath} is not there — is the radio plugged in?")
        }
        val input = try {
            FileInputStream(node)
        } catch (e: IOException) {
            throw IllegalStateException("${descriptor.devicePath} could not be opened for reading: ${e.message}", e)
        }
        val output = try {
            FileOutputStream(node)
        } catch (e: IOException) {
            input.close()
            throw IllegalStateException("${descriptor.devicePath} could not be opened for writing: ${e.message}", e)
        }
        return StreamSerialPort(input, output, descriptor.devicePath)
    }

    actual fun ports(): List<LinkCandidate> {
        val nodes = File(DEVICE_DIRECTORY).listFiles() ?: return emptyList()
        return nodes
            .filter { it.name.startsWith("ttyACM") || it.name.startsWith("ttyUSB") }
            .sortedBy { it.name }
            .map { LinkCandidate(LinkDescriptor.Usb(it.path), it.name) }
    }

    private const val DEVICE_DIRECTORY = "/dev"
}