-- Sample source schema + data + CDC enablement for the end-to-end run.
IF DB_ID('Employees') IS NULL CREATE DATABASE Employees;
GO
USE Employees;
GO
IF OBJECT_ID('dbo.Department','U') IS NULL
  CREATE TABLE dbo.Department (DepartmentID INT PRIMARY KEY, Name NVARCHAR(100) NOT NULL);
IF OBJECT_ID('dbo.Employee','U') IS NULL
  CREATE TABLE dbo.Employee (
    EmployeeID  INT PRIMARY KEY,
    FirstName   NVARCHAR(100),
    LastName    NVARCHAR(100),
    DepartmentID INT,
    Salary      DECIMAL(10,2),
    CreatedAt   DATETIME2 DEFAULT SYSUTCDATETIME()
  );
GO
DELETE FROM dbo.Employee;
DELETE FROM dbo.Department;
INSERT INTO dbo.Department (DepartmentID, Name) VALUES (1,N'Engineering'),(2,N'Sales'),(3,N'HR');
INSERT INTO dbo.Employee (EmployeeID, FirstName, LastName, DepartmentID, Salary) VALUES
 (1,N'Alice',N'Smith',1,120000.00),
 (2,N'Bob',N'Jones',2,90000.50),
 (3,N'Carol',N'Nguyen',1,135000.00),
 (4,N'Dan',N'O''Brien',3,75000.00);
GO
-- Enable CDC (database + tables). Requires SQL Server Agent (MSSQL_AGENT_ENABLED=true).
IF NOT EXISTS (SELECT 1 FROM sys.databases WHERE name='Employees' AND is_cdc_enabled=1)
  EXEC sys.sp_cdc_enable_db;
GO
IF NOT EXISTS (SELECT 1 FROM cdc.change_tables ct JOIN sys.tables t ON ct.source_object_id=t.object_id WHERE t.name='Department')
  EXEC sys.sp_cdc_enable_table @source_schema=N'dbo', @source_name=N'Department', @role_name=NULL, @supports_net_changes=0;
IF NOT EXISTS (SELECT 1 FROM cdc.change_tables ct JOIN sys.tables t ON ct.source_object_id=t.object_id WHERE t.name='Employee')
  EXEC sys.sp_cdc_enable_table @source_schema=N'dbo', @source_name=N'Employee', @role_name=NULL, @supports_net_changes=0;
GO
SELECT name, is_cdc_enabled FROM sys.databases WHERE name='Employees';
SELECT s.name AS [schema], t.name AS [table] FROM cdc.change_tables ct
  JOIN sys.tables t ON ct.source_object_id=t.object_id
  JOIN sys.schemas s ON t.schema_id=s.schema_id;
GO
