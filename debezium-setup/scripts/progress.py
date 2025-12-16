"""
Progress tracking module for database replication
Tracks table-by-table completion status with resume capabilities
"""
import json
import os
from datetime import datetime
from typing import Dict, List, Optional

class ProgressTracker:
    def __init__(self, progress_file: str = "replication_progress.json"):
        self.progress_file = progress_file
        self.progress_data = self._load_progress()
    
    def _load_progress(self) -> Dict:
        """Load existing progress or create new tracking structure"""
        if os.path.exists(self.progress_file):
            try:
                with open(self.progress_file, 'r') as f:
                    return json.load(f)
            except json.JSONDecodeError:
                print(f"⚠️  Warning: Could not parse {self.progress_file}, starting fresh")
                return self._new_progress()
        return self._new_progress()
    
    def _new_progress(self) -> Dict:
        """Create new progress tracking structure"""
        return {
            "session_start": datetime.now().isoformat(),
            "last_updated": datetime.now().isoformat(),
            "tables": {}
        }
    
    def _save_progress(self):
        """Save progress to disk"""
        self.progress_data["last_updated"] = datetime.now().isoformat()
        with open(self.progress_file, 'w') as f:
            json.dump(self.progress_data, f, indent=2)
    
    def mark_table_started(self, table_name: str, phase: str):
        """Mark table as started (schema or data phase)"""
        if table_name not in self.progress_data["tables"]:
            self.progress_data["tables"][table_name] = {}
        
        self.progress_data["tables"][table_name][phase] = {
            "status": "in_progress",
            "started_at": datetime.now().isoformat(),
            "rows_synced": 0,
            "error": None
        }
        self._save_progress()
    
    def mark_table_completed(self, table_name: str, phase: str, rows_synced: int = 0):
        """Mark table as successfully completed"""
        if table_name not in self.progress_data["tables"]:
            self.progress_data["tables"][table_name] = {}
        
        self.progress_data["tables"][table_name][phase] = {
            "status": "completed",
            "completed_at": datetime.now().isoformat(),
            "rows_synced": rows_synced,
            "error": None
        }
        self._save_progress()
    
    def mark_table_failed(self, table_name: str, phase: str, error: str, rows_synced: int = 0):
        """Mark table as failed with error message"""
        if table_name not in self.progress_data["tables"]:
            self.progress_data["tables"][table_name] = {}
        
        self.progress_data["tables"][table_name][phase] = {
            "status": "failed",
            "failed_at": datetime.now().isoformat(),
            "rows_synced": rows_synced,
            "error": error
        }
        self._save_progress()
    
    def is_table_completed(self, table_name: str, phase: str) -> bool:
        """Check if table phase is completed"""
        return (table_name in self.progress_data["tables"] and 
                phase in self.progress_data["tables"][table_name] and
                self.progress_data["tables"][table_name][phase]["status"] == "completed")
    
    def is_table_partial(self, table_name: str, phase: str) -> bool:
        """Check if table phase is partially completed (in_progress or failed)"""
        if table_name not in self.progress_data["tables"]:
            return False
        if phase not in self.progress_data["tables"][table_name]:
            return False
        
        status = self.progress_data["tables"][table_name][phase]["status"]
        return status in ["in_progress", "failed"]
    
    def get_last_failed_table(self, phase: str) -> Optional[str]:
        """Get the last failed or in-progress table"""
        for table_name, phases in self.progress_data["tables"].items():
            if phase in phases:
                status = phases[phase]["status"]
                if status in ["failed", "in_progress"]:
                    return table_name
        return None
    
    def get_completed_tables(self, phase: str) -> List[str]:
        """Get list of completed table names for a phase"""
        completed = []
        for table_name, phases in self.progress_data["tables"].items():
            if phase in phases and phases[phase]["status"] == "completed":
                completed.append(table_name)
        return completed
    
    def get_progress_summary(self, phase: str, total_tables: int) -> Dict:
        """Get summary of progress for reporting"""
        completed = len(self.get_completed_tables(phase))
        failed = sum(1 for t in self.progress_data["tables"].values() 
                    if phase in t and t[phase]["status"] == "failed")
        in_progress = sum(1 for t in self.progress_data["tables"].values() 
                         if phase in t and t[phase]["status"] == "in_progress")
        
        return {
            "total": total_tables,
            "completed": completed,
            "failed": failed,
            "in_progress": in_progress,
            "remaining": total_tables - completed - failed - in_progress
        }
    
    def reset_progress(self):
        """Reset all progress (start fresh)"""
        self.progress_data = self._new_progress()
        self._save_progress()
        print("✅ Progress reset - starting fresh")
    
    def display_progress(self, phase: str, total_tables: int):
        """Display current progress"""
        summary = self.get_progress_summary(phase, total_tables)
        print("\n" + "="*60)
        print("📊 PROGRESS SUMMARY")
        print("="*60)
        print(f"Total tables:     {summary['total']}")
        print(f"✅ Completed:     {summary['completed']}")
        print(f"❌ Failed:        {summary['failed']}")
        print(f"⏳ In Progress:   {summary['in_progress']}")
        print(f"📋 Remaining:     {summary['remaining']}")
        print(f"📈 Progress:      {summary['completed']}/{summary['total']} ({summary['completed']*100//summary['total'] if summary['total'] > 0 else 0}%)")
        print("="*60 + "\n")
    
    def get_table_status(self, table_name: str, phase: str) -> Optional[str]:
        """Get status of a specific table"""
        if table_name in self.progress_data["tables"] and phase in self.progress_data["tables"][table_name]:
            return self.progress_data["tables"][table_name][phase]["status"]
        return None
