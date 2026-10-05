@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.meshpigeon.storage

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.errno
import platform.posix.mkdir
import platform.posix.open
import platform.posix.read
import platform.posix.strerror
import platform.posix.write

/**
 * The key store, on POSIX (macOS shares this file verbatim).
 *
 * Randomness comes from `/dev/urandom`, the kernel's own, which is the same
 * reasoning as SQLite being linked into the binary rather than found at runtime:
 * nothing to install, nothing to locate.
 */
internal actual fun loadOrCreateMasterKey(home: String): ByteArray? {
    val path = keyFileFor(home)
    readKeyFile(path)?.let { return it }

    mkdir("$home/keys", OWNER_DIRECTORY_ONLY.convert())
    val fresh = newMasterKey()
    val handle = open(path, O_RDWR or O_CREAT, OWNER_FILE_ONLY)
    if (handle < 0) {
        throw IllegalStateException(keyFileRefusal(home, "the key at $path could not be created (${strerror(errno)})"))
    }
    fresh.usePinned { pinned -> write(handle, pinned.addressOf(0), fresh.size.toULong()) }
    close(handle)
    return fresh
}

internal actual fun newMasterKey(): ByteArray = ByteArray(MASTER_KEY_BYTES).also {
    readFully("/dev/urandom", it)
}

/**
 * Reads the key at [path], or null when there is none.
 *
 * A file that exists but is the wrong length comes back as itself, not as null:
 * "no key yet" and "the key is broken" are different failures, and only the
 * first one may create a key.
 */
private fun readKeyFile(path: String): ByteArray? {
    val handle = open(path, O_RDWR)
    if (handle < 0) return null
    val buffer = ByteArray(MAX_KEY_FILE_BYTES)
    val read = readFully(path, buffer, handle)
    close(handle)
    return buffer.copyOf(read)
}

/** Reads until [bytes] is full or the source stops; returns how much arrived. */
private fun readFully(path: String, bytes: ByteArray, handle: Int = -1): Int {
    val source = if (handle >= 0) handle else open(path, O_RDWR)
    if (source < 0) return 0
    var read_ = 0
    while (read_ < bytes.size) {
        val count = bytes.usePinned { pinned ->
            read(source, pinned.addressOf(read_), (bytes.size - read_).toULong())
        }
        if (count <= 0L) break
        read_ += count.toInt()
    }
    if (handle < 0) close(source)
    return read_
}

/**
 * `S_IRWXU` for the directory, `S_IRUSR | S_IWUSR` for the key inside it.
 *
 * Two constants because they are two things: 0700 on a *file* would make the key
 * executable, which is how the mode test caught this.
 */
private const val OWNER_DIRECTORY_ONLY = 0x1C0
private const val OWNER_FILE_ONLY = 0x180
