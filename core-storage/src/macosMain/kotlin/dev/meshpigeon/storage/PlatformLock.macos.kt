@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.meshpigeon.storage

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.getpid
import platform.posix.kill
import platform.posix.open
import platform.posix.read
import platform.posix.unlink
import platform.posix.write

/**
 * The lock, on POSIX (macOS shares this file verbatim — `open`, `write`,
 * `kill(0)` and a `0600` mode all mean the same thing on both).
 *
 * The lock file holds this process's ID and is created `0600`: the PID is not a
 * secret, but there is no reason for anyone else to be able to rewrite it.
 */
public actual fun acquireDatabaseLock(databasePath: String): DatabaseLock {
    val lockPath = lockFileFor(databasePath)
    val pid: Int = getpid().convert()

    val held = readPid(lockPath)
    if (!lockIsFree(held)) throw IllegalStateException(lockHeldMessage(held, lockPath))

    val handle = open(lockPath, O_RDWR or O_CREAT, OWNER_FILE_ONLY)
    if (handle < 0) throw IllegalStateException("the lock file $lockPath could not be created")
    val bytes = LOCK_CONTENT.replace("%d", pid.toString()).encodeToByteArray()
    bytes.usePinned { pinned -> write(handle, pinned.addressOf(0), bytes.size.toULong()) }
    close(handle)

    return DatabaseLock {
        // Only ever our own lock: a session that was refused never gets here, so
        // this can never remove the lock a live session is holding.
        unlink(lockPath)
    }
}

public actual fun lockHeldIn(lockPath: String): Int? = readPid(lockPath)

public actual fun heldProcessId(databasePath: String): Int? = readPid(lockFileFor(databasePath))

public actual fun isRunning(pid: Int): Boolean =
    // Signal 0 asks whether a process exists without delivering anything.
    kill(pid.convert(), 0) == 0

/** Reads the PID out of a lock file, or null when nothing is there. */
private fun readPid(lockPath: String): Int? {
    val handle = open(lockPath, O_RDONLY)
    if (handle < 0) return null
    val buffer = ByteArray(64)
    val count = buffer.usePinned { pinned -> read(handle, pinned.addressOf(0), buffer.size.toULong()) }
    close(handle)
    if (count <= 0L) return null
    return buffer.decodeToString(0, count.toInt())
        .substringAfter("pid ", "")
        .trim()
        .toIntOrNull()
}

/** `S_IRUSR | S_IWUSR` — the lock is the owner's business. */
private const val OWNER_FILE_ONLY = 0x180