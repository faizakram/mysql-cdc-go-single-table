import { useState } from 'react';
import { Card, Table, Tag, Typography, Tooltip } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { auditApi } from '../api/client';
import type { AuditEntry } from '../api/types';

const ACTION_COLOR: Record<string, string> = {
  LOGIN: 'blue',
  JOB_START: 'green', JOB_STOP: 'red', JOB_PAUSE: 'orange', JOB_RESUME: 'green',
  PROJECT_CREATE: 'geekblue', PROJECT_UPDATE: 'cyan', PROJECT_DELETE: 'red',
  CONNECTION_CREATE: 'geekblue', CONNECTION_UPDATE: 'cyan', CONNECTION_DELETE: 'red',
  SCHEDULE_CREATE: 'geekblue', SCHEDULE_RUN_NOW: 'purple',
};

/** Admin-only audit log of control + config actions (#57). */
export default function Audit() {
  const [page, setPage] = useState(0);
  const size = 50;
  const q = useQuery({
    queryKey: ['audit', page],
    queryFn: () => auditApi.list(page, size),
    refetchInterval: 10000,
  });

  return (
    <Card title="Audit log" size="small"
      extra={<Typography.Text type="secondary">Who did what — control & config actions</Typography.Text>}>
      <Table<AuditEntry>
        rowKey="id"
        size="small"
        loading={q.isLoading}
        dataSource={q.data?.content}
        scroll={{ x: 'max-content' }}
        pagination={{
          current: page + 1, pageSize: size, total: q.data?.total ?? 0,
          showSizeChanger: false, onChange: (p) => setPage(p - 1),
        }}
        columns={[
          {
            title: 'When', dataIndex: 'createdAt', width: 200,
            render: (v: string) => new Date(v).toLocaleString(),
          },
          { title: 'Actor', dataIndex: 'actor', width: 140, render: (a: string) => <Tag>{a}</Tag> },
          {
            title: 'Action', dataIndex: 'action', width: 180,
            render: (a: string) => <Tag color={ACTION_COLOR[a] ?? 'default'}>{a}</Tag>,
          },
          {
            title: 'Target', dataIndex: 'target', ellipsis: true,
            render: (t: string) => (t ? <Typography.Text code copyable>{t}</Typography.Text> : '—'),
          },
          {
            title: 'Details',
            render: (_: unknown, r: AuditEntry) => {
              const d = JSON.stringify(r.details ?? {});
              return d === '{}' ? '—' : <Tooltip title={d}><Typography.Text type="secondary">{d.slice(0, 60)}</Typography.Text></Tooltip>;
            },
          },
        ]}
      />
    </Card>
  );
}
