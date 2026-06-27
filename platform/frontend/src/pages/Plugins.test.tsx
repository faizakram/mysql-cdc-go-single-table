import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import Plugins from './Plugins';
import { pluginsApi } from '../api/client';

vi.mock('../api/client', () => ({
  pluginsApi: { list: vi.fn() },
}));

const wrap = (ui: ReactNode) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
};

describe('Plugins page', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lists loaded extensions returned by the API', async () => {
    vi.mocked(pluginsApi.list).mockResolvedValue([
      { id: 'debezium-sqlserver', kind: 'SOURCE_CONNECTOR', version: '2.5', detail: 'CDC source' },
      { id: 'pg-dialect', kind: 'SINK_DIALECT', version: '1.0', detail: 'PostgreSQL sink' },
    ]);
    wrap(<Plugins />);
    expect(await screen.findByText('debezium-sqlserver')).toBeInTheDocument();
    expect(screen.getByText('pg-dialect')).toBeInTheDocument();
    expect(screen.getByText('SOURCE_CONNECTOR')).toBeInTheDocument();
  });

  it('shows an empty state when no plugins are loaded', async () => {
    vi.mocked(pluginsApi.list).mockResolvedValue([]);
    wrap(<Plugins />);
    expect(await screen.findByText('No plugins loaded')).toBeInTheDocument();
  });
});
