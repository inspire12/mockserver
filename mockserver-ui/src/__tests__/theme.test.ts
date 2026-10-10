import { describe, it, expect } from 'vitest';
import { buildTheme, logTypeColors, becauseColors, logTypeColor, logRowColor, type LogType } from '../theme';
import type { ThemeMode } from '../types';

// WCAG 2.x relative luminance / contrast ratio for opaque colours.
function channels(colour: string): [number, number, number] {
  if (colour.startsWith('#')) {
    return [1, 3, 5].map((i) => parseInt(colour.slice(i, i + 2), 16)) as [number, number, number];
  }
  return (colour.match(/\d+/g) ?? []).slice(0, 3).map(Number) as [number, number, number];
}
function luminance(colour: string): number {
  const [r, g, b] = channels(colour).map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}
const BACKGROUNDS: Record<ThemeMode, string[]> = {
  light: ['#ffffff', '#fafafa'],
  dark: ['#1e1e1e', '#121212'],
};

describe('buildTheme', () => {
  it('creates a dark theme with correct palette mode', () => {
    const theme = buildTheme('dark');
    expect(theme.palette.mode).toBe('dark');
  });

  it('creates a light theme with correct palette mode', () => {
    const theme = buildTheme('light');
    expect(theme.palette.mode).toBe('light');
  });

  it('dark theme has dark background', () => {
    const theme = buildTheme('dark');
    expect(theme.palette.background.default).toBe('#121212');
  });

  it('light theme has light background', () => {
    const theme = buildTheme('light');
    expect(theme.palette.background.default).toBe('#fafafa');
  });
});

describe('logTypeColors', () => {
  it('has all 18+ log type colours defined', () => {
    const knownTypes = [
      'TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'EXCEPTION',
      'CLEARED', 'RETRIEVED', 'UPDATED_EXPECTATION', 'CREATED_EXPECTATION',
      'REMOVED_EXPECTATION', 'RECEIVED_REQUEST', 'EXPECTATION_RESPONSE',
      'NO_MATCH_RESPONSE', 'EXPECTATION_MATCHED', 'EXPECTATION_NOT_MATCHED',
      'VERIFICATION', 'VERIFICATION_FAILED', 'FORWARDED_REQUEST',
      'TEMPLATE_GENERATED', 'TEMPLATE_GENERATION_FAILED', 'SERVER_CONFIGURATION', 'DEFAULT',
    ] as const;

    for (const type of knownTypes) {
      expect(logTypeColors[type]).toBeDefined();
      expect(logTypeColors[type]).toMatch(/^rgb\(/);
    }
  });
});

describe('becauseColors', () => {
  it('has matched, didntMatch, and neutral colours', () => {
    expect(becauseColors.matched).toBeDefined();
    expect(becauseColors.didntMatch).toBeDefined();
    expect(becauseColors.neutral).toBeDefined();
  });

  it('matched is green-ish, didntMatch is red-ish', () => {
    expect(becauseColors.matched).toContain('107, 199, 118');
    expect(becauseColors.didntMatch).toContain('216, 88, 118');
  });
});

describe('log row colours meet WCAG AA', () => {
  const types = Object.keys(logTypeColors) as LogType[];
  for (const mode of ['light', 'dark'] as const) {
    it(`every log type reaches 4.5:1 on the ${mode} backgrounds`, () => {
      const failures = types.flatMap((type) =>
        BACKGROUNDS[mode]
          .map((bg) => ({ type, bg, ratio: contrast(logTypeColor(type, mode), bg) }))
          .filter(({ ratio }) => ratio < 4.5),
      );
      expect(failures).toEqual([]);
    });
  }

  it('maps the colour the server sends to the readable variant for the mode', () => {
    // The server writes some colours with spaces and some without.
    expect(logRowColor('rgb(216,199,166)', 'light')).toBe(logTypeColor('CREATED_EXPECTATION', 'light'));
    expect(logRowColor('rgb(215, 216, 154)', 'light')).toBe(logTypeColor('TRACE', 'light'));
    expect(logRowColor('rgb(59,122,87)', 'dark')).toBe(logTypeColor('INFO', 'dark'));
    expect(logRowColor('rgb(216,199,166)', 'light')).not.toBe('rgb(216,199,166)');
  });

  it('passes an unknown colour through unchanged', () => {
    expect(logRowColor('rgb(1, 2, 3)', 'light')).toBe('rgb(1, 2, 3)');
  });
});

describe('light theme colour tokens meet WCAG AA', () => {
  const palette = buildTheme('light').palette;
  for (const key of ['primary', 'secondary', 'error', 'warning', 'info', 'success'] as const) {
    it(`${key}.main reads at 4.5:1 as text and under white text`, () => {
      const main = palette[key].main;
      for (const bg of BACKGROUNDS.light) expect(contrast(main, bg)).toBeGreaterThanOrEqual(4.5);
      expect(contrast('#ffffff', main)).toBeGreaterThanOrEqual(4.5);
    });
  }
});

