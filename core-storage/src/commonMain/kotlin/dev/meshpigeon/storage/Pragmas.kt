package dev.meshpigeon.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * The one database, opened the way the plan says it is opened (§8.5).
 *
 * Four pragmas, all of which are decisions rather than defaults:
 *
 * * `journal_mode=WAL` — a reader never blocks the writer, so `/db` can report
 *   while packets are being journaled.
 * * `synchronous=NORMAL` — with WAL this is the durability a desktop app wants:
 *   a crash may lose the last transactions, never the database.
 * * `foreign_keys=ON` — **set on every connection, never in a migration**,
 *   because SQLite silently ignores it inside a transaction.
 * * `busy_timeout=5000` — a second writer waits rather than failing; the plan's
 *   "one process" rule keeps that a rare case rather than the normal one.
 */
public fun SqlDriver.applyPragmas() {
    execute(null, "PRAGMA journal_mode = WAL;", 0)
    execute(null, "PRAGMA synchronous = NORMAL;", 0)
    execute(null, "PRAGMA foreign_keys = ON;", 0)
    execute(null, "PRAGMA busy_timeout = 5000;", 0)
}

/** What `PRAGMA user_version` says — the single source of schema truth. */
public fun SqlDriver.schemaVersion(): Long =
    executeQuery(null, "PRAGMA user_version;", { cursor ->
        QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
    }, 0).value

/** Sets `PRAGMA user_version`, which is how a migration is recorded. */
public fun SqlDriver.setSchemaVersion(version: Long) {
    execute(null, "PRAGMA user_version = $version;", 0)
}