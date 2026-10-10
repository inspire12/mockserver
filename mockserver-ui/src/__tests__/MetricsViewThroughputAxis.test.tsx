/**
 * The derived throughput series has one value per interval between scrapes, so
 * it is one shorter than the scrape history. It must get matching timestamps, or
 * the chart falls back to an index axis with no time labels at all.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, cleanup, waitFor } from '@testing-library/react';
import type { MetricsSnapshot } from '../lib/metricsDerive';

type ChartProps = { series: { data: unknown[]; label: string }[]; timestamps?: number[] };
const charts: ChartProps[] = [];
vi.mock('../components/MetricsLineChart', () => ({
  default: (props: ChartProps) => {
    charts.push(props);
    return null;
  },
}));

const sample = (at: number, received: number): MetricsSnapshot =>
  ({ at, samples: [{ name: 'requests_received_count', labels: {}, value: received }] }) as unknown as MetricsSnapshot;
const history = [sample(1000, 0), sample(4000, 3), sample(7000, 9)];
vi.mock('../hooks/useMetricsPolling', () => ({
  useMetricsPolling: () => ({ status: 'ok', history, latest: history[history.length - 1], error: null, intervalMs: 3000, refresh: vi.fn() }),
}));

import MetricsView from '../components/MetricsView';

afterEach(() => {
  cleanup();
  charts.length = 0;
});

describe('MetricsView throughput chart', () => {
  it('passes one timestamp per rate, stamped at the end of each interval', async () => {
    render(<MetricsView connectionParams={{ host: 'h', port: '1', secure: false }} />);
    await waitFor(() => expect(charts.some((c) => c.series[0]?.label === 'req/s')).toBe(true));
    const all = charts.filter((c) => c.series[0]?.label === 'req/s');
    const throughput = all[all.length - 1]!;
    expect(throughput.series[0]!.data).toEqual([1, 2]);
    expect(throughput.timestamps).toEqual([4000, 7000]);
  });
});
