/**
 * In light mode the title bar is the primary colour, so its text and status
 * chip must reach WCAG AA (4.5:1) against it, including on the active nav group.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import AppBar from '../components/AppBar';
import { useDashboardStore, type ViewMode } from '../store';
import type { ConnectionStatus } from '../types';

type Rgba = [number, number, number, number];
function parse(colour: string): Rgba {
  if (colour.startsWith('#')) {
    const [r, g, b] = [1, 3, 5].map((i) => parseInt(colour.slice(i, i + 2), 16)) as [number, number, number];
    return [r, g, b, 1];
  }
  const [r = 0, g = 0, b = 0, a = 1] = (colour.match(/[\d.]+/g) ?? []).map(Number);
  return [r, g, b, a];
}
function over(fg: Rgba, bg: Rgba): Rgba {
  const a = fg[3];
  return [0, 1, 2].map((i) => fg[i]! * a + bg[i]! * (1 - a)).concat(1) as Rgba;
}
function luminance([r, g, b]: Rgba): number {
  const f = (v: number) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}
function contrast(fg: Rgba, bg: Rgba): number {
  const [hi, lo] = [luminance(fg), luminance(bg)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

const theme = buildTheme('light');
const bar = parse(theme.palette.primary.main);

function renderLight() {
  render(
    <ThemeProvider theme={theme}>
      <AppBar
        onClearServer={vi.fn().mockResolvedValue(undefined)}
        onClearLogs={vi.fn().mockResolvedValue(undefined)}
        onClearExpectations={vi.fn().mockResolvedValue(undefined)}
        onShowShortcuts={vi.fn()}
      />
    </ThemeProvider>,
  );
}

describe('light title bar contrast', () => {
  beforeEach(() => {
    useDashboardStore.setState({ themeMode: 'light', autoScroll: true, view: 'dashboard' as ViewMode });
  });
  afterEach(cleanup);

  for (const status of ['connected', 'connecting', 'disconnected', 'error'] as ConnectionStatus[]) {
    it(`the "${status}" chip reads at 4.5:1 on the bar`, () => {
      useDashboardStore.setState({ connectionStatus: status });
      renderLight();
      const label = screen.getByText(status);
      expect(getComputedStyle(label).color).toMatch(/^(rgb|#)/);
      expect(contrast(over(parse(getComputedStyle(label).color), bar), bar)).toBeGreaterThanOrEqual(4.5);
    });
  }

  it('the active nav group label reads at 4.5:1 on its highlight', () => {
    useDashboardStore.setState({ connectionStatus: 'connected' });
    renderLight();
    const active = screen.getByRole('button', { name: 'Observe views' });
    const background = getComputedStyle(active).backgroundColor;
    // The highlight must be a real translucent overlay, not an unresolved value.
    expect(background).toMatch(/^rgba\(/);
    const highlight = over(parse(background), bar);
    expect(contrast([255, 255, 255, 1], highlight)).toBeGreaterThanOrEqual(4.5);
  });
});
