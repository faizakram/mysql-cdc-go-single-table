#!/usr/bin/env python3
"""
Windows Setup Checker
Validates that all required components are installed
"""

import sys
import os

def check_python():
    """Check Python version"""
    version = sys.version_info
    if version.major >= 3 and version.minor >= 8:
        print(f"✅ Python {version.major}.{version.minor}.{version.micro} - OK")
        return True
    else:
        print(f"❌ Python {version.major}.{version.minor}.{version.micro} - Need 3.8+")
        return False

def check_pyodbc():
    """Check if pyodbc is installed"""
    try:
        import pyodbc
        print(f"✅ pyodbc {pyodbc.version} - OK")
        return True
    except ImportError:
        print(f"❌ pyodbc - NOT INSTALLED")
        print(f"   Install: pip install pyodbc")
        return False

def check_psycopg2():
    """Check if psycopg2 is installed"""
    try:
        import psycopg2
        print(f"✅ psycopg2 - OK")
        return True
    except ImportError:
        print(f"❌ psycopg2 - NOT INSTALLED")
        print(f"   Install: pip install psycopg2-binary")
        return False

def check_dotenv():
    """Check if python-dotenv is installed"""
    try:
        import dotenv
        print(f"✅ python-dotenv - OK")
        return True
    except ImportError:
        print(f"❌ python-dotenv - NOT INSTALLED")
        print(f"   Install: pip install python-dotenv")
        return False

def check_odbc_drivers():
    """Check available ODBC drivers"""
    try:
        import pyodbc
        drivers = pyodbc.drivers()
        
        sql_drivers = [d for d in drivers if 'SQL Server' in d]
        
        if sql_drivers:
            print(f"✅ ODBC Drivers for SQL Server:")
            for driver in sql_drivers:
                print(f"   • {driver}")
            return True
        else:
            print(f"❌ No SQL Server ODBC drivers found!")
            print(f"\n📥 Install Microsoft ODBC Driver:")
            print(f"   Download: https://go.microsoft.com/fwlink/?linkid=2249004")
            print(f"   Or visit: https://learn.microsoft.com/en-us/sql/connect/odbc/download-odbc-driver-for-sql-server")
            return False
    except ImportError:
        print(f"⚠️  Cannot check ODBC drivers (pyodbc not installed)")
        return False

def check_env_file():
    """Check if .env file exists"""
    env_file = os.path.join(os.path.dirname(__file__), '..', '.env')
    if os.path.exists(env_file):
        print(f"✅ .env file - Found")
        return True
    else:
        print(f"❌ .env file - NOT FOUND")
        print(f"   Location: {os.path.abspath(env_file)}")
        return False

def check_docker():
    """Check if Docker is accessible"""
    try:
        import subprocess
        result = subprocess.run(['docker', '--version'], 
                              capture_output=True, 
                              text=True, 
                              timeout=5)
        if result.returncode == 0:
            print(f"✅ Docker - {result.stdout.strip()}")
            return True
        else:
            print(f"❌ Docker - Not accessible")
            return False
    except (FileNotFoundError, subprocess.TimeoutExpired):
        print(f"❌ Docker - Not installed or not in PATH")
        return False

def main():
    print("=" * 60)
    print("Windows Setup Checker for Debezium CDC")
    print("=" * 60)
    print()
    
    results = []
    
    print("🔍 Checking Python...")
    results.append(check_python())
    print()
    
    print("🔍 Checking Python packages...")
    results.append(check_pyodbc())
    results.append(check_psycopg2())
    results.append(check_dotenv())
    print()
    
    print("🔍 Checking ODBC drivers...")
    results.append(check_odbc_drivers())
    print()
    
    print("🔍 Checking configuration...")
    results.append(check_env_file())
    print()
    
    print("🔍 Checking Docker...")
    results.append(check_docker())
    print()
    
    print("=" * 60)
    passed = sum(results)
    total = len(results)
    
    if passed == total:
        print(f"✅ All checks passed ({passed}/{total})")
        print()
        print("🚀 You're ready to run:")
        print("   python scripts\\sync-data.py")
    else:
        print(f"⚠️  Some checks failed ({passed}/{total} passed)")
        print()
        print("📋 Next steps:")
        print("   1. Fix the issues marked with ❌")
        print("   2. Run this script again to verify")
        print("   3. Once all checks pass, run: python scripts\\sync-data.py")
    
    print("=" * 60)
    
    return 0 if passed == total else 1

if __name__ == '__main__':
    sys.exit(main())
