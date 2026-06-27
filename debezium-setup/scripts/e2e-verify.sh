#!/usr/bin/env bash
# End-to-end verification (#59): brings up the CDC data plane, drives a migration entirely through
# the platform API, and asserts snapshot + live CDC (insert/update/soft-delete) + reconciliation.
# Codifies the manually-verified run. Requires Docker. Run: bash debezium-setup/scripts/e2e-verify.sh
#   --down   tear everything down at the end
#
# NOTE: not wired into the fast CI (it emulates SQL Server on arm64 — slow); run on demand locally.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DC="docker compose -f $ROOT/debezium-setup/docker-compose.dataplane.yml"
SA='Str0ng!Passw0rd'
API=http://localhost:8090/api/v1
NET=cdc-dataplane_default
fail() { echo "❌ FAIL: $*"; exit 1; }
step() { echo; echo "▶ $*"; }

step "Build SMT + platform jars (if missing)"
[ -f "$ROOT/debezium-setup/custom-smt/target/snake-case-transform-1.0.0.jar" ] || \
  (cd "$ROOT/debezium-setup/custom-smt" && mvn -q -DskipTests package)
JAR=$(ls "$ROOT"/platform/backend/target/migration-platform-backend-*.jar 2>/dev/null | head -1)
[ -n "$JAR" ] || (cd "$ROOT/platform/backend" && mvn -q -DskipTests package) && \
  JAR=$(ls "$ROOT"/platform/backend/target/migration-platform-backend-*.jar | head -1)

step "Bring up data plane"
$DC up -d
echo "waiting for Connect REST..."
for i in $(seq 1 60); do [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8083/connectors)" = 200 ] && break; sleep 3; done
echo "waiting for SQL Server..."
for i in $(seq 1 60); do docker exec cdc-dataplane-sqlserver-1 /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "$SA" -C -Q "SELECT 1" >/dev/null 2>&1 && break; sleep 5; done

step "Seed source + enable CDC"
docker exec -i cdc-dataplane-sqlserver-1 /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "$SA" -C < "$ROOT/debezium-setup/scripts/sample-source.sql" >/dev/null

step "Start platform on the data-plane network"
docker exec cdc-dataplane-postgres-target-1 psql -U postgres -tc "SELECT 1 FROM pg_database WHERE datname='migration_platform'" | grep -q 1 || \
  docker exec cdc-dataplane-postgres-target-1 psql -U postgres -c "CREATE DATABASE migration_platform" >/dev/null
docker rm -f platform-backend >/dev/null 2>&1
docker run -d --name platform-backend --network $NET -p 8090:8090 \
  -e PLATFORM_METADATA_EMBEDDED=false \
  -e METADATA_DB_URL=jdbc:postgresql://postgres-target:5432/migration_platform \
  -e METADATA_DB_USER=postgres -e METADATA_DB_PASSWORD=postgres \
  -e KAFKA_CONNECT_URL=http://connect:8083 -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -v "$JAR:/app.jar" eclipse-temurin:21-jre java -jar /app.jar >/dev/null
for i in $(seq 1 40); do [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8090/actuator/health)" = 200 ] && break; sleep 3; done

step "Drive migration through the platform API"
TOKEN=$(curl -s -X POST $API/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
H="Authorization: Bearer $TOKEN"
SRC=$(curl -s -X POST $API/connections -H "$H" -H 'Content-Type: application/json' -d '{"name":"src","dbType":"SQLSERVER","host":"sqlserver","port":1433,"databaseName":"Employees","username":"sa","password":"'"$SA"'","options":{"encrypt":false}}' | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
TGT=$(curl -s -X POST $API/connections -H "$H" -H 'Content-Type: application/json' -d '{"name":"tgt","dbType":"POSTGRESQL","host":"postgres-target","port":5432,"databaseName":"target_db","username":"postgres","password":"postgres","options":{}}' | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
curl -s -X POST $API/connections/$SRC/test -H "$H" | grep -q '"success":true' || fail "source connection test"
curl -s -X POST $API/connections/$TGT/test -H "$H" | grep -q '"success":true' || fail "target connection test"
PROJ=$(curl -s -X POST $API/projects -H "$H" -H 'Content-Type: application/json' -d '{"name":"e2e","sourceConnectionId":"'"$SRC"'","targetConnectionId":"'"$TGT"'","config":{"topicPrefix":"mssql","tableIncludeList":"dbo.Department,dbo.Employee","snapshotMode":"initial","deleteStrategy":"SOFT","targetSchema":"public","selectedTables":["dbo.Department","dbo.Employee"]}}' | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
JOB=$(curl -s -X POST $API/projects/$PROJ/jobs -H "$H" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
curl -s -X POST $API/jobs/$JOB/start -H "$H" | grep -q '"status"' || fail "job start"

step "Assert snapshot replicated (Department=3, Employee=4)"
PGq() { docker exec cdc-dataplane-postgres-target-1 psql -U postgres -d target_db -tAc "$1" 2>/dev/null; }
for i in $(seq 1 40); do
  [ "$(PGq "SELECT count(*) FROM public.employee" 2>/dev/null)" = 4 ] && [ "$(PGq "SELECT count(*) FROM public.department")" = 3 ] && break; sleep 4; done
[ "$(PGq "SELECT count(*) FROM public.employee")" = 4 ] || fail "snapshot employee count"
[ "$(PGq "SELECT count(*) FROM public.department")" = 3 ] || fail "snapshot department count"

step "Apply live CDC on source"
docker exec -i cdc-dataplane-sqlserver-1 /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "$SA" -C -d Employees -Q "
INSERT INTO dbo.Employee (EmployeeID,FirstName,LastName,DepartmentID,Salary) VALUES (5,N'Eve',N'M',2,105000.00);
UPDATE dbo.Employee SET Salary=99999.99 WHERE EmployeeID=2;
DELETE FROM dbo.Employee WHERE EmployeeID=4;" >/dev/null

step "Assert CDC propagated (insert + update + soft-delete)"
for i in $(seq 1 30); do
  ins=$(PGq "SELECT count(*) FROM public.employee WHERE employee_id=5 AND first_name='Eve'")
  upd=$(PGq "SELECT count(*) FROM public.employee WHERE employee_id=2 AND salary=99999.99")
  del=$(PGq "SELECT count(*) FROM public.employee WHERE employee_id=4 AND __cdc_deleted=true")
  [ "$ins" = 1 ] && [ "$upd" = 1 ] && [ "$del" = 1 ] && break; sleep 3; done
[ "$ins" = 1 ] || fail "CDC insert not propagated"
[ "$upd" = 1 ] || fail "CDC update not propagated"
[ "$del" = 1 ] || fail "CDC soft-delete not propagated"

step "Assert reconciliation (count + checksum) is clean"
for mode in COUNT CHECKSUM; do
  mm=$(curl -s -X POST "$API/projects/$PROJ/reconciliation?mode=$mode" -H "$H" | python3 -c "import sys,json;print(json.load(sys.stdin)['mismatched'])")
  [ "$mm" = 0 ] || fail "$mode reconciliation mismatched=$mm"
done

echo; echo "✅ E2E PASS — snapshot + live CDC (insert/update/soft-delete) + reconciliation all verified through the platform"
if [ "${1:-}" = "--down" ]; then step "Teardown"; docker rm -f platform-backend >/dev/null 2>&1; $DC down -v; fi
