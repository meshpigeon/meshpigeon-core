package dev.meshpigeon.core.common

import kotlin.time.Clock as KotlinClock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/**
 * Time, as the rest of MeshPigeon sees it: UTC epoch milliseconds, plus a
 * monotonic reading for durations.
 *
 * Injected rather than called statically so tests can freeze time (a fake
 * clock is part of `meshpigeon-core-testing`), and so a client can hand in a
 * clock of its own where that is what it wants.
 */
interface Clock {
    /** Wall-clock time as UTC epoch milliseconds. */
    fun nowMs(): Long

    /** Monotonic milliseconds; only differences are meaningful. */
    fun monotonicMs(): Long

    companion object {
        /** The platform clock. */
        val System: Clock = PlatformClock
    }
}

/**
 * Wall-clock time in UTC epoch milliseconds, from the platform.
 *
 * The stdlib's own clock, so no module carries a `clock_gettime` cinterop and
 * every target reads time the same way; the *value* every caller sees is still
 * "milliseconds since the epoch" and never a raw counter.
 */
@OptIn(ExperimentalTime::class)
public object PlatformClock : Clock {
    override fun nowMs(): Long = KotlinClock.System.now().toEpochMilliseconds()

    override fun monotonicMs(): Long = TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds
}

/**
 * Platform random bytes.
 *
 * Enough for UUIDv7 identifiers and jitter; **not** a CSPRNG. Key material
 * comes from `core-crypto`, which owns its own entropy source — this exists so
 * `core-common` has no crypto dependency.
 */
expect fun platformRandomBytes(size: Int): ByteArray