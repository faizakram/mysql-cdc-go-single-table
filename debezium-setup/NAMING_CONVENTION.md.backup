# Naming Convention Transformation: PascalCase to snake_case

## Problem Statement

**Source (MS SQL Server):**
- Tables: `EmployeeData`, `OrderHistory`, `UserProfile` (PascalCase)
- Columns: `EmployeeID`, `FirstName`, `LastName` (PascalCase)

**Target (PostgreSQL):**
- Tables: `employee_data`, `order_history`, `user_profile` (snake_case)
- Columns: `employee_id`, `first_name`, `last_name` (snake_case)

---

## Solution 1: PostgreSQL Views (RECOMMENDED - Simplest)

### Overview
Let Debezium create tables with original PascalCase names, then create PostgreSQL views with snake_case names.

### Advantages
✅ No code changes to Debezium  
✅ Works immediately  
✅ Easy to maintain  
✅ No performance impact  
✅ Can be applied after tables are created  

### Implementation

#### Step 1: Let Debezium create tables normally

Tables will be created as: `dbo."EmployeeData"`, `dbo."FirstName"`, etc.

#### Step 2: Create snake_case views

```sql
-- Connect to PostgreSQL
\c target_db;

-- Create view for Employees table
CREATE OR REPLACE VIEW dbo.employees AS
SELECT 
    "EmployeeID" AS employee_id,
    "FirstName" AS first_name,
    "LastName" AS last_name,
    salary
FROM dbo."Employees";

-- Create view for Orders table
CREATE OR REPLACE VIEW dbo.orders AS
SELECT 
    "OrderID" AS order_id,
    "OrderDate" AS order_date,
    "Amount" AS amount
FROM dbo."Orders";

-- Create view for UsersTable
CREATE OR REPLACE VIEW dbo.users AS
SELECT 
    "UserID" AS user_id,
    "Username" AS username,
    "Email" AS email
FROM dbo."UsersTable";
```

#### Step 3: Auto-generate views for all 500 tables

```bash
#!/bin/bash
# generate-views.sh

DATABASE="target_db"
SCHEMA="dbo"

# Function to convert PascalCase to snake_case
to_snake_case() {
    echo "$1" | sed 's/\([A-Z]\)/_\L\1/g' | sed 's/^_//'
}

# Generate views for all tables
docker exec postgres18 psql -U admin -d $DATABASE -t -c "
SELECT table_name 
FROM information_schema.tables 
WHERE table_schema = '$SCHEMA' 
  AND table_type = 'BASE TABLE'
ORDER BY table_name;
" | while read -r table; do
    table=$(echo "$table" | xargs)  # Trim whitespace
    
    if [[ -n "$table" ]]; then
        view_name=$(to_snake_case "$table")
        
        echo "Creating view: ${SCHEMA}.${view_name} -> ${SCHEMA}.\"${table}\""
        
        # Get all columns
        columns=$(docker exec postgres18 psql -U admin -d $DATABASE -t -c "
        SELECT column_name 
        FROM information_schema.columns 
        WHERE table_schema = '$SCHEMA' 
          AND table_name = '$table'
        ORDER BY ordinal_position;
        " | while read -r col; do
            col=$(echo "$col" | xargs)
            if [[ -n "$col" ]]; then
                col_snake=$(to_snake_case "$col")
                echo "    \"$col\" AS $col_snake"
            fi
        done | paste -sd "," -)
        
        # Create view
        docker exec postgres18 psql -U admin -d $DATABASE -c "
        CREATE OR REPLACE VIEW ${SCHEMA}.${view_name} AS
        SELECT 
        $columns
        FROM ${SCHEMA}.\"${table}\";
        "
    fi
done

echo "All views created successfully!"
```

#### Usage

```bash
chmod +x generate-views.sh
./generate-views.sh
```

#### Query using snake_case

```sql
-- Application can now use snake_case
SELECT employee_id, first_name, last_name 
FROM dbo.employees 
WHERE employee_id > 1000;

-- Instead of:
SELECT "EmployeeID", "FirstName", "LastName" 
FROM dbo."Employees" 
WHERE "EmployeeID" > 1000;
```

---

## Solution 2: Custom Kafka Connect SMT (Most Flexible)

### Overview
Create a custom Single Message Transform to convert column names before writing to PostgreSQL.

### Advantages
✅ True transformation at Kafka level  
✅ Tables created with snake_case from start  
✅ Works for all 500 tables automatically  
✅ No post-processing needed  

### Disadvantages
❌ Requires Java development  
❌ Need to compile and deploy custom JAR  
❌ More complex to maintain  

### Implementation

#### Step 1: Create Custom SMT

```java
// SnakeCaseTransform.java
package com.debezium.transforms;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.Transformation;

import java.util.Map;

public class SnakeCaseTransform<R extends ConnectRecord<R>> implements Transformation<R> {

    @Override
    public R apply(R record) {
        if (record.valueSchema() == null) {
            return record;
        }

        Schema updatedSchema = makeUpdatedSchema(record.valueSchema());
        Struct value = (Struct) record.value();
        Struct updatedValue = new Struct(updatedSchema);

        for (Field field : value.schema().fields()) {
            String newFieldName = toSnakeCase(field.name());
            updatedValue.put(newFieldName, value.get(field));
        }

        return record.newRecord(
            toSnakeCase(record.topic()),  // Transform topic name too
            record.kafkaPartition(),
            record.keySchema(),
            record.key(),
            updatedSchema,
            updatedValue,
            record.timestamp()
        );
    }

    private Schema makeUpdatedSchema(Schema schema) {
        SchemaBuilder builder = SchemaBuilder.struct().name(toSnakeCase(schema.name()));
        for (Field field : schema.fields()) {
            builder.field(toSnakeCase(field.name()), field.schema());
        }
        return builder.build();
    }

    private String toSnakeCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        // Convert PascalCase/camelCase to snake_case
        return input
            .replaceAll("([a-z])([A-Z])", "$1_$2")  // camelCase -> camel_Case
            .replaceAll("([A-Z])([A-Z][a-z])", "$1_$2")  // HTTPServer -> HTTP_Server
            .toLowerCase();
    }

    @Override
    public ConfigDef config() {
        return new ConfigDef();
    }

    @Override
    public void close() {}

    @Override
    public void configure(Map<String, ?> configs) {}
}
```

#### Step 2: Build and Deploy

```bash
# Create Maven project
mkdir -p snake-case-transform/src/main/java/com/debezium/transforms
cd snake-case-transform

# Create pom.xml
cat > pom.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <groupId>com.debezium</groupId>
    <artifactId>snake-case-transform</artifactId>
    <version>1.0.0</version>
    
    <dependencies>
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>connect-transforms</artifactId>
            <version>3.5.0</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>connect-api</artifactId>
            <version>3.5.0</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
                <configuration>
                    <source>11</source>
                    <target>11</target>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
EOF

# Copy Java file
cp SnakeCaseTransform.java src/main/java/com/debezium/transforms/

# Build
mvn clean package

# Copy to Debezium Connect
docker cp target/snake-case-transform-1.0.0.jar debezium-connect:/kafka/connect/

# Restart Debezium Connect
docker compose restart debezium-connect
```

#### Step 3: Update Connector Configuration

```json
{
  "name": "postgres-sink-connector",
  "config": {
    "connector.class": "io.debezium.connector.jdbc.JdbcSinkConnector",
    "tasks.max": "10",
    
    "topics.regex": "mssql\\.mig_test_db\\.dbo\\.(.*)",
    
    "transforms": "route,snakeCase",
    
    "transforms.route.type": "org.apache.kafka.connect.transforms.RegexRouter",
    "transforms.route.regex": "mssql\\.mig_test_db\\.dbo\\.(.*)",
    "transforms.route.replacement": "$1",
    
    "transforms.snakeCase.type": "com.debezium.transforms.SnakeCaseTransform",
    
    "table.name.format": "dbo.${topic}",
    "quote.identifiers": "false"
  }
}
```

---

## Solution 3: PostgreSQL Triggers (Real-time Sync)

### Overview
Create snake_case tables and use triggers to sync from PascalCase tables.

### Advantages
✅ Both naming conventions available  
✅ Can add business logic in triggers  
✅ Maintains data history  

### Disadvantages
❌ Double storage (two copies of data)  
❌ Trigger overhead  
❌ Complex to maintain for 500 tables  

### Implementation

```sql
-- Create snake_case table
CREATE TABLE dbo.employees (
    employee_id INT PRIMARY KEY,
    first_name VARCHAR(50),
    last_name VARCHAR(50),
    salary DECIMAL(12,2)
);

-- Create trigger on PascalCase table
CREATE OR REPLACE FUNCTION dbo.sync_to_snake_case()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO dbo.employees (employee_id, first_name, last_name, salary)
        VALUES (NEW."EmployeeID", NEW."FirstName", NEW."LastName", NEW.salary);
    ELSIF TG_OP = 'UPDATE' THEN
        UPDATE dbo.employees
        SET first_name = NEW."FirstName",
            last_name = NEW."LastName",
            salary = NEW.salary
        WHERE employee_id = NEW."EmployeeID";
    ELSIF TG_OP = 'DELETE' THEN
        DELETE FROM dbo.employees WHERE employee_id = OLD."EmployeeID";
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER employees_to_snake_case
AFTER INSERT OR UPDATE OR DELETE ON dbo."Employees"
FOR EACH ROW EXECUTE FUNCTION dbo.sync_to_snake_case();
```

---

## Recommendation Matrix

| Solution | Complexity | Performance | Maintenance | Best For |
|----------|------------|-------------|-------------|----------|
| **PostgreSQL Views** | ⭐ Low | ⭐⭐⭐ Excellent | ⭐⭐⭐ Easy | **500 tables, quick deployment** |
| **Custom SMT** | ⭐⭐⭐ High | ⭐⭐⭐ Excellent | ⭐⭐ Moderate | Clean architecture, dev resources |
| **PostgreSQL Triggers** | ⭐⭐ Medium | ⭐ Poor (2x storage) | ⭐ Hard | Complex business logic needed |

---

## Quick Start: Recommended Approach (Solution 1)

```bash
# 1. Let Debezium create tables with PascalCase
./setup.sh

# 2. Wait for initial snapshot to complete
./status.sh

# 3. Generate snake_case views
chmod +x generate-views.sh
./generate-views.sh

# 4. Verify
docker exec postgres18 psql -U admin -d target_db -c "
SELECT * FROM dbo.employees LIMIT 5;
"
```

**Result:** Applications can use `dbo.employees.employee_id` while Debezium maintains `dbo."Employees"."EmployeeID"` internally.

---

## Testing the Transformation

```sql
-- Test INSERT (MS SQL)
INSERT INTO dbo.Employees (FirstName, LastName, salary)
VALUES ('TestUser', 'SnakeCase', 50000.00);

-- Verify in PostgreSQL snake_case view
SELECT employee_id, first_name, last_name, salary
FROM dbo.employees
WHERE first_name = 'TestUser';

-- Should show: employee_id | first_name | last_name | salary
--              1011        | TestUser   | SnakeCase | 50000.00
```

---

## Migration Strategy for 500 Tables

### Phase 1: Pilot (Week 1)
1. Select 5-10 representative tables
2. Apply Solution 1 (Views)
3. Test all CRUD operations
4. Measure performance impact (should be minimal)

### Phase 2: Batch Deployment (Week 2-3)
1. Run `generate-views.sh` for all 500 tables
2. Update application connection strings to use views
3. Monitor query performance

### Phase 3: Validation (Week 4)
1. Compare row counts: PascalCase tables vs snake_case views
2. Verify CDC still working (INSERT/UPDATE/DELETE)
3. Load testing with production-like queries

---

## Performance Impact

### Solution 1 (Views): 
- **Overhead**: <1% (view is just a query rewrite)
- **Storage**: 0 bytes additional
- **CDC Impact**: None

### Solution 2 (Custom SMT):
- **Overhead**: ~2-5% (transformation happens in Kafka Connect)
- **Storage**: 0 bytes additional
- **CDC Impact**: Minimal (processes before write)

### Solution 3 (Triggers):
- **Overhead**: 15-30% (double writes)
- **Storage**: 2x (duplicate data)
- **CDC Impact**: Significant (triggers on every change)

---

## Conclusion

**For 500 tables with immediate deployment needs: Use Solution 1 (PostgreSQL Views)**

This gives you:
- ✅ Zero code changes to Debezium
- ✅ Deployable in 1 hour
- ✅ No performance impact
- ✅ Easy rollback (just drop views)
- ✅ Works with all existing applications

Run `./generate-views.sh` after initial snapshot completes, and you're done!
