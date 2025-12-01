package main

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
	"unicode/utf16"
	"unicode/utf8"

	"github.com/siddontang/go-mysql/mysql"
	"github.com/siddontang/go-mysql/replication"
)

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

// decodeString decodes byte array to string, handling UTF-32 and UTF-16 encodings
func decodeString(b []byte) string {
	if len(b) == 0 {
		return ""
	}
	
	// Try UTF-32 decoding FIRST if length suggests it (4 bytes per character)
	if len(b)%4 == 0 && len(b) >= 4 {
		runes := make([]rune, 0, len(b)/4)
		validUTF32 := true
		for i := 0; i < len(b); i += 4 {
			// Big-endian UTF-32 (MySQL uses big-endian for UTF-32)
			r := rune(b[i])<<24 | rune(b[i+1])<<16 | rune(b[i+2])<<8 | rune(b[i+3])
			if r == 0 {
				break // Stop at null terminator
			}
			// Check if valid Unicode code point
			if r < 0 || r > 0x10FFFF || (r >= 0xD800 && r <= 0xDFFF) {
				validUTF32 = false
				break
			}
			runes = append(runes, r)
		}
		if validUTF32 && len(runes) > 0 {
			return string(runes)
		}
	}
	
	// Check if it's already valid UTF-8 (for short strings)
	if utf8.Valid(b) && len(b) < 64 {
		return string(b)
	}
	
	// Try UTF-16 decoding (common for Windows/SQL Server)
	if len(b)%2 == 0 && len(b) >= 2 {
		u16 := make([]uint16, len(b)/2)
		for i := range u16 {
			u16[i] = uint16(b[i*2]) | uint16(b[i*2+1])<<8
		}
		runes := utf16.Decode(u16)
		if len(runes) > 0 {
			return string(runes)
		}
	}
	
	// Fallback: return as-is
	log.Printf("DEBUG decodeString: FALLBACK path, len=%d", len(b))
	return string(b)
}

func runCDC(cfg Config, srcDB, tgtDB *sql.DB, startFile string, startPos uint32) error {
	// prepare checkpoint table
	if err := EnsureCheckpointTable(tgtDB, cfg.CheckpointTable); err != nil {
		return err
	}

	// create binlog syncer config
	syncerCfg := replication.BinlogSyncerConfig{
		ServerID: cfg.ServerID,
		Flavor:   "mysql",
		Host:     extractHostFromDSN(cfg.SrcDSN),
		Port:     extractPortFromDSN(cfg.SrcDSN),
		User:     extractUserFromDSN(cfg.SrcDSN),
		Password: extractPassFromDSN(cfg.SrcDSN),
	}

	syncer := replication.NewBinlogSyncer(syncerCfg)
	pos := mysql.Position{Name: startFile, Pos: startPos}
	streamer, err := syncer.StartSync(pos)
	if err != nil {
		return err
	}
	defer syncer.Close()

	// graceful shutdown
	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
	ticker := time.NewTicker(time.Duration(cfg.CheckpointPeriod) * time.Second)
	defer ticker.Stop()
	
	ctx := context.Background()

	for {
		select {
		case <-sigs:
			log.Println("Shutdown signal received, exiting CDC")
			// final checkpoint
			if err := WriteCheckpoint(tgtDB, cfg.CheckpointTable, keyFor(cfg), startFile, startPos); err != nil {
				log.Println("Error writing final checkpoint:", err)
			}
			return nil
		default:
			ev, err := streamer.GetEvent(ctx)
			if err != nil {
				log.Println("Error getting event:", err)
				time.Sleep(100 * time.Millisecond)
				continue
			}
			if ev == nil {
				time.Sleep(100 * time.Millisecond)
				continue
			}
			switch e := ev.Event.(type) {
			case *replication.RowsEvent:
				schema := string(e.Table.Schema)
				table := string(e.Table.Table)
				if table != cfg.SrcTable || schema != cfg.SrcDB {
					// ignore
					continue
				}
				// handle row event: e.Header.EventType
				if err := handleRowsEvent(cfg, tgtDB, e, ev.Header); err != nil {
					log.Println("Error applying row event:", err)
					// retry logic could be added here with backoff
				}
			case *replication.RotateEvent:
				// update startFile
				pos := uint32(0)
				startFile = string(e.NextLogName)
				startPos = pos
			case *replication.FormatDescriptionEvent:
				// ignore
			default:
				// ignore others
			}
			
			// Check ticker for periodic checkpoints
			select {
			case <-ticker.C:
				// periodic checkpoint write using last known file/pos from syncer
				file, pos, err := getSourceMasterStatus(srcDB)
				if err == nil {
					if err := WriteCheckpoint(tgtDB, cfg.CheckpointTable, keyFor(cfg), file, pos); err != nil {
						log.Println("checkpoint write failed:", err)
					}
				}
			default:
				// no checkpoint needed
			}
		}
	}
}

func handleRowsEvent(cfg Config, tgtDB *sql.DB, e *replication.RowsEvent, header *replication.EventHeader) error {
	if len(e.Rows) == 0 {
		return nil
	}
	
	// Get column information from the target table (including composite PKs)
	cols, pkCols, err := getTableColumns(tgtDB, cfg.TgtDB, cfg.TargetTable)
	if err != nil {
		return fmt.Errorf("failed to get table columns: %v", err)
	}
	
	// Use table's column count - truncate if row has more columns
	numCols := len(cols)
	
	// Check event type to determine operation
	eventType := header.EventType
	
	switch eventType {
	case replication.WRITE_ROWS_EVENTv1, replication.WRITE_ROWS_EVENTv2:
		// INSERT events
		for _, row := range e.Rows {
			if len(row) > numCols {
				row = row[:numCols]
			}
			if err := applyRowReplace(cfg, tgtDB, cols, row); err != nil {
				log.Println("Error applying INSERT:", err)
				globalMetrics.UpdateError(err.Error())
				return err
			}
			globalMetrics.UpdateEventCount("insert")
		}
		
	case replication.UPDATE_ROWS_EVENTv1, replication.UPDATE_ROWS_EVENTv2:
		// UPDATE events - rows come in pairs (before, after)
		for i := 0; i < len(e.Rows); i += 2 {
			if i+1 >= len(e.Rows) {
				break
			}
			before := e.Rows[i]
			after := e.Rows[i+1]
			if len(after) > numCols {
				after = after[:numCols]
			}
			if len(before) > numCols {
				before = before[:numCols]
			}
			if err := applyRowUpdate(cfg, tgtDB, cols, pkCols, before, after); err != nil {
				log.Println("Error applying UPDATE:", err)
				globalMetrics.UpdateError(err.Error())
				return err
			}
			globalMetrics.UpdateEventCount("update")
		}
		
	case replication.DELETE_ROWS_EVENTv1, replication.DELETE_ROWS_EVENTv2:
		// DELETE events
		for _, row := range e.Rows {
			if len(row) > numCols {
				row = row[:numCols]
			}
			if err := applyRowDelete(cfg, tgtDB, cols, pkCols, row); err != nil {
				log.Println("Error applying DELETE:", err)
				globalMetrics.UpdateError(err.Error())
				return err
			}
			globalMetrics.UpdateEventCount("delete")
		}
		
	default:
		log.Printf("Unknown event type: %v", eventType)
	}
	
	return nil
}

func getTableColumns(db *sql.DB, schema, table string) ([]string, []string, error) {
	query := fmt.Sprintf("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='%s' AND TABLE_NAME='%s' ORDER BY ORDINAL_POSITION", schema, table)
	rows, err := db.Query(query)
	if err != nil {
		return nil, nil, err
	}
	defer rows.Close()
	
	var cols []string
	for rows.Next() {
		var col string
		if err := rows.Scan(&col); err != nil {
			return nil, nil, err
		}
		cols = append(cols, col)
	}
	
	// Get ALL primary key columns (supports composite PKs)
	pkQuery := fmt.Sprintf(`SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE 
		WHERE TABLE_SCHEMA='%s' AND TABLE_NAME='%s' AND CONSTRAINT_NAME='PRIMARY' 
		ORDER BY ORDINAL_POSITION`, schema, table)
	pkRows, err := db.Query(pkQuery)
	if err != nil {
		return cols, []string{cols[0]}, nil // Default to first column if no PK found
	}
	defer pkRows.Close()
	
	var pkCols []string
	for pkRows.Next() {
		var pkCol string
		if err := pkRows.Scan(&pkCol); err != nil {
			return nil, nil, err
		}
		pkCols = append(pkCols, pkCol)
	}
	
	if len(pkCols) == 0 {
		pkCols = []string{cols[0]} // Default to first column if no PK found
	}
	
	return cols, pkCols, nil
}

func applyRowReplace(cfg Config, tgtDB *sql.DB, cols []string, row []interface{}) error {
	// Use REPLACE which handles both INSERT and UPDATE
	var colNames []string
	for _, col := range cols {
		colNames = append(colNames, fmt.Sprintf("`%s`", col))
	}
	
	// Convert row values, ensuring strings are properly handled
	convertedRow := make([]interface{}, len(row))
	for i, val := range row {
		if val == nil {
			convertedRow[i] = nil
		} else if bytes, ok := val.([]byte); ok {
			// Decode UTF-32/UTF-16 bytes to UTF-8 string
			decoded := decodeString(bytes)
			convertedRow[i] = decoded
		} else if str, ok := val.(string); ok {
			// Check if this string contains UTF-32 encoded data
			// UTF-32 has many null bytes (3 out of every 4 bytes for ASCII chars)
			strBytes := []byte(str)
			if len(strBytes)%4 == 0 && len(strBytes) >= 16 {
				// Count null bytes
				nullCount := 0
				for _, b := range strBytes {
					if b == 0 {
						nullCount++
					}
				}
				// If > 25% null bytes, likely UTF-32
				if nullCount > len(strBytes)/4 {
					decoded := decodeString(strBytes)
					convertedRow[i] = decoded
				} else {
					convertedRow[i] = str
				}
			} else {
				convertedRow[i] = str
			}
		} else {
			convertedRow[i] = val
		}
	}
	
	placeholders := strings.Repeat("?,", len(convertedRow))
	placeholders = placeholders[:len(placeholders)-1]
	
	query := fmt.Sprintf("REPLACE INTO `%s`.`%s` (%s) VALUES (%s)", 
		cfg.TgtDB, cfg.TargetTable, strings.Join(colNames, ","), placeholders)
	_, err := tgtDB.Exec(query, convertedRow...)
	return err
}

func applyRowUpdate(cfg Config, tgtDB *sql.DB, cols []string, pkCols []string, before, after []interface{}) error {
	// Build UPDATE statement with actual column names
	var sets []string
	var vals []interface{}
	
	// Convert values with proper charset handling - SAME LOGIC AS INSERT
	convertValue := func(val interface{}) interface{} {
		if val == nil {
			return nil
		} else if bytes, ok := val.([]byte); ok {
			if len(bytes) == 0 {
				return nil // Empty byte array -> NULL (prevents "Data too long" errors)
			}
			// Decode UTF-32/UTF-16 bytes to UTF-8 string
			decoded := decodeString(bytes)
			return decoded
		} else if str, ok := val.(string); ok {
			if str == "" {
				return nil // Empty string -> NULL (prevents "Data too long" errors)
			}
			// Check if this string contains UTF-32 encoded data
			// UTF-32 has many null bytes (3 out of every 4 bytes for ASCII chars)
			strBytes := []byte(str)
			if len(strBytes)%4 == 0 && len(strBytes) >= 16 {
				// Count null bytes
				nullCount := 0
				for _, b := range strBytes {
					if b == 0 {
						nullCount++
					}
				}
				// If > 25% null bytes, likely UTF-32
				if nullCount > len(strBytes)/4 {
					decoded := decodeString(strBytes)
					return decoded
				} else {
					return str
				}
			} else {
				return str
			}
		}
		return val
	}
	
	for i, col := range cols {
		if i < len(after) {
			sets = append(sets, fmt.Sprintf("`%s`=?", col))
			vals = append(vals, convertValue(after[i]))
		}
	}
	
	// Build WHERE clause for ALL primary key columns (supports composite PKs)
	var whereClauses []string
	for _, pkCol := range pkCols {
		pkIdx := -1
		for i, col := range cols {
			if col == pkCol {
				pkIdx = i
				break
			}
		}
		
		if pkIdx >= 0 && pkIdx < len(before) {
			whereClauses = append(whereClauses, fmt.Sprintf("`%s`=?", pkCol))
			vals = append(vals, convertValue(before[pkIdx]))
		}
	}
	
	// Fallback if no PK columns found
	if len(whereClauses) == 0 {
		whereClauses = append(whereClauses, "`"+cols[0]+"`=?")
		vals = append(vals, convertValue(before[0]))
	}
	
	query := fmt.Sprintf("UPDATE `%s`.`%s` SET %s WHERE %s", 
		cfg.TgtDB, cfg.TargetTable, strings.Join(sets, ","), strings.Join(whereClauses, " AND "))
	_, err := tgtDB.Exec(query, vals...)
	return err
}

func applyRowDelete(cfg Config, tgtDB *sql.DB, cols []string, pkCols []string, row []interface{}) error {
	// Convert values with proper charset handling - SAME LOGIC AS INSERT
	convertValue := func(val interface{}) interface{} {
		if val == nil {
			return nil
		} else if bytes, ok := val.([]byte); ok {
			if len(bytes) == 0 {
				return nil // Empty byte array -> NULL (prevents "Data too long" errors)
			}
			// Decode UTF-32/UTF-16 bytes to UTF-8 string
			decoded := decodeString(bytes)
			return decoded
		} else if str, ok := val.(string); ok {
			if str == "" {
				return nil // Empty string -> NULL (prevents "Data too long" errors)
			}
			// Check if this string contains UTF-32 encoded data
			// UTF-32 has many null bytes (3 out of every 4 bytes for ASCII chars)
			strBytes := []byte(str)
			if len(strBytes)%4 == 0 && len(strBytes) >= 16 {
				// Count null bytes
				nullCount := 0
				for _, b := range strBytes {
					if b == 0 {
						nullCount++
					}
				}
				// If > 25% null bytes, likely UTF-32
				if nullCount > len(strBytes)/4 {
					decoded := decodeString(strBytes)
					return decoded
				} else {
					return str
				}
			} else {
				return str
			}
		}
		return val
	}
	
	// Build WHERE clause for ALL primary key columns (supports composite PKs)
	var whereClauses []string
	var pkVals []interface{}
	
	for _, pkCol := range pkCols {
		pkIdx := -1
		for i, col := range cols {
			if col == pkCol {
				pkIdx = i
				break
			}
		}
		
		if pkIdx >= 0 && pkIdx < len(row) {
			whereClauses = append(whereClauses, fmt.Sprintf("`%s`=?", pkCol))
			pkVals = append(pkVals, convertValue(row[pkIdx]))
		}
	}
	
	// Fallback if no PK columns found
	if len(whereClauses) == 0 {
		whereClauses = append(whereClauses, "`"+cols[0]+"`=?")
		pkVals = append(pkVals, convertValue(row[0]))
	}
	
	query := fmt.Sprintf("DELETE FROM `%s`.`%s` WHERE %s", 
		cfg.TgtDB, cfg.TargetTable, strings.Join(whereClauses, " AND "))
	_, err := tgtDB.Exec(query, pkVals...)
	return err
}
