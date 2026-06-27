import { theme as antdTheme, type ThemeConfig } from 'antd';

/**
 * Design system for the migration console — "a control room for data in motion".
 * Confident indigo accent, cool-slate neutrals, semantic colors kept distinct from the accent,
 * generous radii and hairline borders. Supports light + dark via Ant's algorithms.
 */
const SLATE_900 = '#0F172A';
const INDIGO = '#4F46E5';

export const brand = {
  slate900: SLATE_900,
  indigo: INDIGO,
  indigoSoft: 'rgba(99, 102, 241, 0.16)',
};

const FONT =
  '"Inter Variable", "Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, ' +
  '"Helvetica Neue", Arial, "Apple Color Emoji", "Segoe UI Emoji", sans-serif';

export type ThemeMode = 'light' | 'dark';
export type Density = 'comfortable' | 'compact';

export function buildTheme(mode: ThemeMode, density: Density = 'comfortable'): ThemeConfig {
  const dark = mode === 'dark';
  const base = dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm;
  return {
    algorithm: density === 'compact' ? [base, antdTheme.compactAlgorithm] : base,
    token: {
      colorPrimary: INDIGO,
      colorInfo: INDIGO,
      colorSuccess: '#16A34A',
      colorWarning: '#D97706',
      colorError: '#DC2626',
      colorLink: dark ? '#818CF8' : INDIGO,

      colorBgLayout: dark ? '#0B1120' : '#F6F7F9',
      colorBorderSecondary: dark ? 'rgba(255,255,255,0.08)' : '#EBEDF2',
      colorBorder: dark ? 'rgba(255,255,255,0.12)' : '#E2E6EE',

      borderRadius: 8,
      borderRadiusLG: 12,
      fontSize: 14,
      fontFamily: FONT,
      controlHeight: 36,
      wireframe: false,
    },
    components: {
      Layout: {
        headerBg: dark ? '#111827' : '#FFFFFF',
        siderBg: SLATE_900,
        bodyBg: dark ? '#0B1120' : '#F6F7F9',
        headerHeight: 60,
        headerPadding: '0 16px',
      },
      Menu: {
        darkItemBg: 'transparent',
        darkSubMenuItemBg: 'transparent',
        darkPopupBg: SLATE_900,
        darkItemColor: 'rgba(226, 232, 240, 0.72)',
        darkItemHoverColor: '#FFFFFF',
        darkItemHoverBg: 'rgba(148, 163, 184, 0.12)',
        darkItemSelectedBg: brand.indigoSoft,
        darkItemSelectedColor: '#FFFFFF',
        itemBorderRadius: 8,
        itemMarginInline: 10,
        itemHeight: 42,
      },
      Card: { borderRadiusLG: 12, paddingLG: 20 },
      Table: {
        headerBg: dark ? '#141B27' : '#FAFBFC',
        headerColor: dark ? '#94A3B8' : '#5B6472',
        rowHoverBg: dark ? 'rgba(255,255,255,0.04)' : '#F7F8FB',
        cellPaddingBlock: 12,
      },
      Button: { controlHeight: 36, fontWeight: 500, primaryShadow: '0 1px 2px rgba(79, 70, 229, 0.18)' },
      Drawer: { paddingLG: 24 },
      Statistic: { contentFontSize: 28 },
    },
  };
}
