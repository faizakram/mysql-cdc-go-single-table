#!/usr/bin/env python3

"""
Dynamic Schema Replication Script
Automatically reads MS SQL schema and creates equivalent PostgreSQL tables
No manual intervention needed - fully dynamic!
Supports resume from failure point and manual table selection
"""

import pyodbc
import psycopg2
import sys
import time
import os
import argparse
from pathlib import Path
from progress import ProgressTracker

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

# Configuration from environment variables
MSSQL_HOST = os.getenv('MSSQL_HOST', 'localhost')
if MSSQL_HOST == 'host.docker.internal':
    MSSQL_HOST = 'localhost'

MSSQL_CONFIG = {
    'server': f"{MSSQL_HOST},{os.getenv('MSSQL_PORT', '1433')}",
    'database': os.getenv('MSSQL_DATABASE', 'Employees'),
    'username': os.getenv('MSSQL_USER', 'sa'),
    'password': os.getenv('MSSQL_PASSWORD', 'YourStrong@Passw0rd'),
    'driver': os.getenv('MSSQL_DRIVER', '{ODBC Driver 18 for SQL Server}'),
    'TrustServerCertificate': 'yes'
}

POSTGRES_HOST = os.getenv('POSTGRES_HOST', 'localhost')
if POSTGRES_HOST == 'host.docker.internal':
    POSTGRES_HOST = 'localhost'

POSTGRES_CONFIG = {
    'host': POSTGRES_HOST,
    'port': int(os.getenv('POSTGRES_PORT', '5432')),
    'database': os.getenv('POSTGRES_DATABASE', 'target_db'),
    'user': os.getenv('POSTGRES_USER', 'postgres'),
    'password': os.getenv('POSTGRES_PASSWORD', 'postgres')
}

# Schema configuration
MSSQL_SCHEMA = os.getenv('MSSQL_SCHEMA', 'dbo')
POSTGRES_SCHEMA = os.getenv('POSTGRES_SCHEMA', 'dbo')

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
    """Connect to MS SQL Server with automatic driver detection"""
    # List of drivers to try in order of preference
    drivers = [
        MSSQL_CONFIG['driver'],  # User-specified or default from env
        '{ODBC Driver 18 for SQL Server}',
        '{ODBC Driver 17 for SQL Server}',
        '{ODBC Driver 13 for SQL Server}',
        '{SQL Server Native Client 11.0}',
        '{SQL Server}'
    ]
    
    # Remove duplicates while preserving order
    seen = set()
    drivers = [x for x in drivers if not (x in seen or seen.add(x))]
    
    last_error = None
    for driver in drivers:
        conn_str = (
            f"DRIVER={driver};"
            f"SERVER={MSSQL_CONFIG['server']};"
            f"DATABASE={MSSQL_CONFIG['database']};"
            f"UID={MSSQL_CONFIG['username']};"
            f"PWD={MSSQL_CONFIG['password']};"
            f"TrustServerCertificate={MSSQL_CONFIG['TrustServerCertificate']};"
        )
        try:
            conn = pyodbc.connect(conn_str, timeout=10)
            # Connection successful
            if driver != MSSQL_CONFIG['driver']:
                print(f"   ℹ️  Using ODBC driver: {driver}")
            return conn
        except pyodbc.Error as e:
            last_error = e
            # Driver not found or other error, try next driver
            continue
    
    # If we get here, all drivers failed
    print(f"❌ Failed to connect to MS SQL: {last_error}")
    print(f"\n💡 Available ODBC drivers on this system:")
    try:
        available_drivers = pyodbc.drivers()
        for drv in available_drivers:
            print(f"   • {drv}")
        print(f"\n📥 Install Microsoft ODBC Driver for SQL Server:")
        print(f"   Windows: https://learn.microsoft.com/en-us/sql/connect/odbc/download-odbc-driver-for-sql-server")
        print(f"   Linux: sudo apt-get install msodbcsql18 or sudo yum install msodbcsql18")
    except:
        pass
    sys.exit(1)

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

# PostgreSQL reserved keywords that need to be quoted
POSTGRES_RESERVED_KEYWORDS = {
    'all', 'analyse', 'analyze', 'and', 'any', 'array', 'as', 'asc', 'asymmetric',
    'authorization', 'between', 'binary', 'both', 'case', 'cast', 'check', 'collate',
    'collation', 'column', 'concurrently', 'constraint', 'create', 'cross',
    'current_catalog', 'current_date', 'current_role', 'current_schema',
    'current_time', 'current_timestamp', 'current_user', 'default', 'deferrable',
    'desc', 'distinct', 'do', 'else', 'end', 'except', 'false', 'fetch', 'for',
    'foreign', 'freeze', 'from', 'full', 'grant', 'group', 'having', 'ilike', 'in',
    'initially', 'inner', 'intersect', 'into', 'is', 'isnull', 'join', 'lateral',
    'leading', 'left', 'like', 'limit', 'localtime', 'localtimestamp', 'natural',
    'not', 'notnull', 'null', 'offset', 'on', 'only', 'or', 'order', 'outer',
    'overlaps', 'placing', 'primary', 'references', 'returning', 'right', 'select',
    'session_user', 'similar', 'some', 'symmetric', 'table', 'tablesample', 'then',
    'to', 'trailing', 'true', 'union', 'unique', 'user', 'using', 'variadic',
    'verbose', 'when', 'where', 'window', 'with'
}

def quote_identifier(name):
    """Quote identifier if it's a PostgreSQL reserved keyword"""
    if name.lower() in POSTGRES_RESERVED_KEYWORDS:
        return f'"{name}"'
    return name

def create_postgres_table(pg_conn, table_name, schema, progress_tracker=None):
    """Create PostgreSQL table from MS SQL schema"""
    cursor = pg_conn.cursor()
    
    # Mark as started
    if progress_tracker:
        progress_tracker.mark_table_started(table_name, "schema")
    
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
        
        # Quote column name if it's a reserved keyword
        quoted_col_name = quote_identifier(col_name)
        col_def = f"{quoted_col_name} {pg_type}"
        
        if is_pk:
            col_def += " NOT NULL"
            pk_columns.append(quoted_col_name)
        elif not is_nullable:
            col_def += " NOT NULL"
        
        columns.append(col_def)
    
    # Add CDC deleted column
    columns.append("__cdc_deleted TEXT DEFAULT 'false'")
    
    # Build full CREATE TABLE statement
    create_sql = f"CREATE TABLE IF NOT EXISTS {POSTGRES_SCHEMA}.{pg_table_name} (\n    "
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
        if progress_tracker:
            progress_tracker.mark_table_completed(table_name, "schema")
        return True
    except Exception as e:
        error_msg = str(e)
        print(f"   ❌ Error: {error_msg}")
        pg_conn.rollback()
        if progress_tracker:
            progress_tracker.mark_table_failed(table_name, "schema", error_msg)
        return False

def get_cdc_enabled_tables(conn):
    """Get list of CDC-enabled tables (returns empty list if CDC not enabled)"""
    cursor = conn.cursor()
    try:
        # Check if CDC is enabled on database
        cursor.execute("SELECT is_cdc_enabled FROM sys.databases WHERE name = DB_NAME()")
        result = cursor.fetchone()
        if not result or not result[0]:
            # CDC not enabled, return empty list
            return []
        
        # Get CDC-enabled tables
        query = """
        SELECT DISTINCT
            OBJECT_NAME(ct.source_object_id) AS table_name
        FROM cdc.change_tables ct
        WHERE ct.source_object_id IS NOT NULL
        """
        cursor.execute(query)
        return [row.table_name for row in cursor.fetchall()]
    except pyodbc.Error:
        # If any error (e.g., cdc schema doesn't exist), return empty list
        return []

def main():
    # Parse command line arguments
    parser = argparse.ArgumentParser(
        description='Replicate MS SQL schema to PostgreSQL with resume capability',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Normal run (starts from beginning or resumes automatically)
  python replicate-schema.py
  
  # Resume from last failure point
  python replicate-schema.py --resume
  
  # Start from specific table (skips previous tables)
  python replicate-schema.py --start-from-table Orders
  
  # Reset progress and start fresh
  python replicate-schema.py --reset
        """)
    parser.add_argument('--resume', action='store_true', 
                       help='Resume from last failed/incomplete table')
    parser.add_argument('--start-from-table', type=str, metavar='TABLE',
                       help='Start processing from specific table (skips previous tables)')
    parser.add_argument('--reset', action='store_true',
                       help='Reset progress and start fresh')
    args = parser.parse_args()
    
    # Initialize progress tracker
    progress_tracker = ProgressTracker("schema_replication_progress.json")
    
    if args.reset:
        progress_tracker.reset_progress()
        return
    
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
    print("\n📊 Finding tables to replicate...")
    tables = get_cdc_enabled_tables(mssql_conn)
    
    if not tables:
        # Fallback: get all user tables from the source schema
        print("   ℹ️  CDC not enabled, using all tables from source schema")
        mssql_schema = os.getenv('MSSQL_SCHEMA', 'dbo')
        cursor = mssql_conn.cursor()
        cursor.execute(f"""
            SELECT name FROM sys.tables 
            WHERE schema_id = SCHEMA_ID('{mssql_schema}')
            AND is_ms_shipped = 0
            ORDER BY name
        """)
        tables = [row.name for row in cursor.fetchall()]
        print(f"   Found {len(tables)} tables in schema '{mssql_schema}': {', '.join(tables)}")
    else:
        print(f"   Found {len(tables)} CDC-enabled tables: {', '.join(tables)}")
    
    # Display progress
    progress_tracker.display_progress("schema", len(tables))
    
    # Determine starting point
    start_index = 0
    if args.resume:
        last_failed = progress_tracker.get_last_failed_table("schema")
        if last_failed and last_failed in tables:
            start_index = tables.index(last_failed)
            print(f"📍 Resuming from last failure point: {last_failed} (table {start_index + 1}/{len(tables)})\n")
        else:
            print("✅ No failed tables found, processing remaining tables\n")
    elif args.start_from_table:
        if args.start_from_table in tables:
            start_index = tables.index(args.start_from_table)
            print(f"📍 Starting from specified table: {args.start_from_table} (table {start_index + 1}/{len(tables)})\n")
        else:
            print(f"⚠️  Warning: Table '{args.start_from_table}' not found in table list")
            print(f"Available tables: {', '.join(tables)}")
            sys.exit(1)
    
    # Replicate each table
    print("\n🔄 Replicating table schemas...")
    success_count = 0
    skipped_count = 0
    
    for idx, table in enumerate(tables, 1):
        # Skip tables before start_index
        if idx - 1 < start_index:
            skipped_count += 1
            continue
        
        # Skip if already completed (unless --resume flag used)
        if not args.resume and progress_tracker.is_table_completed(table, "schema"):
            print(f"⏭️  Skipping {table} (already completed) [{idx}/{len(tables)}]")
            success_count += 1
            skipped_count += 1
            continue
        
        print(f"\n[{idx}/{len(tables)}] Processing: {table}")
        schema = get_mssql_schema(mssql_conn, table)
        if schema:
            if create_postgres_table(pg_conn, table, schema, progress_tracker):
                success_count += 1
        
        # Show progress periodically
        if idx % 10 == 0:
            progress_tracker.display_progress("schema", len(tables))
    
    # Summary
    progress_tracker.display_progress("schema", len(tables))
    
    print("\n" + "=" * 80)
    print(f"✅ Schema replication complete!")
    print(f"   Total tables: {len(tables)}")
    print(f"   Successful: {success_count}")
    print(f"   Skipped: {skipped_count}")
    print(f"   Failed: {len(tables) - success_count - skipped_count}")
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
