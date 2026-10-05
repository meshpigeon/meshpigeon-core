# meshpigeon-core

Everything MeshPigeon that is not a user interface: the radio API, its
transports, the framing underneath both, and the pieces every client shares.

```
┌────────────────────────────────────────────────────────────────────────┐
│  clients:  meshpigeon-cli (mp) · meshpigeon-android · web · ios        │
│  rendering, input, platform permissions — nothing else                   │
└───────────────────────────────┬────────────────────────────────────────┘
                                │
        proto-gen · core-common · core-transport · core-link-tcp
        core-testing (published fakes)
                                │
                                ▼  one radio API
              meshpigeon-firmware — a dumb modem with a packet store
```

**The core speaks to exactly one thing:** `meshpigeon-firmware`'s protobuf API.
There is no second dialect, no "generic radio" layer, and no knowledge of any
board below that API. What a radio *is* is whatever `DeviceInfo` says: a board
name, capabilities, health, a tuning. Adding a board is a firmware change.

## Status

Phases P0, P1 and P2 of
`docs/plans/2026-10-04-meshpigeon-core-and-cli-plan.md`: the radio API, the
framing, the session, both of the transports a radio can be reached on — TCP and
USB-CDC — and the one database behind them. Identity, protocols and the service
layer follow in P3–P4; `/db` in the CLI lands with it.

| Module | What it is |
|---|---|
| `proto-gen` | Wire-generated Kotlin for the radio API, from the pinned `proto` submodule |
| `core-common` | ids, hex/base64, `Outcome`, `Clock`, `Logger`, COBS, CRC-16, `FrameReader` |
| `core-transport` | `RadioApi` (the firmware's contract as domain types), `RadioSession`, the `RadioLink` SPI |
| `core-link-tcp` | the Wi-Fi TCP transport — one source file per native platform |
| `core-link-usb` | USB-CDC: `termios` on Linux and macOS, streams on the JVM, and the seam an Android USB host uses |
| `core-domain` | the entities and repository interfaces, with no SQL in sight |
| `core-storage` | the one SQLite database: plan §8.2's schema, the drivers, the radio repository, the one-process lock, the key store |
| `core-testing` | published fakes: a radio that answers like the firmware, a clock, a logger |
| `core-aggregate` | the `dev.meshpigeon:meshpigeon-core` artifact clients depend on |

## Using it

```kotlin
val link = TcpRadioLink(LinkDescriptor.Tcp("10.0.0.5", 5000))
val session = RadioSession(link, parentScope = scope)

val device = session.connect()          // identity, spec gate, auth, tuning
println(device.boardName)               // "XIAO WIO"

val ticket = session.sendRaw(frame)     // returns now; the outcome arrives later
println(ticket.await().success)

session.incoming().collect { event -> /* packets, re-tunes, outcomes */ }
```

`dev.meshpigeon:meshpigeon-core` is the one coordinate to depend on; the
per-module artifacts exist for anyone who needs to be narrower.

## Building

```sh
./gradlew build                     # every target, every test
./gradlew jvmTest linuxX64Test      # the fast loop: JVM and one native target
./gradlew publishToMavenLocal       # every coordinate, exactly as it will ship
```

Kotlin 2.2.21 · Gradle 8.14.5 · Linux, macOS and the JVM today; Windows
returns with the rest of the Windows phase (see the notes in
`core-link-tcp/build.gradle.kts` and `core-link-usb/build.gradle.kts`).

The native SQLite driver links the system library (`libsqlite3` on Linux,
`libsqlite3.dylib` on macOS), so building the native targets wants
`libsqlite3-dev` on Linux; see the note in `core-storage/build.gradle.kts`.

## Publishing

One coordinate to depend on, and the parts beneath it:

| Coordinate | What it is |
|---|---|
| `dev.meshpigeon:meshpigeon-core` | the aggregate — what a client declares |
| `dev.meshpigeon:meshpigeon-core-proto` | the generated radio API |
| `dev.meshpigeon:meshpigeon-core-common` | framing, ids, encodings, clock, logging |
| `dev.meshpigeon:meshpigeon-core-transport` | `RadioApi`, `RadioSession`, `RadioLink` |
| `dev.meshpigeon:meshpigeon-core-link-tcp` | the Wi-Fi transport |
| `dev.meshpigeon:meshpigeon-core-link-usb` | the USB-CDC transport |
| `dev.meshpigeon:meshpigeon-core-domain` | entities and repository interfaces |
| `dev.meshpigeon:meshpigeon-core-storage` | the database |
| `dev.meshpigeon:meshpigeon-core-testing` | the fakes (test scope, never in the aggregate) |

Each has one publication per target (`-jvm`, `-linuxx64`, `-macosarm64`, …); the
coordinate is stated in the module's build file rather than inherited from the
directory name, so a module can move without renaming the library.

Pushing a `v*` tag runs `.github/workflows/publish.yml`, which checks that the
tag *is* the declared version and then deploys every publication. It needs three
repository secrets: `CENTRAL_USERNAME`, `CENTRAL_PASSWORD` and the signing key
(`SIGNING_KEY`, `SIGNING_KEY_ID`, `SIGNING_KEY_PASSWORD`) — until those exist, the
tag workflow is the only part of this that has never run, and
`publishToMavenLocal` is the equivalent check without credentials.

## The radio schema

`proto/` is a git submodule of
[meshpigeon-firmware](https://github.com/meshpigeon/meshpigeon-firmware),
pinned to one commit. The schema is never copied into this repository, so it
cannot rot: `scripts/check-proto-drift.sh` compares the pinned descriptor set
with the firmware's `main` in CI, and a change there shows up as a failed build
rather than as a client that misreads a field.

## Rules the build enforces

* **The module dependency rule** (`scripts/check-module-deps.sh`): knowledge
  flows one way. `core-domain` knows nothing about SQLDelight, a protocol module
  knows nothing about radios, and `core-service` is the only module that knows
  everything.
* **One test, two platforms.** The framing and the session run on the JVM and
  natively, because a JVM-only run would not prove the native one.
* **Platform code is tested against the platform.** `TermiosPtyTest` opens a
  pty through the USB link and checks what the kernel does with it, so the
  cinterop is verified without a radio on the desk. `NativeSecretsAndLockTest`
  checks that the key file and the lock really are `0600` on disk.

## Licence

GPL-3.0. `meshpigeon-firmware` is a separate MIT program and links nothing here.
