#!/usr/bin/env bash
# Gracefully stop the live data generator by clearing its control flag.
#   ./live-generate-stop.sh            # stop the generator on PascalShop (default)
#   ./live-generate-stop.sh TestShop
set -euo pipefail
SA='Str0ngP@ssw0rd!'
DB="${1:-PascalShop}"
docker exec mssql-test-source /opt/mssql-tools18/bin/sqlcmd \
  -S localhost -U sa -P "$SA" -C -d "$DB" \
  -Q "IF OBJECT_ID('dbo._GenControl') IS NOT NULL UPDATE dbo._GenControl SET Running = 0 WHERE Id = 1;"
echo "Stop signalled — the generator finishes its current tick and exits within ~1s."
