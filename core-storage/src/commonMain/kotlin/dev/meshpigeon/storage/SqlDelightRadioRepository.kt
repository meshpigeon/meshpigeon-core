package dev.meshpigeon.storage

import dev.meshpigeon.domain.LinkKind
import dev.meshpigeon.domain.LinkTarget
import dev.meshpigeon.domain.RadioRecord
import dev.meshpigeon.domain.RadioRepository
import dev.meshpigeon.domain.SavedLink

/**
 * The radios, over SQLite.
 *
 * `radio_device` holds what `DeviceInfo` reported and nothing else, and
 * `radio_link` holds the ways to reach it. The mapping lives here and nowhere
 * else: a generated table class never leaves this module, which is what lets the
 * interface live in `core-domain` with no SQL in sight.
 */
public class SqlDelightRadioRepository(private val database: MeshPigeonDatabase) : RadioRepository {

    private val radios get() = database.radiosQueries

    override suspend fun radios(): List<RadioRecord> =
        radios.selectAll().executeAsList().map(::toRecord)

    override suspend fun record(radio: RadioRecord): RadioRecord {
        val existing = radios.selectByPersistentId(radio.persistentId).executeAsOneOrNull()
        val id = existing?.id ?: radio.id
        if (existing == null) {
            radios.insert(
                id = id,
                persistent_id = radio.persistentId,
                name = radio.name,
                board_name = radio.boardName,
                fw_version = radio.firmwareVersion,
                spec_version = radio.specVersion?.toLong(),
                pin_sealed = null,
                supports_wifi = radio.supportsWifi.asDbBoolean(),
                supports_ble = radio.supportsBle.asDbBoolean(),
                supports_usb = radio.supportsUsb.asDbBoolean(),
                has_battery = radio.hasBattery.asDbBoolean(),
                first_seen_at = radio.firstSeenAtMs,
                last_seen_at = radio.lastSeenAtMs ?: radio.firstSeenAtMs,
                last_battery_mv = radio.lastBatteryMv?.toLong(),
                last_noise_floor_dbm = radio.lastNoiseFloorDbm?.toLong(),
            )
        } else {
            // `first_seen_at`, `persistent_id` and `pin_sealed` are not written
            // here: those are set once, and rewriting them is how a radio that
            // renamed itself loses the identity it was recognised by.
            radios.update(
                name = radio.name,
                board_name = radio.boardName,
                fw_version = radio.firmwareVersion,
                spec_version = radio.specVersion?.toLong(),
                supports_wifi = radio.supportsWifi.asDbBoolean(),
                supports_ble = radio.supportsBle.asDbBoolean(),
                supports_usb = radio.supportsUsb.asDbBoolean(),
                has_battery = radio.hasBattery.asDbBoolean(),
                last_seen_at = radio.lastSeenAtMs ?: existing.last_seen_at,
                last_battery_mv = radio.lastBatteryMv?.toLong(),
                last_noise_floor_dbm = radio.lastNoiseFloorDbm?.toLong(),
                id = id,
            )
        }
        return toRecord(radios.selectById(id).executeAsOne())
    }

    override suspend fun radio(id: String): RadioRecord? =
        radios.selectById(id).executeAsOneOrNull()?.let(::toRecord)

    override suspend fun links(radioId: String): List<SavedLink> =
        radios.selectLinksByRadio(radioId).executeAsList().map(::toLink)

    override suspend fun addLink(link: SavedLink): SavedLink {
        database.transaction {
            // One preferred link per radio. The partial unique index rejects a
            // second one anyway, so this is about which answer a person gets,
            // not about the constraint.
            if (link.isPreferred) radios.clearPreferred(link.radioId)
            radios.insertLink(
                id = link.id,
                radio_id = link.radioId,
                kind = link.target.kind.wireName,
                host = link.target.host,
                port = link.target.port?.toLong(),
                device_path = link.target.devicePath,
                baud = link.target.baudRate?.toLong(),
                endpoint_id = link.target.endpointId,
                pref_order = link.preferenceOrder.toLong(),
                is_preferred = link.isPreferred.asDbBoolean(),
                last_connected_at = link.lastConnectedAtMs,
                last_error = link.lastError,
            )
        }
        return link
    }

    override suspend fun removeLink(id: String) {
        radios.deleteLinkById(id)
    }

    override suspend fun noteLinkOutcome(id: String, connected: Boolean, atMs: Long, error: String?) {
        // A failure clears the timestamp: "when it last worked" and "when it last
        // was tried" are different questions, and one column answers one of them.
        radios.recordLinkOutcome(
            last_connected_at = if (connected) atMs else null,
            last_error = error,
            id = id,
        )
    }

    override suspend fun forgetRadio(id: String) {
        // ON DELETE CASCADE takes the links with it, which is why foreign keys
        // are on for every connection rather than only in migrations.
        radios.deleteById(id)
    }
}

private fun Boolean.asDbBoolean(): Long = if (this) 1L else 0L

private fun toRecord(row: Radio_device): RadioRecord = RadioRecord(
    id = row.id,
    persistentId = row.persistent_id,
    name = row.name,
    boardName = row.board_name,
    firmwareVersion = row.fw_version,
    specVersion = row.spec_version?.toInt(),
    supportsWifi = row.supports_wifi != 0L,
    supportsBle = row.supports_ble != 0L,
    supportsUsb = row.supports_usb != 0L,
    hasBattery = row.has_battery != 0L,
    firstSeenAtMs = row.first_seen_at,
    lastSeenAtMs = row.last_seen_at,
    lastBatteryMv = row.last_battery_mv?.toInt(),
    lastNoiseFloorDbm = row.last_noise_floor_dbm?.toInt(),
)

/**
 * The one place a link row becomes a [LinkTarget].
 *
 * Evolving enumerations are TEXT validated in Kotlin at a single mapping point
 * (plan §8.2), so adding a transport is a code change here and not a migration.
 * An unknown kind is refused rather than guessed: a link the build cannot open
 * should say so.
 */
private fun toLink(row: Radio_link): SavedLink = SavedLink(
    id = row.id,
    radioId = row.radio_id,
    target = LinkTarget(
        kind = LinkKind.fromWire(row.kind) ?: error("unknown link kind '${row.kind}' in the database"),
        host = row.host,
        port = row.port?.toInt(),
        devicePath = row.device_path,
        baudRate = row.baud?.toInt(),
        endpointId = row.endpoint_id,
        name = row.endpoint_id,
    ),
    preferenceOrder = row.pref_order.toInt(),
    isPreferred = row.is_preferred != 0L,
    lastConnectedAtMs = row.last_connected_at,
    lastError = row.last_error,
)

private val LinkKind.wireName: String
    get() = when (this) {
        LinkKind.Tcp -> "tcp"
        LinkKind.Usb -> "usb"
        LinkKind.Ble -> "ble"
    }