import { Card, Table, Tag, Button, App } from 'antd';
import { CheckOutlined, CheckCircleOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { alertsApi } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import EmptyState from '../components/EmptyState';
import type { AlertItem } from '../api/types';

const SEV_COLOR: Record<string, string> = { INFO: 'blue', WARNING: 'gold', CRITICAL: 'red' };
const STATUS_COLOR: Record<string, string> = { FIRING: 'red', RESOLVED: 'green', ACKNOWLEDGED: 'default' };

export default function Alerts() {
  const { message } = App.useApp();
  const { user } = useAuth();
  const canWrite = user?.role !== 'VIEWER';
  const qc = useQueryClient();

  const { data, isLoading } = useQuery({
    queryKey: ['alerts'],
    queryFn: alertsApi.list,
    refetchInterval: 15000,
  });

  const ack = useMutation({
    mutationFn: alertsApi.acknowledge,
    onSuccess: () => { message.success('Alert acknowledged'); qc.invalidateQueries({ queryKey: ['alerts'] }); },
    onError: (e: any) => message.error(e?.response?.data?.message ?? 'Failed'),
  });

  const columns = [
    { title: 'Severity', dataIndex: 'severity', render: (s: string) => <Tag color={SEV_COLOR[s]}>{s}</Tag> },
    { title: 'Type', dataIndex: 'type', render: (t: string) => <Tag>{t}</Tag> },
    { title: 'Message', dataIndex: 'message', ellipsis: true },
    { title: 'Status', dataIndex: 'status', render: (s: string) => <Tag color={STATUS_COLOR[s]}>{s}</Tag> },
    { title: 'Raised', dataIndex: 'createdAt', render: (t: string) => new Date(t).toLocaleString() },
    {
      title: 'Actions',
      render: (_: unknown, a: AlertItem) => (
        <Button size="small" icon={<CheckOutlined />}
          disabled={!canWrite || a.status === 'ACKNOWLEDGED'}
          onClick={() => ack.mutate(a.id)}>Acknowledge</Button>
      ),
    },
  ];

  return (
    <Card title="Alerts" extra={<span style={{ color: 'rgba(0,0,0,.45)' }}>auto-refreshes every 15s</span>}>
      <Table<AlertItem>
        rowKey="id" loading={isLoading} dataSource={data} columns={columns}
        pagination={{ pageSize: 15 }} scroll={{ x: 'max-content' }}
        locale={{ emptyText: !isLoading && (
          <EmptyState icon={<CheckCircleOutlined />} title="No alerts — all clear"
            description="Connector failures and lag-threshold breaches will appear here when they occur." />
        ) }}
        rowClassName={(a) => (a.status === 'FIRING' ? '' : 'ant-table-row-disabled')} />
    </Card>
  );
}
