# Quick Start Guide (5 Minutes)

For users who want to get started **immediately** without reading the full installation guide.

---

## Prerequisites Checklist

Before you start, ensure you have:

- [ ] Docker Desktop installed and running
- [ ] Python 3.8+ installed
- [ ] Java 11+ installed
- [ ] Maven 3.6+ installed
- [ ] Git installed

**Don't have these?** See [COMPLETE_INSTALLATION_GUIDE.md](COMPLETE_INSTALLATION_GUIDE.md) for installation instructions.

---

## 🚀 Automated Deployment (1 Command!)

The **easiest way** to deploy everything:

```bash
# Clone repository
git clone https://github.com/faizakram/mysql-cdc-go-single-table.git
cd mysql-cdc-go-single-table/debezium-setup

# Run automated deployment script
./scripts/deploy-all.sh
```

**This script automatically:**
- ✅ Checks prerequisites
- ✅ Starts all Docker containers
- ✅ Sets up MS SQL with CDC
- ✅ Replicates schema to PostgreSQL
- ✅ Builds and deploys custom transform
- ✅ Deploys CDC connectors
- ✅ Verifies everything is working

**Expected output:** Colored progress messages ending with "🎉 Deployment completed successfully!"

---

## 🛠️ Manual Deployment (3 Steps)

If you prefer manual control or the automated script fails:

### Step 1: Clone and Start

```bash
# Clone repository
git clone https://github.com/faizakram/mysql-cdc-go-single-table.git
cd mysql-cdc-go-single-table/debezium-setup

# Start infrastructure
docker compose up -d

# Wait 60 seconds for services to start
sleep 60
```

### Step 2: Setup Databases

```bash
# Create MS SQL database and enable CDC
docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -C << 'EOF'
CREATE DATABASE mig_test_db;
GO
USE mig_test_db;
EXEC sys.sp_cdc_enable_db;
GO

CREATE TABLE dbo.Employees (
    EmployeeID INT PRIMARY KEY IDENTITY(1,1),
    FirstName NVARCHAR(50),
    LastName NVARCHAR(50),
    Salary DECIMAL(10,2)
);
GO

EXEC sys.sp_cdc_enable_table 
    @source_schema = N'dbo',
    @source_name = N'Employees',
    @role_name = NULL;
GO

INSERT INTO dbo.Employees (FirstName, LastName, Salary)
VALUES ('John', 'Doe', 75000.00);
GO
EOF
```

### Step 3: Deploy Everything

```bash
# Install Python dependencies
# For Ubuntu 24.04+: Use virtual environment to avoid PEP 668 error
python3 -m venv .venv
source .venv/bin/activate
pip install pyodbc psycopg2-binary

# Run automatic schema replication
python3 scripts/replicate-schema.py

# Build and deploy custom transform
cd custom-smt && mvn clean package && cd ..
docker cp custom-smt/target/snake-case-transform-1.0.0.jar debezium-connect:/kafka/connect/
docker restart debezium-connect
sleep 30

# Deploy CDC connectors
curl -X POST -H "Content-Type: application/json" --data @connectors/mssql-source.json http://localhost:8083/connectors
sleep 10
curl -X POST -H "Content-Type: application/json" --data @connectors/postgres-sink.json http://localhost:8083/connectors
```

---

## ✅ Verify It Works

```bash
# Wait 10 seconds for initial sync
sleep 10

# Check PostgreSQL - data should be there!
docker exec -i postgres18 psql -U admin -d target_db -c "SELECT * FROM dbo.employees;"
```

**Expected Output:**
```
 employee_id | first_name | last_name |  salary  | __cdc_deleted
-------------+------------+-----------+----------+---------------
           1 | John       | Doe       | 75000.00 | false
```

✅ **Notice:**
- `FirstName` became `first_name` (snake_case!)
- `NVARCHAR(50)` became `VARCHAR(50)` (length preserved!)
- `DECIMAL(10,2)` became `NUMERIC(10,2)` (precision preserved!)

---

## 🧪 Test Real-Time CDC

```bash
# Insert new data in MS SQL
docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -d mig_test_db -C -Q "INSERT INTO dbo.Employees (FirstName, LastName, Salary) VALUES ('Jane', 'Smith', 82000.00);"

# Wait 10 seconds
sleep 10

# Check PostgreSQL - new record should appear!
docker exec -i postgres18 psql -U admin -d target_db -c "SELECT * FROM dbo.employees WHERE first_name = 'Jane';"
```

**Expected Output:**
```
 employee_id | first_name | last_name |  salary  | __cdc_deleted
-------------+------------+-----------+----------+---------------
           2 | Jane       | Smith     | 82000.00 | false
```

🎉 **It works!** Data replicated in real-time!

---

## 🐛 Troubleshooting

### Containers won't start?
```bash
docker ps -a  # Check container status
docker logs debezium-connect  # Check logs
```

### Python script fails?
```bash
# Install ODBC driver (Linux)
sudo apt-get install -y unixodbc unixodbc-dev
sudo ACCEPT_EULA=Y apt-get install -y msodbcsql18

# Windows: Download from Microsoft
# https://docs.microsoft.com/en-us/sql/connect/odbc/download-odbc-driver-for-sql-server
```

### Python pip install fails with "externally-managed-environment"?
**Ubuntu 24.04+ users:** This is due to PEP 668. Use virtual environment:
```bash
# Create and activate virtual environment
python3 -m venv .venv
source .venv/bin/activate

# Now install packages
pip install pyodbc psycopg2-binary

# Run script
python3 scripts/replicate-schema.py
```

**For future runs:**
```bash
source .venv/bin/activate  # Always activate first!
python3 scripts/replicate-schema.py
```

### No data replicating?
```bash
# Check connector status
curl http://localhost:8083/connectors/mssql-source-connector/status | jq
curl http://localhost:8083/connectors/postgres-sink-connector/status | jq

# Restart connectors
curl -X POST http://localhost:8083/connectors/mssql-source-connector/restart
curl -X POST http://localhost:8083/connectors/postgres-sink-connector/restart
```

---

## 📚 Next Steps

- **Add more tables:** See [COMPLETE_INSTALLATION_GUIDE.md](COMPLETE_INSTALLATION_GUIDE.md#adding-more-tables)
- **Change credentials:** See [Credentials Management](COMPLETE_INSTALLATION_GUIDE.md#credentials-management)
- **Production deployment:** See [Production Deployment](COMPLETE_INSTALLATION_GUIDE.md#production-deployment)
- **Detailed explanation:** See [AUTOMATIC_SCHEMA_REPLICATION.md](AUTOMATIC_SCHEMA_REPLICATION.md)

---

## 🎯 What You Just Built

A fully automatic CDC pipeline that:
- ✅ Automatically creates PostgreSQL tables from MS SQL schema
- ✅ Preserves VARCHAR lengths, UUID types, DECIMAL precision
- ✅ Transforms column names to snake_case
- ✅ Replicates INSERT/UPDATE/DELETE in 5-10 seconds
- ✅ Supports soft deletes
- ✅ Works on Windows, Linux, macOS

**Need help?** Check [COMPLETE_INSTALLATION_GUIDE.md](COMPLETE_INSTALLATION_GUIDE.md) or [Troubleshooting](COMPLETE_INSTALLATION_GUIDE.md#troubleshooting)
