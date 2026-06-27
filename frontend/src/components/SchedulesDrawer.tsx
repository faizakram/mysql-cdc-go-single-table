import {
  Drawer, Button, Table, Tag, App, Space, Select, Input, Switch, Form, Tooltip, Typography, Empty,
} from 'antd';
import { PlusOutlined, ThunderboltOutlined, DeleteOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { schedulesApi } from '../api/client';
import type { Project, Schedule, ScheduleKind } from '../api/types';

const KIND_LABEL: Record<ScheduleKind, string> = {
  FULL_LOAD: 'Full load (snapshot + CDC)',
  VALIDATION: 'Validation (reconcile)',
};
const STATUS_COLOR: Record<string, string> = { SUCCESS: 'green', FAILED: 'red', RUNNING: 'blue' };
const fmt = (s?: string) => (s ? new Date(s).toLocaleString() : '—');

/** Manage per-project cron schedules for full-load / validation runs (#53). */
export default function SchedulesDrawer({ project, onClose }: { project: Project | null; onClose: () => void }) {
  const { message } = App.useApp();
  const qc = useQueryClient();
  const open = project !== null;
  const [form] = Form.useForm();

  const list = useQuery({
    queryKey: ['schedules', project?.id],
    queryFn: () => schedulesApi.listForProject(project!.id),
    enabled: open,
    refetchInterval: 5000,
  });

  const invalidate = () => qc.invalidateQueries({ queryKey: ['schedules', project?.id] });

  const create = useMutation({
    mutationFn: async () => {
      const v = await form.validateFields();
      return schedulesApi.create(project!.id, { kind: v.kind, cron: v.cron, enabled: v.enabled });
    },
    onSuccess: () => { message.success('Schedule created'); form.resetFields(); invalidate(); },
    onError: (e: any) => message.error(e?.response?.data?.message ?? 'Create failed'),
  });

  const runNow = useMutation({
    mutationFn: (id: string) => schedulesApi.runNow(id),
    onSuccess: () => { message.success('Queued a run now'); invalidate(); },
    onError: (e: any) => message.error(e?.response?.data?.message ?? 'Run failed'),
  });

  const toggle = useMutation({
    mutationFn: (s: Schedule) => schedulesApi.update(s.id, { kind: s.kind, cron: s.cron, enabled: !s.enabled }),
    onSuccess: invalidate,
    onError: (e: any) => message.error(e?.response?.data?.message ?? 'Update failed'),
  });

  const remove = useMutation({
    mutationFn: (id: string) => schedulesApi.remove(id),
    onSuccess: () => { message.success('Schedule deleted'); invalidate(); },
  });

  return (
    <Drawer title={project ? `Schedules — ${project.name}` : ''} width={760} open={open} onClose={onClose}>
      <Form form={form} layout="inline" initialValues={{ kind: 'VALIDATION', cron: '0 0 2 * * *', enabled: true }}
        style={{ marginBottom: 16, rowGap: 8 }}>
        <Form.Item name="kind" rules={[{ required: true }]}>
          <Select style={{ width: 220 }} options={[
            { value: 'VALIDATION', label: KIND_LABEL.VALIDATION },
            { value: 'FULL_LOAD', label: KIND_LABEL.FULL_LOAD },
          ]} />
        </Form.Item>
        <Form.Item name="cron" rules={[{ required: true }]}
          tooltip="Spring 6-field cron: sec min hour day-of-month month day-of-week">
          <Input style={{ width: 200 }} placeholder="0 0 2 * * *" />
        </Form.Item>
        <Form.Item name="enabled" valuePropName="checked" label="Enabled">
          <Switch />
        </Form.Item>
        <Form.Item>
          <Button type="primary" icon={<PlusOutlined />} loading={create.isPending}
            onClick={() => create.mutate()}>Add schedule</Button>
        </Form.Item>
      </Form>
      <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
        Cron is 6-field (sec min hour dom mon dow). e.g. <code>0 0 2 * * *</code> = daily 02:00,
        <code> 0 */15 * * * *</code> = every 15 min. Runs go through the job queue (concurrency-limited).
      </Typography.Paragraph>

      <Table
        rowKey="id"
        size="small"
        loading={list.isLoading}
        dataSource={list.data}
        locale={{ emptyText: <Empty description="No schedules yet" /> }}
        pagination={false}
        columns={[
          { title: 'Kind', dataIndex: 'kind', render: (k: ScheduleKind) => <Tag>{KIND_LABEL[k]}</Tag> },
          { title: 'Cron', dataIndex: 'cron', render: (c: string) => <code>{c}</code> },
          {
            title: 'Enabled', dataIndex: 'enabled',
            render: (_: boolean, s: Schedule) => (
              <Switch size="small" checked={s.enabled} loading={toggle.isPending}
                onChange={() => toggle.mutate(s)} />
            ),
          },
          {
            title: 'Last run', dataIndex: 'lastRunAt',
            render: (_: string, s: Schedule) => (
              <Space size={4}>
                <span>{fmt(s.lastRunAt)}</span>
                {s.lastStatus && <Tag color={STATUS_COLOR[s.lastStatus] ?? 'default'}>{s.lastStatus}</Tag>}
              </Space>
            ),
          },
          { title: 'Next run', dataIndex: 'nextRunAt', render: (v: string) => fmt(v) },
          {
            title: '', key: 'actions',
            render: (_: unknown, s: Schedule) => (
              <Space>
                <Tooltip title="Run now">
                  <Button size="small" icon={<ThunderboltOutlined />} loading={runNow.isPending}
                    onClick={() => runNow.mutate(s.id)} />
                </Tooltip>
                <Button size="small" danger icon={<DeleteOutlined />} onClick={() => remove.mutate(s.id)} />
              </Space>
            ),
          },
        ]}
      />
    </Drawer>
  );
}
