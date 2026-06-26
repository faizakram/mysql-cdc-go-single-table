export type DbType = 'SQLSERVER' | 'POSTGRESQL';

export type ProjectStatus = 'DRAFT' | 'READY' | 'ACTIVE' | 'ARCHIVED';

export interface Connection {
  id: string;
  name: string;
  dbType: DbType;
  host: string;
  port: number;
  databaseName: string;
  username: string;
  options: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface ConnectionRequest {
  name: string;
  dbType: DbType;
  host: string;
  port: number;
  databaseName: string;
  username: string;
  password: string;
  options?: Record<string, unknown>;
}

export interface TestResult {
  success: boolean;
  message: string;
  latencyMs: number | null;
}

export interface Project {
  id: string;
  name: string;
  description?: string;
  status: ProjectStatus;
  sourceConnectionId?: string;
  targetConnectionId?: string;
  config: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectRequest {
  name: string;
  description?: string;
  sourceConnectionId?: string;
  targetConnectionId?: string;
  config?: Record<string, unknown>;
}

export interface TableInfo {
  schemaName: string;
  tableName: string;
  hasPrimaryKey: boolean;
  cdcEnabled: boolean;
}

export interface ColumnInfo {
  name: string;
  dataType: string;
  size: number;
  nullable: boolean;
  primaryKey: boolean;
}

export interface ColumnMapping {
  column: string;
  sourceType: string;
  size: number;
  nullable: boolean;
  primaryKey: boolean;
  proposedType: string;
  semantic: 'NONE' | 'UUID' | 'JSON';
}

export type JobStatus =
  | 'CREATED' | 'SNAPSHOT' | 'RUNNING' | 'PAUSED' | 'STOPPED' | 'FAILED' | 'COMPLETED';

export interface TaskHealth {
  id: number;
  state: string;
  workerId: string | null;
  trace: string | null;
}

export interface ConnectorHealth {
  name: string;
  role: string;
  state: string;
  workerId: string | null;
  tasks: TaskHealth[];
}

export interface ProjectHealth {
  projectId: string;
  projectName: string;
  jobId: string | null;
  jobStatus: string;
  healthy: boolean;
  connectors: ConnectorHealth[];
}

export interface ReconciliationResult {
  schemaName: string;
  tableName: string;
  sourceCount: number | null;
  targetCount: number | null;
  difference: number | null;
  status: 'MATCH' | 'MISMATCH' | 'ERROR';
  error: string | null;
}

export interface ReconciliationRun {
  id: string;
  projectId: string;
  status: string;
  totalTables: number;
  mismatched: number;
  startedAt: string;
  finishedAt: string | null;
  results: ReconciliationResult[];
}

export interface Job {
  id: string;
  projectId: string;
  status: JobStatus;
  phase?: string;
  sourceConnectorName?: string;
  sinkConnectorName?: string;
  error?: string;
  startedAt?: string;
  finishedAt?: string;
  createdAt: string;
  updatedAt: string;
}
