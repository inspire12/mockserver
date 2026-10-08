/**
 * The Composer's binary response form edits binaryResponse.upstream: what happens upstream to the
 * matched message on a relayed binary connection. The built JSON is observed through the "Test
 * Matcher" playground, which is seeded from the exact payload that would be registered.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup, waitFor, within } from '@testing-library/react';
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

afterEach(cleanup);

async function candidateJson(user: ReturnType<typeof userEvent.setup>): Promise<Record<string, unknown>> {
  await user.click(screen.getByRole('button', { name: 'Test Matcher' }));
  const candidate = screen.getByLabelText('Candidate expectation JSON') as HTMLTextAreaElement;
  const json = JSON.parse(candidate.value) as Record<string, unknown>;
  await user.keyboard('{Escape}');
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  return json;
}

async function chooseUpstream(user: ReturnType<typeof userEvent.setup>, option: RegExp) {
  await user.click(screen.getByRole('combobox', { name: 'Upstream' }));
  await user.click(within(screen.getByRole('listbox')).getByRole('option', { name: option }));
}

describe('Composer binary response upstream', () => {
  beforeEach(() => {
    useDashboardStore.setState({ activeExpectations: [], pendingEditExpectation: null });
  });

  it('defaults to answer only and leaves upstream out of the JSON', async () => {
    const user = userEvent.setup({ delay: null });
    renderComposer();
    await user.type(screen.getByLabelText('Path'), '/binary');
    await user.click(screen.getByRole('radio', { name: /Binary response/ }));

    expect(screen.getByRole('combobox', { name: 'Upstream' })).toHaveTextContent('Answer only (default)');
    const json = await candidateJson(user);
    expect(json['binaryResponse']).toEqual({});
  });

  it('writes the chosen upstream into the JSON and removes it again for answer only', async () => {
    const user = userEvent.setup({ delay: null });
    renderComposer();
    await user.type(screen.getByLabelText('Path'), '/binary');
    await user.click(screen.getByRole('radio', { name: /Binary response/ }));
    await user.type(screen.getByLabelText('Binary data (base64)'), 'VXBzdHJlYW0=');

    await chooseUpstream(user, /Forward and replace/);
    expect(await candidateJson(user)).toMatchObject({
      binaryResponse: { binaryData: 'VXBzdHJlYW0=', upstream: 'FORWARD_AND_REPLACE' },
    });

    await chooseUpstream(user, /Answer only/);
    expect((await candidateJson(user))['binaryResponse']).toEqual({ binaryData: 'VXBzdHJlYW0=' });
  });

  it('keeps the upstream of an expectation loaded for editing', async () => {
    const exp = {
      id: 'binary-upstream-1',
      httpRequest: { path: '/binary' },
      binaryResponse: { binaryData: 'VXBzdHJlYW0=', upstream: 'ANSWER_AND_FORWARD' },
    };
    useDashboardStore.setState({
      activeExpectations: [{ key: 'binary-upstream-1', value: exp }],
      pendingEditExpectation: exp,
    });
    const user = userEvent.setup({ delay: null });
    renderComposer();

    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Upstream' })).toHaveTextContent('Answer and forward'));
    expect(await candidateJson(user)).toMatchObject({
      binaryResponse: { binaryData: 'VXBzdHJlYW0=', upstream: 'ANSWER_AND_FORWARD' },
    });
  });
});
