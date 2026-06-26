import { Routes, Route, Navigate } from 'react-router-dom';
import { Spin } from 'antd';
import AppLayout from './layout/AppLayout';
import Dashboard from './pages/Dashboard';
import Projects from './pages/Projects';
import Connections from './pages/Connections';
import Alerts from './pages/Alerts';
import Users from './pages/Users';
import Audit from './pages/Audit';
import Login from './pages/Login';
import { useAuth } from './auth/AuthContext';

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
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/projects" element={<Projects />} />
        <Route path="/connections" element={<Connections />} />
        <Route path="/alerts" element={<Alerts />} />
        <Route path="/users" element={user.role === 'ADMIN' ? <Users /> : <Navigate to="/" replace />} />
        <Route path="/audit" element={user.role === 'ADMIN' ? <Audit /> : <Navigate to="/" replace />} />
        <Route path="/login" element={<Navigate to="/" replace />} />
      </Routes>
    </AppLayout>
  );
}
