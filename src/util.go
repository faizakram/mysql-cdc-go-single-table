package main

import (
	"database/sql"
	"fmt"
	"strings"
)

func DetectSingleIntPK(db *sql.DB, schema, table string) (string, bool, error) {
	q := `
SELECT k.COLUMN_NAME, c.DATA_TYPE
FROM information_schema.table_constraints t
JOIN information_schema.key_column_usage k
  ON t.constraint_name = k.constraint_name
  AND t.table_schema = k.table_schema
  AND t.table_name = k.table_name
JOIN information_schema.columns c
  ON c.table_schema = k.table_schema
  AND c.table_name = k.table_name
  AND c.column_name = k.column_name
WHERE t.constraint_type='PRIMARY KEY' AND t.table_schema = ? AND t.table_name = ?
ORDER BY k.ORDINAL_POSITION`
	rows, err := db.Query(q, schema, table)
	if err != nil {
		return "", false, err
	}
	defer rows.Close()
	var cols []struct {
		Name string
		Type string
	}
	for rows.Next() {
		var name, dtype string
		if err := rows.Scan(&name, &dtype); err != nil {
			return "", false, err
		}
		cols = append(cols, struct {
			Name string
			Type string
		}{Name: name, Type: dtype})
	}
	if len(cols) == 1 {
		t := strings.ToLower(cols[0].Type)
		if t == "int" || t == "bigint" || t == "smallint" || t == "mediumint" || t == "tinyint" {
			return cols[0].Name, true, nil
		}
	}
	return "", false, nil
}

// getPrimaryKeyColumns returns all primary key column names in order
func getPrimaryKeyColumns(db *sql.DB, schema, table string) ([]string, error) {
	q := `
SELECT k.COLUMN_NAME
FROM information_schema.table_constraints t
JOIN information_schema.key_column_usage k
  ON t.constraint_name = k.constraint_name
  AND t.table_schema = k.table_schema
  AND t.table_name = k.table_name
WHERE t.constraint_type='PRIMARY KEY' AND t.table_schema = ? AND t.table_name = ?
ORDER BY k.ORDINAL_POSITION`
	
	rows, err := db.Query(q, schema, table)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	
	var pkCols []string
	for rows.Next() {
		var col string
		if err := rows.Scan(&col); err != nil {
			return nil, err
		}
		pkCols = append(pkCols, col)
	}
	
	return pkCols, nil
}

func GetMinMax(db *sql.DB, schema, table, col string) (int64, int64, error) {
	q := fmt.Sprintf("SELECT MIN(`%s`), MAX(`%s`) FROM `%s`.`%s`", col, col, schema, table)
	row := db.QueryRow(q)
	var mn, mx sql.NullInt64
	if err := row.Scan(&mn, &mx); err != nil {
		return 0, 0, err
	}
	if !mn.Valid || !mx.Valid {
		return 0, 0, nil
	}
	return mn.Int64, mx.Int64, nil
}

func CopyTableSchema(src *sql.DB, tgt *sql.DB, srcSchema, srcTable, tgtSchema, tgtTable string) error {
	q := fmt.Sprintf("SHOW CREATE TABLE `%s`.`%s`", srcSchema, srcTable)
	row := src.QueryRow(q)
	var tbl string
	var createSQL string
	if err := row.Scan(&tbl, &createSQL); err != nil {
		return err
	}
	// replace table name - safer approach: locate first occurrence after CREATE TABLE
	createSQL = strings.Replace(createSQL, fmt.Sprintf("CREATE TABLE `%s`", srcTable), fmt.Sprintf("CREATE TABLE `%s`", tgtTable), 1)
	
	// Use the target database
	if _, err := tgt.Exec(fmt.Sprintf("USE `%s`", tgtSchema)); err != nil {
		return err
	}
	
	// drop target if exists
	if _, err := tgt.Exec(fmt.Sprintf("DROP TABLE IF EXISTS `%s`", tgtTable)); err != nil {
		return err
	}
	if _, err := tgt.Exec(createSQL); err != nil {
		return err
	}
	return nil
}

func extractHostFromDSN(dsn string) string {
	// DSN format: user:pass@tcp(host:port)/dbname
	if idx := strings.Index(dsn, "@tcp("); idx != -1 {
		rest := dsn[idx+5:]
		if end := strings.Index(rest, ":"); end != -1 {
			return rest[:end]
		}
	}
	return "127.0.0.1"
}

func extractPortFromDSN(dsn string) uint16 {
	// DSN format: user:pass@tcp(host:port)/dbname
	if idx := strings.Index(dsn, "@tcp("); idx != -1 {
		rest := dsn[idx+5:]
		if start := strings.Index(rest, ":"); start != -1 {
			if end := strings.Index(rest[start+1:], ")"); end != -1 {
				port := rest[start+1 : start+1+end]
				var p uint16
				fmt.Sscanf(port, "%d", &p)
				return p
			}
		}
	}
	return 3306
}

func extractUserFromDSN(dsn string) string {
	// DSN format: user:pass@tcp(host:port)/dbname
	if idx := strings.Index(dsn, ":"); idx != -1 {
		return dsn[:idx]
	}
	return "root"
}

func extractPassFromDSN(dsn string) string {
	// DSN format: user:pass@tcp(host:port)/dbname
	if start := strings.Index(dsn, ":"); start != -1 {
		if end := strings.Index(dsn[start+1:], "@"); end != -1 {
			return dsn[start+1 : start+1+end]
		}
	}
	return ""
}

func getSourceMasterStatus(db *sql.DB) (string, uint32, error) {
	row := db.QueryRow("SHOW MASTER STATUS")
	var file string
	var pos uint32
	var binlogDoDB, binlogIgnoreDB, executedGtidSet sql.NullString
	if err := row.Scan(&file, &pos, &binlogDoDB, &binlogIgnoreDB, &executedGtidSet); err != nil {
		return "", 0, err
	}
	return file, pos, nil
}
