#!/usr/bin/env bash
# Runs workflow/api-snapshot.sh against two builds of the service and diffs the answers: "nothing changes for our
# users" as evidence. Each jar runs in Docker on its own JRE with its own empty Postgres database.
#
#   workflow/api-diff.sh OLD_JAR OLD_JRE_IMAGE NEW_JAR NEW_JRE_IMAGE
#   workflow/api-diff.sh /tmp/cfp-old.jar eclipse-temurin:11-jre target/cfp.jar eclipse-temurin:25-jre
#
# Leaves old.txt and new.txt in $OUT (default /tmp/api-diff). Exit code 1 if they differ.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${OUT:-/tmp/api-diff}"
net=api-diff
mkdir -p "$out"
# Colima only shares $HOME with containers, so the jars are mounted from a copy there.
jars="$HOME/.cache/api-diff"
mkdir -p "$jars"
cp "$1" "$jars/old.jar"
cp "$3" "$jars/new.jar"
old_jar="$jars/old.jar" old_jre="$2"
new_jar="$jars/new.jar" new_jre="$4"

cleanup() { docker rm -f api-diff-db api-diff-old api-diff-new >/dev/null 2>&1 || true; docker network rm "$net" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup
docker network create "$net" >/dev/null
docker run -d --name api-diff-db --network "$net" -e POSTGRES_USER=cfp -e POSTGRES_PASSWORD=cfp -e POSTGRES_DB=cfp \
    postgres:16-alpine >/dev/null
until docker exec api-diff-db pg_isready -U cfp -d cfp >/dev/null 2>&1; do sleep 1; done
sleep 2
docker exec api-diff-db psql -qU cfp -d cfp -c 'CREATE DATABASE cfp_old' -c 'CREATE DATABASE cfp_new'

run() { # name jar jre port
    docker run -d --name "api-diff-$1" --network "$net" -p "$4:8080" -v "$2:/app.jar:ro" \
        -e PGHOST=api-diff-db -e PGDATABASE="cfp_$1" -e PGUSER=cfp -e PGPASSWORD=cfp -e GIT_SHA=compare \
        "$3" java -jar /app.jar >/dev/null
}
run old "$old_jar" "$old_jre" 18081
run new "$new_jar" "$new_jre" 18082
for port in 18081 18082; do
    for _ in $(seq 1 120); do
        curl -fsS "http://localhost:$port/health" >/dev/null 2>&1 && break
        sleep 1
    done
    curl -fsS "http://localhost:$port/health" >/dev/null || { docker logs "api-diff-$([ $port = 18081 ] && echo old || echo new)" | tail -50; exit 2; }
done

"$here/api-snapshot.sh" http://localhost:18081 > "$out/old.txt"
"$here/api-snapshot.sh" http://localhost:18082 > "$out/new.txt"
for port in 18081 18082; do curl -sS "http://localhost:$port/actuator/info"; echo; done > "$out/info.txt"
docker logs api-diff-new > "$out/new.log" 2>&1
diff -u "$out/old.txt" "$out/new.txt"
