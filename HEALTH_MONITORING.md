# Health Checks and Monitoring Guide

## Overview

The MySQL CDC application now includes built-in health checks and metrics endpoints for production monitoring and observability.

## Health Check Endpoints

### 1. `/health` - Comprehensive Health Status

Returns overall application health including database connectivity.

**Endpoint:** `http://localhost:8080/health`

**Response (Healthy):**
```json
{
  "status": "healthy",
  "time": "2025-11-30T12:00:00Z",
  "version": "1.0.0",
  "database": {
    "source": "guardian.channel_txn_temp",
    "target": "guardian.channel_txn_temp"
  }
}
```

**Response (Unhealthy):**
```json
{
  "status": "unhealthy",
  "time": "2025-11-30T12:00:00Z",
  "version": "1.0.0",
  "database": {
    "source": "guardian.channel_txn_temp",
    "target": "guardian.channel_txn_temp",
    "source_error": "connection refused"
  }
}
```

**HTTP Status Codes:**
- `200 OK` - Application is healthy
- `503 Service Unavailable` - Application is unhealthy

### 2. `/metrics` - Replication Metrics

Returns detailed CDC replication statistics.

**Endpoint:** `http://localhost:8080/metrics`

**Response:**
```json
{
  "status": "cdc_running",
  "uptime_seconds": 3600,
  "events_processed": 15234,
  "inserts_processed": 8421,
  "updates_processed": 5123,
  "deletes_processed": 1690,
  "events_per_second": "4.23",
  "error_count": 0,
  "last_error": "",
  "last_checkpoint": "mysql-bin.000004:634878995",
  "replication_lag_sec": "0.15",
  "last_event_time": "2025-11-30T12:00:00Z"
}
```

**Metric Descriptions:**
- `status`: Current CDC state (`initializing`, `full_load`, `cdc_running`, `error`)
- `uptime_seconds`: Time since application started
- `events_processed`: Total events replicated
- `inserts_processed`: Number of INSERT operations
- `updates_processed`: Number of UPDATE operations
- `deletes_processed`: Number of DELETE operations
- `events_per_second`: Average replication throughput
- `error_count`: Total errors encountered
- `last_error`: Most recent error message
- `last_checkpoint`: Current binlog position
- `replication_lag_sec`: Estimated replication lag
- `last_event_time`: Timestamp of last processed event

### 3. `/ready` - Readiness Probe

Indicates if the application is ready to serve traffic (for Kubernetes readiness probes).

**Endpoint:** `http://localhost:8080/ready`

**Response (Ready):**
```json
{
  "status": "ready"
}
```

**Response (Not Ready):**
```json
{
  "status": "not ready",
  "cdc_status": "initializing"
}
```

**HTTP Status Codes:**
- `200 OK` - Application is ready
- `503 Service Unavailable` - Application is not ready

## Configuration

Set the health check port using the `HEALTH_PORT` environment variable:

```bash
export HEALTH_PORT=8080
```

Default: `8080`

## Docker Integration

The Dockerfile includes an automatic health check:

```dockerfile
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1
```

Check Docker container health:
```bash
docker ps
# Look for "(healthy)" in STATUS column

docker inspect --format='{{.State.Health.Status}}' mysql-cdc
```

## Kubernetes Integration

### Liveness Probe
```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  timeoutSeconds: 3
  failureThreshold: 3
```

### Readiness Probe
```yaml
readinessProbe:
  httpGet:
    path: /ready
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
  timeoutSeconds: 2
  failureThreshold: 2
```

## Monitoring Integration

### Prometheus

Scrape the `/metrics` endpoint and convert JSON to Prometheus format using a sidecar or exporter.

Example Prometheus config:
```yaml
scrape_configs:
  - job_name: 'mysql-cdc'
    static_configs:
      - targets: ['mysql-cdc:8080']
    metrics_path: /metrics
```

### Grafana Dashboard

Key metrics to monitor:
- Events per second (throughput)
- Replication lag
- Error rate
- Database connection health
- Uptime

### Alerting Rules

**High Replication Lag:**
```yaml
- alert: HighReplicationLag
  expr: mysql_cdc_replication_lag_sec > 60
  for: 5m
  annotations:
    summary: "CDC replication lag is high"
```

**High Error Rate:**
```yaml
- alert: HighErrorRate
  expr: rate(mysql_cdc_error_count[5m]) > 0.1
  for: 2m
  annotations:
    summary: "CDC error rate is elevated"
```

**Unhealthy Status:**
```yaml
- alert: CDCUnhealthy
  expr: mysql_cdc_health_status != 1
  for: 1m
  annotations:
    summary: "CDC application is unhealthy"
```

## Command-Line Testing

### Check Health
```bash
curl http://localhost:8080/health | jq
```

### Get Metrics
```bash
curl http://localhost:8080/metrics | jq
```

### Check Readiness
```bash
curl http://localhost:8080/ready | jq
```

### Monitor Metrics in Real-Time
```bash
watch -n 5 'curl -s http://localhost:8080/metrics | jq'
```

## Troubleshooting

### Health Check Failing

**Symptoms:** Health endpoint returns 503 or connection refused

**Solutions:**
1. Check if application is running: `docker ps`
2. Check logs: `docker logs mysql-cdc`
3. Verify HEALTH_PORT is not blocked by firewall
4. Test database connectivity manually

### High Replication Lag

**Symptoms:** `replication_lag_sec` is increasing

**Solutions:**
1. Check source database load
2. Increase PARALLEL_WORKERS
3. Increase BATCH_SIZE
4. Check network latency
5. Verify target database performance

### No Metrics Available

**Symptoms:** `/metrics` returns empty or zero values

**Solutions:**
1. Verify CDC is running (not just full load)
2. Check if events are being processed
3. Review application logs for errors
4. Confirm binlog format is ROW on source

## Best Practices

1. **Always use health checks** in production deployments
2. **Monitor replication lag** continuously
3. **Set up alerts** for critical metrics
4. **Use readiness probes** in Kubernetes to prevent traffic to unhealthy pods
5. **Track error_count** to identify data quality issues
6. **Monitor events_per_second** to detect throughput degradation
7. **Regular health endpoint checks** (every 30s recommended)
8. **Log aggregation** for error analysis

## Security Considerations

- Health endpoints return non-sensitive operational data
- No authentication required (designed for internal monitoring)
- **Do not expose** health port to public internet
- Use network policies to restrict access in Kubernetes
- Consider adding API key authentication for sensitive environments
