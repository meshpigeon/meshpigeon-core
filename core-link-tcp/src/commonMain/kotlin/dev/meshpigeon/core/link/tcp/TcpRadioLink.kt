package dev.meshpigeon.core.link.tcp

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
 * A radio reached over TCP — the Wi-Fi transport, and the one the desktop
 * simulator speaks.
 *
 * TCP is a byte stream, so this link does no framing at all: it hands over what
 * arrived and writes what it is given, and the session's frame reader is what
 * turns that into frames. That is the whole point of the split — the API cannot
 * tell a TCP radio from a USB one.
 *
 * [inbound] has exactly one consumer, the session. Sharing the flow would mean
 * two collectors splitting the bytes between them, and the session would see
 * half of every frame.
 */
public class TcpRadioLink(
    override val descriptor: LinkDescriptor.Tcp,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : RadioLink {
    private val _state = MutableStateFlow<LinkState>(LinkState.Closed)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private var connection: TcpConnection? = null

    override suspend fun open() {
        if (_state.value is LinkState.Open) return
        _state.value = LinkState.Opening
        val opened = try {
            Tcp.connect(descriptor)
        } catch (e: Throwable) {
            _state.value = LinkState.Failed(e.message ?: e::class.simpleName.orEmpty())
            throw e
        }
        connection = opened
        _state.value = LinkState.Open(sinceMs = Clock.System.nowMs())
    }

    override suspend fun close() {
        val open = connection
        connection = null
        if (open != null) open.close()
        _state.value = LinkState.Closed
    }

    /** The bytes as they arrive; the flow completes when the peer goes away. */
    override fun inbound(): Flow<ByteArray> = connection?.inbound ?: NoBytes

    override suspend fun write(bytes: ByteArray) {
        val open = connection ?: throw IllegalStateException("the TCP link is not open")
        open.write(bytes)
    }

    override suspend fun discover(): List<LinkCandidate> =
        listOf(LinkCandidate(descriptor, "${descriptor.host}:${descriptor.port}"))

    private companion object {
        val NoBytes: Flow<ByteArray> = emptyFlow()
    }
}