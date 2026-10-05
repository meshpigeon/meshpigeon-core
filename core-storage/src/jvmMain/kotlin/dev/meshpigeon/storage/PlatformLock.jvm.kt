package dev.meshpigeon.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * The lock, on the JVM.
 *
 * The same file, the same contents and the same refusal as POSIX; only the
 * system calls differ. `0600` is requested through the file attributes rather
 * than by a umask, so a shared machine does not decide it for us.
 */
public actual fun acquireDatabaseLock(databasePath: String): DatabaseLock {
    val lockPath = lockFileFor(databasePath)
    val pid = ProcessHandle.current().pid()

    val held = readPid(lockPath)
    if (!lockIsFree(held)) throw IllegalStateException(lockHeldMessage(held, lockPath))

    val file = File(lockPath)
    file.parentFile?.mkdirs()
    Files.write(
        file.toPath(),
        LOCK_CONTENT.replace("%d", pid.toString()).toByteArray(),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    )
    runCatching {
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
    }

    return DatabaseLock {
        // Only ever our own lock: a session that was refused never gets here, so
        // this can never remove the lock a live session is holding.
        file.delete()
    }
}

public actual fun lockHeldIn(lockPath: String): Int? = readPid(lockPath)

public actual fun heldProcessId(databasePath: String): Int? = readPid(lockFileFor(databasePath))

public actual fun isRunning(pid: Int): Boolean =
    ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false)

private fun readPid(lockPath: String): Int? {
    val file = File(lockPath)
    if (!file.isFile) return null
    return runCatching { file.readText().substringAfter("pid ", "").trim().toIntOrNull() }.getOrNull()
}

/** The same answer the lock writes, from the same source it already uses. */
public actual fun currentProcessId(): Int = ProcessHandle.current().pid().toInt()
