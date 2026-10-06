import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import TrafficInspector from '../components/TrafficInspector';
import { useDashboardStore } from '../store';
import type { JsonListItem } from '../types';

// Actions that send or match a row's request must never act on a body the server shortened: they load
// it whole first, and when that fails they do nothing and say why.

const shortenedRow: JsonListItem = {
  key: 'entry-req_request',
  value: {
    httpRequest: {
      method: 'POST',
      path: '/api/large',
      headers: [{ name: 'host', values: ['example.com'] }],
      body: 'first-64-KiB-only',
    },
    httpResponse: { statusCode: 200, body: 'short' },
  },
  truncatedBodies: {
    httpRequest: { logEntryId: 'entry-req', part: 'request', originalLength: 1048576, shownLength: 65536 },
  },
};

const fullRequest = {
  method: 'POST',
  path: '/api/large',
  headers: [{ name: 'host', values: ['example.com'] }],
  body: 'the-whole-body',
};

function evicted() {
  return {
    ok: false,
    status: 404,
    statusText: 'Not Found',
    json: async () => ({ error: 'no request for log entry entry-req; it may have been cleared or evicted' }),
    text: async () => '',
  };
}

function loaded() {
  return { ok: true, status: 200, json: async () => ({ httpRequest: fullRequest }), text: async () => '' };
}

function renderInspector() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <TrafficInspector />
    </ThemeProvider>,
  );
}

async function selectRow(user: ReturnType<typeof userEvent.setup>) {
  renderInspector();
  await user.click(screen.getByText(/\/api\/large/));
}

function calledUrls(fetchMock: ReturnType<typeof vi.fn>): string[] {
  return fetchMock.mock.calls.map((call) => String(call[0]));
}

describe('actions on a row whose body was shortened', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [shortenedRow],
      recordedRequests: [],
      activeExpectations: [],
      trafficSearch: '',
      selectedTrafficKey: null,
      fullMessages: {},
      notification: null,
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('Capture as mock does not open when the full body cannot be loaded, and says why', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(evicted()));
    await selectRow(user);

    await user.click(screen.getByRole('button', { name: /Capture as mock/i }));

    await waitFor(() => expect(useDashboardStore.getState().notification?.message).toContain('cleared or evicted'));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('Capture as mock opens on the whole body once it is loaded', async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn().mockResolvedValue(loaded());
    vi.stubGlobal('fetch', fetchMock);
    await selectRow(user);

    await user.click(screen.getByRole('button', { name: /Capture as mock/i }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Capture as Mock')).toBeInTheDocument();
    expect(calledUrls(fetchMock)).toContain('http://localhost:3000/mockserver/logEntryBody?id=entry-req&part=request');
    const stored = useDashboardStore.getState().proxiedRequests[0];
    expect(stored?.value.httpRequest).toEqual(fullRequest);
    expect(stored?.truncatedBodies).toBeUndefined();
  });

  it('Repeat does not open when the full body cannot be loaded, and says why', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(evicted()));
    await selectRow(user);

    await user.click(screen.getByRole('button', { name: /Repeat/i }));

    await waitFor(() => expect(useDashboardStore.getState().notification?.message).toContain('cleared or evicted'));
    expect(screen.queryByText('Repeat Request')).toBeNull();
  });

  it('Replay does not send a shortened request when the full body cannot be loaded', async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn().mockResolvedValue(evicted());
    vi.stubGlobal('fetch', fetchMock);
    await selectRow(user);

    await user.click(screen.getByRole('button', { name: /^Replay$/ }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: /^Replay$/ }));

    expect(await within(dialog).findByText(/cleared or evicted/)).toBeInTheDocument();
    expect(calledUrls(fetchMock).some((url) => url.includes('/mockserver/replay'))).toBe(false);
  });

  it('Copy as curl copies nothing when the full body cannot be loaded, and says why', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(evicted()));
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    await selectRow(user);

    await user.click(screen.getByRole('button', { name: /Copy as curl/i }));

    await waitFor(() => expect(useDashboardStore.getState().notification?.message).toContain('cleared or evicted'));
    expect(writeText).not.toHaveBeenCalled();
  });
});
