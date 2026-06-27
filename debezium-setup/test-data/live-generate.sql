-- Continuous live data generator for the PascalShop source (manual CDC demo).
-- Each tick (~1s) runs a mixed workload across all 10 tables in FK-dependency order:
--   INSERT ~15 rows, UPDATE ~7 rows, DELETE ~4 rows (leaf tables only, FK-safe),
-- so you can watch inserts/updates/deletes flow through CDC live and in the integrity report.
--
-- Controlled by dbo._GenControl(Running): the loop runs while Running=1.
-- Stop it any time with:  UPDATE dbo._GenControl SET Running = 0 WHERE Id = 1;
-- (live-generate-stop.sh does exactly that). _GenControl is a helper table, not CDC-tracked,
-- and is not part of the migration project's selected tables.
SET NOCOUNT ON;
-- Required to INSERT into tables with persisted computed columns (OrderItems.LineTotal)
-- and XML/spatial columns (DataTypeShowcase); sqlcmd over stdin defaults these OFF.
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;

IF OBJECT_ID('dbo._GenControl') IS NULL
    CREATE TABLE dbo._GenControl (Id INT PRIMARY KEY, Running BIT NOT NULL);
IF NOT EXISTS (SELECT 1 FROM dbo._GenControl WHERE Id = 1)
    INSERT INTO dbo._GenControl (Id, Running) VALUES (1, 1);
ELSE
    UPDATE dbo._GenControl SET Running = 1 WHERE Id = 1;

DECLARE @tick INT = 0;

WHILE (SELECT Running FROM dbo._GenControl WHERE Id = 1) = 1
BEGIN
    SET @tick += 1;

    ---- Parents (no FK dependencies) ----
    INSERT INTO dbo.Categories (Name, Slug)
    VALUES (CONCAT(N'Gen Cat ', @tick), CONCAT('gen-cat-', LOWER(REPLACE(CAST(NEWID() AS VARCHAR(36)), '-', ''))));

    INSERT INTO dbo.Suppliers (Name, Country, CreditLimit)
    VALUES (CONCAT(N'Gen Supplier ', LEFT(REPLACE(CAST(NEWID() AS VARCHAR(36)), '-', ''), 10)), 'US', 5000);

    INSERT INTO dbo.Customers (FirstName, LastName, Email)
    VALUES (N'Gen', N'User', CONCAT('gen+', LEFT(REPLACE(CAST(NEWID() AS VARCHAR(36)), '-', ''), 18), '@test.local'));
    INSERT INTO dbo.Customers (FirstName, LastName, Email)
    VALUES (N'Gen', N'User', CONCAT('gen+', LEFT(REPLACE(CAST(NEWID() AS VARCHAR(36)), '-', ''), 18), '@test.local'));

    INSERT INTO dbo.Employees (FullName, Email, HireDate, Salary, Dept)
    VALUES (N'Gen Employee',
            CONCAT('emp+', LEFT(REPLACE(CAST(NEWID() AS VARCHAR(36)), '-', ''), 18), '@test.local'),
            CAST(SYSUTCDATETIME() AS DATE), 30000 + ABS(CHECKSUM(NEWID())) % 50000, 'SALES');

    ---- Children (pick valid parent keys at random) ----
    INSERT INTO dbo.Addresses (CustomerId, Line1, City, PostalCode)
    SELECT TOP 1 CustomerId, N'123 Gen Street', N'Gen City', '00000' FROM dbo.Customers ORDER BY NEWID();

    INSERT INTO dbo.Products (CategoryId, SupplierId, Sku, Name, Price, Cost, InStock)
    SELECT
        (SELECT TOP 1 CategoryId FROM dbo.Categories ORDER BY NEWID()),
        (SELECT TOP 1 SupplierId FROM dbo.Suppliers ORDER BY NEWID()),
        CONCAT('GEN-SKU-', LEFT(REPLACE(CAST(NEWID() AS VARCHAR(36)), '-', ''), 18)),
        N'Gen Product', 9.99 + ABS(CHECKSUM(NEWID())) % 500, 5.00 + ABS(CHECKSUM(NEWID())) % 200,
        ABS(CHECKSUM(NEWID())) % 1000;
    INSERT INTO dbo.Products (CategoryId, SupplierId, Sku, Name, Price, Cost, InStock)
    SELECT
        (SELECT TOP 1 CategoryId FROM dbo.Categories ORDER BY NEWID()),
        (SELECT TOP 1 SupplierId FROM dbo.Suppliers ORDER BY NEWID()),
        CONCAT('GEN-SKU-', LEFT(REPLACE(CAST(NEWID() AS VARCHAR(36)), '-', ''), 18)),
        N'Gen Product', 9.99 + ABS(CHECKSUM(NEWID())) % 500, 5.00 + ABS(CHECKSUM(NEWID())) % 200,
        ABS(CHECKSUM(NEWID())) % 1000;

    INSERT INTO dbo.Orders (CustomerId, EmployeeId, Status, Total)
    SELECT (SELECT TOP 1 CustomerId FROM dbo.Customers ORDER BY NEWID()),
           (SELECT TOP 1 EmployeeId FROM dbo.Employees ORDER BY NEWID()),
           'NEW', ABS(CHECKSUM(NEWID())) % 2000;
    INSERT INTO dbo.Orders (CustomerId, EmployeeId, Status, Total)
    SELECT (SELECT TOP 1 CustomerId FROM dbo.Customers ORDER BY NEWID()),
           (SELECT TOP 1 EmployeeId FROM dbo.Employees ORDER BY NEWID()),
           'PAID', ABS(CHECKSUM(NEWID())) % 2000;

    INSERT INTO dbo.OrderItems (OrderId, ProductId, Quantity, UnitPrice)
    SELECT (SELECT TOP 1 OrderId FROM dbo.Orders ORDER BY NEWID()),
           (SELECT TOP 1 ProductId FROM dbo.Products ORDER BY NEWID()),
           1 + ABS(CHECKSUM(NEWID())) % 10, 9.99 + ABS(CHECKSUM(NEWID())) % 100;
    INSERT INTO dbo.OrderItems (OrderId, ProductId, Quantity, UnitPrice)
    SELECT (SELECT TOP 1 OrderId FROM dbo.Orders ORDER BY NEWID()),
           (SELECT TOP 1 ProductId FROM dbo.Products ORDER BY NEWID()),
           1 + ABS(CHECKSUM(NEWID())) % 10, 9.99 + ABS(CHECKSUM(NEWID())) % 100;

    INSERT INTO dbo.Payments (OrderId, Amount, Method)
    SELECT (SELECT TOP 1 OrderId FROM dbo.Orders ORDER BY NEWID()),
           10 + ABS(CHECKSUM(NEWID())) % 500, 'CARD';

    INSERT INTO dbo.DataTypeShowcase (OrderId, CInt, CVarchar, CUniqueidentifier, CDecimal)
    SELECT (SELECT TOP 1 OrderId FROM dbo.Orders ORDER BY NEWID()),
           ABS(CHECKSUM(NEWID())) % 100000, 'gen', NEWID(), ABS(CHECKSUM(NEWID())) % 1000;

    ---- UPDATEs (exercise update CDC; counts unchanged) ----
    ;WITH c AS (SELECT TOP 2 * FROM dbo.Customers ORDER BY NEWID())
    UPDATE c SET Balance = Balance + 10, LoyaltyPoints = LoyaltyPoints + 1;

    ;WITH p AS (SELECT TOP 2 * FROM dbo.Products ORDER BY NEWID())
    UPDATE p SET Price = Price + 1, InStock = InStock + 1, UpdatedAt = SYSUTCDATETIME();

    ;WITH o AS (SELECT TOP 2 * FROM dbo.Orders ORDER BY NEWID())
    UPDATE o SET Status = 'SHIPPED', Total = Total + 5;

    ;WITH g AS (SELECT TOP 1 * FROM dbo.Categories WHERE Name LIKE 'Gen Cat%' ORDER BY NEWID())
    UPDATE g SET Name = CONCAT(N'Gen Cat upd ', @tick);

    ---- DELETEs (leaf tables only, newest generated rows — FK-safe) ----
    DELETE TOP (2) FROM dbo.OrderItems
    WHERE OrderItemId IN (SELECT TOP 2 OrderItemId FROM dbo.OrderItems ORDER BY OrderItemId DESC);

    DELETE TOP (1) FROM dbo.Payments
    WHERE PaymentId IN (SELECT TOP 1 PaymentId FROM dbo.Payments ORDER BY PaymentId DESC);

    DELETE TOP (1) FROM dbo.Addresses
    WHERE AddressId IN (SELECT TOP 1 AddressId FROM dbo.Addresses ORDER BY AddressId DESC);

    DELETE TOP (1) FROM dbo.DataTypeShowcase
    WHERE Id IN (SELECT TOP 1 Id FROM dbo.DataTypeShowcase ORDER BY Id DESC);

    WAITFOR DELAY '00:00:01';
END
