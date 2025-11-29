# Quick Reference

## Start Everything
```bash
./start.sh
```

## Stop Everything
```bash
./stop.sh
```

## Monitor CDC
```bash
docker logs -f mysql-cdc
```

## Test Operations

### Insert Data
```bash
docker exec mysql-source mysql -usrcuser -psrcpass offercraft \
  -e "INSERT INTO channel_transactions (transaction_id, channel_name, amount, currency, status) 
      VALUES ('TXN-100', 'api', 250.00, 'USD', 'pending');"
```

### Update Data
```bash
docker exec mysql-source mysql -usrcuser -psrcpass offercraft \
  -e "UPDATE channel_transactions SET status='completed' WHERE transaction_id='TXN-100';"
```

### Delete Data
```bash
docker exec mysql-source mysql -usrcuser -psrcpass offercraft \
  -e "DELETE FROM channel_transactions WHERE transaction_id='TXN-100';"
```

## Verify Data

### Check Source
```bash
docker exec mysql-source mysql -usrcuser -psrcpass offercraft \
  -e "SELECT * FROM channel_transactions ORDER BY id DESC LIMIT 10;"
```

### Check Target
```bash
docker exec mysql-target mysql -utgtuser -ptgtpass offercraft \
  -e "SELECT * FROM channel_transactions_temp ORDER BY id DESC LIMIT 10;"
```

### Compare Row Counts
```bash
# Source
docker exec mysql-source mysql -usrcuser -psrcpass offercraft \
  -e "SELECT COUNT(*) as source_count FROM channel_transactions;"

# Target
docker exec mysql-target mysql -utgtuser -ptgtpass offercraft \
  -e "SELECT COUNT(*) as target_count FROM channel_transactions_temp;"
```

## Debug

### View Checkpoints
```bash
docker exec mysql-target mysql -utgtuser -ptgtpass offercraft \
  -e "SELECT * FROM cdc_checkpoints;"
```

### View Binlog Status (Source)
```bash
docker exec mysql-source mysql -uroot -prootpass \
  -e "SHOW MASTER STATUS\G"
```

### Check Binlog Events
```bash
docker exec mysql-source mysql -uroot -prootpass \
  -e "SHOW BINLOG EVENTS LIMIT 10;"
```

## Restart CDC Only
```bash
docker stop mysql-cdc && docker rm mysql-cdc
./start.sh  # Will skip database setup
```

## Connect to Databases

### Source Database
```bash
docker exec -it mysql-source mysql -usrcuser -psrcpass offercraft
```

### Target Database
```bash
docker exec -it mysql-target mysql -utgtuser -ptgtpass offercraft
```

## Container Status
```bash
docker ps -a | grep -E "mysql-source|mysql-target|mysql-cdc"
```

## Network Info
```bash
docker network ls | grep mysql-cdc
docker network inspect mysql-cdc-go-single-table_default
```
