export interface LoginResponse {
  token: string;
  username: string;
  role: string;
  expiresInMinutes: number;
}

export interface MeResponse {
  username: string;
  role: string;
}

export interface AlertItem {
  id: string;
  projectId: string | null;
  severity: 'INFO' | 'WARNING' | 'CRITICAL';
  type: string;
  message: string;
  status: 'FIRING' | 'RESOLVED' | 'ACKNOWLEDGED';
  createdAt: string;
  updatedAt: string;
}

export type RoleName = 'ADMIN' | 'OPERATOR' | 'VIEWER';

export interface UserAdmin {
  id: string;
  username: string;
  role: RoleName;
  enabled: boolean;
  createdAt: string;
}

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

export interface ConstraintApplyResult {
  indexes: number;
  foreignKeys: number;
  statements: string[];
  errors: string[];
}

export interface ColumnMapping {
  column: string;
  sourceType: string;
  size: number;
  nullable: boolean;
  primaryKey: boolean;
  proposedType: string;
  semantic: 'NONE' | 'UUID' | 'JSON';
  note: string | null;
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
  sampled: number | null;
  missing: number | null;
  changed: number | null;
  status: 'MATCH' | 'MISMATCH' | 'ERROR' | 'SKIPPED';
  error: string | null;
}

export interface ReconciliationRun {
  id: string;
  projectId: string;
  status: string;
  mode: string;
  totalTables: number;
  mismatched: number;
  startedAt: string;
  finishedAt: string | null;
  results: ReconciliationResult[];
}

export interface JobTableStatus {
  schemaName: string;
  tableName: string;
  phase: string;
  status: string;
  rowsSynced: number;
  error: string | null;
  updatedAt: string;
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
