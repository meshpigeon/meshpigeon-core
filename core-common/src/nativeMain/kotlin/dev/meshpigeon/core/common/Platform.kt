package dev.meshpigeon.core.common

actual fun platformRandomBytes(size: Int): ByteArray = kotlin.random.Random.Default.nextBytes(size)
