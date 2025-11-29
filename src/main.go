package main

import (
	"fmt"
	"log"
	"time"
)

func main() {
	cfg := LoadConfig()
	log.Printf("Starting mysql-cdc-go single-table for %s.%s -> %s.%s\n", cfg.SrcDB, cfg.SrcTable, cfg.TgtDB, cfg.TargetTable)

	srcDB, err := OpenDB(cfg.SrcDSN)
	if err != nil {
		log.Fatalln("open src db:", err)
	}
	tgtDB, err := OpenDB(cfg.TgtDSN)
	if err != nil {
		log.Fatalln("open tgt db:", err)
	}
	defer srcDB.Close()
	defer tgtDB.Close()

	// Check if we can resume from checkpoint (skip full load)
	var file string
	var pos uint32
	
	checkpointTable := fmt.Sprintf("`%s`.`%s`", cfg.TgtDB, cfg.CheckpointTable)
	if err = EnsureCheckpointTable(tgtDB, checkpointTable); err != nil {
		log.Fatalln("ensure checkpoint:", err)
	}
	
	file, pos, err = ReadCheckpoint(tgtDB, checkpointTable, keyFor(cfg))
	if err == nil && file != "" && pos > 0 {
		// Check if target table exists and has data
		var count int64
		checkQuery := fmt.Sprintf("SELECT COUNT(*) FROM `%s`.`%s` LIMIT 1", cfg.TgtDB, cfg.TargetTable)
		err = tgtDB.QueryRow(checkQuery).Scan(&count)
		if err == nil && count > 0 {
			log.Printf("Found existing checkpoint %s:%d with %d rows in target table\n", file, pos, count)
			log.Println("Skipping full load, will resume CDC from checkpoint")
			goto StartCDC
		} else {
			log.Println("Checkpoint exists but target table is empty, performing full load")
			file = ""
			pos = 0
		}
	} else {
		log.Println("No checkpoint found, performing full load")
	}

	// full-load with retries
	for attempt := 1; attempt <= cfg.FullloadRetries; attempt++ {
		log.Printf("Full-load attempt %d/%d\n", attempt, cfg.FullloadRetries)
		if attempt > 1 && cfg.FullloadDrop {
			// drop target table and progress
			log.Println("Dropping target table and progress tables before retry")
			tgtDB.Exec(fmt.Sprintf("DROP TABLE IF EXISTS `%s`.`%s`", cfg.TgtDB, cfg.TargetTable))
			tgtDB.Exec("DROP TABLE IF EXISTS full_load_progress")
		}
		file, pos, err = runFullLoad(cfg, srcDB, tgtDB)
		if err == nil {
			break
		}
		log.Println("Full-load attempt failed:", err)
		if attempt == cfg.FullloadRetries {
			log.Fatalln("Full-load failed after retries")
		}
		// exponential backoff
		sleep := 1 << attempt
		log.Printf("Sleeping %d seconds before next attempt\n", sleep)
		// small guard
		if sleep > 60 {
			sleep = 60
		}
		time.Sleep(time.Duration(sleep) * time.Second)
	}

StartCDC:
	// Use target database for CDC
	if _, err := tgtDB.Exec(fmt.Sprintf("USE `%s`", cfg.TgtDB)); err != nil {
		log.Fatalln("use target db for cdc:", err)
	}
	
	// Start CDC
	if file == "" || pos == 0 {
		log.Fatalln("No binlog position available after full load")
	}
	log.Printf("Starting CDC from %s:%d\n", file, pos)

	if err := runCDC(cfg, srcDB, tgtDB, file, pos); err != nil {
		log.Fatalln("cdc failed:", err)
	}
}
