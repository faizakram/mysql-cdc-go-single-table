import { defineConfig, devices } from '@playwright/test';

/**
 * E2E smoke. The SPA is served from a production `vite preview` build; all `/api/v1/*` calls are
 * mocked in-test (page.route) so the smoke needs no backend, Kafka or databases — it verifies the
 * UI wiring end to end: login → dashboard → create project → dry-run.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['list']] : 'list',
  use: {
    baseURL: 'http://localhost:4173',
    trace: 'on-first-retry',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    command: 'npm run build && npm run preview -- --port 4173',
    url: 'http://localhost:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
