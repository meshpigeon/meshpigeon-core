#!/bin/sh
# The radio schema has not drifted from the firmware (D2b).
#
# The submodule under `proto/` is the pinned copy of
# `meshpigeon-firmware/protobufs`, and it is what the generated Kotlin is built
# from. This compares its descriptor set with the firmware's `main`, so a schema
# change there pings us here rather than producing a client that quietly
# misreads a field. Descriptor sets, not file diffs: a comment or a reformat
# never trips it, and a moved field number always does.
#
# Needs `buf` (github.com/bufbuild/buf) on the path, and network access.
set -eu

root="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
firmware="${FIRMWARE_REPO:-https://github.com/meshpigeon/meshpigeon-firmware.git}"
ref="${FIRMWARE_REF:-main}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

command -v buf >/dev/null 2>&1 || {
    echo "buf is not on the path: https://github.com/bufbuild/buf" >&2
    exit 1
}

echo "fetching $firmware@$ref …"
git clone --depth 1 --branch "$ref" "$firmware" "$work/firmware" >/dev/null 2>&1

buf build "$root/proto/protobufs" -o "$work/pinned.binpb"
buf build "$work/firmware/protobufs" -o "$work/main.binpb"

if cmp -s "$work/pinned.binpb" "$work/main.binpb"; then
    echo "proto: the pinned schema matches $ref"
    exit 0
fi

echo "the radio schema on $ref has moved. Review the change, then:" >&2
echo "  git -C proto fetch origin && git -C proto checkout <tag-or-commit>" >&2
echo "  ./gradlew :proto-gen:generateCommonMainProtos" >&2
exit 1