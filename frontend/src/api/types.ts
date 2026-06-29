/** Uniform paged-list envelope returned by the list endpoints (#127). */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  total: number;
}

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

export type DbType = 'SQLSERVER' | 'POSTGRESQL' | 'MYSQL' | 'ORACLE' | 'DB2';

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
  lagRecords: number | null;
  connectors: ConnectorHealth[];
}

export interface AdvisorRecommendation {
  severity: string;          // OK | SUGGESTION | WARNING
  setting: string | null;
  current: string | null;
  suggested: string | null;
  message: string;
}

export interface AdvisorReport {
  projectId: string;
  jobStatus: string;
  eventsPerSec: number;
  maxLagMs: number;
  sinkLagRecords: number | null;
  recommendations: AdvisorRecommendation[];
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
  totalRows: number | null;   // source row-count estimate for %-complete / ETA (#185)
  error: string | null;
  updatedAt: string;
  lastLagMs: number | null;   // per-table replication lag, CDC tables (#185)
  startedAt: string | null;   // when this table's sync began (per-table throughput)
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

export type ScheduleKind = 'FULL_LOAD' | 'VALIDATION';

export interface Schedule {
  id: string;
  projectId: string;
  kind: ScheduleKind;
  cron: string;
  enabled: boolean;
  lastRunAt?: string;
  lastStatus?: string;
  nextRunAt?: string;
  createdAt: string;
}

export interface ScheduleRequest {
  kind: ScheduleKind;
  cron: string;
  enabled?: boolean;
}

export interface OrchestratorTask {
  taskId: string;
  projectId: string;
  projectName: string;
  kind: ScheduleKind;
  source: 'SCHEDULED' | 'MANUAL';
  state: 'QUEUED' | 'RUNNING';
  enqueuedAt: string;
  startedAt?: string;
}

export interface OrchestratorStatus {
  maxConcurrent: number;
  running: number;
  queued: number;
  runningTasks: OrchestratorTask[];
  queuedTasks: OrchestratorTask[];
}

export interface AuditEntry {
  id: string;
  actor: string;
  action: string;
  target?: string;
  details: Record<string, unknown>;
  createdAt: string;
}

export interface AuditPage {
  content: AuditEntry[];
  page: number;
  size: number;
  total: number;
}

export interface EngineSpec {
  type: DbType;
  displayName: string;
  defaultPort: number;
  driverClass: string;
  jdbcUrlTemplate: string;
  canSource: boolean;
  canSink: boolean;
  debeziumConnector: string | null;
  cdcStyle: string;
}

export interface CdcCheck { name: string; ok: boolean; detail: string; remediation: string; }
export interface CdcReadiness { engine: DbType; cdcStyle: string; ready: boolean; checks: CdcCheck[]; }

export interface PlanTable {
  fqName: string; rowCount: number; bytes: number; level: number; hasPk: boolean; risks: string[];
}
export interface MigrationPlan {
  tables: PlanTable[]; levels: number; hasCycles: boolean;
  totalRows: number; totalBytes: number; estimatedSeconds: number; risks: string[];
}

export interface SchemaObject {
  category: string; schema: string; name: string; status: string; detail: string;
}
export interface SchemaObjectInventory {
  engine: DbType; migratable: number; reportOnly: number; objects: SchemaObject[];
}

export interface DryRunReport {
  ok: boolean;
  source?: { success: boolean; message: string; latencyMs?: number };
  target?: { success: boolean; message: string; latencyMs?: number };
  plan?: MigrationPlan;
  blockers: string[];
  warnings: string[];
}

export interface CostEstimate {
  rows: number; bytes: number; durationSeconds: number;
  computeUsd: number; storageUsdPerMonth: number; totalFirstMonthUsd: number; assumptions: string[];
}

export interface TableValidation {
  schema: string; table: string; sourceRows: number; targetRows: number;
  nullPrimaryKey: number; duplicateKeys: number; missingRows: number; extraRows: number;
  cdcInserts: number; cdcUpdates: number; cdcDeletes: number;
  status: string; issues: string[];
}
export interface ValidationReport { tables: number; passed: number; syncing: number; failed: number; results: TableValidation[]; }

// Async, job-based validation run (#150). Counters update as the run streams in.
export type ValidationRunStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
export interface ValidationRun {
  id: string;
  projectId: string;
  status: ValidationRunStatus;
  totalTables: number;
  completedTables: number;
  passed: number;
  syncing: number;
  failed: number;
  error: string | null;
  startedAt: string;
  finishedAt: string | null;
  results: TableValidation[];
}

export interface Recommendation {
  table: string; column: string; sourceType: string; recommended: string; rationale: string; confidence: string;
}

export interface PluginInfo { id: string; kind: string; version: string; detail: string; }

export interface ColumnProfile {
  column: string; type: string; nulls: number; nullPct: number; distinct: number;
  min: string | null; max: string | null; pii: string;
}
export interface TableProfile { schema: string; table: string; rows: number; columns: ColumnProfile[]; }

// Live sync monitor (#168) — real-time per-table CDC throughput + lag streamed over SSE.
export interface LiveTableThroughput {
  projectId: string | null;
  project: string | null;
  table: string;
  inserts: number; updates: number; deletes: number; reads: number; total: number;
  eventsPerSec: number;
  lastLagMs: number;
  lastEventAgoMs: number;
}
export interface LiveSnapshot {
  epochMs: number;
  totalEventsPerSec: number;
  tables: LiveTableThroughput[];
}
