import { useState } from 'react';
import {
  Button, Card, Collapse, Form, Input, InputNumber, Modal, Select, Space, Switch, Table, Tag, App,
} from 'antd';
import { PlusOutlined, ThunderboltOutlined, DeleteOutlined, DatabaseOutlined, SafetyOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { connectionsApi } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import EmptyState from '../components/EmptyState';
import type { Connection, ConnectionRequest, DbType } from '../api/types';

export const ENGINE_COLOR: Record<DbType, string> = {
  SQLSERVER: 'volcano', POSTGRESQL: 'geekblue', MYSQL: 'gold', ORACLE: 'red', DB2: 'cyan',
};

interface ConnForm extends Omit<ConnectionRequest, 'options'> {
  encrypt?: boolean;
  trustServerCertificate?: boolean;
  sslmode?: string;
}

const SSL_ENGINES: DbType[] = ['POSTGRESQL', 'MYSQL'];

function buildRequest(v: ConnForm): ConnectionRequest {
  const options: Record<string, unknown> = {};
  if (v.dbType === 'SQLSERVER') {
    options.encrypt = v.encrypt ?? true;
    if (v.trustServerCertificate) options.trustServerCertificate = true;
  } else if (v.sslmode) {
    options.sslmode = v.sslmode;
  }
  return {
    name: v.name, dbType: v.dbType, host: v.host, port: v.port,
    databaseName: v.databaseName, username: v.username, password: v.password, options,
  };
}

export default function Connections() {
  const { message, modal } = App.useApp();
  const { user } = useAuth();
  const canWrite = user?.role !== 'VIEWER';
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<ConnForm>();
  const dbType = Form.useWatch('dbType', form);

  const PAGE_SIZE = 20;
  const [page, setPage] = useState(0);
  const [q, setQ] = useState<string | undefined>(undefined);
  const [engine, setEngine] = useState<DbType | undefined>(undefined);

  const { data, isLoading } = useQuery({
    queryKey: ['connections', 'page', page, q, engine],
    queryFn: () => connectionsApi.page({ page, size: PAGE_SIZE, q, dbType: engine }),
  });
  const engines = useQuery({ queryKey: ['engines'], queryFn: connectionsApi.engines });
  const portByEngine = (t: DbType): number =>
    engines.data?.find((e) => e.type === t)?.defaultPort ?? 1433;

  const create = useMutation({
    mutationFn: connectionsApi.create,
    onSuccess: () => {
      message.success('Connection saved');
      qc.invalidateQueries({ queryKey: ['connections'] });
      setOpen(false);
      form.resetFields();
    },
    onError: (e: any) => message.error(e?.response?.data?.message ?? 'Save failed'),
  });

  const remove = useMutation({
    mutationFn: connectionsApi.remove,
    onSuccess: () => {
      message.success('Connection deleted');
      qc.invalidateQueries({ queryKey: ['connections'] });
    },
  });

  const testAdhoc = useMutation({ mutationFn: connectionsApi.testAdhoc });

  const cdcCheck = async (row: Connection) => {
    try {
      const r = await connectionsApi.cdcReadiness(row.id);
      modal.info({
        title: `CDC readiness — ${row.name} (${r.engine}, ${r.cdcStyle})`,
        width: 560,
        content: (
          <Space direction="vertical" style={{ width: '100%', marginTop: 8 }}>
            {r.checks.map((c) => (
              <div key={c.name}>
                <Tag color={c.ok ? 'green' : 'red'}>{c.ok ? 'OK' : 'FIX'}</Tag>
                <strong>{c.name}</strong> — {c.detail}
                {!c.ok && <div style={{ color: '#8A93A3', fontSize: 12, marginLeft: 4 }}>↳ {c.remediation}</div>}
              </div>
            ))}
          </Space>
        ),
      });
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? 'Readiness check failed');
    }
  };

  const handleTest = async () => {
    try {
      const values = await form.validateFields();
      const res = await testAdhoc.mutateAsync(buildRequest(values));
      if (res.success) message.success(`Connected in ${res.latencyMs ?? '?'} ms`);
      else message.error(`Failed: ${res.message}`);
    } catch {
      /* validation errors are surfaced inline by the form */
    }
  };

  const columns = [
    { title: 'Name', dataIndex: 'name' },
    {
      title: 'Engine', dataIndex: 'dbType',
      render: (t: DbType) => <Tag color={ENGINE_COLOR[t]}>{t}</Tag>,
    },
    { title: 'Host', dataIndex: 'host' },
    { title: 'Port', dataIndex: 'port' },
    { title: 'Database', dataIndex: 'databaseName' },
    { title: 'User', dataIndex: 'username' },
    {
      title: 'Actions',
      render: (_: unknown, row: Connection) => (
        <Space>
          <Button
            size="small"
            icon={<ThunderboltOutlined />}
            disabled={!canWrite}
            onClick={async () => {
              const res = await connectionsApi.test(row.id);
              res.success
                ? message.success(`Connected in ${res.latencyMs ?? '?'} ms`)
                : message.error(`Failed: ${res.message}`);
            }}
          >
            Test
          </Button>
          <Button size="small" icon={<SafetyOutlined />} onClick={() => cdcCheck(row)}>CDC</Button>
          <Button
            size="small" danger icon={<DeleteOutlined />} disabled={!canWrite}
            onClick={() => modal.confirm({
              title: `Delete connection "${row.name}"?`,
              onOk: () => remove.mutate(row.id),
            })}
          />
        </Space>
      ),
    },
  ];

  return (
    <Card
      title="Database connections"
      extra={
        <Button type="primary" icon={<PlusOutlined />} disabled={!canWrite} onClick={() => setOpen(true)}>
          New connection
        </Button>
      }
    >
      <Space style={{ marginBottom: 12 }} wrap>
        <Input.Search allowClear placeholder="Search name / host / database / user" style={{ width: 320 }}
          onSearch={(v) => { setQ(v || undefined); setPage(0); }}
          onChange={(e) => { if (!e.target.value) { setQ(undefined); setPage(0); } }} />
        <Select allowClear placeholder="All engines" style={{ width: 180 }} value={engine}
          onChange={(v) => { setEngine(v); setPage(0); }}
          options={(engines.data ?? []).map((e) => ({ value: e.type, label: e.displayName }))} />
      </Space>
      <Table
        rowKey="id"
        loading={isLoading}
        dataSource={data?.content}
        columns={columns}
        pagination={{
          current: page + 1, pageSize: PAGE_SIZE, total: data?.total ?? 0,
          showSizeChanger: false, hideOnSinglePage: true, onChange: (p) => setPage(p - 1),
        }}
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: !isLoading && (
          <EmptyState icon={<DatabaseOutlined />} title={q || engine ? 'No matches' : 'No connections yet'}
            description={q || engine ? 'Try a different search or filter.'
              : 'Add a source (SQL Server) and target (PostgreSQL) connection to get started.'} />
        ) }}
      />

      <Modal
        title="New connection"
        open={open}
        onCancel={() => setOpen(false)}
        footer={[
          <Button key="test" icon={<ThunderboltOutlined />} loading={testAdhoc.isPending} onClick={handleTest}>
            Test connection
          </Button>,
          <Button key="cancel" onClick={() => setOpen(false)}>Cancel</Button>,
          <Button
            key="save" type="primary" loading={create.isPending}
            onClick={async () => {
              const values = await form.validateFields();
              create.mutate(buildRequest(values));
            }}
          >
            Save
          </Button>,
        ]}
      >
        <Form form={form} layout="vertical" initialValues={{ dbType: 'SQLSERVER', port: 1433, encrypt: true }}>
          <Form.Item name="name" label="Name" rules={[{ required: true }]}>
            <Input placeholder="prod-source-mssql" />
          </Form.Item>
          <Form.Item name="dbType" label="Engine" rules={[{ required: true }]}
            tooltip="Any engine can be a source and/or target — heterogeneous or homogeneous (#76)">
            <Select
              loading={engines.isLoading}
              onChange={(v: DbType) => form.setFieldValue('port', portByEngine(v))}
              options={(engines.data ?? []).map((e) => ({
                value: e.type,
                label: `${e.displayName} — ${[e.canSource && 'source', e.canSink && 'target'].filter(Boolean).join(' / ')}`,
              }))}
            />
          </Form.Item>
          <Space.Compact block>
            <Form.Item name="host" label="Host" rules={[{ required: true }]} style={{ width: '70%' }}>
              <Input placeholder="db.example.com" />
            </Form.Item>
            <Form.Item name="port" label="Port" rules={[{ required: true }]} style={{ width: '30%' }}>
              <InputNumber min={1} max={65535} style={{ width: '100%' }} />
            </Form.Item>
          </Space.Compact>
          <Form.Item name="databaseName" label="Database" rules={[{ required: true }]}>
            <Input placeholder="Employees" />
          </Form.Item>
          <Form.Item name="username" label="Username" rules={[{ required: true }]}>
            <Input placeholder="migration_user" />
          </Form.Item>
          <Form.Item name="password" label="Password" rules={[{ required: true }]}>
            <Input.Password placeholder="••••••••" />
          </Form.Item>

          <Collapse
            ghost
            defaultActiveKey={['tls']}
            items={[{
              key: 'tls',
              label: 'TLS / encryption',
              children: SSL_ENGINES.includes(dbType) ? (
                <Form.Item name="sslmode" label="SSL mode"
                  tooltip="PostgreSQL JDBC sslmode; use require/verify-full in production">
                  <Select allowClear placeholder="driver default" options={[
                    { value: 'disable', label: 'disable' },
                    { value: 'require', label: 'require' },
                    { value: 'verify-ca', label: 'verify-ca' },
                    { value: 'verify-full', label: 'verify-full' },
                  ]} />
                </Form.Item>
              ) : (
                <Space size="large">
                  <Form.Item name="encrypt" label="Encrypt" valuePropName="checked"
                    tooltip="SQL Server: encrypt the connection (recommended on)">
                    <Switch />
                  </Form.Item>
                  <Form.Item name="trustServerCertificate" label="Trust server cert" valuePropName="checked"
                    tooltip="Only for dev / self-signed certs; do not enable in production">
                    <Switch />
                  </Form.Item>
                </Space>
              ),
            }]}
          />
        </Form>
      </Modal>
    </Card>
  );
}
