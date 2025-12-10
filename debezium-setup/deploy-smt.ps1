# Build and deploy custom Snake Case SMT to Debezium Connect
# This script compiles the Java SMT and deploys it to the running Debezium container
# PowerShell script for Windows

$ErrorActionPreference = "Stop"

$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
$SMT_DIR = Join-Path $SCRIPT_DIR "custom-smt"

Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host "Building Snake Case Transform SMT" -ForegroundColor Cyan
Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host ""

# Check if Maven is installed
$mvnCommand = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvnCommand) {
    Write-Host "❌ Maven is not installed." -ForegroundColor Red
    Write-Host "Please install Maven from: https://maven.apache.org/download.cgi" -ForegroundColor Yellow
    Write-Host "Or use Chocolatey: choco install maven" -ForegroundColor Yellow
    exit 1
}

# Build the SMT
Write-Host "📦 Building JAR..." -ForegroundColor Green
Set-Location $SMT_DIR
& mvn clean package -DskipTests

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Maven build failed!" -ForegroundColor Red
    exit 1
}

$JAR_FILE = Join-Path $SMT_DIR "target\snake-case-transform-1.0.0.jar"

if (-not (Test-Path $JAR_FILE)) {
    Write-Host "❌ Build failed! JAR not found at $JAR_FILE" -ForegroundColor Red
    exit 1
}

Write-Host "✅ Build successful: $JAR_FILE" -ForegroundColor Green
Write-Host ""

# Deploy to Debezium Connect
Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host "Deploying to Debezium Connect" -ForegroundColor Cyan
Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host ""

# Copy JAR to Debezium Connect container
Write-Host "📤 Copying JAR to Debezium Connect container..." -ForegroundColor Green
& docker cp $JAR_FILE debezium-connect:/kafka/connect/snake-case-transform-1.0.0.jar

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Failed to copy JAR to container" -ForegroundColor Red
    exit 1
}

Write-Host "✅ JAR copied successfully" -ForegroundColor Green
Write-Host ""

Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host "Restarting Debezium Connect" -ForegroundColor Cyan
Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host ""

Set-Location $SCRIPT_DIR
& docker compose restart debezium-connect

Write-Host ""
Write-Host "⏳ Waiting for Debezium Connect to be ready..." -ForegroundColor Yellow
Start-Sleep -Seconds 15

# Wait for Debezium to be healthy (max 60 seconds)
$maxAttempts = 30
$attempt = 0
$isReady = $false

while ($attempt -lt $maxAttempts) {
    $attempt++
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:8083/" -UseBasicParsing -TimeoutSec 2 -ErrorAction SilentlyContinue
        if ($response.StatusCode -eq 200) {
            Write-Host "✅ Debezium Connect is ready!" -ForegroundColor Green
            $isReady = $true
            break
        }
    }
    catch {
        # Connection failed, continue waiting
    }
    
    if ($attempt % 5 -eq 0) {
        Write-Host "  ... still waiting (attempt $attempt/$maxAttempts)" -ForegroundColor Yellow
    }
    
    Start-Sleep -Seconds 2
}

if (-not $isReady) {
    Write-Host "❌ Timeout waiting for Debezium Connect" -ForegroundColor Red
    Write-Host "Check container status with: docker ps" -ForegroundColor Yellow
    Write-Host "Check logs with: docker logs debezium-connect" -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host "✅ Snake Case SMT deployed successfully!" -ForegroundColor Green
Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Now update your connector configuration to use it:" -ForegroundColor Yellow
Write-Host ""
Write-Host '  "transforms": "route,snakeCase",' -ForegroundColor White
Write-Host '  "transforms.snakeCase.type": "com.debezium.transforms.SnakeCaseTransform$Value"' -ForegroundColor White
Write-Host ""
Write-Host "Then redeploy the connector:" -ForegroundColor Yellow
Write-Host '  curl -X DELETE http://localhost:8083/connectors/postgres-sink-connector' -ForegroundColor White
Write-Host '  curl -X POST -H "Content-Type: application/json" `' -ForegroundColor White
Write-Host '    --data @connectors/postgres-sink.json `' -ForegroundColor White
Write-Host '    http://localhost:8083/connectors' -ForegroundColor White
Write-Host ""
