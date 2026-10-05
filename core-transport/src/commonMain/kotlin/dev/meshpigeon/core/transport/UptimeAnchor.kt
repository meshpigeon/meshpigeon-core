package dev.meshpigeon.core.transport

/**
 * Maps the radio's monotonic uptime onto the client's wall clock.
 *
 * The radio has no RTC, so time correlation is the app's job (docs §5.1): anchor
 * once per connection, re-anchor periodically, and every packet timestamp is
 * then plain arithmetic. A reboot legitimately restarts uptime, which shows up
 * here as a reboot rather than as a timestamp from the future.
 */
public class UptimeAnchor(
    private var uptimeAtAnchorMs: Long = 0,
    private var wallAtAnchorMs: Long = 0,
    private var anchored: Boolean = false,
) {
    /** Whether an anchor has been taken. */
    public val isAnchored: Boolean get() = anchored

    /** The uptime reading the anchor was taken at. */
    public val anchorUptimeMs: Long get() = uptimeAtAnchorMs

    /** The wall clock the anchor was taken at. */
    public val anchorWallMs: Long get() = wallAtAnchorMs

    /**
     * Takes a new anchor.
     *
     * @return true when this reading shows the radio rebooted since the last
     *   anchor — uptime went backwards — which invalidates every timestamp
     *   derived from the old one.
     */
    public fun anchor(radioUptimeMs: Long, wallClockMs: Long): Boolean {
        val rebooted = anchored && radioUptimeMs < uptimeAtAnchorMs
        uptimeAtAnchorMs = radioUptimeMs
        wallAtAnchorMs = wallClockMs
        anchored = true
        return rebooted
    }

    /** The wall-clock time of a packet stamped with [radioUptimeMs]. */
    public fun wallMsFor(radioUptimeMs: Long): Long {
        if (!anchored) return 0
        // The delta may be negative after a reboot; clamp so a packet can never
        // be dated before the anchor itself.
        val delta = (radioUptimeMs - uptimeAtAnchorMs).coerceAtLeast(0)
        return wallAtAnchorMs + delta
    }

    /** How far the radio's uptime is from this anchor, in milliseconds. */
    public fun driftMs(radioUptimeMs: Long): Long = radioUptimeMs - uptimeAtAnchorMs
}