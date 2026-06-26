import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ConfigProvider } from 'antd';
import { buildTheme, type Density, type ThemeMode } from '../theme';

interface ThemeModeState {
  mode: ThemeMode;
  density: Density;
  toggle: () => void;
  setMode: (m: ThemeMode) => void;
  toggleDensity: () => void;
}

const Ctx = createContext<ThemeModeState | undefined>(undefined);
const MODE_KEY = 'cdc-theme-mode';
const DENSITY_KEY = 'cdc-theme-density';

function initialMode(): ThemeMode {
  const saved = localStorage.getItem(MODE_KEY);
  if (saved === 'light' || saved === 'dark') return saved;
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}
function initialDensity(): Density {
  return localStorage.getItem(DENSITY_KEY) === 'compact' ? 'compact' : 'comfortable';
}

/** Provides light/dark + density theming: persists choices, drives ConfigProvider + a data-theme attr. */
export function ThemeModeProvider({ children }: { children: ReactNode }) {
  const [mode, setMode] = useState<ThemeMode>(initialMode);
  const [density, setDensity] = useState<Density>(initialDensity);

  useEffect(() => {
    localStorage.setItem(MODE_KEY, mode);
    document.documentElement.dataset.theme = mode;
  }, [mode]);
  useEffect(() => { localStorage.setItem(DENSITY_KEY, density); }, [density]);

  const value = useMemo<ThemeModeState>(() => ({
    mode,
    density,
    setMode,
    toggle: () => setMode((m) => (m === 'dark' ? 'light' : 'dark')),
    toggleDensity: () => setDensity((d) => (d === 'compact' ? 'comfortable' : 'compact')),
  }), [mode, density]);

  return (
    <Ctx.Provider value={value}>
      <ConfigProvider theme={buildTheme(mode, density)}>{children}</ConfigProvider>
    </Ctx.Provider>
  );
}

export function useThemeMode() {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useThemeMode must be used within ThemeModeProvider');
  return ctx;
}
