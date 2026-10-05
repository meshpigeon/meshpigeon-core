package dev.meshpigeon.storage

import app.cash.sqldelight.db.SqlDriver

/**
 * Opens the database on this platform.
 *
 * Native builds SQLite into the binary (`NativeSqliteDriver`, from SQLDelight's
 * own amalgamation) so `mp` has no runtime dependency; the JVM goes through
 * JDBC, which is the only SQLite a JVM application can rely on being present.
 *
 * Whatever the platform, the four pragmas of plan §8.5 are applied as the
 * connection opens — never in a migration, because SQLite ignores
 * `foreign_keys` inside a transaction.
 */
public expect class DatabaseDriverFactory() {
    public fun create(path: String): SqlDriver
}
