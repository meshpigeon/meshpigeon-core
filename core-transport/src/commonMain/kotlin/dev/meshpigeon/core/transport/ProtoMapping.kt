package dev.meshpigeon.core.transport

import dev.meshpigeon.proto.ClientToRadio
import dev.meshpigeon.proto.DeviceInfo as ProtoDeviceInfo
import dev.meshpigeon.proto.DeviceSettings as ProtoDeviceSettings
import dev.meshpigeon.proto.Error as ProtoError
import dev.meshpigeon.proto.PacketEntry as ProtoPacketEntry
import dev.meshpigeon.proto.RadioSettings as ProtoRadioSettings
import dev.meshpigeon.proto.Status as ProtoStatus
import dev.meshpigeon.proto.StoreInfo as ProtoStoreInfo
import okio.ByteString

/**
 * Generated protobuf types never escape this module: every message crossing the
 * API boundary becomes one of the domain types in `RadioModel.kt`, and this file
 * is the only place the two vocabularies meet.
 */
internal fun ProtoDeviceInfo.toDomain(): DeviceInfo = DeviceInfo(
    specVersion = spec_version,
    firmwareVersion = fw_version,
    boardName = board_name,
    capabilities = capabilities.mapNotNull { it.toDomain() }.toSet(),
    uptimeMs = uptime_ms,
    bootCount = boot_count,
    store = store?.toDomain() ?: StoreStats(count = 0, capacityBytes = 0, dropped = 0, oldestSeq = 0),
    radioConfigEpoch = radio_config_epoch,
    batteryMilliVolts = battery_mv.takeIf { it != UNKNOWN_BATTERY_MV },
    radioOk = radio_ok,
    authRequired = auth_required,
    noiseFloorDbm = noise_floor_dbm.takeIf { it != 0 },
)

/** `0xFFFF` is the firmware's "no battery reading" marker. */
private const val UNKNOWN_BATTERY_MV = 0xFFFF

/** Unknown capability values are ignored, exactly as the schema instructs. */
private fun dev.meshpigeon.proto.Capability.toDomain(): Capability? = when (this) {
    dev.meshpigeon.proto.Capability.CAPABILITY_WIFI_STA -> Capability.WIFI
    dev.meshpigeon.proto.Capability.CAPABILITY_BATTERY -> Capability.BATTERY
    dev.meshpigeon.proto.Capability.CAPABILITY_BLE -> Capability.BLE
    dev.meshpigeon.proto.Capability.CAPABILITY_USB_CDC -> Capability.USB_CDC
    dev.meshpigeon.proto.Capability.CAPABILITY_UNSPECIFIED -> null
}

internal fun ProtoStoreInfo.toDomain(): StoreStats =
    StoreStats(count = count, capacityBytes = capacity_bytes, dropped = dropped, oldestSeq = oldest_seq)

internal fun ProtoRadioSettings.toDomain(): Tuning = Tuning(
    frequencyHz = freq_hz,
    bandwidthHz = bandwidth_hz,
    spreadingFactor = sf,
    codingRate = cr,
    powerDbm = power_dbm,
    configEpoch = config_epoch,
)

/** The firmware ignores a request's epoch, so it is only sent when echoing one. */
internal fun Tuning.toProto(): ProtoRadioSettings = ProtoRadioSettings(
    freq_hz = frequencyHz,
    bandwidth_hz = bandwidthHz,
    sf = spreadingFactor,
    cr = codingRate,
    power_dbm = powerDbm,
)

internal fun ProtoStatus.toDomain(): LinkStatus = LinkStatus(
    wifiState = when (wifi_state) {
        ProtoStatus.WifiState.WIFI_STATE_OFF,
        ProtoStatus.WifiState.WIFI_STATE_UNSPECIFIED,
        -> WifiState.OFF
        ProtoStatus.WifiState.WIFI_STATE_CONNECTING -> WifiState.CONNECTING
        ProtoStatus.WifiState.WIFI_STATE_CONNECTED -> WifiState.CONNECTED
        ProtoStatus.WifiState.WIFI_STATE_AUTH_FAIL -> WifiState.AUTH_FAIL
        ProtoStatus.WifiState.WIFI_STATE_ERROR -> WifiState.ERROR
    },
    wifiSsid = wifi_ssid,
    wifiIpv4 = wifi_ipv4.toByteArray().takeIf { it.isNotEmpty() },
    wifiPort = wifi_port,
    wifiRssiDbm = wifi_rssi.takeIf { it != 0 },
    bleClients = ble_clients,
    usbCdcClients = usb_cdc_clients,
    tcpClients = wifi_tcp_clients,
)

internal fun ProtoDeviceSettings.toDomain(): DeviceSettings = DeviceSettings(
    name = name,
    wifiEnabled = wifi_enabled,
    wifiSsid = wifi_ssid,
    wifiPassword = wifi_password,
    wifiPort = wifi_port,
)

internal fun ProtoPacketEntry.toDomain(observedAtMs: Long): StoredPacket = StoredPacket(
    seq = seq,
    raw = raw.toByteArray(),
    radioUptimeMs = uptime_ms,
    observedAtMs = observedAtMs,
    rssiDbm = rssi.takeIf { it != 0 },
    snr = snr.toDouble().takeIf { it != 0.0 },
    origin = when (origin) {
        ProtoPacketEntry.Origin.ORIGIN_SENT -> PacketOrigin.SENT
        else -> PacketOrigin.RECEIVED
    },
)

internal fun ProtoError.toDomain(): RadioError.Remote =
    RadioError.Remote(RadioErrorCode.of(code.value), message.takeIf { it.isNotBlank() })

/** A request builder: the session fills in the correlation id. */
internal typealias RequestBuilder = (Int) -> ClientToRadio

/** An envelope needs a correlation id; the firmware ignores id 0. */
internal fun ClientToRadio.withId(id: Int): ClientToRadio = if (this.id == id) this else copy(id = id)

internal fun ByteString?.orEmptyBytes(): ByteArray = this?.toByteArray() ?: ByteArray(0)