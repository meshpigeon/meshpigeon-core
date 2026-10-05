package dev.meshpigeon.core.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * meshpigeon-firmware's API — the only interface to any radio.
 *
 * There is exactly one implementation of this interface (the protobuf session,
 * [RadioSession]); it exists so `meshpigeon-core-testing` can supply a fake and a
 * scripted replay, not to plan a second dialect of the air.
 *
 * Generated protobuf types never appear here: the API speaks domain types, so no
 * protocol module, no database row and no user interface ever imports one.
 */
public interface RadioApi {
    /** Identity, versions, capabilities, store statistics, health. */
    public suspend fun deviceInfo(): DeviceInfo

    /** Connection status. */
    public suspend fun status(): LinkStatus

    /** Unlocks this connection with the device PIN. */
    public suspend fun authenticate(secret: Secret): AuthResult

    /** The current tuning. */
    public suspend fun readTuning(): Tuning

    /**
     * Applies [tuning] and returns the post-write read model, epoch included.
     *
     * The firmware guards this itself: a concurrent retune is refused by epoch
     * conflict, only the first change in the five minutes after boot is honored,
     * and a retune while the radio is keying answers `BUSY`.
     */
    public suspend fun writeTuning(tuning: Tuning): Tuning

    /**
     * Hands [raw] to the radio to put on the air once.
     *
     * Returns as soon as the radio has stored it and keyed up; the outcome
     * arrives later through [TxTicket.await] or as a [PacketEvent].
     */
    public suspend fun sendRaw(raw: ByteArray): TxTicket

    /** Packets, pushes and out-of-band changes, as one stream. */
    public fun incoming(): Flow<PacketEvent>

    /** Stored packets after [since], oldest first. */
    public suspend fun fetchHistory(since: Cursor, limit: Int): HistoryPage

    /** Clears the packet store. Settings are untouched. */
    public suspend fun purgeHistory()

    /** The device settings read model. Requires authentication. */
    public suspend fun deviceSettings(): DeviceSettings

    /** Applies a subset of the device settings atomically. */
    public suspend fun writeDeviceSettings(patch: DeviceSettingsPatch)

    /** Restarts into the persisted configuration. */
    public suspend fun reboot()

    /** Wipes settings and the packet store, then restarts. */
    public suspend fun factoryReset()
}

/** Something the radio did or reported, as one stream for every client. */
public sealed interface PacketEvent {
    /** A packet was received off the air (or came back from our own send). */
    public data class PacketReceived(val packet: StoredPacket) : PacketEvent

    /** An asynchronous send outcome. */
    public data class TxCompleted(val outcome: TxOutcome) : PacketEvent

    /** Another client re-tuned the radio. */
    public data class RadioRetuned(val tuning: Tuning) : PacketEvent

    /** Another client changed the device settings. */
    public data class DeviceSettingsChanged(val settings: DeviceSettings) : PacketEvent

    /** The Wi-Fi state changed. */
    public data class LinkChanged(val status: LinkStatus) : PacketEvent

    /** An unsolicited error: a lost packet, or a refused re-tune. */
    public data class Reported(val error: RadioError) : PacketEvent

    /** Frames arrived but were not usable — the firmware drops these silently. */
    public data class FramesDropped(val count: Int) : PacketEvent
}

/** Where a link is, in the terms a person can read. */
public sealed interface LinkDescriptor {
    /** A host and port on the network (the Wi-Fi TCP transport). */
    public data class Tcp(val host: String, val port: Int) : LinkDescriptor

    /** A serial port on this machine (the USB CDC transport). */
    public data class Usb(val devicePath: String, val baudRate: Int = 115_200) : LinkDescriptor

    /** A BLE endpoint (the NUS transport). */
    public data class Ble(val endpointId: String, val name: String?) : LinkDescriptor
}

/** The lifecycle of a [RadioLink]. */
public sealed interface LinkState {
    public data object Closed : LinkState
    public data object Opening : LinkState
    public data class Open(val sinceMs: Long) : LinkState

    /** The link could not be opened, or dropped. */
    public data class Failed(val detail: String) : LinkState
}

/** One endpoint a link found while discovering. */
public data class LinkCandidate(
    val descriptor: LinkDescriptor,
    /** What to show a person: a name, a path, a host:port. */
    val label: String,
)

/**
 * A byte pipe to a radio: TCP, USB CDC, BLE — nothing above this knows which.
 *
 * The API (verbs, correlation, authentication, store semantics) lives in
 * [RadioSession]; this is only the pipe. Separating them is what makes a USB
 * radio and a Wi-Fi radio behave identically.
 */
public interface RadioLink {
    public val descriptor: LinkDescriptor

    public val state: StateFlow<LinkState>

    /** Opens the link. Calling it on an open link does nothing. */
    public suspend fun open()

    /** Closes the link; [inbound] then completes. */
    public suspend fun close()

    /** Bytes as they arrive, in whatever chunking the transport produced. */
    public fun inbound(): Flow<ByteArray>

    /** Writes one chunk. Frame boundaries are the caller's business. */
    public suspend fun write(bytes: ByteArray)

    /** Endpoints this link could connect to — probed ports, serial ports, … */
    public suspend fun discover(): List<LinkCandidate>
}