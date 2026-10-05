# MeshPigeon Core — Guiding Principles

> **MeshPigeon exists so a stranger to mesh radio can install, connect, and
> message in minutes — offline, forever.**
>
> 1. **One radio API, and it is the firmware's.** `meshpigeon-core` speaks
>    `meshpigeon-firmware`'s protobuf contract and nothing else. No dialect
>    layer, no "generic radio", no path to a stock node's device API.
> 2. **Hardware knowledge stops at that API.** A board is what `DeviceInfo`
>    says it is. No chip part numbers, no HAL, no register access anywhere
>    below it — so a new board is a firmware change and nothing else.
> 3. **Protocol lives in the client; the radio stays dumb and durable.** The
>    firmware holds raw packets and settings. It has no identity, no keys, no
>    protocol, and this library is what supplies those.
> 4. **Hide mechanics; expose outcomes.** "heard ✓", "delivered ✓✓" — never
>    ACK, flood route, payload or PSK in anything a person reads.
> 5. **Generated types never escape.** The API speaks domain types, so no
>    protocol module, no database row and no interface ever imports a generated
>    class.
> 6. **Layers stay separated and the build says so.** The module dependency
>    rule is enforced by a script, not by good intentions.
> 7. **Byte-exactness beats convenience.** Anything touching the wire or a
>    cipher gets a golden vector *before* the refactor. A codec that differs by
>    one bit order interoperates with nothing and fails as "nobody hears me".
> 8. **Offline-first, always useful with no radio and no network.**
> 9. **Minimal diffs; match the surrounding style; every change maps to a
>    stated need.**
> 10. **The data model changes slowly, on purpose.** Forward-only migrations,
>     expand/contract for anything destructive, and a golden database per
>     release that CI migrates to head.

Concrete consequences, enforced in review or in CI:

- `scripts/check-module-deps.sh` fails on an edge the architecture forbids.
- The framing (COBS + CRC-16) is common code with tests on the JVM *and*
  natively; it used to live in one Android app on `java.io`, which meant only
  one platform ever ran it.
- `meshpigeon-core-testing` is a published module so a client's tests can drive
  a radio that is not a radio — and it is deliberately **not** part of the
  aggregate artifact, so nobody ships the fakes by accident.
- Identity is core-owned for every protocol, and the radio never sees it: the
  firmware has no identity to configure.
