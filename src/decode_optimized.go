package main

import (
	"unicode/utf16"
	"unicode/utf8"
)

// fastDecodeString optimized version with fast path for ASCII/UTF-8
// Only does expensive UTF-32 decoding when necessary
func fastDecodeString(data []byte) string {
	if len(data) == 0 {
		return ""
	}
	
	// Fast path: Check if it's already valid UTF-8 (most common case)
	// This avoids expensive null-byte counting for normal UTF-8 strings
	if utf8.Valid(data) {
		// Check if it looks like UTF-8 (low null byte count)
		nullCount := 0
		checkLimit := len(data)
		if checkLimit > 100 {
			checkLimit = 100 // Only check first 100 bytes for performance
		}
		for i := 0; i < checkLimit; i++ {
			if data[i] == 0 {
				nullCount++
			}
		}
		
		// If < 10% nulls in sample, it's likely normal UTF-8
		if nullCount < checkLimit/10 {
			return string(data)
		}
	}
	
	// Slow path: Might be UTF-32, do full decode
	return decodeString(data)
}

// Optimized UTF-32 detection - only check if length is multiple of 4
func isLikelyUTF32(data []byte) bool {
	if len(data)%4 != 0 || len(data) < 16 {
		return false
	}
	
	// Quick check: count nulls in first 64 bytes
	checkLen := len(data)
	if checkLen > 64 {
		checkLen = 64
	}
	
	nullCount := 0
	for i := 0; i < checkLen; i++ {
		if data[i] == 0 {
			nullCount++
		}
	}
	
	// UTF-32 has ~75% null bytes for ASCII text
	return nullCount > checkLen/4
}

// Batch convert values with optimized decoding
func batchConvertValues(values []interface{}) []interface{} {
	result := make([]interface{}, len(values))
	
	for i, val := range values {
		if val == nil {
			result[i] = nil
			continue
		}
		
		if bytes, ok := val.([]byte); ok {
			if len(bytes) == 0 {
				result[i] = nil
				continue
			}
			// Use fast decode path
			result[i] = fastDecodeString(bytes)
		} else if str, ok := val.(string); ok {
			if str == "" {
				result[i] = nil
				continue
			}
			// Check if string contains UTF-32 encoded data
			strBytes := []byte(str)
			if isLikelyUTF32(strBytes) {
				result[i] = fastDecodeString(strBytes)
			} else {
				result[i] = str
			}
		} else {
			result[i] = val
		}
	}
	
	return result
}

// Ultra-fast UTF-16 decoder (faster than UTF-32 for most cases)
func decodeUTF16(data []byte) string {
	if len(data)%2 != 0 {
		return string(data) // Not UTF-16
	}
	
	// Convert bytes to uint16 slice
	u16 := make([]uint16, len(data)/2)
	for i := 0; i < len(u16); i++ {
		// Big-endian
		u16[i] = uint16(data[i*2])<<8 | uint16(data[i*2+1])
	}
	
	// Decode UTF-16 to UTF-8
	runes := utf16.Decode(u16)
	return string(runes)
}
