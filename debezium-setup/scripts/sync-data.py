#!/usr/bin/env python3

"""
Full-Load Data Synchronization Script
Direct MS SQL → PostgreSQL replication with transformations
Supports snake_case, UUID, and JSON conversions
"""

import pyodbc
import psycopg2
from psycopg2.extras import execute_batch
import sys
import os
import re
from pathlib import Path
from datetime import datetime
import json

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
    'driver': '{ODBC Driver 18 for SQL Server}',
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
    try:
        return pyodbc.connect(conn_str, timeout=10)
    except Exception as e:
        print(f"❌ Failed to connect to MS SQL: {e}")
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

def sync_table_data(mssql_conn, postgres_conn, schema, table, columns):
    """Sync data from MS SQL table to PostgreSQL"""
    table_full_name = f"{schema}.{table}"
    pg_table_name = camel_to_snake(table)
    pg_full_table = f"{POSTGRES_SCHEMA}.{pg_table_name}"
    
    print(f"\n📊 Syncing {table_full_name} → {pg_full_table}")
    
    # Get source column names
    source_columns = [col['name'] for col in columns]
    target_columns = [camel_to_snake(col['name']) for col in columns]
    
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
    
    # Build INSERT query for PostgreSQL
    placeholders = ', '.join(['%s'] * len(target_columns))
    insert_query = f"""
    INSERT INTO {pg_full_table} ({', '.join(target_columns)})
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
        return rows_synced
        
    except Exception as e:
        print(f"  ❌ Error syncing {table_full_name}: {e}")
        postgres_conn.rollback()
        return 0

def main():
    """Main synchronization process"""
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
    
    # Sync each table
    start_time = datetime.now()
    total_rows = 0
    successful_tables = 0
    
    print("\n🔄 Starting synchronization...")
    
    for schema, table in tables:
        try:
            columns = get_table_columns(mssql_conn, schema, table)
            rows = sync_table_data(mssql_conn, postgres_conn, schema, table, columns)
            total_rows += rows
            if rows >= 0:
                successful_tables += 1
        except Exception as e:
            print(f"❌ Failed to sync {schema}.{table}: {e}")
    
    # Close connections
    mssql_conn.close()
    postgres_conn.close()
    
    # Summary
    end_time = datetime.now()
    duration = (end_time - start_time).total_seconds()
    
    print("\n" + "=" * 60)
    print("📊 Synchronization Summary")
    print("=" * 60)
    print(f"✅ Successful tables: {successful_tables}/{len(tables)}")
    print(f"📝 Total rows synced: {total_rows:,}")
    print(f"⏱️  Duration: {duration:.2f} seconds")
    if duration > 0:
        print(f"⚡ Throughput: {total_rows/duration:.0f} rows/second")
    print("=" * 60)
    
    if successful_tables == len(tables):
        print("✅ All tables synchronized successfully!")
        sys.exit(0)
    else:
        print(f"⚠️  {len(tables) - successful_tables} tables failed to sync")
        sys.exit(1)

if __name__ == "__main__":
    main()
