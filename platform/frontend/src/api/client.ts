import axios from 'axios';
import { tokenStore } from '../auth/token';
import type {
  Connection, ConnectionRequest, TestResult,
  Project, ProjectRequest, Job, JobTableStatus, TableInfo, ColumnInfo, ColumnMapping, ProjectHealth,
  ReconciliationRun, LoginResponse, MeResponse, UserAdmin, RoleName, AlertItem, ConstraintApplyResult,
  Schedule, ScheduleRequest, OrchestratorStatus,
} from './types';

const http = axios.create({ baseURL: '/api/v1' });

// Attach the bearer token to every request (#55).
http.interceptors.request.use((config) => {
  const t = tokenStore.get();
  if (t) config.headers.Authorization = `Bearer ${t}`;
  return config;
});

// On 401 (expired/invalid token), drop it and bounce to login — except on the login call itself.
http.interceptors.response.use(
  (r) => r,
  (error) => {
    const url: string = error?.config?.url ?? '';
    if (error?.response?.status === 401 && !url.endsWith('/auth/login')) {
      tokenStore.clear();
      if (window.location.pathname !== '/login') window.location.assign('/login');
    }
    return Promise.reject(error);
  },
);

export const authApi = {
  login: (username: string, password: string) =>
    http.post<LoginResponse>('/auth/login', { username, password }).then((r) => r.data),
  me: () => http.get<MeResponse>('/auth/me').then((r) => r.data),
};

export const connectionsApi = {
  list: () => http.get<Connection[]>('/connections').then((r) => r.data),
  create: (body: ConnectionRequest) =>
    http.post<Connection>('/connections', body).then((r) => r.data),
  update: (id: string, body: ConnectionRequest) =>
    http.put<Connection>(`/connections/${id}`, body).then((r) => r.data),
  remove: (id: string) => http.delete(`/connections/${id}`).then(() => undefined),
  test: (id: string) => http.post<TestResult>(`/connections/${id}/test`).then((r) => r.data),
  testAdhoc: (body: ConnectionRequest) =>
    http.post<TestResult>('/connections/test', body).then((r) => r.data),
};

export const usersApi = {
  list: () => http.get<UserAdmin[]>('/users').then((r) => r.data),
  create: (body: { username: string; password: string; role: RoleName }) =>
    http.post<UserAdmin>('/users', body).then((r) => r.data),
  update: (id: string, body: { role?: RoleName; enabled?: boolean; password?: string }) =>
    http.patch<UserAdmin>(`/users/${id}`, body).then((r) => r.data),
  remove: (id: string) => http.delete(`/users/${id}`).then(() => undefined),
};

export const reconciliationApi = {
  run: (projectId: string, mode: 'COUNT' | 'CHECKSUM' = 'COUNT', sampleSize = 1000) =>
    http.post<ReconciliationRun>(`/projects/${projectId}/reconciliation`, null, {
      params: { mode, sampleSize },
    }).then((r) => r.data),
  history: (projectId: string) =>
    http.get<ReconciliationRun[]>(`/projects/${projectId}/reconciliation`).then((r) => r.data),
  report: (runId: string) =>
    http.get(`/reconciliation/${runId}/report.csv`, { responseType: 'blob' }).then((r) => r.data as Blob),
};

export const alertsApi = {
  list: () => http.get<AlertItem[]>('/alerts').then((r) => r.data),
  count: () => http.get<{ firing: number }>('/alerts/count').then((r) => r.data),
  acknowledge: (id: string) => http.post<AlertItem>(`/alerts/${id}/acknowledge`).then((r) => r.data),
};

export const monitoringApi = {
  overview: () => http.get<ProjectHealth[]>('/monitoring/overview').then((r) => r.data),
  projectStatus: (projectId: string) =>
    http.get<ProjectHealth>(`/monitoring/projects/${projectId}`).then((r) => r.data),
};

export const schemaApi = {
  tables: (connectionId: string, schema?: string) =>
    http.get<TableInfo[]>(`/connections/${connectionId}/schema/tables`, {
      params: schema ? { schema } : undefined,
    }).then((r) => r.data),
  columns: (connectionId: string, schema: string, table: string) =>
    http.get<ColumnInfo[]>(`/connections/${connectionId}/schema/columns`, {
      params: { schema, table },
    }).then((r) => r.data),
  typeMapping: (connectionId: string, schema: string, table: string, projectId?: string) =>
    http.get<ColumnMapping[]>(`/connections/${connectionId}/schema/type-mapping`, {
      params: projectId ? { schema, table, projectId } : { schema, table },
    }).then((r) => r.data),
  constraintsDdl: (projectId: string) =>
    http.get<string[]>(`/projects/${projectId}/schema/constraints/ddl`).then((r) => r.data),
  applyConstraints: (projectId: string) =>
    http.post<ConstraintApplyResult>(`/projects/${projectId}/schema/constraints/apply`).then((r) => r.data),
};

export const projectsApi = {
  list: () => http.get<Project[]>('/projects').then((r) => r.data),
  create: (body: ProjectRequest) => http.post<Project>('/projects', body).then((r) => r.data),
  update: (id: string, body: ProjectRequest) =>
    http.put<Project>(`/projects/${id}`, body).then((r) => r.data),
  remove: (id: string) => http.delete(`/projects/${id}`).then(() => undefined),
};

export const jobsApi = {
  listForProject: (projectId: string) =>
    http.get<Job[]>(`/projects/${projectId}/jobs`).then((r) => r.data),
  create: (projectId: string) =>
    http.post<Job>(`/projects/${projectId}/jobs`).then((r) => r.data),
  preview: (projectId: string) =>
    http.get<{ source: unknown; sink: unknown }>(`/projects/${projectId}/connector-preview`).then((r) => r.data),
  tables: (jobId: string) =>
    http.get<JobTableStatus[]>(`/jobs/${jobId}/tables`).then((r) => r.data),
  start: (id: string) => http.post<Job>(`/jobs/${id}/start`).then((r) => r.data),
  pause: (id: string) => http.post<Job>(`/jobs/${id}/pause`).then((r) => r.data),
  resume: (id: string) => http.post<Job>(`/jobs/${id}/resume`).then((r) => r.data),
  stop: (id: string) => http.post<Job>(`/jobs/${id}/stop`).then((r) => r.data),
};

export const schedulesApi = {
  listForProject: (projectId: string) =>
    http.get<Schedule[]>(`/projects/${projectId}/schedules`).then((r) => r.data),
  create: (projectId: string, body: ScheduleRequest) =>
    http.post<Schedule>(`/projects/${projectId}/schedules`, body).then((r) => r.data),
  update: (id: string, body: ScheduleRequest) =>
    http.put<Schedule>(`/schedules/${id}`, body).then((r) => r.data),
  remove: (id: string) => http.delete(`/schedules/${id}`).then(() => undefined),
  runNow: (id: string) => http.post<Schedule>(`/schedules/${id}/run-now`).then((r) => r.data),
};

export const orchestratorApi = {
  status: () => http.get<OrchestratorStatus>('/orchestrator/status').then((r) => r.data),
};
