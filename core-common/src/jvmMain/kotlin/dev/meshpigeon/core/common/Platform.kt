package dev.meshpigeon.core.common

import java.security.SecureRandom

private val secureRandom = SecureRandom()

actual fun platformRandomBytes(size: Int): ByteArray =
    ByteArray(size).also { secureRandom.nextBytes(it) }
