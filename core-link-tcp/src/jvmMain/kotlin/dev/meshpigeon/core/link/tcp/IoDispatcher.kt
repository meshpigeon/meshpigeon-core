package dev.meshpigeon.core.link.tcp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** JVM's IO pool: exactly what a blocking socket read wants. */
internal val ioDispatcher: CoroutineDispatcher get() = Dispatchers.IO
