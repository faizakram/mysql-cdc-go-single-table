-- ========================================================
-- Enable CDC on All Tables in Database
-- ========================================================
-- This script enables CDC on all user tables in the current database
-- Run this once after enabling CDC at database level

USE YourDatabaseName;  -- Change to your database name
GO

-- ========================================================
-- Step 1: Enable CDC at Database Level (if not already enabled)
-- ========================================================
IF (SELECT is_cdc_enabled FROM sys.databases WHERE name = DB_NAME()) = 0
BEGIN
    PRINT 'Enabling CDC at database level...';
    EXEC sys.sp_cdc_enable_db;
    PRINT 'CDC enabled at database level.';
END
ELSE
BEGIN
    PRINT 'CDC is already enabled at database level.';
END
GO

-- ========================================================
-- Step 2: Enable CDC on All User Tables
-- ========================================================
DECLARE @TableSchema NVARCHAR(128);
DECLARE @TableName NVARCHAR(128);
DECLARE @SQL NVARCHAR(MAX);

PRINT '========================================================';
PRINT 'Enabling CDC on all user tables...';
PRINT '========================================================';

-- Cursor to iterate through all user tables that don't have CDC enabled
DECLARE table_cursor CURSOR FOR
SELECT 
    s.name AS SchemaName,
    t.name AS TableName
FROM 
    sys.tables t
    INNER JOIN sys.schemas s ON t.schema_id = s.schema_id
WHERE 
    t.is_tracked_by_cdc = 0  -- Not yet CDC-enabled
    AND t.type = 'U'          -- User tables only
    AND s.name NOT IN ('cdc') -- Exclude CDC system tables
ORDER BY 
    s.name, t.name;

OPEN table_cursor;
FETCH NEXT FROM table_cursor INTO @TableSchema, @TableName;

WHILE @@FETCH_STATUS = 0
BEGIN
    BEGIN TRY
        PRINT 'Enabling CDC on [' + @TableSchema + '].[' + @TableName + ']...';
        
        EXEC sys.sp_cdc_enable_table
            @source_schema = @TableSchema,
            @source_name = @TableName,
            @role_name = NULL,
            @supports_net_changes = 1;
        
        PRINT '  ✓ Success';
    END TRY
    BEGIN CATCH
        PRINT '  ✗ Failed: ' + ERROR_MESSAGE();
    END CATCH
    
    FETCH NEXT FROM table_cursor INTO @TableSchema, @TableName;
END

CLOSE table_cursor;
DEALLOCATE table_cursor;

GO

-- ========================================================
-- Step 3: Verify CDC Status
-- ========================================================
PRINT '';
PRINT '========================================================';
PRINT 'CDC Status Summary';
PRINT '========================================================';

-- Database CDC status
SELECT 
    name AS DatabaseName,
    is_cdc_enabled AS CDC_Enabled_On_Database
FROM 
    sys.databases 
WHERE 
    name = DB_NAME();

PRINT '';
PRINT 'CDC-Enabled Tables:';
PRINT '----------------------------------------';

-- List all CDC-enabled tables
SELECT 
    s.name AS SchemaName,
    t.name AS TableName,
    t.is_tracked_by_cdc AS CDC_Enabled
FROM 
    sys.tables t
    INNER JOIN sys.schemas s ON t.schema_id = s.schema_id
WHERE 
    t.is_tracked_by_cdc = 1
ORDER BY 
    s.name, t.name;

PRINT '';
PRINT '========================================================';
PRINT 'Total CDC-enabled tables: ';
SELECT COUNT(*) AS TotalCDCTables FROM sys.tables WHERE is_tracked_by_cdc = 1;
PRINT '========================================================';

GO
