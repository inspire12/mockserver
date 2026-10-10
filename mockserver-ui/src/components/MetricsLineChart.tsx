import { LineChart } from '@mui/x-charts/LineChart';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';

export interface MetricsSeries {
  /**
   * One value per x-position. `null` renders a gap — used by the load chart so a
   * per-scenario line only draws over the frames where that scenario was running.
   */
  data: (number | null)[];
  label: string;
}

interface MetricsLineChartProps {
  series: MetricsSeries[];
  height?: number;
  /** Formats y-axis + tooltip values (e.g. bytes → "1.2 MB"). */
  valueFormatter?: (value: number) => string;
  /**
   * Epoch-millis timestamp for each point (one per sample, in lockstep with the
   * series data). When supplied the x-axis renders readable wall-clock time
   * labels (HH:MM, with seconds for short spans) instead of bare indices; falls back to indices if omitted or
   * length-mismatched.
   */
  timestamps?: number[];
}

/** Below this time span the x-axis ticks are under a minute apart, so they need seconds. */
const SECONDS_LABEL_SPAN_MILLIS = 20 * 60_000;

/**
 * Time x-axis tick label in the viewer's locale: HH:MM, or HH:MM:SS when the chart spans less
 * than {@link SECONDS_LABEL_SPAN_MILLIS} (otherwise every tick of a short run reads the same minute).
 */
// eslint-disable-next-line react-refresh/only-export-components
export function formatTimeLabel(epochMillis: number, spanMillis = Infinity): string {
  const withSeconds = spanMillis < SECONDS_LABEL_SPAN_MILLIS;
  return new Date(epochMillis).toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    ...(withSeconds ? { second: '2-digit' as const } : {}),
  });
}

/** The tick label formatter for a window of sample timestamps (oldest first). */
// eslint-disable-next-line react-refresh/only-export-components
export function timeTickFormatter(timestamps: number[]): (epochMillis: number) => string {
  const span = timestamps.length > 1 ? Math.abs(timestamps[timestamps.length - 1]! - timestamps[0]!) : 0;
  return (epochMillis) => formatTimeLabel(epochMillis, span);
}

/**
 * Point-scale tick filter: a tick only where its label differs from the previous
 * sample's, so several samples inside one label period draw one tick, not a row
 * of identical labels.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function distinctLabelTicks(
  xData: number[],
  label: (value: number) => string,
): (value: number, index: number) => boolean {
  return (value, index) => index === 0 || label(xData[index - 1]!) !== label(value);
}

// Upper bound on one character's width at the axis label font (x-charts measures
// tick labels at 12px), so an estimate never comes out narrower than the label.
const LABEL_CHAR_PX = 7.2;
const Y_TICK_AND_GAP_PX = 14;

const labelWidthPx = (text: string): number => Math.ceil(text.length * LABEL_CHAR_PX);

/** `value` rounded away from zero to one significant figure: the axis's likely end tick. */
function niceBound(value: number): number {
  if (value === 0 || !Number.isFinite(value)) return 0;
  const magnitude = 10 ** Math.floor(Math.log10(Math.abs(value)));
  return Math.sign(value) * Math.ceil(Math.abs(value) / magnitude) * magnitude;
}

/**
 * Width for the y-axis that fits its widest tick label. Computed on every render
 * from the data, because the chart's own `width: 'auto'` measured the first
 * labels only and then cut "100.0 ms" to "100.0…" once the values grew.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function yAxisWidthFor(series: MetricsSeries[], format: (v: number) => string = String): number {
  const values = series.flatMap((s) => s.data.filter((v): v is number => v != null && Number.isFinite(v)));
  const min = Math.min(0, ...values);
  const max = Math.max(0, ...values);
  const candidates = [min, max, niceBound(min), niceBound(max)];
  const widest = Math.max(...candidates.map((v) => labelWidthPx(format(v))));
  return Math.min(140, Math.max(28, widest + Y_TICK_AND_GAP_PX));
}

/** Room a centred tick label needs either side of its tick: half the widest label, plus a gap. */
// eslint-disable-next-line react-refresh/only-export-components
export function halfLabelRoomPx(values: number[], format: (v: number) => string): number {
  const widest = Math.max(0, ...values.map((v) => labelWidthPx(format(v))));
  return Math.ceil(widest / 2) + 4;
}

/** True when every value is a whole number, so the y-axis must not draw fractional ticks. */
// eslint-disable-next-line react-refresh/only-export-components
export function isWholeNumberData(series: MetricsSeries[]): boolean {
  return series.every((s) => s.data.every((v) => v == null || Number.isInteger(v)));
}

/**
 * Thin wrapper around `@mui/x-charts` LineChart for the Metrics view. Renders a
 * real time x-axis (HH:MM tick labels from the snapshot timestamps), a soft area
 * fill under each line and a coherent series colour drawn from the theme palette
 * so the charts read as intentional data-viz. Disables point marks for a clean
 * live line, and shows a "collecting…" placeholder until at least two samples
 * exist.
 */
export default function MetricsLineChart({ series, height = 220, valueFormatter, timestamps }: MetricsLineChartProps) {
  const theme = useTheme();
  // shortest series length, so an accidental ragged input shows the placeholder
  // rather than silently clipping points.
  const length = series.length === 0 ? 0 : Math.min(...series.map((s) => s.data.length));

  if (length < 2) {
    return (
      <Box sx={{ height, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Typography variant="caption" color="text.secondary">
          collecting…
        </Typography>
      </Box>
    );
  }

  // Coherent palette: lead with the primary/secondary brand colours, then the
  // charting palette, so single-series charts read as one intentional accent
  // rather than an arbitrary default colour.
  const palette = [
    theme.palette.primary.main,
    theme.palette.secondary.main,
    theme.palette.info.main,
    theme.palette.success.main,
    theme.palette.warning.main,
    theme.palette.error.main,
  ];

  // Use real timestamps when they line up with the data; otherwise fall back to
  // plain indices (keeps the component robust if a caller omits timestamps).
  const useTime = Array.isArray(timestamps) && timestamps.length === length;
  const xData = useTime
    ? (timestamps as number[]).slice(0, length)
    : Array.from({ length }, (_, i) => i);
  const timeLabel = useTime ? timeTickFormatter(xData) : undefined;
  const yFormat = valueFormatter ?? ((v: number) => v.toLocaleString());
  const yWidth = yAxisWidthFor(series, yFormat);
  // The first and last time labels are centred on the chart's edges; x-charts
  // ellipsises a label that would cross the SVG bounds, so leave half a label
  // of room past each end (the y-axis already provides some on the left).
  const edgeRoom = timeLabel ? halfLabelRoomPx(xData, timeLabel) : 12;

  // A single-series chart fills the area under the line for a stronger data-viz
  // read; multi-series charts keep clean lines so overlapping fills don't muddy.
  const fillArea = series.length === 1;

  return (
    <LineChart
      height={height}
      series={series.map((s, i) => ({
        data: s.data,
        label: s.label,
        showMark: false,
        area: fillArea,
        curve: 'monotoneX' as const,
        color: palette[i % palette.length],
        valueFormatter: valueFormatter ? (v: number | null) => (v == null ? '' : valueFormatter(v)) : undefined,
      }))}
      xAxis={[{
        data: xData,
        scaleType: 'point',
        valueFormatter: timeLabel ?? (() => ''),
        ...(timeLabel ? { tickInterval: distinctLabelTicks(xData, timeLabel) } : {}),
      }]}
      // The y-axis is as wide as its widest label: the default fixed width cut
      // "47.7 MB" or "100,000" down to "47.…" / "100…". Whole-number series
      // (counts) never get fractional ticks that round to duplicates (0, 1, 1).
      yAxis={[{
        width: yWidth,
        valueFormatter: valueFormatter ? (v: number) => valueFormatter(v) : undefined,
        ...(isWholeNumberData(series) ? { tickMinStep: 1 } : {}),
      }]}
      margin={{ left: Math.max(4, edgeRoom - yWidth), right: edgeRoom, top: 16, bottom: 4 }}
      hideLegend={series.length <= 1}
      sx={{
        // Soften the filled area so it reads as a gradient-style wash under the
        // line rather than a solid block.
        '& .MuiLineChart-area, & .MuiAreaElement-root': {
          fillOpacity: 0.16,
        },
        '& .MuiChartsAxis-tickLabel': {
          fontSize: theme.typography.caption.fontSize,
        },
      }}
    />
  );
}
