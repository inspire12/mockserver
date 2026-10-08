/**
 * Fields the composer keeps through an edit are listed in an "Other fields" panel, where
 * each can be removed; and editing a message list keeps each message's hidden fields.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
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
const delay = { timeUnit: 'MILLISECONDS', value: 250 };
const http = (path: string) => ({ method: 'GET', path });

function load(expectation: Record<string, unknown>) {
  useDashboardStore.setState({
    activeExpectations: [],
    pendingEditExpectation: expectation as never,
    view: 'composer',
  });
  render(
    <ThemeProvider theme={buildTheme('dark')}>
      <ComposerView connectionParams={params} />
    </ThemeProvider>,
  );
}

async function save(fetchMock: ReturnType<typeof vi.spyOn>, name = /Update expectation/): Promise<Record<string, unknown>> {
  const user = userEvent.setup({ delay: null });
  await user.click(screen.getByRole('button', { name }));
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
  return JSON.parse(String((fetchMock.mock.calls[0]![1] as RequestInit).body)) as Record<string, unknown>;
}

async function replaceText(label: string | RegExp, text: string) {
  const user = userEvent.setup({ delay: null });
  const field = await screen.findByLabelText(label);
  await user.clear(field);
  await user.type(field, text);
}

describe('kept action fields in the composer', () => {
  let fetchMock: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    useDashboardStore.setState({ activeExpectations: [], pendingEditExpectation: null, view: 'composer' });
    try { globalThis.sessionStorage?.clear(); } catch { /* noop */ }
    fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 201 }));
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('lists the fields the form does not show, and a removed one is not saved', async () => {
    const user = userEvent.setup({ delay: null });
    load({ id: 'kept-forward', httpRequest: http('/fwd'), httpForward: { host: 'up.example.com', port: 8080, scheme: 'HTTP', delay, primary: true } });
    const panel = await screen.findByTestId('kept-action-fields');
    expect(panel).toHaveTextContent('delay 250 MILLISECONDS');
    expect(panel).toHaveTextContent('primary true');
    await user.click(within(panel).getByRole('button', { name: 'Remove primary' }));
    expect(screen.getByTestId('kept-action-fields')).not.toHaveTextContent('primary');
    const sent = await save(fetchMock);
    expect(sent['httpForward']).toEqual({ host: 'up.example.com', port: 8080, scheme: 'HTTP', delay });
  });

  it.each([['after New / clear', true], ['after loading another expectation', false]])(
    'picking an expectation again %s does not reapply an earlier removal',
    async (_label, viaClear) => {
      const user = userEvent.setup({ delay: null });
      const a = { id: 'kept-repick', httpRequest: http('/fwd'), httpForward: { host: 'up.example.com', port: 8080, scheme: 'HTTP', primary: true } };
      const b = { id: 'kept-other', httpRequest: http('/other'), httpResponse: { statusCode: 200 } };
      useDashboardStore.setState({
        activeExpectations: [{ key: 'kept-repick', value: a }, { key: 'kept-other', value: b }] as never,
        pendingEditExpectation: a as never,
        view: 'composer',
      });
      render(
        <ThemeProvider theme={buildTheme('dark')}>
          <ComposerView connectionParams={params} />
        </ThemeProvider>,
      );
      await user.click(within(await screen.findByTestId('kept-action-fields')).getByRole('button', { name: 'Remove primary' }));
      expect(screen.queryByTestId('kept-action-fields')).not.toBeInTheDocument();
      if (viaClear) {
        await user.click(screen.getByRole('button', { name: /New \/ clear/ }));
      } else {
        act(() => { useDashboardStore.setState({ pendingEditExpectation: b as never }); });
        await waitFor(() => expect((screen.getByLabelText('Path') as HTMLInputElement).value).toBe('/other'));
      }
      act(() => { useDashboardStore.setState({ pendingEditExpectation: a as never }); });
      expect(await screen.findByTestId('kept-action-fields')).toHaveTextContent('primary true');
      const sent = await save(fetchMock);
      expect(sent['httpForward']).toEqual(a.httpForward);
    },
  );

  it('a value the form shows differently can be replaced by what the form shows', async () => {
    const user = userEvent.setup({ delay: null });
    load({ id: 'kept-lossy', httpRequest: http('/static'), httpResponse: { statusCode: 200, delay: { timeUnit: 'HOURS', value: 1 } } });
    const panel = await screen.findByTestId('kept-action-fields');
    expect(panel).toHaveTextContent('delay 1 HOURS (form shows 60 MINUTES)');
    await user.click(within(panel).getByRole('button', { name: "Use the form's delay" }));
    expect(screen.queryByTestId('kept-action-fields')).not.toBeInTheDocument();
    const sent = await save(fetchMock);
    expect(sent['httpResponse']).toEqual({ statusCode: 200, delay: { timeUnit: 'MINUTES', value: 60 } });
  });

  it('the Java snippet shows a kept field until it is removed', async () => {
    const user = userEvent.setup({ delay: null });
    load({ id: 'kept-java', httpRequest: http('/cb'), httpResponseClassCallback: { callbackClass: 'com.example.Callback', primary: true } });
    const panel = await screen.findByTestId('kept-action-fields');
    await user.click(screen.getByRole('tab', { name: 'Java' }));
    expect(await screen.findByText(/\.withPrimary\(true\)/)).toBeInTheDocument();
    await user.click(within(panel).getByRole('button', { name: 'Remove primary' }));
    await waitFor(() => expect(screen.queryByText(/\.withPrimary\(true\)/)).not.toBeInTheDocument());
  });

  it('Quick mode lists kept response fields and saves without a removed one', async () => {
    const user = userEvent.setup({ delay: null });
    load({ id: 'kept-quick', httpRequest: http('/static'), httpResponse: { statusCode: 200, body: 'ok', primary: true, trailers: { t: ['v'] } } });
    await waitFor(() => expect(screen.getByText('Expectation kind')).toBeInTheDocument());
    await user.click(screen.getByLabelText('Quick mock'));
    const panel = await screen.findByTestId('kept-action-fields');
    expect(panel).toHaveTextContent('trailers {"t":["v"]}');
    await user.click(within(panel).getByRole('button', { name: 'Remove trailers' }));
    const sent = await save(fetchMock, /Update mock/);
    expect(sent['httpResponse']).toEqual({ statusCode: 200, body: 'ok', primary: true });
  });

  it('editing one WebSocket message keeps every message\'s delay', async () => {
    load({
      id: 'kept-ws-edit',
      httpRequest: http('/ws'),
      httpWebSocketResponse: { messages: [{ text: 'a', delay }, { text: 'b', delay: { timeUnit: 'SECONDS', value: 2 } }] },
    });
    expect(await screen.findByTestId('kept-action-fields')).toHaveTextContent('messages[1].delay 2 SECONDS');
    await replaceText(/^Initial messages/, 'a2\nb');
    const sent = await save(fetchMock);
    expect((sent['httpWebSocketResponse'] as Record<string, unknown>)['messages']).toEqual([
      { text: 'a2', delay },
      { text: 'b', delay: { timeUnit: 'SECONDS', value: 2 } },
    ]);
  });

  it('deleting a WebSocket message does not move its delay onto the next one', async () => {
    load({
      id: 'kept-ws-delete',
      httpRequest: http('/ws'),
      httpWebSocketResponse: { messages: [{ text: 'a', delay }, { text: 'b' }, { text: 'c' }] },
    });
    await replaceText(/^Initial messages/, 'b\nc\nd');
    const sent = await save(fetchMock);
    expect((sent['httpWebSocketResponse'] as Record<string, unknown>)['messages']).toEqual([{ text: 'b' }, { text: 'c' }, { text: 'd' }]);
  });

  it('a WebSocket message the form cannot show is kept in place and can be removed', async () => {
    const user = userEvent.setup({ delay: null });
    load({
      id: 'kept-ws-binary',
      httpRequest: http('/ws'),
      httpWebSocketResponse: { messages: [{ text: 'a' }, { binary: 'AAE=' }, { text: 'c' }] },
    });
    const panel = await screen.findByTestId('kept-action-fields');
    expect(panel).toHaveTextContent('messages[1] {"binary":"AAE="}');
    await replaceText(/^Initial messages/, 'a\n\nc2');
    const before = await save(fetchMock);
    expect((before['httpWebSocketResponse'] as Record<string, unknown>)['messages']).toEqual([{ text: 'a' }, { binary: 'AAE=' }, { text: 'c2' }]);

    fetchMock.mockClear();
    await user.click(within(screen.getByTestId('kept-action-fields')).getByRole('button', { name: 'Remove messages[1]' }));
    const after = await save(fetchMock);
    expect((after['httpWebSocketResponse'] as Record<string, unknown>)['messages']).toEqual([{ text: 'a' }, { text: 'c2' }]);
  });

  it('editing an SSE event keeps its delay', async () => {
    load({ id: 'kept-sse', httpRequest: http('/sse'), httpSseResponse: { statusCode: 200, events: [{ data: 'd', delay }] } });
    await replaceText('Data', 'e');
    const sent = await save(fetchMock);
    expect((sent['httpSseResponse'] as Record<string, unknown>)['events']).toEqual([{ data: 'e', delay }]);
  });

  it('editing a gRPC message keeps its delay', async () => {
    load({
      id: 'kept-grpc',
      httpRequest: { method: 'POST', path: '/pkg.Svc/Method' },
      grpcStreamResponse: { statusName: 'OK', messages: [{ json: '{"a":1}', delay }] },
    });
    await replaceText(/^Messages \(one JSON per line\)/, '{{"a":2}');
    const sent = await save(fetchMock);
    expect((sent['grpcStreamResponse'] as Record<string, unknown>)['messages']).toEqual([{ json: '{"a":2}', delay }]);
  });

  it('a binary response lists its carried delay and primary, and a removed one is not saved', async () => {
    const user = userEvent.setup({ delay: null });
    load({
      id: 'kept-binary',
      httpRequest: { path: '/bin', body: { type: 'BINARY', base64Bytes: 'AAEC' } },
      binaryResponse: { binaryData: 'SGk=', delay, primary: true },
    });
    const panel = await screen.findByTestId('kept-action-fields');
    await user.click(within(panel).getByRole('button', { name: 'Remove delay' }));
    const sent = await save(fetchMock);
    expect(sent['binaryResponse']).toEqual({ binaryData: 'SGk=', primary: true });
  });
});
