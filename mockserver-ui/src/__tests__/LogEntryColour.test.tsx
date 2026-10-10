import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import LogEntry from '../components/LogEntry';
import { buildTheme, logTypeColor } from '../theme';
import type { LogEntryValue } from '../types';
import type { ThemeMode } from '../types';

afterEach(cleanup);

// The server colours each row for a dark background (CREATED_EXPECTATION is a
// pale beige, about 1.6:1 on white); the row must render the theme's readable variant.
function renderRow(mode: ThemeMode, color: string) {
  const entry: LogEntryValue = {
    style: { color, paddingTop: '4px' },
    messageParts: [{ key: 'm0', value: 'creating expectation' }],
  };
  render(
    <ThemeProvider theme={buildTheme(mode)}>
      <LogEntry entry={entry} />
    </ThemeProvider>,
  );
  return screen.getByText('creating expectation');
}

const spaced = (rgb: string) => rgb.replace(/,\s*/g, ', ');

describe('LogEntry row colour', () => {
  it('uses the light-mode variant of the server colour on the light theme', () => {
    const text = renderRow('light', 'rgb(216,199,166)');
    expect(getComputedStyle(text).color).toBe(spaced(logTypeColor('CREATED_EXPECTATION', 'light')));
  });

  it('uses the dark-mode variant on the dark theme', () => {
    const text = renderRow('dark', 'rgb(59,122,87)');
    expect(getComputedStyle(text).color).toBe(spaced(logTypeColor('INFO', 'dark')));
  });

  it('keeps a colour it does not recognise', () => {
    const text = renderRow('light', 'rgb(1, 2, 3)');
    expect(getComputedStyle(text).color).toBe('rgb(1, 2, 3)');
  });
});
