package dev.meshpigeon.core.common

import dev.meshpigeon.core.common.LogLevel.DEBUG
import dev.meshpigeon.core.common.LogLevel.ERROR
import dev.meshpigeon.core.common.LogLevel.INFO
import dev.meshpigeon.core.common.LogLevel.WARN

actual fun platformStderrLogger(name: String, minLevel: LogLevel): Logger = CallbackLogger { level, message, error ->
    if (level.ordinal < minLevel.ordinal) return@CallbackLogger
    val prefix = when (level) {
        DEBUG -> "D"
        INFO -> "I"
        WARN -> "W"
        ERROR -> "E"
    }
    val line = buildString {
        append(prefix).append(' ').append(name).append(": ").append(message)
        error?.let { append(" | ").append(it.toString()) }
    }
    System.err.println(line)
}