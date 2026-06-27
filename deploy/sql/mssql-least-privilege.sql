-- Least-privilege SQL Server account for CDC (#46).
-- Run as a SQL Server admin AFTER the database exists and CDC has been enabled on it
-- (enabling CDC is a one-time DBA action; the runtime account below must NOT be able to do it).
--
-- Replace <SOURCE_DB> and the password before running. Do not reuse `sa`.

USE [master];
GO
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'cdc_app')
    CREATE LOGIN [cdc_app] WITH PASSWORD = N'CHANGE_ME_Strong!Pass1', CHECK_POLICY = ON;
GO

USE [<SOURCE_DB>];
GO
IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = N'cdc_app')
    CREATE USER [cdc_app] FOR LOGIN [cdc_app];
GO

-- Read source tables (for snapshot) — scope to specific tables instead of db_datareader if desired.
ALTER ROLE [db_datareader] ADD MEMBER [cdc_app];
GO

-- Read CDC change data: SELECT + EXECUTE on the cdc schema (capture instances + fn_cdc_* functions).
GRANT SELECT ON SCHEMA :: [cdc] TO [cdc_app];
GRANT EXECUTE ON SCHEMA :: [cdc] TO [cdc_app];
GRANT VIEW DATABASE STATE TO [cdc_app];
GO

-- If a CDC gating role was set via @role_name when enabling capture instances, add the user to it:
--   ALTER ROLE [<cdc_role>] ADD MEMBER [cdc_app];

-- Explicitly ensure NO write / NO CDC-admin capability (db_datareader grants none; do not add
-- db_owner / db_ddladmin / sysadmin). The account cannot run sys.sp_cdc_enable_* (DBA-only).
GO
