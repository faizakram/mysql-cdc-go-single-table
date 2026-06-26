import { Card, Col, Row, Statistic, Alert, Table, Tag, Tooltip, Badge, Space, Empty, Button, App } from 'antd';
import { PauseOutlined, StepForwardOutlined, StopOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { projectsApi, connectionsApi, monitoringApi, jobsApi } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import type { ConnectorHealth, ProjectHealth } from '../api/types';

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

  return (
    <>
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card><Statistic title="Projects" value={projects.data?.length ?? 0} loading={projects.isLoading} /></Card>
        </Col>
        <Col span={6}>
          <Card><Statistic title="Connections" value={connections.data?.length ?? 0} loading={connections.isLoading} /></Card>
        </Col>
        <Col span={6}>
          <Card><Statistic title="Active migrations" value={overview.data?.length ?? 0} loading={overview.isLoading} /></Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="Unhealthy"
              valueStyle={{ color: (overview.data?.filter((p) => !p.healthy).length ?? 0) > 0 ? '#cf1322' : undefined }}
              value={overview.data?.filter((p) => !p.healthy).length ?? 0}
              loading={overview.isLoading}
            />
          </Card>
        </Col>
      </Row>

      <Card title="Live migration status" size="small">
        {overview.data && overview.data.length === 0 ? (
          <Empty description="No migrations with deployed connectors yet. Start a run from a project." />
        ) : (
          <Table<ProjectHealth>
            rowKey="projectId"
            loading={overview.isLoading}
            dataSource={overview.data}
            pagination={false}
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
                  : <span style={{ color: v > 0 ? '#faad14' : '#3f8600' }}>{v}</span>),
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

      <Alert
        type="info"
        showIcon
        style={{ marginTop: 16 }}
        message="Live status polls Kafka Connect every 4s."
        description="JVM + custom metrics (incl. migration_active_jobs) are exposed at /actuator/prometheus for Grafana (#51). Per-table sync progress and lag charts land with the rest of the monitoring epic."
      />
    </>
  );
}
