/**
 * The composer's existing-mocks list is fed from the dashboard's capped live
 * window. Once the server holds more expectations than that window, the list
 * says so and a search reaches every expectation on the server, so a mock past
 * the window can still be found and loaded for editing.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ComposerView from '../components/ComposerView';
import { useDashboardStore } from '../store';
import type { JsonListItem } from '../types';

vi.mock('../lib/mcpClient', () => ({
  buildBaseUrl: () => 'http://127.0.0.1:1080',
  callMcpTool: vi.fn().mockResolvedValue({ ok: true, result: { tools: [], count: 0 } }),
}));

vi.mock('../lib/conversationCodegen', () => ({
  listConversationScenarios: () => [],
}));

const params = { host: '127.0.0.1', port: '1080', secure: false };

function expectation(i: number): Record<string, unknown> {
  const n = String(i).padStart(3, '0');
  return { id: `id-${n}`, httpRequest: { method: 'GET', path: `/many/${n}` }, httpResponse: { statusCode: 200 + (i % 100) } };
}

const ALL = Array.from({ length: 120 }, (_, i) => expectation(i));
const WINDOW: JsonListItem[] = ALL.slice(0, 100).map((value) => ({ key: value['id'] as string, value }));

function renderComposer() {
  globalThis.sessionStorage?.setItem('mockserver-composer-mode', 'advanced');
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <ComposerView connectionParams={params} />
    </ThemeProvider>,
  );
}

describe('Composer existing-mocks list past the live window', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ALL });
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('says the list is a window of the server total, and a search finds and loads a mock beyond it', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({ activeExpectations: WINDOW, activeExpectationsTotal: 120 });
    renderComposer();
    const list = screen.getByTestId('existing-mocks-list');
    expect(within(list).getByTestId('existing-mocks-window-notice')).toHaveTextContent(
      'This list holds the 100 expectations the dashboard keeps live, of 120 on the server.',
    );
    expect(within(list).queryByText(/\/many\/119/)).not.toBeInTheDocument();

    await user.type(within(list).getByLabelText('Search by path or id'), '/many/119');
    const row = await within(list).findByRole('button', { name: /\/many\/119/ }, { timeout: 3000 });
    expect(fetchMock).toHaveBeenCalledWith(
      'http://127.0.0.1:1080/mockserver/retrieve?type=active_expectations&format=json',
      expect.objectContaining({ method: 'PUT' }),
    );
    expect(within(list).getAllByRole('button', { name: /\/many\// })).toHaveLength(1);

    await user.click(row);
    expect(screen.getByLabelText('Expectation ID (optional)')).toHaveValue('id-119');
    expect(screen.getByLabelText('Path')).toHaveValue('/many/119');
    expect(screen.getByLabelText('Status code')).toHaveValue(219);
  });

  it('filters the window locally, without asking the server, while everything fits in it', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({ activeExpectations: WINDOW.slice(0, 5), activeExpectationsTotal: 5 });
    renderComposer();
    const list = screen.getByTestId('existing-mocks-list');
    expect(within(list).queryByTestId('existing-mocks-window-notice')).not.toBeInTheDocument();

    await user.type(within(list).getByLabelText('Search by path or id'), 'id-003');
    expect(within(list).getAllByRole('button', { name: /\/many\// }).map((b) => b.textContent)).toEqual([
      expect.stringContaining('/many/003'),
    ]);
    await user.clear(within(list).getByLabelText('Search by path or id'));
    await user.type(within(list).getByLabelText('Search by path or id'), '/nothing');
    expect(within(list).getByText('No HTTP mocks match “/nothing”.')).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalledWith(expect.stringContaining('/mockserver/retrieve'), expect.anything());
  });
});
