package dev.meshpigeon.core.link.usb

import dev.meshpigeon.core.common.Clock
import dev.meshpigeon.core.transport.LinkCandidate
import dev.meshpigeon.core.transport.LinkDescriptor
import dev.meshpigeon.core.transport.LinkState
import dev.meshpigeon.core.transport.RadioLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A radio reached over USB CDC — the transport for a radio plugged into the
 * machine, and the second link in the plan's order (TCP first, USB second, BLE
 * last) because it is the one that works with no network at all.
 *
 * A serial port is a byte stream, so this link does no framing: it hands over
 * what arrived and writes what it is given, and the session's frame reader is
 * what turns that into frames. The API above cannot tell a USB radio from a
 * Wi-Fi one, which is the whole reason the pipe is separate from the API.
 *
 * [inbound] has exactly one consumer, the session — sharing the flow would mean
 * two collectors splitting the bytes between them.
 */
public class UsbRadioLink(
    override val descriptor: LinkDescriptor.Usb,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * How the port is opened. `UsbSerial.open` unless a test says otherwise,
     * which is the only way to drive this link without a device node — and,
     * on Android, the only way to supply endpoint streams.
     */
    private val opener: (LinkDescriptor.Usb) -> SerialPort = UsbSerial::open,
) : RadioLink {
    private val _state = MutableStateFlow<LinkState>(LinkState.Closed)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private var port: SerialPort? = null

    override suspend fun open() {
        if (_state.value is LinkState.Open) return
        _state.value = LinkState.Opening
        val opened = try {
            opener(descriptor)
        } catch (e: Throwable) {
            _state.value = LinkState.Failed(e.message ?: e::class.simpleName.orEmpty())
            throw e
        }
        port = opened
        _state.value = LinkState.Open(sinceMs = Clock.System.nowMs())
    }

    override suspend fun close() {
        val open = port
        port = null
        if (open != null) open.close()
        _state.value = LinkState.Closed
    }

    /** The bytes as they arrive; the flow completes when the port closes. */
    override fun inbound(): Flow<ByteArray> = port?.inbound ?: NoBytes

    override suspend fun write(bytes: ByteArray) {
        val open = port ?: throw IllegalStateException("the USB link is not open")
        open.write(bytes)
    }

    /**
     * The ports this machine has.
     *
     * A USB radio needs no discovery to be usable — the path is in the link —
     * so this is only what `/link add usb` can offer. Android overrides this
     * with the real bus, because there the device node does not exist.
     */
    override suspend fun discover(): List<LinkCandidate> = UsbSerial.ports()

    private companion object {
        val NoBytes: Flow<ByteArray> = emptyFlow()
    }
}