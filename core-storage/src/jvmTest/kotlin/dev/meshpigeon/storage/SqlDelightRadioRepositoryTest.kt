package dev.meshpigeon.storage

import dev.meshpigeon.domain.LinkKind
import dev.meshpigeon.domain.LinkTarget
import dev.meshpigeon.domain.RadioRecord
import dev.meshpigeon.domain.RadioRepository
import dev.meshpigeon.domain.SavedLink
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The radio repository against a real SQLite file.
 *
 * Not an in-memory database: WAL, the pragmas, the cascade on delete and the
 * partial unique index are all claims about a *file*, and an in-memory database
 * would quietly pass without any of them.
 */
class SqlDelightRadioRepositoryTest {
    private val home: String = createTempDirectory()
    private val databaseFile = "$home/meshpigeon.db"
    private val driver = DatabaseDriverFactory().create(databaseFile)
    private val database = MeshPigeonDatabase(driver)
    private val repository: RadioRepository = SqlDelightRadioRepository(database)

    @AfterTest
    fun tearDown() {
        driver.close()
        File(home).deleteRecursively()
    }

    private fun radio(name: String = "bench", persistentId: String = "10.0.0.5:5000") = RadioRecord(
        id = UUID.randomUUID().toString(),
        persistentId = persistentId,
        name = name,
        boardName = "SIM",
        firmwareVersion = "0.1.0",
        specVersion = 2,
        supportsWifi = true,
        supportsUsb = true,
        firstSeenAtMs = 1_700_000_000_000,
        lastSeenAtMs = 1_700_000_001_000,
        lastBatteryMv = 3980,
        lastNoiseFloorDbm = -98,
    )

    @Test
    fun the_schema_is_created_and_the_version_is_recorded() {
        assertTrue(File(databaseFile).isFile, "the database file was not created")
        // `PRAGMA user_version` is the single source of schema truth (plan §8.2).
        assertTrue(driver.schemaVersion() >= 1, "user_version should be at least 1")
    }

    @Test
    fun an_empty_database_has_no_radios() = runTest {
        assertEquals(emptyList(), repository.radios())
    }

    @Test
    fun a_radio_is_recorded_and_read_back_as_it_was_told() = runTest {
        val saved = repository.record(radio())
        val read = assertNotNull(repository.radio(saved.id))

        assertEquals("bench", read.name)
        assertEquals("SIM", read.boardName)
        assertEquals("10.0.0.5:5000", read.persistentId)
        assertEquals(2, read.specVersion)
        assertTrue(read.supportsWifi && read.supportsUsb)
        assertTrue(!read.supportsBle)
        assertEquals(3980, read.lastBatteryMv)
    }

    @Test
    fun a_radio_that_answers_twice_is_one_radio() = runTest {
        val first = repository.record(radio())
        // Same persistent id, new name and a newer sighting: a radio that renames
        // itself is still the radio, and a second row would be a ghost.
        val second = repository.record(
            radio(name = "workbench", persistentId = first.persistentId).copy(
                lastSeenAtMs = 1_700_000_002_000,
            ),
        )

        assertEquals(first.id, second.id)
        assertEquals(1, repository.radios().size)
        assertEquals("workbench", repository.radios().single().name)
    }

    @Test
    fun first_seen_survives_a_later_sighting() = runTest {
        val saved = repository.record(radio())
        repository.record(radio(name = "moved", persistentId = saved.persistentId))
        assertEquals(1_700_000_000_000, repository.radio(saved.id)?.firstSeenAtMs)
    }

    @Test
    fun links_survive_every_kind_of_target() = runTest {
        val device = repository.record(radio())
        val targets = listOf(
            LinkTarget(LinkKind.Tcp, host = "10.0.0.5", port = 5000),
            LinkTarget(LinkKind.Usb, devicePath = "/dev/ttyACM0", baudRate = 115_200),
            LinkTarget(LinkKind.Ble, endpointId = "AA:BB", name = "bench"),
        )
        for (target in targets) {
            repository.addLink(
                SavedLink(
                    id = UUID.randomUUID().toString(),
                    radioId = device.id,
                    target = target,
                ),
            )
        }

        // By kind, not by position: two links with the same preference order come
        // back in id order, and asserting on an index would be asserting on a
        // random UUID.
        val read = repository.links(device.id).associate { it.target.kind to it.target }
        // Typed columns, not one address string: a USB path comes back as a path
        // and a TCP endpoint as a host and port.
        assertEquals("10.0.0.5:5000", read.getValue(LinkKind.Tcp).label)
        assertEquals("/dev/ttyACM0", read.getValue(LinkKind.Usb).devicePath)
        assertEquals(115_200, read.getValue(LinkKind.Usb).baudRate)
        assertEquals("AA:BB", read.getValue(LinkKind.Ble).endpointId)
        assertEquals("tcp://10.0.0.5:5000", read.getValue(LinkKind.Tcp).canonical)
        assertEquals("usb:/dev/ttyACM0", read.getValue(LinkKind.Usb).canonical)
    }

    @Test
    fun one_preferred_link_per_radio() = runTest {
        val device = repository.record(radio())
        val first = repository.addLink(
            SavedLink(UUID.randomUUID().toString(), device.id, LinkTarget(LinkKind.Tcp, host = "a", port = 1), isPreferred = true),
        )
        repository.addLink(
            SavedLink(UUID.randomUUID().toString(), device.id, LinkTarget(LinkKind.Tcp, host = "b", port = 2), isPreferred = true),
        )

        val preferred = repository.links(device.id).filter { it.isPreferred }
        assertEquals(1, preferred.size)
        assertEquals("b", preferred.single().target.host)
        assertNotNull(first)
    }

    @Test
    fun a_link_records_whether_it_worked() = runTest {
        val device = repository.record(radio())
        val link = repository.addLink(
            SavedLink(UUID.randomUUID().toString(), device.id, LinkTarget(LinkKind.Tcp, host = "a", port = 1)),
        )

        repository.noteLinkOutcome(link.id, connected = true, atMs = 1_700_000_005_000)
        assertEquals(1_700_000_005_000, repository.links(device.id).single().lastConnectedAtMs)

        // A failure clears the timestamp: "when it last worked" and "when it was
        // last tried" are different questions and one column answers one of them.
        repository.noteLinkOutcome(link.id, connected = false, atMs = 1_700_000_009_000, error = "refused")
        val failed = repository.links(device.id).single()
        assertNull(failed.lastConnectedAtMs)
        assertEquals("refused", failed.lastError)
    }

    @Test
    fun forgetting_a_radio_takes_its_links_with_it() = runTest {
        val device = repository.record(radio())
        repository.addLink(
            SavedLink(UUID.randomUUID().toString(), device.id, LinkTarget(LinkKind.Tcp, host = "a", port = 1)),
        )

        repository.forgetRadio(device.id)

        assertEquals(emptyList(), repository.radios())
        // The cascade is a foreign key, and foreign keys are on for every
        // connection rather than only inside migrations.
        assertEquals(emptyList(), repository.links(device.id))
    }

    @Test
    fun removing_a_link_leaves_the_radio() = runTest {
        val device = repository.record(radio())
        val link = repository.addLink(
            SavedLink(UUID.randomUUID().toString(), device.id, LinkTarget(LinkKind.Tcp, host = "a", port = 1)),
        )

        repository.removeLink(link.id)

        assertEquals(emptyList(), repository.links(device.id))
        assertNotNull(repository.radio(device.id))
    }

    @Test
    fun the_database_survives_reopening() = runTest {
        val saved = repository.record(radio())
        driver.close()

        val reopened = DatabaseDriverFactory().create(databaseFile)
        try {
            val read = SqlDelightRadioRepository(MeshPigeonDatabase(reopened))
            assertEquals(saved.id, read.radios().single().id)
            // WAL is on: the file a second session opens is the same data, not a
            // stale snapshot.
            assertContentEquals(
                listOf("bench"),
                read.radios().map { it.name },
            )
        } finally {
            reopened.close()
        }
    }

    private fun createTempDirectory(): String =
        File(System.getProperty("java.io.tmpdir"), "mp-storage-${UUID.randomUUID()}").also {
            it.mkdirs()
        }.path

    private companion object {
        init {
            // One driver factory per test class is not enough: `assertFailsWith`
            // below needs its own file, and a shared one would make the lock test
            // see the repository test's lock.
        }
    }
}

/**
 * The one-process rule.
 *
 * Two sessions on one database would each believe they own the radio and the
 * outbox, so the second one is refused *by name* — and a lock left behind by a
 * crashed session is stale, not held.
 */
class DatabaseLockTest {
    private val home: String =
        File(System.getProperty("java.io.tmpdir"), "mp-lock-${UUID.randomUUID()}").also { it.mkdirs() }.path
    private val databasePath = "$home/meshpigeon.db"

    @AfterTest
    fun tearDown() {
        File(home).deleteRecursively()
    }

    @Test
    fun the_lock_is_taken_and_released() {
        val lock = acquireDatabaseLock(databasePath)
        assertEquals(ProcessHandle.current().pid().toInt(), heldProcessId(databasePath))
        lock.release()
        assertNull(heldProcessId(databasePath))
    }

    @Test
    fun releasing_twice_is_not_an_error() {
        val lock = acquireDatabaseLock(databasePath)
        lock.release()
        lock.release()
    }

    @Test
    fun a_lock_held_by_a_live_process_refuses_the_next_one() {
        val lock = acquireDatabaseLock(databasePath)
        try {
            // The refusal names the holder: "the database is locked" is the least
            // useful sentence a person can be handed at startup.
            val failure = assertFailsWith<IllegalStateException> { acquireDatabaseLock(databasePath) }
            assertTrue(failure.message.orEmpty().contains("pid ${ProcessHandle.current().pid()}"))
        } finally {
            lock.release()
        }
    }

    @Test
    fun a_lock_left_by_a_dead_process_is_not_a_lock() {
        // A PID that cannot be running: the state a crashed session leaves behind.
        val lockFile = File("$databasePath.lock")
        lockFile.parentFile?.mkdirs()
        lockFile.writeText("meshpigeon session lock, pid ${impossiblePid()}")

        assertTrue(lockIsFree(lockFilePid(lockFile)))
        val lock = acquireDatabaseLock(databasePath)
        assertEquals(ProcessHandle.current().pid().toInt(), heldProcessId(databasePath))
        lock.release()
    }

    private fun lockFilePid(file: File): Int? =
        file.readText().substringAfter("pid ", "").trim().toIntOrNull()

    /** Above `pid_max`, and therefore certainly not running. */
    private fun impossiblePid(): Int = 0x7FFF_FFFE
}