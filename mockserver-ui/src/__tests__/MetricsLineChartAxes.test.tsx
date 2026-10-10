/**
 * Axis configuration of the Metrics charts: tick labels must not be cut to
 * "47.…" / "100…", time ticks must not repeat one HH:MM, and whole-number
 * series must not get fractional ticks that round to duplicates (0, 1, 1).
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';

type AxisProps = Record<string, unknown>;
const captured: { xAxis?: AxisProps[]; yAxis?: AxisProps[]; margin?: Record<string, number> } = {};
vi.mock('@mui/x-charts/LineChart', () => ({
  LineChart: (props: { xAxis: AxisProps[]; yAxis: AxisProps[]; margin: Record<string, number> }) => {
    captured.xAxis = props.xAxis;
    captured.yAxis = props.yAxis;
    captured.margin = props.margin;
    return null;
  },
}));

import MetricsLineChart, {
  distinctLabelTicks,
  halfLabelRoomPx,
  yAxisWidthFor,
  formatTimeLabel,
  isWholeNumberData,
  timeTickFormatter,
} from '../components/MetricsLineChart';

afterEach(cleanup);

const T0 = new Date('2024-01-01T01:06:00').getTime();
const every3s = (n: number) => Array.from({ length: n }, (_, i) => T0 + i * 3000);

describe('MetricsLineChart axes', () => {
  it('sizes the y-axis to its widest label instead of a fixed width that ellipsises them', () => {
    const ms = (v: number) => `${v.toFixed(1)} ms`;
    render(<MetricsLineChart series={[{ data: [2, 93.5], label: 'p99' }]} valueFormatter={ms} />);
    // The axis ends at 100, whose label "100.0 ms" is the widest it will draw.
    expect(captured.yAxis?.[0]?.width).toBe(yAxisWidthFor([{ data: [100], label: '' }], ms));
    expect(captured.yAxis?.[0]?.width).toBeGreaterThan(yAxisWidthFor([{ data: [4], label: '' }], ms));
  });

  it('widens the y-axis when the values grow', () => {
    const ms = (v: number) => `${v.toFixed(1)} ms`;
    const { rerender } = render(<MetricsLineChart series={[{ data: [1, 4], label: 'p99' }]} valueFormatter={ms} />);
    const narrow = captured.yAxis?.[0]?.width as number;
    rerender(<MetricsLineChart series={[{ data: [1, 4, 93.5], label: 'p99' }]} valueFormatter={ms} />);
    expect(captured.yAxis?.[0]?.width as number).toBeGreaterThan(narrow);
  });

  it('gives whole-number series a minimum tick step of 1', () => {
    render(<MetricsLineChart series={[{ data: [0, 1, 1], label: 'count' }]} />);
    expect(captured.yAxis?.[0]?.tickMinStep).toBe(1);
  });

  it('leaves fractional series free to use fractional ticks', () => {
    render(<MetricsLineChart series={[{ data: [0.4, 1.7], label: 'ms' }]} />);
    expect(captured.yAxis?.[0]?.tickMinStep).toBeUndefined();
  });

  it('labels a short time window with seconds and draws one tick per distinct label', () => {
    const ts = every3s(6);
    render(<MetricsLineChart series={[{ data: [1, 2, 3, 4, 5, 6], label: 'x' }]} timestamps={ts} />);
    const x = captured.xAxis?.[0] as { valueFormatter: (v: number) => string; tickInterval: (v: number, i: number) => boolean };
    const labels = ts.map((t) => x.valueFormatter(t));
    expect(new Set(labels).size).toBe(ts.length);
    expect(ts.every((t, i) => x.tickInterval(t, i))).toBe(true);
    // Room past each end for half of the first and last centred labels.
    const half = halfLabelRoomPx(ts, x.valueFormatter);
    expect(captured.margin?.right).toBe(half);
    expect((captured.margin?.left ?? 0) + (captured.yAxis?.[0]?.width as number)).toBeGreaterThanOrEqual(half);
  });
});

describe('time tick helpers', () => {
  it('passes the window span to formatTimeLabel: seconds for a short window, HH:MM for a long one', () => {
    const short = every3s(60);
    expect(timeTickFormatter(short)(T0)).toBe(formatTimeLabel(T0, short[short.length - 1]! - T0));
    expect(timeTickFormatter(short)(T0)).not.toBe(formatTimeLabel(T0));
    const long = [T0, T0 + 30 * 60_000];
    expect(timeTickFormatter(long)(T0)).toBe(formatTimeLabel(T0));
  });

  it('skips ticks whose label repeats the previous sample', () => {
    const xs = [T0, T0 + 3000, T0 + 60_000, T0 + 63_000];
    const keep = distinctLabelTicks(xs, (v) => formatTimeLabel(v));
    expect(xs.map((v, i) => keep(v, i))).toEqual([true, false, true, false]);
  });

  it('detects whole-number data, ignoring gaps', () => {
    expect(isWholeNumberData([{ data: [0, null, 3], label: 'a' }])).toBe(true);
    expect(isWholeNumberData([{ data: [0, 0.5], label: 'a' }])).toBe(false);
  });
});

describe('label room helpers', () => {
  it('fits the formatted end tick, not just the data maximum', () => {
    const fmt = (v: number) => v.toLocaleString('en-US');
    // 95,000 rounds up to an end tick of 100,000, one character wider.
    expect(yAxisWidthFor([{ data: [0, 95_000], label: '' }], fmt)).toBe(yAxisWidthFor([{ data: [100_000], label: '' }], fmt));
  });

  it('gives a 12-hour "02:01:00 AM" label more room than "02:01"', () => {
    expect(halfLabelRoomPx([0], () => '02:01:00 AM')).toBeGreaterThan(halfLabelRoomPx([0], () => '02:01'));
  });
});
