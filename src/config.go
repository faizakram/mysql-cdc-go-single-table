package main

import (
	"os"
	"strconv"
)

type Config struct {
	SrcDSN           string
	TgtDSN           string
	SrcDB            string
	TgtDB            string
	SrcTable         string
	TargetTable      string
	ParallelWorkers  int
	BatchSize        int
	DBRetryAttempts  int
	DBRetryMaxWait   int
	FullloadRetries  int
	FullloadDrop     bool
	CheckpointTable  string
	CheckpointEvery  int
	CheckpointPeriod int
	ServerID         uint32
	HealthPort       int
	Workers          int
}

func LoadConfig() Config {
	p := func(k, def string) string {
		if v := os.Getenv(k); v != "" {
			return v
		}
		return def
	}
	toInt := func(s string, def int) int {
		if v, err := strconv.Atoi(p(s, "")); err == nil {
			return v
		}
		return def
	}
	toBool := func(s string, def bool) bool {
		v := p(s, "")
		if v == "" {
			return def
		}
		if v == "1" || v == "true" || v == "yes" {
			return true
		}
		return false
	}

	cfg := Config{
		// For large datasets (20-30M rows), append connection params for optimization:
		// ?maxAllowedPacket=67108864 (64MB) - allows larger batch inserts
		// &writeTimeout=300s - prevents timeout on large writes (5 minutes)
		// &readTimeout=300s - prevents timeout on large reads (5 minutes)
		// &timeout=60s - connection timeout (1 minute)
		// &charset=utf8mb4 - ensures proper charset conversion for utf32 tables
		SrcDSN:           p("SRC_DSN", "root:rootpass@tcp(source-host:3306)/?maxAllowedPacket=67108864&readTimeout=300s&writeTimeout=300s&timeout=60s&charset=utf8mb4"),
		TgtDSN:           p("TGT_DSN", "root:rootpass@tcp(target-host:3306)/?maxAllowedPacket=67108864&writeTimeout=300s&readTimeout=300s&timeout=60s&charset=utf8mb4"),
		SrcDB:            p("SRC_DB", "offercraft"),
		TgtDB:            p("TGT_DB", "offercraft"),
		SrcTable:         p("SRC_TABLE", "channel_transactions"),
		TargetTable:      p("TARGET_TABLE", "channel_transactions_temp"),
		ParallelWorkers:  toInt("PARALLEL_WORKERS", 8),  // Increased for large datasets
		BatchSize:        toInt("BATCH_SIZE", 10000),    // Larger batches for better throughput
		DBRetryAttempts:  toInt("DB_RETRY_ATTEMPTS", 5),
		DBRetryMaxWait:   toInt("DB_RETRY_MAX_WAIT", 10),
		FullloadRetries:  toInt("FULLLOAD_MAX_RETRIES", 3),
		FullloadDrop:     toBool("FULLLOAD_DROP_ON_RETRY", true),
		CheckpointTable:  p("CHECKPOINT_TABLE", "cdc_checkpoints"),
		CheckpointEvery:  toInt("CHECKPOINT_EVERY", 100),
		CheckpointPeriod: toInt("CHECKPOINT_WRITE_SECONDS", 5),
		ServerID:         uint32(toInt("BINLOG_SERVER_ID", 9999)),
		HealthPort:       toInt("HEALTH_PORT", 8080),
		Workers:          toInt("PARALLEL_WORKERS", 8),
	}
	return cfg
}
