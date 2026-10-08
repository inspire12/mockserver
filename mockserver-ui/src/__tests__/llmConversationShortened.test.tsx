import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import LlmConversationForm from '../components/LlmConversationForm';
import { useDashboardStore } from '../store';
import type { JsonListItem } from '../types';

// The conversation editor re-registers every turn, so a turn the live update shortened must be loaded
// whole before the editor builds its draft, or it would save the shortened completion text.

const callMcpTool = vi.fn();
vi.mock('../lib/mcpClient', () => ({
  buildBaseUrl: () => 'http://127.0.0.1:1080',
  callMcpTool: (...args: unknown[]) => callMcpTool(...args),
}));

const params = { host: '127.0.0.1', port: '1080', secure: false };
const SCENARIO = '__llm_conv_chat';
const WHOLE_TEXT = 'the-whole-completion-'.repeat(20);

function turn(text: string): Record<string, unknown> {
  return {
    id: 'turn-0',
    scenarioName: SCENARIO,
    scenarioState: 'Started',
    httpRequest: { method: 'POST', path: '/v1/messages' },
    httpLlmResponse: { provider: 'ANTHROPIC', model: 'claude', completion: { text }, conversationPredicates: { turnIndex: 0 } },
  };
}

const shortenedTurn: JsonListItem = {
  key: 'turn-0',
  value: turn('the-whole-com'),
  truncatedExpectation: { expectationId: 'turn-0', part: 'expectation', originalLength: WHOLE_TEXT.length, shownLength: 13 },
};

function serving(whole: Record<string, unknown> | null, retrievals: unknown[]) {
  return vi.fn(async (input: unknown, init?: RequestInit) => {
    const url = String(input);
    if (url.includes('/mockserver/retrieve')) {
      const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
      retrievals.push(body);
      if (body['id'] === undefined) return new Response(JSON.stringify(whole ? [whole] : []), { status: 200 });
      return new Response(JSON.stringify(whole && body['id'] === whole['id'] ? [whole] : []), { status: 200 });
    }
    return new Response('{}', { status: 200 });
  });
}

function renderForm() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <LlmConversationForm connectionParams={params} initialScenarioName={SCENARIO} />
    </ThemeProvider>,
  );
}

describe('editing an LLM conversation the live update shortened', () => {
  beforeEach(() => {
    callMcpTool.mockReset();
    callMcpTool.mockResolvedValue({ ok: true, result: {} });
    useDashboardStore.setState({ activeExpectations: [shortenedTurn], notification: null });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('loads each shortened turn whole by id and registers the whole completion', async () => {
    const user = userEvent.setup({ delay: null });
    const retrievals: unknown[] = [];
    vi.stubGlobal('fetch', serving(turn(WHOLE_TEXT), retrievals));

    renderForm();

    const update = await screen.findByRole('button', { name: /Update 1 expectation/ });
    expect(retrievals[0]).toEqual({ id: 'turn-0' });
    await user.click(update);

    await waitFor(() => expect(callMcpTool).toHaveBeenCalledTimes(1));
    const args = JSON.stringify(callMcpTool.mock.calls[0]![2]);
    expect(args).toContain(WHOLE_TEXT);
  });

  it('offers no editor when a shortened turn cannot be loaded', async () => {
    vi.stubGlobal('fetch', serving(null, []));

    renderForm();

    expect(await screen.findByTestId('llm-conversation-load-error')).toHaveTextContent(
      'Could not load the whole conversation, so it cannot be edited: Expectation turn-0 is no longer registered',
    );
    expect(screen.queryByRole('button', { name: /Update|Replace|Register/ })).toBeNull();
    expect(callMcpTool).not.toHaveBeenCalled();
  });
});
