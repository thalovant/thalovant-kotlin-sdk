#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "$0")/../.." && pwd)"
node_sdk="${1:?supply built pinned Node SDK directory}"
cd "$root"
./gradlew --no-daemon testClasses --console=plain
peer_log="$(mktemp)"
node "$root/tools/interop/peer.mjs" "$node_sdk" >"$peer_log" 2>&1 &
peer_pid=$!
trap 'status=$?; kill "$peer_pid" 2>/dev/null || true; cat "$peer_log"; rm -f "$peer_log"; exit "$status"' EXIT
endpoint=""
for attempt in {1..100}; do
  endpoint="$(sed -n '1p' "$peer_log")"
  if [[ "$endpoint" == ws://127.0.0.1:* ]]; then break; fi
  if ! kill -0 "$peer_pid" 2>/dev/null; then exit 1; fi
  sleep 0.1
done
[[ "$endpoint" == ws://127.0.0.1:* ]]
./gradlew --no-daemon interopTest -PinteropEndpoint="$endpoint" --console=plain
wait "$peer_pid"
