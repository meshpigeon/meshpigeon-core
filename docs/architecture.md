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

## Two links, one API

`core-link-tcp` and `core-link-usb` both implement `RadioLink` and neither knows
what is above it. The same session drives a radio on a network and a radio in a
USB cable, because the verbs, correlation, authentication and store semantics
live in `core-transport` and only the pipe differs.

The USB half is where the platform boundary is sharpest:

```
dev.ttyACM0 (Linux)          /dev/cu.usbmodem* (macOS)
        │                            │
   termios: open, tcgetattr,   the same, with ULong where Linux
   cfmakeraw, tcsetattr,      has UInt — hence one file each
   poll, read, write
        │                            │
   NativeSerialPort            NativeSerialPort
        └──────────────┬─────────────┘
                       ▼
             UsbRadioLink : RadioLink
```

Two decisions are worth knowing before touching it:

* **`SerialPort` is public, not internal.** On Android a USB host has a claimed
  interface, not a device path: `UsbDeviceConnection` gives bulk-in and bulk-out
  streams. So the port is an interface a client can satisfy, and the JVM ships
  one (`StreamSerialPort`) that the app can hand its own streams to.
* **A pty is a real serial device**, with a real line discipline and real
  `termios` semantics. That is what makes `TermiosPtyTest` possible: the
  cinterop is checked against the kernel without a radio on the desk. What it
  cannot prove is the cable, the enumeration and a baud rate a real bridge
  negotiates — those stay with the hardware.

## The one database

```
radio_device ──1:n──► radio_link          22 tables in all (plan §8.2), and only
      │                   │                the radio half has Kotlin behind it yet
      └── radio_observed_tuning
```

The schema is landed whole and used in pieces. That is deliberate: a migration
that adds twenty tables is a review nobody reads carefully, whereas twenty small
migrations are twenty reviews, and the plan's rule is that a migration is
forward-only and never edited afterwards.

Four decisions carry the weight:

* **`core-domain` has the interface, `core-storage` has the SQL.** A generated
  table class never leaves `core-storage`, which is what lets `RadioRepository`
  live in `core-domain` and lets `core-testing` supply an in-memory one.
* **The pragmas are a connection concern, not a statement.** `foreign_keys=ON`
  is silently ignored inside a transaction and applies per connection, so the
  JVM driver sets it through the connection configuration and the native driver
  applies it as the connection opens. `SqlDelightRadioRepositoryTest` proved the
  alternative: a cascade that does not happen is a cascade nobody notices until
  a radio is deleted with its links still attached.
* **`PRAGMA user_version` is the only schema counter.** The JDBC driver creates
  the schema without writing it, so `create()` writes it explicitly — a database
  at version 0 would otherwise be re-"migrated" forever, silently.
* **One process, by name.** `DatabaseLock` refuses a second session and names
  the PID that holds it. A lock file left by a crash is checked against the
  process table rather than trusted.
* **The native driver takes a database *name*, not a path.** SQLDelight's
  `NativeSqliteDriver` is SQLIter underneath, and SQLIter rejects a name with a
  path separator — it picks the directory itself. That directory is
  `Extended.basePath`, so `DatabaseDriverFactory` splits the path: the basename
  goes to the driver, the directory to `basePath`. The JVM driver takes a path
  directly, which is why only the native side of the `expect` has to do this.
* **Pragmas run through the query path, never `execute`.** On SQLIter, `execute`
  is Android's `execSQL`, which refuses any statement that returns rows — and
  `journal_mode = WAL` and `busy_timeout = 5000` both answer with the value they
  set. JDBC is lenient, so this only bites natively: `applyPragmas` reads and
  writes every pragma as a query, whatever it returns.

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
