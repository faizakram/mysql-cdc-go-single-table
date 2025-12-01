package main

import (
	"database/sql"
	"fmt"
	"time"

	_ "github.com/go-sql-driver/mysql"
	backoff "github.com/cenkalti/backoff/v4"
)

func OpenDB(dsn string) (*sql.DB, error) {
	// dsn must include no DB name, we'll use db-specific queries
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, err
	}
	
	// Configure connection pool for large dataset operations and parallel inserts
	// These settings prevent connection timeouts during long-running queries
	db.SetMaxOpenConns(50)                  // Increased for parallel inserters (was 25)
	db.SetMaxIdleConns(20)                  // Keep more connections ready (was 10)
	db.SetConnMaxLifetime(15 * time.Minute) // Longer lifetime for bulk operations (was 10min)
	db.SetConnMaxIdleTime(5 * time.Minute)  // Close idle connections after 5 minutes
	
	// Verify connection works
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, fmt.Errorf("database ping failed: %v", err)
	}
	
	return db, nil
}

func RetryOp(attempts int, maxWaitSec int, op func() error) error {
	b := backoff.NewExponentialBackOff()
	b.MaxElapsedTime = time.Duration(maxWaitSec) * time.Second * time.Duration(attempts)
	b.MaxInterval = time.Duration(maxWaitSec) * time.Second
	return backoff.Retry(op, backoff.WithMaxRetries(b, uint64(attempts)))
}

func MustExecRetry(db *sql.DB, attempts int, maxWait int, q string, args ...any) error {
	op := func() error {
		_, err := db.Exec(q, args...)
		return err
	}
	return RetryOp(attempts, maxWait, op)
}

func QueryRows(db *sql.DB, q string, args ...any) (*sql.Rows, error) {
	return db.Query(q, args...)
}

func QueryRowScan(db *sql.DB, q string, args ...any) (*sql.Row, error) {
	return db.QueryRow(q, args...), nil
}

func QuoteIdent(s string) string {
	return fmt.Sprintf("`%s`", s)
}
