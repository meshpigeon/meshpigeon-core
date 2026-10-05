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

Phases P0 and P1 of `docs/plans/2026-10-04-meshpigeon-core-and-cli-plan.md`:
the radio API, the framing, the session, and the TCP transport, with `mp`
driving a real `meshpigeon-sim` end to end. Storage, identity, protocols and the
service layer follow in P2–P4.

| Module | What it is |
|---|---|
| `proto-gen` | Wire-generated Kotlin for the radio API, from the pinned `proto` submodule |
| `core-common` | ids, hex/base64, `Outcome`, `Clock`, `Logger`, COBS, CRC-16, `FrameReader` |
| `core-transport` | `RadioApi` (the firmware's contract as domain types), `RadioSession`, the `RadioLink` SPI |
| `core-link-tcp` | the Wi-Fi TCP transport — one source file per native platform |
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
```

Kotlin 2.2.21 · Gradle 8.14.5 · Linux, macOS and the JVM today; Windows
returns with the rest of the Windows phase (see the note in
`core-link-tcp/build.gradle.kts`).

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

## Licence

GPL-3.0. `meshpigeon-firmware` is a separate MIT program and links nothing here.
