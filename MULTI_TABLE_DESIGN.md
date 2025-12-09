# Multi-Table & Heterogeneous Database Support Design

## Current Limitations
- **Single table**: One source table → One target table
- **Homogeneous**: MariaDB → MySQL only
- **Time**: 15M rows taking ~42 minutes (good for single table, but for 50+ tables?)

## Use Cases to Support

### 1. Multi-Table Homogeneous (MariaDB → MySQL)
```
Source: MariaDB (localhost:3306)
  - guardian.channel_txn_temp
  - guardian.users
  - guardian.orders
  - guardian.products (50+ tables)
  
Target: MySQL Aurora (AWS RDS)
  - guardian.channel_txn_temp
  - guardian.users
  - guardian.orders
  - guardian.products
```

**Time Calculation:**
- Current: 15M rows = 42 minutes (1 table)
- 50 tables: If sequential = 50 × 42 min = **35 hours** ❌
- **Solution**: Parallel table replication (10 tables at once) = **3.5 hours** ✅

### 2. Heterogeneous Database Support

#### MariaDB → PostgreSQL
```
Source: MariaDB
  - Data types: TINYINT, ENUM, TIMESTAMP
  - Functions: NOW(), CONCAT()
  
Target: PostgreSQL
  - Data types: SMALLINT, VARCHAR CHECK, TIMESTAMPTZ
  - Functions: CURRENT_TIMESTAMP, ||
```

**Challenges:**
- ❌ Type mapping (TINYINT → SMALLINT, ENUM → CHECK constraint)
- ❌ SQL syntax differences
- ❌ Binlog format (MySQL-specific)
- ❌ Auto-increment vs SERIAL

#### MySQL → MongoDB
```
Source: MySQL (relational)
Target: MongoDB (document-based)
```
**Challenges:**
- ❌ Schema transformation (tables → collections)
- ❌ Binlog → Change Streams mapping
- ❌ JOIN dependencies

## Proposed Architecture

### Phase 1: Multi-Table Support (Homogeneous)

```yaml
# config.yaml
source:
  type: mariadb
  host: localhost:3306
  user: root
  database: guardian
  
target:
  type: mysql
  host: aws-rds-endpoint:3306
  user: admin
  database: guardian

replication:
  tables:
    - name: channel_txn_temp
      batch_size: 50000
      parallel_workers: 8
    - name: users
      batch_size: 10000
    - name: orders
      batch_size: 20000
    - name: products
      batch_size: 5000
  
  parallel_tables: 10  # Replicate 10 tables simultaneously
  timeout: 3600
```

**Implementation:**
```go
// New multi-table coordinator
type TableReplicationJob struct {
    SourceTable string
    TargetTable string
    BatchSize   int
    Workers     int
}

func RunMultiTableReplication(jobs []TableReplicationJob, maxParallel int) {
    jobChan := make(chan TableReplicationJob, len(jobs))
    var wg sync.WaitGroup
    
    // Start parallel table replicators
    for i := 0; i < maxParallel; i++ {
        wg.Add(1)
        go func(workerID int) {
            defer wg.Done()
            for job := range jobChan {
                log.Printf("Worker %d: Starting table %s", workerID, job.SourceTable)
                runFullLoad(job) // Existing function
            }
        }(i)
    }
    
    // Queue all tables
    for _, job := range jobs {
        jobChan <- job
    }
    close(jobChan)
    wg.Wait()
}
```

### Phase 2: Heterogeneous Support (Future)

**Option A: External Transformation Layer**
```
MariaDB → CDC App → Transformation Service → PostgreSQL
                    (Debezium/AWS DMS style)
```

**Option B: Built-in Type Mapping**
```go
type TypeMapper interface {
    MapType(sourceType string, targetDB string) string
    MapValue(value interface{}, sourceType, targetType string) interface{}
}

// MariaDB → PostgreSQL mapper
type MariaDBToPostgresMapper struct {}

func (m *MariaDBToPostgresMapper) MapType(sourceType string) string {
    mapping := map[string]string{
        "TINYINT": "SMALLINT",
        "ENUM":    "VARCHAR(255) CHECK",
        "TIMESTAMP": "TIMESTAMPTZ",
    }
    return mapping[sourceType]
}
```

**Recommendation**: ❌ **Don't build this**
- Debezium already handles this well
- AWS DMS handles this well
- Fivetran handles this well
- Too complex for a lightweight CDC tool

## Recommendation

### ✅ Phase 1: Multi-Table (Worth Building)
**Benefits:**
- Replicates entire databases in 3-4 hours vs 35 hours
- Simple configuration
- Reuses existing code
- No external dependencies

**Effort**: 1-2 days

### ❌ Phase 2: Heterogeneous (Use Existing Tools)
**Reasons:**
- Debezium is mature and free
- AWS DMS if on AWS
- Type mapping is complex (100+ edge cases)
- Maintenance burden

**Alternative**: 
```
MariaDB → Current CDC → MySQL (staging)
MySQL → Debezium → PostgreSQL (production)
```

## Time Estimates

### Current: Single Table
- 15M rows = 42 minutes ✅

### Multi-Table: Sequential
- 50 tables × 42 min = 35 hours ❌

### Multi-Table: Parallel (10 tables)
- 50 tables / 10 parallel = 5 batches
- 5 batches × 42 min = 3.5 hours ✅

### Multi-Table: Parallel (20 tables) - Aggressive
- 50 tables / 20 parallel = 2.5 batches
- 2.5 batches × 42 min = 1.75 hours ✅
- **Risk**: May overload source database

## Next Steps

1. **Implement multi-table config** (YAML/JSON)
2. **Add table discovery** (auto-detect all tables)
3. **Parallel table coordinator**
4. **Progress tracking per table**
5. **Failure handling** (continue other tables if one fails)

## Questions to Answer

1. **Which tables to replicate?**
   - All tables in database?
   - Specific list?
   - Pattern matching (e.g., `channel_*`)?

2. **Dependencies?**
   - Foreign key order?
   - Or disable FK checks? (current approach)

3. **Priority?**
   - Large tables first?
   - Critical tables first?
   - Alphabetical?

4. **Failure strategy?**
   - Stop all on first failure?
   - Continue others?
   - Retry failed tables?
