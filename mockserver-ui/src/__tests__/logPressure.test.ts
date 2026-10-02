import { describe, it, expect } from 'vitest';
import { parseLogPressure, totalDropped } from '../lib/logPressure';

const NONE = { ringFull: 0, inFlightBytes: 0, unattributed: 0, evicted: 0 };

describe('parseLogPressure', () => {
  it('reads each drop reason and the eviction counter separately', () => {
    const text = [
      '# HELP mock_server_dropped_log_events_total Log events dropped before being recorded, by reason',
      '# TYPE mock_server_dropped_log_events_total counter',
      'mock_server_dropped_log_events_total{reason="in_flight_bytes"} 7.0',
      'mock_server_dropped_log_events_total{reason="ring_full"} 42.0',
      '# TYPE mock_server_evicted_log_entries_total counter',
      'mock_server_evicted_log_entries_total 1500.0',
    ].join('\n');
    expect(parseLogPressure(text)).toEqual({ ringFull: 42, inFlightBytes: 7, unattributed: 0, evicted: 1500 });
  });

  it('reports an unlabelled drop counter from an older server as unattributed', () => {
    expect(parseLogPressure('mock_server_dropped_log_events_total 3.0')).toEqual({ ...NONE, unattributed: 3 });
  });

  it('counts a drop reason it does not know as unattributed rather than ignoring it', () => {
    const text = [
      'mock_server_dropped_log_events_total{reason="ring_full"} 1.0',
      'mock_server_dropped_log_events_total{reason="some_future_reason"} 4.0',
    ].join('\n');
    expect(parseLogPressure(text)).toEqual({ ...NONE, ringFull: 1, unattributed: 4 });
  });

  it('returns zeros when the counters are absent (metrics disabled or empty input)', () => {
    expect(parseLogPressure('requests_received_count 5.0\nother_metric 1.0')).toEqual(NONE);
    expect(parseLogPressure('')).toEqual(NONE);
  });

  // The server's Prometheus client only ever emits the `_total` form; reading
  // the bare registered name would pick up the wrong sample.
  it('reads the _total samples, not the bare names', () => {
    const text = [
      'mock_server_dropped_log_events{reason="ring_full"} 7.0',
      'mock_server_dropped_log_events_total{reason="ring_full"} 99.0',
      'mock_server_evicted_log_entries 5.0',
      'mock_server_evicted_log_entries_total 6.0',
    ].join('\n');
    expect(parseLogPressure(text)).toEqual({ ...NONE, ringFull: 99, evicted: 6 });
  });
});

describe('totalDropped', () => {
  it('sums every drop cause but not evictions', () => {
    expect(totalDropped({ ringFull: 1, inFlightBytes: 2, unattributed: 4, evicted: 100 })).toBe(7);
  });
});
