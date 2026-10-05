# Plan status

The plan this repository implements:
[`2026-10-04-meshpigeon-core-and-cli-plan.md`](2026-10-04-meshpigeon-core-and-cli-plan.md)
(v6). This file records what is built, what was decided differently, and why.

## Where the plan stands

| Phase | Status |
|---|---|
| P0 scaffolding | done — KMP skeleton (linuxX64/Arm64, macosX64/Arm64, jvm), CI, `meshpigeon-core-testing`, publishing coordinates, `docs/` |
| P1 radio API + TCP | done — submodule + drift check, Wire codegen, COBS/CRC in common code with native tests, `RadioApi`/`RadioSession`, `core-link-tcp`, and `mp` driving a real `meshpigeon-sim` end to end |
| P2 USB-CDC + storage | next — `posix termios`, SQLDelight schema §8.2, `SecretStore`, `/db` |
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
