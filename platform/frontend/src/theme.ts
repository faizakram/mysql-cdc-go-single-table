import type { ThemeConfig } from 'antd';

/**
 * Design system for the migration console — "a control room for data in motion".
 * Confident indigo accent, cool-slate neutrals, semantic colors kept distinct from the accent,
 * generous radii and hairline borders. Tokens here drive the whole app via ConfigProvider.
 */
const SLATE_900 = '#0F172A';   // sider / deep chrome
const INDIGO = '#4F46E5';      // primary accent

export const brand = {
  slate900: SLATE_900,
  indigo: INDIGO,
  indigoSoft: 'rgba(99, 102, 241, 0.16)',
};

export const theme: ThemeConfig = {
  token: {
    colorPrimary: INDIGO,
    colorInfo: INDIGO,
    colorSuccess: '#16A34A',
    colorWarning: '#D97706',
    colorError: '#DC2626',
    colorLink: INDIGO,

    colorTextBase: '#1E2430',
    colorBgLayout: '#F6F7F9',
    colorBorderSecondary: '#EBEDF2',
    colorBorder: '#E2E6EE',

    borderRadius: 8,
    borderRadiusLG: 12,
    fontSize: 14,
    fontFamily:
      '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, ' +
      '"Apple Color Emoji", "Segoe UI Emoji", sans-serif',
    controlHeight: 36,
    wireframe: false,
  },
  components: {
    Layout: {
      headerBg: '#FFFFFF',
      siderBg: SLATE_900,
      bodyBg: '#F6F7F9',
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
    Card: {
      borderRadiusLG: 12,
      paddingLG: 20,
    },
    Table: {
      headerBg: '#FAFBFC',
      headerColor: '#5B6472',
      borderColor: '#EEF0F4',
      rowHoverBg: '#F7F8FB',
      cellPaddingBlock: 12,
    },
    Button: {
      controlHeight: 36,
      fontWeight: 500,
      primaryShadow: '0 1px 2px rgba(79, 70, 229, 0.18)',
    },
    Drawer: {
      paddingLG: 24,
    },
    Statistic: {
      contentFontSize: 28,
    },
  },
};
