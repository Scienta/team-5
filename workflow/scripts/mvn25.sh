#!/usr/bin/env bash
# Runs ./mvnw in the official Java 25 Maven image, for machines without a local JDK 25 (production is Java 25 since
# the Spring Boot 4 upgrade). Testcontainers uses the host's Docker through the mounted socket.
#
#   workflow/scripts/mvn25.sh package          # build and run every test
#   workflow/scripts/mvn25.sh test -Dtest=WorkshopRegistrationTest
set -euo pipefail
root="$(git rev-parse --show-toplevel)"
exec docker run --rm --network host -u "$(id -u):$(id -g)" -e HOME=/tmp/home -e MAVEN_CONFIG=/tmp/home/.m2 \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  -v /var/run/docker.sock:/var/run/docker.sock --group-add "$(stat -c %g /var/run/docker.sock)" \
  -v "$HOME/.m2:/tmp/home/.m2" -v "$root:/w" -w /w \
  maven:3.9-eclipse-temurin-25 ./mvnw -B "$@"
