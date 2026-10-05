package dev.meshpigeon.core.link.usb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

/**
 * A serial port that is two streams.
 *
 * This is the JVM half of the USB link, and it is the one a *host* needs
 * rather than a device path: Android's `UsbDeviceConnection` gives a bulk-in and
 * a bulk-out endpoint and no node to configure, so the app builds one of these
 * from its own streams. It also serves a desktop JVM that has already had the
 * port configured by something else.
 *
 * Linux and macOS do not come here: a device node there needs `termios`, which
 * is what `UsbSerial.linux.kt` and `UsbSerial.macos.kt` are for.
 */
public class StreamSerialPort(
    private val input: InputStream,
    private val output: OutputStream,
    /** What to call this port in an error. */
    public val label: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SerialPort {
    private val inboundChannel = Channel<ByteArray>(Channel.BUFFERED)
    private val loop: Job = scope.launch { readLoop() }

    override val inbound: Flow<ByteArray> = inboundChannel.receiveAsFlow()

    override suspend fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override suspend fun close() {
        loop.cancel()
        runCatching { input.close() }
        runCatching { output.close() }
        inboundChannel.close()
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            while (true) {
                val count = input.read(buffer)
                // -1 is end of stream, which on a USB endpoint is the device
                // being unplugged; 0 would be a stream with nothing in it, which
                // a blocking read does not return.
                if (count <= 0) break
                inboundChannel.send(buffer.copyOf(count))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // A stream error is a dropped link, which completing the flow says.
        } finally {
            inboundChannel.close()
        }
    }

    private companion object {
        const val READ_BUFFER_BYTES = 1024
    }
}