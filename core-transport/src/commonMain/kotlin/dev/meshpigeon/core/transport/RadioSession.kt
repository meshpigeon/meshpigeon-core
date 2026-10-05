package dev.meshpigeon.core.transport

import dev.meshpigeon.core.common.Clock
import dev.meshpigeon.core.common.Logger
import dev.meshpigeon.core.common.framing.FrameEvent
import dev.meshpigeon.core.common.framing.FrameReader
import dev.meshpigeon.core.common.framing.Framing
import dev.meshpigeon.proto.Auth
import dev.meshpigeon.proto.Bootloader
import dev.meshpigeon.proto.ClientToRadio
import dev.meshpigeon.proto.FactoryReset
import dev.meshpigeon.proto.FetchPackets
import dev.meshpigeon.proto.GetDeviceInfo
import dev.meshpigeon.proto.GetDeviceSettings
import dev.meshpigeon.proto.GetRadioSettings
import dev.meshpigeon.proto.GetStatus
import dev.meshpigeon.proto.PacketEntry as ProtoPacketEntry
import dev.meshpigeon.proto.PurgeStore
import dev.meshpigeon.proto.RadioToClient
import dev.meshpigeon.proto.Reboot
import dev.meshpigeon.proto.SendPacket
import dev.meshpigeon.proto.SetDeviceSettings
import dev.meshpigeon.proto.SetRadioSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import kotlin.coroutines.ContinuationInterceptor

/** Where a connection is in its lifecycle, as one value a client can render. */
public sealed interface LinkPhase {
    public data object Disconnected : LinkPhase
    public data object Connecting : LinkPhase

    /** The link is open; the handshake is running. */
    public data object Handshaking : LinkPhase

    /** Authenticated (or not needed) and usable. */
    public data object Ready : LinkPhase

    /** The link dropped and a retry is scheduled. */
    public data class Reconnecting(val attempt: Int, val delayMs: Long, val reason: String) : LinkPhase

    /** The link could not be opened, or the radio is unusable. */
    public data class Failed(val error: RadioError) : LinkPhase
}

/** Tunables for one session. Defaults are the ones the bench uses. */
public data class SessionConfig(
    /** How long a request waits for its response. */
    val requestTimeoutMs: Long = 5_000,
    /** How long opening the link plus the handshake may take. */
    val connectTimeoutMs: Long = 10_000,
    /** How long a send may stay outstanding before its ticket fails. */
    val txTimeoutMs: Long = 30_000,
    /** Whether a dropped link is re-opened automatically. */
    val autoReconnect: Boolean = true,
    val initialReconnectDelayMs: Long = 500,
    val maxReconnectDelayMs: Long = 15_000,
    /** How often the uptime anchor is refreshed while connected. */
    val reanchorIntervalMs: Long = 60_000,
)

/**
 * The one implementation of [RadioApi]: meshpigeon-firmware's protobuf contract
 * over a [RadioLink].
 *
 * It owns the parts of a radio that are protocol rather than plumbing:
 * correlation ids, async pushes, the specification-version gate, uptime
 * anchoring, reconnect backoff, and turning generated types into domain types.
 * It knows nothing about LoRa, boards or meshes.
 */
public class RadioSession(
    private val link: RadioLink,
    private val config: SessionConfig = SessionConfig(),
    private val clock: Clock = Clock.System,
    private val log: Logger = Logger.None,
    parentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : RadioApi {
    private val sessionJob: Job = SupervisorJob(parentScope.coroutineContext[Job])
    private val baseDispatcher: CoroutineDispatcher =
        (parentScope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default

    /**
     * All session state lives on one dispatcher, so the pending-request table and
     * the frame reader need no locks: only one coroutine touches them at a time,
     * whichever coroutine a caller happens to be on.
     */
    private val state: CoroutineDispatcher = baseDispatcher.limitedParallelism(1)
    private val scope: CoroutineScope =
        CoroutineScope(parentScope.coroutineContext + state + sessionJob + CoroutineName("meshpigeon-radio"))

    private val _phase = MutableStateFlow<LinkPhase>(LinkPhase.Disconnected)

    /** The connection lifecycle, as one value. */
    public val phase: StateFlow<LinkPhase> = _phase.asStateFlow()

    private val _device = MutableStateFlow<DeviceInfo?>(null)

    /** The last `DeviceInfo` seen, or `null` when disconnected. */
    public val device: StateFlow<DeviceInfo?> = _device.asStateFlow()

    private val _tuning = MutableStateFlow<Tuning?>(null)

    /** The last `RadioSettings` seen, from our own write or a re-tune push. */
    public val tuning: StateFlow<Tuning?> = _tuning.asStateFlow()

    private val _events = MutableSharedFlow<PacketEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val reader = FrameReader()
    private val anchor = UptimeAnchor()
    private val lifecycle = Mutex()
    private val pending = LinkedHashMap<Int, PendingOperation>()
    private val tickets = LinkedHashMap<Int, CompletableDeferred<TxOutcome>>()

    /**
     * Outcomes that arrived before their ticket existed.
     *
     * The firmware answers a send with the accepted ack and pushes the outcome
     * later, but nothing in the protocol promises the gap is long: a fast
     * radio can push the outcome before the client's own coroutine has got
     * round to registering the ticket. Holding the outcome for a moment is
     * cheaper than losing it and timing out on a send that succeeded.
     */
    private val earlyOutcomes = LinkedHashMap<Int, TxOutcome>()
    private var nextRequestId = 1
    private var connectSecret: Secret? = null
    private var pinRequired = false
    private var wantConnection = false
    private var readJob: Job? = null
    private var reanchorJob: Job? = null
    private var reconnectJob: Job? = null

    private sealed interface PendingOperation {
        /** A request that gets exactly one response. */
        class Reply(val response: CompletableDeferred<RadioToClient>) : PendingOperation

        /** A `FetchPackets` stream: many entries, then a terminator. */
        class Stream(
            val onPacket: (RadioToClient) -> Unit,
            val end: CompletableDeferred<RadioToClient>,
        ) : PendingOperation
    }

    /**
     * Opens the link and brings the radio up: identity, the specification-version
     * gate, the uptime anchor, and authentication when the device asks for it.
     *
     * @throws RadioException when the link cannot be opened, the radio speaks a
     *   specification this client does not implement, or the PIN is refused.
     */
    public suspend fun connect(secret: Secret? = null): DeviceInfo = lifecycle.withLock {
        val alreadyConnected = _device.value
        if (alreadyConnected != null && _phase.value is LinkPhase.Ready) return@withLock alreadyConnected
        wantConnection = true
        connectSecret = secret
        _phase.value = LinkPhase.Connecting
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            openAndHandshake()
        } catch (e: RadioException) {
            wantConnection = false
            _phase.value = LinkPhase.Failed(e.error)
            throw e
        } catch (e: Throwable) {
            wantConnection = false
            val error = RadioError.LinkUnavailable(e.message ?: "connect failed", e)
            _phase.value = LinkPhase.Failed(error)
            throw RadioException(error)
        }
    }

    /** Closes the link and stops reconnecting. */
    public suspend fun disconnect(): Unit = lifecycle.withLock {
        wantConnection = false
        reconnectJob?.cancel()
        reconnectJob = null
        readJob?.cancel()
        readJob = null
        reanchorJob?.cancel()
        reanchorJob = null
        onState {
            failEverything(RadioError.NotConnected("disconnected"))
            reader.reset()
        }
        runCatching { link.close() }
        _device.value = null
        _tuning.value = null
        _phase.value = LinkPhase.Disconnected
    }

    /** Releases the session's own coroutines. The client owns `parentScope`. */
    public fun close() {
        sessionJob.cancel()
    }

    override fun incoming(): Flow<PacketEvent> = _events

    override suspend fun deviceInfo(): DeviceInfo {
        val message = call { id -> ClientToRadio(id = id, get_device_info = GetDeviceInfo()) }
        val info = message.device_info?.toDomain() ?: fail("device info response carried no body")
        onState { recordDeviceInfo(info) }
        return info
    }

    override suspend fun status(): LinkStatus {
        val message = call { id -> ClientToRadio(id = id, get_status = GetStatus()) }
        return message.status?.toDomain() ?: fail("status response carried no body")
    }

    override suspend fun authenticate(secret: Secret): AuthResult {
        // `DeviceInfo.auth_required` also reads false once this connection has
        // authenticated, so the "no PIN is set at all" answer is remembered from
        // the handshake rather than inferred from the current reading.
        if (!pinRequired) return AuthResult.NotRequired
        return try {
            call { id -> ClientToRadio(id = id, auth = Auth(pin = secret.reveal())) }
            onState { _device.value = _device.value?.copy(authRequired = false) }
            AuthResult.Authorized
        } catch (e: RadioException) {
            when (val error = e.error) {
                is RadioError.Remote -> AuthResult.Rejected(error)
                else -> throw e
            }
        }
    }

    override suspend fun readTuning(): Tuning {
        val message = call { id -> ClientToRadio(id = id, get_radio_settings = GetRadioSettings()) }
        val tuning = message.radio_settings?.toDomain() ?: fail("tuning response carried no body")
        onState { _tuning.value = tuning }
        return tuning
    }

    override suspend fun writeTuning(tuning: Tuning): Tuning {
        val message = call { id ->
            ClientToRadio(id = id, set_radio_settings = SetRadioSettings(settings = tuning.toProto()))
        }
        val applied = message.radio_settings?.toDomain() ?: fail("retune response carried no body")
        onState {
            _tuning.value = applied
            _device.value = _device.value?.copy(radioConfigEpoch = applied.configEpoch)
        }
        return applied
    }

    override suspend fun sendRaw(raw: ByteArray): TxTicket {
        if (raw.size > MAX_RAW_PACKET_BYTES) throw RadioException(RadioError.PacketTooLarge(raw.size))
        // A board whose radio failed to come up answers NO_RADIO; failing here
        // says so in one line instead of waiting for that answer.
        if (_device.value?.radioOk == false) throw RadioException(RadioError.NoRadio())

        val message = call { id ->
            ClientToRadio(id = id, send_packet = SendPacket(raw = raw.toByteString()))
        }
        val accepted = message.packet_accepted ?: fail("send response carried no body")
        val outcome = CompletableDeferred<TxOutcome>()
        onState {
            val early = earlyOutcomes.remove(accepted.seq)
            if (early != null) {
                outcome.complete(early)
            }
            tickets[accepted.seq] = outcome
            scope.launch {
                delay(config.txTimeoutMs)
                tickets.remove(accepted.seq)?.completeExceptionally(
                    RadioException(RadioError.Timeout("send", config.txTimeoutMs)),
                )
            }
        }
        return TxTicket(accepted.seq, outcome)
    }

    override suspend fun fetchHistory(since: Cursor, limit: Int): HistoryPage {
        val packets = mutableListOf<StoredPacket>()
        val end = CompletableDeferred<RadioToClient>()
        val id = onState { register(PendingOperation.Stream({ packets += it.packet_entry!!.asPacket() }, end)) }
        try {
            write(
                ClientToRadio(
                    id = id,
                    fetch_packets = FetchPackets(
                        since_seq = since.seq,
                        // 0 means "no bound" on the wire, not "none at all".
                        max_count = if (limit <= 0) 0 else limit,
                    ),
                ),
            )
            val terminator = withTimeoutOrNull(config.requestTimeoutMs) { end.await() }
                ?: fail(RadioError.Timeout("fetch", config.requestTimeoutMs))
            val lastSeq = packets.lastOrNull()?.seq ?: since.seq
            return HistoryPage(
                packets = packets.toList(),
                delivered = terminator.fetch_end?.count ?: packets.size,
                resumeAt = Cursor(lastSeq).takeIf { packets.isNotEmpty() },
            )
        } finally {
            onState { pending.remove(id) }
        }
    }

    override suspend fun purgeHistory() {
        call { id -> ClientToRadio(id = id, purge_store = PurgeStore()) }
        // The store statistics changed, so the device info we show is stale.
        deviceInfo()
    }

    override suspend fun deviceSettings(): DeviceSettings {
        val message = call { id -> ClientToRadio(id = id, get_device_settings = GetDeviceSettings()) }
        return message.device_settings?.toDomain() ?: fail("device settings response carried no body")
    }

    override suspend fun writeDeviceSettings(patch: DeviceSettingsPatch) {
        require(!patch.isEmpty) { "an empty device settings patch changes nothing" }
        call { id ->
            ClientToRadio(
                id = id,
                set_device_settings = SetDeviceSettings(
                    name = patch.name,
                    pin = patch.pin,
                    wifi_enabled = patch.wifiEnabled,
                    wifi_ssid = patch.wifiSsid,
                    wifi_password = patch.wifiPassword,
                    wifi_port = patch.wifiPort,
                ),
            )
        }
    }

    override suspend fun reboot() {
        call { id -> ClientToRadio(id = id, reboot = Reboot()) }
    }

    override suspend fun factoryReset() {
        call { id -> ClientToRadio(id = id, factory_reset = FactoryReset()) }
    }

    /** Enters the bootloader/DFU mode. Requires authentication. */
    public suspend fun bootloader() {
        call { id -> ClientToRadio(id = id, bootloader = Bootloader()) }
    }

    // ── connection lifecycle ────────────────────────────────────────────────

    private suspend fun openAndHandshake(): DeviceInfo {
        _phase.value = LinkPhase.Connecting
        try {
            link.open()
        } catch (e: Throwable) {
            throw RadioException(RadioError.LinkUnavailable(e.message ?: "open failed", e))
        }
        _phase.value = LinkPhase.Handshaking
        onState { reader.reset() }
        readJob?.cancel()
        readJob = scope.launch { readLoop() }

        val info = try {
            withTimeoutOrNull(config.connectTimeoutMs) { deviceInfo() }
                ?: throw RadioException(RadioError.Timeout("connect", config.connectTimeoutMs))
        } catch (e: Throwable) {
            tearDownReadPath()
            throw e
        }

        // Whole-spec breaks bump this and nothing else does, so a radio outside
        // the set is refused rather than half-understood.
        if (info.specVersion !in DeviceInfo.SUPPORTED_SPEC_VERSIONS) {
            tearDownReadPath()
            val error = RadioError.UnsupportedSpec(info.specVersion)
            _phase.value = LinkPhase.Failed(error)
            throw RadioException(error)
        }

        onState { pinRequired = info.authRequired }
        if (info.authRequired) {
            val secret = connectSecret
                ?: throw RadioException(RadioError.AuthRequired("no PIN given for this radio"))
            when (val result = authenticate(secret)) {
                AuthResult.Authorized, AuthResult.NotRequired -> Unit
                // The radio answers a wrong PIN with AUTH_REQUIRED, which is
                // indistinguishable from never having authenticated.
                is AuthResult.Rejected -> throw RadioException(
                    if ((result.error as? RadioError.Remote)?.code == RadioErrorCode.AUTH_REQUIRED) {
                        RadioError.AuthRequired("the device refused this PIN")
                    } else {
                        result.error
                    },
                )
            }
        }

        _phase.value = LinkPhase.Ready
        startReanchoring()
        // The tuning is read once here so a client has something to show the
        // moment it connects; it is the first question a person asks a radio,
        // and `GetRadioSettings` is a single request.
        runCatching { readTuning() }.onFailure { log.debug("could not read the tuning: ${it.message}") }
        // Authentication happened after the snapshot above, so the caller is
        // handed one that reflects it (`auth_required` is false afterwards).
        return if (info.authRequired) deviceInfo() else info
    }

    private suspend fun tearDownReadPath() {
        readJob?.cancel()
        readJob = null
        reanchorJob?.cancel()
        reanchorJob = null
        onState { failEverything(RadioError.NotConnected("handshake failed")) }
        runCatching { link.close() }
    }

    private fun startReanchoring() {
        reanchorJob?.cancel()
        reanchorJob = scope.launch {
            while (isActive) {
                delay(config.reanchorIntervalMs)
                // Re-reading is cheap, and it is what notices a reboot: uptime
                // going backwards rebases every timestamp derived from it.
                try {
                    deviceInfo()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.debug("re-anchor poll failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun readLoop() {
        try {
            link.inbound().collect { chunk ->
                for (event in reader.feed(chunk)) {
                    when (event) {
                        is FrameEvent.Decoded -> onState { dispatch(event.frame.envelope) }
                        is FrameEvent.Dropped -> _events.emit(PacketEvent.FramesDropped(1))
                    }
                }
            }
            handleLinkLost("closed by the link")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            handleLinkLost(e.message ?: e::class.simpleName.orEmpty(), e)
        }
    }

    private fun handleLinkLost(reason: String, cause: Throwable? = null) {
        log.warn("link lost: $reason", cause)
        val error = RadioError.LinkLost(reason, cause)
        scope.launch {
            onState {
                reader.reset()
                failEverything(error)
                _device.value = null
            }
            reanchorJob?.cancel()
            reanchorJob = null
            if (!wantConnection) {
                _phase.value = LinkPhase.Disconnected
                return@launch
            }
            if (!config.autoReconnect) {
                _phase.value = LinkPhase.Failed(error)
                return@launch
            }
            var attempt = 0
            var wait = config.initialReconnectDelayMs
            while (wantConnection && isActive) {
                attempt++
                // A little jitter so a mesh of clients does not retry in lockstep.
                val jitter = clock.monotonicMs() % 250
                _phase.value = LinkPhase.Reconnecting(attempt, wait + jitter, reason)
                delay(wait + jitter)
                if (!wantConnection) break
                val recovered = try {
                    lifecycle.withLock { openAndHandshake() }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.warn("reconnect attempt $attempt failed: ${e.message}")
                    false
                }
                if (recovered) {
                    log.info("reconnected on attempt $attempt")
                    return@launch
                }
                wait = (wait * 2).coerceAtMost(config.maxReconnectDelayMs)
            }
            _phase.value = LinkPhase.Disconnected
        }
    }

    private fun failEverything(error: RadioError) {
        val failure = RadioException(error)
        pending.values.forEach { operation ->
            when (operation) {
                is PendingOperation.Reply -> operation.response.completeExceptionally(failure)
                is PendingOperation.Stream -> operation.end.completeExceptionally(failure)
            }
        }
        pending.clear()
        tickets.values.forEach { if (!it.isCompleted) it.completeExceptionally(failure) }
        tickets.clear()
        earlyOutcomes.clear()
    }

    /** Routes one decoded envelope: a response to a request, or an async push. */
    private fun dispatch(envelope: ByteArray) {
        val message = runCatching { RadioToClient.ADAPTER.decode(envelope) }.getOrNull() ?: run {
            // The firmware drops an envelope that fails to decode without a
            // reply; a client must not invent one either.
            log.debug("dropped an undecodable envelope of ${envelope.size} bytes")
            return
        }
        if (message.id != 0) {
            val operation = pending[message.id] ?: run {
                log.debug("no request waiting for id ${message.id}")
                return
            }
            when (operation) {
                is PendingOperation.Reply -> {
                    pending.remove(message.id)
                    operation.response.complete(message)
                }
                // A fetch stream answers with many frames that all echo the
                // request's id, so it stays registered until the terminator.
                is PendingOperation.Stream ->
                    if (message.fetch_end != null) {
                        pending.remove(message.id)
                        operation.end.complete(message)
                    } else {
                        operation.onPacket(message)
                    }
            }
            return
        }

        // An id of zero is an async push. The three that carry sensitive read
        // models only reach authorized connections, so a client renders what
        // arrives rather than re-deriving the gate.
        when {
            message.packet_entry != null ->
                _events.tryEmit(PacketEvent.PacketReceived(message.packet_entry!!.asPacket()))

            message.tx_result != null -> {
                val result = message.tx_result!!
                val txOutcome = TxOutcome(seq = result.seq, success = result.success)
                val ticket = tickets.remove(result.seq)
                if (ticket != null) {
                    ticket.complete(txOutcome)
                } else {
                    // Hold it for the ticket that is about to be registered.
                    if (earlyOutcomes.size >= EARLY_OUTCOME_LIMIT) {
                        earlyOutcomes.remove(earlyOutcomes.keys.first())
                    }
                    earlyOutcomes[result.seq] = txOutcome
                }
                _events.tryEmit(PacketEvent.TxCompleted(txOutcome))
            }

            message.radio_settings != null -> {
                val tuning = message.radio_settings!!.toDomain()
                _tuning.value = tuning
                _events.tryEmit(PacketEvent.RadioRetuned(tuning))
            }

            message.device_settings != null ->
                _events.tryEmit(PacketEvent.DeviceSettingsChanged(message.device_settings!!.toDomain()))

            message.status != null -> _events.tryEmit(PacketEvent.LinkChanged(message.status!!.toDomain()))

            message.error != null -> _events.tryEmit(PacketEvent.Reported(message.error!!.toDomain()))

            else -> log.debug("ignoring an empty push")
        }
    }

    private fun ProtoPacketEntry.asPacket(): StoredPacket =
        toDomain(observedAtMs = anchor.wallMsFor(uptime_ms))

    private fun recordDeviceInfo(info: DeviceInfo) {
        if (anchor.anchor(info.uptimeMs, clock.nowMs())) {
            log.info("radio rebooted (uptime ${info.uptimeMs}ms); the clock was re-anchored")
        }
        _device.value = info
    }

    // ── request plumbing ────────────────────────────────────────────────────

    private suspend fun call(build: RequestBuilder): RadioToClient {
        val response = CompletableDeferred<RadioToClient>()
        val id = onState { register(PendingOperation.Reply(response)) }
        try {
            write(build(id))
            val message = try {
                withTimeoutOrNull(config.requestTimeoutMs) { response.await() }
            } catch (e: TimeoutCancellationException) {
                null
            } ?: throw RadioException(RadioError.Timeout("request", config.requestTimeoutMs))
            message.error?.let { throw RadioException(it.toDomain()) }
            return message
        } finally {
            onState { pending.remove(id) }
        }
    }

    private suspend fun write(request: ClientToRadio) {
        val bytes = runCatching { Framing.encode(request.encode()) }.getOrElse {
            throw RadioException(RadioError.LinkLost("could not encode ${request::class.simpleName}: ${it.message}"))
        }
        runCatching { link.write(bytes) }.getOrElse {
            throw RadioException(RadioError.LinkLost(it.message ?: "write failed", it))
        }
    }

    private fun fail(reason: String): Nothing = throw RadioException(RadioError.UnexpectedResponse(reason))

    private fun fail(error: RadioError): Nothing = throw RadioException(error)

    /** Runs [block] on the session dispatcher; every state mutation goes through it. */
    private suspend fun <T> onState(block: () -> T): T = withContext(state) { block() }

    private fun register(operation: PendingOperation): Int {
        // 0 is reserved for async pushes, so requests start at 1.
        val id = nextRequestId
        nextRequestId = if (nextRequestId >= MAX_REQUEST_ID) 1 else nextRequestId + 1
        pending[id] = operation
        return id
    }

    private companion object {
        /** Comfortably inside uint32, and far from wrapping. */
        const val MAX_REQUEST_ID = 0x7FFF_FFFF

        /** How many unmatched send outcomes are worth holding. */
        const val EARLY_OUTCOME_LIMIT = 64
    }
}