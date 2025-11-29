package main

import (
	"database/sql"
	"fmt"
	"log"
	"strings"
)

// ValidateConfig validates the configuration parameters
func ValidateConfig(cfg Config) error {
	// Check required fields
	if cfg.SrcDSN == "" {
		return fmt.Errorf("SOURCE_DSN is required")
	}
	if cfg.TgtDSN == "" {
		return fmt.Errorf("TARGET_DSN is required")
	}
	if cfg.SrcDB == "" {
		return fmt.Errorf("SOURCE_DATABASE is required")
	}
	if cfg.TgtDB == "" {
		return fmt.Errorf("TARGET_DATABASE is required")
	}
	if cfg.SrcTable == "" {
		return fmt.Errorf("SOURCE_TABLE is required")
	}
	if cfg.TargetTable == "" {
		return fmt.Errorf("TARGET_TABLE is required")
	}
	
	// Validate numeric parameters
	if cfg.BatchSize <= 0 {
		return fmt.Errorf("BATCH_SIZE must be greater than 0, got %d", cfg.BatchSize)
	}
	if cfg.Workers <= 0 {
		return fmt.Errorf("WORKERS must be greater than 0, got %d", cfg.Workers)
	}
	if cfg.CheckpointPeriod <= 0 {
		return fmt.Errorf("CHECKPOINT_PERIOD must be greater than 0, got %d", cfg.CheckpointPeriod)
	}
	
	// Validate server ID (must be unique in replication topology)
	if cfg.ServerID == 0 {
		log.Println("Warning: SERVER_ID is 0, using default 9999")
	}
	
	log.Println("✓ Configuration validation passed")
	return nil
}

// ValidateSourceDatabase validates the source database prerequisites
func ValidateSourceDatabase(srcDB *sql.DB, cfg Config) error {
	log.Println("Validating source database prerequisites...")
	
	// Check binlog format
	var binlogFormat string
	err := srcDB.QueryRow("SELECT @@binlog_format").Scan(&binlogFormat)
	if err != nil {
		return fmt.Errorf("failed to check binlog format: %v", err)
	}
	
	if strings.ToUpper(binlogFormat) != "ROW" {
		return fmt.Errorf("binlog_format must be ROW, got %s. Set binlog_format=ROW in MySQL config", binlogFormat)
	}
	log.Printf("✓ Binlog format: %s", binlogFormat)
	
	// Check if binlog is enabled
	var logBin string
	err = srcDB.QueryRow("SELECT @@log_bin").Scan(&logBin)
	if err != nil {
		return fmt.Errorf("failed to check log_bin: %v", err)
	}
	
	if logBin != "1" && strings.ToUpper(logBin) != "ON" {
		return fmt.Errorf("binary logging is not enabled. Set log_bin=ON in MySQL config")
	}
	log.Println("✓ Binary logging is enabled")
	
	// Check if source table exists
	var tableExists int
	query := fmt.Sprintf("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?")
	err = srcDB.QueryRow(query, cfg.SrcDB, cfg.SrcTable).Scan(&tableExists)
	if err != nil {
		return fmt.Errorf("failed to check source table existence: %v", err)
	}
	
	if tableExists == 0 {
		return fmt.Errorf("source table %s.%s does not exist", cfg.SrcDB, cfg.SrcTable)
	}
	log.Printf("✓ Source table %s.%s exists", cfg.SrcDB, cfg.SrcTable)
	
	// Check row count
	var rowCount int64
	countQuery := fmt.Sprintf("SELECT COUNT(*) FROM `%s`.`%s`", cfg.SrcDB, cfg.SrcTable)
	err = srcDB.QueryRow(countQuery).Scan(&rowCount)
	if err != nil {
		log.Printf("Warning: Could not get row count: %v", err)
	} else {
		log.Printf("✓ Source table has %d rows", rowCount)
	}
	
	log.Println("✓ Source database validation passed")
	return nil
}

// ValidateTargetDatabase validates the target database prerequisites
func ValidateTargetDatabase(tgtDB *sql.DB, cfg Config) error {
	log.Println("Validating target database prerequisites...")
	
	// Check if target database exists
	var dbExists int
	err := tgtDB.QueryRow("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?", cfg.TgtDB).Scan(&dbExists)
	if err != nil {
		return fmt.Errorf("failed to check target database existence: %v", err)
	}
	
	if dbExists == 0 {
		return fmt.Errorf("target database %s does not exist", cfg.TgtDB)
	}
	log.Printf("✓ Target database %s exists", cfg.TgtDB)
	
	// Check write permissions
	testQuery := fmt.Sprintf("CREATE TABLE IF NOT EXISTS `%s`.`_cdc_permission_test` (id INT)", cfg.TgtDB)
	_, err = tgtDB.Exec(testQuery)
	if err != nil {
		return fmt.Errorf("no write permission on target database: %v", err)
	}
	
	// Clean up test table
	tgtDB.Exec(fmt.Sprintf("DROP TABLE IF EXISTS `%s`.`_cdc_permission_test`", cfg.TgtDB))
	log.Println("✓ Target database has write permissions")
	
	log.Println("✓ Target database validation passed")
	return nil
}
