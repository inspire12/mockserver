import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ExpectationPanel from '../components/ExpectationPanel';
import ComposerView from '../components/ComposerView';
import { useDashboardStore } from '../store';
import type { JsonListItem } from '../types';

// The server shortens long expectation bodies in live updates. Nothing may edit, duplicate, test or save
// such a value: each loads the whole expectation by id first, and does nothing if it cannot.

vi.mock('../lib/mcpClient', () => ({
  buildBaseUrl: () => 'http://127.0.0.1:1080',
  callMcpTool: vi.fn().mockResolvedValue({ ok: true, result: { tools: [], count: 0 } }),
}));

vi.mock('../lib/conversationCodegen', () => ({
  listConversationScenarios: () => [],
}));

const params = { host: '127.0.0.1', port: '1080', secure: false };
const WHOLE_BODY = 'whole-body-'.repeat(10);

function expectation(id: string, body: string): Record<string, unknown> {
  return { id, httpRequest: { method: 'GET', path: `/${id}` }, httpResponse: { statusCode: 200, body } };
}

function shortened(id: string): JsonListItem {
  return {
    key: id,
    value: expectation(id, 'first-64-KiB-only'),
    truncatedExpectation: { expectationId: id, part: 'expectation', originalLength: 1048576, shownLength: 65536 },
  };
}

// Applied as a live update, so the store knows which values are shortened.
function receive(items: JsonListItem[]) {
  // a push carrying only expectations, as the store accepts (absent sections are kept)
  useDashboardStore.getState().applyMessage({ activeExpectations: items } as unknown as Parameters<ReturnType<typeof useDashboardStore.getState>['applyMessage']>[0]);
}

interface Retrieval {
  url: string;
  body: unknown;
}

function serving(wholeById: Record<string, Record<string, unknown>>, retrievals: Retrieval[], saved: unknown[]) {
  return vi.fn(async (input: unknown, init?: RequestInit) => {
    const url = String(input);
    if (url.includes('/mockserver/retrieve')) {
      const body = JSON.parse(String(init?.body)) as { id: string };
      retrievals.push({ url, body });
      const whole = wholeById[body.id];
      return new Response(JSON.stringify(whole ? [whole] : []), { status: 200 });
    }
    if (url.includes('/mockserver/expectation')) {
      saved.push(JSON.parse(String(init?.body)));
      return new Response('', { status: 201 });
    }
    return new Response('{}', { status: 404 });
  });
}

function renderComposer() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <ComposerView connectionParams={params} />
    </ThemeProvider>,
  );
}

describe('shortened Active Expectations', () => {
  let retrievals: Retrieval[];
  let saved: unknown[];

  beforeEach(() => {
    retrievals = [];
    saved = [];
    useDashboardStore.setState({
      activeExpectations: [],
      expectationSearch: '',
      notification: null,
      pendingEditExpectation: null,
      view: 'dashboard',
    });
    try {
      globalThis.sessionStorage?.clear();
      globalThis.sessionStorage?.setItem('mockserver-composer-mode', 'advanced');
    } catch { /* noop */ }
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('Edit loads the whole expectation by id before handing it to the Composer', async () => {
    const user = userEvent.setup();
    receive([shortened('big')]);
    vi.stubGlobal('fetch', serving({ big: expectation('big', WHOLE_BODY) }, retrievals, saved));

    render(<ExpectationPanel />);
    await user.click(screen.getByLabelText('Edit expectation'));

    await waitFor(() => expect(useDashboardStore.getState().pendingEditExpectation).toEqual(expectation('big', WHOLE_BODY)));
    expect(retrievals).toHaveLength(1);
    expect(retrievals[0]!.url).toContain('/mockserver/retrieve?type=active_expectations&format=json');
    expect(retrievals[0]!.body).toEqual({ id: 'big' });
    expect(useDashboardStore.getState().view).toBe('composer');
  });

  it('Edit does nothing, and says so, when the whole expectation cannot be loaded', async () => {
    const user = userEvent.setup();
    receive([shortened('gone')]);
    vi.stubGlobal('fetch', serving({}, retrievals, saved));

    render(<ExpectationPanel />);
    await user.click(screen.getByLabelText('Edit expectation'));

    await waitFor(() => expect(useDashboardStore.getState().notification?.severity).toBe('error'));
    expect(useDashboardStore.getState().notification?.message).toMatch(/Could not load the whole expectation, so nothing was done: Expectation gone is no longer registered/);
    expect(useDashboardStore.getState().pendingEditExpectation).toBeNull();
    expect(useDashboardStore.getState().view).toBe('dashboard');
  });

  it('Duplicate copies the whole expectation', async () => {
    const user = userEvent.setup();
    receive([shortened('dup')]);
    vi.stubGlobal('fetch', serving({ dup: expectation('dup', WHOLE_BODY) }, retrievals, saved));

    render(<ExpectationPanel />);
    await user.click(screen.getByLabelText('Duplicate expectation'));

    await waitFor(() => expect(useDashboardStore.getState().pendingEditExpectation).not.toBeNull());
    const copy = useDashboardStore.getState().pendingEditExpectation!;
    expect(copy).not.toHaveProperty('id');
    expect((copy['httpResponse'] as Record<string, unknown>)['body']).toBe(WHOLE_BODY);
  });

  it('Test seeds the matcher playground with the whole expectation', async () => {
    const user = userEvent.setup();
    receive([shortened('tested')]);
    vi.stubGlobal('fetch', serving({ tested: expectation('tested', WHOLE_BODY) }, retrievals, saved));

    render(<ExpectationPanel />);
    await user.click(screen.getByLabelText('Test expectation'));

    const candidate = (await screen.findByLabelText('Candidate expectation JSON')) as HTMLTextAreaElement;
    expect(candidate.value).toContain(WHOLE_BODY);
    expect(candidate.value).not.toContain('first-64-KiB-only');
  });

  it('an expectation sent whole is edited at once, without a request', async () => {
    const user = userEvent.setup();
    const value = expectation('small', 'small body');
    receive([{ key: 'small', value }]);
    const fetchMock = serving({}, retrievals, saved);
    vi.stubGlobal('fetch', fetchMock);

    render(<ExpectationPanel />);
    await user.click(screen.getByLabelText('Edit expectation'));

    expect(useDashboardStore.getState().pendingEditExpectation).toBe(value);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('the expanded row says the body was shortened', async () => {
    const user = userEvent.setup();
    receive([shortened('shown')]);

    render(<ExpectationPanel />);
    await user.click(screen.getByText('/shown'));

    const notice = await screen.findByTestId('truncated-body-notice');
    expect(notice).toHaveTextContent('Body shortened: showing the first 64 KiB of 1.0 MiB.');
    expect(within(notice).queryByTestId('load-full-body')).toBeNull();
    // the copy would be pasted or saved shortened, so it is not offered
    expect(screen.queryByRole('button', { name: 'Copy' })).toBeNull();
  });

  it('an expectation sent whole can still be copied', async () => {
    const user = userEvent.setup();
    receive([{ key: 'whole', value: expectation('whole', 'small body') }]);

    render(<ExpectationPanel />);
    await user.click(screen.getByText('/whole'));

    expect(await screen.findByRole('button', { name: 'Copy' })).toBeInTheDocument();
  });

  it('the store refuses to hand a shortened value to the Composer', () => {
    receive([shortened('direct')]);
    const listed = useDashboardStore.getState().activeExpectations[0]!;

    useDashboardStore.getState().editExpectation(listed.value);

    expect(useDashboardStore.getState().pendingEditExpectation).toBeNull();
    expect(useDashboardStore.getState().notification?.severity).toBe('error');
  });

  it('picking a shortened expectation in the Composer loads it whole, and Update saves the whole body', async () => {
    const user = userEvent.setup({ delay: null });
    receive([shortened('picked')]);
    vi.stubGlobal('fetch', serving({ picked: expectation('picked', WHOLE_BODY) }, retrievals, saved));
    renderComposer();

    await user.click(within(screen.getByTestId('existing-mocks-list')).getByText(/\/picked/));

    await waitFor(() => expect((screen.getByLabelText(/Expectation ID/) as HTMLInputElement).value).toBe('picked'));
    expect(retrievals.map((r) => r.body)).toEqual([{ id: 'picked' }]);
    await user.click(screen.getByRole('button', { name: /(Register|Update) expectation/ }));

    await waitFor(() => expect(saved).toHaveLength(1));
    const sent = saved[0] as Record<string, Record<string, unknown>>;
    expect(sent['httpResponse']!['body']).toBe(WHOLE_BODY);
  });

  it('an edit handed to the Composer is saved whole although the list holds it shortened', async () => {
    const user = userEvent.setup({ delay: null });
    receive([shortened('handed')]);
    vi.stubGlobal('fetch', serving({}, retrievals, saved));
    useDashboardStore.setState({ pendingEditExpectation: expectation('handed', WHOLE_BODY), view: 'composer' });
    renderComposer();

    await waitFor(() => expect((screen.getByLabelText(/Expectation ID/) as HTMLInputElement).value).toBe('handed'));
    await user.click(screen.getByRole('button', { name: /(Register|Update) expectation/ }));

    await waitFor(() => expect(saved).toHaveLength(1));
    expect((saved[0] as Record<string, Record<string, unknown>>)['httpResponse']!['body']).toBe(WHOLE_BODY);
    expect(retrievals).toHaveLength(0);
  });

  it('the Composer does not save over a shortened value that reached it', async () => {
    const user = userEvent.setup({ delay: null });
    receive([shortened('leaked')]);
    const listed = useDashboardStore.getState().activeExpectations[0]!;
    vi.stubGlobal('fetch', serving({}, retrievals, saved));
    // bypasses editExpectation's guard, as a future caller might
    useDashboardStore.setState({ pendingEditExpectation: listed.value, view: 'composer' });
    renderComposer();

    await waitFor(() => expect((screen.getByLabelText(/Expectation ID/) as HTMLInputElement).value).toBe('leaked'));
    await user.click(screen.getByRole('button', { name: /(Register|Update) expectation/ }));

    const error = await screen.findByTestId('register-error');
    expect(error).toHaveTextContent(/shortened/);
    expect(saved).toHaveLength(0);
  });
});
