import { Card, Col, Row, Alert, Table, Tag, Tooltip, Badge, Space, Button, App } from 'antd';
import {
  PauseOutlined, StepForwardOutlined, StopOutlined, ProjectOutlined, DatabaseOutlined,
  ThunderboltOutlined, WarningOutlined, InboxOutlined,
} from '@ant-design/icons';
import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { projectsApi, connectionsApi, monitoringApi, jobsApi, orchestratorApi } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import StatCard from '../components/StatCard';
import EmptyState from '../components/EmptyState';
import Sparkline from '../components/Sparkline';
import { useSeries } from '../hooks/useSeries';
import type { ConnectorHealth, ProjectHealth, OrchestratorTask } from '../api/types';

const STATE_COLOR: Record<string, string> = {
  RUNNING: 'green', PAUSED: 'orange', FAILED: 'red',
  UNASSIGNED: 'gold', NOT_FOUND: 'default', UNKNOWN: 'default',
};

function ConnectorTag({ c }: { c: ConnectorHealth }) {
  const failed = c.tasks.find((t) => t.state === 'FAILED' && t.trace);
  const label = `${c.role}: ${c.state} (${c.tasks.filter((t) => t.state === 'RUNNING').length}/${c.tasks.length} tasks)`;
  const tag = <Tag color={STATE_COLOR[c.state] ?? 'default'}>{label}</Tag>;
  return failed ? <Tooltip title={failed.trace}>{tag}</Tooltip> : tag;
}

export default function Dashboard() {
  const { message } = App.useApp();
  const { user } = useAuth();
  const canControl = user?.role !== 'VIEWER';
  const qc = useQueryClient();
  const projects = useQuery({ queryKey: ['projects'], queryFn: projectsApi.list });
  const connections = useQuery({ queryKey: ['connections'], queryFn: connectionsApi.list });

  const control = useMutation({
    mutationFn: ({ fn, id }: { fn: (id: string) => Promise<unknown>; id: string }) => fn(id),
    onSuccess: () => { message.success('Done'); qc.invalidateQueries({ queryKey: ['monitoring-overview'] }); },
    onError: (e: any) => message.error(e?.response?.data?.message ?? 'Action failed'),
  });
  const overview = useQuery({
    queryKey: ['monitoring-overview'],
    queryFn: monitoringApi.overview,
    refetchInterval: 4000,
  });
  const queue = useQuery({
    queryKey: ['orchestrator-status'],
    queryFn: orchestratorApi.status,
    refetchInterval: 3000,
  });
  const queueTasks: OrchestratorTask[] = [
    ...(queue.data?.runningTasks ?? []),
    ...(queue.data?.queuedTasks ?? []),
  ];

  const activeCount = overview.data?.length ?? 0;
  const unhealthyCount = overview.data?.filter((p) => !p.healthy).length ?? 0;
  const activeSeries = useSeries(overview.data ? activeCount : undefined);
  const unhealthySeries = useSeries(overview.data ? unhealthyCount : undefined);

  // Per-project sink-lag history, accumulated client-side from the live overview polls.
  const lagHist = useRef<Record<string, number[]>>({});
  const [, bump] = useState(0);
  useEffect(() => {
    if (!overview.data) return;
    const h = lagHist.current;
    for (const p of overview.data) {
      if (p.lagRecords == null) continue;
      h[p.projectId] = [...(h[p.projectId] ?? []), p.lagRecords].slice(-24);
    }
    bump((n) => n + 1);
  }, [overview.data]);

  return (
    <>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={12} sm={12} xl={6}>
          <StatCard title="Projects" value={projects.data?.length ?? 0} loading={projects.isLoading}
            icon={<ProjectOutlined />} stripe="#4F46E5" />
        </Col>
        <Col xs={12} sm={12} xl={6}>
          <StatCard title="Connections" value={connections.data?.length ?? 0} loading={connections.isLoading}
            icon={<DatabaseOutlined />} stripe="#0EA5E9" />
        </Col>
        <Col xs={12} sm={12} xl={6}>
          <StatCard title="Active migrations" value={activeCount} loading={overview.isLoading}
            icon={<ThunderboltOutlined />} stripe="#16A34A" series={activeSeries} />
        </Col>
        <Col xs={12} sm={12} xl={6}>
          <StatCard title="Unhealthy" value={unhealthyCount} loading={overview.isLoading}
            icon={<WarningOutlined />} stripe={unhealthyCount > 0 ? '#DC2626' : '#94A3B8'}
            series={unhealthySeries}
            valueStyle={{ color: unhealthyCount > 0 ? '#DC2626' : undefined }} />
        </Col>
      </Row>

      <Card title="Live migration status" size="small">
        {overview.data && overview.data.length === 0 ? (
          <EmptyState
            icon={<InboxOutlined />}
            title="No active migrations"
            description="Once you start a run from a project, its connectors, lag and health will stream here live."
          />
        ) : (
          <Table<ProjectHealth>
            rowKey="projectId"
            loading={overview.isLoading}
            dataSource={overview.data}
            pagination={false}
            scroll={{ x: 'max-content' }}
            columns={[
              { title: 'Project', dataIndex: 'projectName' },
              {
                title: 'Health',
                render: (_, p) => (
                  <Badge status={p.healthy ? 'success' : 'error'} text={p.healthy ? 'Healthy' : 'Attention'} />
                ),
              },
              { title: 'Job', dataIndex: 'jobStatus', render: (s: string) => <Tag>{s}</Tag> },
              {
                title: 'Lag (records)', dataIndex: 'lagRecords',
                render: (v: number | null) => (v == null ? '—'
                  : <span style={{ color: v > 0 ? '#D97706' : '#16A34A' }}>{v}</span>),
              },
              {
                title: 'Lag trend',
                render: (_, p) => {
                  const s = lagHist.current[p.projectId] ?? [];
                  return s.length > 1
                    ? <Sparkline data={s} color={s[s.length - 1] > 0 ? '#D97706' : '#16A34A'} />
                    : <span style={{ color: '#94A3B8' }}>—</span>;
                },
              },
              {
                title: 'Connectors',
                render: (_, p) => (
                  <Space wrap>
                    {p.connectors.length === 0
                      ? '—'
                      : p.connectors.map((c) => <ConnectorTag key={c.name} c={c} />)}
                  </Space>
                ),
              },
              {
                title: 'Controls',
                render: (_, p: ProjectHealth) => (
                  <Space>
                    <Tooltip title="Pause"><Button size="small" icon={<PauseOutlined />}
                      disabled={!canControl || !p.jobId || !['RUNNING', 'SNAPSHOT'].includes(p.jobStatus)}
                      onClick={() => control.mutate({ fn: jobsApi.pause, id: p.jobId! })} /></Tooltip>
                    <Tooltip title="Resume"><Button size="small" icon={<StepForwardOutlined />}
                      disabled={!canControl || !p.jobId || p.jobStatus !== 'PAUSED'}
                      onClick={() => control.mutate({ fn: jobsApi.resume, id: p.jobId! })} /></Tooltip>
                    <Tooltip title="Stop"><Button size="small" danger icon={<StopOutlined />}
                      disabled={!canControl || !p.jobId || ['STOPPED', 'COMPLETED'].includes(p.jobStatus)}
                      onClick={() => control.mutate({ fn: jobsApi.stop, id: p.jobId! })} /></Tooltip>
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Card>

      <Card
        title={(
          <Space>
            Job queue
            <Tag color="blue">{queue.data?.running ?? 0} running</Tag>
            <Tag>{queue.data?.queued ?? 0} queued</Tag>
            <Tag color="default">limit {queue.data?.maxConcurrent ?? '—'}</Tag>
          </Space>
        )}
        size="small"
        style={{ marginTop: 16 }}
      >
        {queueTasks.length === 0 ? (
          <EmptyState compact icon={<ThunderboltOutlined />} title="Queue idle"
            description="No scheduled or manual runs in flight. Run-now from a schedule to enqueue one." />
        ) : (
          <Table<OrchestratorTask>
            rowKey="taskId"
            size="small"
            pagination={false}
            scroll={{ x: 'max-content' }}
            dataSource={queueTasks}
            columns={[
              { title: 'Project', dataIndex: 'projectName' },
              { title: 'Kind', dataIndex: 'kind', render: (k: string) => <Tag>{k}</Tag> },
              { title: 'Source', dataIndex: 'source', render: (s: string) => <Tag color={s === 'SCHEDULED' ? 'geekblue' : 'purple'}>{s}</Tag> },
              {
                title: 'State', dataIndex: 'state',
                render: (s: string) => <Badge status={s === 'RUNNING' ? 'processing' : 'warning'} text={s} />,
              },
              {
                title: 'Since',
                render: (_, t: OrchestratorTask) => new Date(t.startedAt ?? t.enqueuedAt).toLocaleTimeString(),
              },
            ]}
          />
        )}
      </Card>

      <Alert
        type="info"
        showIcon
        style={{ marginTop: 16 }}
        message="Live status polls Kafka Connect every 4s; the job queue refreshes every 3s."
        description="Custom metrics (lag, connector state, job state) are at /actuator/prometheus and visualized in Grafana (the Grafana ↗ nav link). Schedule full-load / validation runs from a project's Schedules drawer (#53); they execute through the concurrency-limited job queue above (#54)."
      />
    </>
  );
}
