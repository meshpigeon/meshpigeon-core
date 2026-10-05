package dev.meshpigeon.core.testing

import dev.meshpigeon.core.common.Clock
import dev.meshpigeon.core.common.framing.FrameEvent
import dev.meshpigeon.core.common.framing.FrameReader
import dev.meshpigeon.core.common.framing.Framing
import dev.meshpigeon.core.transport.Capability
import dev.meshpigeon.core.transport.LinkCandidate
import dev.meshpigeon.core.transport.LinkDescriptor
import dev.meshpigeon.core.transport.LinkState
import dev.meshpigeon.core.transport.RadioLink
import dev.meshpigeon.proto.ClientToRadio
import dev.meshpigeon.proto.DeviceInfo
import dev.meshpigeon.proto.DeviceSettings
import dev.meshpigeon.proto.FetchEnd
import dev.meshpigeon.proto.Error
import dev.meshpigeon.proto.Ok
import dev.meshpigeon.proto.PacketAccepted
import dev.meshpigeon.proto.PacketEntry
import dev.meshpigeon.proto.Pong
import dev.meshpigeon.proto.RadioSettings
import dev.meshpigeon.proto.RadioToClient
import dev.meshpigeon.proto.SetDeviceSettings
import dev.meshpigeon.proto.Status
import dev.meshpigeon.proto.StoreInfo
import dev.meshpigeon.proto.TxResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import okio.ByteString.Companion.toByteString

/**
 * A radio that behaves like `meshpigeon-firmware` without being one: the same
 * framing, the same protobuf API and the same store semantics, answered by the
 * rules the firmware documents.
 *
 * It exists so the session, the CLI and every client can be tested end to end
 * without a simulator process or a bench — and because it encodes and decodes
 * real frames it doubles as a contract test of the framing in both directions.
 *
 * Misbehaviour is scripted explicitly ([scriptError], [dropNextResponses],
 * [injectGarbage], [dropLink]) so a test can reproduce what a real radio does
 * under stress instead of hoping for it.
 */
public class FakeRadio(
    private val clock: Clock = FakeClock(),
    /** The specification version this fake claims, so the version gate is testable. */
    public var specVersion: Int = 2,
    private val boardName: String = "FAKE",
    private val capabilities: Set<Capability> = setOf(Capability.WIFI),
    /** The device PIN, or `null` for the shipped default, which needs no authentication. */
    private var pin: String? = null,
    private val storeCapacityBytes: Int = 4096,
) {
    private val reader = FrameReader()
    private var inboundChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val linkState = MutableStateFlow<LinkState>(LinkState.Closed)

    /** Every frame the client wrote, as wire bytes. */
    public val written: MutableList<ByteArray> = mutableListOf()

    /** Every request the client sent, decoded — assertions read this. */
    public val requests: MutableList<ClientToRadio> = mutableListOf()

    private val store = mutableListOf<PacketEntry>()
    private var nextSeq = 1
    private var droppedPackets = 0
    private var tuning = RadioSettings(
        freq_hz = 868_500_000,
        bandwidth_hz = 125_000,
        sf = 8,
        cr = 5,
        power_dbm = 20,
        config_epoch = 1,
    )
    private var settings = DeviceSettings(name = "FakeRadio", wifi_enabled = false, wifi_port = 5000)
    private var uptimeMs = 0L
    private var bootCount = 1
    private var configEpoch = 1
    private var authenticated = false
    private var nextError: Error? = null
    private var dropResponseCount = 0

    /** The link a client connects to. */
    public val link: RadioLink = object : RadioLink {
        override val descriptor: LinkDescriptor = LinkDescriptor.Tcp(host = "fake", port = 0)

        override val state: StateFlow<LinkState> = linkState.asStateFlow()

        override suspend fun open() {
            if (linkState.value !is LinkState.Closed) return
            linkState.value = LinkState.Opening
            if (inboundChannel.isClosedForSend) inboundChannel = Channel(Channel.UNLIMITED)
            reader.reset()
            // Auth state lives on the connection, not on the radio.
            authenticated = false
            linkState.value = LinkState.Open(sinceMs = clock.nowMs())
        }

        override suspend fun close() {
            linkState.value = LinkState.Closed
            inboundChannel.close()
        }

        override fun inbound(): Flow<ByteArray> = inboundChannel.receiveAsFlow()

        override suspend fun write(bytes: ByteArray) {
            written += bytes
            for (event in reader.feed(bytes)) {
                if (event is FrameEvent.Decoded) handle(event.frame.envelope)
            }
        }

        override suspend fun discover(): List<LinkCandidate> =
            listOf(LinkCandidate(descriptor, "fake radio"))
    }

    /** The device information this fake reports. */
    public fun deviceInfo(): DeviceInfo = DeviceInfo(
        spec_version = specVersion,
        fw_version = "0.0.0-fake",
        board_name = boardName,
        capabilities = capabilities.map { it.toProto() },
        uptime_ms = uptimeMs,
        boot_count = bootCount,
        store = StoreInfo(
            count = store.size,
            capacity_bytes = storeCapacityBytes,
            dropped = droppedPackets,
            oldest_seq = store.firstOrNull()?.seq ?: nextSeq,
        ),
        radio_config_epoch = configEpoch,
        battery_mv = 0xFFFF,
        radio_ok = true,
        auth_required = requiresAuth(),
        noise_floor_dbm = 0,
    )

    /** Advances the radio's own clock, which packets are stamped from. */
    public fun advanceUptime(ms: Long) {
        uptimeMs += ms
    }

    /** Simulates a reboot: the uptime restarts and the RAM store is gone. */
    public fun reboot() {
        uptimeMs = 0
        bootCount++
        store.clear()
        nextSeq = 1
        dropLink()
    }

    /** Stores a packet the client sent, as the firmware stores what it keys up. */
    public fun recordSentPacket(raw: ByteArray) {
        store += PacketEntry(
            seq = nextSeq++,
            raw = raw.toByteString(),
            uptime_ms = uptimeMs,
            rssi = 0,
            snr = 0,
            origin = PacketEntry.Origin.ORIGIN_SENT,
        )
    }

    /** Puts a packet in the store *and* pushes it live, as a reception does. */
    public fun receive(raw: ByteArray, rssi: Int = -90, snr: Int = 75) {
        val entry = PacketEntry(
            seq = nextSeq++,
            raw = raw.toByteString(),
            uptime_ms = uptimeMs,
            rssi = rssi,
            snr = snr,
            origin = PacketEntry.Origin.ORIGIN_RECEIVED,
        )
        store += entry
        push(RadioToClient(packet_entry = entry))
    }

    /** Pushes a re-tune notice, as another client's retune does. */
    public fun announceRetune() {
        push(RadioToClient(radio_settings = tuning))
    }

    /** Makes the next request answer with [code] instead of doing its job. */
    public fun scriptError(code: Error.ErrorCode, hint: String = "") {
        nextError = Error(code = code, message = hint)
    }

    /** Swallows the next [count] responses, as a lost acknowledgement does. */
    public fun dropNextResponses(count: Int = 1) {
        dropResponseCount = count
    }

    /** Writes bytes that are not a frame, as the ESP-IDF Wi-Fi log spew does. */
    public fun injectGarbage(text: String) {
        pushRaw(text.encodeToByteArray() + Framing.DELIMITER)
    }

    /** Drops the link, as a Wi-Fi loss does: inbound then completes. */
    public fun dropLink() {
        linkState.value = LinkState.Closed
        inboundChannel.close()
    }

    /** How many packets the store currently holds. */
    public val storedCount: Int get() = store.size

    /** Evicts the oldest packets until the byte budget is respected. */
    public fun evictToBudget() {
        var used = store.sumOf { it.raw.size }
        while (used > storeCapacityBytes && store.isNotEmpty()) {
            droppedPackets++
            used -= store.removeAt(0).raw.size
        }
    }

    private fun requiresAuth(): Boolean = pin != null && !authenticated

    private fun handle(envelope: ByteArray) {
        val request = runCatching { ClientToRadio.ADAPTER.decode(envelope) }.getOrNull() ?: return
        requests += request
        nextError?.let { error ->
            nextError = null
            push(RadioToClient(id = request.id, error = error))
            return
        }
        if (dropResponseCount > 0) {
            dropResponseCount--
            return
        }
        val response = respondTo(request)
        if (response != null) push(response)
        // The firmware answers a send with the accepted ack first and the
        // outcome afterwards; the client holds that seq until it does, so the
        // order is part of the contract.
        val send = request.send_packet
        val accepted = response?.packet_accepted
        if (send != null && accepted != null) {
            push(RadioToClient(tx_result = TxResult(seq = accepted.seq, success = true)))
        }
    }

    private fun respondTo(request: ClientToRadio): RadioToClient? {
        val id = request.id
        if (id == 0) return null // a request must carry a non-zero id
        if (request.requiresAuth() && requiresAuth()) {
            return RadioToClient(id = id, error = Error(code = Error.ErrorCode.ERROR_CODE_AUTH_REQUIRED))
        }
        // Smart casts do not apply to properties of another module's class, so
        // each oneof member is read into a local first.
        val ping = request.ping
        val auth = request.auth
        val setRadio = request.set_radio_settings
        val send = request.send_packet
        val fetch = request.fetch_packets
        return when {
            ping != null -> RadioToClient(id = id, pong = Pong(payload = ping.payload))
            request.get_device_info != null -> RadioToClient(id = id, device_info = deviceInfo())
            request.get_status != null -> RadioToClient(id = id, status = status())
            auth != null -> authenticate(auth.pin, id)
            request.get_radio_settings != null -> RadioToClient(id = id, radio_settings = tuning)
            setRadio != null -> retune(setRadio.settings, id)
            send != null -> acceptSend(send.raw.toByteArray(), id)
            fetch != null -> {
                streamHistory(fetch.since_seq, fetch.max_count, id)
                null
            }
            request.purge_store != null -> {
                store.clear()
                RadioToClient(id = id, ok = Ok())
            }
            request.get_device_settings != null -> RadioToClient(id = id, device_settings = settings)
            request.set_device_settings != null -> applySettings(request.set_device_settings!!, id)
            request.reboot != null -> RadioToClient(id = id, ok = Ok())
            request.bootloader != null -> RadioToClient(id = id, ok = Ok())
            request.factory_reset != null -> {
                store.clear()
                settings = DeviceSettings(name = "FakeRadio", wifi_enabled = false, wifi_port = 5000)
                RadioToClient(id = id, ok = Ok())
            }
            else -> RadioToClient(id = id, error = Error(code = Error.ErrorCode.ERROR_CODE_BAD_COMMAND))
        }
    }

    private fun authenticate(pin: String, id: Int): RadioToClient =
        if (pin == (this.pin ?: DEFAULT_PIN)) {
            authenticated = true
            RadioToClient(id = id, ok = Ok())
        } else {
            RadioToClient(id = id, error = Error(code = Error.ErrorCode.ERROR_CODE_AUTH_REQUIRED))
        }

    private fun retune(requested: RadioSettings?, id: Int): RadioToClient {
        if (requested == null) {
            return RadioToClient(
                id = id,
                error = Error(code = Error.ErrorCode.ERROR_CODE_BAD_PAYLOAD, message = "no settings"),
            )
        }
        tuning = requested.copy(config_epoch = ++configEpoch)
        configEpoch = tuning.config_epoch
        return RadioToClient(id = id, radio_settings = tuning)
    }

    private fun acceptSend(raw: ByteArray, id: Int): RadioToClient {
        if (raw.isEmpty() || raw.size > MAX_RAW_PACKET) {
            return RadioToClient(id = id, error = Error(code = Error.ErrorCode.ERROR_CODE_BAD_PAYLOAD))
        }
        evictToBudget()
        recordSentPacket(raw)
        val seq = store.last().seq
        return RadioToClient(id = id, packet_accepted = PacketAccepted(seq = seq))
    }

    private fun streamHistory(sinceSeq: Int, maxCount: Int, id: Int) {
        val matching = store.filter { it.seq > sinceSeq }
            .let { if (maxCount == 0) it else it.take(maxCount) }
        matching.forEach { push(RadioToClient(id = id, packet_entry = it)) }
        push(RadioToClient(id = id, fetch_end = FetchEnd(count = matching.size)))
    }

    private fun applySettings(patch: SetDeviceSettings, id: Int): RadioToClient {
        settings = settings.copy(
            name = patch.name ?: settings.name,
            wifi_enabled = patch.wifi_enabled ?: settings.wifi_enabled,
            wifi_ssid = patch.wifi_ssid ?: settings.wifi_ssid,
            wifi_password = patch.wifi_password ?: settings.wifi_password,
            wifi_port = patch.wifi_port ?: settings.wifi_port,
        )
        // Writing a PIN proves this connection knows it.
        patch.pin?.let {
            pin = it
            authenticated = true
        }
        return RadioToClient(id = id, device_settings = settings)
    }

    private fun status() = Status(
        wifi_state = Status.WifiState.WIFI_STATE_OFF,
        wifi_port = 5000,
        usb_cdc_clients = if (linkState.value is LinkState.Open) 1 else 0,
    )

    /** The operations the firmware gates behind authentication. */
    private fun ClientToRadio.requiresAuth(): Boolean = when {
        get_device_settings != null -> true
        set_device_settings != null -> true
        get_radio_settings != null -> true
        set_radio_settings != null -> true
        send_packet != null -> true
        fetch_packets != null -> true
        purge_store != null -> true
        reboot != null -> true
        factory_reset != null -> true
        bootloader != null -> true
        else -> false
    }

    private fun push(message: RadioToClient) {
        pushRaw(Framing.encode(message.encode()))
    }

    private fun pushRaw(bytes: ByteArray) {
        inboundChannel.trySend(bytes)
    }

    private companion object {
        /** The PIN every board ships with, public on purpose. */
        const val DEFAULT_PIN = "0000"

        /** The Semtech SX12xx silicon cap on a single transmission. */
        const val MAX_RAW_PACKET = 255
    }
}

/** The capability a client reads, as the wire enum the firmware sends. */
private fun Capability.toProto(): dev.meshpigeon.proto.Capability = when (this) {
    Capability.WIFI -> dev.meshpigeon.proto.Capability.CAPABILITY_WIFI_STA
    Capability.BATTERY -> dev.meshpigeon.proto.Capability.CAPABILITY_BATTERY
    Capability.BLE -> dev.meshpigeon.proto.Capability.CAPABILITY_BLE
    Capability.USB_CDC -> dev.meshpigeon.proto.Capability.CAPABILITY_USB_CDC
}