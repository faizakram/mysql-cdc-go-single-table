package main

import (
	"strings"
	"testing"
)

func TestDecodeString_UTF32(t *testing.T) {
	// Test UTF-32 big-endian decoding
	// "Hello" in UTF-32 BE: 00 00 00 48 00 00 00 65 00 00 00 6C 00 00 00 6C 00 00 00 6F
	input := []byte{
		0x00, 0x00, 0x00, 0x48, // H
		0x00, 0x00, 0x00, 0x65, // e
		0x00, 0x00, 0x00, 0x6C, // l
		0x00, 0x00, 0x00, 0x6C, // l
		0x00, 0x00, 0x00, 0x6F, // o
	}
	
	result := decodeString(input)
	expected := "Hello"
	
	if result != expected {
		t.Errorf("UTF-32 decode failed: got %q, want %q", result, expected)
	}
}

func TestDecodeString_EmptyInput(t *testing.T) {
	result := decodeString([]byte{})
	if result != "" {
		t.Errorf("Empty input should return empty string, got %q", result)
	}
}

func TestDecodeString_NullTerminated(t *testing.T) {
	// UTF-32 with null terminator
	input := []byte{
		0x00, 0x00, 0x00, 0x41, // A
		0x00, 0x00, 0x00, 0x00, // null terminator
		0x00, 0x00, 0x00, 0x42, // B (should not appear)
	}
	
	result := decodeString(input)
	expected := "A"
	
	if result != expected {
		t.Errorf("Null-terminated UTF-32: got %q, want %q", result, expected)
	}
}

func TestDecodeString_RegularUTF8(t *testing.T) {
	// Regular UTF-8 string
	input := []byte("Regular ASCII text")
	result := decodeString(input)
	
	if result != string(input) {
		t.Errorf("Regular UTF-8: got %q, want %q", result, string(input))
	}
}

func TestMin(t *testing.T) {
	tests := []struct {
		a, b, expected int
	}{
		{5, 10, 5},
		{10, 5, 5},
		{0, 0, 0},
		{-5, -10, -10},
	}
	
	for _, tt := range tests {
		result := min(tt.a, tt.b)
		if result != tt.expected {
			t.Errorf("min(%d, %d) = %d, want %d", tt.a, tt.b, result, tt.expected)
		}
	}
}

func TestExtractHostFromDSN(t *testing.T) {
	tests := []struct {
		dsn      string
		expected string
	}{
		{"user:pass@tcp(localhost:3306)/db", "localhost"},
		{"user:pass@tcp(192.168.1.100:3306)/db", "192.168.1.100"},
		{"user:pass@tcp(db.example.com:3306)/db", "db.example.com"},
	}
	
	for _, tt := range tests {
		result := extractHostFromDSN(tt.dsn)
		if result != tt.expected {
			t.Errorf("extractHostFromDSN(%q) = %q, want %q", tt.dsn, result, tt.expected)
		}
	}
}

func TestExtractPortFromDSN(t *testing.T) {
	tests := []struct {
		dsn      string
		expected uint16
	}{
		{"user:pass@tcp(localhost:3306)/db", 3306},
		{"user:pass@tcp(192.168.1.100:3307)/db", 3307},
		{"user:pass@tcp(db.example.com:3308)/db", 3308},
	}
	
	for _, tt := range tests {
		result := extractPortFromDSN(tt.dsn)
		if result != tt.expected {
			t.Errorf("extractPortFromDSN(%q) = %d, want %d", tt.dsn, result, tt.expected)
		}
	}
}

func TestExtractUserFromDSN(t *testing.T) {
	tests := []struct {
		dsn      string
		expected string
	}{
		{"root:password@tcp(localhost:3306)/db", "root"},
		{"admin:pass@tcp(localhost:3306)/db", "admin"},
		{"user123:pass@tcp(localhost:3306)/db", "user123"},
	}
	
	for _, tt := range tests {
		result := extractUserFromDSN(tt.dsn)
		if result != tt.expected {
			t.Errorf("extractUserFromDSN(%q) = %q, want %q", tt.dsn, result, tt.expected)
		}
	}
}

func TestExtractPassFromDSN(t *testing.T) {
	tests := []struct {
		dsn      string
		expected string
	}{
		{"root:password@tcp(localhost:3306)/db", "password"},
		{"admin:pass123@tcp(localhost:3306)/db", "pass123"},
		{"user:@tcp(localhost:3306)/db", ""},
	}
	
	for _, tt := range tests {
		result := extractPassFromDSN(tt.dsn)
		if result != tt.expected {
			t.Errorf("extractPassFromDSN(%q) = %q, want %q", tt.dsn, result, tt.expected)
		}
	}
}

func TestKeyFor(t *testing.T) {
	cfg := Config{
		SrcDSN:   "user:pass@tcp(localhost:3306)/db",
		SrcDB:    "source_db",
		SrcTable: "source_table",
	}
	
	result := keyFor(cfg)
	
	// Should contain DSN, database, and table
	if !strings.Contains(result, cfg.SrcDSN) {
		t.Errorf("keyFor should contain DSN")
	}
	if !strings.Contains(result, cfg.SrcDB) {
		t.Errorf("keyFor should contain source database")
	}
	if !strings.Contains(result, cfg.SrcTable) {
		t.Errorf("keyFor should contain source table")
	}
}
