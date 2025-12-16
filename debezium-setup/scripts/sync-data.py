#!/usr/bin/env python3

"""
Full-Load Data Synchronization Script
Direct MS SQL → PostgreSQL replication with transformations
Supports snake_case, UUID, and JSON conversions
Supports resume from failure point with truncate for partial tables
"""

import pyodbc
import psycopg2
from psycopg2.extras import execute_batch
import sys
import os
import re
import argparse
from pathlib import Path
from datetime import datetime
import json
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
CDC_ENABLED = os.getenv('CDC_ENABLED', 'true').lower() == 'true'

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

# Target schema configuration
POSTGRES_SCHEMA = os.getenv('POSTGRES_SCHEMA', 'dbo')

# Get UUID and JSON columns from environment
UUID_COLUMNS = [col.strip() for col in os.getenv('UUID_COLUMNS', '').split(',') if col.strip()]
JSON_COLUMNS = [col.strip() for col in os.getenv('JSON_COLUMNS', '').split(',') if col.strip()]

# Batch size for inserts
BATCH_SIZE = int(os.getenv('SYNC_BATCH_SIZE', '1000'))

def camel_to_snake(name):
    """Convert CamelCase to snake_case"""
    # Insert underscore before uppercase letters (except the first one)
    s1 = re.sub('(.)([A-Z][a-z]+)', r'\1_\2', name)
    # Insert underscore before uppercase letters preceded by lowercase letters
    return re.sub('([a-z0-9])([A-Z])', r'\1_\2', s1).lower()

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
                print(f"ℹ️  Connected using driver: {driver}")
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
    try:
        return psycopg2.connect(**POSTGRES_CONFIG)
    except Exception as e:
        print(f"❌ Failed to connect to PostgreSQL: {e}")
        sys.exit(1)

def get_tables_to_sync(mssql_conn):
    """Get list of tables to sync"""
    cursor = mssql_conn.cursor()
    
    # Get all user tables (not CDC-specific)
    query = """
    SELECT 
        s.name AS schema_name,
        t.name AS table_name
    FROM sys.tables t
    INNER JOIN sys.schemas s ON t.schema_id = s.schema_id
    WHERE t.is_ms_shipped = 0
    AND s.name NOT IN ('cdc', 'sys')
    ORDER BY s.name, t.name
    """
    
    cursor.execute(query)
    tables = [(row.schema_name, row.table_name) for row in cursor.fetchall()]
    cursor.close()
    
    return tables

def get_table_columns(mssql_conn, schema, table):
    """Get column information for a table"""
    cursor = mssql_conn.cursor()
    
    query = """
    SELECT 
        c.COLUMN_NAME,
        c.DATA_TYPE,
        c.IS_NULLABLE
    FROM INFORMATION_SCHEMA.COLUMNS c
    WHERE c.TABLE_SCHEMA = ? AND c.TABLE_NAME = ?
    ORDER BY c.ORDINAL_POSITION
    """
    
    cursor.execute(query, (schema, table))
    columns = []
    for row in cursor.fetchall():
        columns.append({
            'name': row.COLUMN_NAME,
            'type': row.DATA_TYPE,
            'nullable': row.IS_NULLABLE == 'YES'
        })
    
    cursor.close()
    return columns

def transform_value(value, column_name, data_type):
    """Transform value based on column type and configuration"""
    if value is None:
        return None
    
    # UUID transformation
    if column_name.lower() in [col.lower() for col in UUID_COLUMNS]:
        if isinstance(value, str):
            return value
        return str(value)
    
    # JSON transformation
    if column_name.lower() in [col.lower() for col in JSON_COLUMNS]:
        if isinstance(value, str):
            return value
        return json.dumps(value) if value else None
    
    # UNIQUEIDENTIFIER to string (will be cast to UUID in PostgreSQL)
    if data_type == 'uniqueidentifier':
        return str(value)
    
    # DATETIMEOFFSET - convert to string (PostgreSQL will store as TIMESTAMPTZ)
    if data_type == 'datetimeoffset':
        return str(value) if value else None
    
    return value

def sync_table_data(mssql_conn, postgres_conn, schema, table, columns, progress_tracker=None, truncate_partial=True):
    """Sync data from MS SQL table to PostgreSQL"""
    table_full_name = f"{schema}.{table}"
    pg_table_name = camel_to_snake(table)
    pg_full_table = f"{POSTGRES_SCHEMA}.{pg_table_name}"
    
    # Mark as started
    if progress_tracker:
        progress_tracker.mark_table_started(table, "data")
    
    print(f"\n📊 Syncing {table_full_name} → {pg_full_table}")
    
    # Get source column names
    source_columns = [col['name'] for col in columns]
    target_columns = [camel_to_snake(col['name']) for col in columns]
    target_columns_quoted = [quote_identifier(col) for col in target_columns]
    
    # Build SELECT query for MS SQL - convert datetimeoffset to string
    select_columns = []
    for col in columns:
        if col['type'] == 'datetimeoffset':
            select_columns.append(f"CONVERT(VARCHAR(50), [{col['name']}], 127) AS [{col['name']}]")
        else:
            select_columns.append(f"[{col['name']}]")
    
    select_query = f"""
    SELECT {', '.join(select_columns)}
    FROM [{schema}].[{table}]
    """
    
    # Build INSERT query for PostgreSQL with quoted identifiers
    placeholders = ', '.join(['%s'] * len(target_columns_quoted))
    insert_query = f"""
    INSERT INTO {pg_full_table} ({', '.join(target_columns_quoted)})
    VALUES ({placeholders})
    """
    
    try:
        # Fetch data from MS SQL
        print("  🔍 Reading from MS SQL...")
        mssql_cursor = mssql_conn.cursor()
        mssql_cursor.execute(select_query)
        
        # Truncate target table
        print("  🗑️  Truncating target table...")
        pg_cursor = postgres_conn.cursor()
        pg_cursor.execute(f"TRUNCATE TABLE {pg_full_table} CASCADE")
        postgres_conn.commit()
        
        # Batch insert into PostgreSQL
        print("  📝 Writing to PostgreSQL...")
        rows_synced = 0
        batch = []
        
        while True:
            row = mssql_cursor.fetchone()
            if row is None:
                # Insert remaining batch
                if batch:
                    execute_batch(pg_cursor, insert_query, batch, page_size=BATCH_SIZE)
                    postgres_conn.commit()
                    rows_synced += len(batch)
                break
            
            # Transform row values
            transformed_row = []
            for i, value in enumerate(row):
                col_name = source_columns[i]
                col_type = columns[i]['type']
                transformed_value = transform_value(value, col_name, col_type)
                transformed_row.append(transformed_value)
            
            batch.append(tuple(transformed_row))
            
            # Insert batch when size reached
            if len(batch) >= BATCH_SIZE:
                execute_batch(pg_cursor, insert_query, batch, page_size=BATCH_SIZE)
                postgres_conn.commit()
                rows_synced += len(batch)
                print(f"  ✓ {rows_synced} rows synced...", end='\r')
                batch = []
        
        mssql_cursor.close()
        pg_cursor.close()
        
        print(f"  ✅ {rows_synced} rows synced successfully")
        if progress_tracker:
            progress_tracker.mark_table_completed(table, "data", rows_synced)
        return rows_synced
        
    except Exception as e:
        error_msg = str(e)
        print(f"  ❌ Error syncing {table_full_name}: {error_msg}")
        postgres_conn.rollback()
        if progress_tracker:
            progress_tracker.mark_table_failed(table, "data", error_msg)
        return 0

def main():
    """Main synchronization process"""
    # Parse command line arguments
    parser = argparse.ArgumentParser(
        description='Sync MS SQL data to PostgreSQL with resume capability',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Normal run (syncs all tables)
  python sync-data.py
  
  # Resume from last failure (truncates partial tables)
  python sync-data.py --resume
  
  # Start from specific table
  python sync-data.py --start-from-table Orders
  
  # Resume without truncating partial tables
  python sync-data.py --resume --no-truncate
  
  # Reset progress and start fresh
  python sync-data.py --reset
        """)
    parser.add_argument('--resume', action='store_true',
                       help='Resume from last failed/incomplete table (truncates partial data)')
    parser.add_argument('--start-from-table', type=str, metavar='TABLE',
                       help='Start processing from specific table (skips previous tables)')
    parser.add_argument('--no-truncate', action='store_true',
                       help='Do not truncate partial tables when resuming')
    parser.add_argument('--reset', action='store_true',
                       help='Reset progress and start fresh')
    args = parser.parse_args()
    
    # Initialize progress tracker
    progress_tracker = ProgressTracker("data_sync_progress.json")
    
    if args.reset:
        progress_tracker.reset_progress()
        return
    
    print("=" * 60)
    print("MS SQL → PostgreSQL Full-Load Data Synchronization")
    print("=" * 60)
    
    if CDC_ENABLED:
        print("⚙️  Mode: CDC-enabled tables only")
    else:
        print("⚙️  Mode: All tables (full-load)")
    
    print(f"📦 Batch size: {BATCH_SIZE}")
    print(f"🔧 UUID columns: {', '.join(UUID_COLUMNS) if UUID_COLUMNS else 'None'}")
    print(f"🔧 JSON columns: {', '.join(JSON_COLUMNS) if JSON_COLUMNS else 'None'}")
    
    # Connect to databases
    print("\n🔌 Connecting to databases...")
    mssql_conn = connect_mssql()
    postgres_conn = connect_postgres()
    print("✅ Connected to MS SQL and PostgreSQL")
    
    # Get tables to sync
    print("\n📋 Getting tables to sync...")
    tables = get_tables_to_sync(mssql_conn)
    
    if not tables:
        print("⚠️  No tables found to sync!")
        sys.exit(1)
    
    print(f"✅ Found {len(tables)} tables to sync:")
    for schema, table in tables:
        print(f"   • {schema}.{table}")
    
    # Display progress
    progress_tracker.display_progress("data", len(tables))
    
    # Determine starting point
    start_index = 0
    table_names = [table for schema, table in tables]
    
    if args.resume:
        last_failed = progress_tracker.get_last_failed_table("data")
        if last_failed and last_failed in table_names:
            start_index = table_names.index(last_failed)
            print(f"📍 Resuming from last failure point: {last_failed} (table {start_index + 1}/{len(tables)})")
            
            # Check if partial data exists and truncate if needed
            if not args.no_truncate and progress_tracker.is_table_partial(last_failed, "data"):
                print(f"⚠️  Table {last_failed} has partial data - will truncate before re-sync\n")
        else:
            print("✅ No failed tables found, processing remaining tables\n")
    elif args.start_from_table:
        if args.start_from_table in table_names:
            start_index = table_names.index(args.start_from_table)
            print(f"📍 Starting from specified table: {args.start_from_table} (table {start_index + 1}/{len(tables)})\n")
        else:
            print(f"⚠️  Warning: Table '{args.start_from_table}' not found in table list")
            print(f"Available tables: {', '.join(table_names)}")
            sys.exit(1)
    
    # Sync each table
    start_time = datetime.now()
    total_rows = 0
    successful_tables = 0
    skipped_count = 0
    
    print("\n🔄 Starting synchronization...")
    
    for idx, (schema, table) in enumerate(tables, 1):
        # Skip tables before start_index
        if idx - 1 < start_index:
            skipped_count += 1
            continue
        
        # Skip if already completed (unless --resume flag used)
        if not args.resume and progress_tracker.is_table_completed(table, "data"):
            status = progress_tracker.get_table_status(table, "data")
            print(f"⏭️  Skipping {schema}.{table} (already completed) [{idx}/{len(tables)}]")
            skipped_count += 1
            continue
        
        print(f"\n[{idx}/{len(tables)}] Processing: {schema}.{table}")
        
        try:
            columns = get_table_columns(mssql_conn, schema, table)
            rows = sync_table_data(mssql_conn, postgres_conn, schema, table, columns, 
                                 progress_tracker, truncate_partial=not args.no_truncate)
            total_rows += rows
            if rows >= 0:
                successful_tables += 1
        except Exception as e:
            print(f"❌ Failed to sync {schema}.{table}: {e}")
            if progress_tracker:
                progress_tracker.mark_table_failed(table, "data", str(e))
        
        # Show progress periodically
        if idx % 10 == 0:
            progress_tracker.display_progress("data", len(tables))
    
    # Close connections
    mssql_conn.close()
    postgres_conn.close()
    
    # Final progress display
    progress_tracker.display_progress("data", len(tables))
    
    # Summary
    end_time = datetime.now()
    duration = (end_time - start_time).total_seconds()
    
    # Calculate actual processed and failed counts
    actually_processed = successful_tables
    failed_tables = len(tables) - successful_tables - skipped_count
    
    print("\n" + "=" * 60)
    print("📊 Synchronization Summary")
    print("=" * 60)
    print(f"Total tables: {len(tables)}")
    print(f"✅ Processed successfully: {actually_processed}")
    print(f"⏭️  Skipped (already done): {skipped_count}")
    print(f"❌ Failed: {failed_tables}")
    print(f"📝 Total rows synced: {total_rows:,}")
    print(f"⏱️  Duration: {duration:.2f} seconds")
    if duration > 0 and total_rows > 0:
        print(f"⚡ Throughput: {total_rows/duration:.0f} rows/second")
    print("=" * 60)
    
    if successful_tables + skipped_count == len(tables):
        if skipped_count > 0:
            print(f"✅ All tables complete ({actually_processed} synced, {skipped_count} already done)")
        else:
            print("✅ All tables synchronized successfully!")
        sys.exit(0)
    else:
        print(f"⚠️  {failed_tables} tables failed to sync")
        print(f"💡 Use --resume to continue from failure point")
        sys.exit(1)

if __name__ == "__main__":
    main()
