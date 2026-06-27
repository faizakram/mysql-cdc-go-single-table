/* =========================================================================
   TestShop — rich MS SQL Server source for manual migration / CDC testing.

   10 tables with primary keys, foreign keys (incl. a self-referencing FK on
   employees and a computed PERSISTED column on order_items), broad data-type
   coverage (numeric, money, all date/time, char/nchar/varchar(max), binary,
   uniqueidentifier, xml, sql_variant, geography, hierarchyid, rowversion),
   ~10,000 rows in the order_items fact table, then CDC enabled DB-wide and
   per-table. Idempotent: drops and recreates TestShop on each run.
   ========================================================================= */

IF DB_ID('TestShop') IS NOT NULL
BEGIN
    ALTER DATABASE TestShop SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
    DROP DATABASE TestShop;
END
GO
CREATE DATABASE TestShop;
GO
USE TestShop;
GO
-- Required for computed columns, XML methods and spatial types (sqlcmd defaults this OFF).
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

/* ---------- 1. categories ---------- */
CREATE TABLE dbo.categories (
    category_id   INT IDENTITY(1,1) PRIMARY KEY,
    name          NVARCHAR(100)  NOT NULL,
    slug          VARCHAR(120)   NOT NULL UNIQUE,
    description   NVARCHAR(MAX)  NULL,
    is_active     BIT            NOT NULL DEFAULT 1,
    sort_order    SMALLINT       NOT NULL DEFAULT 0,
    created_at    DATETIME2(7)   NOT NULL DEFAULT SYSUTCDATETIME()
);

/* ---------- 2. suppliers ---------- */
CREATE TABLE dbo.suppliers (
    supplier_id   INT IDENTITY(1,1) PRIMARY KEY,
    name          NVARCHAR(150) NOT NULL,
    contact_email VARCHAR(255)  NULL,
    phone         VARCHAR(40)   NULL,
    country       CHAR(2)       NOT NULL DEFAULT 'US',
    rating        DECIMAL(3,2)  NULL,
    credit_limit  MONEY         NOT NULL DEFAULT 0,
    created_on    DATE          NOT NULL DEFAULT CAST(SYSUTCDATETIME() AS DATE)
);

/* ---------- 3. customers ---------- */
CREATE TABLE dbo.customers (
    customer_id   INT IDENTITY(1,1) PRIMARY KEY,
    guid          UNIQUEIDENTIFIER NOT NULL DEFAULT NEWID(),
    first_name    NVARCHAR(60)  NOT NULL,
    last_name     NVARCHAR(60)  NOT NULL,
    email         VARCHAR(255)  NOT NULL,
    phone         VARCHAR(40)   NULL,
    birth_date    DATE          NULL,
    signup_ts     DATETIMEOFFSET(7) NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    loyalty_points INT          NOT NULL DEFAULT 0,
    is_vip        BIT           NOT NULL DEFAULT 0,
    balance       DECIMAL(12,2) NOT NULL DEFAULT 0
);

/* ---------- 4. addresses (FK -> customers) ---------- */
CREATE TABLE dbo.addresses (
    address_id    INT IDENTITY(1,1) PRIMARY KEY,
    customer_id   INT NOT NULL,
    line1         NVARCHAR(200) NOT NULL,
    line2         NVARCHAR(200) NULL,
    city          NVARCHAR(100) NOT NULL,
    state         NCHAR(2)      NULL,
    postal_code   VARCHAR(12)   NULL,
    country       CHAR(2)       NOT NULL DEFAULT 'US',
    is_primary    BIT           NOT NULL DEFAULT 1,
    geo_lat       FLOAT         NULL,
    geo_lng       FLOAT         NULL,
    CONSTRAINT fk_addr_customer FOREIGN KEY (customer_id) REFERENCES dbo.customers(customer_id)
);

/* ---------- 5. employees (self-referencing FK) ---------- */
CREATE TABLE dbo.employees (
    employee_id   INT IDENTITY(1,1) PRIMARY KEY,
    manager_id    INT           NULL,
    full_name     NVARCHAR(120) NOT NULL,
    email         VARCHAR(255)  NOT NULL,
    hire_date     DATE          NOT NULL,
    salary        DECIMAL(10,2) NOT NULL,
    commission_pct REAL         NULL,
    dept          VARCHAR(40)   NOT NULL,
    active        BIT           NOT NULL DEFAULT 1,
    CONSTRAINT fk_emp_manager FOREIGN KEY (manager_id) REFERENCES dbo.employees(employee_id)
);

/* ---------- 6. products (FK -> categories, suppliers; xml + varbinary) ---------- */
CREATE TABLE dbo.products (
    product_id      INT IDENTITY(1,1) PRIMARY KEY,
    category_id     INT NOT NULL,
    supplier_id     INT NOT NULL,
    sku             VARCHAR(40)   NOT NULL UNIQUE,
    name            NVARCHAR(200) NOT NULL,
    description     NVARCHAR(MAX) NULL,
    price           MONEY         NOT NULL,
    cost            DECIMAL(10,4) NOT NULL,
    weight_kg       FLOAT         NULL,
    dimensions      VARCHAR(50)   NULL,
    in_stock        INT           NOT NULL DEFAULT 0,
    reorder_level   SMALLINT      NOT NULL DEFAULT 10,
    is_discontinued BIT           NOT NULL DEFAULT 0,
    spec_xml        XML           NULL,
    thumbnail       VARBINARY(MAX) NULL,
    created_at      DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    updated_at      DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT fk_prod_cat  FOREIGN KEY (category_id) REFERENCES dbo.categories(category_id),
    CONSTRAINT fk_prod_supp FOREIGN KEY (supplier_id) REFERENCES dbo.suppliers(supplier_id)
);

/* ---------- 7. orders (FK -> customers, employees; rowversion) ---------- */
CREATE TABLE dbo.orders (
    order_id      INT IDENTITY(1,1) PRIMARY KEY,
    customer_id   INT NOT NULL,
    employee_id   INT NULL,
    order_date    DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    required_date DATE          NULL,
    shipped_date  DATETIME2(3)  NULL,
    status        VARCHAR(20)   NOT NULL DEFAULT 'NEW',
    subtotal      MONEY         NOT NULL DEFAULT 0,
    tax           DECIMAL(10,2) NOT NULL DEFAULT 0,
    freight       SMALLMONEY    NOT NULL DEFAULT 0,
    total         MONEY         NOT NULL DEFAULT 0,
    notes         NVARCHAR(500) NULL,
    row_version   ROWVERSION,
    CONSTRAINT fk_ord_customer FOREIGN KEY (customer_id) REFERENCES dbo.customers(customer_id),
    CONSTRAINT fk_ord_employee FOREIGN KEY (employee_id) REFERENCES dbo.employees(employee_id)
);

/* ---------- 8. order_items (FK -> orders, products; computed PERSISTED) — the ~10K fact table ---------- */
CREATE TABLE dbo.order_items (
    order_item_id BIGINT IDENTITY(1,1) PRIMARY KEY,
    order_id      INT NOT NULL,
    product_id    INT NOT NULL,
    quantity      INT NOT NULL,
    unit_price    MONEY NOT NULL,
    discount      DECIMAL(5,4) NOT NULL DEFAULT 0,
    line_total    AS (CAST(quantity * unit_price * (1 - discount) AS MONEY)) PERSISTED,
    created_at    DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT fk_oi_order   FOREIGN KEY (order_id)   REFERENCES dbo.orders(order_id),
    CONSTRAINT fk_oi_product FOREIGN KEY (product_id) REFERENCES dbo.products(product_id)
);

/* ---------- 9. payments (FK -> orders) ---------- */
CREATE TABLE dbo.payments (
    payment_id   INT IDENTITY(1,1) PRIMARY KEY,
    order_id     INT NOT NULL,
    amount       MONEY NOT NULL,
    method       VARCHAR(20) NOT NULL,
    card_last4   CHAR(4) NULL,
    paid_at      DATETIMEOFFSET(7) NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    is_refunded  BIT NOT NULL DEFAULT 0,
    txn_guid     UNIQUEIDENTIFIER NOT NULL DEFAULT NEWID(),
    CONSTRAINT fk_pay_order FOREIGN KEY (order_id) REFERENCES dbo.orders(order_id)
);

/* ---------- 10. data_type_showcase (every remaining type; FK -> orders) ---------- */
CREATE TABLE dbo.data_type_showcase (
    id                INT IDENTITY(1,1) PRIMARY KEY,
    order_id          INT NULL,
    c_bigint          BIGINT NULL,
    c_int             INT NULL,
    c_smallint        SMALLINT NULL,
    c_tinyint         TINYINT NULL,
    c_bit             BIT NULL,
    c_decimal         DECIMAL(18,6) NULL,
    c_numeric         NUMERIC(10,2) NULL,
    c_money           MONEY NULL,
    c_smallmoney      SMALLMONEY NULL,
    c_float           FLOAT NULL,
    c_real            REAL NULL,
    c_date            DATE NULL,
    c_time            TIME(7) NULL,
    c_datetime        DATETIME NULL,
    c_datetime2       DATETIME2(7) NULL,
    c_smalldatetime   SMALLDATETIME NULL,
    c_datetimeoffset  DATETIMEOFFSET(7) NULL,
    c_char            CHAR(10) NULL,
    c_varchar         VARCHAR(100) NULL,
    c_varcharmax      VARCHAR(MAX) NULL,
    c_nchar           NCHAR(10) NULL,
    c_nvarchar        NVARCHAR(100) NULL,
    c_nvarcharmax     NVARCHAR(MAX) NULL,
    c_binary          BINARY(8) NULL,
    c_varbinary       VARBINARY(256) NULL,
    c_uniqueidentifier UNIQUEIDENTIFIER NULL,
    c_xml             XML NULL,
    c_sql_variant     SQL_VARIANT NULL,
    c_geography       GEOGRAPHY NULL,
    c_hierarchyid     HIERARCHYID NULL,
    CONSTRAINT fk_dts_order FOREIGN KEY (order_id) REFERENCES dbo.orders(order_id)
);
GO

/* ============================ DATA ============================ */

/* 1. categories — 8 */
INSERT dbo.categories (name, slug, description, is_active, sort_order) VALUES
(N'Electronics', 'electronics', N'Phones, laptops & gadgets 📱', 1, 1),
(N'Books',       'books',       N'Fiction and non-fiction',      1, 2),
(N'Home & Kitchen','home-kitchen',N'Everything for the home',    1, 3),
(N'Sports',      'sports',      N'Gear & apparel',               1, 4),
(N'Toys',        'toys',        N'Fun for all ages',             1, 5),
(N'Garden',      'garden',      N'Outdoor & plants 🌿',          1, 6),
(N'Beauty',      'beauty',      N'Cosmetics & care',             0, 7),
(N'Automotive',  'automotive',  N'Parts & accessories',          1, 8);

/* 2. suppliers — 25 */
;WITH n AS (SELECT TOP (25) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) rn FROM sys.all_objects)
INSERT dbo.suppliers (name, contact_email, phone, country, rating, credit_limit, created_on)
SELECT CONCAT(N'Supplier ', rn),
       CONCAT('sales', rn, '@vendor.example'),
       CONCAT('+1-555-', RIGHT('0000' + CAST(rn AS VARCHAR), 4)),
       CASE rn % 4 WHEN 0 THEN 'US' WHEN 1 THEN 'GB' WHEN 2 THEN 'DE' ELSE 'JP' END,
       CAST(2.5 + (rn % 25) / 10.0 AS DECIMAL(3,2)),
       (rn % 10) * 1000.00,
       DATEADD(DAY, -(rn * 7), CAST(SYSUTCDATETIME() AS DATE))
FROM n;

/* 3. customers — 1000 */
;WITH n AS (SELECT TOP (1000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) rn FROM sys.all_objects a CROSS JOIN sys.all_objects b)
INSERT dbo.customers (first_name, last_name, email, phone, birth_date, loyalty_points, is_vip, balance)
SELECT CONCAT(N'First', rn),
       CONCAT(N'Last', rn),
       CONCAT('customer', rn, '@mail.example'),
       CASE WHEN rn % 7 = 0 THEN NULL ELSE CONCAT('+1-555-', RIGHT('0000' + CAST(rn AS VARCHAR), 4)) END,
       DATEADD(DAY, -(6000 + (rn % 9000)), CAST(SYSUTCDATETIME() AS DATE)),
       (rn * 13) % 5000,
       CASE WHEN rn % 20 = 0 THEN 1 ELSE 0 END,
       CAST(((rn % 500) - 50) AS DECIMAL(12,2))
FROM n;

/* 4. addresses — ~1300 (every customer once, plus a 2nd for every 3rd) */
INSERT dbo.addresses (customer_id, line1, line2, city, state, postal_code, country, is_primary, geo_lat, geo_lng)
SELECT c.customer_id, CONCAT(c.customer_id, N' Main St'),
       CASE WHEN c.customer_id % 5 = 0 THEN N'Apt 4B' ELSE NULL END,
       N'Springfield', N'IL', RIGHT('00000' + CAST(c.customer_id AS VARCHAR), 5), 'US', 1,
       37.0 + (c.customer_id % 100) / 100.0, -122.0 - (c.customer_id % 100) / 100.0
FROM dbo.customers c
UNION ALL
SELECT c.customer_id, CONCAT(c.customer_id, N' Second Ave'), NULL,
       N'Shelbyville', N'IL', RIGHT('00000' + CAST(c.customer_id + 1 AS VARCHAR), 5), 'US', 0,
       40.0 + (c.customer_id % 50) / 100.0, -120.0 - (c.customer_id % 50) / 100.0
FROM dbo.customers c WHERE c.customer_id % 3 = 0;

/* 5. employees — 50 (first 5 are top managers with NULL manager_id) */
;WITH n AS (SELECT TOP (50) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) rn FROM sys.all_objects)
INSERT dbo.employees (manager_id, full_name, email, hire_date, salary, commission_pct, dept, active)
SELECT CASE WHEN rn <= 5 THEN NULL ELSE ((rn % 5) + 1) END,
       CONCAT(N'Employee ', rn),
       CONCAT('emp', rn, '@corp.example'),
       DATEADD(DAY, -(rn * 30), CAST(SYSUTCDATETIME() AS DATE)),
       CAST(40000 + (rn * 750) AS DECIMAL(10,2)),
       CASE WHEN rn % 3 = 0 THEN CAST((rn % 10) / 100.0 AS REAL) ELSE NULL END,
       CASE rn % 4 WHEN 0 THEN 'Sales' WHEN 1 THEN 'Support' WHEN 2 THEN 'Ops' ELSE 'Eng' END,
       CASE WHEN rn % 11 = 0 THEN 0 ELSE 1 END
FROM n;

/* 6. products — 300 (every 10th carries xml + a varbinary thumbnail) */
;WITH n AS (SELECT TOP (300) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) rn FROM sys.all_objects a CROSS JOIN sys.all_objects b)
INSERT dbo.products (category_id, supplier_id, sku, name, description, price, cost, weight_kg,
                     dimensions, in_stock, reorder_level, is_discontinued, spec_xml, thumbnail)
SELECT ((rn % 8) + 1), ((rn % 25) + 1),
       CONCAT('SKU-', RIGHT('00000' + CAST(rn AS VARCHAR), 5)),
       CONCAT(N'Product ', rn),
       CASE WHEN rn % 4 = 0 THEN NULL ELSE CONCAT(N'Description for product ', rn, N' — quality goods ✓') END,
       CAST(((rn % 500) + 1) + 0.99 AS MONEY),
       CAST(((rn % 500) + 1) * 0.6 AS DECIMAL(10,4)),
       CASE WHEN rn % 6 = 0 THEN NULL ELSE CAST((rn % 50) / 10.0 AS FLOAT) END,
       CONCAT((rn % 30) + 1, 'x', (rn % 20) + 1, 'x', (rn % 10) + 1, ' cm'),
       (rn * 7) % 1000, CAST((rn % 50) AS SMALLINT),
       CASE WHEN rn % 25 = 0 THEN 1 ELSE 0 END,
       CASE WHEN rn % 10 = 0 THEN CAST(CONCAT('<spec><color>', CASE rn%3 WHEN 0 THEN 'red' WHEN 1 THEN 'blue' ELSE 'green' END,
            '</color><warranty months="', (rn%24)+1, '"/></spec>') AS XML) ELSE NULL END,
       CASE WHEN rn % 10 = 0 THEN CAST(CONCAT('thumb', rn) AS VARBINARY(MAX)) ELSE NULL END
FROM n;

/* 7. orders — 2000 */
;WITH n AS (SELECT TOP (2000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) rn FROM sys.all_objects a CROSS JOIN sys.all_objects b)
INSERT dbo.orders (customer_id, employee_id, order_date, required_date, shipped_date, status,
                   subtotal, tax, freight, total, notes)
SELECT ((rn % 1000) + 1),
       CASE WHEN rn % 8 = 0 THEN NULL ELSE ((rn % 50) + 1) END,
       DATEADD(MINUTE, -(rn * 37), SYSUTCDATETIME()),
       DATEADD(DAY, (rn % 14) + 1, CAST(SYSUTCDATETIME() AS DATE)),
       CASE WHEN rn % 3 = 0 THEN DATEADD(DAY, (rn % 5) + 1, SYSUTCDATETIME()) ELSE NULL END,
       CASE rn % 5 WHEN 0 THEN 'NEW' WHEN 1 THEN 'PAID' WHEN 2 THEN 'SHIPPED' WHEN 3 THEN 'DELIVERED' ELSE 'CANCELLED' END,
       CAST((rn % 900) + 10 AS MONEY),
       CAST(((rn % 900) + 10) * 0.08 AS DECIMAL(10,2)),
       CAST((rn % 30) AS SMALLMONEY),
       CAST(((rn % 900) + 10) * 1.08 + (rn % 30) AS MONEY),
       CASE WHEN rn % 6 = 0 THEN N'Gift wrap 🎁 please' ELSE NULL END
FROM n;

/* 8. order_items — 10000 (the fact table) */
;WITH n AS (SELECT TOP (10000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) rn FROM sys.all_objects a CROSS JOIN sys.all_objects b)
INSERT dbo.order_items (order_id, product_id, quantity, unit_price, discount)
SELECT ((rn % 2000) + 1), ((rn % 300) + 1),
       (rn % 9) + 1,
       CAST(((rn % 500) + 1) + 0.99 AS MONEY),
       CAST(CASE WHEN rn % 7 = 0 THEN (rn % 30) / 100.0 ELSE 0 END AS DECIMAL(5,4))
FROM n;

/* 9. payments — 2000 (one per order; some refunded) */
INSERT dbo.payments (order_id, amount, method, card_last4, is_refunded)
SELECT o.order_id, o.total,
       CASE o.order_id % 4 WHEN 0 THEN 'CARD' WHEN 1 THEN 'PAYPAL' WHEN 2 THEN 'BANK' ELSE 'CASH' END,
       CASE WHEN o.order_id % 4 = 0 THEN RIGHT('0000' + CAST(o.order_id AS VARCHAR), 4) ELSE NULL END,
       CASE WHEN o.order_id % 17 = 0 THEN 1 ELSE 0 END
FROM dbo.orders o;

/* 10. data_type_showcase — 100 rows covering every type, incl. NULLs and edge values */
;WITH n AS (SELECT TOP (100) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) rn FROM sys.all_objects)
INSERT dbo.data_type_showcase (
    order_id, c_bigint, c_int, c_smallint, c_tinyint, c_bit, c_decimal, c_numeric, c_money, c_smallmoney,
    c_float, c_real, c_date, c_time, c_datetime, c_datetime2, c_smalldatetime, c_datetimeoffset,
    c_char, c_varchar, c_varcharmax, c_nchar, c_nvarchar, c_nvarcharmax, c_binary, c_varbinary,
    c_uniqueidentifier, c_xml, c_sql_variant, c_geography, c_hierarchyid)
SELECT
    CASE WHEN rn % 4 = 0 THEN NULL ELSE ((rn % 2000) + 1) END,
    CAST(rn AS BIGINT) * 1000000000,
    rn * 100 - 5000,
    CAST(rn - 50 AS SMALLINT),
    CAST(rn % 256 AS TINYINT),
    rn % 2,
    CAST(rn + 0.123456 AS DECIMAL(18,6)),
    CAST(rn + 0.5 AS NUMERIC(10,2)),
    CAST(rn * 1.25 AS MONEY),
    CAST(rn * 0.5 AS SMALLMONEY),
    CAST(rn * 3.14159 AS FLOAT),
    CAST(rn * 2.5 AS REAL),
    DATEADD(DAY, rn, '2000-01-01'),
    CAST(DATEADD(SECOND, rn * 37, '00:00:00') AS TIME(7)),
    DATEADD(MINUTE, rn, '2010-06-15T08:30:00'),
    SYSUTCDATETIME(),
    DATEADD(DAY, rn, '2015-01-01'),
    SYSDATETIMEOFFSET(),
    CONCAT('C', rn),
    CONCAT('varchar-', rn),
    CASE WHEN rn % 5 = 0 THEN NULL ELSE REPLICATE(CONCAT('x', rn, ' '), 10) END,
    CONCAT('N', rn),
    CONCAT(N'unicode-', rn, N'-héllo-世界'),
    CASE WHEN rn % 3 = 0 THEN NULL ELSE CONCAT(N'big text ', REPLICATE(N'😀', rn % 5), N' end') END,
    CAST(rn AS BINARY(8)),
    CAST(CONCAT('bin', rn) AS VARBINARY(256)),
    NEWID(),
    CAST(CONCAT('<row id="', rn, '"><v>', rn * 2, '</v></row>') AS XML),
    CASE rn % 3 WHEN 0 THEN CAST(rn AS SQL_VARIANT) WHEN 1 THEN CAST(CONCAT('variant-', rn) AS SQL_VARIANT) ELSE CAST(SYSUTCDATETIME() AS SQL_VARIANT) END,
    geography::Point(37.0 + (rn % 90) / 100.0, -122.0 + (rn % 90) / 100.0, 4326),
    CAST(CONCAT('/', rn, '/') AS HIERARCHYID)
FROM n;
GO

/* keep order/customer rollups vaguely consistent (optional, exercises UPDATEs too) */
UPDATE o
SET subtotal = x.s, total = x.s + o.tax + o.freight
FROM dbo.orders o
JOIN (SELECT order_id, SUM(line_total) s FROM dbo.order_items GROUP BY order_id) x
  ON x.order_id = o.order_id;
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
SELECT t.name AS table_name,
       p.rows AS row_count,
       t.is_tracked_by_cdc AS cdc_enabled
FROM sys.tables t
JOIN sys.partitions p ON p.object_id = t.object_id AND p.index_id IN (0,1)
WHERE t.schema_id = SCHEMA_ID('dbo')
ORDER BY p.rows DESC;
GO
