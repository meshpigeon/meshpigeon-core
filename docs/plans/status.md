# Plan status

The plan this repository implements:
[`2026-10-04-meshpigeon-core-and-cli-plan.md`](2026-10-04-meshpigeon-core-and-cli-plan.md)
(v6). This file records what is built, what was decided differently, and why.

## Where the plan stands

| Phase | Status |
|---|---|
| P0 scaffolding | done — KMP skeleton (linuxX64/Arm64, macosX64/Arm64, jvm), CI, `meshpigeon-core-testing`, publishing coordinates (verified consumable from a clean external build; the Central deploy itself needs repository secrets), `docs/` |
| P1 radio API + TCP | done — submodule + drift check, Wire codegen, COBS/CRC in common code with native tests, `RadioApi`/`RadioSession`, `core-link-tcp`, and `mp` driving a real `meshpigeon-sim` end to end: real `DeviceInfo`, a real re-tune, and the packet store read back and purged |
| P2 USB-CDC + storage | **USB-CDC done** — `termios` on Linux and macOS, streams on the JVM, the Android seam, a pty test for the cinterop and `mp` talking to `meshpigeon-sim` over a serial port. Still to come: SQLDelight schema §8.2, migrations v1, `SecretStore`, `/db` |
| P3 crypto + MeshCore | not started (D23 spike first) |
| P4 domain + service | not started |
| P5 the session, polished | not started — the *loop* and the command vocabulary exist since P0/P1; the line editor and the streaming screen are P5 |
| P6 Meshtastic | not started, droppable |
| P7 harness + hardening | partially — `meshpigeon-core-testing` is the harness; `scripts/e2e-sim.sh` is the first e2e script |
| P8 release + polish | not started |

## Decisions taken while building

| # | Question | Decision | Why |
|---|---|---|---|
| **D2b** | How does the pinned schema stay honest? | `scripts/check-proto-drift.sh` compares **descriptor sets** (not file diffs) with the firmware's `main` in CI | A comment or a reformat never trips it; a moved field number always does. There is no tag in the firmware yet, so the submodule is pinned to a **commit** — equally immutable, and one line to bump |
| **D4′** | Wire version | **6.4.7**, not 7.x | Wire 7.x publishes Kotlin/Native klibs built with **Kotlin 2.3.21** (`abi_version=2.3.0`); a 2.2.x toolchain cannot read them ("KLIB resolver: Could not find …"). 6.4.x is the last line built with 2.2.21, the toolchain `meshpigeon-android` and this repository share. Revisit when Kotlin moves |
| **D4″** | Which output shape? | Flat oneofs (the default), immutable copies of repeated fields | `buildersOnly = true` is what Meshtastic uses; flat constructors read better against a schema that is mostly oneofs, and `copy()` stays available |
| **D4‴** | The TCP socket's source sets | **One file per native platform** (linuxMain, macosMain) | Kotlin refuses a generic numeric conversion it cannot prove equal across a group of platforms, and socket C types differ (size `ULong` vs `UInt`, address field vs union, pointer index width). Per platform each is a plain assignment. Windows follows with its own file in the Windows phase |
| **D10c** | Does the core read the tuning on connect? | Yes — one `GetRadioSettings` during the handshake | The tuning is the first question a person asks a radio, and a client that connects and shows nothing is a worse first impression than one extra request |
| **D34** | What does a dropped send outcome do if it beats its ticket? | Held in a small bounded map and consumed when the ticket registers | The firmware answers a send with the accepted ack and pushes the outcome later, but nothing promises the gap is long; losing the outcome means timing out on a send that succeeded |
| **D35** | `TcpRadioLink.inbound()` | Exactly one consumer, the session | A shared flow with two collectors splits the bytes between them, and the session sees half of every frame. A link that also drains the flow to track its own state silently steals the traffic |
| **D36** | Is `/json on`'s own output JSON? | Yes — a command that changes rendering declares `affectsRendering` | Otherwise "the mode changed" is printed in the mode you just left, which is the first thing a script sees and always surprises |
| **D37** | TCP on Windows (P1) | Deferred to the Windows phase | Not a shortcut so much as the same per-platform split as D4‴, and the Windows work (USB, packaging) arrives then anyway. `mp` still builds for Windows; it has no TCP link until then |
| **D38** | How is the published coordinate named? | Stated in each module's `mavenPublishing { coordinates(...) }` | The Kotlin plugin names every publication after the *module directory*, per-target ones included, so `core-common` would have shipped as `dev.meshpigeon:core-common-linuxx64`. `coordinates()` is the one call that renames the whole set. Verified by consuming `dev.meshpigeon:meshpigeon-core:0.1.0-SNAPSHOT` from a clean external build |
| **D42** | How is the USB link's `termios` structured? | `alloc` gives back the struct; the C functions take a pointer to it, and an untyped `reinterpret()` into a `CPointer<termios>` spells that. A *typed* `reinterpret<T>()` reads the struct back by value instead — the same trap the TCP socket hit, now written down in both files |
| **D43** | How does the read loop wait? | `poll` with a 100 ms timeout, not a blocking `read` | A blocking read in another thread does not wake on close, and on Linux the descriptor can already have been reused. `poll` also sees `POLLHUP`, which is how an unplug is distinguished from "nothing yet" |
| **D44** | What configures the line? | `cfmakeraw` + 8N1 + `IGNPAR` + `CLOCAL\|CREAD`, and `O_NOCTTY` + `O_NONBLOCK` on open | Canonical mode would hold every byte until a newline, and a radio that reports without one would look silent. `IGNPAR` keeps a mid-byte unplug from looking like framing corruption. `O_NOCTTY` stops the first write from raising `SIGTTOU` |
| **D45** | Can the USB link be tested without a radio? | Yes — a pty pair is a real serial device with a real line discipline | `TermiosPtyTest` opens one through the link, so `open`/`tcgetattr`/`cfmakeraw`/`tcsetattr`/`poll`/`read` are all checked against the kernel. What still needs hardware is the cable, the enumerating radio and a baud rate a real bridge negotiates |
| **D46** | Where does the JVM's serial port live? | `jvmMain`, over `java.io` streams — and a public `StreamSerialPort` | Android has no device node: `UsbDeviceConnection` gives endpoint streams, which is exactly this class. Native targets keep `termios` because that is what a node needs |
| **D39** | The aggregate artifact has no code | It carries one documented `MeshPigeonCore` marker object | A module with no sources produces no klib, and its per-target publications fail at `generateMetadataFileFor…`. The marker is also the one symbol that says "this client is on meshpigeon-core", which is worth having |

## Deviations from the plan, and why

* **The aggregate artifact is `dev.meshpigeon:meshpigeon-core`**
  (plan §4.3) and lives in the `core-aggregate` module, with
  `dev.meshpigeon:meshpigeon-core-<module>` for the parts. `core-testing` is
  deliberately **not** in the aggregate, so a published client cannot ship the
  fakes by accident.
* **Kotlin 2.2.21, not 2.3.x.** The plan pins the toolchain
  `meshpigeon-android` uses ("one toolchain to learn, not two"), which costs the
  library versions in D4′.
* **Wire generates into `dev.meshpigeon.proto`** — the schema's own
  `java_package`, not a renamed one.
