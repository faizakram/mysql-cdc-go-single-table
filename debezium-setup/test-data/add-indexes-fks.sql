-- Add secondary indexes and foreign keys to the generated PascalShop source (T001..T300) so the
-- platform's "Indexes & FKs" replication feature has real objects to show and migrate.
--   * Nonclustered indexes on T001..T050 (Code, and composite Name+Amount).
--   * Foreign keys T002..T030.ParentId -> T001(Id) (+ supporting index). ParentId is a nullable column
--     added here; the initial snapshot reads the table directly so its values migrate with the data.
USE PascalShop;
SET NOCOUNT ON;

DECLARE @i INT, @t SYSNAME, @sql NVARCHAR(MAX);

-- Indexes on the first 50 tables.
SET @i = 1;
WHILE @i <= 50
BEGIN
    SET @t = 'T' + RIGHT('000' + CAST(@i AS VARCHAR(3)), 3);
    SET @sql = 'CREATE NONCLUSTERED INDEX ' + QUOTENAME('IX_' + @t + '_Code') + ' ON dbo.' + QUOTENAME(@t) + '(Code);'
             + 'CREATE NONCLUSTERED INDEX ' + QUOTENAME('IX_' + @t + '_Name_Amount') + ' ON dbo.' + QUOTENAME(@t) + '(Name, Amount);';
    EXEC sys.sp_executesql @sql;
    SET @i += 1;
END;

-- Foreign keys: T002..T030 reference T001(Id) via a new nullable ParentId column.
SET @i = 2;
WHILE @i <= 30
BEGIN
    SET @t = 'T' + RIGHT('000' + CAST(@i AS VARCHAR(3)), 3);
    SET @sql = 'ALTER TABLE dbo.' + QUOTENAME(@t) + ' ADD ParentId INT NULL;';
    EXEC sys.sp_executesql @sql;
    SET @sql = 'UPDATE dbo.' + QUOTENAME(@t) + ' SET ParentId = ((Id - 1) % 10000) + 1;';
    EXEC sys.sp_executesql @sql;
    SET @sql = 'CREATE NONCLUSTERED INDEX ' + QUOTENAME('IX_' + @t + '_ParentId') + ' ON dbo.' + QUOTENAME(@t) + '(ParentId);'
             + 'ALTER TABLE dbo.' + QUOTENAME(@t) + ' ADD CONSTRAINT ' + QUOTENAME('FK_' + @t + '_T001')
             + ' FOREIGN KEY (ParentId) REFERENCES dbo.T001(Id);';
    EXEC sys.sp_executesql @sql;
    SET @i += 1;
END;

SELECT 'nonclustered_indexes' AS metric,
       COUNT(*) AS value FROM sys.indexes WHERE type_desc = 'NONCLUSTERED'
       AND object_id IN (SELECT object_id FROM sys.tables WHERE name LIKE 'T[0-9][0-9][0-9]')
UNION ALL
SELECT 'foreign_keys', COUNT(*) FROM sys.foreign_keys;
