package dev.meshpigeon.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteConfig.JournalMode
import org.sqlite.SQLiteConfig.SynchronousMode

/**
 * The JVM driver: JDBC, because that is the SQLite a JVM application can count
 * on being present.
 *
 * The pragmas go through the **connection configuration**, not a statement run
 * once after connecting. That is the whole point of plan §8.5: SQLite ignores
 * `PRAGMA foreign_keys` inside a transaction and applies connection settings per
 * connection, so a pragma sent down one connection leaves every other one — and
 * every cascade — unprotected. `SQLiteConfig` is what the driver applies to each
 * connection it opens, which is the open hook the plan asks for.
 */
public actual class DatabaseDriverFactory actual constructor() {
    public actual fun create(path: String): SqlDriver {
        // "A new file is created, an old one is migrated" is stated here rather
        // than left to the driver: creating over an existing database is how a
        // schema gets wiped by accident.
        val fresh = !File(path).exists() || File(path).length() == 0L
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path", connectionProperties())
        if (fresh) {
            MeshPigeonDatabase.Schema.create(driver).value
            // `Schema.create` does not write `user_version`, and the next open
            // decides create-or-migrate by reading it.
            driver.setSchemaVersion(MeshPigeonDatabase.Schema.version)
        } else {
            migrateIfBehind(driver)
        }
        return driver
    }

    /**
     * Runs the migrations this schema carries, if the file is behind them.
     *
     * `PRAGMA user_version` is the truth (plan §8.2): nothing here counts
     * migrations, and nothing here decides to skip one.
     */
    private fun migrateIfBehind(driver: SqlDriver) {
        val from = driver.schemaVersion()
        val target = MeshPigeonDatabase.Schema.version
        if (from < target) {
            MeshPigeonDatabase.Schema.migrate(driver, from, target).value
            driver.setSchemaVersion(target)
        }
    }

    /**
     * A private, in-memory database with the schema created — what the tests and
     * `--dry-run` use, so neither needs a file or a lock.
     */
    public fun inMemory(): SqlDriver = JdbcSqliteDriver(IN_MEMORY, connectionProperties()).also { driver ->
        MeshPigeonDatabase.Schema.create(driver).value
        driver.setSchemaVersion(MeshPigeonDatabase.Schema.version)
    }

    private companion object {
        const val IN_MEMORY = JdbcSqliteDriver.IN_MEMORY

        /**
         * WAL so a reader never blocks the writer, `NORMAL` durability with it,
         * foreign keys on every connection, and five seconds of patience before
         * a writer gives up (plan §8.5).
         */
        fun connectionProperties(): Properties = Properties().apply {
            putAll(
                SQLiteConfig().apply {
                    enforceForeignKeys(true)
                    setJournalMode(JournalMode.WAL)
                    setSynchronous(SynchronousMode.NORMAL)
                    setBusyTimeout(5_000)
                }.toProperties(),
            )
        }
    }
}
