import { Button, Form, Input, Typography, App } from 'antd';
import { LockOutlined, UserOutlined, DeploymentUnitOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useAuth } from '../auth/AuthContext';

export default function Login() {
  const { login } = useAuth();
  const { message } = App.useApp();
  const [loading, setLoading] = useState(false);

  const onFinish = async (v: { username: string; password: string }) => {
    setLoading(true);
    try {
      await login(v.username, v.password);
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? 'Invalid username or password');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-shell">
      <div className="login-card">
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 4 }}>
          <span className="brand-mark" style={{ width: 36, height: 36 }}>
            <DeploymentUnitOutlined style={{ fontSize: 20 }} />
          </span>
          <div>
            <Typography.Title level={4} style={{ margin: 0 }}>CDC Console</Typography.Title>
            <Typography.Text type="secondary" style={{ fontSize: 13 }}>
              Heterogeneous database migration
            </Typography.Text>
          </div>
        </div>

        <Typography.Paragraph type="secondary" style={{ margin: '18px 0 20px' }}>
          Sign in to manage connections, migrations and validation.
        </Typography.Paragraph>

        <Form layout="vertical" onFinish={onFinish} requiredMark={false}>
          <Form.Item name="username" label="Username"
            rules={[{ required: true, message: 'Enter your username' }]}>
            <Input prefix={<UserOutlined style={{ color: '#9AA3B2' }} />} placeholder="admin" size="large" autoFocus />
          </Form.Item>
          <Form.Item name="password" label="Password"
            rules={[{ required: true, message: 'Enter your password' }]}>
            <Input.Password prefix={<LockOutlined style={{ color: '#9AA3B2' }} />} placeholder="••••••••" size="large" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block size="large" loading={loading} style={{ marginTop: 4 }}>
            Sign in
          </Button>
        </Form>
      </div>
    </div>
  );
}
