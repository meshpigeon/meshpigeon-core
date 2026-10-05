package dev.meshpigeon.core.testing

import dev.meshpigeon.core.transport.AuthResult
import dev.meshpigeon.core.transport.Capability
import dev.meshpigeon.core.transport.Cursor
import dev.meshpigeon.core.transport.DeviceInfo
import dev.meshpigeon.core.transport.DeviceSettingsPatch
import dev.meshpigeon.core.transport.LinkPhase
import dev.meshpigeon.core.transport.PacketEvent
import dev.meshpigeon.core.transport.RadioError
import dev.meshpigeon.core.transport.RadioErrorCode
import dev.meshpigeon.core.transport.RadioException
import dev.meshpigeon.core.transport.RadioSession
import dev.meshpigeon.core.transport.SessionConfig
import dev.meshpigeon.core.transport.Secret
import dev.meshpigeon.core.transport.Tuning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session against a radio that answers like the firmware.
 *
 * These run on real time rather than `runTest`'s virtual clock: the session's
 * own request deadlines are real deadlines, and a virtual clock would fire them
 * before the reader thread has been scheduled.
 *
 * Every one of these pushes real frames through the real codec in both
 * directions, so they are as much a test of the framing and the protobuf mapping
 * as of the session itself.
 */
class RadioSessionTest {
    private val clock = FakeClock()
    private val radio = FakeRadio(clock = clock)
    private val scope = CoroutineScope(SupervisorJob())
    private val session = RadioSession(
        link = radio.link,
        config = SessionConfig(requestTimeoutMs = 1_000, connectTimeoutMs = 1_000, reanchorIntervalMs = 600_000),
        clock = clock,
        log = RecordingLogger(),
        parentScope = scope,
    )

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun connect(secret: String? = null): DeviceInfo = session.connect(secret?.let { Secret(it) })

    @Test
    fun connecting_answers_with_the_device_information() = runBlocking {
        val info = connect()

        assertEquals("FAKE", info.boardName)
        assertEquals(DeviceInfo.SUPPORTED_SPEC_VERSIONS, setOf(info.specVersion))
        assertEquals(setOf(Capability.WIFI), info.capabilities)
        assertTrue(info.radioOk)
        assertTrue(!info.authRequired)
        assertEquals(LinkPhase.Ready, session.phase.value)
    }

    @Test
    fun a_radio_speaking_another_specification_is_refused() = runBlocking {
        radio.specVersion = 99

        val failure = assertFailsWith<RadioException> { connect() }

        assertEquals(RadioError.UnsupportedSpec(99), failure.error)
        assertTrue(session.phase.value is LinkPhase.Failed)
    }

    @Test
    fun a_radio_with_a_pin_needs_the_pin() = runBlocking {
        val pinned = FakeRadio(clock = clock, pin = "4242")
        val guarded = RadioSession(pinned.link, SessionConfig(requestTimeoutMs = 1_000), clock, parentScope = scope)

        // No secret at all.
        assertFailsWith<RadioException> { guarded.connect() }
        // The wrong secret.
        val refused = assertFailsWith<RadioException> { guarded.connect(Secret("0000")) }
        assertTrue(refused.error is RadioError.AuthRequired, "wrong PIN gave ${refused.error}")

        val info = guarded.connect(Secret("4242"))
        assertTrue(!info.authRequired)
        assertEquals(LinkPhase.Ready, guarded.phase.value)
    }

    @Test
    fun authenticating_reports_whether_a_pin_was_needed() = runBlocking {
        connect()

        assertEquals(AuthResult.NotRequired, session.authenticate(Secret("0000")))
    }

    @Test
    fun a_refused_pin_is_a_rejection_not_a_crash() = runBlocking {
        val pinned = FakeRadio(clock = clock, pin = "4242")
        val guarded = RadioSession(pinned.link, SessionConfig(requestTimeoutMs = 1_000), clock, parentScope = scope)
        guarded.connect(Secret("4242"))

        val result = guarded.authenticate(Secret("0000"))

        assertTrue(result is AuthResult.Rejected, "expected a rejection, got $result")
    }

    @Test
    fun reading_tuning_returns_what_the_radio_reports() = runBlocking {
        connect()

        val tuning = session.readTuning()

        assertEquals(868_500_000, tuning.frequencyHz)
        assertEquals(125_000, tuning.bandwidthHz)
        assertEquals(8, tuning.spreadingFactor)
    }

    @Test
    fun writing_tuning_returns_the_post_write_read_model() = runBlocking {
        connect()

        val applied = session.writeTuning(
            Tuning(
                frequencyHz = 915_000_000,
                bandwidthHz = 250_000,
                spreadingFactor = 10,
                codingRate = 7,
                powerDbm = 17,
                configEpoch = 0,
            ),
        )

        assertEquals(915_000_000, applied.frequencyHz)
        assertEquals(10, applied.spreadingFactor)
        assertTrue(applied.configEpoch > 1, "the firmware bumps the epoch on every accepted change")
        assertEquals(applied, session.tuning.value)
    }

    @Test
    fun another_clients_retune_arrives_as_a_push() = runBlocking {
        connect()
        val events = events()

        radio.announceRetune()

        val retuned = events.await { it is PacketEvent.RadioRetuned } as PacketEvent.RadioRetuned
        assertEquals(868_500_000, retuned.tuning.frequencyHz)
    }

    @Test
    fun sending_returns_a_ticket_and_the_outcome_arrives_afterwards() = runBlocking {
        connect()
        val events = events()

        val ticket = session.sendRaw(byteArrayOf(1, 2, 3))

        val outcome = ticket.await()
        assertTrue(outcome.success)
        assertEquals(ticket.seq, outcome.seq)
        val pushed = events.await { it is PacketEvent.TxCompleted } as PacketEvent.TxCompleted
        assertEquals(outcome, pushed.outcome)
    }

    @Test
    fun a_packet_larger_than_the_air_allows_is_refused_before_the_radio_sees_it() = runBlocking {
        connect()
        val before = radio.requests.size

        val failure = assertFailsWith<RadioException> { session.sendRaw(ByteArray(256)) }

        assertEquals(RadioError.PacketTooLarge(256), failure.error)
        assertEquals(before, radio.requests.size, "an oversized packet must not reach the radio")
    }

    @Test
    fun history_comes_back_oldest_first_with_a_resume_point() = runBlocking {
        connect()
        radio.receive("one".encodeToByteArray())
        radio.receive("two".encodeToByteArray())
        radio.receive("three".encodeToByteArray())

        val page = session.fetchHistory(Cursor.Beginning, limit = 2)

        assertEquals(listOf("one", "two"), page.packets.map { it.raw.decodeToString() })
        assertEquals(2, page.delivered)
        assertEquals(page.packets.last().seq, page.resumeAt?.seq)

        val rest = session.fetchHistory(page.resumeAt!!, limit = 0)
        assertEquals(listOf("three"), rest.packets.map { it.raw.decodeToString() })
    }

    @Test
    fun a_reception_arrives_as_an_event_with_its_timing_mapped_to_wall_clock() = runBlocking {
        radio.advanceUptime(60_000)
        connect()
        val events = events()
        radio.advanceUptime(1_500)

        radio.receive("on the air".encodeToByteArray(), rssi = -95, snr = 42)

        val received = events.await { it is PacketEvent.PacketReceived } as PacketEvent.PacketReceived
        assertContentEquals("on the air".encodeToByteArray(), received.packet.raw)
        assertEquals(-95, received.packet.rssiDbm)
        assertEquals(42.0, received.packet.snr)
        // The anchor was taken when the radio reported 60 000 ms of uptime, so a
        // packet stamped 1 500 ms later is 1 500 ms later in wall-clock terms.
        assertEquals(clock.nowMs() + 1_500, received.packet.observedAtMs)
    }

    @Test
    fun log_noise_on_the_wire_drops_one_frame_and_keeps_the_session_alive() = runBlocking {
        connect()
        val events = events()

        // The ESP-IDF Wi-Fi driver writes its own log text to the same port.
        radio.injectGarbage("I (1234) wifi:mode : sta (aa:bb:cc:dd:ee:ff)")

        assertEquals(868_500_000, session.readTuning().frequencyHz)
        events.await { it is PacketEvent.FramesDropped }
        Unit
    }

    @Test
    fun a_lost_answer_times_out_rather_than_hanging() = runBlocking {
        connect()

        radio.dropNextResponses()
        val failure = assertFailsWith<RadioException> { session.readTuning() }

        assertTrue(failure.error is RadioError.Timeout, "expected a timeout, got ${failure.error}")
    }

    @Test
    fun an_error_code_the_radio_invents_comes_back_typed() = runBlocking {
        connect()
        radio.scriptError(dev.meshpigeon.proto.Error.ErrorCode.ERROR_CODE_BUSY, "transmitting")

        val failure = assertFailsWith<RadioException> { session.purgeHistory() }

        val remote = failure.error as? RadioError.Remote
        assertNotNull(remote)
        assertEquals(RadioErrorCode.BUSY, remote.code)
        assertEquals("transmitting", remote.hint)
    }

    @Test
    fun device_settings_round_trip() = runBlocking {
        connect()
        val before = session.deviceSettings()
        assertEquals("FakeRadio", before.name)

        session.writeDeviceSettings(DeviceSettingsPatch(name = "Bench"))

        assertEquals("Bench", session.deviceSettings().name)
        assertEquals("FakeRadio", before.name, "a read model is a value, not a view onto the device")
    }

    @Test
    fun purging_clears_the_store_and_refreshes_what_the_radio_reports() = runBlocking {
        connect()
        radio.receive(byteArrayOf(7))
        assertEquals(1, radio.storedCount)

        session.purgeHistory()

        assertEquals(0, radio.storedCount)
        assertEquals(0, session.deviceInfo().store.count)
    }

    @Test
    fun a_dropped_link_is_reopened_without_being_asked() = runBlocking {
        connect()
        assertEquals(LinkPhase.Ready, session.phase.value)

        radio.dropLink()

        // First wait for the drop to be noticed, so the loop below cannot pass
        // on the Ready phase left over from before the drop.
        withTimeout(10_000) { while (session.phase.value is LinkPhase.Ready) delay(20) }
        withTimeout(10_000) { while (session.phase.value !is LinkPhase.Ready) delay(20) }
        assertEquals(868_500_000, session.readTuning().frequencyHz)
    }

    @Test
    fun disconnecting_ends_the_session_cleanly() = runBlocking {
        connect()

        session.disconnect()

        assertEquals(LinkPhase.Disconnected, session.phase.value)
        assertNull(session.device.value)
        assertFailsWith<RadioException> { session.readTuning() }
        Unit
    }

    /** Collects the session's event stream for the duration of one test. */
    private fun events(): Channel<PacketEvent> {
        val channel = Channel<PacketEvent>(Channel.UNLIMITED)
        scope.launch { session.incoming().collect { channel.send(it) } }
        return channel
    }

    /** Waits for the first event matching [predicate], or fails the test. */
    private suspend fun Channel<PacketEvent>.await(predicate: (PacketEvent) -> Boolean): PacketEvent =
        withTimeout(10_000) {
            var event = receive()
            while (!predicate(event)) event = receive()
            event
        }
}