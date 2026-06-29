import {
  Drawer, Button, Table, Tag, Space, App, Modal, Typography, Empty, Tooltip, Checkbox, Progress,
} from 'antd';
import {
  PlayCircleOutlined, PauseOutlined, StepForwardOutlined, StopOutlined,
  PlusOutlined, EyeOutlined, ReloadOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { jobsApi } from '../api/client';
import type { Job, JobStatus, JobTableStatus, Project } from '../api/types';

const TS_COLOR: Record<string, string> = {
  PENDING: 'default', IN_PROGRESS: 'processing', COMPLETED: 'green', FAILED: 'red',
};
const PHASE_COLOR: Record<string, string> = {
  SCHEMA: 'purple', DATA: 'blue', CDC: 'cyan',
};

/** Human-friendly duration from seconds (#185). */
function fmtDuration(sec: number): string {
  if (!isFinite(sec) || sec < 0) return '—';
  if (sec < 60) return `${Math.round(sec)}s`;
  if (sec < 3600) return `${Math.floor(sec / 60)}m ${Math.round(sec % 60)}s`;
  return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`;
}

/** Per-table replication lag, ms → "x ms" / "x.x s" (#185). */
function fmtLag(ms: number | null): string {
  if (ms == null) return '—';
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}

/** Snapshot %-complete from rows synced vs the source estimate; COMPLETED is always 100 (#185). */
function progressPct(t: JobTableStatus): number | null {
  if (t.status === 'COMPLETED') return 100;
  if (!t.totalRows || t.totalRows <= 0) return null;
  return Math.min(99, Math.round((100 * t.rowsSynced) / t.totalRows));  // cap <100 until truly done
}

/** ETA seconds from per-table throughput (rows synced / elapsed since start); null when not derivable. */
function etaSeconds(t: JobTableStatus): number | null {
  if (t.status !== 'IN_PROGRESS' || !t.totalRows || !t.startedAt) return null;
  const elapsed = (Date.now() - new Date(t.startedAt).getTime()) / 1000;
  const rate = elapsed > 0 ? t.rowsSynced / elapsed : 0;
  const remaining = t.totalRows - t.rowsSynced;
  return rate > 0 && remaining > 0 ? remaining / rate : null;
}

function JobTables({ jobId, live }: { jobId: string; live: boolean }) {
  const { data, isLoading } = useQuery({
    queryKey: ['job-tables', jobId],
    queryFn: () => jobsApi.tables(jobId),
    // Poll while the run is active so per-table rows/phase advance in view (#129).
    refetchInterval: live ? 8000 : false,   // (#216)
  });
  if (!isLoading && (!data || data.length === 0)) {
    return <Typography.Text type="secondary">No per-table status (start the run to populate).</Typography.Text>;
  }
  return (
    <Table<JobTableStatus>
      size="small" rowKey={(t) => `${t.schemaName}.${t.tableName}`} loading={isLoading}
      dataSource={data} pagination={false}
      columns={[
        { title: 'Table', render: (_: unknown, t: JobTableStatus) => `${t.schemaName}.${t.tableName}` },
        {
          title: 'Phase', dataIndex: 'phase',
          render: (p: string) => <Tag color={PHASE_COLOR[p] ?? 'default'}>{p}</Tag>,
        },
        {
          title: 'Status', dataIndex: 'status',
          render: (s: string, t: JobTableStatus) => {
            const tag = <Tag color={TS_COLOR[s] ?? 'default'}>{s}</Tag>;
            return t.error ? <Tooltip title={t.error}>{tag}</Tooltip> : tag;
          },
        },
        {
          title: 'Rows synced', dataIndex: 'rowsSynced', align: 'right' as const,
          render: (n: number, t: JobTableStatus) => {
            const synced = (n ?? 0).toLocaleString();
            return t.totalRows && t.totalRows > 0
              ? <Tooltip title={`~${t.totalRows.toLocaleString()} estimated`}>{synced}</Tooltip>
              : synced;
          },
        },
        {
          title: 'Progress', align: 'right' as const, width: 120,
          render: (_: unknown, t: JobTableStatus) => {
            const pct = progressPct(t);
            if (pct == null) return <Typography.Text type="secondary">—</Typography.Text>;
            return (
              <Progress
                percent={pct} size="small" style={{ width: 96, marginBottom: 0 }}
                status={t.status === 'FAILED' ? 'exception' : t.status === 'COMPLETED' ? 'success' : 'active'}
              />
            );
          },
        },
        {
          title: 'ETA', align: 'right' as const,
          render: (_: unknown, t: JobTableStatus) => {
            if (t.status === 'COMPLETED') return <Typography.Text type="secondary">done</Typography.Text>;
            const eta = etaSeconds(t);
            return eta == null ? <Typography.Text type="secondary">—</Typography.Text> : fmtDuration(eta);
          },
        },
        {
          title: 'Lag', align: 'right' as const,
          render: (_: unknown, t: JobTableStatus) => (
            <Typography.Text type={t.lastLagMs != null && t.lastLagMs > 0 ? undefined : 'secondary'}>
              {fmtLag(t.lastLagMs)}
            </Typography.Text>
          ),
        },
      ]}
    />
  );
}

const STATUS_COLOR: Record<JobStatus, string> = {
  CREATED: 'default', SNAPSHOT: 'processing', RUNNING: 'green',
  PAUSED: 'orange', STOPPED: 'default', FAILED: 'red', COMPLETED: 'blue',
};

export default function JobsDrawer({ project, onClose }: { project: Project | null; onClose: () => void }) {
  const { message, modal } = App.useApp();
  const qc = useQueryClient();
  const [preview, setPreview] = useState<unknown | null>(null);
  const open = project !== null;

  const jobs = useQuery({
    queryKey: ['jobs', project?.id],
    queryFn: () => jobsApi.listForProject(project!.id),
    enabled: open,
    refetchInterval: open ? 8000 : false,   // (#216)
  });

  const invalidate = () => qc.invalidateQueries({ queryKey: ['jobs', project?.id] });
  const onErr = (e: any) => message.error(e?.response?.data?.message ?? 'Action failed');

  const createRun = useMutation({
    mutationFn: () => jobsApi.create(project!.id),
    onSuccess: () => { message.success('Run created'); invalidate(); },
    onError: onErr,
  });
  const act = (fn: (id: string) => Promise<Job>, label: string) => useMutation({
    mutationFn: fn,
    onSuccess: () => { message.success(label); invalidate(); },
    onError: onErr,
  });
  const start = act(jobsApi.start, 'Started');
  const pause = act(jobsApi.pause, 'Paused');
  const resume = act(jobsApi.resume, 'Resumed');
  const stop = act(jobsApi.stop, 'Stopped');
  const reload = useMutation({
    mutationFn: ({ id, cleanTarget }: { id: string; cleanTarget: boolean }) => jobsApi.reload(id, cleanTarget),
    onSuccess: () => { message.success('Full reload started — re-snapshotting'); invalidate(); },
    onError: onErr,
  });

  const confirmReload = (id: string) => {
    let cleanTarget = false;
    modal.confirm({
      title: 'Re-run full load?',
      okText: 'Re-run full load',
      okButtonProps: { danger: true },
      width: 520,
      content: (
        <div>
          <Typography.Paragraph>
            Resets the source connector offsets so the snapshot runs again from scratch and the target
            is re-synced (rows are re-applied as upserts). Requires Kafka Connect 3.6+.
          </Typography.Paragraph>
          <Checkbox onChange={(e) => { cleanTarget = e.target.checked; }}>
            <b>Clean target first</b> — truncate all target tables before re-snapshot. Removes rows that
            were deleted on the source since the last load. <Typography.Text type="danger">Deletes all
            current target rows for this project.</Typography.Text>
          </Checkbox>
        </div>
      ),
      onOk: () => reload.mutate({ id, cleanTarget }),
    });
  };

  const showPreview = async () => {
    try { setPreview(await jobsApi.preview(project!.id)); }
    catch (e: any) { onErr(e); }
  };

  const columns = [
    {
      title: 'Status', dataIndex: 'status',
      render: (s: JobStatus) => <Tag color={STATUS_COLOR[s]}>{s}</Tag>,
    },
    { title: 'Phase', dataIndex: 'phase', render: (p?: string) => p ?? '—' },
    {
      title: 'Created', dataIndex: 'createdAt',
      render: (t: string) => new Date(t).toLocaleString(),
    },
    {
      title: 'Error', dataIndex: 'error',
      render: (e?: string) => (e ? <Typography.Text type="danger">{e}</Typography.Text> : '—'),
    },
    {
      title: 'Controls',
      render: (_: unknown, j: Job) => (
        <Space>
          <Button size="small" type="primary" icon={<PlayCircleOutlined />}
            disabled={['RUNNING', 'SNAPSHOT'].includes(j.status)}
            loading={start.isPending} onClick={() => start.mutate(j.id)}>Start</Button>
          <Button size="small" icon={<PauseOutlined />}
            disabled={!['RUNNING', 'SNAPSHOT'].includes(j.status)}
            onClick={() => pause.mutate(j.id)} />
          <Button size="small" icon={<StepForwardOutlined />}
            disabled={j.status !== 'PAUSED'}
            onClick={() => resume.mutate(j.id)} />
          <Button size="small" danger icon={<StopOutlined />}
            disabled={['STOPPED', 'COMPLETED'].includes(j.status)}
            onClick={() => stop.mutate(j.id)} />
          <Tooltip title="Re-run full load (re-snapshot)">
            <Button size="small" icon={<ReloadOutlined />}
              disabled={!['RUNNING', 'SNAPSHOT', 'PAUSED'].includes(j.status)}
              loading={reload.isPending} onClick={() => confirmReload(j.id)} />
          </Tooltip>
        </Space>
      ),
    },
  ];

  return (
    <Drawer
      title={project ? `Runs — ${project.name}` : ''}
      width={840}
      open={open}
      onClose={onClose}
      extra={
        <Space>
          <Button icon={<EyeOutlined />} onClick={showPreview}>Preview connectors</Button>
          <Button type="primary" icon={<PlusOutlined />}
            loading={createRun.isPending} onClick={() => createRun.mutate()}>New run</Button>
        </Space>
      }
    >
      {jobs.data && jobs.data.length === 0
        ? <Empty description="No runs yet — create one, then Start to deploy the connectors." />
        : <Table rowKey="id" loading={jobs.isLoading} dataSource={jobs.data} columns={columns} pagination={false}
            expandable={{ expandedRowRender: (j) => (
              <JobTables jobId={j.id} live={['SNAPSHOT', 'RUNNING'].includes(j.status)} />
            ) }} />}

      <Modal title="Connector preview (secrets masked)" open={preview !== null}
        footer={null} width={760} onCancel={() => setPreview(null)}>
        <pre style={{
          maxHeight: 480, overflow: 'auto', background: '#f5f5f5', color: '#1E2430',
          padding: 12, borderRadius: 6, margin: 0, fontSize: 12,
        }}>
          {JSON.stringify(preview, null, 2)}
        </pre>
      </Modal>
    </Drawer>
  );
}
