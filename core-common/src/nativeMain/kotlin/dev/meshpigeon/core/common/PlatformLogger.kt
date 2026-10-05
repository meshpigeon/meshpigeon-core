@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.meshpigeon.core.common

import platform.posix.fprintf
import platform.posix.stderr

actual fun platformStderrLogger(name: String, minLevel: LogLevel): Logger = CallbackLogger { level, message, error ->
    if (level.ordinal < minLevel.ordinal) return@CallbackLogger
    val prefix = when (level) {
        LogLevel.DEBUG -> "D"
        LogLevel.INFO -> "I"
        LogLevel.WARN -> "W"
        LogLevel.ERROR -> "E"
    }
    val line = buildString {
        append(prefix).append(' ').append(name).append(": ").append(message)
        error?.let { append(" | ").append(it.toString()) }
    }
    fprintf(stderr, "%s\n", line)
}