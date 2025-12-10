# Deploy SMT Scripts

This directory contains deployment scripts for the Snake Case SMT (Single Message Transform).

## Files

- **deploy-smt.sh** - Bash script for Linux/macOS
- **deploy-smt.ps1** - PowerShell script for Windows

## Usage

### Linux/macOS

```bash
cd debezium-setup
chmod +x deploy-smt.sh
./deploy-smt.sh
```

### Windows (PowerShell)

```powershell
cd debezium-setup
.\deploy-smt.ps1
```

Or if you get execution policy errors:

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy-smt.ps1
```

### Windows (Command Prompt with PowerShell)

```cmd
cd debezium-setup
powershell -ExecutionPolicy Bypass -File .\deploy-smt.ps1
```

## Prerequisites

### All Platforms
- Docker Desktop running
- Docker Compose (included with Docker Desktop)
- Debezium containers running (`docker compose up -d`)

### Linux/macOS
- Maven (`sudo apt-get install maven` or `brew install maven`)
- Bash shell

### Windows
- Maven (download from https://maven.apache.org/download.cgi or `choco install maven`)
- PowerShell 5.1+ (included with Windows 10/11)
- Docker Desktop for Windows

## What the Script Does

1. ✅ Checks if Maven is installed
2. 📦 Builds the custom Snake Case SMT JAR file
3. 📤 Copies the JAR to the Debezium Connect container
4. 🔄 Restarts Debezium Connect to load the new transform
5. ⏳ Waits for Debezium Connect to be ready
6. ✅ Verifies successful deployment

## Troubleshooting

### Windows: "Execution Policy" Error

If you see: `cannot be loaded because running scripts is disabled on this system`

**Solution 1** (Recommended - one-time bypass):
```powershell
powershell -ExecutionPolicy Bypass -File .\deploy-smt.ps1
```

**Solution 2** (Change policy for current user):
```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
.\deploy-smt.ps1
```

### Maven Not Found

**Linux/Ubuntu:**
```bash
sudo apt-get update && sudo apt-get install -y maven
```

**macOS:**
```bash
brew install maven
```

**Windows (with Chocolatey):**
```powershell
choco install maven
```

**Windows (manual):**
1. Download from https://maven.apache.org/download.cgi
2. Extract to `C:\Program Files\Apache\maven`
3. Add `C:\Program Files\Apache\maven\bin` to PATH
4. Restart terminal

### Docker Container Not Running

```bash
# Check if containers are running
docker ps

# Start containers
cd debezium-setup
docker compose up -d

# Check logs if issues
docker logs debezium-connect
```

### Timeout Waiting for Debezium Connect

```bash
# Check container logs
docker logs debezium-connect --tail 50

# Check if port 8083 is accessible
curl http://localhost:8083/

# Restart container
docker compose restart debezium-connect
```

## After Deployment

Update your connector configuration to use the Snake Case transform:

**PostgreSQL Sink Connector** (`connectors/postgres-sink.json`):
```json
{
  "transforms": "route,unwrap,renameDeleted,snakeCaseKey,snakeCaseValue",
  "transforms.snakeCaseKey.type": "com.debezium.transforms.SnakeCaseTransform$Key",
  "transforms.snakeCaseValue.type": "com.debezium.transforms.SnakeCaseTransform$Value"
}
```

Then redeploy the connector:
```bash
# Delete old connector
curl -X DELETE http://localhost:8083/connectors/postgres-sink-connector

# Deploy updated connector
curl -X POST -H "Content-Type: application/json" \
  --data @connectors/postgres-sink.json \
  http://localhost:8083/connectors
```

## Verification

Check that the SMT is loaded:
```bash
# List available transforms
curl -s http://localhost:8083/connector-plugins | jq '.[] | select(.class | contains("SnakeCase"))'

# Check connector status
curl -s http://localhost:8083/connectors/postgres-sink-connector/status | jq .
```

## Output Example

```
==========================================================================
Building Snake Case Transform SMT
==========================================================================

📦 Building JAR...
[INFO] Building snake-case-transform 1.0.0
[INFO] BUILD SUCCESS
✅ Build successful: .../target/snake-case-transform-1.0.0.jar

==========================================================================
Deploying to Debezium Connect
==========================================================================

📤 Copying JAR to Debezium Connect container...
✅ JAR copied successfully

==========================================================================
Restarting Debezium Connect
==========================================================================

⏳ Waiting for Debezium Connect to be ready...
✅ Debezium Connect is ready!

==========================================================================
✅ Snake Case SMT deployed successfully!
==========================================================================
```
