package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"
)

// Metrics tracks CDC replication statistics
type Metrics struct {
	mu                sync.RWMutex
	StartTime         time.Time
	LastEventTime     time.Time
	EventsProcessed   int64
	InsertsProcessed  int64
	UpdatesProcessed  int64
	DeletesProcessed  int64
	ErrorCount        int64
	LastError         string
	LastCheckpoint    string
	ReplicationLagSec float64
	Status            string
}

var globalMetrics = &Metrics{
	StartTime: time.Now(),
	Status:    "initializing",
}

// UpdateMetrics updates the global metrics safely
func (m *Metrics) UpdateEventCount(eventType string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.EventsProcessed++
	m.LastEventTime = time.Now()
	switch eventType {
	case "insert":
		m.InsertsProcessed++
	case "update":
		m.UpdatesProcessed++
	case "delete":
		m.DeletesProcessed++
	}
}

func (m *Metrics) UpdateError(err string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.ErrorCount++
	m.LastError = err
}

func (m *Metrics) UpdateCheckpoint(checkpoint string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.LastCheckpoint = checkpoint
}

func (m *Metrics) UpdateStatus(status string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.Status = status
}

func (m *Metrics) UpdateReplicationLag(lag float64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.ReplicationLagSec = lag
}

func (m *Metrics) GetSnapshot() map[string]interface{} {
	m.mu.RLock()
	defer m.mu.RUnlock()
	
	uptime := time.Since(m.StartTime).Seconds()
	var eventsPerSec float64
	if uptime > 0 {
		eventsPerSec = float64(m.EventsProcessed) / uptime
	}
	
	return map[string]interface{}{
		"status":                m.Status,
		"uptime_seconds":        int64(uptime),
		"events_processed":      m.EventsProcessed,
		"inserts_processed":     m.InsertsProcessed,
		"updates_processed":     m.UpdatesProcessed,
		"deletes_processed":     m.DeletesProcessed,
		"events_per_second":     fmt.Sprintf("%.2f", eventsPerSec),
		"error_count":           m.ErrorCount,
		"last_error":            m.LastError,
		"last_checkpoint":       m.LastCheckpoint,
		"replication_lag_sec":   fmt.Sprintf("%.2f", m.ReplicationLagSec),
		"last_event_time":       m.LastEventTime.Format(time.RFC3339),
	}
}

// HealthCheck represents the health status
type HealthCheck struct {
	Status   string                 `json:"status"`
	Time     string                 `json:"time"`
	Version  string                 `json:"version"`
	Database map[string]string      `json:"database"`
	Metrics  map[string]interface{} `json:"metrics,omitempty"`
}

// StartHealthServer starts the HTTP health check server
func StartHealthServer(port int, srcDB, tgtDB *sql.DB, cfg Config) {
	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		handleHealthCheck(w, r, srcDB, tgtDB, cfg)
	})
	
	http.HandleFunc("/metrics", func(w http.ResponseWriter, r *http.Request) {
		handleMetrics(w, r)
	})
	
	http.HandleFunc("/ready", func(w http.ResponseWriter, r *http.Request) {
		handleReadiness(w, r, srcDB, tgtDB)
	})
	
	addr := fmt.Sprintf(":%d", port)
	log.Printf("Health check server listening on %s", addr)
	
	if err := http.ListenAndServe(addr, nil); err != nil {
		log.Printf("Health server error: %v", err)
	}
}

func handleHealthCheck(w http.ResponseWriter, r *http.Request, srcDB, tgtDB *sql.DB, cfg Config) {
	health := HealthCheck{
		Status:  "healthy",
		Time:    time.Now().Format(time.RFC3339),
		Version: "1.0.0",
		Database: map[string]string{
			"source": fmt.Sprintf("%s.%s", cfg.SrcDB, cfg.SrcTable),
			"target": fmt.Sprintf("%s.%s", cfg.TgtDB, cfg.TargetTable),
		},
	}
	
	// Check source database connection
	if err := srcDB.Ping(); err != nil {
		health.Status = "unhealthy"
		health.Database["source_error"] = err.Error()
		w.WriteHeader(http.StatusServiceUnavailable)
	}
	
	// Check target database connection
	if err := tgtDB.Ping(); err != nil {
		health.Status = "unhealthy"
		health.Database["target_error"] = err.Error()
		w.WriteHeader(http.StatusServiceUnavailable)
	}
	
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(health)
}

func handleMetrics(w http.ResponseWriter, r *http.Request) {
	metrics := globalMetrics.GetSnapshot()
	
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(metrics)
}

func handleReadiness(w http.ResponseWriter, r *http.Request, srcDB, tgtDB *sql.DB) {
	// Check if databases are ready
	if err := srcDB.Ping(); err != nil {
		w.WriteHeader(http.StatusServiceUnavailable)
		json.NewEncoder(w).Encode(map[string]string{"status": "not ready", "reason": "source db unavailable"})
		return
	}
	
	if err := tgtDB.Ping(); err != nil {
		w.WriteHeader(http.StatusServiceUnavailable)
		json.NewEncoder(w).Encode(map[string]string{"status": "not ready", "reason": "target db unavailable"})
		return
	}
	
	// Check if CDC is actively processing
	snapshot := globalMetrics.GetSnapshot()
	status := snapshot["status"].(string)
	
	if status == "initializing" || status == "error" {
		w.WriteHeader(http.StatusServiceUnavailable)
		json.NewEncoder(w).Encode(map[string]string{"status": "not ready", "cdc_status": status})
		return
	}
	
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "ready"})
}
