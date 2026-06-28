import { Routes, Route, Navigate } from 'react-router-dom';
import { Spin } from 'antd';
import { lazy, Suspense } from 'react';
import AppLayout from './layout/AppLayout';
import Login from './pages/Login';
import { useAuth } from './auth/AuthContext';

// Route-level code-splitting (#128): each page ships as its own chunk, loaded on demand.
const Dashboard = lazy(() => import('./pages/Dashboard'));
const LiveStream = lazy(() => import('./pages/LiveStream'));
const Projects = lazy(() => import('./pages/Projects'));
const Connections = lazy(() => import('./pages/Connections'));
const Alerts = lazy(() => import('./pages/Alerts'));
const Users = lazy(() => import('./pages/Users'));
const Audit = lazy(() => import('./pages/Audit'));
const Plugins = lazy(() => import('./pages/Plugins'));

const PageFallback = () => (
  <div style={{ display: 'grid', placeItems: 'center', padding: '64px 0' }}><Spin /></div>
);

export default function App() {
  const { user, ready } = useAuth();

  if (!ready) {
    return <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center' }}><Spin size="large" /></div>;
  }

  if (!user) {
    return (
      <Routes>
        <Route path="*" element={<Login />} />
      </Routes>
    );
  }

  return (
    <AppLayout>
      <Suspense fallback={<PageFallback />}>
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/live" element={<LiveStream />} />
          <Route path="/projects" element={<Projects />} />
          <Route path="/connections" element={<Connections />} />
          <Route path="/alerts" element={<Alerts />} />
          <Route path="/users" element={user.role === 'ADMIN' ? <Users /> : <Navigate to="/" replace />} />
          <Route path="/audit" element={user.role === 'ADMIN' ? <Audit /> : <Navigate to="/" replace />} />
          <Route path="/plugins" element={user.role === 'ADMIN' ? <Plugins /> : <Navigate to="/" replace />} />
          <Route path="/login" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </AppLayout>
  );
}
