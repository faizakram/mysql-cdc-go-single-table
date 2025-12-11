#!/usr/bin/env python3

"""
Dynamic Schema Replication Script
Automatically reads MS SQL schema and creates equivalent PostgreSQL tables
No manual intervention needed - fully dynamic!
"""

import pyodbc
import psycopg2
import sys
import time
import os
from pathlib import Path

# Load environment variables from .env file
def load_env():
    env_file = Path(__file__).parent.parent / '.env'
    if env_file.exists():
        with open(env_file) as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith('#') and '=' in line:
                    key, value = line.split('=', 1)
                    os.environ[key.strip()] = value.strip()

load_env()

# Detect if running inside Docker container
def is_running_in_container():
    return os.path.exists('/.dockerenv') or os.path.exists('/run/.containerenv')

# Determine appropriate hostnames
RUNNING_IN_CONTAINER = is_running_in_container()
if RUNNING_IN_CONTAINER:
    # When running inside deployer container, use Docker service names
    mssql_host = os.getenv('MSSQL_HOST', 'mssql-source')
    postgres_host = os.getenv('POSTGRES_HOST', 'postgres-target')
    # Replace host.docker.internal with actual service name
    if mssql_host == 'host.docker.internal':
        mssql_host = 'mssql-source'
    if postgres_host == 'host.docker.internal':
        postgres_host = 'postgres-target'
else:
    # When running on host, use localhost or configured host
    mssql_host = 'localhost'
    postgres_host = 'localhost'

# Configuration from environment variables
MSSQL_CONFIG = {
    'server': f"{mssql_host},{os.getenv('MSSQL_PORT', '1433')}",
    'database': os.getenv('MSSQL_DATABASE', 'Employees'),
    'username': os.getenv('MSSQL_USER', 'sa'),
    'password': os.getenv('MSSQL_PASSWORD', 'YourStrong@Passw0rd'),
    'driver': '{ODBC Driver 18 for SQL Server}',
    'TrustServerCertificate': 'yes'
}

POSTGRES_CONFIG = {
    'host': postgres_host,
    'port': int(os.getenv('POSTGRES_PORT', '5432')),
    'database': os.getenv('POSTGRES_DATABASE', 'target_db'),
    'user': os.getenv('POSTGRES_USER', 'postgres'),
    'password': os.getenv('POSTGRES_PASSWORD', 'postgres')
}

# Type mapping: MS SQL → PostgreSQL
TYPE_MAPPING = {
    # Integer types
    'tinyint': 'SMALLINT',
    'smallint': 'SMALLINT',
    'int': 'INTEGER',
    'bigint': 'BIGINT',
    
    # Decimal types
    'decimal': lambda p, s: f'NUMERIC({p},{s})',
    'numeric': lambda p, s: f'NUMERIC({p},{s})',
    'money': 'NUMERIC(19,4)',
    'smallmoney': 'NUMERIC(10,4)',
    
    # Floating point
    'float': 'DOUBLE PRECISION',
    'real': 'REAL',
    
    # Boolean
    'bit': 'BOOLEAN',
    
    # Character types (with length preservation!)
    'char': lambda length: f'CHAR({length//2})',  # NCHAR uses 2 bytes per char
    'varchar': lambda length: f'VARCHAR({length})' if length > 0 and length < 8000 else 'TEXT',
    'nchar': lambda length: f'CHAR({length//2})',
    'nvarchar': lambda length: f'VARCHAR({length//2})' if length > 0 and length < 8000 else 'TEXT',
    'text': 'TEXT',
    'ntext': 'TEXT',
    
    # Date/Time types
    'date': 'DATE',
    'time': 'TIME(6)',
    'datetime': 'TIMESTAMP(6)',
    'datetime2': 'TIMESTAMP(6)',
    'smalldatetime': 'TIMESTAMP(6)',
    'datetimeoffset': 'TIMESTAMPTZ(6)',
    
    # Binary types
    'binary': 'BYTEA',
    'varbinary': 'BYTEA',
    'image': 'BYTEA',
    
    # Special types
    'uniqueidentifier': 'UUID',  # Native UUID!
    'xml': 'TEXT',
    'sql_variant': 'TEXT',
    'geography': 'TEXT',  # Could use PostGIS
    'geometry': 'TEXT',    # Could use PostGIS
    'hierarchyid': 'TEXT'
}

def connect_mssql():
    """Connect to MS SQL Server"""
    conn_str = (
        f"DRIVER={MSSQL_CONFIG['driver']};"
        f"SERVER={MSSQL_CONFIG['server']};"
        f"DATABASE={MSSQL_CONFIG['database']};"
        f"UID={MSSQL_CONFIG['username']};"
        f"PWD={MSSQL_CONFIG['password']};"
        f"TrustServerCertificate={MSSQL_CONFIG['TrustServerCertificate']};"
    )
    return pyodbc.connect(conn_str)

def connect_postgres():
    """Connect to PostgreSQL"""
    return psycopg2.connect(
        host=POSTGRES_CONFIG['host'],
        port=POSTGRES_CONFIG['port'],
        database=POSTGRES_CONFIG['database'],
        user=POSTGRES_CONFIG['user'],
        password=POSTGRES_CONFIG['password']
    )

def get_mssql_schema(mssql_conn, table_name):
    """Get table schema from MS SQL Server"""
    cursor = mssql_conn.cursor()
    
    query = """
    SELECT 
        c.name AS column_name,
        t.name AS data_type,
        c.max_length,
        c.precision,
        c.scale,
        c.is_nullable,
        c.is_identity,
        CASE WHEN pk.column_name IS NOT NULL THEN 1 ELSE 0 END AS is_primary_key
    FROM sys.columns c
    JOIN sys.types t ON c.user_type_id = t.user_type_id
    LEFT JOIN (
        SELECT 
            ic.object_id,
            COL_NAME(ic.object_id, ic.column_id) AS column_name
        FROM sys.indexes i
        JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
        WHERE i.is_primary_key = 1
    ) pk ON c.object_id = pk.object_id AND c.name = pk.column_name
    WHERE c.object_id = OBJECT_ID(?)
    ORDER BY c.column_id
    """
    
    cursor.execute(query, (f'dbo.{table_name}',))
    return cursor.fetchall()

def convert_type(data_type, max_length, precision, scale):
    """Convert MS SQL type to PostgreSQL type"""
    data_type_lower = data_type.lower()
    
    if data_type_lower in ['decimal', 'numeric']:
        return TYPE_MAPPING[data_type_lower](precision, scale)
    elif data_type_lower in ['char', 'varchar', 'nchar', 'nvarchar']:
        return TYPE_MAPPING[data_type_lower](max_length)
    elif data_type_lower in TYPE_MAPPING:
        mapping = TYPE_MAPPING[data_type_lower]
        return mapping if isinstance(mapping, str) else mapping()
    else:
        return 'TEXT'  # Fallback

def to_snake_case(name):
    """Convert PascalCase to snake_case"""
    import re
    name = re.sub('(.)([A-Z][a-z]+)', r'\1_\2', name)
    name = re.sub('([a-z0-9])([A-Z])', r'\1_\2', name)
    return name.lower()

def create_postgres_table(pg_conn, table_name, schema):
    """Create PostgreSQL table from MS SQL schema"""
    cursor = pg_conn.cursor()
    
    # Convert table name to snake_case
    pg_table_name = to_snake_case(table_name)
    
    # Build CREATE TABLE statement
    columns = []
    pk_columns = []
    
    for row in schema:
        col_name = to_snake_case(row.column_name)
        data_type = row.data_type
        max_length = row.max_length
        precision = row.precision
        scale = row.scale
        is_nullable = row.is_nullable
        is_pk = row.is_primary_key
        
        pg_type = convert_type(data_type, max_length, precision, scale)
        
        col_def = f"{col_name} {pg_type}"
        
        if is_pk:
            col_def += " NOT NULL"
            pk_columns.append(col_name)
        elif not is_nullable:
            col_def += " NOT NULL"
        
        columns.append(col_def)
    
    # Add CDC deleted column
    columns.append("__cdc_deleted TEXT DEFAULT 'false'")
    
    # Build full CREATE TABLE statement
    create_sql = f"CREATE TABLE IF NOT EXISTS dbo.{pg_table_name} (\n    "
    create_sql += ",\n    ".join(columns)
    
    if pk_columns:
        create_sql += f",\n    PRIMARY KEY ({', '.join(pk_columns)})"
    
    create_sql += "\n);"
    
    print(f"\n📋 Creating table: dbo.{pg_table_name}")
    print(f"   Columns: {len(schema)}")
    print(f"   Primary Keys: {', '.join(pk_columns) if pk_columns else 'None'}")
    
    try:
        cursor.execute(f"DROP TABLE IF EXISTS dbo.{pg_table_name} CASCADE")
        cursor.execute(create_sql)
        pg_conn.commit()
        print(f"   ✅ Created successfully")
        return True
    except Exception as e:
        print(f"   ❌ Error: {str(e)}")
        pg_conn.rollback()
        return False

def get_cdc_enabled_tables(conn):
    """Get list of CDC-enabled tables"""
    cursor = conn.cursor()
    query = """
    SELECT DISTINCT
        OBJECT_NAME(ct.source_object_id) AS table_name
    FROM cdc.change_tables ct
    WHERE ct.source_object_id IS NOT NULL
    """
    cursor.execute(query)
    return [row.table_name for row in cursor.fetchall()]

def main():
    print("=" * 80)
    print("Dynamic Schema Replication - MS SQL → PostgreSQL")
    print("=" * 80)
    print()
    
    # Connect to databases
    print("🔌 Connecting to MS SQL Server...")
    try:
        mssql_conn = connect_mssql()
        print("   ✅ Connected to MS SQL")
    except Exception as e:
        print(f"   ❌ Failed: {str(e)}")
        sys.exit(1)
    
    print("🔌 Connecting to PostgreSQL...")
    try:
        pg_conn = connect_postgres()
        print("   ✅ Connected to PostgreSQL")
    except Exception as e:
        print(f"   ❌ Failed: {str(e)}")
        sys.exit(1)
    
    # Get CDC-enabled tables
    print("\n📊 Finding CDC-enabled tables...")
    tables = get_cdc_enabled_tables(mssql_conn)
    
    if not tables:
        # Fallback: get all user tables
        cursor = mssql_conn.cursor()
        cursor.execute("""
            SELECT name FROM sys.tables 
            WHERE schema_id = SCHEMA_ID('dbo')
            AND is_ms_shipped = 0
            ORDER BY name
        """)
        tables = [row.name for row in cursor.fetchall()]
    
    print(f"   Found {len(tables)} tables: {', '.join(tables)}")
    
    # Replicate each table
    print("\n🔄 Replicating table schemas...")
    success_count = 0
    
    for table in tables:
        schema = get_mssql_schema(mssql_conn, table)
        if schema:
            if create_postgres_table(pg_conn, table, schema):
                success_count += 1
    
    # Summary
    print("\n" + "=" * 80)
    print(f"✅ Schema replication complete!")
    print(f"   Tables processed: {len(tables)}")
    print(f"   Successful: {success_count}")
    print(f"   Failed: {len(tables) - success_count}")
    print("=" * 80)
    print()
    print("Next steps:")
    print("1. Restart sink connector: curl -X POST http://localhost:8083/connectors/postgres-sink-connector/restart")
    print("2. Trigger data sync by updating MS SQL tables")
    print()
    
    # Close connections
    mssql_conn.close()
    pg_conn.close()

if __name__ == "__main__":
    main()
