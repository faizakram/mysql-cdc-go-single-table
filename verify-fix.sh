#!/bin/bash

# Production Server Diagnostic Script
# Run this on your production server to verify the timeout fix is applied

echo "╔════════════════════════════════════════════════════════════╗"
echo "║     MySQL CDC Timeout Fix - Production Verification       ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# Check 1: Git repository status
echo "1️⃣  Checking Git repository status..."
CURRENT_COMMIT=$(git rev-parse HEAD 2>/dev/null || echo "NOT A GIT REPO")
LATEST_COMMIT=$(git rev-parse origin/main 2>/dev/null || echo "UNKNOWN")
echo "   Current commit: $CURRENT_COMMIT"
echo "   Latest commit:  $LATEST_COMMIT"

if [ "$CURRENT_COMMIT" != "$LATEST_COMMIT" ]; then
    echo "   ⚠️  WARNING: Your code is NOT up to date!"
    echo "   Run: git pull origin main"
    echo ""
fi

# Check 2: Verify code has the fix
echo "2️⃣  Checking if source code has the timeout fix..."
if grep -q "SetConnMaxLifetime(10 \* time.Minute)" src/db.go 2>/dev/null; then
    echo "   ✅ Source code has connection pool fix"
else
    echo "   ❌ Source code MISSING connection pool fix"
    echo "   Run: git pull origin main"
fi

if grep -q "readTimeout=300s" src/config.go 2>/dev/null; then
    echo "   ✅ Source code has 300s timeout in config"
else
    echo "   ❌ Source code MISSING 300s timeout"
fi

if grep -q "readTimeout=300s" run-external-db.sh 2>/dev/null; then
    echo "   ✅ run-external-db.sh has 300s timeout"
else
    echo "   ❌ run-external-db.sh MISSING 300s timeout"
fi
echo ""

# Check 3: Docker image age
echo "3️⃣  Checking Docker image age..."
IMAGE_DATE=$(docker inspect mysql-cdc-go-single-table --format='{{.Created}}' 2>/dev/null || echo "NOT FOUND")
echo "   Image created: $IMAGE_DATE"

if [ "$IMAGE_DATE" = "NOT FOUND" ]; then
    echo "   ❌ Docker image not found!"
    echo "   Run: docker build -t mysql-cdc-go-single-table ."
else
    # Check if image is old (more than 10 minutes)
    IMAGE_TIMESTAMP=$(docker inspect mysql-cdc-go-single-table --format='{{.Created}}' | cut -d'.' -f1)
    CURRENT_TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%S")
    echo "   Current time:  $CURRENT_TIMESTAMP"
    
    # Simple age check - if image is from before today, it's likely old
    IMAGE_DATE_ONLY=$(echo $IMAGE_TIMESTAMP | cut -d'T' -f1)
    CURRENT_DATE_ONLY=$(echo $CURRENT_TIMESTAMP | cut -d'T' -f1)
    
    if [ "$IMAGE_DATE_ONLY" != "$CURRENT_DATE_ONLY" ]; then
        echo "   ⚠️  WARNING: Docker image is from a previous day"
        echo "   Rebuild: docker build -t mysql-cdc-go-single-table ."
    fi
fi
echo ""

# Check 4: Running container status
echo "4️⃣  Checking running container..."
CONTAINER_ID=$(docker ps -q -f name=mysql-cdc)
if [ -z "$CONTAINER_ID" ]; then
    echo "   ℹ️  No mysql-cdc container running"
else
    echo "   Container ID: $CONTAINER_ID"
    CONTAINER_IMAGE=$(docker inspect $CONTAINER_ID --format='{{.Config.Image}}' 2>/dev/null)
    CONTAINER_CREATED=$(docker inspect $CONTAINER_ID --format='{{.Created}}' 2>/dev/null | cut -d'.' -f1)
    echo "   Using image: $CONTAINER_IMAGE"
    echo "   Started at: $CONTAINER_CREATED"
    
    # Check if container is using the latest image
    RUNNING_IMAGE_ID=$(docker inspect $CONTAINER_ID --format='{{.Image}}' 2>/dev/null)
    LATEST_IMAGE_ID=$(docker images mysql-cdc-go-single-table -q | head -1)
    
    if [ "$RUNNING_IMAGE_ID" != "$LATEST_IMAGE_ID" ]; then
        echo "   ⚠️  WARNING: Container is using OLD image!"
        echo "   Current container image: $RUNNING_IMAGE_ID"
        echo "   Latest built image:      $LATEST_IMAGE_ID"
        echo ""
        echo "   Fix: Stop and restart with new image"
    else
        echo "   ✅ Container is using the latest image"
    fi
fi
echo ""

# Check 5: Environment variables (if container is running)
if [ ! -z "$CONTAINER_ID" ]; then
    echo "5️⃣  Checking environment variables..."
    SRC_DSN=$(docker inspect $CONTAINER_ID --format='{{range .Config.Env}}{{println .}}{{end}}' | grep "^SRC_DSN=" | cut -d'=' -f2-)
    
    if [ ! -z "$SRC_DSN" ]; then
        if echo "$SRC_DSN" | grep -q "readTimeout=300s"; then
            echo "   ✅ SRC_DSN has readTimeout=300s"
        elif echo "$SRC_DSN" | grep -q "readTimeout=30s"; then
            echo "   ❌ SRC_DSN still has OLD readTimeout=30s"
            echo "   The container is using old environment variables!"
        elif echo "$SRC_DSN" | grep -q "readTimeout="; then
            TIMEOUT_VAL=$(echo "$SRC_DSN" | grep -o "readTimeout=[^&]*" | cut -d'=' -f2)
            echo "   ⚠️  SRC_DSN has readTimeout=$TIMEOUT_VAL"
        else
            echo "   ⚠️  SRC_DSN has NO readTimeout parameter"
        fi
    else
        echo "   ℹ️  No SRC_DSN environment variable (using defaults from config.go)"
    fi
fi
echo ""

# Summary and Recommendations
echo "╔════════════════════════════════════════════════════════════╗"
echo "║                    RECOMMENDATIONS                         ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# Determine if fix is needed
NEEDS_FIX=false

if [ "$CURRENT_COMMIT" != "$LATEST_COMMIT" ]; then
    NEEDS_FIX=true
    echo "📋 Step 1: Update code"
    echo "   git fetch origin"
    echo "   git pull origin main"
    echo ""
fi

if ! grep -q "SetConnMaxLifetime(10 \* time.Minute)" src/db.go 2>/dev/null || \
   ! grep -q "readTimeout=300s" src/config.go 2>/dev/null; then
    NEEDS_FIX=true
fi

if [ "$NEEDS_FIX" = "true" ] || [ "$IMAGE_DATE" = "NOT FOUND" ]; then
    echo "📋 Step 2: Rebuild Docker image"
    echo "   docker build -t mysql-cdc-go-single-table ."
    echo ""
fi

if [ ! -z "$CONTAINER_ID" ]; then
    RUNNING_IMAGE_ID=$(docker inspect $CONTAINER_ID --format='{{.Image}}' 2>/dev/null)
    LATEST_IMAGE_ID=$(docker images mysql-cdc-go-single-table -q | head -1)
    
    if [ "$RUNNING_IMAGE_ID" != "$LATEST_IMAGE_ID" ] || [ "$NEEDS_FIX" = "true" ]; then
        echo "📋 Step 3: Stop old container"
        echo "   docker stop mysql-cdc"
        echo "   docker rm mysql-cdc"
        echo ""
        
        echo "📋 Step 4: Clear checkpoint (optional - for fresh test)"
        echo "   mysql -h <host> -P <port> -u<user> -p<pass> <db> \\"
        echo "     -e \"TRUNCATE TABLE <table>; DROP TABLE IF EXISTS _cdc_checkpoint;\""
        echo ""
        
        echo "📋 Step 5: Start with new image"
        echo "   ./run-external-db.sh"
        echo ""
    fi
fi

if [ "$NEEDS_FIX" = "false" ] && [ ! -z "$CONTAINER_ID" ]; then
    RUNNING_IMAGE_ID=$(docker inspect $CONTAINER_ID --format='{{.Image}}' 2>/dev/null)
    LATEST_IMAGE_ID=$(docker images mysql-cdc-go-single-table -q | head -1)
    
    if [ "$RUNNING_IMAGE_ID" = "$LATEST_IMAGE_ID" ]; then
        echo "✅ Everything looks good! Container is using the latest image with fixes."
        echo ""
        echo "If you're still seeing timeouts, check:"
        echo "  1. MySQL server-side timeout settings:"
        echo "     mysql> SHOW VARIABLES LIKE '%timeout%';"
        echo ""
        echo "  2. Table indexes for ORDER BY optimization:"
        echo "     mysql> SHOW INDEX FROM your_table;"
        echo ""
        echo "  3. Query execution time in MySQL slow query log"
        echo ""
    fi
fi

echo "════════════════════════════════════════════════════════════"
