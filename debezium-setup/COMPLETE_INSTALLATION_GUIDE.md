# Complete Installation Guide: MS SQL to PostgreSQL CDC with Automatic Schema Replication

## 🎯 What You'll Get

A **fully automatic** Change Data Capture (CDC) system that:
- ✅ **Automatically replicates table schemas** from MS SQL → PostgreSQL
- ✅ **Preserves VARCHAR lengths** (VARCHAR(50) stays VARCHAR(50))
- ✅ **Converts to native types** (UNIQUEIDENTIFIER → UUID)
- ✅ **Transforms column names** (FirstName → first_name)
- ✅ **Replicates data in real-time** (5-10 second latency)
- ✅ **Works on Windows, Linux, and macOS**
- ✅ **No manual table creation needed!**

---

## 📋 Table of Contents

1. [System Requirements](#system-requirements)
2. [Step-by-Step Installation](#step-by-step-installation)
   - [Linux Installation](#linux-installation)
   - [Windows Installation](#windows-installation)
   - [macOS Installation](#macos-installation)
3. [Configuration](#configuration)
4. [Running the System](#running-the-system)
5. [Testing](#testing)
6. [Troubleshooting](#troubleshooting)
7. [Credentials Management](#credentials-management)

---

## System Requirements

### Hardware

| Component | Minimum | Recommended |
|-----------|---------|-------------|
| CPU | 2 cores | 4+ cores |
| RAM | 8 GB | 16 GB |
| Disk Space | 10 GB | 20+ GB |
| Network | 100 Mbps | 1 Gbps |

### Software Prerequisites

| Software | Minimum Version | Required For |
|----------|----------------|--------------|
| **Docker Desktop** | 20.10+ | Running containers |
| **Docker Compose** | 2.0+ | Orchestration |
| **Python 3** | 3.8+ | Schema replication script |
| **Java JDK** | 11+ | Building custom transforms |
| **Maven** | 3.6+ | Building Java code |
| **Git** | Any | Cloning repository |

### Supported Operating Systems

- ✅ **Linux**: Ubuntu 20.04+, Debian 11+, CentOS 8+, RHEL 8+
- ✅ **Windows**: Windows 10/11 (with WSL 2)
- ✅ **macOS**: macOS 11+ (Big Sur and newer)

---

## Step-by-Step Installation

Choose your operating system:

---

## 🐧 Linux Installation

### Step 1: Update System

```bash
sudo apt-get update
sudo apt-get upgrade -y
```

### Step 2: Install Docker & Docker Compose

```bash
# Install Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# Add your user to docker group (run without sudo)
sudo usermod -aG docker $USER

# Apply group changes
newgrp docker

# Verify installation
docker --version
docker compose version
```

**Expected Output:**
```
Docker version 24.0.0 or higher
Docker Compose version v2.20.0 or higher
```

### Step 3: Install Java JDK 11

```bash
# Install OpenJDK 11
sudo apt-get install -y openjdk-11-jdk

# Verify installation
java -version
```

**Expected Output:**
```
openjdk version "11.0.x"
```

### Step 4: Install Maven

```bash
# Install Maven
sudo apt-get install -y maven

# Verify installation
mvn -version
```

**Expected Output:**
```
Apache Maven 3.6.x or higher
Java version: 11.0.x
```

### Step 5: Install Python 3 and Dependencies

```bash
# Install Python 3 and pip
sudo apt-get install -y python3 python3-pip python3-venv

# Install ODBC drivers for SQL Server
sudo apt-get install -y unixodbc unixodbc-dev

# Install Microsoft ODBC Driver 18 for SQL Server
curl https://packages.microsoft.com/keys/microsoft.asc | sudo apt-key add -
curl https://packages.microsoft.com/config/ubuntu/$(lsb_release -rs)/prod.list | sudo tee /etc/apt/sources.list.d/mssql-release.list
sudo apt-get update
sudo ACCEPT_EULA=Y apt-get install -y msodbcsql18

# Verify ODBC driver
odbcinst -q -d
```

**Expected Output:**
```
[ODBC Driver 18 for SQL Server]
```

### Step 6: Install Python Packages

**⚠️ Important for Ubuntu 24.04+ Users:**
Ubuntu 24.04 and newer implement PEP 668 to protect system Python. You **must use a virtual environment** instead of installing packages globally.

#### Recommended Method: Virtual Environment

```bash
# Navigate to project directory
cd ~/projects/mysql-cdc-go-single-table/debezium-setup

# Create virtual environment
python3 -m venv .venv

# Activate the virtual environment
source .venv/bin/activate

# Install required packages
pip install pyodbc psycopg2-binary

# Verify installations
pip list | grep -E "pyodbc|psycopg2"
```

**Expected Output:**
```
psycopg2-binary   2.9.x
pyodbc            5.x.x
```

**For future runs, always activate the virtual environment first:**
```bash
cd ~/projects/mysql-cdc-go-single-table/debezium-setup
source .venv/bin/activate
# Now you can run Python scripts
```

#### Alternative Method: System Packages

If you prefer system packages (older versions, but no virtual environment needed):

```bash
sudo apt-get install -y python3-pyodbc python3-psycopg2
```

**Note:** If you encounter `externally-managed-environment` error when using pip, see [Troubleshooting Issue 7](#issue-7-python-package-installation-error-ubuntu-2404).

### Step 7: Install Git and curl

```bash
sudo apt-get install -y git curl jq

# Verify
git --version
curl --version
jq --version
```

### Step 8: Clone Repository

```bash
# Navigate to your projects directory
cd ~
mkdir -p projects
cd projects

# Clone repository
git clone https://github.com/faizakram/mysql-cdc-go-single-table.git

# Navigate to setup directory
cd mysql-cdc-go-single-table/debezium-setup

# Verify directory structure
ls -la
```

**Expected Directory Structure:**
```
debezium-setup/
├── docker-compose.yml
├── connectors/
│   ├── mssql-source.json
│   └── postgres-sink.json
├── custom-smt/
│   ├── pom.xml
│   └── src/
├── scripts/
│   ├── replicate-schema.py
│   ├── deploy-all.sh
│   └── integration-test.sh
└── COMPLETE_INSTALLATION_GUIDE.md (this file)
```

---

## 🪟 Windows Installation

### Step 1: Enable WSL 2

```powershell
# Open PowerShell as Administrator

# Install WSL 2
wsl --install

# Set WSL 2 as default
wsl --set-default-version 2

# Restart computer
```

### Step 2: Install Docker Desktop

1. **Download Docker Desktop:**
   - Visit: https://www.docker.com/products/docker-desktop/
   - Download "Docker Desktop for Windows"

2. **Install Docker Desktop:**
   - Run `Docker Desktop Installer.exe`
   - Check "Use WSL 2 instead of Hyper-V"
   - Complete installation and restart

3. **Start Docker Desktop:**
   - Launch from Start Menu
   - Wait for "Docker Desktop is running" in system tray

4. **Verify:**
```powershell
docker --version
docker compose version
```

**Expected Output:**
```
Docker version 24.0.0 or higher
Docker Compose version v2.20.0 or higher
```

### Step 3: Install Java JDK 11

**Option A: Using Installer (Recommended)**

1. Download from: https://adoptium.net/temurin/releases/
2. Select: Windows x64, JDK 11 (LTS)
3. Run installer → Install for all users
4. Verify:

```powershell
java -version
```

**Expected Output:**
```
openjdk version "11.0.x"
```

**Option B: Using Chocolatey**

```powershell
# Install Chocolatey (if not installed)
Set-ExecutionPolicy Bypass -Scope Process -Force
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor 3072
iex ((New-Object System.Net.WebClient).DownloadString('https://community.chocolatey.org/install.ps1'))

# Install Java
choco install openjdk11 -y

# Verify
java -version
```

### Step 4: Install Maven

**Option A: Using Installer**

1. Download from: https://maven.apache.org/download.cgi
2. Download `apache-maven-3.x.x-bin.zip`
3. Extract to `C:\Program Files\Apache\maven`
4. Add to PATH:
   - System Properties → Environment Variables
   - Edit "Path" → Add: `C:\Program Files\Apache\maven\bin`
5. Close and reopen PowerShell
6. Verify:

```powershell
mvn -version
```

**Option B: Using Chocolatey**

```powershell
choco install maven -y

# Verify
mvn -version
```

**Expected Output:**
```
Apache Maven 3.6.x or higher
Java version: 11.0.x
```

### Step 5: Install Python 3

**Option A: Using Installer (Recommended)**

1. Download from: https://www.python.org/downloads/
2. Download Python 3.11+ for Windows
3. Run installer:
   - ✅ Check "Add Python to PATH"
   - Click "Install Now"
4. Verify:

```powershell
python --version
pip --version
```

**Option B: Using Chocolatey**

```powershell
choco install python -y

# Verify
python --version
```

### Step 6: Install ODBC Driver for SQL Server

```powershell
# Download and install Microsoft ODBC Driver 18
# Visit: https://docs.microsoft.com/en-us/sql/connect/odbc/download-odbc-driver-for-sql-server
# Download: msodbcsql_18.x.x.x_x64.msi
# Run installer with defaults

# Verify installation (in PowerShell)
Get-OdbcDriver | Where-Object {$_.Name -like "*SQL Server*"}
```

**Expected Output:**
```
Name                           Platform
----                           --------
ODBC Driver 18 for SQL Server  64-bit
```

### Step 7: Install Python Packages

```powershell
# Create virtual environment (recommended)
python -m venv C:\cdc-venv

# Activate virtual environment
C:\cdc-venv\Scripts\Activate.ps1

# Install packages
pip install pyodbc psycopg2-binary

# Verify
pip list | Select-String "pyodbc|psycopg2"
```

**Expected Output:**
```
psycopg2-binary   2.9.x
pyodbc            5.x.x
```

### Step 8: Install Git

1. Download from: https://git-scm.com/download/win
2. Run installer with default settings
3. Verify:

```powershell
git --version
```

### Step 9: Clone Repository

```powershell
# Create projects directory
New-Item -ItemType Directory -Force -Path C:\Projects
cd C:\Projects

# Clone repository
git clone https://github.com/faizakram/mysql-cdc-go-single-table.git

# Navigate to setup directory
cd mysql-cdc-go-single-table\debezium-setup

# Verify directory structure
dir
```

---

## 🍎 macOS Installation

### Step 1: Install Homebrew

```bash
# Install Homebrew (if not already installed)
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# Verify
brew --version
```

### Step 2: Install Docker Desktop

1. Download from: https://www.docker.com/products/docker-desktop/
2. Download "Docker Desktop for Mac"
3. Open `.dmg` file and drag Docker to Applications
4. Launch Docker Desktop
5. Verify:

```bash
docker --version
docker compose version
```

### Step 3: Install Java JDK 11

```bash
# Install OpenJDK 11 using Homebrew
brew install openjdk@11

# Add to PATH
echo 'export PATH="/usr/local/opt/openjdk@11/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc

# Verify
java -version
```

### Step 4: Install Maven

```bash
# Install Maven
brew install maven

# Verify
mvn -version
```

### Step 5: Install Python 3 and ODBC Driver

```bash
# Install Python 3
brew install python3

# Install unixODBC
brew install unixodbc

# Install Microsoft ODBC Driver 18 for SQL Server
brew tap microsoft/mssql-release https://github.com/Microsoft/homebrew-mssql-release
brew update
brew install msodbcsql18

# Verify
odbcinst -q -d
```

### Step 6: Install Python Packages

```bash
# Create virtual environment
python3 -m venv ~/cdc-venv
source ~/cdc-venv/bin/activate

# Install packages
pip3 install pyodbc psycopg2-binary

# Verify
pip3 list | grep -E "pyodbc|psycopg2"
```

### Step 7: Clone Repository

```bash
# Navigate to projects directory
cd ~
mkdir -p projects
cd projects

# Clone repository
git clone https://github.com/faizakram/mysql-cdc-go-single-table.git
cd mysql-cdc-go-single-table/debezium-setup

# Verify
ls -la
```

---

## Configuration

### 1. Database Credentials

All credentials are managed in the following files:

#### MS SQL Server Credentials

**File:** `connectors/mssql-source.json`
```json
{
  "config": {
    "database.hostname": "mssql-test",
    "database.port": "1433",
    "database.user": "sa",
    "database.password": "YourStrong@Passw0rd",
    "database.names": "mig_test_db"
  }
}
```

**File:** `scripts/replicate-schema.py`
```python
MSSQL_CONFIG = {
    'server': 'localhost,1433',
    'database': 'mig_test_db',
    'username': 'Sa',
    'password': 'YourStrong@Passw0rd',
    'driver': '{ODBC Driver 18 for SQL Server}',
    'TrustServerCertificate': 'yes'
}
```

#### PostgreSQL Credentials

**File:** `connectors/postgres-sink.json`
```json
{
  "config": {
    "connection.url": "jdbc:postgresql://postgres18:5432/target_db?currentSchema=dbo",
    "connection.username": "admin",
    "connection.password": "admin123"
  }
}
```

**File:** `scripts/replicate-schema.py`
```python
POSTGRES_CONFIG = {
    'host': 'localhost',
    'port': 5432,
    'database': 'target_db',
    'user': 'admin',
    'password': 'admin123'
}
```

#### Docker Compose Credentials

**File:** `docker-compose.yml`
```yaml
services:
  mssql-test:
    environment:
      SA_PASSWORD: "YourStrong@Passw0rd"
      MSSQL_PID: "Developer"
  
  postgres18:
    environment:
      POSTGRES_USER: admin
      POSTGRES_PASSWORD: admin123
      POSTGRES_DB: target_db
```

### 2. Changing Credentials

To use your own credentials:

1. **Update docker-compose.yml** with your passwords
2. **Update connectors/*.json** with matching credentials
3. **Update scripts/replicate-schema.py** with matching credentials
4. **Restart containers:** `docker compose down && docker compose up -d`

### 3. Table Selection

**To replicate all tables in a schema:**

`connectors/mssql-source.json`:
```json
"table.include.list": "dbo.*"
```

**To replicate specific tables only:**

```json
"table.include.list": "dbo.Employees,dbo.Orders,dbo.Products"
```

---

## Running the System

### Phase 1: Start Infrastructure

#### 🐧 Linux / 🍎 macOS

```bash
cd ~/projects/mysql-cdc-go-single-table/debezium-setup

# Start all containers
docker compose up -d

# Wait for services to start (30-60 seconds)
sleep 60

# Verify all containers are running
docker ps
```

#### 🪟 Windows

```powershell
cd C:\Projects\mysql-cdc-go-single-table\debezium-setup

# Start all containers
docker compose up -d

# Wait for services
Start-Sleep -Seconds 60

# Verify
docker ps
```

**Expected Output:**
```
CONTAINER ID   IMAGE                       STATUS
abc123...      debezium/connect:2.5.4      Up 1 minute
def456...      confluentinc/cp-kafka       Up 1 minute
ghi789...      confluentinc/cp-zookeeper   Up 1 minute
jkl012...      mcr.microsoft.com/mssql     Up 1 minute
mno345...      postgres:18                 Up 1 minute
```

---

### Phase 2: Setup MS SQL Source Database

#### Create Database and Enable CDC

#### 🐧 Linux / 🍎 macOS

```bash
docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -C << 'EOF'
-- Create database
IF NOT EXISTS (SELECT * FROM sys.databases WHERE name = 'mig_test_db')
BEGIN
    CREATE DATABASE mig_test_db;
END
GO

USE mig_test_db;
GO

-- Enable CDC on database
EXEC sys.sp_cdc_enable_db;
GO

-- Verify CDC is enabled
SELECT name, is_cdc_enabled 
FROM sys.databases 
WHERE name = 'mig_test_db';
GO
EOF
```

#### 🪟 Windows

```powershell
docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -C -Q "CREATE DATABASE mig_test_db;"
docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -C -Q "USE mig_test_db; EXEC sys.sp_cdc_enable_db;"
```

#### Create Sample Table

#### 🐧 Linux / 🍎 macOS

```bash
docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -d mig_test_db -C << 'EOF'
-- Create Employees table
CREATE TABLE dbo.Employees (
    EmployeeID INT PRIMARY KEY IDENTITY(1,1),
    FirstName NVARCHAR(50) NOT NULL,
    LastName NVARCHAR(50) NOT NULL,
    Email NVARCHAR(100),
    PhoneNumber VARCHAR(20),
    HireDate DATE,
    Salary DECIMAL(10,2),
    IsActive BIT DEFAULT 1
);
GO

-- Enable CDC on Employees table
EXEC sys.sp_cdc_enable_table 
    @source_schema = N'dbo',
    @source_name = N'Employees',
    @role_name = NULL,
    @supports_net_changes = 0;
GO

-- Insert sample data
INSERT INTO dbo.Employees (FirstName, LastName, Email, PhoneNumber, HireDate, Salary, IsActive)
VALUES 
    ('John', 'Doe', 'john.doe@example.com', '555-0001', '2024-01-15', 75000.00, 1),
    ('Jane', 'Smith', 'jane.smith@example.com', '555-0002', '2024-02-20', 82000.00, 1),
    ('Bob', 'Johnson', 'bob.johnson@example.com', '555-0003', '2024-03-10', 68000.00, 1);
GO

-- Verify data
SELECT * FROM dbo.Employees;
GO

-- Verify CDC is enabled on table
SELECT name, is_tracked_by_cdc 
FROM sys.tables 
WHERE name = 'Employees';
GO
EOF
```

**Expected Output:**
```
is_cdc_enabled
--------------
1

EmployeeID  FirstName  LastName   Email                     Salary
----------  ---------  ---------  ------------------------  ----------
1           John       Doe        john.doe@example.com      75000.00
2           Jane       Smith      jane.smith@example.com    82000.00
3           Bob        Johnson    bob.johnson@example.com   68000.00

is_tracked_by_cdc
-----------------
1
```

---

### Phase 3: Run Automatic Schema Replication

This is the **magic step** - it automatically reads MS SQL schema and creates PostgreSQL tables!

#### 🐧 Linux / 🍎 macOS

```bash
cd ~/projects/mysql-cdc-go-single-table/debezium-setup

# Activate virtual environment
source ~/cdc-venv/bin/activate

# Run schema replication
python3 scripts/replicate-schema.py
```

#### 🪟 Windows

```powershell
cd C:\Projects\mysql-cdc-go-single-table\debezium-setup

# Activate virtual environment
C:\cdc-venv\Scripts\Activate.ps1

# Run schema replication
python scripts\replicate-schema.py
```

**Expected Output:**
```
=============================================================================
Dynamic Schema Replication - MS SQL → PostgreSQL
=============================================================================

🔌 Connecting to MS SQL Server...
   ✅ Connected to MS SQL
🔌 Connecting to PostgreSQL...
   ✅ Connected to PostgreSQL

📊 Finding CDC-enabled tables...
   Found 1 tables: Employees

🔄 Replicating table schemas...

📋 Creating table: dbo.employees
   Columns: 8
   Primary Keys: employee_id
   ✅ Created successfully

=============================================================================
✅ Schema replication complete!
   Tables processed: 1
   Successful: 1
   Failed: 0
=============================================================================

Next steps:
1. Restart sink connector: curl -X POST http://localhost:8083/connectors/postgres-sink-connector/restart
2. Trigger data sync by updating MS SQL tables
```

#### Verify PostgreSQL Schema

```bash
docker exec -i postgres18 psql -U admin -d target_db -c "\d dbo.employees"
```

**Expected Output:**
```
                            Table "dbo.employees"
    Column     |         Type          | Nullable |    Default
---------------+-----------------------+----------+--------------
 employee_id   | integer               | not null |
 first_name    | character varying(50) |          |
 last_name     | character varying(50) |          |
 email         | character varying(100)|          |
 phone_number  | character varying(20) |          |
 hire_date     | date                  |          |
 salary        | numeric(10,2)         |          |
 is_active     | boolean               |          |
 __cdc_deleted | text                  |          | 'false'::text
Indexes:
    "employees_pkey" PRIMARY KEY, btree (employee_id)
```

✅ **Notice:**
- `FirstName` → `first_name` (snake_case transformation)
- `NVARCHAR(50)` → `VARCHAR(50)` (length preserved!)
- `DECIMAL(10,2)` → `NUMERIC(10,2)` (precision preserved!)
- `BIT` → `BOOLEAN` (native type)
- `__cdc_deleted` column added automatically

---

### Phase 4: Build and Deploy Custom Transform

#### 🐧 Linux / 🍎 macOS

```bash
cd ~/projects/mysql-cdc-go-single-table/debezium-setup

# Make script executable
chmod +x scripts/deploy-smt.sh

# Deploy custom transform
./scripts/deploy-smt.sh
```

#### 🪟 Windows

```powershell
cd C:\Projects\mysql-cdc-go-single-table\debezium-setup

# Build custom transform
cd custom-smt
mvn clean package

# Copy JAR to Debezium Connect
docker cp target\snake-case-transform-1.0.0.jar debezium-connect:/kafka/connect/

# Restart Debezium Connect
docker restart debezium-connect

# Wait for restart
Start-Sleep -Seconds 30

# Verify
curl http://localhost:8083/ | ConvertFrom-Json
```

**Expected Output:**
```
==========================================================================
Building Snake Case Transform SMT
==========================================================================

📦 Building JAR...
[INFO] BUILD SUCCESS
✅ Build successful: custom-smt/target/snake-case-transform-1.0.0.jar

==========================================================================
Deploying to Debezium Connect
==========================================================================

✅ JAR copied successfully
✅ Debezium Connect restarted
✅ Snake Case SMT deployed successfully!
```

---

### Phase 5: Deploy CDC Connectors

#### 🐧 Linux / 🍎 macOS

```bash
cd ~/projects/mysql-cdc-go-single-table/debezium-setup

# Make script executable
chmod +x scripts/deploy-all.sh

# Deploy connectors
./scripts/deploy-all.sh
```

#### 🪟 Windows

```powershell
cd C:\Projects\mysql-cdc-go-single-table\debezium-setup

# Deploy MS SQL source connector
$sourceConfig = Get-Content connectors\mssql-source.json -Raw
Invoke-RestMethod -Uri "http://localhost:8083/connectors" -Method Post -ContentType "application/json" -Body $sourceConfig

# Wait 10 seconds
Start-Sleep -Seconds 10

# Deploy PostgreSQL sink connector
$sinkConfig = Get-Content connectors\postgres-sink.json -Raw
Invoke-RestMethod -Uri "http://localhost:8083/connectors" -Method Post -ContentType "application/json" -Body $sinkConfig

# Verify connectors
Invoke-RestMethod -Uri "http://localhost:8083/connectors" | ConvertFrom-Json
```

**Expected Output:**
```
==========================================================================
Deploying Debezium Connectors
==========================================================================

📤 Deploying MS SQL Source Connector...
✅ MS SQL Source Connector deployed

📤 Deploying PostgreSQL Sink Connector...
✅ PostgreSQL Sink Connector deployed

==========================================================================
✅ All connectors deployed successfully!
==========================================================================

Active Connectors:
  - mssql-source-connector
  - postgres-sink-connector
```

---

## Testing

### Test 1: Verify Data Replication

Wait 10 seconds for initial sync, then check PostgreSQL:

```bash
docker exec -i postgres18 psql -U admin -d target_db -c "SELECT * FROM dbo.employees ORDER BY employee_id;"
```

**Expected Output:**
```
 employee_id | first_name | last_name |         email          | phone_number | hire_date  |  salary  | is_active | __cdc_deleted
-------------+------------+-----------+-----------------------+--------------+------------+----------+-----------+---------------
           1 | John       | Doe       | john.doe@example.com   | 555-0001     | 2024-01-15 | 75000.00 | t         | false
           2 | Jane       | Smith     | jane.smith@example.com | 555-0002     | 2024-02-20 | 82000.00 | t         | false
           3 | Bob        | Johnson   | bob.johnson@...        | 555-0003     | 2024-03-10 | 68000.00 | t         | false
```

✅ **Success indicators:**
- All 3 records present
- Column names in snake_case (`first_name`, not `FirstName`)
- `__cdc_deleted` is `false`

### Test 2: Test INSERT

```bash
docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -d mig_test_db -C << 'EOF'
INSERT INTO dbo.Employees (FirstName, LastName, Email, Salary)
VALUES ('Alice', 'Williams', 'alice.w@example.com', 90000.00);
GO
EOF

# Wait 10 seconds
sleep 10

# Check PostgreSQL
docker exec -i postgres18 psql -U admin -d target_db -c "SELECT * FROM dbo.employees WHERE first_name = 'Alice';"
```

**Expected Output:**
```
 employee_id | first_name | last_name |        email         | ... | salary   | __cdc_deleted
-------------+------------+-----------+----------------------+-----+----------+---------------
           4 | Alice      | Williams  | alice.w@example.com  | ... | 90000.00 | false
```

### Test 3: Test UPDATE

```bash
docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -d mig_test_db -C << 'EOF'
UPDATE dbo.Employees 
SET Salary = 95000.00 
WHERE FirstName = 'Alice';
GO
EOF

sleep 10

docker exec -i postgres18 psql -U admin -d target_db -c "SELECT employee_id, first_name, salary FROM dbo.employees WHERE first_name = 'Alice';"
```

**Expected Output:**
```
 employee_id | first_name |  salary
-------------+------------+----------
           4 | Alice      | 95000.00
```

### Test 4: Test DELETE (Soft Delete)

```bash
docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -d mig_test_db -C << 'EOF'
DELETE FROM dbo.Employees WHERE FirstName = 'Alice';
GO
EOF

sleep 10

docker exec -i postgres18 psql -U admin -d target_db -c "SELECT employee_id, first_name, __cdc_deleted FROM dbo.employees WHERE first_name = 'Alice';"
```

**Expected Output:**
```
 employee_id | first_name | __cdc_deleted
-------------+------------+---------------
           4 | Alice      | true
```

✅ **Notice:** Record still exists but `__cdc_deleted = true`

### Test 5: Test VARCHAR Constraint

```bash
docker exec -i postgres18 psql -U admin -d target_db -c "INSERT INTO dbo.employees (employee_id, first_name, last_name) VALUES (9999, 'ThisIsAVeryLongFirstNameThatExceedsFiftyCharactersLimitForSure', 'Test');"
```

**Expected Output:**
```
ERROR:  value too long for type character varying(50)
```

✅ **Success:** VARCHAR(50) constraint is enforced!

### Run Complete Integration Test

#### 🐧 Linux / 🍎 macOS

```bash
cd ~/projects/mysql-cdc-go-single-table/debezium-setup

chmod +x scripts/integration-test.sh
./scripts/integration-test.sh
```

#### 🪟 Windows

For Windows, run tests manually as shown above (Test 1-5).

**Expected Output:**
```
=============================================================================
🎉 All integration tests PASSED!
=============================================================================

✅ Schema Verification:
   - VARCHAR(50) constraints: PASS
   - UUID native type: PASS
   - NUMERIC(10,2) precision: PASS

✅ Data Replication:
   - INSERT operations: PASS
   - UPDATE operations: PASS
   - DELETE operations (soft): PASS

✅ Constraint Enforcement:
   - VARCHAR length limits: PASS
   - Decimal precision: PASS
```

---

## Troubleshooting

### Issue 1: Containers Won't Start

**Symptoms:**
```
Error response from daemon: Ports are not available
```

**Solution:**
```bash
# Check what's using the ports
# Linux/macOS:
sudo lsof -i :1433  # MS SQL
sudo lsof -i :5432  # PostgreSQL
sudo lsof -i :8083  # Debezium Connect

# Windows:
netstat -ano | findstr :1433
netstat -ano | findstr :5432

# Kill conflicting processes or change ports in docker-compose.yml
```

### Issue 2: Python Script - ODBC Driver Not Found

**Symptoms:**
```
ImportError: libodbc.so.2: cannot open shared object file
```

**Solution (Linux):**
```bash
sudo apt-get install -y unixodbc unixodbc-dev
sudo ACCEPT_EULA=Y apt-get install -y msodbcsql18
```

**Solution (Windows):**
Download and install: https://docs.microsoft.com/en-us/sql/connect/odbc/download-odbc-driver-for-sql-server

### Issue 3: Connector Deployment Failed

**Symptoms:**
```
curl: (7) Failed to connect to localhost:8083
```

**Solution:**
```bash
# Check if Debezium Connect is running
docker logs debezium-connect

# Wait longer for startup (can take 60+ seconds)
sleep 60

# Retry deployment
curl http://localhost:8083/
```

### Issue 4: No Data Replicating

**Symptoms:**
PostgreSQL table is empty after 2+ minutes.

**Solution:**
```bash
# 1. Check connector status
curl http://localhost:8083/connectors/mssql-source-connector/status | jq
curl http://localhost:8083/connectors/postgres-sink-connector/status | jq

# 2. Check Kafka topics
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092

# 3. Check connector logs
docker logs debezium-connect | grep ERROR

# 4. Restart connectors
curl -X POST http://localhost:8083/connectors/mssql-source-connector/restart
curl -X POST http://localhost:8083/connectors/postgres-sink-connector/restart
```

### Issue 5: Maven Build Failed

**Symptoms:**
```
[ERROR] Failed to execute goal ... compilation failure
```

**Solution:**
```bash
# Verify Java version (must be 11+)
java -version

# Clean and rebuild
cd custom-smt
mvn clean install -U

# If still fails, check pom.xml Java version settings
```

### Issue 6: Schema Replication Script Hangs

**Symptoms:**
Python script hangs at "Connecting to MS SQL Server..."

**Solution:**
```bash
# 1. Verify MS SQL is accessible
docker exec -i mssql-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U Sa -P 'YourStrong@Passw0rd' -C -Q "SELECT @@VERSION"

# 2. Check credentials in replicate-schema.py
# Verify: server, username, password

# 3. Check ODBC driver is installed
odbcinst -q -d

# 4. Run with timeout
timeout 30 python3 scripts/replicate-schema.py
```

### Issue 7: Python Package Installation Error (Ubuntu 24.04+)

**Symptoms:**
```
error: externally-managed-environment

× This environment is externally managed
╰─> To install Python packages system-wide, try apt install
    python3-xyz, where xyz is the package you are trying to
    install.
```

**Root Cause:**
Ubuntu 24.04 and newer systems implement **PEP 668** to prevent system Python breakage. This blocks direct `pip install` commands globally.

**Solution 1: Use Virtual Environment (RECOMMENDED)**

This is the **safest and best practice** method:

```bash
# Create a virtual environment in your project
cd ~/projects/mysql-cdc-go-single-table/debezium-setup
python3 -m venv .venv

# Activate the virtual environment
source .venv/bin/activate

# Install packages (now safe!)
pip install pyodbc psycopg2-binary

# Verify installation
pip list | grep -E "pyodbc|psycopg2"

# Run your script
python3 scripts/replicate-schema.py

# When done, deactivate
deactivate
```

**For future runs, always activate the virtual environment first:**
```bash
cd ~/projects/mysql-cdc-go-single-table/debezium-setup
source .venv/bin/activate
python3 scripts/replicate-schema.py
```

**Solution 2: Use System Packages (ALTERNATIVE)**

Install packages through apt (pre-compiled, may be older versions):

```bash
# Install system packages
sudo apt-get install -y python3-pyodbc python3-psycopg2

# Run script normally
python3 scripts/replicate-schema.py
```

**Solution 3: Override PEP 668 (NOT RECOMMENDED)**

Only use this if you understand the risks:

```bash
# This can break your system Python!
pip install --break-system-packages pyodbc psycopg2-binary
```

⚠️ **Warning:** Solution 3 bypasses safety checks and may cause system instability. Always prefer Solution 1 (virtual environment).

**Why Virtual Environments Are Better:**
- ✅ Isolated dependencies per project
- ✅ No conflicts with system Python
- ✅ Easy to replicate across machines
- ✅ Can use different package versions per project
- ✅ Safe to delete and recreate

---

## Credentials Management

### Default Credentials

| Service | Username | Password | Database | Port |
|---------|----------|----------|----------|------|
| **MS SQL Server** | `sa` | `YourStrong@Passw0rd` | `mig_test_db` | 1433 |
| **PostgreSQL** | `admin` | `admin123` | `target_db` | 5432 |
| **Kafka** | - | - | - | 9092 |
| **Debezium Connect** | - | - | - | 8083 |
| **Kafka UI** | - | - | - | 8080 |

### Changing Credentials

To change default credentials, update these files:

1. **docker-compose.yml**
```yaml
services:
  mssql-test:
    environment:
      SA_PASSWORD: "YourNewPassword!"  # <-- Change this
  
  postgres18:
    environment:
      POSTGRES_PASSWORD: admin456  # <-- Change this
```

2. **connectors/mssql-source.json**
```json
{
  "config": {
    "database.password": "YourNewPassword!"  // <-- Change this
  }
}
```

3. **connectors/postgres-sink.json**
```json
{
  "config": {
    "connection.password": "admin456"  // <-- Change this
  }
}
```

4. **scripts/replicate-schema.py**
```python
MSSQL_CONFIG = {
    'password': 'YourNewPassword!',  # <-- Change this
}

POSTGRES_CONFIG = {
    'password': 'admin456',  # <-- Change this
}
```

5. **Restart everything:**
```bash
docker compose down
docker compose up -d
```

### Security Best Practices

For production deployments:

1. **Use environment variables:**
```bash
# Set environment variables
export MSSQL_SA_PASSWORD="SecurePassword123!"
export POSTGRES_PASSWORD="SecurePostgres456!"

# Reference in docker-compose.yml
environment:
  SA_PASSWORD: "${MSSQL_SA_PASSWORD}"
```

2. **Use Docker secrets:**
```yaml
secrets:
  mssql_password:
    file: ./secrets/mssql_password.txt
  
services:
  mssql-test:
    secrets:
      - mssql_password
```

3. **Rotate credentials regularly**
4. **Use strong passwords** (16+ characters, mixed case, numbers, symbols)
5. **Restrict network access** (firewall rules, VPN)
6. **Enable SSL/TLS** for database connections
7. **Use secrets management** (HashiCorp Vault, AWS Secrets Manager)

---

## Next Steps

### Adding More Tables

1. **Create table in MS SQL:**
```sql
CREATE TABLE dbo.YourNewTable (
    ID INT PRIMARY KEY,
    Name NVARCHAR(100),
    CreatedAt DATETIME2
);
```

2. **Enable CDC:**
```sql
EXEC sys.sp_cdc_enable_table 
    @source_schema = N'dbo',
    @source_name = N'YourNewTable',
    @role_name = NULL;
```

3. **Run schema replication:**
```bash
python3 scripts/replicate-schema.py
```

4. **Restart sink connector:**
```bash
curl -X POST http://localhost:8083/connectors/postgres-sink-connector/restart
```

5. **Insert data - it will automatically replicate!**

### Monitoring

**Kafka UI:** http://localhost:8080
- View topics
- Monitor message flow
- Check consumer lag

**Debezium Connect REST API:**
```bash
# List connectors
curl http://localhost:8083/connectors | jq

# Check connector status
curl http://localhost:8083/connectors/mssql-source-connector/status | jq

# View connector config
curl http://localhost:8083/connectors/mssql-source-connector | jq
```

**Container Logs:**
```bash
docker logs -f debezium-connect
docker logs -f kafka
docker logs -f mssql-test
docker logs -f postgres18
```

---

## Summary

You now have a **fully automatic** CDC system that:

✅ Automatically replicates table schemas (no manual CREATE TABLE!)
✅ Preserves VARCHAR lengths, UUID types, DECIMAL precision
✅ Transforms column names to snake_case
✅ Replicates INSERT, UPDATE, DELETE in real-time
✅ Supports soft deletes with `__cdc_deleted` column
✅ Works on Windows, Linux, and macOS

**To add new tables:**
1. Create in MS SQL + enable CDC
2. Run `python3 scripts/replicate-schema.py`
3. Data automatically replicates!

**For help:**
- Check [Troubleshooting](#troubleshooting) section
- Review logs: `docker logs debezium-connect`
- See [AUTOMATIC_SCHEMA_REPLICATION.md](AUTOMATIC_SCHEMA_REPLICATION.md) for details

**Enjoy your automatic CDC pipeline! 🎉**
