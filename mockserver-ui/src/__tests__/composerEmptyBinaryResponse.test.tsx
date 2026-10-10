/**
 * A binary response with no data means the matched message gets no reply, so the Composer lets the
 * user register one, says what an empty field means, and still rejects data that is not base64.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ComposerView from '../components/ComposerView';
import { useDashboardStore } from '../store';

vi.mock('../lib/mcpClient', () => ({
  buildBaseUrl: () => 'http://127.0.0.1:1080',
  callMcpTool: vi.fn().mockResolvedValue({ ok: true, result: { tools: [], count: 0 } }),
}));

vi.mock('../lib/conversationCodegen', () => ({
  listConversationScenarios: () => [],
}));

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderComposer() {
  try { globalThis.sessionStorage?.setItem('mockserver-composer-mode', 'advanced'); } catch { /* noop */ }
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <ComposerView connectionParams={params} />
    </ThemeProvider>,
  );
}

async function chooseBinaryResponse(user: ReturnType<typeof userEvent.setup>) {
  renderComposer();
  await user.type(screen.getByLabelText('Path'), '/binary');
  await user.click(screen.getByRole('radio', { name: /Binary response/ }));
}

describe('Composer binary response with no data', () => {
  beforeEach(() => {
    useDashboardStore.setState({ activeExpectations: [] });
  });

  it('can be registered, and says that no data means no reply', async () => {
    const user = userEvent.setup({ delay: null });
    await chooseBinaryResponse(user);

    expect(screen.getByLabelText('Binary data (base64)')).toHaveValue('');
    expect(screen.getByText(/Leave empty to send no reply/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Register expectation/ })).toBeEnabled();
  });

  it('still rejects data that is not base64', async () => {
    const user = userEvent.setup({ delay: null });
    await chooseBinaryResponse(user);

    await user.type(screen.getByLabelText('Binary data (base64)'), 'not base64!');

    expect(screen.getByRole('button', { name: /Register expectation/ })).toBeDisabled();
  });
});
