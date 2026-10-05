package dev.meshpigeon.core.common

/** Severity levels, ordered: a logger with a threshold drops everything below it. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Where diagnostics go. Clients get logging from core this way rather than from
 * a logging framework, so no module can quietly bind to one.
 */
interface Logger {
    fun log(level: LogLevel, message: String, error: Throwable? = null)

    fun debug(message: String) = log(LogLevel.DEBUG, message)
    fun info(message: String) = log(LogLevel.INFO, message)
    fun warn(message: String, error: Throwable? = null) = log(LogLevel.WARN, message, error)
    fun error(message: String, error: Throwable? = null) = log(LogLevel.ERROR, message, error)

    companion object {
        /** Discards everything. The default, so library code is quiet in tests. */
        val None: Logger = object : Logger {
            override fun log(level: LogLevel, message: String, error: Throwable?) = Unit
        }
    }
}

/** A logger that hands every line to [sink]. Useful in tests. */
class CallbackLogger(private val sink: (LogLevel, String, Throwable?) -> Unit) : Logger {
    override fun log(level: LogLevel, message: String, error: Throwable?) = sink(level, message, error)
}

/**
 * A logger writing to the platform's standard error. Standard *output* belongs
 * to the client — core never prints to it.
 */
expect fun platformStderrLogger(name: String, minLevel: LogLevel = LogLevel.INFO): Logger