import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { authApi } from '../api/client';
import { tokenStore } from './token';

interface AuthUser { username: string; role: string; }

interface AuthState {
  user: AuthUser | null;
  ready: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthState | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (!tokenStore.get()) { setReady(true); return; }
    authApi.me()
      .then((me) => setUser({ username: me.username, role: me.role }))
      .catch(() => { tokenStore.clear(); setUser(null); })
      .finally(() => setReady(true));
  }, []);

  // Silent token refresh (#55): while logged in, swap the token for a fresh one periodically so an
  // active session never expires mid-use. On failure the next API 401 bounces to login.
  useEffect(() => {
    if (!user) return;
    const id = window.setInterval(() => {
      if (!tokenStore.get()) return;
      authApi.refresh().then((res) => tokenStore.set(res.token)).catch(() => { /* 401 handler bounces */ });
    }, 15 * 60 * 1000); // every 15 min (token TTL is 8h)
    return () => window.clearInterval(id);
  }, [user]);

  const login = async (username: string, password: string) => {
    const res = await authApi.login(username, password);
    tokenStore.set(res.token);
    setUser({ username: res.username, role: res.role });
  };

  const logout = () => {
    tokenStore.clear();
    setUser(null);
    window.location.assign('/login');
  };

  return <AuthContext.Provider value={{ user, ready, login, logout }}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
