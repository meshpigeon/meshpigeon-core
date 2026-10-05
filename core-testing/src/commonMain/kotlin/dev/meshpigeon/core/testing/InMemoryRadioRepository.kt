package dev.meshpigeon.core.testing

import dev.meshpigeon.domain.RadioRecord
import dev.meshpigeon.domain.RadioRepository
import dev.meshpigeon.domain.SavedLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [RadioRepository] in a list, for tests.
 *
 * The interface exists so the CLI's link book can be tested without a database
 * file — and this is the other end of that: the same behaviour SQLDelight
 * promises, with the one preferred link per radio enforced in code rather than
 * by a partial unique index, so a test that passes here is a test of the book's
 * logic and not of SQLite.
 *
 * It is published rather than test-only because the Android app will want the
 * same thing, and a second copy of "how do radios and links relate" is a second
 * copy to keep right.
 */
public class InMemoryRadioRepository : RadioRepository {
    private val mutex = Mutex()
    private val devices = linkedMapOf<String, RadioRecord>()
    private val byPersistentId = mutableMapOf<String, String>()
    private val linkRows = mutableListOf<SavedLink>()

    override suspend fun radios(): List<RadioRecord> = mutex.withLock {
        devices.values.sortedWith(compareByDescending<RadioRecord> { it.lastSeenAtMs ?: it.firstSeenAtMs }.thenBy { it.id })
    }

    override suspend fun record(radio: RadioRecord): RadioRecord = mutex.withLock {
        val existingId = byPersistentId[radio.persistentId]
        if (existingId == null) {
            devices[radio.id] = radio.copy(lastSeenAtMs = radio.lastSeenAtMs ?: radio.firstSeenAtMs)
            byPersistentId[radio.persistentId] = radio.id
        } else {
            // `firstSeenAtMs` and `persistentId` are set once, exactly as the
            // SQL implementation does: rewriting them is how a radio that
            // renamed itself loses the identity it was recognised by.
            val existing = devices.getValue(existingId)
            devices[existingId] = radio.copy(
                id = existingId,
                persistentId = existing.persistentId,
                firstSeenAtMs = existing.firstSeenAtMs,
                lastSeenAtMs = radio.lastSeenAtMs ?: existing.lastSeenAtMs,
            )
        }
        devices.getValue(existingId ?: radio.id)
    }

    override suspend fun radio(id: String): RadioRecord? = mutex.withLock { devices[id] }

    override suspend fun links(radioId: String): List<SavedLink> = mutex.withLock {
        linkRows.filter { it.radioId == radioId }.sortedBy { it.preferenceOrder }
    }

    override suspend fun addLink(link: SavedLink): SavedLink = mutex.withLock {
        if (link.isPreferred) {
            // One preferred link per radio — the rule the partial unique index
            // enforces in the database, kept here so a test is not testing SQLite.
            for (index in linkRows.indices) {
                val row = linkRows[index]
                if (row.radioId == link.radioId) linkRows[index] = row.copy(isPreferred = false)
            }
        }
        linkRows.removeAll { it.id == link.id }
        linkRows += link
        link
    }

    override suspend fun removeLink(id: String) {
        mutex.withLock { linkRows.removeAll { it.id == id } }
    }

    override suspend fun noteLinkOutcome(id: String, connected: Boolean, atMs: Long, error: String?) {
        mutex.withLock {
            val index = linkRows.indexOfFirst { it.id == id }
            if (index >= 0) {
                linkRows[index] = linkRows[index].copy(
                    // A failure clears the timestamp: "when it last worked" and
                    // "when it last was tried" are different questions.
                    lastConnectedAtMs = if (connected) atMs else null,
                    lastError = error,
                )
            }
        }
    }

    override suspend fun forgetRadio(id: String) {
        mutex.withLock {
            devices.remove(id)?.let { byPersistentId.remove(it.persistentId) }
            linkRows.removeAll { it.radioId == id }
        }
    }
}
