/**
 * Each row of a list the composer edits (WebSocket messages, matchers and responses, SSE
 * events, gRPC messages) keeps the hidden fields of the item it was loaded from through
 * deletes, inserts, moves and edits in one save; a new row gets none. The hidden row
 * identity never reaches the saved JSON or the code snippets.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ComposerView from '../components/ComposerView';
import { useDashboardStore } from '../store';
import {
  buildExpectationJson,
  standardToCurl,
  standardToJava,
  standardToJson,
  type StandardActionPayload,
  type StandardMatcher,
} from '../lib/standardCodegen';
import { standardToNode } from '../lib/codegen/node';
import { standardToPython } from '../lib/codegen/python';
import { standardToGo } from '../lib/codegen/go';
import { standardToCsharp } from '../lib/codegen/csharp';
import { standardToRuby } from '../lib/codegen/ruby';
import { standardToRust } from '../lib/codegen/rust';

vi.mock('../lib/mcpClient', () => ({
  buildBaseUrl: () => 'http://127.0.0.1:1080',
  callMcpTool: vi.fn().mockResolvedValue({ ok: true, result: { tools: [], count: 0 } }),
}));

vi.mock('../lib/conversationCodegen', () => ({
  listConversationScenarios: () => [],
}));

const params = { host: '127.0.0.1', port: '1080', secure: false };
const ms = (value: number) => ({ timeUnit: 'MILLISECONDS', value });
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

const user = () => userEvent.setup({ delay: null });

async function click(name: string, scope?: HTMLElement) {
  await user().click(scope ? within(scope).getByRole('button', { name }) : await screen.findByRole('button', { name }));
}

async function replaceText(label: string, text: string, scope?: HTMLElement) {
  const field = scope ? within(scope).getByLabelText(label) : await screen.findByLabelText(label);
  await user().clear(field);
  await user().type(field, text);
}

describe('list rows keep their own hidden fields through an edit', () => {
  let fetchMock: ReturnType<typeof vi.spyOn>;

  async function save(): Promise<Record<string, unknown>> {
    await user().click(screen.getByRole('button', { name: /Update expectation/ }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const body = String((fetchMock.mock.calls[0]![1] as RequestInit).body);
    expect(body).not.toContain('itemId');
    return JSON.parse(body) as Record<string, unknown>;
  }

  beforeEach(() => {
    useDashboardStore.setState({ activeExpectations: [], pendingEditExpectation: null, view: 'composer' });
    try { globalThis.sessionStorage?.clear(); } catch { /* noop */ }
    fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 201 }));
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('WebSocket messages: deleting one and editing another', async () => {
    load({
      id: 'ids-ws-delete',
      httpRequest: http('/ws'),
      httpWebSocketResponse: { messages: [{ text: 'a', delay: ms(1) }, { text: 'b', delay: ms(2) }, { text: 'c', delay: ms(3) }] },
    });
    await click('Remove message 1');
    await replaceText('Message 1', 'b2');
    expect(screen.getByTestId('kept-action-fields')).toHaveTextContent('messages[0].delay 2 MILLISECONDS');
    const ws = (await save())['httpWebSocketResponse'] as Record<string, unknown>;
    expect(ws['messages']).toEqual([{ text: 'b2', delay: ms(2) }, { text: 'c', delay: ms(3) }]);
  });

  it('WebSocket messages: inserting one and editing another', async () => {
    load({
      id: 'ids-ws-insert',
      httpRequest: http('/ws'),
      httpWebSocketResponse: { messages: [{ text: 'a', delay: ms(1) }, { text: 'b', delay: ms(2) }] },
    });
    await click('Add message');
    await replaceText('Message 3', 'x');
    await click('Move message 3 up');
    await click('Move message 2 up');
    await replaceText('Message 2', 'a2');
    const ws = (await save())['httpWebSocketResponse'] as Record<string, unknown>;
    expect(ws['messages']).toEqual([{ text: 'x' }, { text: 'a2', delay: ms(1) }, { text: 'b', delay: ms(2) }]);
  });

  it('WebSocket messages: a new message with a deleted message\'s text gets none of its fields', async () => {
    load({
      id: 'ids-ws-fresh',
      httpRequest: http('/ws'),
      httpWebSocketResponse: { messages: [{ text: 'a', delay: ms(1), primary: true }] },
    });
    await click('Remove message 1');
    await click('Add message');
    await replaceText('Message 1', 'a');
    expect(screen.queryByTestId('kept-action-fields')).not.toBeInTheDocument();
    const ws = (await save())['httpWebSocketResponse'] as Record<string, unknown>;
    expect(ws['messages']).toEqual([{ text: 'a' }]);
  });

  it('WebSocket messages: swapping two equal messages swaps their fields', async () => {
    load({
      id: 'ids-ws-swap',
      httpRequest: http('/ws'),
      httpWebSocketResponse: { messages: [{ text: 'x', delay: ms(1) }, { text: 'x', delay: ms(2) }] },
    });
    await click('Move message 2 up');
    const ws = (await save())['httpWebSocketResponse'] as Record<string, unknown>;
    expect(ws['messages']).toEqual([{ text: 'x', delay: ms(2) }, { text: 'x', delay: ms(1) }]);
  });

  it('WebSocket matchers: deleting one and editing another keeps each matcher\'s responses', async () => {
    load({
      id: 'ids-ws-matchers',
      httpRequest: http('/ws'),
      httpWebSocketResponse: {
        matchers: [
          { frameType: 'TEXT', textMatcher: 'p1', responses: [{ text: 'r1', delay: ms(1) }] },
          { frameType: 'TEXT', textMatcher: 'p2', responses: [{ text: 'r2', delay: ms(2) }] },
        ],
      },
    });
    await click('Remove matcher 1');
    await replaceText('Text matcher', 'p2x');
    const ws = (await save())['httpWebSocketResponse'] as Record<string, unknown>;
    expect(ws['matchers']).toEqual([{ frameType: 'TEXT', textMatcher: 'p2x', responses: [{ text: 'r2', delay: ms(2) }] }]);
  });

  it('WebSocket matcher responses: inserting one and editing another', async () => {
    load({
      id: 'ids-ws-responses',
      httpRequest: http('/ws'),
      httpWebSocketResponse: {
        matchers: [{ frameType: 'TEXT', textMatcher: 'p', responses: [{ text: 'r1', delay: ms(1) }, { text: 'r2', delay: ms(2) }] }],
      },
    });
    const matcher = await screen.findByTestId('websocket-matcher-1');
    await click('Add response', matcher);
    await replaceText('Response 3', 'new', matcher);
    await click('Move response 3 up', matcher);
    await click('Move response 2 up', matcher);
    await replaceText('Response 3', 'r2x', matcher);
    const ws = (await save())['httpWebSocketResponse'] as Record<string, unknown>;
    expect((ws['matchers'] as Record<string, unknown>[])[0]!['responses']).toEqual([
      { text: 'new' }, { text: 'r1', delay: ms(1) }, { text: 'r2x', delay: ms(2) },
    ]);
  });

  it('SSE events: deleting one and editing another', async () => {
    load({
      id: 'ids-sse',
      httpRequest: http('/sse'),
      httpSseResponse: { statusCode: 200, events: [{ data: 'd1', delay: ms(1) }, { data: 'd2', delay: ms(2) }] },
    });
    await click('Remove event 1');
    await replaceText('Data', 'd2x');
    const sse = (await save())['httpSseResponse'] as Record<string, unknown>;
    expect(sse['events']).toEqual([{ data: 'd2x', delay: ms(2) }]);
  });

  it('gRPC messages: moving one and editing it', async () => {
    load({
      id: 'ids-grpc',
      httpRequest: { method: 'POST', path: '/pkg.Svc/Method' },
      grpcStreamResponse: { statusName: 'OK', messages: [{ json: '{"n":1}', delay: ms(1) }, { json: '{"n":2}', delay: ms(2) }] },
    });
    await click('Move message 2 up');
    await replaceText('Message 1', '{{"n":22}');
    const grpc = (await save())['grpcStreamResponse'] as Record<string, unknown>;
    expect(grpc['messages']).toEqual([{ json: '{"n":22}', delay: ms(2) }, { json: '{"n":1}', delay: ms(1) }]);
  });
});

describe('editing messages one per row', () => {
  let fetchMock: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    useDashboardStore.setState({ activeExpectations: [], pendingEditExpectation: null, view: 'composer' });
    fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 201 }));
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  async function saved(key: string): Promise<Record<string, unknown>> {
    await user().click(screen.getByRole('button', { name: /Update expectation/ }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    return (JSON.parse(String((fetchMock.mock.calls[0]![1] as RequestInit).body)) as Record<string, Record<string, unknown>>)[key]!;
  }

  it.each([
    ['httpWebSocketResponse', 'text', http('/ws')],
    ['grpcStreamResponse', 'json', { method: 'POST', path: '/pkg.Svc/Method' }],
  ])('%s: a loaded multi-line message stays one message when another is edited', async (key, field, httpRequest) => {
    const multi = field === 'json' ? '{\n  "n": 1\n}' : 'line one\nline two';
    load({ id: `ids-multi-${field}`, httpRequest, [key]: { messages: [{ [field]: multi, delay: ms(1) }, { [field]: field === 'json' ? '{"n":2}' : 'b' }] } });
    expect(((await screen.findByLabelText('Message 1')) as HTMLTextAreaElement).value).toBe(multi);
    expect(screen.getByRole('button', { name: /^Split message 1 into [23] messages$/ })).toBeInTheDocument();
    await replaceText('Message 2', field === 'json' ? '{{"n":3}' : 'c');
    expect((await saved(key))['messages']).toEqual([
      { [field]: multi, delay: ms(1) },
      { [field]: field === 'json' ? '{"n":3}' : 'c' },
    ]);
  });

  it('several lines pasted into an empty row become one row each', async () => {
    load({ id: 'ids-paste', httpRequest: http('/ws'), httpWebSocketResponse: { messages: [{ text: 'a', delay: ms(1) }] } });
    await click('Add message');
    const empty = screen.getByLabelText('Message 2');
    await user().click(empty);
    await user().paste('x\n\ny');
    expect((screen.getByLabelText('Message 2') as HTMLTextAreaElement).value).toBe('x');
    expect((screen.getByLabelText('Message 3') as HTMLTextAreaElement).value).toBe('y');
    expect((await saved('httpWebSocketResponse'))['messages']).toEqual([{ text: 'a', delay: ms(1) }, { text: 'x' }, { text: 'y' }]);
  });

  it.each([
    ['httpWebSocketResponse', 'text', http('/ws')],
    ['grpcStreamResponse', 'json', { method: 'POST', path: '/pkg.Svc/Method' }],
  ])('%s: pretty-printed JSON pasted into an empty row stays one message', async (key, field, httpRequest) => {
    load({ id: `ids-paste-json-${field}`, httpRequest, [key]: { messages: [{ [field]: '{"n":0}' }] } });
    await click('Add message');
    await user().click(screen.getByLabelText('Message 2'));
    await user().paste('{\n  "n": 1\n}');
    expect(screen.queryByLabelText('Message 3')).not.toBeInTheDocument();
    expect((await saved(key))['messages']).toEqual([{ [field]: '{"n":0}' }, { [field]: '{\n  "n": 1\n}' }]);
  });

  it('one JSON value per line pasted into an empty row becomes one row each', async () => {
    load({ id: 'ids-paste-jsonl', httpRequest: { method: 'POST', path: '/pkg.Svc/Method' }, grpcStreamResponse: { messages: [{ json: '{"n":0}' }] } });
    await click('Add message');
    await user().click(screen.getByLabelText('Message 2'));
    await user().paste('{"n":1}\n{"n":2}');
    expect((await saved('grpcStreamResponse'))['messages']).toEqual([{ json: '{"n":0}' }, { json: '{"n":1}' }, { json: '{"n":2}' }]);
  });

  it('a pasted single line loses its trailing newline', async () => {
    load({ id: 'ids-paste-line', httpRequest: http('/ws'), httpWebSocketResponse: { messages: [{ text: 'ab' }] } });
    const field = (await screen.findByLabelText('Message 1')) as HTMLTextAreaElement;
    await user().click(field);
    field.setSelectionRange(1, 1);
    await user().paste('X\n');
    expect(field.value).toBe('aXb');
    await user().click(screen.getByRole('button', { name: 'Add message' }));
    await user().click(screen.getByLabelText('Message 2'));
    await user().paste('y\r\n');
    expect((screen.getByLabelText('Message 2') as HTMLTextAreaElement).value).toBe('y');
  });

  it('lines pasted into a row that has text stay in that message, which can then be split', async () => {
    load({ id: 'ids-split', httpRequest: http('/ws'), httpWebSocketResponse: { messages: [{ text: 'a', delay: ms(1) }] } });
    await user().click(await screen.findByLabelText('Message 1'));
    await user().paste('\nb');
    expect((screen.getByLabelText('Message 1') as HTMLTextAreaElement).value).toBe('a\nb');
    await click('Split message 1 into 2 messages');
    expect((screen.getByLabelText('Message 1') as HTMLTextAreaElement).value).toBe('a');
    expect((screen.getByLabelText('Message 2') as HTMLTextAreaElement).value).toBe('b');
    expect((await saved('httpWebSocketResponse'))['messages']).toEqual([{ text: 'a', delay: ms(1) }, { text: 'b' }]);
  });

  it('a moved row keeps focus on its move button, or the other one at the end of the list', async () => {
    load({ id: 'ids-focus', httpRequest: http('/ws'), httpWebSocketResponse: { messages: [{ text: 'a' }, { text: 'b' }, { text: 'c' }] } });
    await click('Move message 3 up');
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Move message 2 up' }));
    await user().click(screen.getByRole('button', { name: 'Move message 2 up' }));
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Move message 1 down' }));
    expect((screen.getByLabelText('Message 1') as HTMLTextAreaElement).value).toBe('c');
  });

  it('each matcher and its responses are labelled groups', async () => {
    load({
      id: 'ids-groups',
      httpRequest: http('/ws'),
      httpWebSocketResponse: { matchers: [{ frameType: 'TEXT', textMatcher: 'p', responses: [{ text: 'r1' }] }, { frameType: 'ANY', responses: [{ text: 'r2' }] }] },
    });
    const second = await screen.findByRole('group', { name: 'Matcher 2' });
    const responses = within(second).getByRole('group', { name: 'Matcher 2 responses' });
    expect((within(responses).getByLabelText('Response 1') as HTMLTextAreaElement).value).toBe('r2');
    expect(within(responses).getByRole('button', { name: 'Remove response 1' })).toBeInTheDocument();
  });
});

describe('row ids stay out of the JSON and the code snippets', () => {
  const matcher: StandardMatcher = {
    id: '', method: 'GET', path: '/rows', headers: '', queryString: '', cookies: '',
    pathParams: '', body: '', bodyBinary: false, bodyMatcherType: 'string', priority: 0, times: 0,
  };
  const URL = 'http://localhost:1080';
  const emitters: [string, (a: StandardActionPayload) => string][] = [
    ['JSON', (a) => standardToJson(matcher, a)],
    ['curl', (a) => standardToCurl(matcher, a, URL)],
    ['Java', (a) => standardToJava(matcher, a)],
    ['Node', (a) => standardToNode(matcher, a, URL)],
    ['Python', (a) => standardToPython(matcher, a, URL)],
    ['Go', (a) => standardToGo(matcher, a, URL)],
    ['C#', (a) => standardToCsharp(matcher, a, URL)],
    ['Ruby', (a) => standardToRuby(matcher, a, URL)],
    ['Rust', (a) => standardToRust(matcher, a, URL)],
  ];
  // The same action as lines of text (no ids) and as rows carrying ids.
  const actions: [string, StandardActionPayload, StandardActionPayload][] = [
    ['websocket',
      { type: 'websocket', websocket: { subprotocol: '', messages: 'a\n b ', closeConnection: true, matchers: [{ frameType: 'TEXT', textMatcher: 'p', responses: 'r1\nr2' }] } },
      { type: 'websocket', websocket: {
        subprotocol: '',
        messages: [{ itemId: 901, text: 'a' }, { itemId: 902, text: '' }, { itemId: 903, text: ' b ' }],
        closeConnection: true,
        matchers: [{ itemId: 904, frameType: 'TEXT', textMatcher: 'p', responses: [{ itemId: 905, text: 'r1' }, { itemId: 906, text: 'r2' }] }],
      } }],
    ['sse',
      { type: 'sse', sse: { statusCode: 200, headers: '', events: [{ event: 'e', data: 'd', id: '1', retry: '' }], closeConnection: true } },
      { type: 'sse', sse: { statusCode: 200, headers: '', events: [{ itemId: 907, event: 'e', data: 'd', id: '1', retry: '' }], closeConnection: true } }],
    ['grpc_stream',
      { type: 'grpc_stream', grpcStream: { statusName: 'OK', statusMessage: '', headers: '', messages: '{"a":1}\n{"a":2}', closeConnection: false } },
      { type: 'grpc_stream', grpcStream: { statusName: 'OK', statusMessage: '', headers: '', messages: [{ itemId: 908, text: '{"a":1}' }, { itemId: 909, text: '{"a":2}' }], closeConnection: false } }],
  ];

  it.each(actions)('%s: rows emit the same JSON as lines of text', (_name, asText, asRows) => {
    expect(buildExpectationJson(matcher, asRows)).toEqual(buildExpectationJson(matcher, asText));
  });

  for (const [language, emit] of emitters) {
    it.each(actions)(`${language}: %s snippet is unchanged and carries no row id`, (_name, asText, asRows) => {
      const code = emit(asRows);
      expect(code).toBe(emit(asText));
      expect(code).not.toMatch(/itemId|90[1-9]/);
    });
  }
});
