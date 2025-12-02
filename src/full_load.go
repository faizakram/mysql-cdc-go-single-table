package main

import (
	"database/sql"
	"fmt"
	"log"
	"os"
	"strings"
	"sync"
)

// batchInsertJob contains everything needed for a batch insert
type batchInsertJob struct {
	cols      []string
	batchRows [][]interface{}
}

func runFullLoad(cfg Config, srcDB, tgtDB *sql.DB) (string, uint32, error) {
	key := fmt.Sprintf("%s.%s.%s", cfg.SrcDSN, cfg.SrcDB, cfg.SrcTable)
	
	// Use target database
	if _, err := tgtDB.Exec(fmt.Sprintf("USE `%s`", cfg.TgtDB)); err != nil {
		return "", 0, err
	}
	
	// prepare target
	log.Println("Copying schema to target table:", cfg.TargetTable)
	if err := CopyTableSchema(srcDB, tgtDB, cfg.SrcDB, cfg.SrcTable, cfg.TgtDB, cfg.TargetTable); err != nil {
		return "", 0, err
	}

	// disable FK checks on target
	if _, err := tgtDB.Exec("SET FOREIGN_KEY_CHECKS=0"); err != nil {
		return "", 0, err
	}

	// ensure progress table
	if err := EnsureProgressTable(tgtDB); err != nil {
		return "", 0, err
	}

	pkCol, ok, err := DetectSingleIntPK(srcDB, cfg.SrcDB, cfg.SrcTable)
	if err != nil {
		return "", 0, err
	}

	if ok {
		log.Println("Detected integer PK:", pkCol, " — using parallel load")
		mn, mx, err := GetMinMax(srcDB, cfg.SrcDB, cfg.SrcTable, pkCol)
		if err != nil {
			return "", 0, err
		}
		if mn == 0 && mx == 0 {
			log.Println("Empty table, performing streaming load")
			if err := streamingLoad(cfg, srcDB, tgtDB); err != nil {
				return "", 0, err
			}
		} else {
			taskRanges := buildRanges(mn, mx, cfg.ParallelWorkers)
			// skip already-done ranges
			done, err := GetDoneRanges(tgtDB, key)
			if err != nil {
				return "", 0, err
			}
			tasks := filterRanges(taskRanges, done)
			if len(tasks) == 0 {
				log.Println("All ranges already done")
			} else {
				log.Printf("Starting %d worker(s) for %d ranges\n", cfg.ParallelWorkers, len(tasks))
				rangeCh := make(chan [2]int64, len(tasks))
				var wg sync.WaitGroup
				for i := 0; i < cfg.ParallelWorkers; i++ {
					wg.Add(1)
					go func(workerId int) {
						defer wg.Done()
						for rng := range rangeCh {
							if err := loadRange(cfg, srcDB, tgtDB, pkCol, rng[0], rng[1]); err != nil {
								log.Printf("Worker %d: range %d-%d failed: %v\n", workerId, rng[0], rng[1], err)
								// on persistent failure, stop and bubble up
								// writing to progress is not done for failed range
								// caller may choose to retry full-load
								os.Exit(1)
							}
						}
					}(i)
				}
				for _, r := range tasks {
					rangeCh <- r
				}
				close(rangeCh)
				wg.Wait()
			}
		}
	} else {
		log.Println("No single integer PK; performing streaming load")
		if err := streamingLoad(cfg, srcDB, tgtDB); err != nil {
			return "", 0, err
		}
	}

	// re-enable FK checks
	if _, err := tgtDB.Exec("SET FOREIGN_KEY_CHECKS=1"); err != nil {
		return "", 0, err
	}

	// capture master status from source
	file, pos, err := captureMasterStatus(cfg, srcDB)
	if err != nil {
		return "", 0, err
	}
	// write checkpoint to target DB - use fully qualified table name
	checkpointTable := fmt.Sprintf("`%s`.`%s`", cfg.TgtDB, cfg.CheckpointTable)
	if err := EnsureCheckpointTable(tgtDB, checkpointTable); err != nil {
		return "", 0, err
	}
	if err := WriteCheckpoint(tgtDB, checkpointTable, key, file, pos); err != nil {
		return "", 0, err
	}
	log.Printf("Wrote checkpoint %s:%d\n", file, pos)
	return file, pos, nil
}

func captureMasterStatus(cfg Config, srcDB *sql.DB) (string, uint32, error) {
	row := srcDB.QueryRow("SHOW MASTER STATUS")
	var file string
	var pos uint32
	
	// Try 5 columns first (MySQL 5.7+, 8.0+)
	var binlogDoDB, binlogIgnoreDB, executedGtidSet sql.NullString
	err := row.Scan(&file, &pos, &binlogDoDB, &binlogIgnoreDB, &executedGtidSet)
	if err != nil && strings.Contains(err.Error(), "expected 4 destination arguments") {
		// Fallback to 4 columns for MySQL 5.6/older 5.7/MariaDB 10.3
		row = srcDB.QueryRow("SHOW MASTER STATUS")
		err = row.Scan(&file, &pos, &binlogDoDB, &binlogIgnoreDB)
	}
	
	if err != nil {
		return "", 0, err
	}
	return file, pos, nil
}

func buildRanges(minv, maxv int64, workers int) [][2]int64 {
	total := maxv - minv + 1
	step := total / int64(workers)
	if step < 1 {
		step = 1
	}
	var res [][2]int64
	start := minv
	for start <= maxv {
		end := start + step - 1
		if end > maxv {
			end = maxv
		}
		res = append(res, [2]int64{start, end})
		start = end + 1
	}
	return res
}

func filterRanges(all [][2]int64, done [][2]int64) [][2]int64 {
	res := make([][2]int64, 0)
	for _, r := range all {
		s, e := r[0], r[1]
		covered := false
		for _, d := range done {
			if s >= d[0] && e <= d[1] {
				covered = true
				break
			}
		}
		if !covered {
			res = append(res, r)
		}
	}
	return res
}

func loadRange(cfg Config, srcDB, tgtDB *sql.DB, pk string, start, end int64) error {
	log.Printf("Loading range %d - %d\n", start, end)
	// streaming SELECT with LIMIT is fine for safety; we fetch by pk range batches
	offset := start
	for {
		q := fmt.Sprintf("SELECT * FROM `%s`.`%s` WHERE `%s` BETWEEN ? AND ? ORDER BY `%s` LIMIT %d", cfg.SrcDB, cfg.SrcTable, pk, pk, cfg.BatchSize)
		rows, err := srcDB.Query(q, offset, end)
		if err != nil {
			return err
		}
		cols, _ := rows.Columns()
		if len(cols) == 0 {
			rows.Close()
			break
		}
		values := make([]sql.RawBytes, len(cols))
		scanArgs := make([]interface{}, len(values))
		for i := range values {
			scanArgs[i] = &values[i]
		}
		count := 0
		tx, err := tgtDB.Begin()
		if err != nil {
			rows.Close()
			return err
		}
		// Use target database
		if _, err := tx.Exec(fmt.Sprintf("USE `%s`", cfg.TgtDB)); err != nil {
			rows.Close()
			tx.Rollback()
			return err
		}
		
		// Optimization: Use extended INSERT for batch inserts (much faster for large datasets)
		// Build multi-row INSERT statement
		var batchRows [][]interface{}
		for rows.Next() {
			if err := rows.Scan(scanArgs...); err != nil {
				rows.Close()
				tx.Rollback()
				return err
			}
			args := make([]interface{}, len(values))
			for i, v := range values {
				if v == nil {
					args[i] = nil  // Preserve NULL values
				} else {
					args[i] = string(v)
				}
			}
			batchRows = append(batchRows, args)
			count++
		}
		rows.Close()
		
		if count > 0 {
			// Execute batch insert with extended INSERT syntax
			if err := executeBatchInsert(tx, cfg, cols, batchRows); err != nil {
				tx.Rollback()
				return err
			}
		}
		
		if err := tx.Commit(); err != nil {
			return err
		}
		if count == 0 {
			break
		}
		// compute next offset: read last pk from target for this range
		var last int64
		err = tgtDB.QueryRow(fmt.Sprintf("SELECT MAX(`%s`) FROM `%s`.`%s` WHERE `%s` BETWEEN ? AND ?", pk, cfg.TgtDB, cfg.TargetTable, pk), start, end).Scan(&last)
		if err != nil {
			return err
		}
		if last >= end {
			break
		}
		offset = last + 1
	}
	// mark done
	if err := MarkRangeDone(tgtDB, keyFor(cfg), start, end); err != nil {
		return err
	}
	return nil
}

func keyFor(cfg Config) string {
	return fmt.Sprintf("%s.%s.%s", cfg.SrcDSN, cfg.SrcDB, cfg.SrcTable)
}

// executeBatchInsert performs optimized batch insert using extended INSERT syntax
// This is much faster than individual inserts for large datasets (20-30M rows)
func executeBatchInsert(tx *sql.Tx, cfg Config, cols []string, batchRows [][]interface{}) error {
	if len(batchRows) == 0 {
		return nil
	}
	
	// Build column list
	var colNames []string
	for _, col := range cols {
		colNames = append(colNames, fmt.Sprintf("`%s`", col))
	}
	colList := "("
	for i, name := range colNames {
		if i > 0 {
			colList += ", "
		}
		colList += name
	}
	colList += ")"
	
	// Split into chunks of 1000 rows to avoid max_allowed_packet limit
	chunkSize := 1000
	for i := 0; i < len(batchRows); i += chunkSize {
		end := i + chunkSize
		if end > len(batchRows) {
			end = len(batchRows)
		}
		chunk := batchRows[i:end]
		
		// Build extended INSERT with multiple value sets
		placeholders := "("
		for j := 0; j < len(cols); j++ {
			if j > 0 {
				placeholders += ","
			}
			placeholders += "?"
		}
		placeholders += ")"
		
		var valueSets string
		var allArgs []interface{}
		for idx, row := range chunk {
			if idx > 0 {
				valueSets += ","
			}
			valueSets += placeholders
			allArgs = append(allArgs, row...)
		}
		
		query := fmt.Sprintf("INSERT INTO `%s`.`%s` %s VALUES %s", cfg.TgtDB, cfg.TargetTable, colList, valueSets)
		if _, err := tx.Exec(query, allArgs...); err != nil {
			return err
		}
	}
	
	return nil
}

func indexOfCol(cols []string, col string) int {
	for i, c := range cols {
		if c == col {
			return i
		}
	}
	return -1
}

func buildInsertStatement(cfg Config, cols []string) string {
	var qCols []string
	var qPlace []string
	for _, c := range cols {
		qCols = append(qCols, QuoteIdent(c))
		qPlace = append(qPlace, "?")
	}
	return fmt.Sprintf("INSERT INTO %s.%s (%s) VALUES (%s)", QuoteIdent(cfg.TgtDB), QuoteIdent(cfg.TargetTable), join(qCols, ","), join(qPlace, ","))
}

func join(a []string, sep string) string {
	res := ""
	for i, s := range a {
		if i > 0 {
			res += sep
		}
		res += s
	}
	return res
}

func streamingLoad(cfg Config, srcDB, tgtDB *sql.DB) error {
	// Use target database
	if _, err := tgtDB.Exec(fmt.Sprintf("USE `%s`", cfg.TgtDB)); err != nil {
		return err
	}
	
	// Optimize target database for bulk inserts (disable safety features temporarily)
	log.Println("Optimizing target database for bulk insert performance...")
	optimizations := []string{
		"SET SESSION sql_log_bin = 0",         // Disable binary logging for this session
		"SET SESSION unique_checks = 0",       // Disable unique key checks
		"SET SESSION foreign_key_checks = 0",  // Disable foreign key checks
		"SET SESSION autocommit = 0",          // Manual transaction control
	}
	
	for _, opt := range optimizations {
		if _, err := tgtDB.Exec(opt); err != nil {
			log.Printf("Warning: optimization failed (%s): %v", opt, err)
			// Continue anyway - these are performance hints, not critical
		}
	}
	
	// Get primary key columns for ordering (ensures consistent, resumable load)
	pkCols, err := getPrimaryKeyColumns(srcDB, cfg.SrcDB, cfg.SrcTable)
	if err != nil || len(pkCols) == 0 {
		return fmt.Errorf("cannot perform streaming load without primary key: %v", err)
	}
	
	// Build ORDER BY clause for deterministic ordering
	var orderBy string
	for i, pk := range pkCols {
		if i > 0 {
			orderBy += ", "
		}
		orderBy += fmt.Sprintf("`%s`", pk)
	}
	
	// Streaming load with batching and progress logging
	// Use larger batch size for better performance (same as parallel load)
	batchSize := cfg.BatchSize
	if batchSize < 1000 {
		batchSize = 1000
	}
	
	log.Printf("Starting cursor-based streaming load (optimized for large tables) with batch size: %d\n", batchSize)
	
	totalCount := 0
	var lastPKValues []interface{} // Cursor position - last primary key values seen
	var cols []string               // Column names - captured from first query
	
	// Use multiple goroutines to parallelize INSERT operations
	// While one batch is being inserted, the next batch is being fetched
	const numInserters = 4
	batchChan := make(chan batchInsertJob, numInserters*2) // Buffer for pipelining
	errorChan := make(chan error, numInserters)
	var insertWg sync.WaitGroup
	
	for {
		// Verify database connection is alive before querying
		// This prevents "invalid connection" errors on stale connections
		if err := srcDB.Ping(); err != nil {
			log.Printf("Warning: source database connection lost, reconnecting...")
			return fmt.Errorf("connection lost at row %d: %v", totalCount, err)
		}
		
		// Build WHERE clause for cursor-based pagination (much faster than OFFSET)
		// This uses the primary key as a cursor to fetch the next batch
		var query string
		if lastPKValues == nil {
			// First batch - no WHERE clause needed
			query = fmt.Sprintf("SELECT * FROM `%s`.`%s` ORDER BY %s LIMIT %d", 
				cfg.SrcDB, cfg.SrcTable, orderBy, batchSize)
		} else {
			// Subsequent batches - use WHERE clause with last PK values as cursor
			// For composite PKs: WHERE (pk1, pk2, ...) > (last_pk1, last_pk2, ...)
			whereCols := "("
			for i, pk := range pkCols {
				if i > 0 {
					whereCols += ", "
				}
				whereCols += fmt.Sprintf("`%s`", pk)
			}
			whereCols += ")"
			
			wherePlaceholders := "("
			for i := range pkCols {
				if i > 0 {
					wherePlaceholders += ", "
				}
				wherePlaceholders += "?"
			}
			wherePlaceholders += ")"
			
			query = fmt.Sprintf("SELECT * FROM `%s`.`%s` WHERE %s > %s ORDER BY %s LIMIT %d", 
				cfg.SrcDB, cfg.SrcTable, whereCols, wherePlaceholders, orderBy, batchSize)
		}
		
		var rows *sql.Rows
		var err error
		
		if lastPKValues == nil {
			rows, err = srcDB.Query(query)
		} else {
			rows, err = srcDB.Query(query, lastPKValues...)
		}
		
		if err != nil {
			// Check if it's a connection/timeout error and provide better error message
			errStr := err.Error()
			if errStr == "invalid connection" || 
			   strings.Contains(errStr, "i/o timeout") || 
			   strings.Contains(errStr, "timeout") {
				return fmt.Errorf("query failed at row %d: connection timeout (query took too long, BATCH_SIZE=%d may be too large for your database)", totalCount, cfg.BatchSize)
			}
			return fmt.Errorf("query failed at row %d: %v", totalCount, err)
		}

		colsFromQuery, err := rows.Columns()
		if err != nil {
			rows.Close()
			return err
		}
		
		// First iteration: capture column names and start inserter goroutines
		if cols == nil {
			cols = colsFromQuery
			
			// Start inserter goroutines now that we have column info
			for i := 0; i < numInserters; i++ {
				insertWg.Add(1)
				go func(workerID int) {
					defer insertWg.Done()
					for job := range batchChan {
						if err := insertBatchJob(tgtDB, cfg, job); err != nil {
							errorChan <- fmt.Errorf("worker %d: %v", workerID, err)
							return
						}
					}
				}(i)
			}
		}

		// Find indices of primary key columns for cursor tracking
		pkIndices := make([]int, len(pkCols))
		for i, pkCol := range pkCols {
			found := false
			for j, col := range cols {
				if col == pkCol {
					pkIndices[i] = j
					found = true
					break
				}
			}
			if !found {
				rows.Close()
				return fmt.Errorf("primary key column %s not found in result set", pkCol)
			}
		}

		// Collect batch rows
		var batchRows [][]interface{}
		values := make([]sql.RawBytes, len(cols))
		scanArgs := make([]interface{}, len(cols))
		for i := range values {
			scanArgs[i] = &values[i]
		}
		
		batchCount := 0
		for rows.Next() {
			if err := rows.Scan(scanArgs...); err != nil {
				rows.Close()
				return fmt.Errorf("scan failed at row %d: %v", totalCount, err)
			}

			args := make([]interface{}, len(values))
			for i, v := range values {
				if v == nil {
					args[i] = nil  // Preserve NULL values
				} else {
					args[i] = string(v)
				}
			}
			
			// Update cursor to last row's PK values
			lastPKValues = make([]interface{}, len(pkCols))
			for i, pkIdx := range pkIndices {
				lastPKValues[i] = args[pkIdx]
			}
			
			batchRows = append(batchRows, args)
			batchCount++
		}
		rows.Close()

		if batchCount == 0 {
			break
		}

		// Send batch to inserter goroutines for parallel processing
		// Make a copy to avoid data races
		batchCopy := make([][]interface{}, len(batchRows))
		copy(batchCopy, batchRows)
		
		job := batchInsertJob{
			cols:      cols,
			batchRows: batchCopy,
		}
		
		select {
		case batchChan <- job:
			// Batch queued successfully
		case err := <-errorChan:
			close(batchChan)
			insertWg.Wait()
			return fmt.Errorf("insert error at row %d: %v", totalCount, err)
		}

		totalCount += batchCount
		
		// Progress logging every 50K rows (increased for less overhead)
		if totalCount%50000 == 0 || batchCount < batchSize {
			log.Printf("Streaming load progress: %d rows loaded\n", totalCount)
		}

		// If we got fewer rows than batch size, we're done
		if batchCount < batchSize {
			break
		}
	}
	
	// Close channel and wait for all inserters to finish
	close(batchChan)
	insertWg.Wait()
	
	// Check for any errors that occurred during insertion
	select {
	case err := <-errorChan:
		return fmt.Errorf("final insert error: %v", err)
	default:
		// No errors
	}
	
	// Ensure we're still using the target database before restoring settings
	if _, err := tgtDB.Exec(fmt.Sprintf("USE `%s`", cfg.TgtDB)); err != nil {
		log.Printf("Warning: failed to select database before restore: %v", err)
	}
	
	// Restore target database settings
	restoreSettings := []string{
		"SET SESSION sql_log_bin = 1",
		"SET SESSION unique_checks = 1",
		"SET SESSION foreign_key_checks = 1",
		"SET SESSION autocommit = 1",
	}
	
	for _, cmd := range restoreSettings {
		if _, err := tgtDB.Exec(cmd); err != nil {
			log.Printf("Warning: failed to restore setting (%s): %v", cmd, err)
		}
	}

	log.Printf("Streaming load completed: %d rows\n", totalCount)
	return nil
}

// insertBatchJob handles the actual database insert with proper error handling
func insertBatchJob(tgtDB *sql.DB, cfg Config, job batchInsertJob) error {
	tx, err := tgtDB.Begin()
	if err != nil {
		return fmt.Errorf("begin transaction failed: %v", err)
	}

	if err := executeBatchInsert(tx, cfg, job.cols, job.batchRows); err != nil {
		tx.Rollback()
		return fmt.Errorf("batch insert failed: %v", err)
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit failed: %v", err)
	}
	
	return nil
}
