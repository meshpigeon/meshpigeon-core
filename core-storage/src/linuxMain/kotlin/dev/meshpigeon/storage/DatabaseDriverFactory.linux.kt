package dev.meshpigeon.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

/**
 * The Linux driver: SQLDelight's native driver against the system SQLite
 * (`libsqlite3`, which is why the link flags above exist).
 */
public actual class DatabaseDriverFactory actual constructor() {
    public actual fun create(path: String): SqlDriver {
        // The schema is handed to the driver rather than applied afterwards, so a
        // new file is created and an old one migrated in one step, and the
        // pragmas go through `applyPragmas` for the same reason the JVM sets them
        // on every connection: SQLite ignores `foreign_keys` inside a transaction.
        return NativeSqliteDriver(MeshPigeonDatabase.Schema, path).also { it.applyPragmas() }
    }
}
