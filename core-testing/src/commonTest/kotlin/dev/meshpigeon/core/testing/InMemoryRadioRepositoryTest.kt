package dev.meshpigeon.core.testing

import dev.meshpigeon.domain.LinkKind
import dev.meshpigeon.domain.LinkTarget
import dev.meshpigeon.domain.RadioRecord
import dev.meshpigeon.domain.SavedLink
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fake has to keep the same promises as the SQL one, or every test that
 * uses it is testing a fiction: idempotence in `persistentId`, one preferred
 * link per radio, and a cascade when a radio is forgotten.
 */
class InMemoryRadioRepositoryTest {

    private fun radio(id: String, persistentId: String, seenAt: Long) = RadioRecord(
        id = id,
        persistentId = persistentId,
        name = id,
        boardName = "SIM",
        firmwareVersion = "0.1.0",
        specVersion = 1,
        firstSeenAtMs = seenAt,
        lastSeenAtMs = seenAt,
    )

    private fun link(id: String, radioId: String, target: LinkTarget, preferred: Boolean = false) = SavedLink(
        id = id,
        radioId = radioId,
        target = target,
        isPreferred = preferred,
    )

    @Test
    fun a_radio_recorded_twice_is_one_radio() = runTest {
        val repository = InMemoryRadioRepository()
        repository.record(radio("radio-1", "tcp://10.0.0.5:5000", seenAt = 1_000))
        // A different id, the same radio: the persistent id is what recognises it.
        val again = repository.record(
            radio("radio-2", "tcp://10.0.0.5:5000", seenAt = 2_000).copy(name = "desk"),
        )

        assertEquals(listOf("radio-1"), repository.radios().map { it.id })
        assertEquals("desk", again.name)
        assertEquals(2_000, again.lastSeenAtMs)
    }

    @Test
    fun first_seen_is_never_moved_by_a_later_sighting() = runTest {
        val repository = InMemoryRadioRepository()
        repository.record(radio("radio-1", "tcp://10.0.0.5:5000", seenAt = 1_000))
        val again = repository.record(radio("radio-1", "tcp://10.0.0.5:5000", seenAt = 9_000))

        assertEquals(1_000, again.firstSeenAtMs)
    }

    @Test
    fun only_one_link_per_radio_is_preferred() = runTest {
        val repository = InMemoryRadioRepository()
        val device = repository.record(radio("radio-1", "tcp://10.0.0.5:5000", seenAt = 1))
        repository.addLink(link("link-1", device.id, LinkTarget(LinkKind.Tcp, host = "10.0.0.5", port = 5000), preferred = true))
        repository.addLink(
            link(
                "link-2",
                device.id,
                LinkTarget(LinkKind.Usb, devicePath = "/dev/ttyACM0"),
                preferred = true,
            ),
        )

        val links = repository.links(device.id)
        assertEquals(listOf(false, true), links.sortedBy { it.id }.map { it.isPreferred })
    }

    @Test
    fun a_failure_clears_the_last_connection_but_keeps_the_reason() = runTest {
        val repository = InMemoryRadioRepository()
        val device = repository.record(radio("radio-1", "usb:/dev/ttyACM0", seenAt = 1))
        repository.addLink(link("link-1", device.id, LinkTarget(LinkKind.Usb, devicePath = "/dev/ttyACM0")))
        repository.noteLinkOutcome("link-1", connected = true, atMs = 500)
        repository.noteLinkOutcome("link-1", connected = false, atMs = 900, error = "no such device")

        val link = repository.links(device.id).single()
        assertNull(link.lastConnectedAtMs)
        assertEquals("no such device", link.lastError)
    }

    @Test
    fun forgetting_a_radio_takes_its_links_with_it() = runTest {
        val repository = InMemoryRadioRepository()
        val device = repository.record(radio("radio-1", "tcp://10.0.0.5:5000", seenAt = 1))
        repository.addLink(link("link-1", device.id, LinkTarget(LinkKind.Tcp, host = "10.0.0.5", port = 5000)))
        repository.addLink(link("link-2", device.id, LinkTarget(LinkKind.Usb, devicePath = "/dev/ttyACM0")))

        repository.forgetRadio(device.id)

        assertTrue(repository.links(device.id).isEmpty())
        assertTrue(repository.radios().isEmpty())
        // And the persistent id is free again, so the same radio reconnects as new.
        assertNull(repository.radio(device.id))
    }
}
