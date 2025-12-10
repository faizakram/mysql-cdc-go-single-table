# PostgreSQL Data Type Optimization Guide

## Current Data Type Mapping

The Debezium JDBC Sink Connector maps MS SQL data types to PostgreSQL as follows:

| MS SQL Type | PostgreSQL Type (Current) | Optimal PostgreSQL Type |
|-------------|---------------------------|-------------------------|
| UNIQUEIDENTIFIER | text | **UUID** |
| NVARCHAR(MAX) with JSON | text | **JSONB** |
| VARCHAR(MAX) with JSON | text | **JSONB** |
| XML | text | **XML** or **JSONB** |
| GEOGRAPHY | Not supported | PostGIS **GEOGRAPHY** |
| GEOMETRY | Not supported | PostGIS **GEOMETRY** |

## Why TEXT Instead of Native Types?

The JDBC Sink Connector uses TEXT for:
1. **Compatibility** - Works with all string-like data
2. **Schema Evolution** - Easier to handle changes
3. **No Data Loss** - Preserves exact values

## Solution: Post-Migration Type Conversion

### Option 1: Create Views with Type Casting (Recommended)

Create views that cast TEXT columns to proper types for application use:

```sql
-- View with proper UUID and JSONB types
CREATE VIEW dbo.modern_data_types_typed AS
SELECT 
    id,
    user_id::UUID AS user_id,                      -- Cast to UUID
    session_id::UUID AS session_id,
    transaction_id::UUID AS transaction_id,
    user_preferences::JSONB AS user_preferences,   -- Cast to JSONB
    api_response::JSONB AS api_response,
    metadata::JSONB AS metadata,
    xml_config::XML AS xml_config,                 -- Cast to XML
    user_name,
    email,
    is_active,
    created_at,
    CASE 
        WHEN last_login_id IS NOT NULL 
        THEN last_login_id::UUID 
    END AS last_login_id,
    CASE 
        WHEN settings IS NOT NULL 
        THEN settings::JSONB 
    END AS settings
FROM dbo.modern_data_types
WHERE __cdc_deleted IS NULL OR __cdc_deleted = 'false';

-- Now query with proper types
SELECT 
    user_id,                          -- UUID type
    user_preferences->'theme',        -- JSONB operations
    user_preferences->>'language'     -- Extract JSON text
FROM dbo.modern_data_types_typed;
```

### Option 2: Alter Table After Initial Load

After CDC completes initial snapshot, convert columns to native types:

```sql
-- Stop writes temporarily
-- ALTER TABLE ... is blocking operation

BEGIN;

-- Create new columns with proper types
ALTER TABLE dbo.modern_data_types 
    ADD COLUMN user_id_uuid UUID,
    ADD COLUMN session_id_uuid UUID,
    ADD COLUMN transaction_id_uuid UUID,
    ADD COLUMN user_preferences_json JSONB,
    ADD COLUMN api_response_json JSONB,
    ADD COLUMN metadata_json JSONB;

-- Populate new columns
UPDATE dbo.modern_data_types SET
    user_id_uuid = user_id::UUID,
    session_id_uuid = session_id::UUID,
    transaction_id_uuid = transaction_id::UUID,
    user_preferences_json = user_preferences::JSONB,
    api_response_json = api_response::JSONB,
    metadata_json = metadata::JSONB;

-- Drop old columns and rename new ones
ALTER TABLE dbo.modern_data_types 
    DROP COLUMN user_id,
    DROP COLUMN session_id,
    DROP COLUMN transaction_id,
    DROP COLUMN user_preferences,
    DROP COLUMN api_response,
    DROP COLUMN metadata;

ALTER TABLE dbo.modern_data_types 
    RENAME COLUMN user_id_uuid TO user_id;
ALTER TABLE dbo.modern_data_types 
    RENAME COLUMN session_id_uuid TO session_id;
ALTER TABLE dbo.modern_data_types 
    RENAME COLUMN transaction_id_uuid TO transaction_id;
ALTER TABLE dbo.modern_data_types 
    RENAME COLUMN user_preferences_json TO user_preferences;
ALTER TABLE dbo.modern_data_types 
    RENAME COLUMN api_response_json TO api_response;
ALTER TABLE dbo.modern_data_types 
    RENAME COLUMN metadata_json TO metadata;

COMMIT;

-- ⚠️ WARNING: This breaks CDC! 
-- Connector will fail because column types changed.
-- Use this only for one-time migrations, not ongoing CDC.
```

### Option 3: Use Materialized View with Refresh

For read-heavy workloads:

```sql
CREATE MATERIALIZED VIEW dbo.modern_data_types_optimized AS
SELECT 
    id,
    user_id::UUID,
    session_id::UUID,
    transaction_id::UUID,
    user_preferences::JSONB,
    api_response::JSONB,
    metadata::JSONB,
    xml_config::XML,
    user_name,
    email,
    is_active,
    created_at,
    last_login_id::UUID,
    settings::JSONB
FROM dbo.modern_data_types
WHERE __cdc_deleted IS NULL OR __cdc_deleted = 'false';

-- Create index on UUID columns
CREATE INDEX idx_modern_user_id ON dbo.modern_data_types_optimized(user_id);
CREATE INDEX idx_modern_session_id ON dbo.modern_data_types_optimized(session_id);

-- Create GIN index for JSONB queries
CREATE INDEX idx_modern_preferences ON dbo.modern_data_types_optimized 
    USING GIN (user_preferences);
CREATE INDEX idx_modern_api_response ON dbo.modern_data_types_optimized 
    USING GIN (api_response);

-- Refresh periodically (can be automated)
REFRESH MATERIALIZED VIEW CONCURRENTLY dbo.modern_data_types_optimized;
```

## JSON/JSONB Query Examples

Even with TEXT storage, you can cast and query:

```sql
-- Extract JSON field
SELECT 
    user_name,
    (user_preferences::JSONB)->>'theme' AS theme,
    (user_preferences::JSONB)->>'language' AS language
FROM dbo.modern_data_types;

-- JSON path queries
SELECT 
    user_name,
    (api_response::JSONB)#>'{data,userId}' AS user_id_from_json,
    (api_response::JSONB)#>'{data,roles}' AS roles_array
FROM dbo.modern_data_types
WHERE (api_response::JSONB)->>'status' = 'success';

-- JSON array operations
SELECT 
    user_name,
    jsonb_array_elements_text(
        (api_response::JSONB)#>'{data,roles}'
    ) AS role
FROM dbo.modern_data_types
WHERE (api_response::JSONB) ? 'data';
```

## UUID Query Examples

```sql
-- UUID comparisons
SELECT * FROM dbo.modern_data_types 
WHERE user_id::UUID = '6ba7b810-9dad-11d1-80b4-00c04fd430c8';

-- UUID generation
SELECT user_name, user_id::UUID AS old_id, gen_random_uuid() AS new_id
FROM dbo.modern_data_types;

-- UUID validation
SELECT 
    user_name,
    user_id,
    CASE 
        WHEN user_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        THEN 'Valid UUID'
        ELSE 'Invalid UUID'
    END AS uuid_validity
FROM dbo.modern_data_types;
```

## Performance Optimization

### Create Functional Indexes on Casted Columns

```sql
-- Index on UUID cast (for WHERE clauses)
CREATE INDEX idx_modern_user_id_uuid ON dbo.modern_data_types 
    ((user_id::UUID));

-- Index on JSON field extraction
CREATE INDEX idx_modern_theme ON dbo.modern_data_types 
    (((user_preferences::JSONB)->>'theme'));

-- GIN index for JSON containment queries
CREATE INDEX idx_modern_preferences_gin ON dbo.modern_data_types 
    USING GIN ((user_preferences::JSONB));
```

### Use Statistics on Casted Expressions

```sql
-- Create statistics for query planner
CREATE STATISTICS modern_uuid_stats ON (user_id::UUID) 
    FROM dbo.modern_data_types;

CREATE STATISTICS modern_json_stats ON ((user_preferences::JSONB)->>'theme') 
    FROM dbo.modern_data_types;

-- Analyze the table
ANALYZE dbo.modern_data_types;
```

## Best Practices

### ✅ DO:
1. **Use Views for Type Safety** - Create typed views for applications
2. **Cast in Queries** - Use `::UUID` and `::JSONB` in SELECT statements
3. **Create Functional Indexes** - Index on casted expressions
4. **Validate Data** - Ensure TEXT contains valid UUID/JSON format
5. **Document Type Mappings** - Keep schema documentation updated

### ❌ DON'T:
1. **Don't ALTER original CDC tables** - Breaks replication
2. **Don't store invalid JSON** - Validate before insert
3. **Don't mix formats** - Be consistent with UUID format (uppercase/lowercase)
4. **Don't forget indexes** - Casting is expensive without indexes

## Application Integration

### Python Example (psycopg2/psycopg3)

```python
import psycopg2
from psycopg2.extras import Json, RealDictCursor
import uuid

# Connect
conn = psycopg2.connect(
    host="localhost",
    database="target_db",
    user="admin",
    password="admin123"
)

# Query with automatic type conversion
with conn.cursor(cursor_factory=RealDictCursor) as cur:
    # Cast in query
    cur.execute("""
        SELECT 
            id,
            user_id::UUID,
            user_preferences::JSONB,
            api_response::JSONB
        FROM dbo.modern_data_types
        WHERE user_id::UUID = %s
    """, (uuid.UUID('6ba7b810-9dad-11d1-80b4-00c04fd430c8'),))
    
    result = cur.fetchone()
    print(f"User ID: {result['user_id']}")  # UUID object
    print(f"Theme: {result['user_preferences']['theme']}")  # dict
```

### Node.js Example (pg)

```javascript
const { Pool } = require('pg');

const pool = new Pool({
  host: 'localhost',
  database: 'target_db',
  user: 'admin',
  password: 'admin123'
});

async function getUser(userId) {
  const result = await pool.query(`
    SELECT 
      id,
      user_id::UUID,
      user_preferences::JSONB,
      api_response::JSONB
    FROM dbo.modern_data_types
    WHERE user_id::UUID = $1
  `, [userId]);
  
  const row = result.rows[0];
  console.log('User ID:', row.user_id);           // UUID string
  console.log('Theme:', row.user_preferences.theme);  // JSON object
}
```

### Java Example (JDBC)

```java
import java.sql.*;
import java.util.UUID;
import org.postgresql.util.PGobject;

Connection conn = DriverManager.getConnection(
    "jdbc:postgresql://localhost:5432/target_db",
    "admin", "admin123"
);

PreparedStatement stmt = conn.prepareStatement(
    "SELECT id, user_id::UUID, user_preferences::JSONB " +
    "FROM dbo.modern_data_types WHERE user_id::UUID = ?"
);

stmt.setObject(1, UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"));

ResultSet rs = stmt.executeQuery();
while (rs.next()) {
    UUID userId = (UUID) rs.getObject("user_id");
    PGobject json = (PGobject) rs.getObject("user_preferences");
    System.out.println("User ID: " + userId);
    System.out.println("JSON: " + json.getValue());
}
```

## Migration Script Template

Save this as `optimize_data_types.sql`:

```sql
-- ============================================================================
-- PostgreSQL Data Type Optimization
-- Converts TEXT columns to native UUID and JSONB types after CDC load
-- ============================================================================

-- Configuration
SET search_path TO dbo;

-- 1. Create typed view for immediate use
CREATE OR REPLACE VIEW modern_data_types_typed AS
SELECT 
    id,
    user_id::UUID,
    session_id::UUID,
    transaction_id::UUID,
    user_preferences::JSONB,
    api_response::JSONB,
    metadata::JSONB,
    user_name,
    email,
    is_active,
    created_at,
    CASE WHEN last_login_id IS NOT NULL THEN last_login_id::UUID END AS last_login_id,
    CASE WHEN settings IS NOT NULL THEN settings::JSONB END AS settings
FROM modern_data_types
WHERE __cdc_deleted IS NULL OR __cdc_deleted = 'false';

-- 2. Create indexes on casted columns
CREATE INDEX IF NOT EXISTS idx_modern_user_id_cast 
    ON modern_data_types ((user_id::UUID));

CREATE INDEX IF NOT EXISTS idx_modern_session_id_cast 
    ON modern_data_types ((session_id::UUID));

CREATE INDEX IF NOT EXISTS idx_modern_preferences_gin 
    ON modern_data_types USING GIN ((user_preferences::JSONB));

CREATE INDEX IF NOT EXISTS idx_modern_api_response_gin 
    ON modern_data_types USING GIN ((api_response::JSONB));

-- 3. Create statistics
CREATE STATISTICS IF NOT EXISTS modern_uuid_stats 
    ON (user_id::UUID), (session_id::UUID) 
    FROM modern_data_types;

CREATE STATISTICS IF NOT EXISTS modern_json_stats 
    ON ((user_preferences::JSONB)->>'theme'), ((api_response::JSONB)->>'status') 
    FROM modern_data_types;

-- 4. Analyze
ANALYZE modern_data_types;

-- 5. Grant permissions
GRANT SELECT ON modern_data_types_typed TO PUBLIC;

-- 6. Create helper functions
CREATE OR REPLACE FUNCTION get_user_preference(p_user_id UUID, p_key TEXT)
RETURNS TEXT AS $$
    SELECT (user_preferences::JSONB)->>p_key 
    FROM modern_data_types_typed 
    WHERE user_id = p_user_id;
$$ LANGUAGE SQL STABLE;

-- Done!
SELECT 'Data type optimization complete!' AS status;
```

Run with:
```bash
docker exec -i postgres18 psql -U admin -d target_db -f optimize_data_types.sql
```

## Summary

| Aspect | Current (TEXT) | Optimized (Native Types) |
|--------|---------------|-------------------------|
| **Storage** | More space | Less space (UUID = 16 bytes vs TEXT) |
| **Query Performance** | Slower (needs casting) | Faster (with indexes) |
| **Type Safety** | Runtime errors | Compile-time validation |
| **JSON Operations** | Must cast each time | Direct JSONB operators |
| **CDC Compatible** | ✅ Yes | ⚠️ Only with views |
| **Recommended For** | Active CDC replication | Read-heavy applications |

**Recommendation:**  
For ongoing CDC, use **Option 1 (Views)** to get native type benefits without breaking replication.

---

**Last Updated:** December 10, 2025
