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
    // Through the *query* path, never `execute`: on the native driver `execute`
    // is SQLIter's `execSQL`, which refuses any statement that returns rows — and
    // `journal_mode = WAL` and `busy_timeout = 5000` both answer with the value
    // they just set. A pragma is a query whether or not anyone reads the answer.
    pragma("PRAGMA journal_mode = WAL;")
    pragma("PRAGMA synchronous = NORMAL;")
    pragma("PRAGMA foreign_keys = ON;")
    pragma("PRAGMA busy_timeout = 5000;")
}

/** Runs a statement for its effect, discarding any row it returns. */
private fun SqlDriver.pragma(sql: String) {
    executeQuery(null, sql, { _ -> QueryResult.Value(Unit) }, 0).value
}

/** What `PRAGMA user_version` says — the single source of schema truth. */
public fun SqlDriver.schemaVersion(): Long =
    executeQuery(null, "PRAGMA user_version;", { cursor ->
        QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
    }, 0).value

/** What `PRAGMA journal_mode` says — `wal` on a healthy database. */
public fun SqlDriver.journalMode(): String =
    executeQuery(null, "PRAGMA journal_mode;", { cursor ->
        QueryResult.Value(if (cursor.next().value) cursor.getString(0).orEmpty() else "")
    }, 0).value

/**
 * The file's size in bytes, from SQLite rather than from the filesystem.
 *
 * `page_count * page_size` is the database itself; a file length would also
 * count whatever WAL happens to be beside it, which is not what "how big is the
 * database" means.
 */
public fun SqlDriver.databaseSizeBytes(): Long =
    executeQuery(null, "SELECT page_count * page_size FROM pragma_page_count(), pragma_page_size();", { cursor ->
        QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
    }, 0).value

/** Sets `PRAGMA user_version`, which is how a migration is recorded. */
public fun SqlDriver.setSchemaVersion(version: Long) {
    execute(null, "PRAGMA user_version = $version;", 0)
}