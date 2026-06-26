import { useState } from 'react';
import {
  Button, Card, Form, Input, Modal, Select, Space, Switch, Table, Tag, App,
} from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { usersApi } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import type { RoleName, UserAdmin } from '../api/types';

const ROLE_OPTS = [
  { value: 'ADMIN', label: 'ADMIN — full control' },
  { value: 'OPERATOR', label: 'OPERATOR — run & configure' },
  { value: 'VIEWER', label: 'VIEWER — read only' },
];

export default function Users() {
  const { message, modal } = App.useApp();
  const { user } = useAuth();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<{ username: string; password: string; role: RoleName }>();

  const { data, isLoading } = useQuery({ queryKey: ['users'], queryFn: usersApi.list });
  const invalidate = () => qc.invalidateQueries({ queryKey: ['users'] });
  const onErr = (e: any) => message.error(e?.response?.data?.message ?? 'Action failed');

  const create = useMutation({
    mutationFn: usersApi.create,
    onSuccess: () => { message.success('User created'); invalidate(); setOpen(false); form.resetFields(); },
    onError: onErr,
  });
  const update = useMutation({
    mutationFn: ({ id, body }: { id: string; body: any }) => usersApi.update(id, body),
    onSuccess: () => { message.success('User updated'); invalidate(); },
    onError: onErr,
  });
  const remove = useMutation({
    mutationFn: usersApi.remove,
    onSuccess: () => { message.success('User deleted'); invalidate(); },
    onError: onErr,
  });

  const columns = [
    {
      title: 'Username', dataIndex: 'username',
      render: (n: string) => <Space>{n}{n === user?.username && <Tag>you</Tag>}</Space>,
    },
    {
      title: 'Role', dataIndex: 'role',
      render: (r: RoleName, row: UserAdmin) => (
        <Select size="small" value={r} style={{ width: 130 }}
          options={ROLE_OPTS.map((o) => ({ value: o.value, label: o.value }))}
          onChange={(role) => update.mutate({ id: row.id, body: { role } })} />
      ),
    },
    {
      title: 'Enabled', dataIndex: 'enabled',
      render: (en: boolean, row: UserAdmin) => (
        <Switch checked={en} onChange={(enabled) => update.mutate({ id: row.id, body: { enabled } })} />
      ),
    },
    {
      title: 'Created', dataIndex: 'createdAt',
      render: (t: string) => new Date(t).toLocaleString(),
    },
    {
      title: 'Actions',
      render: (_: unknown, row: UserAdmin) => (
        <Button size="small" danger icon={<DeleteOutlined />} disabled={row.username === user?.username}
          onClick={() => modal.confirm({ title: `Delete user "${row.username}"?`, onOk: () => remove.mutate(row.id) })} />
      ),
    },
  ];

  return (
    <Card
      title="Users & roles"
      extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>New user</Button>}
    >
      <Table rowKey="id" loading={isLoading} dataSource={data}
        columns={columns} pagination={false} scroll={{ x: 'max-content' }}
        rowClassName={(r) => (r.enabled ? '' : 'ant-table-row-disabled')} />

      <Modal title="New user" open={open} onCancel={() => setOpen(false)}
        confirmLoading={create.isPending}
        onOk={async () => create.mutate(await form.validateFields())}>
        <Form form={form} layout="vertical" initialValues={{ role: 'OPERATOR' }}>
          <Form.Item name="username" label="Username" rules={[{ required: true }]}>
            <Input placeholder="jane.doe" />
          </Form.Item>
          <Form.Item name="password" label="Password" rules={[{ required: true, min: 6 }]}>
            <Input.Password placeholder="initial password" />
          </Form.Item>
          <Form.Item name="role" label="Role" rules={[{ required: true }]}>
            <Select options={ROLE_OPTS} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
