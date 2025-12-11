# Project File Structure

## Overview
This document describes all files in the `debezium-setup` directory and their purposes.

---

## 📁 Directory Structure

```
debezium-setup/
├── .env                              # ⚙️ Configuration (DO NOT COMMIT)
├── .env.example                      # 📋 Configuration template
├── .gitignore                        # 🚫 Git ignore rules
├── docker-compose.yml                # 🐳 Infrastructure definition
├── README.md                         # 📖 Main documentation
├── COMPLETE_INSTALLATION_GUIDE.md    # 📚 Detailed setup guide
├── DEPLOY_ALL_GUIDE.md               # 🚀 Automated deployment guide
├── QUICK_START.md                    # ⚡ Quick start guide
├── AUTOMATIC_SCHEMA_REPLICATION.md   # 🔄 Schema replication docs
├── FILE_STRUCTURE.md                 # 📁 This file
│
├── scripts/                          # 🛠️ Automation scripts
│   ├── deploy-all.sh                 # Main deployment automation
│   ├── generate-connectors.sh        # Generates JSON from .env
│   └── replicate-schema.py           # Python schema replication
│
├── connectors/                       # 🔌 Connector configurations
│   ├── mssql-source.json            # Generated source connector
│   └── postgres-sink.json           # Generated sink connector
│
└── custom-smt/                       # 🔧 Custom transformation
    ├── pom.xml                       # Maven build configuration
    └── src/                          # Java source code
        └── main/java/com/debezium/transforms/
            ├── SnakeCaseTransform.java      # Snake_case converter
            └── TypeConversionTransform.java # UUID/JSON converter
```

---

## 📄 File Descriptions

### Core Configuration

#### `.env` (Git ignored - DO NOT COMMIT)
**Purpose:** Single source of truth for all configuration
**Contains:**
- Database credentials (MS SQL, PostgreSQL)
- Connection strings (hosts, ports, database names)
- Connector settings (names, tasks, Kafka bootstrap servers)
- Transform settings (UUID columns, JSON columns)
- Performance settings (JVM heap, timeouts)

**Usage:** Loaded by all scripts and used to generate connector configs

**Security:** Contains sensitive data - must be in `.gitignore`

---

#### `.env.example`
**Purpose:** Template for creating `.env` file
**Usage:**
```bash
cp .env.example .env
nano .env  # Edit with your values
```

---

#### `docker-compose.yml`
**Purpose:** Defines Docker infrastructure
**Services:**
- `zookeeper` - Kafka coordination
- `kafka` - Message broker
- `debezium-connect` - CDC connector runtime
- `kafka-ui` - Web UI for Kafka (port 8090)
- `mssql-source` - MS SQL Server 2022 source
- `postgres-target` - PostgreSQL 18 target

**Environment variables:** Reads from `.env` file

---

### Documentation Files

#### `README.md`
**Purpose:** Main project documentation
**Contains:**
- Feature overview
- Quick start instructions
- Architecture diagram
- Links to detailed guides

---

#### `DEPLOY_ALL_GUIDE.md`
**Purpose:** Complete guide for automated deployment
**Contains:**
- How to use `deploy-all.sh`
- Configuration via `.env`
- Troubleshooting steps
- Verification commands

---

#### `COMPLETE_INSTALLATION_GUIDE.md`
**Purpose:** Detailed manual installation guide
**Contains:**
- Step-by-step instructions
- Platform-specific setup (Windows/Linux/macOS)
- Prerequisites
- Manual connector deployment

---

#### `QUICK_START.md`
**Purpose:** Fast track setup for experienced users
**Contains:**
- Condensed installation steps
- Minimal configuration
- Quick verification

---

#### `AUTOMATIC_SCHEMA_REPLICATION.md`
**Purpose:** Schema replication technical documentation
**Contains:**
- How automatic schema replication works
- Type mapping details
- Python script explanation

---

### Scripts Directory

#### `scripts/deploy-all.sh`
**Purpose:** Main deployment automation script
**What it does:**
1. Loads configuration from `.env`
2. Validates prerequisites
3. Starts Docker infrastructure
4. Validates MS SQL CDC setup
5. Replicates schema to PostgreSQL
6. Builds custom transforms
7. Generates and deploys connectors
8. Verifies data replication

**Requirements:**
- `.env` file must exist
- Docker, Python 3, Maven, jq must be installed

**Exit codes:**
- 0: Success
- 1: Validation failed or error occurred

---

#### `scripts/generate-connectors.sh`
**Purpose:** Generates connector JSON files from `.env`
**What it does:**
1. Sources `.env` file
2. Creates `connectors/mssql-source.json` with substituted variables
3. Creates `connectors/postgres-sink.json` with substituted variables
4. Displays configuration summary

**Called by:** `deploy-all.sh` before deploying connectors

**Output files:**
- `connectors/mssql-source.json` (overwritten each time)
- `connectors/postgres-sink.json` (overwritten each time)

---

#### `scripts/replicate-schema.py`
**Purpose:** Automatic schema replication from MS SQL to PostgreSQL
**What it does:**
1. Loads configuration from `.env`
2. Connects to MS SQL and queries CDC-enabled tables
3. Reads column definitions, types, lengths from `sys.columns`
4. Maps MS SQL types to PostgreSQL types
5. Preserves VARCHAR lengths, handles UNIQUEIDENTIFIER → UUID
6. Creates tables in PostgreSQL with exact schema
7. Handles primary keys, constraints, nullability

**Dependencies:**
- `pyodbc` (MS SQL connection)
- `psycopg2` (PostgreSQL connection)

**Type mappings:**
- `VARCHAR(n)` → `VARCHAR(n)` (length preserved)
- `NVARCHAR(n)` → `VARCHAR(n)`
- `UNIQUEIDENTIFIER` → `UUID`
- `BIT` → `BOOLEAN`
- `DATETIME/DATETIME2` → `TIMESTAMP`
- `DECIMAL(p,s)` → `DECIMAL(p,s)`

---

### Connectors Directory

#### `connectors/mssql-source.json` (Generated - Git ignored)
**Purpose:** Debezium MS SQL source connector configuration
**Generated by:** `generate-connectors.sh` from `.env`
**Deployed to:** Debezium Connect via REST API

**Key settings:**
- `database.hostname`: From `MSSQL_HOST`
- `database.names`: From `MSSQL_DATABASE`
- `topic.prefix`: From `TOPIC_PREFIX`
- `snapshot.mode`: "initial" (full snapshot + CDC)
- `transforms.route`: Simplifies Kafka topic names

**Do not edit manually** - regenerated each deployment

---

#### `connectors/postgres-sink.json` (Generated - Git ignored)
**Purpose:** Debezium PostgreSQL sink connector configuration
**Generated by:** `generate-connectors.sh` from `.env`
**Deployed to:** Debezium Connect via REST API

**Key settings:**
- `connection.url`: From `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DATABASE`
- `topics.regex`: From `TOPIC_PREFIX` and `MSSQL_DATABASE`
- Custom transforms:
  - `snakeCaseKey` / `snakeCaseValue`: CamelCase → snake_case
  - `typeConversion`: String → UUID/JSON for specified columns
  - `unwrap`: Extract CDC event payload
  - `renameDeleted`: Track soft deletes

**Do not edit manually** - regenerated each deployment

---

### Custom SMT Directory

#### `custom-smt/pom.xml`
**Purpose:** Maven build configuration for custom transforms
**Builds:**
- `snake-case-transform-1.0.0.jar`

**Dependencies:**
- Kafka Connect API
- Debezium libraries

---

#### `custom-smt/src/main/java/com/debezium/transforms/SnakeCaseTransform.java`
**Purpose:** Converts field names from CamelCase to snake_case
**Examples:**
- `FirstName` → `first_name`
- `EmployeeID` → `employee_id`
- `DateOfBirth` → `date_of_birth`

**Applied to:** Both keys and values (separate instances)

---

#### `custom-smt/src/main/java/com/debezium/transforms/TypeConversionTransform.java`
**Purpose:** Converts specific columns to native PostgreSQL types
**Conversions:**
- String → UUID (for columns in `UUID_COLUMNS` from `.env`)
- String → JSON (for columns in `JSON_COLUMNS` from `.env`)

**Why needed:** Debezium transmits UUIDs and JSON as strings; this converts them back

---

## 🔄 Workflow: How Files Interact

```
1. User creates .env (copy from .env.example)
   ↓
2. deploy-all.sh starts
   ├── Loads .env
   ├── Validates prerequisites
   └── Starts docker-compose.yml (reads .env)
   ↓
3. replicate-schema.py runs
   ├── Loads .env
   ├── Connects to MS SQL
   ├── Reads CDC tables
   └── Creates PostgreSQL tables
   ↓
4. generate-connectors.sh runs
   ├── Loads .env
   ├── Creates mssql-source.json
   └── Creates postgres-sink.json
   ↓
5. Connectors deployed via REST API
   ├── Source: Reads MS SQL CDC
   ├── Kafka: Stores events
   └── Sink: Writes to PostgreSQL
   ↓
6. Custom SMT transforms data
   ├── Snake_case conversion
   └── UUID/JSON type conversion
```

---

## 🧹 What Was Removed (No Longer Needed)

### Removed Files
- `connectors/postgres-sink-v2.json` - Old test version
- `connectors/postgres-sink-test.json` - Old test version  
- `connectors/postgres-sink-generated.json` - Superseded by generate-connectors.sh
- `deployment-log.txt` - Old log file
- `fresh-deployment-log.txt` - Old log file

### Why Removed
All connector configurations are now:
1. Generated dynamically from `.env` via `generate-connectors.sh`
2. No need for multiple versions
3. Single source of truth approach

---

## 🔒 Security Notes

### Files to NEVER commit to Git:
- `.env` - Contains passwords and sensitive configuration
- `connectors/*.json` - May contain credentials (auto-generated)
- Log files (`*.log`, `*-log.txt`)

### Safe to commit:
- `.env.example` - Template without real credentials
- All documentation files
- Scripts (no hardcoded credentials)
- `docker-compose.yml` (uses `${VARIABLE}` syntax)
- Custom SMT source code

---

## 📝 Updating Configuration

### To change database credentials:
```bash
nano .env  # Edit MSSQL_PASSWORD, POSTGRES_PASSWORD, etc.
bash scripts/deploy-all.sh  # Redeploy with new config
```

### To add new UUID columns:
```bash
nano .env  # Add column names to UUID_COLUMNS
bash scripts/deploy-all.sh  # Regenerate connectors
```

### To change connector settings:
```bash
nano .env  # Update CONNECTOR_TASKS_MAX, etc.
bash scripts/deploy-all.sh  # Regenerate connectors
```

---

## 🎯 Key Principles

1. **Single Source of Truth**: `.env` file contains ALL configuration
2. **No Hardcoded Values**: All scripts read from `.env`
3. **Auto-Generation**: Connector JSONs generated from `.env` each deployment
4. **Security First**: Sensitive files in `.gitignore`
5. **Reproducible**: Same `.env` = same deployment anywhere

---

## 📚 For More Information

- **Getting Started**: See `README.md`
- **Automated Setup**: See `DEPLOY_ALL_GUIDE.md`
- **Manual Setup**: See `COMPLETE_INSTALLATION_GUIDE.md`
- **Schema Details**: See `AUTOMATIC_SCHEMA_REPLICATION.md`
