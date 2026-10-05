# MeshPigeon Core + CLI — Implementation Plan (v6)

**Status:** architecture corrected; two decisions still open · **Updated:** 2026-10-04
**Repos:** [`meshpigeon/meshpigeon-core`](https://github.com/meshpigeon/meshpigeon-core) ·
[`meshpigeon/meshpigeon-cli`](https://github.com/meshpigeon/meshpigeon-cli)

> **v5 corrects the architecture, not just the wording.** v2–v4 had me building a
> `RadioDialect` layer so that "a stock Meshtastic node" could be driven directly.
> That is wrong: **core never speaks to anything but `meshpigeon-firmware`.**
> There is one radio API (its protobuf API), one framing, one set of transports.
> MeshCore *and* Meshtastic are both just bytes handed to the same dumb modem —
> what differs between them is what the bytes *mean*, never how they travel.
> Also fixed: no hardware knowledge below the protobuf API, no BLOBs beyond
> genuine byte arrays, a real `protocol` table instead of TEXT references, an
> i18n-ready string layer, and a CLI that is **one interactive session with no
> application command-line options** — scriptable by piping that same session.
> **v6** adds an expert schema review (§8.2 conventions, §8.6 migration
> playbook) whose net effect is *less* churn: one canonical schema version,
> database-enforced invariants, no cascade-delete of replies, and no duplicated
> state that can drift.

---

## 1. Context

### 1.1 What exists today (measured)

**`meshpigeon-firmware`** (`main` @ `6687d8d`) — a dumb, durable LoRa modem.
Its protobuf API is the *only* interface to it, and the whole client surface:

- `protobufs/meshpigeon/{envelope,device,radio}.proto`, package `meshpigeon`,
  ~14 operations, additive-only, enforced by `buf lint` + `buf breaking`;
  whole-spec breaks bump `DeviceInfo.spec_version`.
- Framing: `COBS( envelope ‖ crc16_LE(CRC-16/CCITT-FALSE) ) 0x00`, one envelope
  per frame, identical over USB CDC / BLE NUS / Wi-Fi TCP. Requests carry a
  client `id`; async pushes use `id = 0`; `FetchPackets` streams with a terminator.
- It stores raw packets it cannot parse, persists radio settings and device
  settings (name, PIN, Wi-Fi), and keys up on demand. **It has no identity, no
  keys, no protocol.**
- Nanopb C is generated and committed; `scripts/regen-protos.sh --check` is CI.
- **Simulator**: `pio run -e sim` → `.pio/build/sim/program --port N [--loss
  --dup --traffic-ms --store --fake-wifi]` speaks the real API over TCP;
  `scripts/mesh-sim.sh N BASE` boots an N-radio mesh. **Our test harness.**

**`meshpigeon-android`** (`main` @ `1a8b81b`) — all non-UI code lives here today,
written before the protobuf work, on BouncyCastle (JVM-only), Room
(Android-only) and `java.io.ByteArrayOutputStream`. **Reference only** (D22):
we mine vectors, constants and ideas (§15), and reuse the code when we actually
rebuild the app at P9.

### 1.2 The architecture, stated correctly

```
┌──────────────────────────────────────────────────────────────────────┐
│  ANY MeshPigeon client:  mp   ·   Android   ·   web   ·   iOS       │
│  rendering · input · platform permissions        (nothing else)     │
└──────────────────────────────┬───────────────────────────────────────┘
                               │  MeshPigeonService
┌──────────────────────────────▼───────────────────────────────────────┐
│  meshpigeon-core                                                    │
│                                                                      │
│   core-meshcore    core-meshtastic     ← what the bytes MEAN         │
│        │                  │              (codec + crypto, pure)       │
│        └────────┬─────────┘                                        │
│                 │ raw frames (≤255 B), byte-identical to MeshCore    │
│   core-storage  ←→  core-domain  ←→  core-crypto                    │
│        ▲                                                             │
│        │   ONE radio API — meshpigeon-firmware's protobuf API        │
│   core-transport (framing, session, auth, retry, uptime anchoring)   │
│        ▲                    ▲                    ▲                  │
│   RadioLink tcp        RadioLink usb        RadioLink ble            │
└────────┼────────────────────┼────────────────────┼───────────────────┘
         ▼                    ▼                    ▼
   ┌──────────────────────────────────────────────────────────────┐
   │  meshpigeon-firmware — dumb modem + durable packet store     │
   │  (SX1262 / LR1110 / whatever — the core never learns this)    │
   └───────────────────────────────┬──────────────────────────────┘
                                   ▼ on-air raw frames
                      MeshPigeon repeaters, MeshCore, Meshtastic…
```

Three consequences that drive everything below:

1. **Meshtastic support means a codec, not a driver.** `core-meshtastic` encodes
   MeshPigeon-radio frames carrying Meshtastic payloads (protobuf, AES-CTR by
   PSK). The radio does not know or care; neither does `core-meshtastic`. A
   stock Meshtastic node is just another thing on the air, decoded by our code.
2. **Hardware knowledge stops at the protobuf API.** The core's entire picture of
   a board is what `DeviceInfo`/`Status` report: `board_name`, `capabilities`,
   `radio_ok`, `battery_mv`, `noise_floor_dbm`, `uptime_ms`, `boot_count`,
   `radio_config_epoch`, store statistics. No chip part numbers, no HAL, no
   vendor registers — and nothing in a protocol module ever sees a radio.
3. **There is exactly one radio API to implement.** That is why the CLI and
   `meshpigeon-core` stay small enough to be right.

### 1.3 Research findings that shaped this plan

| Finding | Effect |
|---|---|
| Meshtastic publishes **KMP protobuf models**: `org.meshtastic:protobufs:2.7.23` (Wire-generated, Maven Central, android/jvm/js/wasm/native/ios/tvos) — i.e. the *air* schema as Kotlin | **D4 → Square Wire.** We consume the air messages (`MeshPacket`, `Data`, `Position`, `NodeInfo`, `User`, `Routing`, `ChannelSettings`) and never touch their device API. Their build is our reference for Wire + KMP + Central publishing. |
| Meshtastic has **real keypairs** (`Config.SecurityConfig.public_key/private_key`, `User.public_key`), XEdDSA signing, PKI encryption, node numbers | One identity model serves both protocols: an Ed25519 keypair plus a protocol-specific address. Identity is core-owned; the radio never sees it (D27, D28 moot). |
| Meshtastic protos are **GPL-3.0** | **D21 → GPL-3.0** for `meshpigeon-core` + `meshpigeon-cli`. `meshpigeon-firmware` stays MIT (separate program). `meshpigeon-android` relicensed at P9. |
| kotlinx-protobuf's runtime is not on Maven Central; `org.kotlincrypto` has hashes/MACs/CSPRNG but no cipher/signature; libsodium bindings have no AES-ECB/CTR | **D6 → an assembly, chosen by a 2-day spike** (§6.0). The risk is byte-exactness, not availability. |
| SQLDelight **2.4.0**, Clikt **5.1.0**, Mordant **3.1.0** are current | D5/D9 confirmed with real versions. |

---

## 2. Vision

> `meshpigeon-core` is a Kotlin Multiplatform library holding **every line of
> MeshPigeon that is not a user interface**: the radio API, mesh protocols,
> identity, crypto, the packet pipeline, the SQLite data model, and the service
> that orchestrates them. `meshpigeon-cli`, `meshpigeon-android`,
> `meshpigeon-web`, `meshpigeon-ios` hold **only rendering, input, and platform
> permissions**.

| G | Goal | Proof |
|---|---|---|
| G1 | `mp` is a **native executable** on Linux/macOS/Windows | `mp` from a release tarball, no JVM present |
| G2 | Clients contain **no** protocol, storage or radio logic | CI import-gate (§4.5); the Android diff is UI only |
| G3 | **One SQLite file, one schema**, every client, protocol-agnostic | A DB written by `mp` opens in the Android app |
| G4 | **MeshCore *and* Meshtastic** identities usable through one interface, both over MeshPigeon radios | switch identity, message on either |
| G5 | Scriptable tests against real radios and sims | CI scripts pipe command scripts into `mp` against `meshpigeon-sim` |
| G6 | Versioned, published artifacts | `dev.meshpigeon:meshpigeon-core:<semver>` on Maven Central |

**Non-goals:** re-implementing the Android UI; a full Meshtastic feature set
(only what maps onto our model); **stock-Meshtastic or any other node's device
API** (only `meshpigeon-firmware` is ever spoken to); cloud sync; accounts; push
notifications; a LoRa HAL or any hardware detail below the protobuf API;
**multiple simultaneous radio connections in one client (D25)**; a general mesh
framework (if a protocol cannot be expressed through the §6 SPI, we fork the SPI
deliberately rather than leak specifics upward).

**D25 — single connection per client, deliberately.** The firmware serves several
clients happily, but one `mp` process holds one radio connection, and
multi-connection gets *no* accommodation: no session registry, no multiplexing,
no UI, nothing "kept ready". It will be cheap later only because the seam is
already right — a connection is one `RadioLink` behind one `RadioApi` session, so
N connections means running that pipeline N times.

**Extended principles** for both new repos: **P1** protocol lives in the client
(→ now: in `meshpigeon-core`); **P5** layers stay separated (→ no client
implements protocol, crypto, persistence, or radio handling); **P9** *the CLI is
a first-class client, not a test harness — anything hard in the GUI must be
reachable from `mp`, because that is how it gets fixed.*

---

## 3. Repo topology

```
              ┌────────────────────────────────────────────────────┐
              │  meshpigeon-firmware  (C++/PlatformIO)             │
              │  owns meshpigeon/*.proto + radio-protocol.md       │
              │  dumb modem: packet store + settings               │
              │  meshpigeon-sim (TCP) = our test harness           │
              └───────────────────▲────────────────────────────────┘
                    submodule pinned to a firmware tag
              ┌───────────────────┴────────────────────────────────┐
              │  meshpigeon-core (Kotlin Multiplatform)            │
              │  proto-gen · core-crypto · core-transport          │
              │  core-link-{tcp,usb,ble} · core-domain             │
              │  core-meshcore · core-meshtastic                   │
              │  core-storage · core-service · core-testing        │
              └───┬──────────────┬──────────────┬──────────────────┘
       ┌──────────┘              │              └─────────────┐
┌──────▼─────────────┐  ┌────────▼─────────┐  ┌─────────────────▼────┐
│ meshpigeon-cli     │  │ meshpigeon-android│  │ web / ios (later)    │
│ one REPL session,  │  │ (Compose, later)  │  │                     │
│ `mp`               │  │                   │  │                     │
└────────────────────┘  └───────────────────┘  └─────────────────────┘
```

| Rule | Detail |
|---|---|
| Namespace | `github.com/meshpigeon/*` ✅ both repos created |
| Kotlin | Kotlin Multiplatform, Kotlin 2.2.x (same toolchain as `meshpigeon-android`) |
| Gradle | 8.14.5 wrapper, version catalogs, configuration cache |
| Version | SemVer; one train — a core tag is what clients move to |
| Branching | Trunk-based, short-lived branches, conventional commits |
| Docs | `docs/plans/` in each repo; user docs with `meshpigeon-cli` |
| License | **GPL-3.0** for core + cli. `meshpigeon-firmware` stays **MIT** (separate program, links nothing of ours). `meshpigeon-android` relicensed at P9, when it starts linking core |
| CI | GitHub Actions: `buf` gate, all-target build, common tests, e2e against the sim, nightly interop |

---

## 4. `meshpigeon-core` — module layout

```
meshpigeon-core/
├─ proto/                     # git submodule: meshpigeon-firmware@<tag>/protobufs
├─ proto-gen/                 # Wire-generated Kotlin for the radio API (commonMain)
├─ core-common/               # ids, hex/base64, Result, Clock, Logger, COBS, CRC
├─ core-crypto/               # primitives + protocol crypto (the D6 answer)
├─ core-transport/            # RadioApi (the firmware protobuf API) + RadioLink SPI + session
├─ core-link-tcp/             # TCP link
├─ core-link-usb/             # USB-CDC link (posix cinterop + jvm/android streams)
├─ core-link-ble/             # BLE link — android + apple source sets only (D15)
├─ core-domain/               # entities, repository interfaces, pure use-case logic
├─ core-meshcore/             # on-air MeshCore v1  (implements MeshProtocol)
├─ core-meshtastic/           # on-air Meshtastic    (implements MeshProtocol) — P6
├─ core-storage/              # SQLDelight schema, DAOs, migrations, SecretStore
├─ core-service/              # MeshPigeonService — the façade every client calls
└─ core-testing/              # PUBLISHED fakes: fake radio API, in-memory store, fake clock
```

**Dependency rule**, enforced by a Gradle `check` task that parses the graph:

```
common → crypto → transport → domain → protocol impls → storage → service
                              ↑                                    │
                              └──────── core-testing (fakes) ─────┘
```

`core-domain` knows nothing about SQLDelight. `core-storage` knows nothing about
any mesh protocol. `core-meshcore`/`core-meshtastic` know nothing about radios,
transports, or storage. `core-service` is the only module that knows everything.

### 4.1 Why this split

| Module | Reason it exists |
|---|---|
| `proto-gen` | Generated code must live in one module's `commonMain` |
| `core-crypto` | Swapping crypto backends is a one-module, separately reviewable change |
| `core-transport` vs `core-link-*` | The **API** (verbs, correlation, auth, store semantics) is one thing; the **pipe** (tcp/usb/ble) is another. Separating them is what makes a USB radio and a Wi-Fi radio behave identically |
| `core-domain` apart from `core-storage` | Port/adapter, so `core-testing` can supply in-memory repos and CLI tests need no DB file |
| `core-meshcore` / `core-meshtastic` | Adding a protocol never touches shared code, and neither can see a radio |
| `core-service` | One façade, so four clients cannot each invent orchestration |

### 4.2 Targets (D1 — confirmed)

| Target | For | Phase |
|---|---|---|
| `linuxX64`, `linuxArm64` | native `mp` | P1 |
| `macosX64`, `macosArm64` | native `mp` | P1 |
| `mingwX64` | native `mp` for Windows | P2 |
| `jvm` | Android, IDE, fastest CI feedback | P1 |
| `androidTarget` | `meshpigeon-android` | P9 |
| `iosArm64`, `iosX64`, `iosSimulatorArm64` | `meshpigeon-ios` | P10 |
| `js(IR)`, `wasmJs` | `meshpigeon-web` | P10 |

Rule: modules are `commonMain` with `expect` only at true platform boundaries
(sockets, serial, BLE, keystore, clock). CI keeps a **compile-only** target list
(§11.4) so a JVM-only API sneaking into a shared file fails the build rather than
the iOS build six months later.

### 4.3 Publishing (D3 — confirmed)

**Maven Central** via `com.vanniktech.maven.publish` — the plugin Meshtastic
uses, so the Central Portal path is already known to work.

| Artifact | Contents |
|---|---|
| `dev.meshpigeon:meshpigeon-core` | aggregating, `api` deps only |
| `dev.meshpigeon:meshpigeon-core-<module>` | per-module |
| `dev.meshpigeon:meshpigeon-proto` | published from the firmware repo (destination state of D2) |
| `dev.meshpigeon:meshpigeon-cli-core` | the CLI's command/output model, so docs and scripts can reference it |

Snapshots → Central Portal snapshots repo, versioned `{tag}.{N}-g{sha}-SNAPSHOT`
so dependency bots sort correctly. During P1–P6 the two repos develop against
each other through a Gradle composite build. `sourcesJar` + Dokka mandatory.

### 4.4 What "a client is done" means

A client's entire source tree is UI/commands, rendering, and platform
permissions, and it compiles without reaching into core.

### 4.5 The import-gate (D18)

`meshpigeon-cli` may use **only** `meshpigeon-core`'s public API. CI fails on any
of these appearing in `meshpigeon-cli/src/`: `java.`, `javax.crypto`,
`org.bouncycastle`, `app.cash.sqldelight`, `com.squareup.wire`, any SQL string,
any generated protobuf type, any core `internal`/`io` package, **and any chip or
vendor identifier** (an `sx1262` in the CLI would mean hardware knowledge leaked
out of the firmware's abstraction).

`meshpigeon-core-testing` (fake radio API, in-memory store, fake clock) is fair
game **in CLI tests**.

---

## 5. Wire layer: one radio API

### 5.1 Codegen: Square **Wire** (D4)

`wire { kotlin { … } }` from the `com.squareup.wire` Gradle plugin (7.x).
Wire generates **Kotlin** straight into `commonMain`; its runtime publishes every
target we need; and Meshtastic's own KMP package is built exactly this way for
exactly our target matrix. `buf` stays for governance: `buf lint` +
`buf breaking --against '.git#branch=main'` in the firmware repo.

### 5.2 Schema ownership (D2 — confirmed)

The firmware implements the API, so it owns the schema, and
`docs/radio-protocol.md` stays normative beside the `.proto` files. How core
consumes it without a copy that can rot:

| Option | Mechanism | Verdict |
|---|---|---|
| **(a) Submodule pinned to a firmware tag** | `meshpigeon-core/proto` is a git submodule at tag `v0.2.0`; bumps are deliberate PRs | ✅ **v1.** Zero copies, so zero drift; the pinned tag *is* the compatibility statement |
| **(b) Descriptor-set drift check** | CI fetches the firmware's `main` and byte-compares `buf build -o x.binpb` against the submodule | ✅ **with (a).** Compares descriptor sets, so comment/whitespace edits never false-positive but a real schema move always pings us |
| **(c) Firmware publishes generated Kotlin** | firmware gains a `proto-kmp/` module publishing `dev.meshpigeon:meshpigeon-proto` | ⏳ **Destination state (P8).** Core then has no submodule at all |

### 5.3 Generated types never escape

`proto-gen` types stay inside `core-transport`. The API returns domain types
(`DeviceInfo`, `Tuning`, `StoredPacket`, `LinkStatus`, `LinkPhase`, `RadioError`),
so no protocol module, no DB row, and no UI ever imports a generated class.

### 5.4 Hardware is abstracted behind the protobuf API

Everything the core knows about a board comes from `DeviceInfo` / `Status` /
`RadioSettings`, i.e.:

`board_name`, `capabilities` (`WIFI_STA`, `BATTERY`, `BLE`, `USB_CDC`), `radio_ok`,
`battery_mv`, `noise_floor_dbm`, `uptime_ms`, `boot_count`,
`radio_config_epoch`, `store{count,capacity_bytes,dropped,oldest_seq}`, plus
tuning `freq_hz/bandwidth_hz/sf/cr/power_dbm/config_epoch`.

That is the entire hardware surface. There is **no** chip model, no driver, no
HAL, no register access, no "LoRa modem class" below this line — and no protocol
module is ever handed a radio at all. Instead of pretending we abstract the
hardware, we **ask the device about itself**:

- `CAPABILITY_USB_CDC` → offer a USB-CDC link for it.
- `CAPABILITY_BLE` → offer a BLE link. `CAPABILITY_WIFI_STA` → offer TCP.
- `CAPABILITY_BATTERY` → show battery. `radio_ok == false` → the radio is broken;
  every send fails fast with a clear message instead of timing out.
- `noise_floor_dbm` → the quiet-band indicator; unknown future capabilities are
  ignored, per the firmware's additive-only rule.

This is why a future board is a firmware change and nothing else.

### 5.5 The radio API and link SPI

One implementation. The interface exists so `core-testing` can supply a fake and
a scripted replay — not to plan a second dialect.

```kotlin
/** meshpigeon-firmware's API. The only interface to any radio. */
interface RadioApi {
    suspend fun deviceInfo(): DeviceInfo
    suspend fun status(): LinkStatus
    suspend fun authenticate(secret: Secret?): AuthResult

    suspend fun readTuning(): Tuning                    // GetRadioSettings
    suspend fun writeTuning(t: Tuning): Tuning          // SetRadioSettings → post-write + epoch

    suspend fun sendRaw(raw: ByteArray): TxTicket       // SendPacket → ticket now, outcome async
    fun incoming(): Flow<PacketEvent>                   // PacketEntry / TxResult / settings pushes
    suspend fun fetchHistory(since: Cursor, limit: Int): HistoryPage
    suspend fun purgeHistory()

    suspend fun deviceSettings(): DeviceSettings        // name, PIN, Wi-Fi
    suspend fun writeDeviceSettings(patch: DeviceSettingsPatch)
    suspend fun reboot(); suspend fun factoryReset()
}

/** A byte pipe to a radio. tcp / usb / ble — nothing above this knows which. */
interface RadioLink {
    val descriptor: LinkDescriptor
    val state: StateFlow<LinkState>
    suspend fun open(); suspend fun close()
    fun inbound(): Flow<ByteArray>
    suspend fun write(bytes: ByteArray)
    suspend fun discover(): List<Candidate>             // tcp: probe ports · usb: enumerate
}
```

`sendRaw` returns a **ticket**: the outcome arrives asynchronously as `TxResult`
(or as a decode failure, or a gap). The domain never sees the difference. The
COBS + CRC-16/CCITT-FALSE logic already in the firmware (and mirrored in
`meshpigeon-android`) is correct; here it just gets rewritten off
`java.io.ByteArrayOutputStream` so it is genuinely common code, with its tests
running natively.

---

## 6. Protocol layer: one SPI, two meshes, capabilities-driven differences

### 6.0 Crypto strategy (D6/D23) — what the risk actually is

Not "will working crypto exist on all platforms" — it will. The risk is two
narrower things:

1. **No single KMP library covers our exact algorithm set.** MeshCore needs
   Ed25519, its X25519-style key exchange, AES-128-ECB, and a 2-byte
   HMAC-SHA256. Meshtastic adds AES-CTR, PSK derivation, and XEdDSA. On Maven
   Central today `org.kotlincrypto` has SHA-2/HMAC/CSPRNG in pure KMP but **no
   cipher and no signature**; libsodium bindings cover Ed25519/curve25525/SHA/
   HMAC on every target but **no AES-ECB or AES-CTR**. So it is an **assembly**.
2. **Byte-exactness, silently.** A perfectly valid Ed25519 or AES implementation
   that differs by one bit-order from MeshCore's interops with *nothing*, and
   fails as "nobody hears me" rather than as an error. That is why P3's exit
   criterion is a vector produced by MeshCore's own C library matching ours byte
   for byte — not "the tests pass".

Cheap insurance: a 2-day spike building the realistic candidates for linuxX64 +
macosArm64. If you'd rather not spend the days, the default is RustCrypto
(`cargoBuild`: `ed25519-dalek`, `curve25519-dalek`, `aes`, `ctr`, `ecb`, `sha2`,
`hmac` — one audited suite covering everything), with `org.kotlincrypto` for
hashes/HMAC/CSPRNG where it removes native-build weight. The parity suite is the
gate either way, and every provider sits behind one interface.

### 6.1 The SPI (D7 — large common core, specifics stay in their implementation)

```kotlin
interface MeshProtocol {
    val id: ProtocolId                       // "meshpigeon.meshcore.v1"
    val spec: ProtocolSpec                   // name, version, docs, capabilities

    // ── identity ───────────────────────────────────────────────────────────
    fun generateIdentity(seed: IdentitySeed? = null): ProtocolIdentity
    fun importIdentity(encoded: ByteArray, passphrase: CharArray? = null): ProtocolIdentity
    fun exportIdentity(identity: ProtocolIdentity): ByteArray     // sealed by core-storage
    fun parseShareCode(text: String): ProtocolIdentity?
    fun shareCode(identity: ProtocolIdentity): String             // QR / link payload
    fun addressOf(identity: ProtocolIdentity): ProtocolAddress    // the on-air address
    fun contactAddressOf(decoded: DecodedPacket): ProtocolAddress? // adverts → contacts

    // ── codec ─────────────────────────────────────────────────────────────
    fun decode(raw: ByteArray, nowMs: Long): DecodedPacket?       // null ⇒ not ours
    fun encode(payload: OutboundPayload, ctx: EncodeContext): List<ByteArray>

    // ── crypto ────────────────────────────────────────────────────────────
    fun crypto(): ProtocolCrypto

    // ── semantics ─────────────────────────────────────────────────────────
    fun planSend(request: SendRequest): SendPlan      // frames, ack policy, retries, airtime
    fun describeInbound(decoded: DecodedPacket): InboundMessage?
    fun observeContact(decoded: DecodedPacket): ContactObservation?
    fun capabilities(): ProtocolFeatures
}
```

A protocol module is a pure codec + crypto. It receives raw bytes and produces
raw bytes. It cannot see a radio, a link, a DB, or a UI — which is why "the same
identity works on any radio, over any transport" is structurally true rather than
a promise.

**Commonality is maximized; differences are data, not branches.** Concretely:

1. ~85 % of `MeshProtocol` is shared, and a reusable **`ProtocolContractSuite`**
   abstract test class runs against every implementation. **That is the mechanism
   that keeps the SPI honest** rather than aspirational.
2. Unique abilities are **declared**, not `instanceof`'d:

```kotlin
interface ProtocolFeatures {
    val directMessages: Support      // FULL | LIMITED | UNSUPPORTED
    val groupChannels: Support
    val broadcast: Support
    val encryption: EncryptionModel  // PEER_KEYS | SHARED_SECRET | PSK | NONE
    val signedPackets: Boolean
    val positionReports: Boolean
    val reactions: Boolean
    val replies: Boolean
    val deliveryStates: Set<DeliveryState>
    val addressing: AddressingScheme          // PUBLIC_KEY | NODE_NUMBER | …
    val maxPayloadBytes: Int
    fun channels(): ChannelSemantics
    fun deliveryOptions(): List<DeliveryOption>   // declared knobs (below)
}
```

The CLI and Android ask the descriptor, never the concrete type: "no direct
messages here" hides the entry point; "delivery states = {sent, delivered}"
renders two ticks, not three. **New protocol, new UI, zero UI changes.**

**Users never see protocol mechanics.** Nothing in the vocabulary says "ACK",
"flood route", "hop", "PSK" or "payload". Internal delivery states map per
protocol into outcome language — MeshCore's as `sent` / `heard ✓` /
`delivered ✓✓`, Meshtastic's as `sent` / `delivered`. A verbose view can print
raw states for debugging; the default surface is outcomes only.

3. **Configurable delivery behaviour is declared data (D7).** Some protocol
   behaviour *is* user-facing — a MeshCore DM can ask for a **double ACK**, a
   Meshtastic packet sets `want_ack` — so the SPI exposes tunables without
   exposing mechanisms:

```kotlin
sealed interface DeliveryOption {
    val id: String; val label: String; val help: String
    val scope: DeliveryScope                       // IDENTITY | DM | CONVERSATION
    val default: DeliveryValue
    fun allowed(): Set<DeliveryValue>
}
// MeshCore  declares: "Confirm delivery"      single | double  scope=DM,      default single
// Meshtastic declares: "Request delivery ack" on | off       scope=IDENTITY, default on
```

- v1 scope is **global per identity** (D26), as MeshCore treats it today; the
  `delivery_policy` table carries a nullable `conversation_id` so a per-DM
  override later is an addition, not a redesign.
- The UI has **no** protocol-specific code: it renders whatever is declared, and
  a protocol with zero knobs renders none. Adding "double ACK" to MeshCore needs
  no client change at all.

4. **Protocol specifics stay reachable but typed (D30).** Each implementation
   registers its own extensions, discoverable by protocol id:

```kotlin
interface ProtocolExtension           // marker
data class MeshCoreChannelSlot(…) : ProtocolExtension     // slot/secret-flag details
data class MeshCoreDirectConfirm(…) : ProtocolExtension
data class MeshtasticNodeRole(…) : ProtocolExtension       // CLIENT | ROUTER | REPEATER
data class MeshtasticPositionPrecision(…) : ProtocolExtension
val extensions: ExtensionRegistry     // protocol id → extensions
```

Available when a client genuinely needs them, invisible otherwise, and never a
cast to a concrete protocol class. **Nothing hardware-shaped appears here** — a
protocol knows nothing about what carried it.

### 6.2 Identity, both protocols (D8/D27/D28)

|  | MeshCore | Meshtastic |
|---|---|---|
| Key material | Ed25519 keypair | Ed25519 keypair |
| On-air address | pubkey hash (full key in adverts) | `uint32` node number |
| Group messaging | channels: public / hashtag / private | channels: PSK (0/16/32 B) |
| Direct messaging | X25519-style ECDH, AES-128-ECB + 2-byte HMAC | PKI to the peer's public key |
| Signatures | Ed25519 over the frame | XEdDSA |
| Radio settings | region frequency plan → tuning rows | modem preset → tuning rows |

**Identity is core-owned, for both protocols, and the radio never sees it.**
That is the governing principle: the radio is a dumb modem; `meshpigeon-core`
defines the identity, stores it in the `identity` table with exactly the same
lifecycle as a MeshCore identity, and DMs are encrypted to it by us. There is no
"adopt from device" path and no device-write path — and none is needed, because
the firmware has no concept of identity to configure. **D28 is therefore moot
and closed.**

### 6.3 `core-meshcore` (build from scratch, mine vectors)

MeshCore v1 on-air codec, channels (public/hashtag/private), ACK/retry FSM with
physics-based timeouts, uptime→wall-clock mapping, path cache, share
codes/cards, reactions. Built fresh against the **MeshCore C++ source on this
machine** plus `openhop_core` and `meshcore_py` as oracles (§15).

### 6.4 `core-meshtastic` (P6, droppable)

Air format only. Consumes `org.meshtastic:protobufs` for `MeshPacket`, `Data`,
`Position`, `NodeInfo`, `User`, `Routing`, `ChannelSettings`, `MyNodeInfo`
(our own local node descriptor — an air message, not a device concept).
Implements: channel PSK → hash derivation, AES-CTR payload encryption,
XEdDSA verification, node-number addressing, hop limits, delivery acks, and the
mapping of its modem presets into `protocol_radio_settings` rows (they are just
`freq/bw/sf/cr` tuples). **No device API, no `ToRadio`/`FromRadio`/`Config`
messages, no radio identity.**

Highest-risk unknown: the exact PSK→channel-hash and AES-CTR derivation.
Mitigated by a **recorded real-frame corpus** captured from a live Meshtastic
node and cross-checked against upstream's own decoder (P6 day 1).

---

## 7. `meshpigeon-cli` — `mp`: one session, no flags

### 7.1 Libraries (D9 — confirmed)

| Concern | Choice | Why |
|---|---|---|
| Commands | **Clikt 5.1.0** | The mature multiplatform Kotlin CLI framework; one command declaration drives the session, help, and completions |
| Terminal | **Mordant 3.1.0** (Clikt's own renderer) | Tables, colors, progress, live displays; multiplatform |
| Config/JSON | `kotlinx-serialization` (+ TOML) | output models and `/json` mode |
| Bytes/time | `kotlinx-io`, `kotlinx-datetime` | no `java.io`/`java.time` in common code |
| Crypto | in core, never the CLI | |

Rejected: Mosaic (JVM-only), JLine (JVM-only; we hand-roll a POSIX line editor),
Kong (unmaintained), anything in Rust/Go/Python (a second language).

### 7.2 The interface is the session (D30-CLI)

**There are no application-defined command-line options.** `mp` opens the
session and everything — identity, radio, chats, contacts, channels, packets,
settings, output mode, profiles — happens inside it, as slash commands. The only
options are the ones the library gives us for free (`--help`, `--version`).

```console
$ mp

  MeshPigeon 0.1.0 · no identity · no radio
  /help for commands · /quit to exit

  mp ▸ /onboard
  mp ▸ /chat open #test
  #test ▸ on my way
  #test ▸ /quit
```

Scripting falls out of the same design rather than being bolted on: **the session
reads its command stream from stdin.** A piped/non-TTY stdin runs the identical
command language with no line editor and no colors, so tests are the same code
path as a human session.

```bash
printf '/chat open #test\non my way\n/quit\n' | mp
mp < scripts/acceptance/first-message.mp          # a script is just a command file
MP_PROFILE=ci mp < scripts/acceptance/dm.mp       # isolated profile + DB
```

Exit status in non-interactive mode is the last non-zero command status, so CI
can assert on it. That gives us everything the old `--json`/`--timeout` flags
would have: `/json on` switches the *output mode* inside the session,
`/profile ci` and `/timeout 30s` switch context. Same surface, no flags.

### 7.3 The messaging session (the flagship screen)

```
  mp ▸ /chat open #test
  ── #test · meshcore · 4 online · last 20 ─────────────────────── 12:04 ──
  12:01  alex ▸ anyone near the south trail tonight?
  12:02  kim ▸ yes, leaving in ten
  ─────────────────────────────────────────────────────────────────────────
  #test ▸ on my way
  ⠋ sending…                                                         12:04:03
  ✓✓ delivered · rtt 2.4 s
```

- **Targets resolve by handle**: `#test` (channel), `@alex` (contact), a
  conversation id, or the open chat's pinned name. Bare text posts to the
  currently-open conversation; `/say` forces an explicit target.
- **Opening a chat loads recent history** (last N, default 20) then streams new
  messages as they arrive.
- `/quit` (also `/exit`, also Ctrl-D) exits cleanly after flushing the outbox;
  Ctrl-C cancels the in-flight send, never the process.
- **First run is a guided flow, never a dead end**: with no identity the session
  offers `/onboard` — pick a protocol (MeshCore first), create an identity,
  connect a radio, let the core apply that protocol's radio settings (§8.3).
  Every step is the corresponding real command, so the wizard teaches the
  vocabulary instead of hiding it behind a one-off path.
- **The device PIN is remembered** per device in the DB, sealed, beside its
  connection details; `/pin <code>` overrides when someone changed it on the device.

Design decisions:

- **Line-append streaming, not full-screen redraw.** Robust over SSH, no
  alternate-screen escapes, no resize handling, works in a 20-row terminal and a
  200-row one. (Full-screen `LiveDisplay` stays available for `/watch`.)
- **One output coordinator owns the terminal.** Background events may print while
  the editor is idle; while the user is typing, events queue and the prompt is
  redrawn beneath them. This arbitration is the hardest part of the session, so it
  is one isolated component with its own tests.
- **Raw-mode line editor** (POSIX `termios` cinterop on native, JLine on JVM):
  history, ←/→, Ctrl-A/E/K/W, Tab completion for `/commands`, `@contacts`,
  `#channels`, identities.
- **One command declaration** generates the interactive parser, `/help`, the
  completion list, and the piped-script parser, so they cannot diverge.

### 7.4 Command surface

```
/help  /quit  /onboard  /json  /color  /profile  /doctor  /db  /clear

/identity   list · show · create · import · export · share-code · use · delete · delivery
/radio      list · connect · disconnect · info · status · auth · watch · settings
            · reboot · factory-reset · bootloader
/link       list · add · remove · test          # tcp://host:port · usb:/dev/ttyACM0
/chat       list · open · show · history · search · read · unread · archive
/send       --to <@contact|#channel|id> [--file F] "body"
/contact    list · add · show · rename · accept · reject · block · unblock · remove · import-qr
/channel    list · create · show · join · leave · share · delete · set-key
/packet     list · show · hex · send-raw · export · prune · stats
/path       trace --message <id>
/sync       history · prune · vacuum · backup · restore
/db         info · migrate · vacuum · export · restore · check
/repeater   on · off · status
/dev        sim · replay · fake-radio · golden
```

### 7.5 Localization-ready from day one (D31)

Adding languages later must not be a refactor, so the string boundary is designed
in now:

- **No user-facing string literal lives in logic.** Every visible text is a
  message id resolved through one catalog, e.g. `t("chat.opened", channel, n)`.
- **Templates are whole, with positional placeholders** — `"chat.opened" =
  "{channel} · {count} online"` — never concatenation, so word order is
  translatable. No English in code, therefore no i18n bug later.
- **Built-in `en` catalog in code**; additional catalogs as `.properties` files
  in `$XDG_DATA_HOME/meshpigeon/locale/<lang>.properties`, resolved from
  `LANG`/`LC_ALL`, with graceful fallback to `en`.
- **Numbers, dates, relative times and plurals go through one locale-aware
  formatter** over `kotlinx-datetime` (`{count} message` / `{count} messages` as
  separate keys, not an `if` in logic).
- **Protocol- and user-supplied data is never translated** — channel names,
  contact names and packet contents pass through verbatim.
- **A CI gate greps for string literals inside logic functions**, in the same
  spirit as the import-gate (§4.5), so the invariant holds instead of decaying.
- Clikt's own `--help` prose stays library-English; if that ever matters, we
  render our own `/help` from the command declaration (which we already generate).

### 7.6 Files, config, profiles

```
$XDG_CONFIG_HOME/meshpigeon/config.toml      # display prefs, defaults
$XDG_DATA_HOME/meshpigeon/meshpigeon.db      # ONE database (WAL)
$XDG_DATA_HOME/meshpigeon/keys/master.key    # 0600, seals secrets at rest
$XDG_DATA_HOME/meshpigeon/history            # session history
$XDG_DATA_HOME/meshpigeon/locale/*.properties
--profile work → meshpigeon-work/…            # /profile work, or MP_PROFILE=work
MP_HOME=…  → overrides XDG dirs               # how tests get a throwaway DB
```

### 7.7 Module layout

```
meshpigeon-cli/
├─ cli-core/      command declaration, output models, JSON envelope, exit statuses
├─ cli-render/    Mordant renderers for the output models
├─ cli-i18n/      message catalog, templates, locale resolution, formatters
├─ cli-journal/   session state machine: prompt, completion, terminal arbiter
└─ cli-app/       native entry point (main) + JVM entry point
```

Every command produces a **typed output model** with two renderers: human
(Mordant) and machine (`/json`). That is why the two cannot drift, and why
snapshot tests are trivial.

### 7.8 Distribution (D17 — `mp`)

GitHub Releases per OS/arch + checksums, a Homebrew tap, and a Nix flake (you
already use Nix for `meshcore-cli`). The JVM jar is published but not the
advertised install — the native binary is the product.

### 7.9 Link coverage (D13/D15)

| Link | `mp` | Core |
|---|---|---|
| TCP | ✅ primary | P1 |
| USB-CDC | ✅ secondary | P2 (posix cinterop + jvm/android streams) |
| BLE | ❌ never in the CLI | `core-link-ble` ships android + apple source sets only, so Android/iOS can add it without the CLI |

Which links are *offered* for a device is decided by what `DeviceInfo` reports
(§5.4), so a board without `CAPABILITY_WIFI_STA` never shows a TCP option.

---

## 8. Data model

### 8.1 Principles

1. **One DB file per client, one schema everywhere.** If `mp` can open the
   Android app's DB, G3 holds.
2. **`protocol` is a table, not a TEXT convention.** `identity.protocol_id`
   references `protocol(id)`; the row is upserted at startup by the protocol
   module that implements it. Referential integrity, and a place for protocol
   metadata.
3. **Protocol-specific data gets real columns, never a BLOB.** A protocol
   declares its extra tables (§8.2); nothing opaque, nothing stringly-typed, and
   nothing that has to be parsed at read time.
4. **BLOBs only where a genuine, unparsed byte array belongs**: public keys,
   sealed private keys and channel keys, sealed PINs, raw packets, hashes/tags.
5. **Identity is the namespace root.**
6. **Packets are protocol- and radio-scoped, never identity-scoped.**
7. **Everything prunable; nothing precious.**
8. **All data lives in the DB (D11)**, including private keys — sealed at rest.

### 8.2 Schema (SQLDelight `.sq`)

**Conventions, so new tables have no decisions left to make:**

- All timestamps are **UTC epoch milliseconds**, columns named `*_at`.
- TEXT primary keys are **UUIDv7** — time-ordered, so B-tree inserts stay local and
  `(created_at, id)` ordering is stable. Generated in `core-common`
  (`Id.newId()`), never by the DB. Random v4 keys would fragment every index.
- **BLOBs only for genuine byte arrays** we address or compare but never parse.
- **Evolving enumerations are TEXT** validated in Kotlin at one mapping point, so
  adding a value is a code change, not a migration. **Closed sets that will never
  grow carry CHECK constraints** (direction, decode_state, tx_outcome, kinds) —
  because SQLite cannot alter a CHECK without a table rebuild.
- **`PRAGMA user_version` is the single source of truth** for the schema version
  (SQLDelight owns it). `db_meta` holds app-level metadata only — never a second
  schema-version counter.
- `PRAGMA foreign_keys = ON` **on every connection**, and it is silently ignored
  inside a transaction — set it in the open hook, not in a migration.
- `packet` uses `AUTOINCREMENT` deliberately: `message.packet_id` references its
  rowid, so ids must never be reused.

```sql
──────── protocol registry ────────────────────────────────────────────────

CREATE TABLE protocol (
  id            TEXT PRIMARY KEY NOT NULL,      -- 'meshpigeon.meshcore.v1'
  display_name  TEXT NOT NULL,
  version       INTEGER NOT NULL,
  is_builtin    INTEGER NOT NULL DEFAULT 1,
  registered_at INTEGER NOT NULL
);

-- Escape hatch for genuinely free-form protocol metadata. Typed protocol data
-- belongs in the protocol's own table, not here.
CREATE TABLE protocol_property (
  protocol_id TEXT NOT NULL REFERENCES protocol(id) ON DELETE CASCADE,
  key   TEXT NOT NULL, value TEXT NOT NULL,
  PRIMARY KEY (protocol_id, key)
) WITHOUT ROWID;

──────── identity ──────────────────────────────────────────────────────────

CREATE TABLE identity (
  id            TEXT PRIMARY KEY NOT NULL,       -- UUIDv7
  protocol_id   TEXT NOT NULL REFERENCES protocol(id),
  display_name  TEXT NOT NULL,
  created_at    INTEGER NOT NULL,
  last_used_at  INTEGER,
  is_active     INTEGER NOT NULL DEFAULT 0 CHECK (is_active IN (0,1)),
  -- byte arrays (the only BLOBs here): keys and the on-air address
  public_key         BLOB NOT NULL,             -- Ed25519, both protocols
  address            BLOB NOT NULL,             -- MeshCore addr hash | Meshtastic node num (LE)
  private_key_sealed BLOB NOT NULL,             -- sealed at rest
  advert_policy  TEXT NOT NULL DEFAULT 'manual',
  last_advert_at INTEGER
);
-- "Exactly one active identity" is a database invariant, not a code convention.
CREATE UNIQUE INDEX identity_one_active ON identity(is_active) WHERE is_active = 1;
CREATE UNIQUE INDEX identity_address ON identity(protocol_id, address);

-- Protocol-owned extension tables: real columns, real types, no BLOBs (D30).
CREATE TABLE meshcore_identity_ext (
  identity_id TEXT PRIMARY KEY REFERENCES identity(id) ON DELETE CASCADE,
  flags INTEGER NOT NULL DEFAULT 0,
  has_shared_secret INTEGER NOT NULL DEFAULT 0 CHECK (has_shared_secret IN (0,1))
) WITHOUT ROWID;

CREATE TABLE meshtastic_identity_ext (
  identity_id TEXT PRIMARY KEY REFERENCES identity(id) ON DELETE CASCADE,
  node_num INTEGER NOT NULL UNIQUE,             -- client-assigned
  long_name TEXT, short_name TEXT, role TEXT,
  is_licensed INTEGER NOT NULL DEFAULT 0 CHECK (is_licensed IN (0,1))
) WITHOUT ROWID;

──────── radio settings: protocol default → identity override → observed ────

CREATE TABLE protocol_radio_settings (
  protocol_id TEXT PRIMARY KEY NOT NULL REFERENCES protocol(id) ON DELETE CASCADE,
  freq_hz INTEGER NOT NULL CHECK (freq_hz > 0),
  bandwidth_hz INTEGER NOT NULL CHECK (bandwidth_hz > 0),
  sf INTEGER NOT NULL CHECK (sf BETWEEN 5 AND 12),
  coding_rate INTEGER NOT NULL CHECK (coding_rate BETWEEN 5 AND 8),
  power_dbm INTEGER NOT NULL CHECK (power_dbm BETWEEN 0 AND 22),
  defaults_version INTEGER NOT NULL DEFAULT 1,
  updated_at INTEGER NOT NULL
) WITHOUT ROWID;

CREATE TABLE identity_radio_settings (
  identity_id TEXT PRIMARY KEY NOT NULL REFERENCES identity(id) ON DELETE CASCADE,
  freq_hz INTEGER NOT NULL, bandwidth_hz INTEGER NOT NULL,
  sf INTEGER NOT NULL, coding_rate INTEGER NOT NULL, power_dbm INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
) WITHOUT ROWID;

CREATE TABLE radio_observed_tuning (
  radio_id TEXT PRIMARY KEY NOT NULL REFERENCES radio_device(id) ON DELETE CASCADE,
  freq_hz INTEGER NOT NULL, bandwidth_hz INTEGER NOT NULL, sf INTEGER NOT NULL,
  coding_rate INTEGER NOT NULL, power_dbm INTEGER NOT NULL,
  config_epoch INTEGER NOT NULL DEFAULT 0, observed_at INTEGER NOT NULL
) WITHOUT ROWID;

──────── radios (what DeviceInfo reported, and nothing more) ────────────────

CREATE TABLE radio_device (
  id            TEXT PRIMARY KEY NOT NULL,
  persistent_id TEXT NOT NULL UNIQUE,           -- BLE MAC / usb path / host:port
  name          TEXT NOT NULL,
  board_name    TEXT,                           -- as reported; never a chip part number
  fw_version    TEXT, spec_version INTEGER,
  pin_sealed    BLOB,                           -- the device PIN, sealed
  -- DeviceInfo.capabilities as columns; unknown capabilities are ignored
  supports_wifi INTEGER NOT NULL DEFAULT 0 CHECK (supports_wifi IN (0,1)),
  supports_ble  INTEGER NOT NULL DEFAULT 0 CHECK (supports_ble  IN (0,1)),
  supports_usb  INTEGER NOT NULL DEFAULT 0 CHECK (supports_usb  IN (0,1)),
  has_battery   INTEGER NOT NULL DEFAULT 0 CHECK (has_battery   IN (0,1)),
  first_seen_at INTEGER NOT NULL, last_seen_at INTEGER,
  last_battery_mv INTEGER, last_noise_floor_dbm INTEGER
);

-- One device, several ways to reach it: typed columns, not one address string.
CREATE TABLE radio_link (
  id       TEXT PRIMARY KEY NOT NULL,
  radio_id TEXT NOT NULL REFERENCES radio_device(id) ON DELETE CASCADE,
  kind     TEXT NOT NULL CHECK (kind IN ('tcp','usb','ble')),
  host     TEXT, port       INTEGER,            -- tcp
  device_path TEXT, baud    INTEGER,            -- usb
  endpoint_id  TEXT,                            -- ble
  pref_order INTEGER NOT NULL DEFAULT 0,
  is_preferred INTEGER NOT NULL DEFAULT 0 CHECK (is_preferred IN (0,1)),
  last_connected_at INTEGER, last_error TEXT,
  -- Decisive because `kind` is NOT NULL: a mismatch yields FALSE, never NULL,
  -- and a NULL CHECK result would *pass* in SQLite.
  CHECK (
    (kind = 'tcp' AND host IS NOT NULL AND port IS NOT NULL) OR
    (kind = 'usb' AND device_path IS NOT NULL) OR
    (kind = 'ble' AND endpoint_id IS NOT NULL)
  )
);
CREATE INDEX radio_link_radio ON radio_link(radio_id);
CREATE UNIQUE INDEX radio_link_one_preferred ON radio_link(radio_id) WHERE is_preferred = 1;

CREATE TABLE radio_sync_cursor (
  radio_id TEXT NOT NULL REFERENCES radio_device(id) ON DELETE CASCADE,
  link_id  TEXT NOT NULL REFERENCES radio_link(id)  ON DELETE CASCADE,
  seq INTEGER NOT NULL DEFAULT 0, seen_at INTEGER NOT NULL,
  PRIMARY KEY (radio_id, link_id)
) WITHOUT ROWID;

──────── packet journal (bounded, prunable) ───────────────────────────────

CREATE TABLE packet (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,   -- referenced by message.packet_id
  radio_id     TEXT REFERENCES radio_device(id) ON DELETE SET NULL,
  protocol_id  TEXT REFERENCES protocol(id),        -- NULL until a protocol claims it
  device_seq   INTEGER,                             -- the radio's store seq (cursor)
  direction    TEXT NOT NULL CHECK (direction IN ('rx','tx')),
  raw          BLOB NOT NULL,                       -- raw on-air bytes
  rssi INTEGER, snr REAL, radio_uptime_ms INTEGER,
  observed_at  INTEGER NOT NULL,                    -- wall clock via ClockMapper
  seen_count   INTEGER NOT NULL DEFAULT 1,
  decode_state TEXT NOT NULL
    CHECK (decode_state IN ('undecoded','decoded','foreign','error')),
  -- typed decode summary: no JSON, nothing to parse
  decoded_kind       TEXT,
  decoded_src        BLOB,                          -- sender address (byte array)
  decoded_channel_id TEXT REFERENCES channel(id) ON DELETE SET NULL,
  tx_outcome TEXT CHECK (tx_outcome IS NULL OR tx_outcome IN ('accepted','sent','failed'))
);
CREATE INDEX packet_recent    ON packet(observed_at DESC, id DESC);  -- stable ordering + prune scan
CREATE INDEX packet_radio     ON packet(radio_id, device_seq);
CREATE INDEX packet_protocol  ON packet(protocol_id, observed_at DESC);

CREATE TABLE packet_tag (
  identity_id TEXT NOT NULL REFERENCES identity(id) ON DELETE CASCADE,
  tag BLOB NOT NULL, first_seen_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL,
  PRIMARY KEY (identity_id, tag)
) WITHOUT ROWID;

──────── contacts / channels / conversations / messages ───────────────────

CREATE TABLE contact (
  id TEXT PRIMARY KEY NOT NULL,
  identity_id TEXT NOT NULL REFERENCES identity(id) ON DELETE CASCADE,
  address BLOB NOT NULL, display_name TEXT NOT NULL,
  source TEXT NOT NULL CHECK (source IN ('advert','qr','link','manual','seen')),
  first_seen_at INTEGER NOT NULL, last_seen_at INTEGER,
  is_accepted INTEGER NOT NULL DEFAULT 1 CHECK (is_accepted IN (0,1)),
  blocked_at INTEGER, note TEXT,
  last_lat REAL, last_lon REAL, last_alt_m INTEGER, last_location_at INTEGER,
  UNIQUE (identity_id, address)
);
CREATE INDEX contact_identity ON contact(identity_id, display_name);

CREATE TABLE meshtastic_contact_ext (
  contact_id TEXT PRIMARY KEY REFERENCES contact(id) ON DELETE CASCADE,
  node_num INTEGER NOT NULL, hw_model TEXT, role TEXT,
  is_licensed INTEGER NOT NULL DEFAULT 0 CHECK (is_licensed IN (0,1))
) WITHOUT ROWID;

CREATE TABLE channel (
  id TEXT PRIMARY KEY NOT NULL,
  identity_id TEXT NOT NULL REFERENCES identity(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('public','hashtag','private','direct')),
  display_name TEXT NOT NULL,
  key_sealed BLOB,                                -- sealed channel key / PSK
  channel_index INTEGER,                          -- typed protocol-meaningful slot
  created_at INTEGER NOT NULL,
  is_pinned INTEGER NOT NULL DEFAULT 0 CHECK (is_pinned IN (0,1)),
  is_muted  INTEGER NOT NULL DEFAULT 0 CHECK (is_muted  IN (0,1)),
  notify_mode TEXT NOT NULL DEFAULT 'default'
);

CREATE TABLE conversation (
  id TEXT PRIMARY KEY NOT NULL,
  identity_id TEXT NOT NULL REFERENCES identity(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('dm','channel','group','trace')),
  contact_id TEXT REFERENCES contact(id) ON DELETE CASCADE,
  channel_id TEXT REFERENCES channel(id) ON DELETE CASCADE,
  unread_count INTEGER NOT NULL DEFAULT 0 CHECK (unread_count >= 0),
  is_pinned INTEGER NOT NULL DEFAULT 0 CHECK (is_pinned IN (0,1)),
  is_muted  INTEGER NOT NULL DEFAULT 0 CHECK (is_muted  IN (0,1)),
  notify_mode TEXT NOT NULL DEFAULT 'default',
  last_message_at INTEGER,
  is_request INTEGER NOT NULL DEFAULT 0 CHECK (is_request IN (0,1)),
  created_at INTEGER NOT NULL
);
-- COALESCE avoids the SQLite NULL-in-UNIQUE gotcha (NULLs compare distinct).
CREATE UNIQUE INDEX conversation_uniq
  ON conversation(identity_id, kind, COALESCE(contact_id,''), COALESCE(channel_id,''));
CREATE INDEX conversation_recent ON conversation(identity_id, last_message_at DESC);

CREATE TABLE message (
  id TEXT PRIMARY KEY NOT NULL,
  conversation_id TEXT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
  identity_id TEXT NOT NULL REFERENCES identity(id) ON DELETE CASCADE,
  direction TEXT NOT NULL CHECK (direction IN ('in','out')),
  kind TEXT NOT NULL CHECK (kind IN
    ('text','location','contact_card','channel_share','reaction','system')),
  body TEXT,                                      -- normalized text (searchable)
  payload BLOB,                                   -- canonical payload bytes, pre-encryption
  sender_contact_id TEXT REFERENCES contact(id) ON DELETE SET NULL,
  sender_address BLOB, sender_name TEXT,
  created_at INTEGER NOT NULL,
  delivery_state TEXT,                            -- internal; mapped per protocol for display
  rssi INTEGER, snr REAL, hop_count INTEGER, rtt_ms INTEGER,
  ack_key BLOB,                                   -- O(1) ACK → message matching
  content_hash BLOB,
  reply_to_id TEXT REFERENCES message(id) ON DELETE SET NULL,  -- never cascade
  packet_id INTEGER REFERENCES packet(id) ON DELETE SET NULL,
  edited_at INTEGER, deleted_at INTEGER
);
CREATE INDEX message_conversation ON message(conversation_id, created_at, id);
CREATE INDEX message_recent       ON message(identity_id, created_at DESC);
CREATE INDEX message_reply_to     ON message(reply_to_id) WHERE reply_to_id IS NOT NULL;
CREATE UNIQUE INDEX message_ack_key ON message(ack_key) WHERE ack_key IS NOT NULL;
CREATE INDEX message_content_hash  ON message(content_hash) WHERE content_hash IS NOT NULL;

CREATE TABLE outbox (
  id TEXT PRIMARY KEY NOT NULL,
  identity_id TEXT NOT NULL REFERENCES identity(id) ON DELETE CASCADE,
  conversation_id TEXT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
  message_id TEXT NOT NULL REFERENCES message(id) ON DELETE CASCADE,
  radio_id TEXT REFERENCES radio_device(id) ON DELETE SET NULL,
  packet_raw BLOB NOT NULL,                       -- ack_key lives on the message, not here
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at INTEGER NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('pending','inflight','acked','failed')),
  hop_limit INTEGER, is_direct INTEGER,
  created_at INTEGER NOT NULL, last_error TEXT
);
CREATE INDEX outbox_due ON outbox(state, next_attempt_at);

-- Declared, protocol-driven delivery knobs (D7/D26): generic columns, so this
-- table never grows a MeshCore- or Meshtastic-shaped column.
CREATE TABLE delivery_policy (
  identity_id     TEXT NOT NULL REFERENCES identity(id) ON DELETE CASCADE,
  conversation_id TEXT NOT NULL DEFAULT '',       -- '' = identity-wide scope
  option_id TEXT NOT NULL, value TEXT NOT NULL, updated_at INTEGER NOT NULL,
  PRIMARY KEY (identity_id, conversation_id, option_id)
) WITHOUT ROWID;

──────── app state ─────────────────────────────────────────────────────────

-- Scalars only; nested structures get a column or a table, never JSON.
CREATE TABLE setting (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL) WITHOUT ROWID;
-- keys: created_at · created_by_app_version · search_backend ('fts5'|'like')
CREATE TABLE db_meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL) WITHOUT ROWID;

-- Search: a standard (self-contained) FTS5 table kept in sync by triggers.
-- Self-contained rather than external-content so a bug can never desynchronise
-- the index from the table, and identity_id UNINDEXED avoids a join per query.
CREATE VIRTUAL TABLE message_fts USING fts5(
  body, sender_name, identity_id UNINDEXED,
  tokenize = 'unicode61 remove_diacritics 2'
);
CREATE TRIGGER message_fts_ai AFTER INSERT ON message BEGIN
  INSERT INTO message_fts(rowid, body, sender_name, identity_id)
  VALUES (new.rowid, COALESCE(new.body,''), COALESCE(new.sender_name,''), new.identity_id);
END;
CREATE TRIGGER message_fts_ad AFTER DELETE ON message BEGIN
  DELETE FROM message_fts WHERE rowid = old.rowid;
END;
CREATE TRIGGER message_fts_au AFTER UPDATE ON message BEGIN
  DELETE FROM message_fts WHERE rowid = old.rowid;
  INSERT INTO message_fts(rowid, body, sender_name, identity_id)
  VALUES (new.rowid, COALESCE(new.body,''), COALESCE(new.sender_name,''), new.identity_id);
END;
```

**Denormalisation, stated as invariants** (kept deliberately, so nobody "fixes"
them later):

| Column | Why denormalised | Invariant |
|---|---|---|
| `message.identity_id` | lets the recent/search index cover global queries without a join | always equals `conversation.identity_id`; set in the same transaction |
| `conversation.last_message_at`, `unread_count` | the chat list reads them directly | maintained only by `core-storage`, in the same transaction as the message write |
| `radio_observed_tuning` | the device's actual state vs. what we want | refreshed on every successful `GetRadioSettings` |

We deliberately do **not** denormalise `conversation.last_message_id` — the latest
message per conversation is one indexed query (`message_conversation`), and a
cached pointer is state that can drift.

**Deferred until measured** (each is a real lever, none is free):
`WITHOUT ROWID` on the `message`/`packet` tables (needs FTS + rowid thought),
SQLite `STRICT` tables (needs ≥3.37 everywhere — which rules out Android's
bundled SQLite below API 33, so it is all-or-nothing across platforms), generated
columns, and `ANALYZE`. Revisit at P7 with a profile, not before.

**Connection setup, every connection, in the open hook:**

```sql
PRAGMA journal_mode = WAL;          -- persistent, set once
PRAGMA synchronous  = NORMAL;
PRAGMA foreign_keys = ON;           -- ignored inside a transaction
PRAGMA busy_timeout = 5000;
PRAGMA temp_store   = MEMORY;
-- auto_vacuum must be chosen before the first table exists:
PRAGMA auto_vacuum = INCREMENTAL;   -- lets /packet prune reclaim space cheaply
```


### 8.3 Radio-settings resolution (D10)

```
effectiveTuning(identity) =
    identity_radio_settings[identity]              -- user override, if any
  ?: protocol_radio_settings[protocol(identity)]   -- what that protocol needs
  ?: radio_observed_tuning[device]                 -- last known device state (read-only)
```

`core-service` applies it **on identity switch and on connect** when it differs
from `radio_observed_tuning`, through `SetRadioSettings`, guarded by the
firmware's own rules (`config_epoch` conflict detection, the five-minute
first-owner lock, `BUSY` while TX is keying).

**No region presets at the platform level.** Each protocol module seeds its own
`protocol_radio_settings` rows at install: MeshCore's region frequency plan,
Meshtastic's modem presets (which are just `freq/bw/sf/cr` tuples). Neither leaks
upward; `/radio settings edit` changes any of them.

### 8.4 Answers to your data-model questions

| Question | Answer |
|---|---|
| Radio settings: identity or independent? | **Identity-scoped via protocol defaults + optional identity override** (§8.3), with the device's actual tuning cached separately |
| Prune the packet table? | **Yes, first-class** — `/packet prune --older-than 30d --max-rows … --max-bytes …`, plus an automatic retention policy applied on connect. `packet_tag` has its own shorter lifetime |
| Packets not identity-scoped? | **Confirmed and encoded**: `packet` has no `identity_id` |
| Per-identity contact lists? | **Confirmed**: `contact.identity_id` |
| One DB for all protocols? | **Confirmed**: `protocol` is a real table, `protocol_id` FKs everywhere, no protocol-shaped columns in shared tables |

### 8.5 Concurrency, migrations, secrets

- `journal_mode=WAL`, `synchronous=NORMAL`, `foreign_keys=ON`, `busy_timeout=5000`.
- **One process (D10b)**: a lock file; a second `mp` fails with a clear message
  naming the PID. All writes go through one coroutine actor
  (`Dispatchers.Default.limitedParallelism(1)`) — exactly one writer.
- Migrations: SQLDelight `.sqm`, forward-only, one per reviewed commit;
  `/db migrate [--dry-run]`; CI migrates a golden DB from **every prior release**.
- **Secrets (D11)**: everything in the DB; keys sealed via an `expect`
  `SecretStore`. Native CLI: a `0600` key file beside the DB. Android: Keystore.
  iOS: Keychain. Web: WebCrypto (later).

---

### 8.6 Migration playbook (the anti-churn rules)

The goal is a schema we can change for years without drama. These are the rules
that get us there, and they are review criteria:

1. **Forward-only, one logical change per `.sqm`.** Never edit a shipped
   migration, never renumber. SQLDelight tracks `PRAGMA user_version`.
2. **Backfill in the same migration as the change that needs it.** A new column
   that existing rows require gets `UPDATE` in the same file, so there is never a
   version where the code and the data disagree.
3. **Expand / contract for anything destructive** — and spread it over releases:
   1. *Expand*: add the new column/table, nullable, with an index. Dual-write.
   2. *Backfill* in batches if the table is large.
   3. *Switch reads* to the new column; dual-write continues.
   4. *Contract* (a later release): stop dual-writing, drop the old column.
   This means a migration is always additive and always reversible by downgrading
   the app, which matters because the Android app and `mp` will be at different
   versions for a while.
4. **A golden DB per release, and CI migrates every one of them to head.** If a
   migration only works against a fresh DB, it is broken.
5. **Back up before migrating.** `/db migrate` writes `/db backup` first and
   refuses to proceed if the backup fails (`--no-backup` to override).
6. **Verify after every migration**: `PRAGMA foreign_key_check` (must return
   nothing) and `PRAGMA integrity_check`. Both run in the CI migration test, and
   `/db check` runs them on demand.
7. **Remember `PRAGMA foreign_keys` is ignored inside a transaction.** If a
   bulk migration must temporarily drop constraints, toggle it outside the
   transaction, and `foreign_key_check` immediately afterwards.
8. **Prefer adding a column over changing a type.** SQLite type affinity means a
   `TEXT`→`INTEGER` change needs a table rebuild; add-a-column + backfill +
   drop-old does not.
9. **Every table gets its FKs and indexes in the migration that creates it.**
   SQLite cannot add a constraint to an existing table without a rebuild, so a
   forgotten index is permanent debt.
10. **Document any `CHECK` you add.** SQLite cannot `ALTER` a CHECK, so the list
    of "closed sets that will never grow" is a deliberate, reviewed decision —
    which is why evolving enumerations are plain `TEXT` validated in Kotlin.


## 9. Service layer

```kotlin
class MeshPigeonService(
    private val store: Store,
    private val protocols: ProtocolRegistry,      // id → MeshProtocol (+ their ext tables)
    private val clock: Clock, private val log: Logger,
) {
    val session: StateFlow<SessionState>         // disconnected|connecting|ready|reconnecting|error
    val connection: StateFlow<ConnectionSnapshot>// device, tuning, auth, store stats

    suspend fun connect(target: RadioTargetRef, secret: Secret?): ConnectionSnapshot
    suspend fun disconnect()
    fun chats(): Flow<List<ChatSummary>>
    fun messages(conversationId: Id, limit: Int = 200): Flow<List<MessageView>>
    fun packets(filter: PacketFilter): Flow<List<PacketView>>
    fun contacts(identityId: Id): Flow<List<ContactView>>
    suspend fun send(request: SendRequest): SendReceipt
    suspend fun syncHistory(): SyncReport
    suspend fun prune(retention: RetentionPolicy): PruneReport
    fun events(): Flow<MeshEvent>
}
```

- `MeshEvent` is a sealed hierarchy (`MessageReceived`, `DeliveryChanged`,
  `PacketObserved`, `ContactSeen`, `LinkChanged`, `RadioRetuned`, `Error`) — the
  single stream `mp` and every GUI render. This is why the CLI finds bugs the GUI
  hides.
- Long-lived work (outbox pump, retry timers, history sync, uptime anchoring)
  lives in `core-service` inside a `CoroutineScope` the client supplies: a
  foreground service on Android, `main` in `mp`.
- **The CLI holds no business logic.** Any decision belongs in `core-service` or
  `core-domain`.

---

## 10. Phase plan

### P0 — Scaffolding (1 day)
Both repos exist ✅. `GUIDING-PRINCIPLES.md`, `CONTRIBUTING.md`, `LICENSE`
(**GPL-3.0**), this plan set → `docs/plans/`. Gradle KMP skeleton
(linuxX64/Arm64, macosX64/Arm64, mingwX64, jvm), CI green, `mp` running with
`--version`, Clikt + the session skeleton, `/json` mode, exit statuses,
`cli-i18n` catalog + templates from the start.
**Exit:** `./gradlew build` green; a native `mp` opens a session; a tag publishes
to Central staging.

### P1 — Radio API + TCP (2–3 days)
Submodule + descriptor drift CI (D2 a+b); Wire codegen for `proto-gen`; COBS +
CRC-16 in common code with **native** tests; `RadioApi` over the protobuf API
(ping, info, status, auth, tuning, send, fetch, purge); session with id
correlation, async pushes, reconnect backoff, `spec_version` gate, uptime
anchoring; `RadioLink` + `core-link-tcp`; `/radio …` in the session.
**Exit:** `mp` against `meshpigeon-sim --port 8801` prints real `DeviceInfo`,
tunes the radio, and fetches its packet store.

### P2 — USB-CDC + storage skeleton (2–3 days)
POSIX `termios` cinterop + JVM/Android stream impls, validated **day one**
against a real T1000-E / XIAO WIO. SQLDelight setup; land §8.2; migrations v1;
`SecretStore`; `/db`, `/link`, `/radio settings`.
**Exit:** `mp` reads a physical radio over USB and records its tuning.

### P3 — Crypto + MeshCore protocol (3–4 days)
**Crypto spike first (D23)**: build the candidates for linuxX64 + macosArm64,
pick on measured byte-parity against MeshCore C + `openhop_core`. Then SHA-2,
HMAC-SHA256, Ed25519, MeshCore's X25519-style exchange, AES-128-ECB. Build
`core-meshcore`; port the **vectors** mined from `meshpigeon-android` (§15).
**Exit:** `commonTest` green on JVM + linuxX64 + macosArm64; a MeshCore-C vector
matches ours byte for byte.

### P4 — Domain + service, MeshCore end-to-end (4–5 days)
`core-domain`, `MeshPigeonService`, outbox pump, ACK/retry FSM, receive pipeline,
packet journal, app-side repeater. Session commands: `/identity *`, `/chat *`,
`/send`, `/contact *`, `/channel *`, `/packet *`.
**Exit:** two `mp` sessions on two sims exchange a real MeshCore message
end-to-end — delivery states, retries, reconnect history sync.

### P5 — The session, polished (3–4 days) — *requirement (D19)*
`cli-journal` state machine, terminal arbiter, raw-mode line editor (native
cinterop / JLine on JVM), slash commands generated from the command declaration,
streaming transcript, `/onboard`, piped-script mode.
**Exit:** `/chat open #test` → type → `/quit` works against a sim mesh; **every
behaviour has a headless test**; a piped script and an interactive session produce
identical side effects.

### P6 — Meshtastic (4–6 days) — droppable (D20)
Day 1: recorded real-frame corpus + cross-check against upstream's decoder. Then
`core-meshtastic` over `org.meshtastic:protobufs`: PSK → channel hash, AES-CTR,
XEdDSA, node-number addressing, modem-preset tuning rows, `meshtastic_*_ext`
tables.
**Exit:** an `mp` identity on protocol `meshtastic` reaches a real Meshtastic
node over a MeshPigeon radio, and upstream's own client reads what we send.

### P7 — Test harness + hardening (3–4 days)
`/dev sim` orchestrating `scripts/mesh-sim.sh` with the loss/dup model;
`scripts/e2e/*.mp` acceptance scripts piped into `mp` (onboarding, first message,
DM, reconnect sync, outbox retry at 100 % loss, tuning-epoch conflict,
purge/prune, backup/restore, identity-switch re-tune); property/fuzz tests;
24 h soak; allocation-free steady-state receive path.

### P8 — Release + polish (2–3 days)
Release pipeline (tag ⇒ Central ⇒ binaries ⇒ GitHub Release ⇒ Homebrew ⇒ Nix).
Destination state of **D2c**: the firmware publishes
`dev.meshpigeon:meshpigeon-proto` and core drops the submodule. Docs:
`getting-started`, `radios`, `session-reference`, `architecture`, `db-schema`,
`troubleshooting`, plus a first extra locale to prove D31.

### P9 — Android migration (separate plan, 5–8 days)
Delete `:core-protocol`/`:core-transport`/`:core-domain`, depend on
`meshpigeon-core`; Room → `core-storage`; ViewModels observe `MeshPigeonService`;
BLE/USB permissions and the foreground service stay in the app; relicense to
GPL-3.0.
**Exit:** the app has zero protocol/storage/radio code; a message sent from `mp`
appears in the app.

### P10 — Web + iOS (later, separate plans)
`meshpigeon-ios`: Compose Multiplatform over the same service, BLE via
CoreBluetooth. `meshpigeon-web`: Kotlin/Wasm, noting that browser BLE is
WebBluetooth-only (Chrome/Edge desktop), so the likely web story is "a machine
running `mp` holds the radio; the browser talks to it over the LAN" — a design
decision in its own right.

---

## 11. Testing & CI

### 11.1 Test pyramid

| Layer | Where | Target |
|---|---|---|
| Pure unit | `commonTest` — codec, crypto, FSM, DB (in-memory driver) | 250+ |
| Golden vectors | `commonTest/resources/vectors/` — bytes from MeshCore C, `openhop_core`, `meshcore_py`, Meshtastic | 40+ |
| Property/fuzz | codec round-trips, COBS fuzz, decoder never throws, migration fuzz | 30+ |
| Protocol contract | `ProtocolContractSuite`, run once per protocol | per protocol |
| Integration (sim) | `jvmTest` + native smoke against `meshpigeon-sim` over TCP and USB | 25+ |
| E2E | `scripts/e2e/*.mp` piped into `mp` against a sim mesh | 15+ |
| DB migration | every prior release's golden DB → migrate → assert | per release |
| Session | `cli-journal` state-machine tests, no TTY; localization: every id resolves in every catalog | all |
| i18n gate | grep for string literals in logic + every message id present in every catalog | continuous |

Common tests run on **JVM and at least one native target** on every PR.

### 11.2 Interop oracles (all on this machine)

`MeshCore/` (C++ firmware) · `openhop_core` · `meshcore_py` · `meshcore-cli`
(third-party reference client — if our messages render correctly in `meshcli`,
the encoding is right) · `meshpigeon-firmware` sim + `scripts/mesh-sim.sh`.
**"An official client reads what we send" is an acceptance criterion from day
one.**

### 11.3 `/doctor`

Checks DB path/schema/permissions, core version, links available (serial ports
enumerated, network reachability), clock skew, and a `ping` round-trip to the
configured radio. Machine-readable output via `/json`; the first thing a bug
report pastes.

### 11.4 CI matrix

| Job | Trigger | What |
|---|---|---|
| `build-all` | PR | JVM + linuxX64/macosArm64/mingwX64 binaries |
| `test-common` | PR | JVM + host-native `commonTest` |
| `buf` | PR | lint + breaking (firmware) · descriptor drift (core) |
| `migration` | PR | golden DB migrations |
| `import-gate` | PR | §4.5 — CLI may only use core's public API; no hardware identifiers |
| `i18n-gate` | PR | every message id resolvable; no literals in logic |
| `e2e-sim` | PR | sim mesh + `scripts/e2e/*.mp` |
| `test-native-all` | nightly | every declared target |
| `interop` | nightly | cross-check vs MeshCore C / `openhop_core` / `meshcore_py` |
| `publish` | tag | Central + release binaries |
| `android-consume` | nightly (P9+) | Android builds against the published artifact |

`kotlin.native.cacheKind=none` with a shared build-cache dir; `-Xexpect-actual-classes` on.

---

## 12. Versioning & compatibility

| Version | Bumps when | Enforced by |
|---|---|---|
| `meshpigeon-core` SemVer | public API break/feature | Central |
| **DB schema version** | any `.sqm` | `db_meta.schema_version`; older clients refuse newer files with a clear message |
| **Proto `spec_version`** | whole-spec break (firmware-owned) | the radio client refuses radios it does not implement |
| **Session output `schema`** | any output-model change | `schema: 1` in `/json` output |
| key/blob formats | never | sealed blobs + ext-table `defaults_version` |

Policy: DB files move forward only; protobufs additive-only; the core never
silently degrades — if something cannot be done, the client says so; anything
touching wire or crypto gets a golden vector **before** a refactor.

---

## 13. Risks

| # | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | Crypto backend isn't byte-compatible with MeshCore | **Critical** — silent air incompatibility | Vectors from MeshCore C + `openhop_core` first (P3); KAT per primitive; the D23 spike compares on parity, not convenience; BouncyCastle kept in **test fixtures** as a JVM oracle |
| R2 | Kotlin/Native compile times wreck the loop | velocity | host-target-only inner loop; all-target nightly; aggressive caching |
| R3 | Wire + KMP surprises | schema friction | proven by Meshtastic's own build, which we mirror |
| R4 | Meshtastic PSK/CTR derivation misunderstood | protocol #2 slips | recorded corpus day 1; P6 independently droppable |
| R5 | GPL-3.0 | distribution | decided (D21): the whole project is GPL-3.0; `meshpigeon-firmware` stays MIT |
| R6 | Android migration balloons | schedule | P9 is a separate plan; `mp` is complete first |
| R7 | POSIX `termios` cinterop fiddly | blocks hardware testing | validate day 1 of P2; JVM path kept as reference |
| R8 | Session editor-vs-async-output arbitration is subtle | flagship screen feels broken | one isolated component, terminal abstracted, tested headlessly |
| R9 | Scope creep toward GUI parity | nothing ships | `mp` defines the feature set; the GUI follows |
| R10 | Localization drifts in late (strings baked into logic) | painful retrofit | id-based catalog + CI gate from P0 |

---

## 14. Decision register

| ID | Question | **Decision** |
|---|---|---|
| D1 | KMP targets | linuxX64/Arm64, macosX64/Arm64, mingwX64, jvm, android; iOS + wasm staged (P10) |
| D2 | Proto ownership | **Firmware owns it.** Core consumes it as a git **submodule pinned to a firmware tag** (no copy) + a **descriptor-set drift CI** check; destination state (P8) is the firmware publishing `dev.meshpigeon:meshpigeon-proto` |
| D3 | Publishing | **Maven Central**, `dev.meshpigeon:*`, `com.vanniktech.maven.publish`; snapshots via the Central Portal snapshots repo |
| D4 | KMP protobuf codegen | **Square Wire 7.x** Gradle plugin; `buf` retained for lint/breaking |
| D5 | KMP SQLite | **SQLDelight 2.4.0** |
| D6 | Crypto | **Layered assembly**: `org.kotlincrypto` (hash/MAC/CSPRNG) + one native provider for Ed25519/X25519/AES, chosen by the D23 spike |
| D7 | Protocol SPI | large shared core + `ProtocolFeatures` (capabilities) + declared `DeliveryOption` knobs + typed `ProtocolExtension` escape hatches; `ProtocolContractSuite` keeps it honest. Users never see mechanics |
| D8 | Meshtastic identity | it has real Ed25519 keypairs; one identity model serves both |
| D9 | CLI libraries | **Clikt 5.1.0 + Mordant 3.1.0 + kotlinx-serialization** |
| D10 | Radio settings | **No region presets.** `protocol_radio_settings` → optional `identity_radio_settings` override → applied on identity switch/connect; `radio_observed_tuning` caches what the device reports |
| D10b | Multi-process DB | **One process**; lock file with a friendly PID error |
| D11 | Secrets | **all data in the DB**, keys sealed via `SecretStore` |
| D12 | Search | **FTS5** + `LIKE` fallback if a platform lacks it |
| D13 | Link order | **TCP first, USB-CDC second, BLE last** |
| D14 | Crypto parity bar | **exact bytes**, verified against MeshCore C / `openhop_core` before P3 exits |
| D15 | BLE in the CLI | **no BLE in the CLI**; `core-link-ble` ships android + apple source sets only |
| D16 | Org | `meshpigeon` — both repos created |
| D17 | Executable | **`mp`** |
| D18 | CLI purity | public core API only, CI import-gate; `core-testing` allowed in tests |
| D19 | Session | **the only interface.** `mp` with no args; `/chat open #test` loads recent messages and streams new ones; typing posts; `/quit` exits after flushing |
| D20 | Meshtastic ordering | after `mp` is complete on MeshCore; droppable |
| D21 | Licensing | **GPL-3.0** for core + cli. Firmware stays MIT; Android relicensed at P9 |
| D22 | `meshpigeon-android` as a base | **reference only**; reuse the code at P9 |
| D23 | Crypto provider | **open.** §6.0. Rec: 2-day spike, default RustCrypto |
| D24 | Meshtastic protobufs | depend on `org.meshtastic:protobufs` (air messages only), Renovate-pinned; never vendor |
| D25 | Multiple radios | **no — one connection per client**, with no accommodation code |
| D26 | Delivery-knob scope | **global per identity**; knobs are data-driven from `ProtocolFeatures` |
| D27 | Identity ownership | **core-owned for both protocols**; the radio has no identity at all |
| D28 | Installing identity on a device | **moot and closed** — the firmware has no identity to configure |
| D29 | Device PIN | stored per device in the DB beside its connection details, sealed; `/pin` overrides |
| D30 | Protocol-specific data in the DB | **Protocol-owned extension tables** — typed columns, FK-enforced, indexable, no BLOBs (`meshtastic_identity_ext(node_num, long_name, short_name, role, is_licensed)`, `meshcore_identity_ext(flags, …)`) — with `protocol_property` reserved for genuinely free-form metadata. All `.sq`/`.sqm` live in `core-storage` (one migration chain); each protocol module owns the Kotlin rows, mappers and docs for its own tables |
| **D31** | **NEW — localization** | **open.** Rec: id-based catalog with positional templates + locale-aware formatters, built in from P0 with a CI gate; ship English first, add a second locale at P8 to prove it |

| **D32** | **NEW — schema hardening levers** | Deferred until profiled (P7): `WITHOUT ROWID` on `message`/`packet`, SQLite `STRICT` tables, generated columns, `ANALYZE`. `STRICT` is all-or-nothing across platforms (needs SQLite ≥3.37, which rules out Android below API 33) |
| **D33** | **NEW — primary key strategy** | **UUIDv7 TEXT keys** for all entities (`packet` keeps `INTEGER PRIMARY KEY AUTOINCREMENT` because `message.packet_id` references its rowid and ids must never be reused). Time-ordered so B-tree inserts stay local and `(created_at, id)` ordering is stable |

---

## 15. What to mine from `meshpigeon-android` (assets, not code)

| Asset | Use |
|---|---|
| `core-protocol/src/test/**`, `CryptoTest` vectors | golden vectors for the crypto parity gate |
| `core-transport/test/TransportSimInteropTest`, `TcpSessionIntegrationTest` | integration scenarios against `meshpigeon-sim` |
| `core-domain/test/mesh/MeshSimHarness.kt` | the loss/dup/bridge model → `/dev sim` |
| `AckTracker` timeouts + retry physics | the MeshCore ACK/retry FSM in `core-meshcore` |
| `ClockMapper` (64-bit non-wrapping uptime → wall clock) | reimplement in `core-common`; both protocols need it |
| `Reactions`, `ShareCodec`, `ShareCards` behaviour | MeshCore payload semantics + test cases |
| `docs/plans/00–11` | the design record; copy into `meshpigeon-core/docs/plans/` as history |
| UI vocabulary ("heard ✓", "share my contact") | the seed catalog for `cli-i18n` |

---

## 16. First 10 working days (concrete)

| Day | Work | Result |
|---|---|---|
| 1 | P0 skeleton, CI, native `mp` session, `/json`, exit statuses, i18n skeleton | a real binary with a real session |
| 2–3 | P1 submodule + Wire codegen + COBS/CRC in common code, tests native | framing is common code and provably so |
| 4–5 | P1 `RadioApi` + session + TCP link + `/radio …` against `meshpigeon-sim` | `mp` talks to a radio over the real protobuf API |
| 6–7 | P2 SQLDelight schema v1 + migrations + `SecretStore` + `/db /link` | one DB, one process, real migrations |
| 8 | P2 USB-CDC on real hardware (validate the `termios` work early) | a physical radio over USB |
| 9–10 | **D23 crypto spike** → pick a provider; start `core-meshcore` primitives | the highest-risk decision made with data |


---

## 17. Context-reset briefing

Everything a fresh session needs that is **not** derivable from this document:
environment, tooling, verified library versions, working commands, oracles on
this machine, conventions, and how this owner likes to work.

### 17.1 Where things are

```
/home/jason/dev/meshcore/
├─ plans/2026-10-04-meshpigeon-core-and-cli-plan.md   ← THIS DOCUMENT
├─ meshpigeon-firmware/     existing, git repo, origin github.com/meshpigeon/meshpigeon-firmware
├─ meshpigeon-android/      existing, git repo (reference only, D22)
├─ MeshCore/                MeshCore C++ firmware (interop oracle)
├─ MeshCore-dev/  meshcore-open/
├─ openhop_core/  openhop_modem/  openhop_repeater/  openhop_RepeaterUI/
├─ meshcore_py/             Python MeshCore client (interop oracle)
├─ meshcore-cli/            Python reference CLI (third-party interop check)
├─ rf-sweep-core-kit/  mc-rf-sweep/  companion_stock/  filter_onair_tests/
├─ remote/…                 other unrelated projects
```

Two new **empty** repos exist and have no clones yet:
`github.com/meshpigeon/meshpigeon-core`, `github.com/meshpigeon/meshpigeon-cli`
(public, created with `gh`). Clone them as siblings of the above.

**Only two open decisions remain**: **D23** (2-day crypto spike, approved — just
do it) and **D31** (which second locale at P8; any non-English locale proves the
path). Everything else is settled; do not re-litigate.

### 17.2 Toolchain and verified facts

- **Gradle 8.14.5**, **Kotlin 2.2.21**, AGP 8.13.2 — the versions
  `meshpigeon-android` already pins. One toolchain to learn, not two.
- `gh` is authenticated as `jhuebert` (scopes `repo`, `workflow`; **no `read:org`**).
  Do not run `gh auth refresh` — it needs a human.
- **Network access works** (GitHub API, Maven Central, crates.io reachable) — use
  it to verify library versions rather than guessing.
- Verified current versions to pin:

| | version | note |
|---|---|---|
| SQLDelight | **2.4.0** | JVM/native/Android/JS/wasm |
| Clikt | **5.1.0** | multiplatform, has `CliktCommand.test()` |
| Mordant | **3.1.0** | Clikt's renderer; tables, colors, `LiveDisplay` |
| Square Wire | **7.1.0** (gradle plugin `7.0.3`) | Kotlin codegen, all KMP targets |
| `org.meshtastic:protobufs` | **2.7.23** | Wire-generated KMP air messages, GPL-3.0, Central |
| kotlinx-io | 0.9.1 | |
| vanniktech maven-publish | 0.37.0 | Central Portal; used by Meshtastic successfully |

- **kotlinx-protobuf is a dead end**: its runtime is not on Maven Central.
- **`org.kotlincrypto`** (109★ hash, 28★ MACs, 38★ random) = pure-KMP SHA-2/HMAC/
  CSPRNG but **no cipher and no signature**. `dev.whyoleg.cryptography` = libsodium
  on every target but **no AES-ECB/CTR**. Hence the D23 assembly.

### 17.3 Working commands (verify before relying on them)

```sh
# firmware simulator — our test harness
cd /home/jason/dev/meshcore/meshpigeon-firmware
pio run -e sim                       # → .pio/build/sim/program
.pio/build/sim/program --port 8801   # one radio; also --loss N --dup N --traffic-ms N --store BYTES --fake-wifi
scripts/mesh-sim.sh 3 8801           # 3 sims on 8801..8803

# protocol generation in the firmware (authoritative schema)
scripts/regen-protos.sh              # regenerates committed nanopb C; --check is the CI gate
```

The firmware's protobufs: `meshpigeon-firmware/protobufs/meshpigeon/{envelope,
device,radio}.proto`, `package meshpigeon`, `java_package = dev.meshpigeon.proto`,
`buf.yaml` with `PACKAGE_VERSION_SUFFIX` deliberately excepted (keeps nanopb
identifiers short — don't "fix" that).

### 17.4 Reference implementation worth reading before writing code

`meshpigeon-android` — mine these, do not port them:
`core-protocol/src/test/**` (golden vectors), `core-transport/test/*SimInterop*`,
`core-domain/test/mesh/MeshSimHarness.kt` (loss/dup model), `AckTracker`
(physics-based retries), `ClockMapper`, `Reactions`/`ShareCodec`/`ShareCards`,
`docs/plans/00–11` (the original design record).

**Meshtastic's own KMP protobuf build** is the reference for Wire + KMP +
Central: `github.com/meshtastic/protobufs` → `packages/kmp/build.gradle.kts`.
It shows the exact Wire settings worth copying (`oneofMode = "flat"`,
`buildersOnly = true`, `makeImmutableCopies = true`, `-Xjdk-release`,
`automaticRelease = true`).

### 17.5 Conventions to follow

- **Conventional commits**, short-lived branches off `main`, trunk-based — this
  is what the existing meshpigeon history already does (`feat:`, `fix:`,
  `chore:`, `docs:`, `refactor:`).
- **MIT for `meshpigeon-firmware` stays MIT; new repos are GPL-3.0** (D21).
- Minimal diffs, match surrounding style, no drive-by refactors, no dependency
  upgrades smuggled into a feature (this owner's standing rule for any repo).
- Every merged behaviour needs a test named for the requirement; wire/crypto
  changes need a golden vector **before** the refactor.
- `docs/plans/` in each repo; the plan set travels with the code.
- Accessibility/consistency of vocabulary: "heard ✓", "delivered ✓✓", never
  "ACK", "flood route", "payload", "PSK" in user-facing text.

### 17.6 How this owner works (so the next session doesn't get it wrong)

- **Bring data, not adjectives.** When recommending a library or an approach,
  verify it (Maven Central, GitHub, upstream source) and cite what was found.
  Twice now a "most commonly used" question was answered by *looking*, and both
  times the answer differed from the obvious guess (Wire instead of
  kotlinx-protobuf; Meshtastic having real identities).
- **Correct plainly when the architecture is wrong.** v2–v5 of this plan were
  corrected by the owner: core speaks only to `meshpigeon-firmware` (no dialect
  layer, no stock-Meshtastic device API); no hardware knowledge below the
  protobuf API; no command-line options (the session is the interface); no BLOBs
  beyond genuine byte arrays. **Assume the owner knows this domain better than
  the plan does.**
- **Prefer the smallest thing that works; avoid speculative generality.** Multiple
  simultaneous radio connections were explicitly deferred with "don't implement it,
  but don't contort the design either". Same for region presets (deleted) and
  per-conversation delivery overrides (left as a nullable column, not built).
- **Data-driven over branchy.** Differences between protocols should be declared
  data (`ProtocolFeatures`, `DeliveryOption`, extension tables) so adding a
  protocol or a setting touches no client code.
- **Design for the GUI, build with the CLI.** `meshpigeon-android` is not being
  rewritten from the core's model; the CLI is the first client and the
  reference for the feature set.
- Questions get answered with a recommendation attached; "all defaults" is a
  valid answer and the plan should then say so.

### 17.7 First actions for a fresh session

1. Clone both repos as siblings of `/home/jason/dev/meshcore/`, add
   `GUIDING-PRINCIPLES.md`, `CONTRIBUTING.md`, `LICENSE` (**GPL-3.0**), and copy
   this plan into `meshpigeon-core/docs/plans/`.
2. P0: Gradle KMP skeleton (linuxX64/Arm64, macosX64/Arm64, mingwX64, jvm), CI,
   `mp` session skeleton with `/json`, exit statuses, `cli-i18n` skeleton.
3. P1: firmware proto submodule at a tag + descriptor-set drift CI, Wire codegen,
   COBS/CRC-16 in `commonMain` with **native** tests, `RadioApi` + `RadioSession`,
   `core-link-tcp`, `/radio …` in the session against `meshpigeon-sim`.
4. Do not build anything for P2+ until P1's exit criterion is met.
