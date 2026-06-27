#!/usr/bin/env bash
# Start the continuous live data generator against the PascalShop source (manual CDC demo).
# Inserts ~14 rows/second across all 10 tables so you can watch counts climb live.
#
#   ./live-generate.sh                 # generate into PascalShop (default)
#   ./live-generate.sh TestShop        # generate into a different database
#
# Runs in the foreground (blocks). Stop with Ctrl+C, or from another shell:
#   ./live-generate-stop.sh
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
SA='Str0ngP@ssw0rd!'
DB="${1:-PascalShop}"

echo "Generating ~14 rows/sec into $DB. Stop with ./live-generate-stop.sh (or Ctrl+C)…"
docker exec -i mssql-test-source /opt/mssql-tools18/bin/sqlcmd \
  -S localhost -U sa -P "$SA" -C -d "$DB" < "$DIR/live-generate.sql"
