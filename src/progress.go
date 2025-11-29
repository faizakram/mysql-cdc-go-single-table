package main

import (
	"database/sql"
	"fmt"
)

func EnsureCheckpointTable(db *sql.DB, table string) error {
	q := fmt.Sprintf(`
CREATE TABLE IF NOT EXISTS %s (
  id VARCHAR(255) PRIMARY KEY,
  binlog_file VARCHAR(255),
  binlog_pos BIGINT,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
)`, table)
	_, err := db.Exec(q)
	return err
}

func WriteCheckpoint(db *sql.DB, table, key, file string, pos uint32) error {
	q := fmt.Sprintf(`INSERT INTO %s (id, binlog_file, binlog_pos) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE binlog_file=VALUES(binlog_file), binlog_pos=VALUES(binlog_pos)`, table)
	_, err := db.Exec(q, key, file, pos)
	return err
}

func ReadCheckpoint(db *sql.DB, table, key string) (string, uint32, error) {
	q := fmt.Sprintf(`SELECT binlog_file, binlog_pos FROM %s WHERE id = ?`, table)
	row := db.QueryRow(q, key)
	var file string
	var pos uint32
	err := row.Scan(&file, &pos)
	if err != nil {
		return "", 0, err
	}
	return file, pos, nil
}

// full_load_progress to track range completion
func EnsureProgressTable(db *sql.DB) error {
	q := `
CREATE TABLE IF NOT EXISTS full_load_progress (
  table_key VARCHAR(255),
  range_start BIGINT,
  range_end BIGINT,
  status VARCHAR(32),
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (table_key, range_start)
)`
	_, err := db.Exec(q)
	return err
}

func MarkRangeDone(db *sql.DB, key string, start, end int64) error {
	q := `INSERT INTO full_load_progress (table_key, range_start, range_end, status) VALUES (?, ?, ?, 'done') ON DUPLICATE KEY UPDATE range_end = VALUES(range_end), status='done'`
	_, err := db.Exec(q, key, start, end)
	return err
}

func GetDoneRanges(db *sql.DB, key string) ([][2]int64, error) {
	q := `SELECT range_start, range_end FROM full_load_progress WHERE table_key = ? AND status='done' ORDER BY range_start`
	rows, err := db.Query(q, key)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var res [][2]int64
	for rows.Next() {
		var s, e int64
		if err := rows.Scan(&s, &e); err != nil {
			return nil, err
		}
		res = append(res, [2]int64{s, e})
	}
	return res, nil
}
