import { Drawer, Typography, Tag, Empty, Alert, Divider, Table } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { monitoringApi, alertsApi } from '../api/client';
import type { AlertItem, Project } from '../api/types';

const SEV: Record<string, string> = { INFO: 'blue', WARNING: 'gold', CRITICAL: 'red' };

/**
 * Errors & connector diagnostics for a project (issue #40): surfaces FAILED task stack traces from
 * Kafka Connect and the project's alert history in-app — no shelling into containers. (Full INFO-level
 * log streaming is part of the monitoring/log-aggregation epic #12.)
 */
export default function ErrorsDrawer({ project, onClose }: { project: Project | null; onClose: () => void }) {
  const open = project !== null;

  const health = useQuery({
    queryKey: ['proj-health', project?.id],
    queryFn: () => monitoringApi.projectStatus(project!.id),
    enabled: open,
    refetchInterval: open ? 5000 : false,
  });
  const alerts = useQuery({
    queryKey: ['alerts'],
    queryFn: alertsApi.list,
    enabled: open,
    refetchInterval: open ? 10000 : false,
  });

  const traces = (health.data?.connectors ?? []).flatMap((c) =>
    c.tasks.filter((t) => t.trace).map((t) => ({ who: `${c.role} task ${t.id} (${t.state})`, trace: t.trace! })));
  const projectAlerts = (alerts.data ?? []).filter((a) => a.projectId === project?.id);

  return (
    <Drawer title={project ? `Errors & diagnostics — ${project.name}` : ''} width={820} open={open} onClose={onClose}>
      <Typography.Title level={5}>Connector errors</Typography.Title>
      {traces.length === 0
        ? <Alert type="success" showIcon message="No connector task errors — connectors are running cleanly." />
        : traces.map((t, i) => (
            <div key={i} style={{ marginBottom: 12 }}>
              <Tag color="red">{t.who}</Tag>
              <pre style={{ maxHeight: 220, overflow: 'auto', background: '#fff1f0', padding: 10, fontSize: 12 }}>{t.trace}</pre>
            </div>
          ))}

      <Divider orientation="left" plain>Alert history (this project)</Divider>
      {projectAlerts.length === 0
        ? <Empty description="No alerts for this project" />
        : <Table<AlertItem>
            rowKey="id" size="small" dataSource={projectAlerts} pagination={{ pageSize: 8 }}
            columns={[
              { title: 'Severity', dataIndex: 'severity', render: (s: string) => <Tag color={SEV[s]}>{s}</Tag> },
              { title: 'Type', dataIndex: 'type' },
              { title: 'Message', dataIndex: 'message', ellipsis: true },
              { title: 'Status', dataIndex: 'status', render: (s: string) => <Tag>{s}</Tag> },
              { title: 'Raised', dataIndex: 'createdAt', render: (t: string) => new Date(t).toLocaleString() },
            ]} />}
    </Drawer>
  );
}
