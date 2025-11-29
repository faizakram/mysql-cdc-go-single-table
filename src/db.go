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
	return sql.Open("mysql", dsn)
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
