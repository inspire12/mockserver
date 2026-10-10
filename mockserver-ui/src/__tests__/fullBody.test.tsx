import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { fetchFullMessage, withFullMessages, hasTruncatedBodies, formatCharacters } from '../lib/fullBody';
import { useDashboardStore } from '../store';
import TruncatedBodyNotice from '../components/TruncatedBodyNotice';
import type { JsonListItem, TruncatedBody, WebSocketMessage } from '../types';

const params = { host: 'localhost', port: '1080', secure: false, basePath: '' };
const requestMarker: TruncatedBody = { logEntryId: 'entry-1', part: 'request', originalLength: 1048576, shownLength: 65536 };
const responseMarker: TruncatedBody = { logEntryId: 'entry-2', part: 'response', originalLength: 2097152, shownLength: 65536 };

function row(key: string, truncated = true): JsonListItem {
  return {
    key,
    value: { httpRequest: { path: '/big', body: 'short' }, httpResponse: { statusCode: 200, body: 'short' } },
    ...(truncated ? { truncatedBodies: { httpRequest: requestMarker, httpResponse: responseMarker } } : {}),
  };
}

function message(rows: JsonListItem[], frameLimitReached?: boolean): WebSocketMessage {
  return { logMessages: [], activeExpectations: [], recordedRequests: rows, proxiedRequests: [], frameLimitReached };
}

describe('loading shortened bodies', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('asks for one log entry part and returns that message', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ httpRequest: { path: '/big', body: 'the whole body' } }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const full = await fetchFullMessage(params, requestMarker);

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:1080/mockserver/logEntryBody?id=entry-1&part=request', { signal: undefined });
    expect(full).toEqual({ path: '/big', body: 'the whole body' });
  });

  it("reports the server's error, for example an evicted entry", async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      statusText: 'Not Found',
      json: async () => ({ error: 'no request for log entry entry-1; it may have been cleared or evicted' }),
    }));

    await expect(fetchFullMessage(params, requestMarker)).rejects.toThrow('may have been cleared or evicted');
  });

  it('does not try to load an argument that is not the entry\'s own message', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchFullMessage(params, { ...requestMarker, loadable: false })).rejects.toThrow('cannot be loaded');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('does not try to load a shortened expectation', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchFullMessage(params, { ...requestMarker, part: 'expectation' })).rejects.toThrow('cannot be loaded');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('puts loaded messages in place and drops only their markers', () => {
    const half = withFullMessages(row('a'), { httpRequest: { path: '/big', body: 'whole request' } });

    expect(half.value.httpRequest).toEqual({ path: '/big', body: 'whole request' });
    expect(half.truncatedBodies).toEqual({ httpResponse: responseMarker });
    expect(hasTruncatedBodies(half)).toBe(true);

    const whole = withFullMessages(half, { httpResponse: { statusCode: 200, body: 'whole response' } });
    expect(whole.truncatedBodies).toBeUndefined();
    expect(hasTruncatedBodies(whole)).toBe(false);
  });

  it('formats lengths for the notice', () => {
    expect(formatCharacters(65536)).toBe('64 KiB');
    expect(formatCharacters(1048576)).toBe('1.0 MiB');
    expect(formatCharacters(12)).toBe('12 characters');
  });
});

describe('store: shortened rows and the update size limit', () => {
  beforeEach(() => {
    useDashboardStore.setState({ recordedRequests: [], proxiedRequests: [], logMessages: [], frameLimitReached: false, fullMessages: {} });
  });

  it('records whether the update reached its size limit', () => {
    useDashboardStore.getState().applyMessage(message([row('a')], true));
    expect(useDashboardStore.getState().frameLimitReached).toBe(true);

    useDashboardStore.getState().applyMessage(message([row('a')]));
    expect(useDashboardStore.getState().frameLimitReached).toBe(false);
  });

  it('records separately whether older log messages were left out', () => {
    useDashboardStore.getState().applyMessage({ ...message([row('a')]), logMessagesLimitReached: true });
    expect(useDashboardStore.getState().logMessagesLimitReached).toBe(true);
    expect(useDashboardStore.getState().frameLimitReached).toBe(false);

    useDashboardStore.getState().applyMessage(message([row('a')]));
    expect(useDashboardStore.getState().logMessagesLimitReached).toBe(false);
  });

  it('keeps showing a loaded body when later updates send the row shortened again', () => {
    const { applyMessage, applyFullMessages } = useDashboardStore.getState();
    applyMessage(message([row('a'), row('b')]));

    applyFullMessages('a', { httpRequest: { path: '/big', body: 'whole request' }, httpResponse: { statusCode: 200, body: 'whole response' } });
    expect(useDashboardStore.getState().recordedRequests[0]?.value.httpRequest).toEqual({ path: '/big', body: 'whole request' });

    applyMessage(message([row('a'), row('b')]));
    const [a, b] = useDashboardStore.getState().recordedRequests;
    expect(a?.value.httpResponse).toEqual({ statusCode: 200, body: 'whole response' });
    expect(a?.truncatedBodies).toBeUndefined();
    expect(b?.truncatedBodies).toBeDefined();
  });

  it('forgets loaded bodies once their row leaves the window', () => {
    const { applyMessage, applyFullMessages } = useDashboardStore.getState();
    applyMessage(message([row('a')]));
    applyFullMessages('a', { httpRequest: { body: 'whole' } });

    applyMessage(message([row('b')]));

    expect(useDashboardStore.getState().fullMessages).toEqual({});
  });
});

describe('TruncatedBodyNotice', () => {
  it('says how much is shown and loads the whole body on request', async () => {
    const onLoad = vi.fn().mockResolvedValue(undefined);
    render(<TruncatedBodyNotice marker={requestMarker} onLoad={onLoad} />);

    expect(screen.getByTestId('truncated-body-notice').textContent).toContain('showing the first 64 KiB of 1.0 MiB');
    fireEvent.click(screen.getByTestId('load-full-body'));

    await waitFor(() => expect(onLoad).toHaveBeenCalledTimes(1));
  });

  it('shows why loading failed', async () => {
    render(<TruncatedBodyNotice marker={requestMarker} onLoad={() => Promise.reject(new Error('it may have been cleared or evicted'))} />);

    fireEvent.click(screen.getByTestId('load-full-body'));

    expect(await screen.findByText(/cleared or evicted/)).toBeTruthy();
  });

  it('offers no load button for an argument that is not the entry\'s own message', () => {
    render(<TruncatedBodyNotice marker={{ ...requestMarker, loadable: false }} onLoad={vi.fn()} />);

    expect(screen.queryByTestId('load-full-body')).toBeNull();
  });

  it('offers no load button for a shortened expectation', () => {
    render(<TruncatedBodyNotice marker={{ ...requestMarker, part: 'expectation' }} onLoad={vi.fn()} />);

    expect(screen.queryByTestId('load-full-body')).toBeNull();
  });
});
