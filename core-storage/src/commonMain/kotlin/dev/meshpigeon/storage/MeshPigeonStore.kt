package dev.meshpigeon.storage

import app.cash.sqldelight.db.SqlDriver
import dev.meshpigeon.domain.RadioRepository

/**
 * The one database, open.
 *
 * Everything a client needs in one object: the repository, a report about the
 * file, and a [close] that releases the lock. The SQLDelight driver stays
 * inside this module on purpose — a client that had to name `SqlDriver` would
 * know there was SQLite, and the next thing it would do is write SQL. That is
 * the boundary the CLI's import gate enforces, and this class is what makes it
 * possible rather than aspirational.
 *
 * Order matters here, and it is the order the plan gives (§8.5):
 *
 * 1. the lock, so two sessions cannot both be "the one session";
 * 2. the file, created or migrated;
 * 3. the pragmas, per connection, inside the factory.
 *
 * The lock is taken first because a refusal is cheap and the alternative — a
 * schema migration run by a process that is about to be told it is the second
 * one — is not.
 */
public class MeshPigeonStore private constructor(
    // Private, and never in a public signature: a client that cannot name the
    // driver cannot write SQL against it, which is the point of this class.
    private val driver: SqlDriver,
    private val lock: DatabaseLock,
    private val home: String,
    private val database: MeshPigeonDatabase,
) {
    /** The radios and the ways to reach them. */
    public val radios: RadioRepository = SqlDelightRadioRepository(database)

    /**
     * What the file looks like right now, for `/db`.
     *
     * Read on demand rather than cached: a report that remembered its numbers
     * would answer with what was true when the session opened.
     */
    public fun report(): DatabaseReport = DatabaseReport(
        path = databasePath,
        sizeBytes = driver.databaseSizeBytes(),
        schemaVersion = driver.schemaVersion(),
        journalMode = driver.journalMode(),
        radios = database.radiosQueries.countRadios().executeAsOne().toInt(),
        links = database.radiosQueries.countLinks().executeAsOne().toInt(),
        pinsSealed = database.radiosQueries.countSealedPins().executeAsOne().toInt(),
        keyPresent = SecretStore.hasMasterKey(home),
        heldByPid = currentProcessId(),
    )

    /** The path this store opened, which is `<home>/meshpigeon.db`. */
    public val databasePath: String get() = "$home/$DATABASE_NAME"

    /**
     * Closes the database and gives up the lock.
     *
     * Closing before releasing: a lock released first would let a second
     * session open a file this one still has open, and on a network filesystem
     * that is two writers rather than a refusal.
     */
    public fun close() {
        driver.close()
        lock.release()
    }

    public companion object {
        /**
         * Opens the database for [home], creating and locking it.
         *
         * @throws IllegalStateException when another live process holds the
         *   lock — the message names the PID, because "the database is locked"
         *   is the least useful sentence a person can be handed at startup.
         */
        public fun open(home: String): MeshPigeonStore {
            // The home is created before anything is opened in it: a first run
            // has no `~/.local/share/meshpigeon`, and a lock file cannot be
            // written into a directory that is not there yet.
            ensureDirectory(home)
            val path = "$home/$DATABASE_NAME"
            val lock = acquireDatabaseLock(path)
            return try {
                val driver = DatabaseDriverFactory().create(path)
                MeshPigeonStore(driver, lock, home, MeshPigeonDatabase(driver))
            } catch (failure: Throwable) {
                // A failed open must not leave a lock file behind: the next
                // session would be refused by a process that no longer exists,
                // and a stale lock is exactly the failure this design removes.
                lock.release()
                throw failure
            }
        }
    }
}

/** The file name inside `MP_HOME`. Plan §8.5. */
public const val DATABASE_NAME: String = "meshpigeon.db"

/**
 * What `/db` reports: the file, as far as a session can see it.
 *
 * Plain data on purpose, and the reason [MeshPigeonStore] does not hand out its
 * driver. A report is a set of facts about a file, and a client that can ask
 * questions the schema has not been asked yet is a client that will.
 */
public data class DatabaseReport(
    val path: String,
    val sizeBytes: Long,
    /** `PRAGMA user_version` — the one schema counter. */
    val schemaVersion: Long,
    /** `wal`, or whatever the file is actually in. */
    val journalMode: String,
    val radios: Int,
    val links: Int,
    /** Devices with a sealed PIN. Zero until P3 supplies the cipher (D53). */
    val pinsSealed: Int,
    val keyPresent: Boolean,
    val heldByPid: Int,
)
