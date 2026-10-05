#!/bin/sh
# The module dependency rule, enforced.
#
#   common -> crypto -> transport -> domain -> protocol impls -> storage -> service
#   with core-testing (fakes) standing in for any of them.
#
# `core-domain` knows nothing about SQLDelight; `core-storage` knows nothing
# about a mesh protocol; a protocol module knows nothing about radios,
# transports or storage; `core-service` is the only module that knows
# everything. A new module that reaches sideways fails here rather than in a
# review that has to notice.
#
# Usage: scripts/check-module-deps.sh [--print]
#   --print  list the declared edges instead of checking them
set -eu

root="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"

# What each module is allowed to depend on. A module missing from this list may
# depend on nothing but itself and `core-common`.
allowed_for() {
    case "$1" in
        core-common) echo "core-crypto" ;;
        core-crypto) echo "core-common" ;;
        proto-gen) echo "" ;;
        core-transport) echo "core-common core-crypto proto-gen" ;;
        core-link-tcp) echo "core-transport core-common core-crypto" ;;
        core-link-usb) echo "core-transport core-common core-crypto" ;;
        core-link-ble) echo "core-transport core-common core-crypto" ;;
        core-domain) echo "core-common core-crypto" ;;
        core-meshcore) echo "core-domain core-crypto core-common" ;;
        core-meshtastic) echo "core-domain core-crypto core-common" ;;
        core-storage) echo "core-domain core-common core-crypto" ;;
        core-service) echo "core-storage core-domain core-transport core-meshcore core-meshtastic core-common core-crypto" ;;
        core-testing) echo "core-common core-transport proto-gen" ;;
        # The aggregate is the one module allowed to name all of them.
        core-aggregate) echo "proto-gen core-common core-transport core-link-tcp core-link-usb core-link-ble core-domain core-meshcore core-meshtastic core-storage core-service core-crypto" ;;
        *) echo "" ;;
    esac
}

status=0
edges=""

for build_file in "$root"/*/build.gradle.kts; do
    module="$(basename "$(dirname "$build_file")")"
    [ -f "$build_file" ] || continue
    allowed=" $(allowed_for "$module") "

    # `project(":core-common")` wherever it appears in the dependencies block.
    for dependency in $(grep -o 'project(":[a-z0-9-]*")' "$build_file" | sed 's/project(":\(.*\)")/\1/' | sort -u); do
        edges="$edges$module -> $dependency
"
        case "$allowed" in
            *" $dependency "*) ;;
            *)
                echo "$module -> $dependency is not an allowed edge" >&2
                status=1
                ;;
        esac
    done
done

if [ "${1:-}" = "--print" ]; then
    printf '%s' "$edges"
    exit 0
fi

[ "$status" -eq 0 ] && echo "module dependencies: ok"
exit "$status"