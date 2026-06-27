#!/usr/bin/env bash
# Start the MS SQL test source and apply the schema + data + CDC.
# Usage: ./seed.sh
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
SA='Str0ngP@ssw0rd!'

docker compose -f "$DIR/docker-compose.mssql-source.yml" up -d

echo "Waiting for SQL Server, then applying schema/data/CDC (this takes a minute)..."
docker run --rm --network mssql-test_default \
  -e SA="$SA" \
  -v "$DIR/mssql-init.sql:/init.sql:ro" \
  --entrypoint bash \
  mcr.microsoft.com/mssql-tools -c '
    for i in $(seq 1 60); do
      /opt/mssql-tools/bin/sqlcmd -S mssql,1433 -U sa -P "$SA" -Q "SELECT 1" -b >/dev/null 2>&1 \
        && { echo "SQL Server ready"; break; }
      echo "  waiting for server ($i)..."; sleep 3
    done
    /opt/mssql-tools/bin/sqlcmd -S mssql,1433 -U sa -P "$SA" -i /init.sql
  '
echo "Done. Source DB = TestShop on localhost:1433 (sa / Str0ngP@ssw0rd!)."
