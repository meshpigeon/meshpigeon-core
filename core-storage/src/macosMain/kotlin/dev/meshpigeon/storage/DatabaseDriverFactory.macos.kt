package dev.meshpigeon.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

/**
 * The macOS driver: SQLDelight's native driver against the system SQLite, which
 * macOS has always shipped — so there is nothing to install and nothing to find
 * at runtime.
 */
public actual class DatabaseDriverFactory actual constructor() {
    public actual fun create(path: String): SqlDriver {
        // SQLDelight's native driver takes a database *name*, not a path: SQLIter
        // rejects a name with a path separator and puts the file wherever its
        // `DatabaseFileContext` decides. That directory is `Extended.basePath`, so
        // the name is the last segment of the path and the directory is the rest.
        val directory = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        // The schema is handed to the driver rather than applied afterwards, so a
        // new file is created and an old one migrated in one step, and the
        // pragmas go through `applyPragmas` for the same reason the JVM sets them
        // on every connection: SQLite ignores `foreign_keys` inside a transaction.
        return NativeSqliteDriver(
            schema = MeshPigeonDatabase.Schema,
            name = name,
            onConfiguration = { config ->
                config.copy(extendedConfig = config.extendedConfig.copy(basePath = directory))
            },
        ).also { it.applyPragmas() }
    }
}
