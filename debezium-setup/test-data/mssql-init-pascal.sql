/* =========================================================================
   PascalShop — a second MS SQL source whose tables and columns are all named
   in PascalCase (e.g. OrderItems, CustomerId). Mirrors the TestShop schema/data
   so you can test naming strategies (PRESERVE vs SNAKE_CASE/camel/Pascal/UPPER)
   and the case-transform SMT on a real source. ~10,000 rows in OrderItems; CDC
   enabled DB-wide and per-table. Idempotent: drops & recreates PascalShop.
   ========================================================================= */

IF DB_ID('PascalShop') IS NOT NULL
BEGIN
    ALTER DATABASE PascalShop SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
    DROP DATABASE PascalShop;
END
GO
CREATE DATABASE PascalShop;
GO
USE PascalShop;
GO
-- Required for computed columns, XML methods and spatial types (sqlcmd defaults this OFF).
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

/* ---------- 1. Categories ---------- */
CREATE TABLE dbo.Categories (
    CategoryId    INT IDENTITY(1,1) PRIMARY KEY,
    Name          NVARCHAR(100)  NOT NULL,
    Slug          VARCHAR(120)   NOT NULL UNIQUE,
    Description   NVARCHAR(MAX)  NULL,
    IsActive      BIT            NOT NULL DEFAULT 1,
    SortOrder     SMALLINT       NOT NULL DEFAULT 0,
    CreatedAt     DATETIME2(7)   NOT NULL DEFAULT SYSUTCDATETIME()
);

/* ---------- 2. Suppliers ---------- */
CREATE TABLE dbo.Suppliers (
    SupplierId    INT IDENTITY(1,1) PRIMARY KEY,
    Name          NVARCHAR(150) NOT NULL,
    ContactEmail  VARCHAR(255)  NULL,
    Phone         VARCHAR(40)   NULL,
    Country       CHAR(2)       NOT NULL DEFAULT 'US',
    Rating        DECIMAL(3,2)  NULL,
    CreditLimit   MONEY         NOT NULL DEFAULT 0,
    CreatedOn     DATE          NOT NULL DEFAULT CAST(SYSUTCDATETIME() AS DATE)
);

/* ---------- 3. Customers ---------- */
CREATE TABLE dbo.Customers (
    CustomerId    INT IDENTITY(1,1) PRIMARY KEY,
    Guid          UNIQUEIDENTIFIER NOT NULL DEFAULT NEWID(),
    FirstName     NVARCHAR(60)  NOT NULL,
    LastName      NVARCHAR(60)  NOT NULL,
    Email         VARCHAR(255)  NOT NULL,
    Phone         VARCHAR(40)   NULL,
    BirthDate     DATE          NULL,
    SignupTs      DATETIMEOFFSET(7) NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    LoyaltyPoints INT           NOT NULL DEFAULT 0,
    IsVip         BIT           NOT NULL DEFAULT 0,
    Balance       DECIMAL(12,2) NOT NULL DEFAULT 0
);

/* ---------- 4. Addresses (FK -> Customers) ---------- */
CREATE TABLE dbo.Addresses (
    AddressId     INT IDENTITY(1,1) PRIMARY KEY,
    CustomerId    INT NOT NULL,
    Line1         NVARCHAR(200) NOT NULL,
    Line2         NVARCHAR(200) NULL,
    City          NVARCHAR(100) NOT NULL,
    State         NCHAR(2)      NULL,
    PostalCode    VARCHAR(12)   NULL,
    Country       CHAR(2)       NOT NULL DEFAULT 'US',
    IsPrimary     BIT           NOT NULL DEFAULT 1,
    GeoLat        FLOAT         NULL,
    GeoLng        FLOAT         NULL,
    CONSTRAINT FK_Addresses_Customer FOREIGN KEY (CustomerId) REFERENCES dbo.Customers(CustomerId)
);

/* ---------- 5. Employees (self-referencing FK) ---------- */
CREATE TABLE dbo.Employees (
    EmployeeId    INT IDENTITY(1,1) PRIMARY KEY,
    ManagerId     INT           NULL,
    FullName      NVARCHAR(120) NOT NULL,
    Email         VARCHAR(255)  NOT NULL,
    HireDate      DATE          NOT NULL,
    Salary        DECIMAL(10,2) NOT NULL,
    CommissionPct REAL          NULL,
    Dept          VARCHAR(40)   NOT NULL,
    Active        BIT           NOT NULL DEFAULT 1,
    CONSTRAINT FK_Employees_Manager FOREIGN KEY (ManagerId) REFERENCES dbo.Employees(EmployeeId)
);

/* ---------- 6. Products (FK -> Categories, Suppliers; Xml + VarBinary) ---------- */
CREATE TABLE dbo.Products (
    ProductId       INT IDENTITY(1,1) PRIMARY KEY,
    CategoryId      INT NOT NULL,
    SupplierId      INT NOT NULL,
    Sku             VARCHAR(40)   NOT NULL UNIQUE,
    Name            NVARCHAR(200) NOT NULL,
    Description     NVARCHAR(MAX) NULL,
    Price           MONEY         NOT NULL,
    Cost            DECIMAL(10,4) NOT NULL,
    WeightKg        FLOAT         NULL,
    Dimensions      VARCHAR(50)   NULL,
    InStock         INT           NOT NULL DEFAULT 0,
    ReorderLevel    SMALLINT      NOT NULL DEFAULT 10,
    IsDiscontinued  BIT           NOT NULL DEFAULT 0,
    SpecXml         XML           NULL,
    Thumbnail       VARBINARY(MAX) NULL,
    CreatedAt       DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    UpdatedAt       DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT FK_Products_Category FOREIGN KEY (CategoryId) REFERENCES dbo.Categories(CategoryId),
    CONSTRAINT FK_Products_Supplier FOREIGN KEY (SupplierId) REFERENCES dbo.Suppliers(SupplierId)
);

/* ---------- 7. Orders (FK -> Customers, Employees; RowVersion) ---------- */
CREATE TABLE dbo.Orders (
    OrderId       INT IDENTITY(1,1) PRIMARY KEY,
    CustomerId    INT NOT NULL,
    EmployeeId    INT NULL,
    OrderDate     DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    RequiredDate  DATE          NULL,
    ShippedDate   DATETIME2(3)  NULL,
    Status        VARCHAR(20)   NOT NULL DEFAULT 'NEW',
    Subtotal      MONEY         NOT NULL DEFAULT 0,
    Tax           DECIMAL(10,2) NOT NULL DEFAULT 0,
    Freight       SMALLMONEY    NOT NULL DEFAULT 0,
    Total         MONEY         NOT NULL DEFAULT 0,
    Notes         NVARCHAR(500) NULL,
    RowVersion    ROWVERSION,
    CONSTRAINT FK_Orders_Customer FOREIGN KEY (CustomerId) REFERENCES dbo.Customers(CustomerId),
    CONSTRAINT FK_Orders_Employee FOREIGN KEY (EmployeeId) REFERENCES dbo.Employees(EmployeeId)
);

/* ---------- 8. OrderItems (FK -> Orders, Products; computed PERSISTED) — ~10K rows ---------- */
CREATE TABLE dbo.OrderItems (
    OrderItemId   BIGINT IDENTITY(1,1) PRIMARY KEY,
    OrderId       INT NOT NULL,
    ProductId     INT NOT NULL,
    Quantity      INT NOT NULL,
    UnitPrice     MONEY NOT NULL,
    Discount      DECIMAL(5,4) NOT NULL DEFAULT 0,
    LineTotal     AS (CAST(Quantity * UnitPrice * (1 - Discount) AS MONEY)) PERSISTED,
    CreatedAt     DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT FK_OrderItems_Order   FOREIGN KEY (OrderId)   REFERENCES dbo.Orders(OrderId),
    CONSTRAINT FK_OrderItems_Product FOREIGN KEY (ProductId) REFERENCES dbo.Products(ProductId)
);

/* ---------- 9. Payments (FK -> Orders) ---------- */
CREATE TABLE dbo.Payments (
    PaymentId    INT IDENTITY(1,1) PRIMARY KEY,
    OrderId      INT NOT NULL,
    Amount       MONEY NOT NULL,
    Method       VARCHAR(20) NOT NULL,
    CardLast4    CHAR(4) NULL,
    PaidAt       DATETIMEOFFSET(7) NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    IsRefunded   BIT NOT NULL DEFAULT 0,
    TxnGuid      UNIQUEIDENTIFIER NOT NULL DEFAULT NEWID(),
    CONSTRAINT FK_Payments_Order FOREIGN KEY (OrderId) REFERENCES dbo.Orders(OrderId)
);

/* ---------- 10. DataTypeShowcase (every remaining type; FK -> Orders) ---------- */
CREATE TABLE dbo.DataTypeShowcase (
    Id                INT IDENTITY(1,1) PRIMARY KEY,
    OrderId           INT NULL,
    CBigint           BIGINT NULL,
    CInt              INT NULL,
    CSmallint         SMALLINT NULL,
    CTinyint          TINYINT NULL,
    CBit              BIT NULL,
    CDecimal          DECIMAL(18,6) NULL,
    CNumeric          NUMERIC(10,2) NULL,
    CMoney            MONEY NULL,
    CSmallmoney       SMALLMONEY NULL,
    CFloat            FLOAT NULL,
    CReal             REAL NULL,
    CDate             DATE NULL,
    CTime             TIME(7) NULL,
    CDatetime         DATETIME NULL,
    CDatetime2        DATETIME2(7) NULL,
    CSmalldatetime    SMALLDATETIME NULL,
    CDatetimeoffset   DATETIMEOFFSET(7) NULL,
    CChar             CHAR(10) NULL,
    CVarchar          VARCHAR(100) NULL,
    CVarcharMax       VARCHAR(MAX) NULL,
    CNchar            NCHAR(10) NULL,
    CNvarchar         NVARCHAR(100) NULL,
    CNvarcharMax      NVARCHAR(MAX) NULL,
    CBinary           BINARY(8) NULL,
    CVarbinary        VARBINARY(256) NULL,
    CUniqueidentifier UNIQUEIDENTIFIER NULL,
    CXml              XML NULL,
    CSqlVariant       SQL_VARIANT NULL,
    CGeography        GEOGRAPHY NULL,
    CHierarchyid      HIERARCHYID NULL,
    CONSTRAINT FK_DataTypeShowcase_Order FOREIGN KEY (OrderId) REFERENCES dbo.Orders(OrderId)
);
GO

/* ============================ DATA ============================ */

/* 1. Categories — 8 */
INSERT dbo.Categories (Name, Slug, Description, IsActive, SortOrder) VALUES
(N'Electronics', 'electronics', N'Phones, laptops & gadgets 📱', 1, 1),
(N'Books',       'books',       N'Fiction and non-fiction',      1, 2),
(N'Home & Kitchen','home-kitchen',N'Everything for the home',    1, 3),
(N'Sports',      'sports',      N'Gear & apparel',               1, 4),
(N'Toys',        'toys',        N'Fun for all ages',             1, 5),
(N'Garden',      'garden',      N'Outdoor & plants 🌿',          1, 6),
(N'Beauty',      'beauty',      N'Cosmetics & care',             0, 7),
(N'Automotive',  'automotive',  N'Parts & accessories',          1, 8);

/* 2. Suppliers — 25 */
;WITH N AS (SELECT TOP (25) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) Rn FROM sys.all_objects)
INSERT dbo.Suppliers (Name, ContactEmail, Phone, Country, Rating, CreditLimit, CreatedOn)
SELECT CONCAT(N'Supplier ', Rn),
       CONCAT('sales', Rn, '@vendor.example'),
       CONCAT('+1-555-', RIGHT('0000' + CAST(Rn AS VARCHAR), 4)),
       CASE Rn % 4 WHEN 0 THEN 'US' WHEN 1 THEN 'GB' WHEN 2 THEN 'DE' ELSE 'JP' END,
       CAST(2.5 + (Rn % 25) / 10.0 AS DECIMAL(3,2)),
       (Rn % 10) * 1000.00,
       DATEADD(DAY, -(Rn * 7), CAST(SYSUTCDATETIME() AS DATE))
FROM N;

/* 3. Customers — 1000 */
;WITH N AS (SELECT TOP (1000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) Rn FROM sys.all_objects a CROSS JOIN sys.all_objects b)
INSERT dbo.Customers (FirstName, LastName, Email, Phone, BirthDate, LoyaltyPoints, IsVip, Balance)
SELECT CONCAT(N'First', Rn), CONCAT(N'Last', Rn),
       CONCAT('customer', Rn, '@mail.example'),
       CASE WHEN Rn % 7 = 0 THEN NULL ELSE CONCAT('+1-555-', RIGHT('0000' + CAST(Rn AS VARCHAR), 4)) END,
       DATEADD(DAY, -(6000 + (Rn % 9000)), CAST(SYSUTCDATETIME() AS DATE)),
       (Rn * 13) % 5000,
       CASE WHEN Rn % 20 = 0 THEN 1 ELSE 0 END,
       CAST(((Rn % 500) - 50) AS DECIMAL(12,2))
FROM N;

/* 4. Addresses — ~1300 */
INSERT dbo.Addresses (CustomerId, Line1, Line2, City, State, PostalCode, Country, IsPrimary, GeoLat, GeoLng)
SELECT c.CustomerId, CONCAT(c.CustomerId, N' Main St'),
       CASE WHEN c.CustomerId % 5 = 0 THEN N'Apt 4B' ELSE NULL END,
       N'Springfield', N'IL', RIGHT('00000' + CAST(c.CustomerId AS VARCHAR), 5), 'US', 1,
       37.0 + (c.CustomerId % 100) / 100.0, -122.0 - (c.CustomerId % 100) / 100.0
FROM dbo.Customers c
UNION ALL
SELECT c.CustomerId, CONCAT(c.CustomerId, N' Second Ave'), NULL,
       N'Shelbyville', N'IL', RIGHT('00000' + CAST(c.CustomerId + 1 AS VARCHAR), 5), 'US', 0,
       40.0 + (c.CustomerId % 50) / 100.0, -120.0 - (c.CustomerId % 50) / 100.0
FROM dbo.Customers c WHERE c.CustomerId % 3 = 0;

/* 5. Employees — 50 (first 5 top managers) */
;WITH N AS (SELECT TOP (50) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) Rn FROM sys.all_objects)
INSERT dbo.Employees (ManagerId, FullName, Email, HireDate, Salary, CommissionPct, Dept, Active)
SELECT CASE WHEN Rn <= 5 THEN NULL ELSE ((Rn % 5) + 1) END,
       CONCAT(N'Employee ', Rn), CONCAT('emp', Rn, '@corp.example'),
       DATEADD(DAY, -(Rn * 30), CAST(SYSUTCDATETIME() AS DATE)),
       CAST(40000 + (Rn * 750) AS DECIMAL(10,2)),
       CASE WHEN Rn % 3 = 0 THEN CAST((Rn % 10) / 100.0 AS REAL) ELSE NULL END,
       CASE Rn % 4 WHEN 0 THEN 'Sales' WHEN 1 THEN 'Support' WHEN 2 THEN 'Ops' ELSE 'Eng' END,
       CASE WHEN Rn % 11 = 0 THEN 0 ELSE 1 END
FROM N;

/* 6. Products — 300 */
;WITH N AS (SELECT TOP (300) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) Rn FROM sys.all_objects a CROSS JOIN sys.all_objects b)
INSERT dbo.Products (CategoryId, SupplierId, Sku, Name, Description, Price, Cost, WeightKg,
                     Dimensions, InStock, ReorderLevel, IsDiscontinued, SpecXml, Thumbnail)
SELECT ((Rn % 8) + 1), ((Rn % 25) + 1),
       CONCAT('SKU-', RIGHT('00000' + CAST(Rn AS VARCHAR), 5)),
       CONCAT(N'Product ', Rn),
       CASE WHEN Rn % 4 = 0 THEN NULL ELSE CONCAT(N'Description for product ', Rn, N' — quality goods ✓') END,
       CAST(((Rn % 500) + 1) + 0.99 AS MONEY),
       CAST(((Rn % 500) + 1) * 0.6 AS DECIMAL(10,4)),
       CASE WHEN Rn % 6 = 0 THEN NULL ELSE CAST((Rn % 50) / 10.0 AS FLOAT) END,
       CONCAT((Rn % 30) + 1, 'x', (Rn % 20) + 1, 'x', (Rn % 10) + 1, ' cm'),
       (Rn * 7) % 1000, CAST((Rn % 50) AS SMALLINT),
       CASE WHEN Rn % 25 = 0 THEN 1 ELSE 0 END,
       CASE WHEN Rn % 10 = 0 THEN CAST(CONCAT('<spec><color>', CASE Rn%3 WHEN 0 THEN 'red' WHEN 1 THEN 'blue' ELSE 'green' END,
            '</color><warranty months="', (Rn%24)+1, '"/></spec>') AS XML) ELSE NULL END,
       CASE WHEN Rn % 10 = 0 THEN CAST(CONCAT('thumb', Rn) AS VARBINARY(MAX)) ELSE NULL END
FROM N;

/* 7. Orders — 2000 */
;WITH N AS (SELECT TOP (2000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) Rn FROM sys.all_objects a CROSS JOIN sys.all_objects b)
INSERT dbo.Orders (CustomerId, EmployeeId, OrderDate, RequiredDate, ShippedDate, Status,
                   Subtotal, Tax, Freight, Total, Notes)
SELECT ((Rn % 1000) + 1),
       CASE WHEN Rn % 8 = 0 THEN NULL ELSE ((Rn % 50) + 1) END,
       DATEADD(MINUTE, -(Rn * 37), SYSUTCDATETIME()),
       DATEADD(DAY, (Rn % 14) + 1, CAST(SYSUTCDATETIME() AS DATE)),
       CASE WHEN Rn % 3 = 0 THEN DATEADD(DAY, (Rn % 5) + 1, SYSUTCDATETIME()) ELSE NULL END,
       CASE Rn % 5 WHEN 0 THEN 'NEW' WHEN 1 THEN 'PAID' WHEN 2 THEN 'SHIPPED' WHEN 3 THEN 'DELIVERED' ELSE 'CANCELLED' END,
       CAST((Rn % 900) + 10 AS MONEY),
       CAST(((Rn % 900) + 10) * 0.08 AS DECIMAL(10,2)),
       CAST((Rn % 30) AS SMALLMONEY),
       CAST(((Rn % 900) + 10) * 1.08 + (Rn % 30) AS MONEY),
       CASE WHEN Rn % 6 = 0 THEN N'Gift wrap 🎁 please' ELSE NULL END
FROM N;

/* 8. OrderItems — 10000 */
;WITH N AS (SELECT TOP (10000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) Rn FROM sys.all_objects a CROSS JOIN sys.all_objects b)
INSERT dbo.OrderItems (OrderId, ProductId, Quantity, UnitPrice, Discount)
SELECT ((Rn % 2000) + 1), ((Rn % 300) + 1),
       (Rn % 9) + 1,
       CAST(((Rn % 500) + 1) + 0.99 AS MONEY),
       CAST(CASE WHEN Rn % 7 = 0 THEN (Rn % 30) / 100.0 ELSE 0 END AS DECIMAL(5,4))
FROM N;

/* 9. Payments — 2000 */
INSERT dbo.Payments (OrderId, Amount, Method, CardLast4, IsRefunded)
SELECT o.OrderId, o.Total,
       CASE o.OrderId % 4 WHEN 0 THEN 'CARD' WHEN 1 THEN 'PAYPAL' WHEN 2 THEN 'BANK' ELSE 'CASH' END,
       CASE WHEN o.OrderId % 4 = 0 THEN RIGHT('0000' + CAST(o.OrderId AS VARCHAR), 4) ELSE NULL END,
       CASE WHEN o.OrderId % 17 = 0 THEN 1 ELSE 0 END
FROM dbo.Orders o;

/* 10. DataTypeShowcase — 100 rows */
;WITH N AS (SELECT TOP (100) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) Rn FROM sys.all_objects)
INSERT dbo.DataTypeShowcase (
    OrderId, CBigint, CInt, CSmallint, CTinyint, CBit, CDecimal, CNumeric, CMoney, CSmallmoney,
    CFloat, CReal, CDate, CTime, CDatetime, CDatetime2, CSmalldatetime, CDatetimeoffset,
    CChar, CVarchar, CVarcharMax, CNchar, CNvarchar, CNvarcharMax, CBinary, CVarbinary,
    CUniqueidentifier, CXml, CSqlVariant, CGeography, CHierarchyid)
SELECT
    CASE WHEN Rn % 4 = 0 THEN NULL ELSE ((Rn % 2000) + 1) END,
    CAST(Rn AS BIGINT) * 1000000000,
    Rn * 100 - 5000,
    CAST(Rn - 50 AS SMALLINT),
    CAST(Rn % 256 AS TINYINT),
    Rn % 2,
    CAST(Rn + 0.123456 AS DECIMAL(18,6)),
    CAST(Rn + 0.5 AS NUMERIC(10,2)),
    CAST(Rn * 1.25 AS MONEY),
    CAST(Rn * 0.5 AS SMALLMONEY),
    CAST(Rn * 3.14159 AS FLOAT),
    CAST(Rn * 2.5 AS REAL),
    DATEADD(DAY, Rn, '2000-01-01'),
    CAST(DATEADD(SECOND, Rn * 37, '00:00:00') AS TIME(7)),
    DATEADD(MINUTE, Rn, '2010-06-15T08:30:00'),
    SYSUTCDATETIME(),
    DATEADD(DAY, Rn, '2015-01-01'),
    SYSDATETIMEOFFSET(),
    CONCAT('C', Rn),
    CONCAT('varchar-', Rn),
    CASE WHEN Rn % 5 = 0 THEN NULL ELSE REPLICATE(CONCAT('x', Rn, ' '), 10) END,
    CONCAT('N', Rn),
    CONCAT(N'unicode-', Rn, N'-héllo-世界'),
    CASE WHEN Rn % 3 = 0 THEN NULL ELSE CONCAT(N'big text ', REPLICATE(N'😀', Rn % 5), N' end') END,
    CAST(Rn AS BINARY(8)),
    CAST(CONCAT('bin', Rn) AS VARBINARY(256)),
    NEWID(),
    CAST(CONCAT('<row id="', Rn, '"><v>', Rn * 2, '</v></row>') AS XML),
    CASE Rn % 3 WHEN 0 THEN CAST(Rn AS SQL_VARIANT) WHEN 1 THEN CAST(CONCAT('variant-', Rn) AS SQL_VARIANT) ELSE CAST(SYSUTCDATETIME() AS SQL_VARIANT) END,
    geography::Point(37.0 + (Rn % 90) / 100.0, -122.0 + (Rn % 90) / 100.0, 4326),
    CAST(CONCAT('/', Rn, '/') AS HIERARCHYID)
FROM N;
GO

/* keep Order rollups consistent (exercises UPDATEs too) */
UPDATE o
SET Subtotal = x.S, Total = x.S + o.Tax + o.Freight
FROM dbo.Orders o
JOIN (SELECT OrderId, SUM(LineTotal) S FROM dbo.OrderItems GROUP BY OrderId) x
  ON x.OrderId = o.OrderId;
GO

/* ============================ CDC ============================ */
EXEC sys.sp_cdc_enable_db;
GO

DECLARE @t SYSNAME;
DECLARE c CURSOR FOR
    SELECT name FROM sys.tables
    WHERE schema_id = SCHEMA_ID('dbo') AND is_tracked_by_cdc = 0;
OPEN c;
FETCH NEXT FROM c INTO @t;
WHILE @@FETCH_STATUS = 0
BEGIN
    BEGIN TRY
        EXEC sys.sp_cdc_enable_table
            @source_schema = N'dbo', @source_name = @t,
            @role_name = NULL, @supports_net_changes = 0;
        PRINT 'CDC enabled: ' + @t;
    END TRY
    BEGIN CATCH
        PRINT 'CDC SKIPPED (' + @t + '): ' + ERROR_MESSAGE();
    END CATCH
    FETCH NEXT FROM c INTO @t;
END
CLOSE c; DEALLOCATE c;
GO

/* ============================ SUMMARY ============================ */
SELECT t.name AS TableName, p.rows AS RowCountX, t.is_tracked_by_cdc AS CdcEnabled
FROM sys.tables t
JOIN sys.partitions p ON p.object_id = t.object_id AND p.index_id IN (0,1)
WHERE t.schema_id = SCHEMA_ID('dbo')
ORDER BY p.rows DESC;
GO
