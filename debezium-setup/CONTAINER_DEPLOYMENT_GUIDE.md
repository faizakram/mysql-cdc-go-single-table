# Container-Based Deployment Guide

## Overview

This guide explains how to use the **container-based deployment** approach, which eliminates the need to manually install Java, Python, Maven, and other dependencies on your host system. Everything runs inside a pre-configured Docker container.

## Benefits

✅ **Zero Manual Installation** - No need to install Java, Python, Maven on host  
✅ **Consistent Environment** - Same deployment environment everywhere  
✅ **Isolated Dependencies** - Won't conflict with host system packages  
✅ **Portable** - Works on any system with Docker  
✅ **Version Controlled** - Deployment environment is code  

---

## Prerequisites

**Only Docker is required on the host system:**
- Docker Engine 20.10+
- Docker Compose 2.0+

**That's it!** No Java, Python, Maven, or other tools needed on host.

---

## Quick Start

### 1. Create Configuration File

```bash
cd debezium-setup
cp .env.example .env
# Edit .env with your configuration
nano .env
```

### 2. Start Infrastructure

```bash
docker compose up -d
```

Wait ~30 seconds for services to be ready.

### 3. Run Container-Based Deployment

```bash
bash scripts/deploy-with-container.sh
```

This single command will:
- Build a deployment container with all dependencies
- Start the container connected to your networks
- Run the full deployment script inside the container
- Display all output in your terminal

---

## What Gets Installed in the Container

The deployment container (`Dockerfile.deployer`) includes:

**Programming Languages:**
- Python 3.10+
- OpenJDK 17

**Build Tools:**
- Maven 3.8+

**Database Clients:**
- PostgreSQL client
- MS SQL ODBC Driver 18

**Utilities:**
- curl, wget, git
- jq (JSON processor)

**Python Libraries:**
- pyodbc (MS SQL connectivity)
- psycopg2-binary (PostgreSQL connectivity)
- python-dotenv (Environment variable loading)

---

## How It Works

### Architecture

```
Host System (Only Docker Required)
└── Docker Networks
    ├── Debezium Infrastructure (Kafka, Zookeeper, Connect)
    ├── MS SQL Server
    ├── PostgreSQL
    └── Deployment Container (Java, Python, Maven)
        └── Runs deploy-all.sh
```

### Container Configuration

**File:** `Dockerfile.deployer`
- Base image: Ubuntu 22.04
- Installs all deployment dependencies
- Copies deployment scripts
- Pre-configures environment

**File:** `docker-compose.deployer.yml`
- Defines deployer service
- Mounts .env file (read-only)
- Connects to all CDC networks
- Provides interactive shell access

**File:** `scripts/deploy-with-container.sh`
- Orchestrates container-based deployment
- Builds deployer image
- Starts container
- Executes deployment
- Shows results

---

## Usage Examples

### Full Deployment (Container-Based)

```bash
bash scripts/deploy-with-container.sh
```

### Interactive Container Access

```bash
# Access the running deployer container
docker exec -it cdc-deployer bash

# Inside container, you can run:
bash scripts/deploy-all.sh          # Full deployment
bash scripts/manage-cdc.sh          # CDC management
bash scripts/generate-connectors.sh # Generate configs
python3 scripts/replicate-schema.py # Schema replication
```

### Run Specific Commands

```bash
# CDC Management
docker exec -it cdc-deployer bash scripts/manage-cdc.sh

# Generate Connector Configs
docker exec -it cdc-deployer bash scripts/generate-connectors.sh

# Check Connector Status
docker exec -it cdc-deployer curl -s http://debezium-connect:8083/connectors
```

### Rebuild Deployment Container

If you update scripts or need to rebuild:

```bash
cd debezium-setup
docker build -f Dockerfile.deployer -t cdc-deployer:latest .
docker compose -f docker-compose.deployer.yml up -d --force-recreate
```

---

## Container Management

### Start Deployer Container

```bash
docker compose -f docker-compose.deployer.yml up -d
```

### Stop Deployer Container

```bash
docker compose -f docker-compose.deployer.yml down
```

### View Deployer Logs

```bash
docker logs cdc-deployer
```

### Restart Deployer Container

```bash
docker compose -f docker-compose.deployer.yml restart
```

---

## Comparison: Host vs Container Deployment

| Aspect | Host-Based | Container-Based |
|--------|------------|-----------------|
| **Java Required** | ✅ Yes (17+) | ❌ No |
| **Python Required** | ✅ Yes (3.8+) | ❌ No |
| **Maven Required** | ✅ Yes (3.6+) | ❌ No |
| **ODBC Driver** | ✅ Yes | ❌ No |
| **Setup Time** | 15-30 min | 2-5 min |
| **Portability** | Medium | High |
| **Consistency** | Varies | Always same |
| **Conflicts** | Possible | Isolated |
| **Recommended For** | Development | Production/CI/CD |

---

## Troubleshooting

### Container Build Fails

```bash
# Check Docker is running
docker info

# Clear build cache and rebuild
docker build --no-cache -f Dockerfile.deployer -t cdc-deployer:latest .
```

### Cannot Connect to Infrastructure

```bash
# Ensure infrastructure is running
docker compose ps

# Check networks exist
docker network ls | grep debezium

# Restart infrastructure if needed
docker compose restart
```

### .env File Not Found Error

```bash
# Ensure .env exists in debezium-setup directory
ls -la .env

# If missing, create from template
cp .env.example .env
```

### Scripts Not Executable

```bash
# Make scripts executable
chmod +x scripts/*.sh
```

---

## CI/CD Integration

The container-based approach is perfect for CI/CD pipelines:

### GitHub Actions Example

```yaml
name: Deploy CDC Pipeline

on:
  push:
    branches: [main]

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Create .env
        run: |
          echo "MSSQL_HOST=${{ secrets.MSSQL_HOST }}" > .env
          echo "POSTGRES_HOST=${{ secrets.POSTGRES_HOST }}" >> .env
          # ... other env vars
      
      - name: Deploy with Container
        run: |
          cd debezium-setup
          bash scripts/deploy-with-container.sh
```

### GitLab CI Example

```yaml
deploy-cdc:
  stage: deploy
  image: docker:latest
  services:
    - docker:dind
  script:
    - cd debezium-setup
    - cp .env.example .env
    - sed -i "s/MSSQL_HOST=.*/MSSQL_HOST=$MSSQL_HOST/" .env
    - bash scripts/deploy-with-container.sh
```

---

## Security Considerations

**Read-Only .env Mount:**
The .env file is mounted as read-only to prevent accidental modifications:
```yaml
volumes:
  - ./.env:/deployment/.env:ro  # :ro = read-only
```

**Isolated Networks:**
The deployer container only connects to necessary networks.

**No Host System Modification:**
All dependencies stay inside the container.

---

## Migration from Host-Based Deployment

If you're currently using host-based deployment:

**Keep both options available:**
- Host-based: `bash scripts/deploy-all.sh`
- Container-based: `bash scripts/deploy-with-container.sh`

**No migration needed** - Both methods work with the same .env file and scripts.

**Recommendation:**
- Use container-based for production/CI/CD
- Use host-based for rapid development/debugging

---

## Next Steps

1. ✅ Create .env file from template
2. ✅ Run `bash scripts/deploy-with-container.sh`
3. ✅ Verify deployment succeeded
4. ✅ Access container for management: `docker exec -it cdc-deployer bash`

**For detailed deployment steps, see:** `FINAL_DEPLOYMENT_GUIDE.md`

**For project structure, see:** `FILE_STRUCTURE.md`
