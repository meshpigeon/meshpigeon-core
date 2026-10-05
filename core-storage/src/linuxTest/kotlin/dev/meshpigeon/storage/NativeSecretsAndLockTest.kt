@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.meshpigeon.storage

import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.open
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.unlink
import platform.posix.write
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The native key store and lock, against the filesystem.
 *
 * Both are `0600` files beside the database, and both are POSIX calls: this is
 * the test that catches a mode or a path the JVM version could not see.
 */
class NativeSecretsAndLockTest {
    private val home: String = tempDirectory()

    @AfterTest
    fun tearDown() {
        unlink(keyFileFor(home))
        unlink("$home/keys")
        unlink("$home/meshpigeon.db.lock")
        unlink("$home/meshpigeon.db")
        unlink("$home/meshpigeon.db-wal")
        unlink("$home/meshpigeon.db-shm")
        rmdir("$home/keys")
    }

    @Test
    fun a_store_lands_its_database_where_it_was_told_to() {
        // SQLIter wants a database *name*, not a path, and picks the directory
        // itself — so this is the test that proves the file really lands at
        // `<home>/meshpigeon.db` rather than wherever the platform's
        // `DatabaseFileContext` would have defaulted to. It is also the only
        // native test that opens the driver at all, which is the gap that let a
        // full path reach SQLIter and crash the CLI before this existed.
        val store = MeshPigeonStore.open(home)
        try {
            assertTrue(fileExists("$home/meshpigeon.db"), "the database is at <home>/meshpigeon.db")
            assertEquals(1L, store.report().schemaVersion)
            assertEquals("wal", store.report().journalMode)
        } finally {
            store.close()
        }
    }

    @Test
    fun the_master_key_is_created_once_and_then_reused() {
        val first = SecretStore.open(home).masterKey()
        assertEquals(MASTER_KEY_BYTES, first.size)

        // A second session on the same home must get the same key, or everything
        // sealed by the first one becomes unreadable.
        val second = SecretStore.open(home).masterKey()
        assertContentEquals(first, second)
    }

    @Test
    fun a_new_key_is_different_every_time() {
        val store = SecretStore.open(home)
        val keys = (1..8).map { store.newKey() }
        assertEquals(MASTER_KEY_BYTES, keys.first().size)
        assertEquals(keys.size, keys.distinct().size, "newKey handed out the same key twice")
    }

    @Test
    fun the_key_file_is_owner_only() {
        SecretStore.open(home)
        val mode = fileMode(keyFileFor(home))
        // 0600 and nothing else: the key is on a machine that may be shared, and
        // the PID inside it is not a secret but the key is.
        assertEquals(0x180L, mode and 0x180L, "owner bits are not rw-")
        assertEquals(0L, mode and 0x07FL, "group or other can reach the key")
    }

    @Test
    fun the_key_file_is_created_under_keys() {
        SecretStore.open(home)
        assertTrue(keyFileFor(home).startsWith("$home/keys/"), keyFileFor(home))
    }

    @Test
    fun a_key_of_the_wrong_length_is_refused_with_the_path() {
        val path = keyFileFor(home)
        writeFile(path, ByteArray(7))
        val failure = assertFailsWith<IllegalStateException> { SecretStore.open(home) }
        // The refusal names the file: "your key is broken" with no path is a
        // support question instead of a fix.
        assertTrue(failure.message.orEmpty().contains(path), failure.message.orEmpty())
    }

    @Test
    fun the_lock_names_the_process_that_holds_it() {
        val databasePath = "$home/meshpigeon.db"
        val lock = acquireDatabaseLock(databasePath)
        try {
            assertEquals(currentPid(), heldProcessId(databasePath))
            val failure = assertFailsWith<IllegalStateException> { acquireDatabaseLock(databasePath) }
            assertTrue(failure.message.orEmpty().contains("pid ${currentPid()}"), failure.message.orEmpty())
        } finally {
            lock.release()
        }
        assertNull(heldProcessId(databasePath))
    }

    @Test
    fun the_lock_file_is_owner_only() {
        val databasePath = "$home/meshpigeon.db"
        val lock = acquireDatabaseLock(databasePath)
        try {
            // Looked at before the release: afterwards the file is gone, and a
            // mode test that passes because its subject vanished is not a test.
            assertEquals(0L, fileMode("$databasePath.lock") and 0x07FL, "group or other can rewrite the lock")
        } finally {
            lock.release()
        }
    }

    /** `S_IRWXU`. */
    private fun tempDirectory(): String =
        "/tmp/mp-storage-${kotlin.random.Random.nextLong().toString(16)}".also {
            mkdir(it, 0x1C0u.convert())
        }

    private fun fileMode(path: String): Long = memScoped {
        val info = alloc<stat>()
        if (stat(path, info.ptr) != 0) throw IllegalStateException("stat($path) failed")
        info.st_mode.convert<Long>() and 0xFFFFL
    }

    private fun fileExists(path: String): Boolean = memScoped {
        val info = alloc<stat>()
        stat(path, info.ptr) == 0
    }

    private fun currentPid(): Int = getpid().convert()

    private fun writeFile(path: String, bytes: ByteArray) {
        mkdir("$home/keys", 0x1C0u.convert())
        val handle = open(path, O_RDWR or O_CREAT, 0x180)
        bytes.usePinned { pinned -> write(handle, pinned.addressOf(0), bytes.size.toULong()) }
        close(handle)
    }
}