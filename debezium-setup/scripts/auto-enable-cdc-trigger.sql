-- ========================================================
-- Auto-Enable CDC on New Tables (DDL Trigger Approach)
-- ========================================================
-- WARNING: This is an advanced approach. Test in development first!
-- This creates a DDL trigger that automatically enables CDC when new tables are created

USE YourDatabaseName;  -- Change to your database name
GO

-- ========================================================
-- Option 1: DDL Trigger (Auto-enable CDC on CREATE TABLE)
-- ========================================================
-- NOTE: SQL Server Agent must be running for CDC to work
-- This trigger fires whenever a new table is created

CREATE OR ALTER TRIGGER trg_AutoEnableCDC_OnTableCreate
ON DATABASE
FOR CREATE_TABLE
AS
BEGIN
    SET NOCOUNT ON;
    
    DECLARE @TableSchema NVARCHAR(128);
    DECLARE @TableName NVARCHAR(128);
    DECLARE @EventData XML;
    
    -- Get the event data
    SET @EventData = EVENTDATA();
    
    -- Extract schema and table name
    SET @TableSchema = @EventData.value('(/EVENT_INSTANCE/SchemaName)[1]', 'NVARCHAR(128)');
    SET @TableName = @EventData.value('(/EVENT_INSTANCE/ObjectName)[1]', 'NVARCHAR(128)');
    
    -- Wait a moment for table to be fully created
    WAITFOR DELAY '00:00:02';
    
    -- Enable CDC on the newly created table
    BEGIN TRY
        PRINT 'Auto-enabling CDC on new table: [' + @TableSchema + '].[' + @TableName + ']';
        
        EXEC sys.sp_cdc_enable_table
            @source_schema = @TableSchema,
            @source_name = @TableName,
            @role_name = NULL,
            @supports_net_changes = 1;
        
        PRINT 'CDC enabled successfully on [' + @TableSchema + '].[' + @TableName + ']';
    END TRY
    BEGIN CATCH
        PRINT 'Failed to enable CDC on [' + @TableSchema + '].[' + @TableName + ']: ' + ERROR_MESSAGE();
    END CATCH
END;
GO

-- ========================================================
-- Verify Trigger is Created
-- ========================================================
SELECT 
    name AS TriggerName,
    type_desc AS TriggerType,
    is_disabled AS IsDisabled
FROM 
    sys.triggers
WHERE 
    parent_class_desc = 'DATABASE'
    AND name = 'trg_AutoEnableCDC_OnTableCreate';
GO

-- ========================================================
-- To DISABLE the auto-enable trigger (if needed):
-- ========================================================
-- DISABLE TRIGGER trg_AutoEnableCDC_OnTableCreate ON DATABASE;
-- GO

-- ========================================================
-- To ENABLE the trigger again:
-- ========================================================
-- ENABLE TRIGGER trg_AutoEnableCDC_OnTableCreate ON DATABASE;
-- GO

-- ========================================================
-- To DROP the trigger:
-- ========================================================
-- DROP TRIGGER trg_AutoEnableCDC_OnTableCreate ON DATABASE;
-- GO

PRINT '';
PRINT '========================================================';
PRINT 'Auto-enable CDC trigger created successfully!';
PRINT 'New tables will automatically have CDC enabled.';
PRINT '========================================================';
GO
