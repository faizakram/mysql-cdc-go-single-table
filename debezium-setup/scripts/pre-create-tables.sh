#!/bin/bash

# ============================================================================
# Pre-create PostgreSQL tables with exact schema from MS SQL Server
# This ensures VARCHAR length constraints and proper data types
# ============================================================================

set -e

echo "=========================================================================="
echo "Creating PostgreSQL tables with exact MS SQL schema mappings"
echo "=========================================================================="

# Get MS SQL schema and create equivalent PostgreSQL tables
docker exec -i postgres18 psql -U admin -d target_db << 'EOSQL'

-- Drop existing tables
DROP TABLE IF EXISTS dbo.employees CASCADE;
DROP TABLE IF EXISTS dbo.data_type_test CASCADE;
DROP TABLE IF EXISTS dbo.modern_data_types CASCADE;
DROP TABLE IF EXISTS dbo.advanced_data_types CASCADE;

-- Create dbo.employees with VARCHAR length constraints
CREATE TABLE dbo.employees (
    employee_id INTEGER PRIMARY KEY,
    first_name VARCHAR(50),              -- NVARCHAR(50) → VARCHAR(50)
    last_name VARCHAR(50),               -- NVARCHAR(50) → VARCHAR(50)
    salary NUMERIC(10,2),                -- DECIMAL(10,2) → NUMERIC(10,2)
    __cdc_deleted TEXT DEFAULT 'false'
);

-- Create dbo.data_type_test with all constraints
CREATE TABLE dbo.data_type_test (
    test_id INTEGER PRIMARY KEY,
    tiny_int_col SMALLINT,
    small_int_col SMALLINT,
    int_col INTEGER,
    big_int_col BIGINT,
    decimal_col NUMERIC(18,4),
    numeric_col NUMERIC(10,2),
    float_col DOUBLE PRECISION,
    real_col REAL,
    money_col NUMERIC(19,4),
    small_money_col NUMERIC(10,4),
    bit_col BOOLEAN,
    char_col CHAR(10),                    -- CHAR(10) with length
    var_char_col VARCHAR(50),             -- VARCHAR(50) with length
    var_char_max_col TEXT,                -- VARCHAR(MAX) → TEXT
    n_char_col CHAR(10),                  -- NCHAR(10) → CHAR(10)
    n_var_char_col VARCHAR(100),          -- NVARCHAR(100) with length
    n_var_char_max_col TEXT,              -- NVARCHAR(MAX) → TEXT
    text_col TEXT,
    n_text_col TEXT,
    date_col DATE,
    time_col TIME(6),
    date_time_col TIMESTAMP(6),
    date_time2_col TIMESTAMP(6),
    small_date_time_col TIMESTAMP(6),
    date_time_offset_col TIMESTAMPTZ(6),
    binary_col BYTEA,
    var_binary_col BYTEA,
    var_binary_max_col BYTEA,
    unique_identifier_col UUID,           -- UNIQUEIDENTIFIER → UUID
    xml_col TEXT,                         -- XML → TEXT
    nullable_int_col INTEGER,
    nullable_var_char_col VARCHAR(255),   -- Nullable VARCHAR with length
    __cdc_deleted TEXT DEFAULT 'false'
);

-- Create dbo.modern_data_types with UUID and JSON types
CREATE TABLE dbo.modern_data_types (
    id INTEGER PRIMARY KEY,
    user_id UUID NOT NULL,                          -- UNIQUEIDENTIFIER → UUID
    session_id UUID NOT NULL,                       -- UNIQUEIDENTIFIER → UUID
    transaction_id UUID NOT NULL,                   -- UNIQUEIDENTIFIER → UUID
    user_preferences JSON,                          -- JSON string → JSON
    api_response JSON,                              -- JSON string → JSON
    metadata JSON,                                  -- JSON string → JSON
    xml_config TEXT,                                -- XML → TEXT
    user_name VARCHAR(100),                         -- NVARCHAR(100) with length
    email VARCHAR(255),                             -- NVARCHAR(255) with length
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP(6),
    last_login_id UUID,                             -- UNIQUEIDENTIFIER → UUID
    settings JSON,                                  -- JSON string → JSON
    __cdc_deleted TEXT DEFAULT 'false'
);

-- Create indexes for performance
CREATE INDEX idx_employees_first_name ON dbo.employees(first_name);
CREATE INDEX idx_employees_last_name ON dbo.employees(last_name);
CREATE INDEX idx_modern_user_id ON dbo.modern_data_types(user_id);
CREATE INDEX idx_modern_preferences_gin ON dbo.modern_data_types USING GIN ((user_preferences::JSONB));

-- Grant permissions
GRANT ALL ON dbo.employees TO admin;
GRANT ALL ON dbo.data_type_test TO admin;
GRANT ALL ON dbo.modern_data_types TO admin;

-- Show created tables
\dt dbo.*

-- Show detailed structure
\d dbo.employees
\d dbo.modern_data_types

SELECT 'Tables pre-created successfully with VARCHAR length constraints!' AS status;

EOSQL

echo "=========================================================================="
echo "✅ PostgreSQL tables created with exact schema mappings"
echo "=========================================================================="
echo ""
echo "VARCHAR length constraints preserved:"
echo "  - employees.first_name: VARCHAR(50)"
echo "  - employees.last_name: VARCHAR(50)"
echo "  - modern_data_types.user_name: VARCHAR(100)"
echo "  - modern_data_types.email: VARCHAR(255)"
echo ""
echo "UUID and JSON types configured:"
echo "  - UUID columns: user_id, session_id, transaction_id, last_login_id, unique_identifier_col"
echo "  - JSON columns: user_preferences, api_response, metadata, settings"
echo ""
echo "Now restart the sink connector to use these pre-created tables:"
echo "  curl -X POST http://localhost:8083/connectors/postgres-sink-connector/restart"
