import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import TrafficInspector from '../components/TrafficInspector';
import LogEntry from '../components/LogEntry';
import SessionInspector from '../components/SessionInspector';
import McpServerHealthPanel from '../components/McpServerHealthPanel';
import OptimiseView from '../components/OptimiseView';
import { DebugMismatchContext } from '../hooks/DebugMismatchContext';
import { GenerateStubContext } from '../hooks/GenerateStubContext';
import { useDashboardStore } from '../store';
import { AUTO_LOAD_MAX_CHARACTERS } from '../hooks/useLoadFullRow';
import type { JsonListItem, LogEntryValue } from '../types';

// Compare, the Diff Pool, Create Mock, Why Didn't This Match?, Generate Stub and search must use a
// row's whole body, not the first 64 KiB the server sends; LLM views load LLM rows whole by themselves.

let entrySeq = 0;

function marker(logEntryId: string) {
  return { logEntryId, part: 'request' as const, originalLength: 1048576, shownLength: 65536 };
}

function shortenedRow(name: string, opts: { unmatched?: boolean; path?: string } = {}): { row: JsonListItem; full: Record<string, unknown>; id: string } {
  const id = `${name}-${++entrySeq}`;
  const path = opts.path ?? `/api/${name}`;
  const headers = [{ name: 'host', values: ['example.com'] }];
  return {
    id,
    row: {
      key: `${id}_request`,
      value: {
        httpRequest: { method: 'POST', path, headers, body: 'first-64-KiB-only' },
        httpResponse: opts.unmatched ? { statusCode: 404, reasonPhrase: 'Not Found' } : { statusCode: 200, body: 'short' },
      },
      truncatedBodies: { httpRequest: marker(id) },
    },
    full: { method: 'POST', path, headers, body: `whole-body-of-${name}` },
  };
}

function pathText(path: string): RegExp {
  return new RegExp(path.replace(/[/-]/g, '\\$&'));
}

type Loads = Record<string, Record<string, unknown>>;
interface FakeResponse {
  ok: boolean;
  status: number;
  statusText?: string;
  json: () => Promise<unknown>;
  text: () => Promise<string>;
}

function fetchServing(loads: Loads) {
  return vi.fn(async (input: unknown): Promise<FakeResponse> => {
    const url = String(input);
    const match = /logEntryBody\?id=([^&]+)/.exec(url);
    if (match) {
      const full = loads[decodeURIComponent(match[1]!)];
      if (!full) {
        return { ok: false, status: 404, statusText: 'Not Found', json: async () => ({ error: 'it may have been cleared or evicted' }), text: async () => '' };
      }
      return { ok: true, status: 200, json: async () => ({ httpRequest: full }), text: async () => '' };
    }
    if (url.includes('/mockserver/diff')) {
      const diff = { diffs: [], diffCount: 0, identical: true };
      return { ok: true, status: 200, json: async () => diff, text: async () => JSON.stringify(diff) };
    }
    return { ok: false, status: 404, statusText: 'Not Found', json: async () => ({}), text: async () => '' };
  });
}

function logEntryLoads(fetchMock: ReturnType<typeof vi.fn>): string[] {
  return fetchMock.mock.calls.map((call) => String(call[0])).filter((url) => url.includes('/mockserver/logEntryBody'));
}

function renderInspector(contexts: { debugMismatch?: (r: Record<string, unknown>) => Promise<void>; generateStub?: (r: Record<string, unknown>) => Promise<void> } = {}) {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <DebugMismatchContext.Provider value={contexts.debugMismatch ?? null}>
        <GenerateStubContext.Provider value={contexts.generateStub ?? null}>
          <TrafficInspector />
        </GenerateStubContext.Provider>
      </DebugMismatchContext.Provider>
    </ThemeProvider>,
  );
}

function setRows(proxied: JsonListItem[], recorded: JsonListItem[] = [], trafficSearch = '') {
  useDashboardStore.setState({
    proxiedRequests: proxied,
    recordedRequests: recorded,
    activeExpectations: [],
    trafficSearch,
    selectedTrafficKey: null,
    fullMessages: {},
    notification: null,
    pendingEditExpectation: null,
  });
}

describe('row actions on a shortened body', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('Why Didn’t This Match? matches the whole request', async () => {
    const user = userEvent.setup();
    const { row, full, id } = shortenedRow('why', { unmatched: true });
    setRows([], [row]);
    vi.stubGlobal('fetch', fetchServing({ [id]: full }));
    const debugMismatch = vi.fn().mockResolvedValue(undefined);
    renderInspector({ debugMismatch });

    await user.click(screen.getByText(pathText('/api/why')));
    await user.click(screen.getByRole('button', { name: /Why Didn't This Match/ }));

    await waitFor(() => expect(debugMismatch).toHaveBeenCalledWith(full));
  });

  it('Why Didn’t This Match? does nothing when the whole request cannot be loaded, and says why', async () => {
    const user = userEvent.setup();
    const { row } = shortenedRow('why-evicted', { unmatched: true });
    setRows([], [row]);
    vi.stubGlobal('fetch', fetchServing({}));
    const debugMismatch = vi.fn().mockResolvedValue(undefined);
    renderInspector({ debugMismatch });

    await user.click(screen.getByText(pathText('/api/why-evicted')));
    await user.click(screen.getByRole('button', { name: /Why Didn't This Match/ }));

    await waitFor(() => expect(useDashboardStore.getState().notification?.message).toContain('cleared or evicted'));
    expect(debugMismatch).not.toHaveBeenCalled();
  });

  it('Generate Stub builds from the whole request', async () => {
    const user = userEvent.setup();
    const { row, full, id } = shortenedRow('stub', { unmatched: true });
    setRows([], [row]);
    vi.stubGlobal('fetch', fetchServing({ [id]: full }));
    const generateStub = vi.fn().mockResolvedValue(undefined);
    renderInspector({ generateStub });

    await user.click(screen.getByText(pathText('/api/stub')));
    await user.click(screen.getByRole('button', { name: /Generate Stub/ }));

    await waitFor(() => expect(generateStub).toHaveBeenCalledWith(full));
  });

  it('Create Mock drafts from the whole request', async () => {
    const user = userEvent.setup();
    const { row, full, id } = shortenedRow('mock');
    setRows([row]);
    vi.stubGlobal('fetch', fetchServing({ [id]: full }));
    renderInspector();

    await user.click(screen.getByText(pathText('/api/mock')));
    await user.click(screen.getByRole('button', { name: /Create from this request/ }));
    await user.click(await screen.findByRole('menuitem', { name: /Create Mock/ }));

    await waitFor(() => expect(useDashboardStore.getState().pendingEditExpectation).not.toBeNull());
    expect(JSON.stringify(useDashboardStore.getState().pendingEditExpectation)).toContain('whole-body-of-mock');
  });

  it('Create Mock drafts nothing when the whole request cannot be loaded, and says why', async () => {
    const user = userEvent.setup();
    const { row } = shortenedRow('mock-evicted');
    setRows([row]);
    vi.stubGlobal('fetch', fetchServing({}));
    renderInspector();

    await user.click(screen.getByText(pathText('/api/mock-evicted')));
    await user.click(screen.getByRole('button', { name: /Create from this request/ }));
    await user.click(await screen.findByRole('menuitem', { name: /Create Mock/ }));

    await waitFor(() => expect(useDashboardStore.getState().notification?.message).toContain('cleared or evicted'));
    expect(useDashboardStore.getState().pendingEditExpectation).toBeNull();
  });

  it('Compare diffs the two whole requests', async () => {
    const user = userEvent.setup();
    const a = shortenedRow('left');
    const b = shortenedRow('right');
    setRows([a.row, b.row]);
    vi.stubGlobal('fetch', fetchServing({ [a.id]: a.full, [b.id]: b.full }));
    renderInspector();

    await user.click(screen.getByRole('button', { name: 'Compare requests' }));
    await user.click(screen.getByText(pathText('/api/left')));
    await user.click(screen.getByText(pathText('/api/right')));
    await user.click(screen.getByRole('button', { name: /Diff \(2\/2\)/ }));

    const dialog = await screen.findByRole('dialog');
    const expected = within(dialog).getByLabelText('Expected request (JSON)') as HTMLTextAreaElement;
    const actual = within(dialog).getByLabelText('Actual request (JSON)') as HTMLTextAreaElement;
    expect([expected.value, actual.value].join('\n')).toContain('whole-body-of-left');
    expect([expected.value, actual.value].join('\n')).toContain('whole-body-of-right');
    expect(expected.value + actual.value).not.toContain('first-64-KiB-only');
  });

  it('Compare opens no diff when a whole request cannot be loaded, and says why', async () => {
    const user = userEvent.setup();
    const a = shortenedRow('left-ok');
    const b = shortenedRow('right-evicted');
    setRows([a.row, b.row]);
    vi.stubGlobal('fetch', fetchServing({ [a.id]: a.full }));
    renderInspector();

    await user.click(screen.getByRole('button', { name: 'Compare requests' }));
    await user.click(screen.getByText(pathText('/api/left-ok')));
    await user.click(screen.getByText(pathText('/api/right-evicted')));
    await user.click(screen.getByRole('button', { name: /Diff \(2\/2\)/ }));

    await waitFor(() => expect(useDashboardStore.getState().notification?.message).toContain('cleared or evicted'));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('the Diff Pool keeps and diffs whole requests, and adds nothing when one cannot be loaded', async () => {
    const user = userEvent.setup();
    const first = shortenedRow('pooled-a');
    const second = shortenedRow('pooled-b');
    const gone = shortenedRow('pool-evicted');
    setRows([first.row, second.row, gone.row]);
    vi.stubGlobal('fetch', fetchServing({ [first.id]: first.full, [second.id]: second.full }));
    renderInspector();

    await user.click(screen.getByText(pathText('/api/pool-evicted')));
    await user.click(screen.getByRole('button', { name: 'Add to Diff Pool' }));
    await waitFor(() => expect(useDashboardStore.getState().notification?.message).toContain('cleared or evicted'));
    expect(screen.queryByRole('button', { name: /Diff Pool \(/ })).toBeNull();

    await user.click(screen.getByText(pathText('/api/pooled-a')));
    await user.click(screen.getByRole('button', { name: 'Add to Diff Pool' }));
    await user.click(screen.getByText(pathText('/api/pooled-b')));
    await user.click(screen.getByRole('button', { name: 'Add to Diff Pool' }));
    await user.click(await screen.findByRole('button', { name: 'Diff Pool (2)' }));
    await user.click(screen.getByRole('checkbox', { name: 'Pick POST /api/pooled-a' }));
    await user.click(screen.getByRole('checkbox', { name: 'Pick POST /api/pooled-b' }));
    await user.click(screen.getByRole('button', { name: 'Diff Selected' }));

    const dialog = await screen.findByRole('dialog', { name: /Diff/ });
    const expected = within(dialog).getByLabelText('Expected request (JSON)') as HTMLTextAreaElement;
    const actual = within(dialog).getByLabelText('Actual request (JSON)') as HTMLTextAreaElement;
    expect(expected.value).toContain('whole-body-of-pooled-a');
    expect(actual.value).toContain('whole-body-of-pooled-b');
  });
});

describe('search over shortened bodies', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('says the search covers only the shown part, and Search Full Bodies finds a match beyond it', async () => {
    const user = userEvent.setup();
    const { row, full, id } = shortenedRow('searched');
    setRows([row], [], 'whole-body-of-searched');
    const fetchMock = fetchServing({ [id]: full });
    vi.stubGlobal('fetch', fetchMock);
    renderInspector();

    expect(screen.queryByText(pathText('/api/searched'))).toBeNull();
    expect(screen.getByText(/1 request has a body/)).toBeInTheDocument();
    expect(logEntryLoads(fetchMock)).toEqual([]);

    await user.click(screen.getByRole('button', { name: 'Search Full Bodies' }));

    expect(await screen.findByText(pathText('/api/searched'))).toBeInTheDocument();
    expect(screen.queryByText(/request has a body/)).toBeNull();
  });

  it('says how many bodies could not be loaded', async () => {
    const user = userEvent.setup();
    const { row } = shortenedRow('search-evicted');
    setRows([row], [], 'anything');
    vi.stubGlobal('fetch', fetchServing({}));
    renderInspector();

    await user.click(screen.getByRole('button', { name: 'Search Full Bodies' }));

    await waitFor(() => expect(useDashboardStore.getState().notification?.message).toContain('1 of 1 full bodies could not be loaded'));
  });

  it('shows no note without a search', () => {
    const { row } = shortenedRow('unsearched');
    setRows([row]);
    vi.stubGlobal('fetch', fetchServing({}));
    renderInspector();

    expect(screen.queryByText(/request has a body/)).toBeNull();
  });
});

describe('LLM views load shortened LLM rows whole', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('Traffic loads a shortened LLM row once, without being asked, and leaves other rows shortened', async () => {
    const llm = shortenedRow('llm', { path: '/v1/messages' });
    const plain = shortenedRow('plain');
    setRows([llm.row, plain.row]);
    const fetchMock = fetchServing({ [llm.id]: llm.full, [plain.id]: plain.full });
    vi.stubGlobal('fetch', fetchMock);
    const view = renderInspector();

    await waitFor(() =>
      expect(useDashboardStore.getState().proxiedRequests.find((r) => r.key === llm.row.key)?.truncatedBodies).toBeUndefined(),
    );
    expect(logEntryLoads(fetchMock)).toEqual([`http://localhost:3000/mockserver/logEntryBody?id=${llm.id}&part=request`]);
    expect(useDashboardStore.getState().proxiedRequests.find((r) => r.key === plain.row.key)?.truncatedBodies).toBeDefined();

    // A later push carrying the same shortened row does not load it again.
    view.unmount();
    setRows([llm.row, plain.row]);
    renderInspector();
    await waitFor(() =>
      expect(useDashboardStore.getState().proxiedRequests.find((r) => r.key === llm.row.key)?.truncatedBodies).toBeDefined(),
    );
    expect(logEntryLoads(fetchMock)).toHaveLength(1);
  });

  it('leaves a shortened LLM row over the size cap for the user to load', async () => {
    const llm = shortenedRow('llm-huge', { path: '/v1/messages' });
    llm.row.truncatedBodies = { httpRequest: { ...marker(llm.id), originalLength: AUTO_LOAD_MAX_CHARACTERS + 1 } };
    const capped = shortenedRow('llm-at-cap', { path: '/v1/messages' });
    capped.row.truncatedBodies = { httpRequest: { ...marker(capped.id), originalLength: AUTO_LOAD_MAX_CHARACTERS } };
    setRows([llm.row, capped.row]);
    const fetchMock = fetchServing({ [llm.id]: llm.full, [capped.id]: capped.full });
    vi.stubGlobal('fetch', fetchMock);
    renderInspector();

    await waitFor(() =>
      expect(useDashboardStore.getState().proxiedRequests.find((r) => r.key === capped.row.key)?.truncatedBodies).toBeUndefined(),
    );
    expect(logEntryLoads(fetchMock)).toEqual([`http://localhost:3000/mockserver/logEntryBody?id=${capped.id}&part=request`]);
    expect(useDashboardStore.getState().proxiedRequests.find((r) => r.key === llm.row.key)?.truncatedBodies).toBeDefined();
  });

  it('a failed automatic load is not retried and raises no notification', async () => {
    const llm = shortenedRow('llm-evicted', { path: '/v1/chat/completions' });
    setRows([llm.row]);
    const fetchMock = fetchServing({});
    vi.stubGlobal('fetch', fetchMock);
    renderInspector();

    await waitFor(() => expect(logEntryLoads(fetchMock)).toHaveLength(1));
    await act(async () => {
      setRows([{ ...llm.row }]);
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
    expect(logEntryLoads(fetchMock)).toHaveLength(1);
    expect(useDashboardStore.getState().notification).toBeNull();
  });
});

describe.each([
  ['Sessions', () => <SessionInspector connectionParams={{ host: 'localhost', port: '3000', secure: false }} />],
  ['MCP Health', () => <McpServerHealthPanel />],
  ['Optimise', () => <OptimiseView connectionParams={{ host: 'localhost', port: '3000', secure: false }} />],
])('the %s view', (_name, view) => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('loads a shortened LLM row whole without being asked', async () => {
    const llm = shortenedRow('view-llm', { path: '/v1/messages' });
    setRows([llm.row]);
    const fetchMock = fetchServing({ [llm.id]: llm.full });
    vi.stubGlobal('fetch', fetchMock);
    render(<ThemeProvider theme={buildTheme('dark')}>{view()}</ThemeProvider>);

    await waitFor(() =>
      expect(useDashboardStore.getState().proxiedRequests.find((r) => r.key === llm.row.key)?.truncatedBodies).toBeUndefined(),
    );
    expect(logEntryLoads(fetchMock)).toHaveLength(1);
  });
});

describe('log row actions on a shortened request', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  function unmatchedLogEntry(id: string, loadable = true): LogEntryValue {
    return {
      description: '10:00:00 EXPECTATION_NOT_MATCHED',
      messageParts: [
        { key: 'msg_0', value: 'request:' },
        {
          key: 'msg_1',
          value: { method: 'POST', path: '/api/logged', body: 'first-64-KiB-only' },
          json: true,
          argument: true,
          truncatedBody: { ...marker(id), loadable },
        },
        { key: 'msg_2', value: ' did not match any expectation' },
      ],
    };
  }

  function renderLogEntry(entry: LogEntryValue, debugMismatch: (r: Record<string, unknown>) => Promise<void>, generateStub: (r: Record<string, unknown>) => Promise<void>) {
    return render(
      <DebugMismatchContext.Provider value={debugMismatch}>
        <GenerateStubContext.Provider value={generateStub}>
          <LogEntry entry={entry} collapsible />
        </GenerateStubContext.Provider>
      </DebugMismatchContext.Provider>,
    );
  }

  beforeEach(() => {
    useDashboardStore.setState({ notification: null, pendingEditExpectation: null });
  });

  it('Why? and Generate Stub load the whole request first', async () => {
    const user = userEvent.setup();
    const id = `log-${++entrySeq}`;
    const full = { method: 'POST', path: '/api/logged', body: 'whole-logged-body' };
    vi.stubGlobal('fetch', fetchServing({ [id]: full }));
    const debugMismatch = vi.fn().mockResolvedValue(undefined);
    const generateStub = vi.fn().mockResolvedValue(undefined);
    renderLogEntry(unmatchedLogEntry(id), debugMismatch, generateStub);

    await user.click(screen.getByTestId('HelpOutlinedIcon'));
    await user.click(screen.getByTestId('AutoFixHighIcon'));

    await waitFor(() => expect(debugMismatch).toHaveBeenCalledWith(full));
    await waitFor(() => expect(generateStub).toHaveBeenCalledWith(full));
  });

  it('Create Mock drafts from the whole request', async () => {
    const user = userEvent.setup();
    const id = `log-${++entrySeq}`;
    vi.stubGlobal('fetch', fetchServing({ [id]: { method: 'POST', path: '/api/logged', body: 'whole-logged-body' } }));
    renderLogEntry(unmatchedLogEntry(id), vi.fn(), vi.fn());

    await user.click(screen.getByRole('button', { name: /Create from this request/ }));
    await user.click(await screen.findByRole('menuitem', { name: /Create Mock/ }));

    await waitFor(() => expect(JSON.stringify(useDashboardStore.getState().pendingEditExpectation)).toContain('whole-logged-body'));
  });

  it('does nothing with a shortened request that cannot be loaded, and says why', async () => {
    const user = userEvent.setup();
    const id = `log-${++entrySeq}`;
    const fetchMock = fetchServing({ [id]: { method: 'POST', path: '/api/logged', body: 'whole-logged-body' } });
    vi.stubGlobal('fetch', fetchMock);
    const debugMismatch = vi.fn().mockResolvedValue(undefined);
    renderLogEntry(unmatchedLogEntry(id, false), debugMismatch, vi.fn());

    await user.click(screen.getByTestId('HelpOutlinedIcon'));

    await waitFor(() => expect(useDashboardStore.getState().notification?.message).toContain('cannot be loaded'));
    expect(debugMismatch).not.toHaveBeenCalled();
    expect(logEntryLoads(fetchMock)).toEqual([]);
  });
});
