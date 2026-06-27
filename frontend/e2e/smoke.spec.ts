import { test, expect, type Route } from '@playwright/test';

/**
 * Critical-path smoke: login → dashboard → create project → dry-run.
 * The backend is mocked at the network layer, so this runs anywhere without databases or Kafka.
 */

const PROJECT_ID = '11111111-1111-1111-1111-111111111111';

function installApiMock(routeFn: (handler: (route: Route) => Promise<void>) => Promise<void>) {
  // mutable server state so the projects list reflects the create
  const projects: Array<Record<string, unknown>> = [];

  return routeFn(async (route) => {
    const req = route.request();
    const method = req.method();
    const path = new URL(req.url()).pathname.replace('/api/v1', '');
    const json = (body: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

    if (method === 'POST' && path === '/auth/login') {
      return json({ token: 'test-token', username: 'admin', role: 'ADMIN', expiresInMinutes: 480 });
    }
    if (path === '/auth/me') return json({ username: 'admin', role: 'ADMIN' });
    if (path === '/auth/refresh') {
      return json({ token: 'test-token', username: 'admin', role: 'ADMIN', expiresInMinutes: 480 });
    }
    if (path === '/alerts/count') return json({ firing: 0 });
    if (path === '/alerts') return json([]);
    if (path === '/monitoring/overview') return json([]);
    if (path === '/orchestrator/status') {
      return json({ maxConcurrent: 2, running: 0, queued: 0, runningTasks: [], queuedTasks: [] });
    }
    if (path === '/connections') {
      const content = [
        { id: 'src', name: 'MSSQL', dbType: 'SQLSERVER', host: 'mssql', port: 1433, databaseName: 'app', username: 'sa', options: {}, createdAt: '', updatedAt: '' },
        { id: 'tgt', name: 'PG', dbType: 'POSTGRESQL', host: 'pg', port: 5432, databaseName: 'app', username: 'pg', options: {}, createdAt: '', updatedAt: '' },
      ];
      return json({ content, page: 0, size: content.length, total: content.length });
    }
    if (method === 'POST' && path === '/projects') {
      const body = req.postDataJSON() as { name: string; description?: string };
      const project = {
        id: PROJECT_ID, name: body.name, description: body.description ?? '',
        status: 'DRAFT', config: {}, createdAt: '', updatedAt: '',
      };
      projects.push(project);
      return json(project, 201);
    }
    if (method === 'GET' && path === '/projects') {
      return json({ content: projects, page: 0, size: 20, total: projects.length });
    }
    if (method === 'POST' && path === `/projects/${PROJECT_ID}/dry-run`) {
      return json({
        ok: true,
        source: { success: true, message: 'connected' },
        target: { success: true, message: 'connected' },
        plan: { tables: [{ fqName: 'dbo.Employees', rowCount: 10, bytes: 1024, level: 0, hasPk: true, risks: [] }], levels: 1, hasCycles: false, totalRows: 10, totalBytes: 1024, estimatedSeconds: 42, risks: [] },
        blockers: [],
        warnings: [],
      });
    }
    // sensible default for any other read
    return json(method === 'GET' ? [] : {});
  });
}

test('login → dashboard → create project → dry-run', async ({ page }) => {
  await installApiMock((h) => page.route('**/api/v1/**', h));

  // 1. Login
  await page.goto('/');
  await page.getByPlaceholder('admin').fill('admin');
  await page.getByPlaceholder('••••••••').fill('admin');
  await page.getByRole('button', { name: 'Sign in' }).click();

  // 2. Dashboard
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();

  // 3. Projects → create
  await page.getByRole('menuitem', { name: 'Projects' }).click();
  await expect(page.getByRole('heading', { name: 'Projects', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'New project' }).click();
  await page.getByPlaceholder('Employees MSSQL → PG').fill('Smoke Test Project');
  await page.getByRole('button', { name: 'OK' }).click();
  await expect(page.getByRole('cell', { name: 'Smoke Test Project' })).toBeVisible();

  // 4. Dry-run
  await page.getByRole('button', { name: 'Dry-run' }).click();
  await expect(page.getByText(/1 tables, ~42s/)).toBeVisible();
});
