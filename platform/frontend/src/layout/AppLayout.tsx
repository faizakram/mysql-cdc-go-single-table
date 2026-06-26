import { Layout, Menu, Typography, Space, Dropdown, Avatar, Tag, Badge } from 'antd';
import {
  DashboardOutlined, DatabaseOutlined, ProjectOutlined, UserOutlined, LogoutOutlined, TeamOutlined,
  BellOutlined, AreaChartOutlined, AuditOutlined,
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useAuth } from '../auth/AuthContext';
import { alertsApi } from '../api/client';

const { Header, Sider, Content } = Layout;

export default function AppLayout({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useAuth();
  const firing = useQuery({ queryKey: ['alerts-count'], queryFn: alertsApi.count, refetchInterval: 15000 });
  // Grafana dashboards (#51) run alongside the platform; link out to them.
  const grafanaUrl = (import.meta.env.VITE_GRAFANA_URL as string) || 'http://localhost:3001';

  const items = [
    { key: '/', icon: <DashboardOutlined />, label: 'Dashboard' },
    { key: '/projects', icon: <ProjectOutlined />, label: 'Projects' },
    { key: '/connections', icon: <DatabaseOutlined />, label: 'Connections' },
    {
      key: '/alerts', icon: <BellOutlined />,
      label: <Space>Alerts<Badge count={firing.data?.firing ?? 0} size="small" /></Space>,
    },
    ...(user?.role === 'ADMIN' ? [
      { key: '/users', icon: <TeamOutlined />, label: 'Users' },
      { key: '/audit', icon: <AuditOutlined />, label: 'Audit log' },
    ] : []),
    { key: 'grafana', icon: <AreaChartOutlined />, label: 'Grafana ↗' },
  ];
  const selected = items.find((i) => i.key !== '/' && location.pathname.startsWith(i.key))?.key
    ?? '/';

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider breakpoint="lg" collapsedWidth="0">
        <div style={{ height: 56, margin: 16, color: '#fff', display: 'flex', alignItems: 'center' }}>
          <Typography.Text strong style={{ color: '#fff', fontSize: 16 }}>
            CDC Migration
          </Typography.Text>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selected]}
          items={items}
          onClick={({ key }) => (key === 'grafana' ? window.open(grafanaUrl, '_blank', 'noopener') : navigate(key))}
        />
      </Sider>
      <Layout>
        <Header style={{
          background: '#fff', paddingInline: 24, display: 'flex',
          alignItems: 'center', justifyContent: 'space-between',
        }}>
          <Typography.Title level={4} style={{ margin: 0 }}>
            Heterogeneous Database Migration (CDC)
          </Typography.Title>
          <Dropdown
            menu={{ items: [{ key: 'logout', icon: <LogoutOutlined />, label: 'Sign out', onClick: logout }] }}
          >
            <Space style={{ cursor: 'pointer' }}>
              <Avatar size="small" icon={<UserOutlined />} />
              <span>{user?.username}</span>
              <Tag color="blue">{user?.role}</Tag>
            </Space>
          </Dropdown>
        </Header>
        <Content style={{ margin: 24 }}>{children}</Content>
      </Layout>
    </Layout>
  );
}
