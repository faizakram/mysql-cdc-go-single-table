#!/usr/bin/env bash
# Start the full Database Migration Platform stack and print a pretty summary of
# every URL, credential and endpoint once it's healthy.
#
#   ./deploy/up.sh            # build + start everything, then show the banner
#   ./deploy/up.sh --no-build # start without rebuilding images
#
# Plain `docker compose -f deploy/docker-compose.full.yml up -d` still works; this
# wrapper just adds the readiness wait and the console banner.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE=(docker compose -f "$DIR/docker-compose.full.yml")
BUILD="--build"; [ "${1:-}" = "--no-build" ] && BUILD=""

ADMIN_USER="${ADMIN_USERNAME:-admin}"
ADMIN_PASS="${ADMIN_PASSWORD:-admin}"

# Colors only when writing to a real terminal.
if [ -t 1 ]; then
  B=$'\033[1m'; DIM=$'\033[2m'; R=$'\033[0m'
  CY=$'\033[36m'; GR=$'\033[32m'; YE=$'\033[33m'; MG=$'\033[35m'
else
  B=""; DIM=""; R=""; CY=""; GR=""; YE=""; MG=""
fi
rule() { printf '%s\n' "${DIM}────────────────────────────────────────────────────────────────────────${R}"; }
row()  { printf "    %s%-20s%s ${GR}%s${R}%s\n" "$CY" "$1" "$R" "$2" "${3:+   ${YE}$3${R}}"; }
head() { printf "\n  %s%s%s\n" "$B$MG" "$1" "$R"; }

echo "Starting the Database Migration Platform…"
${BUILD:+echo "(building images — first run can take a few minutes)"}
"${COMPOSE[@]}" up -d $BUILD >/dev/null

printf "Waiting for the control plane to become healthy"
healthy=0
for _ in $(seq 1 90); do
  if curl -fsS http://localhost:8090/actuator/health >/dev/null 2>&1; then healthy=1; break; fi
  printf '.'; sleep 2
done
printf '\n'

echo
rule
if [ "$healthy" = 1 ]; then
  printf "  %s🚀  Database Migration Platform — up and running%s\n" "$B$GR" "$R"
else
  printf "  %s⏳  Stack started — control plane still warming up (check logs)%s\n" "$B$YE" "$R"
fi
rule

head "APPLICATION"
row "UI"               "http://localhost:8081"                  "$ADMIN_USER / $ADMIN_PASS"
row "API"              "http://localhost:8090/api/v1"
row "API docs"         "http://localhost:8090/swagger-ui.html"
row "Health"           "http://localhost:8090/actuator/health"

head "OBSERVABILITY"
row "Grafana"          "http://localhost:3001"                  "$ADMIN_USER / $ADMIN_PASS"
row "Prometheus"       "http://localhost:9090"
row "Loki (logs)"      "via Grafana → Explore"

head "DATA PLANE"
row "Kafka Connect"    "http://localhost:8083"
row "Metadata DB"      "localhost:5433"                         "db=migration_platform  postgres/postgres"

printf "\n  %sTIP%s  Connect your own source/target databases with host = %shost.docker.internal%s\n" "$B$CY" "$R" "$YE" "$R"
rule
printf "  Stop:    %sdocker compose -f deploy/docker-compose.full.yml down%s\n" "$DIM" "$R"
printf "  Logs:    %sdocker compose -f deploy/docker-compose.full.yml logs -f backend%s\n" "$DIM" "$R"
rule
echo
