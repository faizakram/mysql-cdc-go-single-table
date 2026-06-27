# Windows Commands for Debezium CDC Setup

## Quick Start for Production Windows Machine

**If you're getting ODBC driver errors, follow these steps:**

### Step 1: Check Your System
```cmd
cd C:\path\to\mysql-cdc-go-single-table\debezium-setup
python scripts\check-windows-setup.py
```
This will tell you exactly what's missing.

### Step 2: Install ODBC Driver (If Missing)
Download and install from one of these links:
- **ODBC Driver 18** (Latest): https://go.microsoft.com/fwlink/?linkid=2249004
- **ODBC Driver 17** (Alternative): https://go.microsoft.com/fwlink/?linkid=2249006

Installation steps:
1. Download "msodbcsql.msi" (64-bit)
2. Run the installer
3. Accept license and click "Install"
4. **Important:** Restart your terminal/PowerShell after installation

### Step 3: Verify Installation
```cmd
python -c "import pyodbc; print('\n'.join(pyodbc.drivers()))"
```
You should see: `ODBC Driver 18 for SQL Server` or `ODBC Driver 17 for SQL Server`

### Step 4: Run the Sync
```cmd
cd C:\path\to\mysql-cdc-go-single-table\debezium-setup
venv\Scripts\activate
python scripts\sync-data.py
```

**The script now auto-detects available drivers, so it will work with any version installed!**

---

## Prerequisites
- Install Docker Desktop for Windows
- Install Python 3.8+
- Install Git for Windows
- **Install Microsoft ODBC Driver for SQL Server** (Required for pyodbc)
  - Download from: https://learn.microsoft.com/en-us/sql/connect/odbc/download-odbc-driver-for-sql-server
  - Install "ODBC Driver 18 for SQL Server" or "ODBC Driver 17 for SQL Server"
  - Choose the Windows 64-bit installer

## 1. Navigate to Project Directory
```cmd
cd C:\path\to\mysql-cdc-go-single-table\debezium-setup
```

## 2. Start Infrastructure (Docker Compose)
```cmd
docker-compose up -d
```

## 3. Stop Infrastructure
```cmd
docker-compose down
```

## 4. Create Python Virtual Environment
```cmd
python -m venv venv
```

## 5. Activate Virtual Environment
```cmd
venv\Scripts\activate
```

## 6. Install Python Dependencies
```cmd
pip install pyodbc psycopg2-binary python-dotenv
```

## 7. Run Deployment Script

### For PowerShell:
```powershell
bash scripts/deploy-all.sh
```

### Alternative - Run Individual Steps:
```cmd
REM Replicate schema
python scripts\replicate-schema.py

REM Sync data (full-load mode)
python scripts\sync-data.py
```

## 8. Check Docker Container Status
```cmd
docker ps
```

## 9. Check Docker Container Logs
```cmd
REM MS SQL Server
docker logs mssql-source

REM PostgreSQL
docker logs postgres-target

REM Kafka
docker logs kafka

REM Debezium Connect
docker logs debezium-connect
```

## 10. Connect to MS SQL Server (Docker)
```cmd
docker exec -it mssql-source /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "YourStrong@Passw0rd" -C
```

## 11. Connect to PostgreSQL (Docker)
```cmd
docker exec -it postgres-target psql -U postgres -d target_db
```

## 12. Check Kafka Topics
```cmd
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092
```

## 13. Check Debezium Connectors (CDC Mode)
```cmd
REM List all connectors
curl http://localhost:8083/connectors

REM Check source connector status
curl http://localhost:8083/connectors/mssql-source-connector/status

REM Check sink connector status
curl http://localhost:8083/connectors/postgres-sink-connector/status
```

## 14. Git Commands
```cmd
REM Check current branch
git branch

REM Switch to CDC mode branch
git checkout feature/snake-case-transformation

REM Switch to full-load mode branch
git checkout feature/full-load-replication

REM Check status
git status

REM Add changes
git add .

REM Commit changes
git commit -m "Your commit message"

REM Push changes
git push origin branch-name
```

## 15. Environment Configuration
Edit `.env` file in a text editor (Notepad, VS Code, etc.)

### For CDC Mode:
```env
CDC_ENABLED=true
```

### For Full-Load Mode:
```env
CDC_ENABLED=false
```

## 16. Run Full-Load Data Sync
```cmd
python scripts\sync-data.py
```

## 17. Check PostgreSQL Data
```cmd
docker exec postgres-target psql -U postgres -d target_db -c "SELECT COUNT(*) FROM campaign_final_v2.employees;"
```

## 18. Check MS SQL Data
```cmd
docker exec -it mssql-source /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "YourStrong@Passw0rd" -C -Q "USE Employees; SELECT COUNT(*) FROM dbo.Employees;"
```

## 19. Clean Up Everything
```cmd
REM Stop and remove containers
docker-compose down -v

REM Remove virtual environment
rmdir /s /q venv
```

## 20. Rebuild Everything from Scratch
```cmd
REM Clean up
docker-compose down -v
rmdir /s /q venv

REM Create venv
python -m venv venv
venv\Scripts\activate
pip install pyodbc psycopg2-binary python-dotenv

REM Start infrastructure
docker-compose up -d

REM Wait 30 seconds for services to start
timeout /t 30

REM Run deployment
bash scripts/deploy-all.sh
```

## Common Issues and Solutions

### Issue 1: Port Already in Use
```cmd
REM Check what's using the port
netstat -ano | findstr :1433
netstat -ano | findstr :5432
netstat -ano | findstr :9092

REM Kill the process (replace PID with actual process ID)
taskkill /PID <PID> /F
```

### Issue 2: Docker Not Running
```cmd
REM Start Docker Desktop manually or:
"C:\Program Files\Docker\Docker\Docker Desktop.exe"
```

### Issue 3: Python Module Not Found
```cmd
REM Make sure virtual environment is activated
venv\Scripts\activate

REM Reinstall dependencies
pip install --force-reinstall pyodbc psycopg2-binary python-dotenv
```

### Issue 6: ODBC Driver Error - "Data source name not found"
**Error Message:**
```
❌ Failed to connect to MS SQL: ('IM002', '[IM002] [Microsoft][ODBC Driver Manager] Data source name not found and no default driver specified (0) (SQLDriverConnect)')
```

**Solution:**
1. Install Microsoft ODBC Driver for SQL Server:
   - Download: https://learn.microsoft.com/en-us/sql/connect/odbc/download-odbc-driver-for-sql-server
   - Install "ODBC Driver 18 for SQL Server" (recommended) or "ODBC Driver 17 for SQL Server"
   - Use the Windows 64-bit installer

2. Verify installation:
```cmd
REM Open ODBC Data Source Administrator
odbcad32.exe

REM Or check via PowerShell
Get-OdbcDriver | Where-Object {$_.Name -like "*SQL Server*"}
```

3. If driver is installed but still getting error, check available drivers:
```python
# Run in Python to see available ODBC drivers
import pyodbc
print(pyodbc.drivers())
```

4. The script will now auto-detect available drivers, but you can specify one in `.env`:
```env
# Add this line to .env file
MSSQL_DRIVER={ODBC Driver 17 for SQL Server}
```

Available driver options:
   - `{ODBC Driver 18 for SQL Server}` (recommended, latest)
   - `{ODBC Driver 17 for SQL Server}` (widely compatible)
   - `{ODBC Driver 13 for SQL Server}` (older systems)
   - `{SQL Server Native Client 11.0}` (legacy)
   - `{SQL Server}` (very old, not recommended)

5. Quick fix - Run this Python command to see available drivers:
```cmd
python -c "import pyodbc; print('\n'.join(pyodbc.drivers()))"
```

6. After installing the driver, restart your terminal/PowerShell and try again

### Issue 4: Permission Denied
```cmd
REM Run PowerShell or CMD as Administrator
REM Right-click > Run as administrator
```

### Issue 5: Git Bash Not Found
```cmd
REM Install Git for Windows from: https://git-scm.com/download/win
REM Or use PowerShell:
powershell -File scripts\deploy-all.ps1
```

## PowerShell Specific Commands

### Set Execution Policy (if needed)
```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
```

### Run Scripts
```powershell
# Deploy all
.\scripts\deploy-all.sh

# Sync data
python .\scripts\sync-data.py
```

### Check Process
```powershell
Get-Process | Where-Object {$_.ProcessName -like "*docker*"}
```

## Useful Windows Aliases (Optional)
Create a `aliases.cmd` file:
```cmd
@echo off
doskey dc=docker-compose $*
doskey dps=docker ps $*
doskey dlogs=docker logs $*
doskey activate=venv\Scripts\activate
doskey py=python $*
```

Run it:
```cmd
aliases.cmd
```

## Quick Reference

| Task | Windows Command |
|------|----------------|
| **Check setup** | `python scripts\check-windows-setup.py` |
| Activate venv | `venv\Scripts\activate` |
| Run sync | `python scripts\sync-data.py` |
| Check drivers | `python -c "import pyodbc; print('\n'.join(pyodbc.drivers()))"` |
| Docker up | `docker-compose up -d` |
| Docker down | `docker-compose down` |
| Check logs | `docker logs <container-name>` |
| SQL Server | `docker exec -it mssql-source /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "YourStrong@Passw0rd" -C` |
| PostgreSQL | `docker exec -it postgres-target psql -U postgres -d target_db` |
| Git checkout | `git checkout <branch-name>` |
| Git status | `git status` |

## Environment Variables (Windows)
```cmd
REM Set environment variable temporarily
set CDC_ENABLED=false

REM Set environment variable permanently (requires admin)
setx CDC_ENABLED "false"
```

## Notes
- Use backslashes (`\`) for Windows paths instead of forward slashes (`/`)
- Use `timeout /t 10` instead of `sleep 10`
- Use `rmdir /s /q` instead of `rm -rf`
- Use `type` instead of `cat`
- Use `dir` instead of `ls`
- Use `cls` instead of `clear`
