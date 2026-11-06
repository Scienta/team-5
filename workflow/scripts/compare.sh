#!/usr/bin/env bash
# Regression check: does the working tree answer exactly like a reference version (default: origin/main)?
#
#   workflow/scripts/compare.sh [ref]
#
# Builds the reference in a temporary git worktree and the working tree as it is (tests skipped: run them
# separately), starts both against the local Compose Postgres (docker compose up -d), sends both the same
# requests with compare.py, prints every difference, and stops both. Exit code 1 if anything differs.
#
# Java: REF_JAVA_HOME and NEW_JAVA_HOME (default: JAVA_HOME) pick the JDK for each side, so an upgrade can run
# the new version on a newer Java than the reference. Use the production Java (11 as shipped) for the reference.
# Ports: REF_PORT (8082) and NEW_PORT (8081) must be free.
set -euo pipefail

ref="${1:-origin/main}"
root="$(git rev-parse --show-toplevel)"
ref_java="${REF_JAVA_HOME:-${JAVA_HOME:-}}"
new_java="${NEW_JAVA_HOME:-${JAVA_HOME:-}}"
ref_port="${REF_PORT:-8082}"
new_port="${NEW_PORT:-8081}"
work="$(mktemp -d /tmp/cfp-compare.XXXX)"
pids=()

cleanup() {
  for p in "${pids[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done
  git -C "$root" worktree remove --force "$work/ref" 2>/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT

java_bin() { if [ -n "$1" ]; then echo "$1/bin/java"; else echo java; fi; }

for port in "$ref_port" "$new_port"; do
  if curl -s -o /dev/null "localhost:$port"; then echo "Port $port is in use." >&2; exit 2; fi
done

echo "Building the reference ($ref, $(git -C "$root" rev-parse --short "$ref"))..."
git -C "$root" worktree add -q --detach "$work/ref" "$ref"
(cd "$work/ref" && JAVA_HOME="$ref_java" ./mvnw -B -q package -DskipTests)
echo "Building the working tree..."
(cd "$root" && JAVA_HOME="$new_java" ./mvnw -B -q package -DskipTests)
cp "$root/target/cfp.jar" "$work/new.jar"

start() { # jar java port log
  (cd "$work" && exec env -u SPRING_PROFILES_ACTIVE "$2" -jar "$1" --server.port="$3" >"$4" 2>&1) &
  pids+=($!)
}
start "$work/ref/target/cfp.jar" "$(java_bin "$ref_java")" "$ref_port" "$work/ref.log"
start "$work/new.jar" "$(java_bin "$new_java")" "$new_port" "$work/new.log"

for port in "$ref_port" "$new_port"; do
  for _ in $(seq 1 90); do
    curl -fs -o /dev/null "localhost:$port/health" && break
    sleep 1
  done
  curl -fs -o /dev/null "localhost:$port/health" || {
    echo "Port $port didn't come up. Logs:" >&2
    tail -30 "$work"/*.log >&2
    exit 2
  }
done
echo "Reference on :$ref_port ($("$(java_bin "$ref_java")" -version 2>&1 | head -1)), working tree on :$new_port ($("$(java_bin "$new_java")" -version 2>&1 | head -1))"

python3 -I "$root/workflow/scripts/compare.py" "http://localhost:$ref_port" "http://localhost:$new_port"
