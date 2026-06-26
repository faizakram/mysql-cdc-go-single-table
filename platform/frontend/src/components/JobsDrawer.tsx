import {
  Drawer, Button, Table, Tag, Space, App, Modal, Typography, Empty,
} from 'antd';
import {
  PlayCircleOutlined, PauseOutlined, StepForwardOutlined, StopOutlined,
  PlusOutlined, EyeOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { jobsApi } from '../api/client';
import type { Job, JobStatus, JobTableStatus, Project } from '../api/types';

const TS_COLOR: Record<string, string> = {
  PENDING: 'default', IN_PROGRESS: 'processing', COMPLETED: 'green', FAILED: 'red',
};

function JobTables({ jobId }: { jobId: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ['job-tables', jobId],
    queryFn: () => jobsApi.tables(jobId),
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
        { title: 'Phase', dataIndex: 'phase' },
        { title: 'Status', dataIndex: 'status', render: (s: string) => <Tag color={TS_COLOR[s] ?? 'default'}>{s}</Tag> },
        { title: 'Rows', dataIndex: 'rowsSynced' },
      ]}
    />
  );
}

const STATUS_COLOR: Record<JobStatus, string> = {
  CREATED: 'default', SNAPSHOT: 'processing', RUNNING: 'green',
  PAUSED: 'orange', STOPPED: 'default', FAILED: 'red', COMPLETED: 'blue',
};

export default function JobsDrawer({ project, onClose }: { project: Project | null; onClose: () => void }) {
  const { message } = App.useApp();
  const qc = useQueryClient();
  const [preview, setPreview] = useState<unknown | null>(null);
  const open = project !== null;

  const jobs = useQuery({
    queryKey: ['jobs', project?.id],
    queryFn: () => jobsApi.listForProject(project!.id),
    enabled: open,
    refetchInterval: open ? 4000 : false,
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
            expandable={{ expandedRowRender: (j) => <JobTables jobId={j.id} /> }} />}

      <Modal title="Connector preview (secrets masked)" open={preview !== null}
        footer={null} width={760} onCancel={() => setPreview(null)}>
        <pre style={{ maxHeight: 480, overflow: 'auto', background: '#f5f5f5', padding: 12 }}>
          {JSON.stringify(preview, null, 2)}
        </pre>
      </Modal>
    </Drawer>
  );
}
