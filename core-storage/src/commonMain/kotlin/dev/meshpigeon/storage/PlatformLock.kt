package dev.meshpigeon.storage

/**
 * The parts of the lock that are the same everywhere: where the lock file is,
 * what it holds, and the sentence a refused session is given.
 *
 * What differs — creating the file, the process ID, and whether that process is
 * alive — is a genuine platform boundary, so it is `expect`/`actual` in
 * `PlatformLock.native.kt` and `PlatformLock.jvm.kt`.
 */
internal fun lockFileFor(databasePath: String): String = "$databasePath.lock"

internal const val LOCK_CONTENT = "meshpigeon session lock, pid %d"

/**
 * The refusal, naming the holder.
 *
 * The PID is the whole point: two sessions on one database is the failure this
 * exists to prevent, and knowing which one to stop is the answer.
 */
internal fun lockHeldMessage(pid: Int?, lockPath: String): String = when (pid) {
    null -> "another mp is using this database ($lockPath)"
    else -> "another mp (pid $pid) is using this database ($lockPath)"
}

/** A stale lock file from a session that crashed is not a lock. */
internal fun lockIsFree(pid: Int?): Boolean = when (pid) {
    null -> true
    else -> !isRunning(pid)
}
