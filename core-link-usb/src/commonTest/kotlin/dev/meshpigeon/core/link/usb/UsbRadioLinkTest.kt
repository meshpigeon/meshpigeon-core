package dev.meshpigeon.core.link.usb

import dev.meshpigeon.core.transport.LinkDescriptor
import dev.meshpigeon.core.transport.LinkState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The link's own behaviour, with the port it opens supplied by the test — the
 * one seam [UsbRadioLink] has, and the same one Android uses to hand over its
 * endpoint streams. The termios itself is [TermiosPtyTest]'s job on Linux.
 */
class UsbRadioLinkTest {
    private class FakePort : SerialPort {
        val written = mutableListOf<ByteArray>()
        private val outbound = Channel<ByteArray>(Channel.BUFFERED)
        private val closed = CompletableDeferred<Unit>()

        override val inbound: Flow<ByteArray> = outbound.receiveAsFlow()

        override suspend fun write(bytes: ByteArray) {
            written += bytes
        }

        /** What the device "sends" to whoever is reading. */
        fun receive(bytes: ByteArray) {
            outbound.trySend(bytes)
        }

        override suspend fun close() {
            outbound.close()
            closed.complete(Unit)
        }

        val isClosed: Boolean get() = closed.isCompleted
    }

    private val device = LinkDescriptor.Usb("/dev/ttyACM9")

    @Test
    fun `a new link is closed`() = runTest {
        val link = UsbRadioLink(device, opener = { FakePort() })
        assertEquals(LinkState.Closed, link.state.value)
    }

    @Test
    fun `opening reports the port and what it wrote arrives`() = runTest {
        val port = FakePort()
        val link = UsbRadioLink(device, opener = { port })

        link.open()
        assertIs<LinkState.Open>(link.state.value)

        link.write(byteArrayOf(1, 2, 3))
        link.write(byteArrayOf(4))
        assertEquals(2, port.written.size)
        assertContentEquals(byteArrayOf(1, 2, 3), port.written[0])
        assertContentEquals(byteArrayOf(4), port.written[1])
    }

    @Test
    fun `opening twice keeps the port it already has`() = runTest {
        val port = FakePort()
        var opened = 0
        val link = UsbRadioLink(device, opener = { opened++; port })

        link.open()
        link.open()
        assertEquals(1, opened)
    }

    @Test
    fun `what the device sends is what the link hands over unchanged`() = runTest {
        val port = FakePort()
        val link = UsbRadioLink(device, opener = { port })
        link.open()

        // A frame is binary and has no newline in it: the link does no framing,
        // and anything that changed the bytes would corrupt every frame.
        val frame = byteArrayOf(0x03, 0x91.toByte(), 0x00, 0xff.toByte(), 0x7f)
        val read = async { withTimeout(2_000) { link.inbound().first() } }
        port.receive(frame)
        assertContentEquals(frame, read.await())
    }

    @Test
    fun `closing closes the port and ends the flow`() = runTest {
        val port = FakePort()
        val link = UsbRadioLink(device, opener = { port })
        link.open()

        link.close()

        assertTrue(port.isClosed)
        assertEquals(LinkState.Closed, link.state.value)
        // Completing rather than hanging: that is how an unplug announces itself
        // to the session above, which is what reconnects.
        assertEquals(emptyList(), withTimeout(2_000) { link.inbound().toList() })
    }

    @Test
    fun `a port that will not open fails the link instead of opening it`() = runTest {
        val link = UsbRadioLink(device, opener = { error("no such device") })

        assertFailsWith<IllegalStateException> { link.open() }
        val state = link.state.value
        assertIs<LinkState.Failed>(state)
        assertEquals("no such device", state.detail)
    }

    @Test
    fun `writing to a link that was never opened says so`() = runTest {
        val link = UsbRadioLink(device, opener = { FakePort() })
        val failure = assertFailsWith<IllegalStateException> { link.write(byteArrayOf(1)) }
        assertTrue(failure.message.orEmpty().contains("not open"))
    }
}