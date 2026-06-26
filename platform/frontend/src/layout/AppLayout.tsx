import { Layout, Menu, Space, Dropdown, Avatar, Tag, Badge, Button, Drawer, Grid } from 'antd';
import {
  DashboardOutlined, DatabaseOutlined, ProjectOutlined, UserOutlined, LogoutOutlined, TeamOutlined,
  BellOutlined, AreaChartOutlined, AuditOutlined, MenuOutlined, DeploymentUnitOutlined,
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useAuth } from '../auth/AuthContext';
import { alertsApi } from '../api/client';

const { Header, Sider, Content } = Layout;
const { useBreakpoint } = Grid;

function Brand({ subtitle }: { subtitle?: boolean }) {
  return (
    <div className="brand">
      <span className="brand-mark"><DeploymentUnitOutlined style={{ fontSize: 17 }} /></span>
      <div style={{ display: 'flex', flexDirection: 'column', lineHeight: 1.15 }}>
        <span className="brand-name">CDC Console</span>
        {subtitle && <span className="brand-sub">Migration control plane</span>}
      </div>
    </div>
  );
}

export default function AppLayout({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useAuth();
  const screens = useBreakpoint();
  const isMobile = !screens.lg;
  const [drawerOpen, setDrawerOpen] = useState(false);

  const firing = useQuery({ queryKey: ['alerts-count'], queryFn: alertsApi.count, refetchInterval: 15000 });
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
  const selected = items.find((i) => i.key !== '/' && location.pathname.startsWith(i.key))?.key ?? '/';

  const onMenuClick = (key: string) => {
    setDrawerOpen(false);
    if (key === 'grafana') window.open(grafanaUrl, '_blank', 'noopener');
    else navigate(key);
  };

  const menu = (
    <Menu
      theme="dark"
      mode="inline"
      selectedKeys={[selected]}
      items={items}
      onClick={({ key }) => onMenuClick(key)}
      style={{ background: 'transparent', borderInlineEnd: 'none' }}
    />
  );

  return (
    <Layout style={{ minHeight: '100vh' }}>
      {!isMobile && (
        <Sider breakpoint="lg" collapsedWidth="0" width={236} trigger={null}>
          <Brand subtitle />
          <div style={{ height: 8 }} />
          {menu}
        </Sider>
      )}

      {isMobile && (
        <Drawer
          placement="left"
          open={drawerOpen}
          onClose={() => setDrawerOpen(false)}
          width={264}
          closable={false}
          styles={{ body: { padding: 0, background: '#0F172A' }, header: { display: 'none' } }}
        >
          <Brand subtitle />
          <div style={{ height: 8 }} />
          {menu}
        </Drawer>
      )}

      <Layout>
        <Header style={{
          background: '#fff', display: 'flex', alignItems: 'center',
          justifyContent: 'space-between', gap: 12,
          borderBottom: '1px solid #EBEDF2', position: 'sticky', top: 0, zIndex: 10,
        }}>
          <Space size={12} style={{ minWidth: 0 }}>
            {isMobile && (
              <Button type="text" icon={<MenuOutlined />} onClick={() => setDrawerOpen(true)}
                aria-label="Open navigation" />
            )}
            <span className="app-title" style={{ fontSize: isMobile ? 15 : 17 }}>
              {isMobile ? 'CDC Console' : 'Heterogeneous Database Migration (CDC)'}
            </span>
          </Space>
          <Dropdown
            menu={{ items: [{ key: 'logout', icon: <LogoutOutlined />, label: 'Sign out', onClick: logout }] }}
          >
            <Space style={{ cursor: 'pointer' }}>
              <Avatar size="small" style={{ background: '#4F46E5' }} icon={<UserOutlined />} />
              {!isMobile && <span style={{ fontWeight: 500 }}>{user?.username}</span>}
              <Tag color="blue" style={{ marginInlineEnd: 0 }}>{user?.role}</Tag>
            </Space>
          </Dropdown>
        </Header>
        <Content className="app-content" style={{ margin: 24 }}>{children}</Content>
      </Layout>
    </Layout>
  );
}
