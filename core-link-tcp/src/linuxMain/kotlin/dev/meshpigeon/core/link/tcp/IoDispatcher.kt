package dev.meshpigeon.core.link.tcp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Kotlin/Native has no `Dispatchers.IO`; the default pool is a real one. */
internal val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Default
