package dev.meshpigeon.domain

/**
 * The kinds of transport a link can be, as the plan spells them.
 *
 * A closed set that will not grow in P2–P4, so it is an enum here rather than a
 * string; a growing set (`core-meshcore` arrives in P3) belongs in a registry
 * table, which is why `protocol` is a table and this is not.
 */
public enum class LinkKind {
    Tcp,
    Usb,
    Ble,
    ;

    public companion object {
        /** The wire spelling, which is what the database stores. */
        public fun fromWire(text: String): LinkKind? = when (text) {
            "tcp" -> Tcp
            "usb" -> Usb
            "ble" -> Ble
            else -> null
        }

        public val wireNames: List<String> = listOf("tcp", "usb", "ble")
    }
}

/**
 * One way to reach a radio.
 *
 * Typed columns rather than one address string: `host`/`port`, `devicePath`,
 * `endpointId` are different things, and a single "address" column would make
 * every query that touches it a parse (plan §8.2).
 */
public data class LinkTarget(
    public val kind: LinkKind,
    public val host: String? = null,
    public val port: Int? = null,
    public val devicePath: String? = null,
    public val baudRate: Int? = null,
    public val endpointId: String? = null,
    public val name: String? = null,
) {
    /** What a person reads: `10.0.0.5:5000`, `/dev/ttyACM0`. */
    public val label: String
        get() = when (kind) {
            LinkKind.Tcp -> "$host:$port"
            LinkKind.Usb -> devicePath.orEmpty()
            LinkKind.Ble -> name ?: endpointId.orEmpty()
        }

    /**
     * The text this target round-trips through: the scheme matters, because a
     * device path on its own is not a target (the CLI learned that the hard way).
     */
    public val canonical: String
        get() = when (kind) {
            LinkKind.Tcp -> "tcp://$host:$port"
            LinkKind.Usb -> "usb:$devicePath"
            LinkKind.Ble -> "ble:$endpointId"
        }
}

/** A saved link: a name a person chose, a target, and what happened last time. */
public data class SavedLink(
    public val id: String,
    public val radioId: String,
    public val target: LinkTarget,
    public val preferenceOrder: Int = 0,
    public val isPreferred: Boolean = false,
    public val lastConnectedAtMs: Long? = null,
    public val lastError: String? = null,
)

/**
 * What a radio said about itself, and nothing more.
 *
 * `persistentId` is the thing that stays the same — a USB path, a host:port, a
 * BLE MAC — so the same radio is recognised after a rename or a reboot. Board
 * names are as the firmware spells them; a chip part number never appears here,
 * because the firmware is the only thing that may know one.
 */
public data class RadioRecord(
    public val id: String,
    public val persistentId: String,
    public val name: String,
    public val boardName: String?,
    public val firmwareVersion: String?,
    public val specVersion: Int?,
    public val supportsWifi: Boolean = false,
    public val supportsBle: Boolean = false,
    public val supportsUsb: Boolean = false,
    public val hasBattery: Boolean = false,
    public val firstSeenAtMs: Long,
    public val lastSeenAtMs: Long? = null,
    public val lastBatteryMv: Int? = null,
    public val lastNoiseFloorDbm: Int? = null,
)

/**
 * The radios this build has seen, and the ways to reach them.
 *
 * An interface, not a class: `core-storage` implements it over SQLite and
 * `core-testing` implements it in memory, so a test needs no database file and
 * `core-domain` never learns what a database is.
 */
public interface RadioRepository {
    /** Every radio, most recently seen first. */
    public suspend fun radios(): List<RadioRecord>

    /**
     * Records what a radio reported, and returns it.
     *
     * Idempotent in `persistentId`: a radio that answers twice is one radio, with
     * its `lastSeenAt` moved and its `name` updated — never a second row.
     */
    public suspend fun record(radio: RadioRecord): RadioRecord

    public suspend fun radio(id: String): RadioRecord?

    /** The links for one radio, in preference order. */
    public suspend fun links(radioId: String): List<SavedLink>

    /**
     * Adds a link. [isPreferred] demotes whatever was preferred, in the same
     * transaction — there is one preferred link per radio, and a partial unique
     * index says so.
     */
    public suspend fun addLink(link: SavedLink): SavedLink

    public suspend fun removeLink(id: String)

    /** Notes that a link was used, or that it failed, and when. */
    public suspend fun noteLinkOutcome(id: String, connected: Boolean, atMs: Long, error: String? = null)

    /** Forgets a radio and everything hanging off it. */
    public suspend fun forgetRadio(id: String)
}