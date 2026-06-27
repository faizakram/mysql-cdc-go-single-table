import { Button, Form, Input, App } from 'antd';
import {
  LockOutlined, UserOutlined, DeploymentUnitOutlined, SwapOutlined, SyncOutlined, SafetyCertificateOutlined,
} from '@ant-design/icons';
import { useState } from 'react';
import { useAuth } from '../auth/AuthContext';

const FEATURES = [
  { icon: <SwapOutlined />, text: 'Any source to any target — Oracle, SQL Server, MySQL, PostgreSQL, MongoDB' },
  { icon: <SyncOutlined />, text: 'Real-time change data capture with live lag & health' },
  { icon: <SafetyCertificateOutlined />, text: 'Dry-run, validation, rollback & full audit trail' },
];

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
    <div className="auth">
      <aside className="auth-brand">
        <div className="auth-logo">
          <span className="brand-mark"><DeploymentUnitOutlined /></span>
          <div>
            <div className="auth-logo-name">CDC Console</div>
            <div className="auth-logo-sub">Database Migration Platform</div>
          </div>
        </div>

        <div>
          <h1 className="auth-headline">Move data between <span className="accent">any databases</span>, safely.</h1>
          <p className="auth-tag">
            Heterogeneous and homogeneous migrations with change data capture, intelligent planning,
            and end-to-end validation — from one console.
          </p>
          <div className="auth-features">
            {FEATURES.map((f) => (
              <div className="auth-feature" key={f.text}>
                <span className="ico">{f.icon}</span><span>{f.text}</span>
              </div>
            ))}
          </div>
        </div>

        <div className="auth-foot">© {new Date().getFullYear()} CDC Console · Secure migration control plane</div>
      </aside>

      <main className="auth-form-side">
        <div className="auth-card">
          <h2 className="auth-welcome">Welcome back</h2>
          <p style={{ color: 'var(--muted)', margin: '0 0 22px' }}>Sign in to your migration console.</p>

          <Form layout="vertical" onFinish={onFinish} requiredMark={false} size="large">
            <Form.Item name="username" label="Username" rules={[{ required: true, message: 'Enter your username' }]}>
              <Input prefix={<UserOutlined style={{ color: '#9AA3B2' }} />} placeholder="admin" autoFocus />
            </Form.Item>
            <Form.Item name="password" label="Password" rules={[{ required: true, message: 'Enter your password' }]}>
              <Input.Password prefix={<LockOutlined style={{ color: '#9AA3B2' }} />} placeholder="••••••••" />
            </Form.Item>
            <Button type="primary" htmlType="submit" block loading={loading} style={{ marginTop: 6, height: 44, fontWeight: 600 }}>
              Sign in
            </Button>
          </Form>

          <div className="auth-hint">Demo credentials: <code>admin</code> / <code>admin</code></div>
        </div>
      </main>
    </div>
  );
}
