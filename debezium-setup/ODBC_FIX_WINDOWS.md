# ODBC Driver Error Fix for Windows Production

## Problem
Getting this error when running `python scripts\sync-data.py`:
```
❌ Failed to connect to MS SQL: ('IM002', '[IM002] [Microsoft][ODBC Driver Manager] Data source name not found and no default driver specified (0) (SQLDriverConnect)')
```

## Solution

### Quick Fix (3 Steps)

#### 1. Install Microsoft ODBC Driver for SQL Server

**Download Link:** https://go.microsoft.com/fwlink/?linkid=2249004

- Click the link above
- Download "msodbcsql.msi" (choose 64-bit if your Windows is 64-bit)
- Run the installer
- Accept the license agreement
- Click "Install"
- **Important:** Close and reopen your PowerShell/CMD terminal after installation

#### 2. Verify Installation

Open a new PowerShell or CMD window and run:
```cmd
python -c "import pyodbc; print('\n'.join(pyodbc.drivers()))"
```

You should see output like:
```
ODBC Driver 18 for SQL Server
SQL Server
```

If you see "ODBC Driver 18 for SQL Server" or "ODBC Driver 17 for SQL Server", you're good!

#### 3. Run the Sync Again

```cmd
cd C:\Users\1040028\NRT-OfferCraft\mysql-cdc-go-single-table\debezium-setup
venv\Scripts\activate
python scripts\sync-data.py
```

## What Was Fixed

### 1. Updated sync-data.py
The script now automatically tries multiple ODBC drivers in order:
- ODBC Driver 18 for SQL Server (latest)
- ODBC Driver 17 for SQL Server
- ODBC Driver 13 for SQL Server
- SQL Server Native Client 11.0 (legacy)
- SQL Server (very old)

It will use the first one it finds, so you don't need to configure anything!

### 2. Added Driver Configuration to .env
You can now optionally specify which driver to use in the `.env` file:
```env
MSSQL_DRIVER={ODBC Driver 17 for SQL Server}
```

But this is optional - the script auto-detects available drivers.

### 3. Added Setup Checker
Run this to check if everything is installed correctly:
```cmd
python scripts\check-windows-setup.py
```

It will tell you exactly what's missing and how to fix it.

## Alternative: If You Can't Install ODBC Driver 18

If you can't install ODBC Driver 18, try these alternatives:

### Option 1: ODBC Driver 17
Download: https://go.microsoft.com/fwlink/?linkid=2249006

### Option 2: Check What's Already Installed
```cmd
python -c "import pyodbc; print('\n'.join(pyodbc.drivers()))"
```

If you see any SQL Server driver, the script will now automatically use it!

### Option 3: Manual Configuration
If you have a different driver installed, add this to your `.env` file:
```env
MSSQL_DRIVER={Your Driver Name Here}
```

Replace `{Your Driver Name Here}` with the exact name from the list you got above.

## Testing Connection

After installing the driver, test the connection:

```cmd
cd C:\Users\1040028\NRT-OfferCraft\mysql-cdc-go-single-table\debezium-setup
venv\Scripts\activate
python scripts\check-windows-setup.py
```

If all checks pass (7/7), you're ready to run:
```cmd
python scripts\sync-data.py
```

## Troubleshooting

### Issue: "pyodbc not found"
```cmd
venv\Scripts\activate
pip install --force-reinstall pyodbc psycopg2-binary python-dotenv
```

### Issue: Still getting ODBC error after installing driver
1. Make sure you closed and reopened your terminal
2. Verify installation:
   ```cmd
   odbcad32.exe
   ```
   This opens ODBC Data Source Administrator. Check the "Drivers" tab.

### Issue: Permission denied during installation
- Right-click PowerShell/CMD
- Select "Run as Administrator"
- Try installation again

## What the Error Means

The error `Data source name not found and no default driver specified` means:
- Python's `pyodbc` module is installed ✅
- But the actual ODBC driver that talks to SQL Server is missing ❌

Think of it like:
- `pyodbc` = The steering wheel in your car (installed)
- `ODBC Driver` = The engine (missing - needs to be installed)

## Summary

**The fix is simple:**
1. Install: https://go.microsoft.com/fwlink/?linkid=2249004
2. Restart terminal
3. Run: `python scripts\sync-data.py`

**The script now auto-detects drivers, so once installed, it just works!**

## Need Help?

If you're still having issues after following these steps:

1. Run the checker: `python scripts\check-windows-setup.py`
2. Share the output
3. Also share the output of: `python -c "import pyodbc; print(pyodbc.drivers())"`
