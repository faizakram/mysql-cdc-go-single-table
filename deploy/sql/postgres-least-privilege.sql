-- Least-privilege PostgreSQL account for the JDBC sink + reconciliation (#46).
-- Run as a Postgres superuser/owner of the TARGET database. Do not reuse the `postgres` superuser.
--
-- Replace <TARGET_DB>, <TARGET_SCHEMA>, and the password before running.
-- Connect to <TARGET_DB> first (\c <TARGET_DB>) so schema grants land in the right database.

-- Role: login only, no SUPERUSER / CREATEDB / CREATEROLE.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'migration_app') THEN
        CREATE ROLE migration_app LOGIN PASSWORD 'CHANGE_ME_Strong!Pass1'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
END $$;

GRANT CONNECT ON DATABASE "<TARGET_DB>" TO migration_app;

-- Work only within the target schema. CREATE is needed because the sink with schema.evolution=basic
-- auto-creates/alters tables. Pre-create tables as the DBA and drop CREATE to tighten further (#33).
GRANT USAGE, CREATE ON SCHEMA "<TARGET_SCHEMA>" TO migration_app;

-- DML + SELECT on existing tables/sequences in the schema.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "<TARGET_SCHEMA>" TO migration_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA "<TARGET_SCHEMA>" TO migration_app;

-- Same privileges for tables/sequences the sink creates in future.
ALTER DEFAULT PRIVILEGES IN SCHEMA "<TARGET_SCHEMA>"
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO migration_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA "<TARGET_SCHEMA>"
    GRANT USAGE, SELECT ON SEQUENCES TO migration_app;

-- Defense in depth: no access to other schemas (e.g. the platform's own metadata DB lives elsewhere).
REVOKE ALL ON SCHEMA public FROM migration_app;   -- if the target schema is not `public`
