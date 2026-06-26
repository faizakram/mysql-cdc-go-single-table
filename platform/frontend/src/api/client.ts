import axios from 'axios';
import type {
  Connection, ConnectionRequest, TestResult,
  Project, ProjectRequest, Job, TableInfo, ColumnInfo, ColumnMapping, ProjectHealth,
} from './types';

const http = axios.create({ baseURL: '/api/v1' });

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
  typeMapping: (connectionId: string, schema: string, table: string) =>
    http.get<ColumnMapping[]>(`/connections/${connectionId}/schema/type-mapping`, {
      params: { schema, table },
    }).then((r) => r.data),
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
  start: (id: string) => http.post<Job>(`/jobs/${id}/start`).then((r) => r.data),
  pause: (id: string) => http.post<Job>(`/jobs/${id}/pause`).then((r) => r.data),
  resume: (id: string) => http.post<Job>(`/jobs/${id}/resume`).then((r) => r.data),
  stop: (id: string) => http.post<Job>(`/jobs/${id}/stop`).then((r) => r.data),
};
