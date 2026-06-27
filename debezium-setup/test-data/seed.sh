#!/usr/bin/env bash
# Start the MS SQL test source and apply the schema + data + CDC for both sample databases:
#   TestShop    — snake/lower-ish identifiers (mssql-init.sql)
#   PascalShop  — PascalCase identifiers, for naming-strategy testing (mssql-init-pascal.sql)
# Usage: ./seed.sh
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
SA='Str0ngP@ssw0rd!'

docker compose -f "$DIR/docker-compose.mssql-source.yml" up -d

echo "Waiting for SQL Server, then applying schema/data/CDC for both databases (takes a minute)..."
docker run --rm --network mssql-test_default \
  -e SA="$SA" \
  -v "$DIR/mssql-init.sql:/testshop.sql:ro" \
  -v "$DIR/mssql-init-pascal.sql:/pascalshop.sql:ro" \
  --entrypoint bash \
  mcr.microsoft.com/mssql-tools -c '
    for i in $(seq 1 60); do
      /opt/mssql-tools/bin/sqlcmd -S mssql,1433 -U sa -P "$SA" -Q "SELECT 1" -b >/dev/null 2>&1 \
        && { echo "SQL Server ready"; break; }
      echo "  waiting for server ($i)..."; sleep 3
    done
    echo "--- Seeding TestShop ---"
    /opt/mssql-tools/bin/sqlcmd -S mssql,1433 -U sa -P "$SA" -i /testshop.sql
    echo "--- Seeding PascalShop ---"
    /opt/mssql-tools/bin/sqlcmd -S mssql,1433 -U sa -P "$SA" -i /pascalshop.sql
  '
echo "Done. On localhost:1433 (sa / Str0ngP@ssw0rd!):"
echo "  - TestShop    (standard identifiers)"
echo "  - PascalShop  (PascalCase tables & columns)"
