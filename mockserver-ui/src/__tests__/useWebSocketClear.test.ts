import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useWebSocket } from '../hooks/useWebSocket';
import { useDashboardStore } from '../store';
import type { JsonListItem, LogMessage } from '../types';

const params = { host: 'localhost', port: '1080', secure: false };
const item = (key: string): JsonListItem => ({ key, value: { path: `/${key}` } });
const log: LogMessage = { key: 'l1', value: { messageParts: [{ key: 'm', value: 'x' }] } };

describe('useWebSocket clearServer', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, statusText: 'OK' });
    vi.stubGlobal('fetch', fetchMock);
    useDashboardStore.setState({
      logMessages: [log],
      recordedRequests: [item('a'), item('b')],
      proxiedRequests: [item('p')],
      activeExpectations: [item('e')],
      notification: null,
    });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  // clear?type=log removes recorded and proxied requests on the server too (they
  // are entries in the same event log), so the panels must not keep showing them
  // until the next push quietly drops them. Expectations survive.
  it("'log' clears the log server-side and drops the cleared requests locally, keeping expectations", async () => {
    const { result } = renderHook(() => useWebSocket(params));
    await act(async () => {
      await result.current.clearServer('log');
    });
    expect(fetchMock).toHaveBeenCalledWith('http://localhost:1080/mockserver/clear?type=log', { method: 'PUT' });
    const s = useDashboardStore.getState();
    expect(s.logMessages).toEqual([]);
    expect(s.recordedRequests).toEqual([]);
    expect(s.proxiedRequests).toEqual([]);
    expect(s.activeExpectations).toHaveLength(1);
    expect(s.notification?.message).toBe('Server logs and recorded requests cleared');
  });

  it("'expectations' keeps the requests and log", async () => {
    const { result } = renderHook(() => useWebSocket(params));
    await act(async () => {
      await result.current.clearServer('expectations');
    });
    const s = useDashboardStore.getState();
    expect(s.activeExpectations).toEqual([]);
    expect(s.recordedRequests).toHaveLength(2);
    expect(s.logMessages).toHaveLength(1);
  });
});
