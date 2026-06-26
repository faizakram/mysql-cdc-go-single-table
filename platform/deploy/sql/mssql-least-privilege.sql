/* =============================================================================
   Least-privilege SQL Server account for the migration platform (issue #46).

   Replaces use of `sa`. This runtime account can READ source data + CDC change
   data only. It CANNOT enable CDC, alter schema, or write — those are one-time
   admin actions performed separately by a DBA (sysadmin/db_owner).

   Used by:
     - the Debezium SQL Server source connector
     - the control plane (connection test, schema discovery, reconciliation)

   Replace <SOURCE_DB> and the password before running. Run as an admin login.
   ============================================================================= */

-- 1) Server login (instance level)
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'cdc_app')
    CREATE LOGIN [cdc_app] WITH PASSWORD = N'CHANGE_ME_Strong#Passw0rd', CHECK_POLICY = ON;
GO

USE [<SOURCE_DB>];
GO

-- 2) Database user mapped to the login
IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = N'cdc_app')
    CREATE USER [cdc_app] FOR LOGIN [cdc_app];
GO

-- 3) Read source tables (SELECT on all user tables). To scope tighter, skip this
--    and GRANT SELECT on specific tables/schemas instead.
ALTER ROLE [db_datareader] ADD MEMBER [cdc_app];
GO

-- 4) Read CDC change data + metadata required by the connector
GRANT SELECT  ON SCHEMA::cdc TO [cdc_app];   -- cdc.* change tables
GRANT EXECUTE ON SCHEMA::cdc TO [cdc_app];   -- cdc.fn_cdc_get_all_changes_* TVFs
GRANT VIEW DATABASE STATE     TO [cdc_app];  -- LSN / state functions
GO

/* -----------------------------------------------------------------------------
   IMPORTANT — gating role:
   If CDC was (or will be) enabled per table WITH a gating role, only members of
   that role (or db_owner) can read the change tables. Enable CDC with a role and
   add cdc_app to it, e.g.:

     EXEC sys.sp_cdc_enable_table
          @source_schema = N'dbo', @source_name = N'YourTable',
          @role_name = N'cdc_reader', @supports_net_changes = 1;

     IF DATABASE_PRINCIPAL_ID(N'cdc_reader') IS NULL CREATE ROLE [cdc_reader];
     ALTER ROLE [cdc_reader] ADD MEMBER [cdc_app];

   Enabling CDC itself (sp_cdc_enable_db / sp_cdc_enable_table) requires sysadmin
   (db level) / db_owner and is a deliberate admin step — NOT granted here.
   ----------------------------------------------------------------------------- */
