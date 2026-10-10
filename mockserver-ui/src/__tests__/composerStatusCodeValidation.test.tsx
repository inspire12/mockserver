/**
 * A response status code the server cannot send — blank, 0, negative or four
 * digits — keeps Register disabled with a visible reason, in Quick mode and in
 * every Advanced action that carries a status code. A blank field used to snap
 * to 0 and register, and the server then answered "HTTP/1.1 0".
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ComposerView from '../components/ComposerView';
import { useDashboardStore } from '../store';
import { responseStatusCodeError } from '../lib/standardCodegen';

vi.mock('../lib/mcpClient', () => ({
  buildBaseUrl: () => 'http://127.0.0.1:1080',
  callMcpTool: vi.fn().mockResolvedValue({ ok: true, result: { tools: [], count: 0 } }),
}));

vi.mock('../lib/conversationCodegen', () => ({
  listConversationScenarios: () => [],
}));

const params = { host: '127.0.0.1', port: '1080', secure: false };
const REASON = 'Status code must be a whole number from 100 to 999';

function renderComposer(mode: 'quick' | 'advanced') {
  globalThis.sessionStorage?.setItem('mockserver-composer-mode', mode);
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <ComposerView connectionParams={params} />
    </ThemeProvider>,
  );
}

describe('responseStatusCodeError', () => {
  it('accepts every three-digit code and refuses anything an HTTP status line cannot carry', () => {
    for (const ok of [100, 200, 418, 599, 999]) expect(responseStatusCodeError(ok)).toBeUndefined();
    for (const bad of [NaN, 0, -5, 99, 1000, 200.5]) expect(responseStatusCodeError(bad)).toBe(REASON);
  });
});

describe('Composer status code validation', () => {
  beforeEach(() => {
    useDashboardStore.setState({ activeExpectations: [] });
  });

  it('Quick mock: a cleared status code stays blank, shows why, and disables Register', async () => {
    const user = userEvent.setup();
    renderComposer('quick');
    await user.type(screen.getByLabelText('Path'), '/validate/status');
    const status = screen.getByLabelText('Status code') as HTMLInputElement;
    expect(screen.getByRole('button', { name: 'Register mock' })).toBeEnabled();

    await user.clear(status);
    expect(status.value).toBe('');
    expect(screen.getByText(REASON)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register mock' })).toBeDisabled();

    await user.type(status, '1000');
    expect(screen.getByRole('button', { name: 'Register mock' })).toBeDisabled();

    await user.clear(status);
    await user.type(status, '201');
    expect(screen.queryByText(REASON)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register mock' })).toBeEnabled();
  });

  it('Advanced static response: 0 disables Register', async () => {
    const user = userEvent.setup();
    renderComposer('advanced');
    await user.type(screen.getByLabelText('Path'), '/validate/status');
    const status = screen.getByLabelText('Status code');
    await user.clear(status);
    await user.type(status, '0');
    expect(screen.getByText(REASON)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Register expectation/ })).toBeDisabled();
  });

  it('Advanced forward with fallback: an out-of-range fallback status code disables Register', async () => {
    const user = userEvent.setup();
    renderComposer('advanced');
    await user.type(screen.getByLabelText('Path'), '/validate/status');
    await user.click(screen.getByRole('radio', { name: /^Forward with fallback/ }));
    await user.type(screen.getByLabelText('Host'), 'upstream.local');
    expect(screen.getByRole('button', { name: /Register expectation/ })).toBeEnabled();
    const status = screen.getByLabelText('Status code');
    await user.clear(status);
    await user.type(status, '99');
    expect(screen.getByRole('button', { name: /Register expectation/ })).toBeDisabled();
  });

  it('Advanced SSE: a blank status code disables Register', async () => {
    const user = userEvent.setup();
    renderComposer('advanced');
    await user.type(screen.getByLabelText('Path'), '/validate/status');
    await user.click(screen.getByRole('radio', { name: /^SSE response/ }));
    await user.type(screen.getByLabelText('Data'), 'hello');
    expect(screen.getByRole('button', { name: /Register expectation/ })).toBeEnabled();
    await user.clear(screen.getByLabelText('Status code'));
    expect(screen.getByRole('button', { name: /Register expectation/ })).toBeDisabled();
  });
});
