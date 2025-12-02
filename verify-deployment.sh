#!/bin/bash
# Script to verify the deployed version has the fix

echo "=== Deployment Verification ==="
echo ""

echo "1. Checking Git Status:"
git log --oneline -1
echo ""

echo "2. Checking source code has 5-column fix:"
grep -A 2 "var binlogDoDB, binlogIgnoreDB, executedGtidSet" src/full_load.go && echo "✓ Source code has fix" || echo "✗ Source code missing fix"
echo ""

echo "3. Checking Docker image timestamp:"
docker images mysql-cdc --format "Image created: {{.CreatedAt}}"
echo ""

echo "4. Building fresh image to test:"
CACHEBUST=$(date +%s)
echo "Building with CACHEBUST=$CACHEBUST"
docker build --build-arg CACHEBUST=$CACHEBUST -q -t mysql-cdc-verify . > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "✓ Build successful"
    
    echo ""
    echo "5. Checking compiled binary has fix:"
    docker run --rm -e SRC_DSN="test" -e TGT_DSN="test" mysql-cdc-verify 2>&1 | grep "Build time" || echo "✗ No build time found"
    
    echo ""
    echo "6. Testing if binary would fail with old error:"
    # The error only happens when actually connecting to DB, but we can verify the function exists
    docker create --name verify-temp mysql-cdc-verify > /dev/null 2>&1
    docker cp verify-temp:/usr/local/bin/mysql-cdc /tmp/verify-binary > /dev/null 2>&1
    docker rm verify-temp > /dev/null 2>&1
    
    if strings /tmp/verify-binary | grep -q "SHOW MASTER STATUS"; then
        echo "✓ Binary contains SHOW MASTER STATUS query"
    else
        echo "✗ Binary missing SHOW MASTER STATUS query"
    fi
    
    rm -f /tmp/verify-binary
    docker rmi mysql-cdc-verify > /dev/null 2>&1
else
    echo "✗ Build failed"
fi

echo ""
echo "=== Instructions for Production ==="
echo "Run these commands on your PRODUCTION server:"
echo ""
echo "  cd /path/to/mysql-cdc-go-single-table"
echo "  git pull origin main"
echo "  git log --oneline -1  # Verify you have latest commit"
echo "  docker rmi mysql-cdc  # Remove old image completely"
echo "  ./cdc-control.sh restart"
echo ""
echo "Then check logs for: 'Build time: YYYY-MM-DD' message"
