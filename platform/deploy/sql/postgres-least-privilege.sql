/* =============================================================================
   Least-privilege PostgreSQL account for the migration platform (issue #46).

   Replaces use of the `postgres` superuser. This account can create the target
   tables (the JDBC sink auto-creates them) and read/write rows within ONE target
   schema only. It is NOT a superuser and cannot create databases/roles or touch
   other schemas.

   Used by:
     - the Debezium JDBC sink connector
     - the control plane (connection test, reconciliation)

   Replace <TARGET_DB>, <TARGET_SCHEMA>, and the password before running.
   Run as a database owner / superuser.
   ============================================================================= */

-- 1) Login role (no SUPERUSER / CREATEDB / CREATEROLE)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'migration_app') THEN
        CREATE ROLE migration_app LOGIN PASSWORD 'CHANGE_ME_Strong#Passw0rd';
    END IF;
END$$;

GRANT CONNECT ON DATABASE "<TARGET_DB>" TO migration_app;

-- 2) Target schema: create it if needed and let the app create tables within it.
CREATE SCHEMA IF NOT EXISTS "<TARGET_SCHEMA>";
GRANT USAGE, CREATE ON SCHEMA "<TARGET_SCHEMA>" TO migration_app;

-- 3) DML on existing + future tables in that schema (CDC upsert/delete + validation reads)
GRANT SELECT, INSERT, UPDATE, DELETE
    ON ALL TABLES IN SCHEMA "<TARGET_SCHEMA>" TO migration_app;
GRANT USAGE, SELECT
    ON ALL SEQUENCES IN SCHEMA "<TARGET_SCHEMA>" TO migration_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA "<TARGET_SCHEMA>"
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO migration_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA "<TARGET_SCHEMA>"
    GRANT USAGE, SELECT ON SEQUENCES TO migration_app;

-- The platform metadata DB is separate from the migration target and has its own
-- owner role; do not reuse migration_app there.
