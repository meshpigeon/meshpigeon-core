package dev.meshpigeon.core.transport

import dev.meshpigeon.core.common.CoreError
import kotlinx.coroutines.Deferred

/**
 * Everything a radio is, as `DeviceInfo` and `Status` report it — and nothing
 * more. No chip part numbers, no drivers, no register-level knowledge: hardware
 * detail stops at the firmware's protobuf API, so a future board is a firmware
 * change and nothing else.
 */
public data class DeviceInfo(
    /** The specification version this firmware implements. */
    val specVersion: Int,
    val firmwareVersion: String,
    /** The board's name, as it reports it ("XIAO WIO", "T1000-E", "SIM"). */
    val boardName: String,
    val capabilities: Set<Capability>,
    /** Monotonic milliseconds since boot; the clock packets are stamped with. */
    val uptimeMs: Long,
    val bootCount: Int,
    val store: StoreStats,
    /** Bumped on every accepted retune, so concurrent retunes are detectable. */
    val radioConfigEpoch: Int,
    /** Battery millivolts, or `null` when the board does not report them. */
    val batteryMilliVolts: Int?,
    /** False when the radio failed to come up: the device answers, the air does not. */
    val radioOk: Boolean,
    /** True when a PIN is set and this connection has not authenticated. */
    val authRequired: Boolean,
    /** Receiver noise floor in dBm, or `null` when nothing has been received yet. */
    val noiseFloorDbm: Int?,
) {
    /** A Wi-Fi station board, so a TCP link is worth offering. */
    val supportsWifi: Boolean get() = Capability.WIFI in capabilities

    /** A BLE NUS board, so a BLE link is worth offering. */
    val supportsBle: Boolean get() = Capability.BLE in capabilities

    /** A USB-CDC board, so a serial link is worth offering. */
    val supportsUsb: Boolean get() = Capability.USB_CDC in capabilities

    /** The battery reading is meaningful. */
    val hasBattery: Boolean get() = Capability.BATTERY in capabilities

    public companion object {
        /** The specification version this client implements. */
        public const val SPEC_VERSION: Int = 2

        /**
         * Every `spec_version` this client can speak. The firmware bumps it on
         * whole-spec breaks and nothing else, so a radio outside this set is
         * refused rather than half-understood.
         */
        public val SUPPORTED_SPEC_VERSIONS: Set<Int> = setOf(SPEC_VERSION)
    }
}

/** A board ability, reported in `DeviceInfo.capabilities`. */
public enum class Capability {
    /** Can join a Wi-Fi network, so it can serve a TCP link. */
    WIFI,

    /** `battery_mv` is meaningful. */
    BATTERY,

    /** Has the BLE NUS transport. */
    BLE,

    /** Has the USB CDC transport. */
    USB_CDC,
}

/** The packet store: a RAM ring of raw frames, bounded by a byte budget. */
public data class StoreStats(
    val count: Int,
    val capacityBytes: Int,
    val dropped: Int,
    /**
     * The first retained sequence number — the *gap boundary*. Anything below
     * it has been evicted and can never be fetched again.
     */
    val oldestSeq: Int,
)

/** LoRa tuning, as the radio reports or accepts it. */
public data class Tuning(
    val frequencyHz: Int,
    val bandwidthHz: Int,
    val spreadingFactor: Int,
    /** Coding rate denominator: 5..8 means 4/5 .. 4/8. */
    val codingRate: Int,
    val powerDbm: Int,
    /** Bumped by the firmware on every accepted change. */
    val configEpoch: Int,
) {
    /** Whether two tunings differ in anything but the epoch. */
    public fun sameTuningAs(other: Tuning): Boolean =
        frequencyHz == other.frequencyHz &&
            bandwidthHz == other.bandwidthHz &&
            spreadingFactor == other.spreadingFactor &&
            codingRate == other.codingRate &&
            powerDbm == other.powerDbm
}

/** Wi-Fi state as the radio reports it. */
public enum class WifiState { OFF, CONNECTING, CONNECTED, AUTH_FAIL, ERROR }

/** Connection status: the snapshot a client polls, because it changes on its own. */
public data class LinkStatus(
    val wifiState: WifiState,
    /** The SSID the driver latched onto, which is not always the configured one. */
    val wifiSsid: String,
    /** Station IPv4 address in network byte order, or `null` when not connected. */
    val wifiIpv4: ByteArray?,
    val wifiPort: Int,
    val wifiRssiDbm: Int?,
    val bleClients: Int,
    val usbCdcClients: Int,
    val tcpClients: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LinkStatus) return false
        return wifiState == other.wifiState &&
            wifiSsid == other.wifiSsid &&
            wifiIpv4.contentEqualsNullable(other.wifiIpv4) &&
            wifiPort == other.wifiPort &&
            wifiRssiDbm == other.wifiRssiDbm &&
            bleClients == other.bleClients &&
            usbCdcClients == other.usbCdcClients &&
            tcpClients == other.tcpClients
    }

    override fun hashCode(): Int {
        var result = wifiState.hashCode()
        result = 31 * result + wifiSsid.hashCode()
        result = 31 * result + (wifiIpv4?.contentHashCode() ?: 0)
        result = 31 * result + wifiPort
        result = 31 * result + (wifiRssiDbm ?: 0)
        result = 31 * result + bleClients
        result = 31 * result + usbCdcClients
        result = 31 * result + tcpClients
        return result
    }

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
        this == null -> other == null
        other == null -> false
        else -> contentEquals(other)
    }
}

/** Where a stored packet came from. */
public enum class PacketOrigin {
    /** Keyed up by this client through `sendRaw`. */
    SENT,

    /** Received off the air. */
    RECEIVED,
}

/**
 * One packet in the radio's store: raw on-air bytes and reception metadata.
 * The firmware cannot read the payload — it keeps raw bytes, and so do we.
 */
public class StoredPacket(
    /** The store's monotonic sequence number; never reused within a boot. */
    public val seq: Int,
    public val raw: ByteArray,
    /** The radio's uptime when it was sent or received. */
    public val radioUptimeMs: Long,
    /** Wall-clock time, mapped from [radioUptimeMs] through the uptime anchor. */
    public val observedAtMs: Long,
    val rssiDbm: Int?,
    val snr: Double?,
    public val origin: PacketOrigin,
) {
    override fun toString(): String = "StoredPacket(seq=$seq, ${raw.size} bytes, $origin)"
}

/** The outcome of a `sendRaw`, delivered asynchronously. */
public data class TxOutcome(val seq: Int, val success: Boolean)

/**
 * A send that the radio accepted: the ticket is returned immediately, the
 * outcome arrives later as a `TxResult` push (or a failure, or never at all).
 */
public class TxTicket internal constructor(
    public val seq: Int,
    private val outcome: Deferred<TxOutcome>,
) {
    /** Waits for the transmission to finish. */
    public suspend fun await(): TxOutcome = outcome.await()

    /** Awaits the outcome, mapping a failure to `null` rather than throwing. */
    public suspend fun awaitOrNull(): TxOutcome? = try {
        outcome.await()
    } catch (_: Exception) {
        null
    }

    override fun toString(): String = "TxTicket(seq=$seq)"
}

/** A resume point for [RadioApi.fetchHistory]: the last sequence number seen. */
public data class Cursor(val seq: Int) {
    public companion object {
        /** From the beginning of what the radio still holds. */
        public val Beginning: Cursor = Cursor(0)
    }
}

/** One page of history: the packets delivered, and where to resume. */
public data class HistoryPage(
    val packets: List<StoredPacket>,
    /** How many entries the radio delivered, which may be fewer than asked for. */
    val delivered: Int,
    /** The cursor to pass to the next call, or `null` when the store is drained. */
    val resumeAt: Cursor?,
)

/** The device settings read model (name, Wi-Fi). The PIN is never readable. */
public data class DeviceSettings(
    val name: String,
    val wifiEnabled: Boolean,
    val wifiSsid: String,
    val wifiPassword: String,
    val wifiPort: Int,
)

/**
 * A subset write: `null` leaves a field unchanged. The device applies the whole
 * request atomically — one invalid field rejects it with nothing applied.
 */
public data class DeviceSettingsPatch(
    val name: String? = null,
    /** Writing a PIN authorizes this connection and de-authorizes the others. */
    val pin: String? = null,
    val wifiEnabled: Boolean? = null,
    val wifiSsid: String? = null,
    val wifiPassword: String? = null,
    val wifiPort: Int? = null,
) {
    /** Whether this patch would change anything. */
    public val isEmpty: Boolean
        get() = name == null && pin == null && wifiEnabled == null &&
            wifiSsid == null && wifiPassword == null && wifiPort == null
}

/** The result of authenticating this connection with the device PIN. */
public sealed interface AuthResult {
    /** The connection may now use the radio. */
    public data object Authorized : AuthResult

    /** The device holds the default PIN and needs no authentication. */
    public data object NotRequired : AuthResult

    /** The PIN was refused. */
    public data class Rejected(val error: RadioError) : AuthResult
}

/** A device PIN. Sealed at rest by `core-storage`; never logged. */
public class Secret(private val value: String) {
    public fun reveal(): String = value

    override fun toString(): String = "Secret(***)"

    override fun equals(other: Any?): Boolean = other is Secret && other.value == value

    override fun hashCode(): Int = value.hashCode()
}

/** The error codes the radio answers with (`Error.code`, normative in the schema). */
public enum class RadioErrorCode(public val wireValue: Int) {
    UNSPECIFIED(0),
    BAD_COMMAND(1),
    BAD_PAYLOAD(2),
    BUSY(3),
    TX_FAILED(4),
    NO_RADIO(5),
    NOT_SUPPORTED(6),
    AUTH_REQUIRED(7),
    ;

    public companion object {
        /** Maps a wire value to a code; unknown values become [UNSPECIFIED]. */
        public fun of(wireValue: Int): RadioErrorCode =
            entries.firstOrNull { it.wireValue == wireValue } ?: UNSPECIFIED
    }
}

/** Everything that can go wrong between a client and a radio. */
public sealed interface RadioError : CoreError {
    /** The radio answered with an `Error`: it understood us and refused. */
    public data class Remote(val code: RadioErrorCode, val hint: String? = null) : RadioError {
        override val message: String get() = hint?.takeIf { it.isNotBlank() } ?: code.name
    }

    /** The link is not open. */
    public data class NotConnected(val detail: String = "not connected") : RadioError {
        override val message: String get() = "radio not connected ($detail)"
    }

    /** The link went away mid-request. */
    public data class LinkLost(val detail: String, override val cause: Throwable? = null) : RadioError {
        override val message: String get() = "link lost ($detail)"
    }

    /** No response within the deadline. */
    public data class Timeout(val what: String, val afterMs: Long) : RadioError {
        override val message: String get() = "$what timed out after ${afterMs}ms"
    }

    /** A radio speaking a specification version this client does not implement. */
    public data class UnsupportedSpec(val specVersion: Int) : RadioError {
        override val message: String
            get() = "radio speaks spec version $specVersion; this client implements " +
                DeviceInfo.SUPPORTED_SPEC_VERSIONS.sorted().joinToString(", ")
    }

    /** The radio failed to come up: it answers, but there is no air. */
    public data class NoRadio(val detail: String = "radio reported radio_ok = false") : RadioError {
        override val message: String get() = detail
    }

    /** The device wants a PIN this connection has not presented. */
    public data class AuthRequired(val detail: String = "device PIN required") : RadioError {
        override val message: String get() = detail
    }

    /** A raw packet the radio cannot accept. */
    public data class PacketTooLarge(val size: Int, val limit: Int = MAX_RAW_PACKET_BYTES) : RadioError {
        override val message: String get() = "packet of $size bytes exceeds the $limit-byte air limit"
    }

    /** The link could not be opened. */
    public data class LinkUnavailable(val detail: String, override val cause: Throwable? = null) : RadioError {
        override val message: String get() = "link unavailable ($detail)"
    }

    /** A response arrived but carried no body for the request that was sent. */
    public data class UnexpectedResponse(val detail: String) : RadioError {
        override val message: String get() = "unexpected response ($detail)"
    }
}

/** Thrown by the [RadioApi] verbs; the [RadioError] is the useful part. */
public class RadioException(public val error: RadioError) : Exception(error.message)

/** The largest raw packet the air accepts: the Semtech SX12xx silicon cap. */
public const val MAX_RAW_PACKET_BYTES: Int = 255