package main

import (
	"testing"
)

func TestValidateConfig(t *testing.T) {
	tests := []struct {
		name      string
		cfg       Config
		shouldErr bool
	}{
		{
			name: "Valid config",
			cfg: Config{
				SrcDSN:           "user:pass@tcp(localhost:3306)/db",
				TgtDSN:           "user:pass@tcp(localhost:3307)/db",
				SrcDB:            "source",
				TgtDB:            "target",
				SrcTable:         "table1",
				TargetTable:      "table1",
				BatchSize:        1000,
				Workers:          4,
				CheckpointPeriod: 5,
			},
			shouldErr: false,
		},
		{
			name: "Missing source DSN",
			cfg: Config{
				TgtDSN:           "user:pass@tcp(localhost:3307)/db",
				SrcDB:            "source",
				TgtDB:            "target",
				SrcTable:         "table1",
				TargetTable:      "table1",
				BatchSize:        1000,
				Workers:          4,
				CheckpointPeriod: 5,
			},
			shouldErr: true,
		},
		{
			name: "Invalid batch size",
			cfg: Config{
				SrcDSN:           "user:pass@tcp(localhost:3306)/db",
				TgtDSN:           "user:pass@tcp(localhost:3307)/db",
				SrcDB:            "source",
				TgtDB:            "target",
				SrcTable:         "table1",
				TargetTable:      "table1",
				BatchSize:        0,
				Workers:          4,
				CheckpointPeriod: 5,
			},
			shouldErr: true,
		},
		{
			name: "Invalid workers",
			cfg: Config{
				SrcDSN:           "user:pass@tcp(localhost:3306)/db",
				TgtDSN:           "user:pass@tcp(localhost:3307)/db",
				SrcDB:            "source",
				TgtDB:            "target",
				SrcTable:         "table1",
				TargetTable:      "table1",
				BatchSize:        1000,
				Workers:          -1,
				CheckpointPeriod: 5,
			},
			shouldErr: true,
		},
	}
	
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := ValidateConfig(tt.cfg)
			if (err != nil) != tt.shouldErr {
				t.Errorf("ValidateConfig() error = %v, shouldErr %v", err, tt.shouldErr)
			}
		})
	}
}
