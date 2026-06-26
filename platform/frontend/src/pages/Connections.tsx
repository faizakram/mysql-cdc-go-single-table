import { useState } from 'react';
import {
  Button, Card, Collapse, Form, Input, InputNumber, Modal, Select, Space, Switch, Table, Tag, App,
} from 'antd';
import { PlusOutlined, ThunderboltOutlined, DeleteOutlined, DatabaseOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { connectionsApi } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import EmptyState from '../components/EmptyState';
import type { Connection, ConnectionRequest, DbType } from '../api/types';

const DEFAULT_PORT: Record<DbType, number> = { SQLSERVER: 1433, POSTGRESQL: 5432 };

interface ConnForm extends Omit<ConnectionRequest, 'options'> {
  encrypt?: boolean;
  trustServerCertificate?: boolean;
  sslmode?: string;
}

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

  const { data, isLoading } = useQuery({ queryKey: ['connections'], queryFn: connectionsApi.list });

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
      title: 'Type', dataIndex: 'dbType',
      render: (t: DbType) => <Tag color={t === 'SQLSERVER' ? 'volcano' : 'geekblue'}>{t}</Tag>,
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
      <Table
        rowKey="id"
        loading={isLoading}
        dataSource={data}
        columns={columns}
        pagination={false}
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: !isLoading && (
          <EmptyState icon={<DatabaseOutlined />} title="No connections yet"
            description="Add a source (SQL Server) and target (PostgreSQL) connection to get started." />
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
          <Form.Item name="dbType" label="Type" rules={[{ required: true }]}>
            <Select
              onChange={(v: DbType) => form.setFieldValue('port', DEFAULT_PORT[v])}
              options={[
                { value: 'SQLSERVER', label: 'SQL Server (source)' },
                { value: 'POSTGRESQL', label: 'PostgreSQL (target)' },
              ]}
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
              children: dbType === 'POSTGRESQL' ? (
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
