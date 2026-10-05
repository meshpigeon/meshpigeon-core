package dev.meshpigeon.core.testing

import dev.meshpigeon.core.common.Clock
import dev.meshpigeon.core.common.Logger

/** A clock the test moves by hand. Time only changes when a test says so. */
public class FakeClock(
    private var wallMs: Long = 1_700_000_000_000L,
    private var monotonicMs: Long = 1_000L,
) : Clock {
    override fun nowMs(): Long = wallMs

    override fun monotonicMs(): Long = monotonicMs

    /** Moves both readings together, as a real clock does. */
    public fun advance(ms: Long) {
        wallMs += ms
        monotonicMs += ms
    }

    /** Sets the wall clock without touching the monotonic reading. */
    public fun setWallClock(ms: Long) {
        wallMs = ms
    }

    /** Sets the monotonic reading without touching the wall clock. */
    public fun setMonotonic(ms: Long) {
        monotonicMs = ms
    }
}

/** One line a [RecordingLogger] kept. */
public data class LogLine(val level: dev.meshpigeon.core.common.LogLevel, val message: String)

/** A logger that keeps its lines, so a test can assert on what was reported. */
public class RecordingLogger : Logger {
    private val lines = mutableListOf<LogLine>()

    public val recorded: List<LogLine> get() = lines.toList()

    override fun log(level: dev.meshpigeon.core.common.LogLevel, message: String, error: Throwable?) {
        lines += LogLine(level, message)
    }

    public fun clear() {
        lines.clear()
    }
}