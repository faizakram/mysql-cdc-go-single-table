import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ConfigProvider } from 'antd';
import { buildTheme, type ThemeMode } from '../theme';

interface ThemeModeState {
  mode: ThemeMode;
  toggle: () => void;
  setMode: (m: ThemeMode) => void;
}

const Ctx = createContext<ThemeModeState | undefined>(undefined);
const STORAGE_KEY = 'cdc-theme-mode';

function initialMode(): ThemeMode {
  const saved = localStorage.getItem(STORAGE_KEY);
  if (saved === 'light' || saved === 'dark') return saved;
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/** Provides light/dark theming: persists the choice, drives ConfigProvider + a `data-theme` attr. */
export function ThemeModeProvider({ children }: { children: ReactNode }) {
  const [mode, setMode] = useState<ThemeMode>(initialMode);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, mode);
    document.documentElement.dataset.theme = mode;
  }, [mode]);

  const value = useMemo<ThemeModeState>(() => ({
    mode,
    setMode,
    toggle: () => setMode((m) => (m === 'dark' ? 'light' : 'dark')),
  }), [mode]);

  return (
    <Ctx.Provider value={value}>
      <ConfigProvider theme={buildTheme(mode)}>{children}</ConfigProvider>
    </Ctx.Provider>
  );
}

export function useThemeMode() {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useThemeMode must be used within ThemeModeProvider');
  return ctx;
}
