package dev.meshpigeon.storage

/**
 * One process at a time (plan D10b).
 *
 * SQLite would happily let two `mp`s write the same file, and the result would
 * be two sessions each believing it owns the radio and the outbox. So the
 * second one is refused *by name* — with the PID of the session that holds the
 * lock — because "the database is locked" is the least useful sentence a person
 * can be handed at startup.
 *
 * The lock is the database file with `.lock` beside it, holding the process ID.
 * A file left by a session that crashed is stale, not held: [lockIsFree] asks
 * the platform whether that process is still alive, which is the difference
 * between "another mp is using this database" and a session that will never
 * start again.
 */
public class DatabaseLock internal constructor(private val releaseAction: () -> Unit) {
    /** Releases it. Calling it twice is not an error. */
    public fun release() {
        releaseAction()
    }
}

/**
 * Takes the lock for [databasePath], or refuses and says who holds it.
 *
 * @throws IllegalStateException when another live process holds it.
 */
public expect fun acquireDatabaseLock(databasePath: String): DatabaseLock

/** The PID recorded in [lockPath], or null when nothing holds it. */
public expect fun lockHeldIn(lockPath: String): Int?

/** The PID recorded beside [databasePath], or null. */
public expect fun heldProcessId(databasePath: String): Int?

/** Whether [pid] names a process that is still running. */
public expect fun isRunning(pid: Int): Boolean
