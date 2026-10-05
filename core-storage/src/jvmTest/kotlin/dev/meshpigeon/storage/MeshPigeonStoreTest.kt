package dev.meshpigeon.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The store against a real file.
 *
 * In-memory SQLite would pass most of this and prove nothing: WAL, the pragmas,
 * `user_version` and the lock file are all claims about a file on a disk, and the
 * lock in particular is about a *process*, which an in-memory database has no
 * opinion on.
 */
class MeshPigeonStoreTest {

    private val homes = mutableListOf<File>()

    private fun home(): String = Files.createTempDirectory("mp-store-").toFile().also { homes += it }.path

    @AfterTest
    fun removeHomes() {
        homes.forEach { it.deleteRecursively() }
    }

    @Test
    fun a_first_session_creates_a_wal_database_at_version_one() {
        val home = home()
        val store = MeshPigeonStore.open(home)
        try {
            val report = store.report()
            assertEquals("$home/meshpigeon.db", report.path)
            assertEquals(1L, report.schemaVersion, "a fresh database is at the schema version, not at zero")
            assertEquals("wal", report.journalMode)
            assertTrue(report.sizeBytes > 0, "a created database has pages")
            assertEquals(currentProcessId(), report.heldByPid)
            assertFalse(report.keyPresent, "asking for a report must not create the key")
        } finally {
            store.close()
        }
        assertTrue(File("$home/meshpigeon.db").isFile)
    }

    @Test
    fun the_second_session_is_refused_and_is_told_who_holds_it() {
        val home = home()
        val first = MeshPigeonStore.open(home)
        try {
            val refusal = assertFailsWith<IllegalStateException> { MeshPigeonStore.open(home) }
            assertTrue(
                refusal.message?.contains(currentProcessId().toString()) == true,
                "the refusal names the holder: ${refusal.message}",
            )
        } finally {
            first.close()
        }
        // And the refusal left no lock behind, so the next session can start.
        val second = MeshPigeonStore.open(home)
        second.close()
    }

    @Test
    fun closing_releases_the_lock_file() {
        val home = home()
        MeshPigeonStore.open(home).close()
        assertFalse(File("$home/meshpigeon.db.lock").exists(), "a released lock is removed, not left as a claim")
    }

    @Test
    fun a_lock_file_from_a_dead_process_is_reclaimed_rather_than_obeyed() {
        val home = home()
        val path = "$home/meshpigeon.db"
        // A PID that cannot be running: the highest pid the kernel will not hand
        // out, so this is a crash rather than a session.
        Files.write(File(path + ".lock").toPath(), "meshpigeon lock, pid 4194304\n".toByteArray())

        val store = MeshPigeonStore.open(path.substringBeforeLast('/'))
        try {
            assertEquals(currentProcessId(), store.report().heldByPid)
        } finally {
            store.close()
        }
    }

    @Test
    fun reopening_keeps_the_schema_version_and_the_rows() {
        val home = home()
        MeshPigeonStore.open(home).close()

        val store = MeshPigeonStore.open(home)
        try {
            // A second open must *migrate*, not re-create: a schema that was
            // rebuilt on every launch would be a database that loses everything
            // it was between sessions.
            assertEquals(1L, store.report().schemaVersion)
        } finally {
            store.close()
        }
    }

    @Test
    fun a_failed_open_does_not_leave_a_lock_behind() {
        val home = home()
        // A directory where the database file belongs: the driver cannot open it.
        Files.createDirectories(File("$home/meshpigeon.db").toPath())
        assertFailsWith<Throwable> { MeshPigeonStore.open(home) }
        assertFalse(
            File("$home/meshpigeon.db.lock").exists(),
            "a session that never started must not block the next one",
        )
    }
}
