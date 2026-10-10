import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import TrafficInspector from '../components/TrafficInspector';
import LogEntry from '../components/LogEntry';
import { useDashboardStore } from '../store';
import type { JsonListItem, LogEntryValue } from '../types';

// jsdom has no layout, so these pin the CSS that keeps a narrow pane usable: the Traffic
// toolbar wraps, the list cannot be scrolled sideways, rows stay on one line, and a
// shortened-body notice sits on its own line rather than inside the message sentence.

function llmRow(key: string): JsonListItem {
  return {
    key,
    value: {
      httpRequest: {
        method: 'POST',
        path: '/llm/v1/chat/completions/with/a/rather/long/path/that/must/truncate',
        headers: [{ name: 'host', values: ['localhost:1095'] }],
        body: { type: 'JSON', json: JSON.stringify({ model: 'gpt-4o', messages: [{ role: 'user', content: 'hi' }] }) },
      },
      httpResponse: {
        statusCode: 200,
        body: { type: 'JSON', json: JSON.stringify({ model: 'gpt-4o', choices: [], usage: { prompt_tokens: 12, completion_tokens: 3 } }) },
      },
    },
  };
}

function renderInspector() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <TrafficInspector />
    </ThemeProvider>,
  );
}

describe('Traffic list in a narrow pane', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [llmRow('a'), llmRow('b')],
      recordedRequests: [],
      activeExpectations: [],
      trafficSearch: '',
      selectedTrafficKey: null,
      fullMessages: {},
      notification: null,
    });
  });

  it('wraps the toolbar so every control stays reachable with the detail pane open', async () => {
    const user = userEvent.setup();
    renderInspector();
    await user.click(screen.getAllByText(/localhost:1095\/llm\/v1\/chat/)[0]!);
    await user.click(screen.getByRole('button', { name: 'Add to Diff Pool' }));

    const toolbar = screen.getByTestId('traffic-toolbar');
    expect(getComputedStyle(toolbar).flexWrap).toBe('wrap');
    for (const name of [/Compare requests/, /Diff Pool \(1\)/, /Select requests/, /Promote to Mocks/]) {
      expect(within(toolbar).getByRole('button', { name })).toBeInTheDocument();
    }
  });

  it('cannot scroll the list sideways when a control past the pane edge takes focus', () => {
    renderInspector();
    const paper = screen.getByTestId('traffic-toolbar').parentElement!;
    // 'hidden' still leaves a programmatically scrollable box; focusing the Diff Pool chip
    // scrolled every row's start out of view.
    expect(getComputedStyle(paper).overflow).toBe('clip');
  });

  it('keeps each row on one line, truncating the path and keeping the status and model chips', () => {
    renderInspector();
    const path = screen.getAllByTestId('traffic-row-path')[0]!;
    const row = path.parentElement!;
    expect(getComputedStyle(row).flexWrap).toBe('nowrap');
    expect(getComputedStyle(path).textOverflow).toBe('ellipsis');
    const status = within(row).getByText('200').closest('.MuiChip-root')!;
    const model = within(row).getByText('gpt-4o').closest('.MuiChip-root')!;
    const tokens = within(row).getByText(/12 in/);
    // When space runs out the token count gives way first; the status chip never does.
    expect(getComputedStyle(status).flexShrink).toBe('0');
    expect(Number(getComputedStyle(tokens).flexShrink)).toBeGreaterThan(Number(getComputedStyle(model).flexShrink || '1'));
  });
});

describe('shortened body in a log message', () => {
  it('shows the notice on its own line, outside the inline argument', () => {
    const entry: LogEntryValue = {
      description: '10:00:00 NO_MATCH_RESPONSE',
      messageParts: [
        { key: 'msg_0', value: 'no expectation for:' },
        {
          key: 'msg_1',
          value: { method: 'POST', path: '/api/upload', body: 'first-64-KiB-only' },
          json: true,
          argument: true,
          truncatedBody: { logEntryId: 'e1', part: 'request', originalLength: 69632, shownLength: 65536 },
        },
        { key: 'msg_2', value: ' returning response:' },
      ],
    };
    render(<LogEntry entry={entry} />);

    const notice = screen.getByTestId('truncated-body-notice');
    const lead = screen.getByText('no expectation for:');
    // A sibling of the message text, not nested in the inline-block JSON box between
    // "no expectation for:" and "returning response:".
    expect(notice.parentElement).toBe(lead.parentElement);
    expect(getComputedStyle(notice).display).not.toMatch(/^inline/);
  });
});
