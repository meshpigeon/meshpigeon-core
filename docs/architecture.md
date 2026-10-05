# Architecture

The shape, and why each seam is where it is. The design record — phases,
decisions, the plan itself — is in [`plans/`](plans/).

## One radio API

```
meshpigeon-firmware's protobuf API  (protobufs/meshpigeon/*.proto)
        │  pinned as a git submodule, generated into proto-gen
        ▼
core-transport · RadioApi        ← domain types only
        ▲
        │  RadioLink (a byte pipe)      core-link-tcp  (one file per platform)
        ▼
   a radio, on a network
```

* **The generated types never escape `core-transport`.** The API returns
  `DeviceInfo`, `Tuning`, `StoredPacket`, `LinkStatus`, `RadioError` — so no
  protocol module, no database row and no interface ever imports a generated
  class. `ProtoMapping.kt` is the only file where the two vocabularies meet.
* **Hardware stops at the API.** `DeviceInfo` and `Status` are the entire
  picture of a board: a name, capabilities, health, a tuning, store statistics.
  A board without Wi-Fi never offers a TCP link, because the firmware said so.
* **The API and the pipe are separate modules.** The verbs, correlation,
  authentication and store semantics are `core-transport`; the byte pipe is
  `core-link-*`. That is what makes a USB radio and a Wi-Fi radio behave
  identically — and what makes USB-CDC a P2 module rather than a rewrite.

## Framing is common code

```
wire frame := COBS( envelope ‖ crc16_LE(CRC-16/CCITT-FALSE) ) 0x00
```

Implemented in `core-common` and tested on the JVM **and** natively. It used to
live in the Android app on `java.io.ByteArrayOutputStream`, which meant exactly
one platform ever ran it and no one could fuzz it.

Two details the tests exist for:

* **A body that is not a frame is dropped, and the stream self-heals at the next
  delimiter.** The ESP-IDF Wi-Fi driver writes its own log text to the same
  port, and it must not take the frame after it with it.
* **A runaway body is dropped at the delimiter, never buffered.** A peer that
  never sends one must not be able to make the reader grow.

## The session

`RadioSession` owns the parts of a radio that are protocol rather than plumbing:
correlation ids, async pushes, the specification-version gate, uptime anchoring,
reconnect backoff, and the mapping to domain types. All of its state lives on one
dispatcher, so the pending-request table needs no locks.

```
connect() ─► open link ─► read loop ─► handshake ─► Ready
                 │                          │
                 │                          ├─ spec_version gate  (refuse what we do not implement)
                 │                          ├─ uptime anchor      (packets → wall clock)
                 │                          └─ auth               (a wrong PIN is AUTH_REQUIRED)
                 │
                 └─ a drop re-opens the link with backoff + jitter
```

## Testing without hardware

`core-testing` is a **published** module, not a test-only trick: `FakeRadio`
answers like the firmware — the same framing, the same protobuf API, the same
store semantics — and misbehaves only when a test scripts it (a lost
acknowledgement, a refused request, log noise on the wire, a dropped link). A
client's tests can therefore drive a real session end to end with no simulator
and no bench, and the fakes double as a contract test of the framing in both
directions.

## Module dependency rule

```
common → crypto → transport → domain → protocol impls → storage → service
                             with core-testing (fakes) standing in for any of them
```

`scripts/check-module-deps.sh` parses the project dependencies out of each
module's build file and fails on an edge that is not on that list, so a new
module cannot quietly reach sideways. It runs as part of `check`.

## One source file per native platform (in `core-link-tcp`)

The C types around a socket do not agree: a size argument is a `ULong` on POSIX
and a `UInt` on Winsock, the address struct is a plain field on one and a union on
the other, and a pointer index takes a different width again. Kotlin refuses a
generic numeric conversion it cannot prove equal across a group of platforms, so
the socket has one file per platform rather than one shared between them. Per
platform each conversion is a plain assignment.

Two things that bit, and are now pinned by tests in
`core-link-tcp/src/commonTest`:

* **The address is held in network order.** `in_addr.s_addr` is a `UInt`, and on
  the little-endian targets this project ships, storing the obvious value leaves
  `01 00 00 7f` in memory where the kernel expects `7f 00 00 01`. The symptom is
  a connect that times out against an address nobody is listening on.
* **The struct outlives the call.** A `memScoped` allocation is gone the moment
  the block returns, and the kernel reads the address during `connect()`.
