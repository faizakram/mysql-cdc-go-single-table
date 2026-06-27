import { Card, Table, Tag, Typography } from 'antd';
import { ApiOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { pluginsApi } from '../api/client';
import type { PluginInfo } from '../api/types';
import EmptyState from '../components/EmptyState';

const KIND_COLOR: Record<string, string> = {
  SOURCE_CONNECTOR: 'geekblue', SINK_DIALECT: 'green', TYPE_MAPPER: 'gold', VALIDATOR: 'purple', TRANSFORM: 'cyan',
};

/** Loaded extensions (engines, sink dialects) discovered via the SPI (#116). */
export default function Plugins() {
  const q = useQuery({ queryKey: ['plugins'], queryFn: pluginsApi.list });
  return (
    <Card title="Loaded extensions"
      extra={<Typography.Text type="secondary">Pluggable engines & dialects discovered at runtime</Typography.Text>}>
      <Table<PluginInfo>
        rowKey="id" size="small" loading={q.isLoading} dataSource={q.data}
        pagination={false} scroll={{ x: 'max-content' }}
        locale={{ emptyText: !q.isLoading && <EmptyState icon={<ApiOutlined />} title="No plugins loaded" /> }}
        columns={[
          { title: 'ID', dataIndex: 'id', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
          { title: 'Kind', dataIndex: 'kind', render: (k: string) => <Tag color={KIND_COLOR[k] ?? 'default'}>{k}</Tag> },
          { title: 'Version', dataIndex: 'version' },
          { title: 'Detail', dataIndex: 'detail' },
        ]}
      />
    </Card>
  );
}
