import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import McpServerHealthPanel from '../components/McpServerHealthPanel';
import { useDashboardStore } from '../store';

function mcpRow(key: string, host: string, error?: { code: number; message: string }) {
  const response: Record<string, unknown> = { jsonrpc: '2.0', id: 1 };
  if (error) response['error'] = error;
  else response['result'] = { ok: true };
  return {
    key,
    value: {
      httpRequest: {
        method: 'POST',
        path: '/mcp',
        headers: [{ name: 'host', values: [host] }],
        body: { type: 'JSON', json: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: {} }) },
      },
      httpResponse: { statusCode: 200, body: { type: 'JSON', json: JSON.stringify(response) } },
    },
  };
}

describe('McpServerHealthPanel', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
      recordedRequests: [mcpRow('a', 'tools.local'), mcpRow('b', 'tools.local', { code: -32601, message: 'Method not found' })],
    });
  });

  it('has no Calls column, since a count over the capped live window would pin at the cap', () => {
    render(<McpServerHealthPanel />);
    const table = screen.getByTestId('mcp-health-table');
    const headers = within(table).getAllByRole('columnheader').map((h) => h.textContent);
    expect(headers).toEqual(['Server', 'Errors', 'Median', 'p95', 'Max', 'Slowest method']);
    const cells = within(within(table).getAllByRole('row')[1]!).getAllByRole('cell');
    expect(cells).toHaveLength(headers.length);
    expect(cells[1]).toHaveTextContent('50%');
  });
});
