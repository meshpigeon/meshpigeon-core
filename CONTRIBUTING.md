# Contributing to meshpigeon-core

## Pull requests

1. **Keep changes minimal and mapped to a stated need.** No drive-by refactors,
   no reformatting of lines you did not have to touch, no dependency upgrades
   smuggled in with a feature.
2. **Every behaviour change adds or extends a test named for the requirement it
   protects.** Wire and crypto changes need a golden vector *before* the
   refactor.
3. **Run the checks before pushing:**

   ```sh
   ./gradlew build          # every target, every test
   ./gradlew jvmTest linuxX64Test   # the fast loop
   ./gradlew checkModuleDependencies
   ```

4. **Kotlin style:** official Kotlin style, 4-space indent, match the voice of
   the file you are in. Comments explain *why* — especially where a workaround
   exists that a reader would otherwise "fix" back in.

## Layer rules (review gates)

- `core-domain` knows nothing about SQLDelight; `core-storage` knows nothing
  about a mesh protocol; a protocol module knows nothing about radios,
  transports or storage; `core-service` is the only module that knows
  everything. The script fails an edge that is not on the list.
- Nothing above `core-transport` knows whether a radio is TCP, USB or BLE.
- No generated protobuf type crosses a module boundary. If a caller needs a
  value, it gets a domain type.
- No hardware identifier below the protobuf API, and none above it either.
- One `expect`/`actual` pair per true platform boundary: sockets, serial, BLE,
  the keystore, the clock. Everything else is common code.

## The radio schema

`proto/` is a git submodule of `meshpigeon-firmware` pinned to one commit. Never
copy a `.proto` into this repository. When the firmware's schema moves:

```sh
git -C proto fetch origin
git -C proto checkout <tag-or-commit>
./gradlew :proto-gen:generateCommonMainProtos
```

CI compares the pinned descriptor set with the firmware's `main`, so the bump is
a reviewed change to one line rather than a silent divergence.

## Conventional commits

`feat:`, `fix:`, `docs:`, `test:`, `chore:`, `refactor:` — one logical change
per commit, on a short-lived branch off `main`.

## Licence

GPL-3.0. (`meshpigeon-firmware` is a separate MIT program and links nothing
here; `meshpigeon-android` is relicensed when it starts linking this.)
