import { test as base, expect, type APIRequestContext, type Page, type Locator } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { requireOrSkip } from './ci-guard';

// Real-browser end-to-end coverage of the dashboard's resilience views against a
// live MockServer: Inspect ▸ Breakpoints, Resilience ▸ Chaos and Resilience ▸
// Performance. Every test drives the real control plane (REST) and the real
// callback WebSocket, then proves the effect on genuine data-plane requests.

const HOST = process.env.E2E_MS_HOST || '127.0.0.1';
const PORT = process.env.E2E_MS_PORT || '1084';
const ORIGIN = `http://${HOST}:${PORT}`;

// Fail any test whose page logs a console error or throws an uncaught
// exception. A test that deliberately provokes a server 4xx lists the expected
// "Failed to load resource" text in `allowedConsoleErrors`.
const test = base.extend<{ consoleErrors: string[]; allowedConsoleErrors: RegExp[] }>({
  allowedConsoleErrors: [[], { option: true }],
  consoleErrors: [async ({ page, allowedConsoleErrors }, use) => {
    const errors: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() !== 'error') return;
      const text = msg.text();
      if (allowedConsoleErrors.some((re) => re.test(text))) return;
      errors.push(`console.error: ${text}`);
    });
    page.on('pageerror', (err) => errors.push(`pageerror: ${err.message}`));
    await use(errors);
    expect(errors, 'the page logged console errors or threw').toEqual([]);
  }, { auto: true }],
});

async function put(request: APIRequestContext, path: string, data?: unknown) {
  const res = await request.put(`${ORIGIN}${path}`, data === undefined ? {} : { data });
  expect(res.ok(), `PUT ${path} returned ${res.status()}: ${await res.text()}`).toBeTruthy();
  return res;
}

async function mockResponse(request: APIRequestContext, path: string, statusCode: number, body: string) {
  await put(request, '/mockserver/expectation', {
    httpRequest: { path },
    httpResponse: { statusCode, body },
  });
}

async function resetAll(request: APIRequestContext) {
  await put(request, '/mockserver/reset');
  await put(request, '/mockserver/breakpoint/matcher/clear');
  await request.delete(`${ORIGIN}/mockserver/chaosExperiment`);
  await request.delete(`${ORIGIN}/mockserver/serviceChaos`);
}

const stamp = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

// ---------------------------------------------------------------------------
// Breakpoints (Inspect ▸ Breakpoints)
// ---------------------------------------------------------------------------

async function openBreakpoints(page: Page) {
  await page.goto('./#/breakpoints');
  // Register Matcher stays disabled until the callback WebSocket has handed the
  // dashboard its server-assigned clientId — the observable "ready" signal.
  await expect(page.getByRole('button', { name: 'Register Matcher' })).toBeEnabled();
}

async function registerMatcherViaUi(
  page: Page,
  opts: { method?: string; path: string; phases?: { request?: boolean; response?: boolean; stream?: boolean }; skip?: string; expectError?: true },
) {
  if (opts.method) {
    await page.getByRole('combobox', { name: 'Method' }).click();
    await page.getByRole('option', { name: opts.method, exact: true }).click();
  }
  await page.getByLabel('Path (regex)').fill(opts.path);
  if (opts.skip !== undefined) await page.getByLabel('Skip count').fill(opts.skip);
  const phases = opts.phases ?? { request: true, response: true };
  await page.getByRole('checkbox', { name: 'Request', exact: true }).setChecked(!!phases.request);
  await page.getByRole('checkbox', { name: 'Response', exact: true }).setChecked(!!phases.response);
  await page.getByRole('checkbox', { name: 'Response stream frames' }).setChecked(!!phases.stream);
  await page.getByRole('button', { name: 'Register Matcher' }).click();
  // A successful registration resets the form; wait for that before the caller
  // types again, or the reset would wipe the next matcher's fields.
  if (opts.expectError === undefined) await expect(page.getByLabel('Path (regex)')).toHaveValue('');
}

async function serverMatchers(request: APIRequestContext) {
  const res = await request.get(`${ORIGIN}/mockserver/breakpoint/matchers`);
  expect(res.ok()).toBeTruthy();
  return ((await res.json()) as { matchers: Array<{ id: string; phases: string[]; httpRequest?: Record<string, unknown>; skipCount?: number }> }).matchers;
}

function exchangeRow(page: Page, text: string | RegExp): Locator {
  return page.getByRole('row').filter({ hasText: text });
}

test.describe('Breakpoints', () => {
  test.beforeEach(async ({ request }) => resetAll(request));

  test('registers a request breakpoint, pauses a live request, and Continue releases it to the mock', async ({ page, request }) => {
    const path = `/e2e/bp-continue-${stamp()}`;
    await mockResponse(request, path, 200, 'from-mock');
    await openBreakpoints(page);

    await registerMatcherViaUi(page, { method: 'GET', path, phases: { request: true } });

    // The server holds a REQUEST-phase matcher for exactly our path, and the table shows it.
    await expect
      .poll(async () => (await serverMatchers(request)).filter((m) => m.httpRequest?.['path'] === path).map((m) => m.phases.join(',')))
      .toEqual(['REQUEST']);
    await expect(page.getByText(`GET ${path}`)).toBeVisible();
    await expect(page.getByText('Registered Matchers (1)')).toBeVisible();

    // Fire a real data-plane request; it must PAUSE rather than complete.
    let settled = false;
    const inflight = request.get(`${ORIGIN}${path}`, { timeout: 30_000 }).then((r) => {
      settled = true;
      return r;
    });

    await page.getByRole('tab', { name: /Live Exchanges/ }).click();
    const row = exchangeRow(page, path);
    await expect(row).toBeVisible();
    await expect(row).toContainText('REQUEST');
    await expect(row).toContainText('GET');
    await expect(page.getByText('1 paused')).toBeVisible();
    expect(settled, 'the request should be held at the breakpoint').toBe(false);

    await row.getByRole('button', { name: /^Continue/ }).click();
    const res = await inflight;
    expect(res.status()).toBe(200);
    expect(await res.text()).toBe('from-mock');
    await expect(row).toHaveCount(0);
    await expect(page.getByText('No paused exchanges.')).toBeVisible();
  });

  test('Abort on a paused request answers the client with 503 instead of the mock', async ({ page, request }) => {
    const path = `/e2e/bp-abort-${stamp()}`;
    await mockResponse(request, path, 200, 'from-mock');
    await openBreakpoints(page);
    await registerMatcherViaUi(page, { path, phases: { request: true } });
    await expect.poll(async () => (await serverMatchers(request)).length).toBe(1);

    const inflight = request.get(`${ORIGIN}${path}`, { timeout: 30_000 });
    await page.getByRole('tab', { name: /Live Exchanges/ }).click();
    const row = exchangeRow(page, path);
    await row.getByRole('button', { name: /^Abort/ }).click();

    const res = await inflight;
    expect(res.status()).toBe(503);
    expect(await res.text()).toBe('Aborted by breakpoint');
    await expect(row).toHaveCount(0);
  });

  test('Modify on a paused request feeds the edited request into the response template', async ({ page, request }) => {
    const path = `/e2e/bp-modreq-${stamp()}`;
    await put(request, '/mockserver/expectation', {
      httpRequest: { path },
      httpResponseTemplate: { templateType: 'VELOCITY', template: '{ "statusCode": 200, "body": "path=$request.path" }' },
    });
    await openBreakpoints(page);
    await registerMatcherViaUi(page, { path, phases: { request: true } });
    await expect.poll(async () => (await serverMatchers(request)).length).toBe(1);

    const inflight = request.get(`${ORIGIN}${path}`, { timeout: 30_000 });
    await page.getByRole('tab', { name: /Live Exchanges/ }).click();
    await exchangeRow(page, path).getByRole('button', { name: /^Modify/ }).click();

    const dialog = page.getByRole('dialog', { name: 'Modify Request' });
    const editor = dialog.getByRole('textbox');
    // The dialog shows the full paused request for inspection.
    const original = JSON.parse(await editor.inputValue()) as Record<string, unknown>;
    expect(original['path']).toBe(path);
    expect(original['method']).toBe('GET');

    // Invalid JSON is refused in place, keeping the dialog open.
    await editor.fill('{ not json');
    await dialog.getByRole('button', { name: 'Send Modified' }).click();
    await expect(dialog.getByText('Invalid JSON')).toBeVisible();

    await editor.fill(JSON.stringify({ ...original, path: `${path}/edited` }, null, 2));
    await dialog.getByRole('button', { name: 'Send Modified' }).click();
    await expect(dialog).toBeHidden();

    const res = await inflight;
    expect(res.status()).toBe(200);
    expect(await res.text()).toBe(`path=${path}/edited`);
  });

  test('pauses at the RESPONSE phase, offers no Abort, and Modify rewrites the response the client receives', async ({ page, request }) => {
    const path = `/e2e/bp-modresp-${stamp()}`;
    await mockResponse(request, path, 200, 'from-mock');
    await openBreakpoints(page);
    await registerMatcherViaUi(page, { path, phases: { response: true } });
    await expect.poll(async () => (await serverMatchers(request)).map((m) => m.phases.join(','))).toEqual(['RESPONSE']);

    const inflight = request.get(`${ORIGIN}${path}`, { timeout: 30_000 });
    await page.getByRole('tab', { name: /Live Exchanges/ }).click();
    const row = page.getByRole('row').filter({ hasText: 'RESPONSE' }).filter({ hasText: '200' });
    await expect(row).toBeVisible();
    await expect(row.getByRole('button', { name: /not applicable for responses/ })).toBeDisabled();

    await row.getByRole('button', { name: /^Modify/ }).click();
    const dialog = page.getByRole('dialog', { name: 'Modify Response' });
    const editor = dialog.getByRole('textbox');
    const original = JSON.parse(await editor.inputValue()) as Record<string, unknown>;
    expect(original['statusCode']).toBe(200);
    await editor.fill(JSON.stringify({ statusCode: 418, body: 'rewritten-at-breakpoint' }));
    await dialog.getByRole('button', { name: 'Send Modified' }).click();

    const res = await inflight;
    expect(res.status()).toBe(418);
    expect(await res.text()).toBe('rewritten-at-breakpoint');
  });

  test('a skip count lets the first N matching requests through and pauses the next', async ({ page, request }) => {
    const path = `/e2e/bp-skip-${stamp()}`;
    await mockResponse(request, path, 200, 'from-mock');
    await openBreakpoints(page);
    await registerMatcherViaUi(page, { path, phases: { request: true }, skip: '1' });
    await expect.poll(async () => (await serverMatchers(request)).map((m) => m.skipCount)).toEqual([1]);
    await expect(page.getByRole('row').filter({ hasText: path })).toContainText('1');

    // The first hit is skipped: it completes without pausing.
    const first = await request.get(`${ORIGIN}${path}`, { timeout: 10_000 });
    expect(await first.text()).toBe('from-mock');

    // The second hit pauses.
    const second = request.get(`${ORIGIN}${path}`, { timeout: 30_000 });
    await page.getByRole('tab', { name: /Live Exchanges/ }).click();
    const row = exchangeRow(page, path);
    await expect(row).toHaveCount(1);
    await row.getByRole('button', { name: /^Continue/ }).click();
    expect(await (await second).text()).toBe('from-mock');
  });

  test('form validation refuses a matcher with no phase or a negative skip count', async ({ page, request }) => {
    await openBreakpoints(page);
    await registerMatcherViaUi(page, { path: '/e2e/never', phases: {}, expectError: true });
    await expect(page.getByText('At least one phase must be selected.')).toBeVisible();

    await registerMatcherViaUi(page, { path: '/e2e/never', phases: { request: true }, skip: '-1', expectError: true });
    await expect(page.getByText('Skip count must be a non-negative integer.')).toBeVisible();
    expect(await serverMatchers(request)).toEqual([]);
  });

  // E2E-RES-10: an empty form would register a catch-all matcher that pauses all
  // traffic, so the dashboard asks first.
  test('an empty matcher form asks before registering a catch-all matcher', async ({ page, request }) => {
    await openBreakpoints(page);
    await page.getByRole('button', { name: 'Register Matcher' }).click();
    const confirm = page.getByRole('dialog', { name: 'Pause every request?' });
    await confirm.getByRole('button', { name: 'Cancel' }).click();
    await expect(confirm).toHaveCount(0);
    expect(await serverMatchers(request)).toEqual([]);

    await page.getByRole('button', { name: 'Register Matcher' }).click();
    await confirm.getByRole('button', { name: 'Pause every request' }).click();
    await expect.poll(async () => (await serverMatchers(request)).map((m) => m.httpRequest?.['path'])).toEqual(['.*']);
  });

  test('removes one matcher and clears all matchers, after which requests are no longer paused', async ({ page, request }) => {
    const id = stamp();
    const a = `/e2e/bp-rm-a-${id}`;
    const b = `/e2e/bp-rm-b-${id}`;
    const c = `/e2e/bp-rm-c-${id}`;
    await mockResponse(request, a, 200, 'a');
    await openBreakpoints(page);
    for (const p of [a, b, c]) await registerMatcherViaUi(page, { path: p, phases: { request: true } });
    await expect.poll(async () => (await serverMatchers(request)).length).toBe(3);
    await expect(page.getByText('Registered Matchers (3)')).toBeVisible();

    // Remove just one, from its own row.
    await page.getByRole('row').filter({ hasText: a }).getByRole('button', { name: /^Remove/ }).click();
    await expect.poll(async () => (await serverMatchers(request)).map((m) => m.httpRequest?.['path']).sort()).toEqual([b, c].sort());
    await expect(page.getByText('Registered Matchers (2)')).toBeVisible();
    const res = await request.get(`${ORIGIN}${a}`, { timeout: 10_000 });
    expect(await res.text(), 'with its matcher removed the request is not paused').toBe('a');

    // Clear the rest through the confirmation dialog — cancel first, then confirm.
    await page.getByRole('button', { name: 'Clear all matchers' }).click();
    const confirm = page.getByRole('dialog', { name: 'Clear all breakpoint matchers?' });
    await confirm.getByRole('button', { name: 'Cancel' }).click();
    await expect(confirm).toBeHidden();
    expect((await serverMatchers(request)).length).toBe(2);

    await page.getByRole('button', { name: 'Clear all matchers' }).click();
    await confirm.getByRole('button', { name: 'Clear all matchers' }).click();
    await expect.poll(async () => (await serverMatchers(request)).length).toBe(0);
    await expect(page.getByText('No matchers registered yet.')).toBeVisible();
  });

  test('keeps the matcher and paused requests while the user navigates to another view and back', async ({ page, request }) => {
    const path = `/e2e/bp-nav-${stamp()}`;
    await mockResponse(request, path, 200, 'from-mock');
    await openBreakpoints(page);
    await registerMatcherViaUi(page, { path, phases: { request: true } });
    await expect.poll(async () => (await serverMatchers(request)).length).toBe(1);

    // Leave the view (no reload), fire the request while it is unmounted, then come back.
    await page.evaluate(() => { window.location.hash = '#/chaos'; });
    await expect(page.getByText('Service Chaos', { exact: true })).toBeVisible();
    const inflight = request.get(`${ORIGIN}${path}`, { timeout: 30_000 });
    await expect.poll(async () => (await serverMatchers(request)).length, 'matcher survives navigation').toBe(1);
    await page.evaluate(() => { window.location.hash = '#/breakpoints'; });

    await page.getByRole('tab', { name: /Live Exchanges/ }).click();
    await exchangeRow(page, path).getByRole('button', { name: /^Continue/ }).click();
    expect(await (await inflight).text()).toBe('from-mock');
  });

  test('"Set breakpoint" on a dashboard log row pre-fills the matcher form', async ({ page, request }) => {
    const path = `/e2e/bp-prefill-${stamp()}`;
    await put(request, '/mockserver/expectation', { httpRequest: { method: 'POST', path }, httpResponse: { statusCode: 200 } });
    await request.post(`${ORIGIN}${path}`, { data: 'hello' });
    await page.goto('./#/dashboard');
    const setBp = page.getByRole('button', { name: 'Set breakpoint on this request' }).filter({ visible: true }).first();
    await expect(setBp).toBeVisible();
    await setBp.click();
    await expect(page.getByLabel('Path (regex)')).toHaveValue(path);
    await expect(page.getByRole('combobox', { name: 'Method' })).toHaveText('POST');
  });

  test('keyboard-only: register a matcher with Tab / Space / Enter', async ({ page, request }) => {
    const path = `/e2e/bp-kbd-${stamp()}`;
    await openBreakpoints(page);
    await page.getByLabel('Path (regex)').focus();
    await page.keyboard.type(path);
    // Untick "Response" with Space, leaving just the REQUEST phase.
    await page.getByRole('checkbox', { name: 'Response', exact: true }).focus();
    await page.keyboard.press('Space');
    await page.keyboard.press('Tab'); // Response stream frames
    await page.keyboard.press('Tab'); // Inbound stream frames
    await page.keyboard.press('Tab'); // Register Matcher
    await expect(page.getByRole('button', { name: 'Register Matcher' })).toBeFocused();
    await page.keyboard.press('Enter');
    await expect.poll(async () => (await serverMatchers(request)).map((m) => `${m.httpRequest?.['path']}|${m.phases.join(',')}`)).toEqual([`${path}|REQUEST`]);
  });

  // E2E-RES-1: the server tells the dashboard when breakpointTimeoutMillis
  // auto-continues a paused exchange, so it leaves the list and an open Modify
  // dialog refuses to send instead of silently doing nothing.
  test('a paused exchange that the server auto-continues on timeout leaves the Live Exchanges list', async ({ page, request }) => {
    const cfg = (await (await request.get(`${ORIGIN}/mockserver/configuration`)).json()) as { breakpointTimeoutMillis: number };
    await put(request, '/mockserver/configuration', { breakpointTimeoutMillis: 4000 });
    try {
      const path = `/e2e/bp-timeout-${stamp()}`;
      await mockResponse(request, path, 200, 'from-mock');
      await openBreakpoints(page);
      await registerMatcherViaUi(page, { path, phases: { request: true } });
      await expect.poll(async () => (await serverMatchers(request)).length).toBe(1);
      const inflight = request.get(`${ORIGIN}${path}`, { timeout: 30_000 });
      await page.getByRole('tab', { name: /Live Exchanges/ }).click();
      await expect(exchangeRow(page, path)).toBeVisible();
      await exchangeRow(page, path).getByRole('button', { name: /^Modify/ }).click();
      const dialog = page.getByRole('dialog', { name: 'Modify Request' });
      await expect(dialog).toBeVisible();

      expect(await (await inflight).text()).toBe('from-mock');
      await expect(dialog.getByText('This item is no longer paused')).toBeVisible();
      await dialog.getByRole('button', { name: 'Send Modified' }).click();
      await expect(dialog, 'a released exchange cannot be sent').toBeVisible();
      await dialog.getByRole('button', { name: 'Cancel' }).click();
      await expect(exchangeRow(page, path)).toHaveCount(0);
      await expect(page.getByTestId('breakpoint-release-notice')).toContainText(`Request GET ${path} is no longer paused`);
      await expect(page.getByTestId('breakpoint-release-notice')).toContainText('breakpoint timeout (4000 ms)');
    } finally {
      await put(request, '/mockserver/configuration', { breakpointTimeoutMillis: cfg.breakpointTimeoutMillis });
    }
  });
});

// Two-event SSE upstream (second event delayed) behind a forward expectation, so the
// forwarded response streams through MockServer's RESPONSE_STREAM hold point.
async function forwardedSse(request: APIRequestContext, id: string, secondDelayMs: number) {
  const upstream = `/e2e/sse-up-${id}`;
  const front = `/e2e/sse-front-${id}`;
  await put(request, '/mockserver/expectation', {
    httpRequest: { path: upstream },
    httpSseResponse: {
      statusCode: 200,
      events: [
        { event: 'message', data: 'first event', id: '1' },
        { event: 'message', data: 'second event', id: '2', delay: { timeUnit: 'MILLISECONDS', value: secondDelayMs } },
      ],
      closeConnection: true,
    },
  });
  await put(request, '/mockserver/expectation', {
    httpRequest: { path: front },
    httpOverrideForwardedRequest: { requestOverride: { path: upstream, headers: [{ name: 'Host', values: [`${HOST}:${PORT}`] }] } },
  });
  return front;
}

// Reads a streaming response body until `predicate` holds (or the stream ends).
async function readStreamUntil(url: string, predicate: (text: string) => boolean): Promise<{ text: string; ended: boolean }> {
  const res = await fetch(url);
  const reader = res.body!.getReader();
  const decoder = new TextDecoder();
  let text = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) return { text, ended: true };
    text += decoder.decode(value, { stream: true });
    if (predicate(text)) {
      await reader.cancel();
      return { text, ended: false };
    }
  }
}

test.describe('Breakpoints — stream frames', () => {
  test.beforeEach(async ({ request }) => resetAll(request));

  // E2E-RES-3: a "Response stream frames" matcher pauses each event of an
  // httpSseResponse mock, as it does a forwarded stream's frames.
  test('a stream-frame breakpoint pauses the frames of an SSE mock', async ({ page, request }) => {
    const path = `/e2e/sse-mock-${stamp()}`;
    await put(request, '/mockserver/expectation', {
      httpRequest: { path },
      httpSseResponse: { statusCode: 200, events: [{ event: 'message', data: 'first event', id: '1' }], closeConnection: true },
    });
    await openBreakpoints(page);
    await registerMatcherViaUi(page, { path, phases: { stream: true } });
    await expect.poll(async () => (await serverMatchers(request)).length).toBe(1);
    const reading = readStreamUntil(`${ORIGIN}${path}`, () => false);
    await page.getByRole('tab', { name: /Live Streams/ }).click();
    const row = page.getByRole('row').filter({ hasText: path });
    await expect(row).toHaveCount(1);
    await row.getByRole('button', { name: /^Continue/ }).click();
    expect((await reading).text).toContain('first event');
  });

  test('pauses each frame of a forwarded SSE stream and Modify rewrites the frame the client receives', async ({ page, request }) => {
    const front = await forwardedSse(request, stamp(), 5000);
    await openBreakpoints(page);
    await registerMatcherViaUi(page, { path: front, phases: { stream: true } });
    await expect.poll(async () => (await serverMatchers(request)).map((m) => m.phases.join(','))).toEqual(['RESPONSE_STREAM']);

    const reading = readStreamUntil(`${ORIGIN}${front}`, (t) => t.includes('data: MODIFIED'));
    await page.getByRole('tab', { name: /Live Streams/ }).click();
    const row = page.getByRole('row').filter({ hasText: front });
    await expect(row).toHaveCount(1);
    await expect(row).toContainText('RESPONSE_STREAM');
    await expect(row).toContainText('Outbound');
    await expect(row).toContainText('#0');

    await row.getByRole('button', { name: /^Modify/ }).click();
    const dialog = page.getByRole('dialog', { name: 'Modify Stream Frame' });
    // The frame body is shown decoded (not Base64) for editing.
    await expect(dialog.getByRole('textbox')).toHaveValue(/data: first event/);
    await dialog.getByRole('textbox').fill('id: 1\nevent: message\ndata: MODIFIED\n\n');
    await dialog.getByRole('button', { name: 'Send Modified Frame' }).click();

    const { text } = await reading;
    expect(text).toContain('data: MODIFIED');
    expect(text).not.toContain('first event');
  });

  test('Close on a paused frame ends the client stream without the frame', async ({ page, request }) => {
    const front = await forwardedSse(request, stamp(), 5000);
    await openBreakpoints(page);
    await registerMatcherViaUi(page, { path: front, phases: { stream: true } });
    await expect.poll(async () => (await serverMatchers(request)).length).toBe(1);

    const reading = readStreamUntil(`${ORIGIN}${front}`, () => false);
    await page.getByRole('tab', { name: /Live Streams/ }).click();
    const row = page.getByRole('row').filter({ hasText: front });
    await row.getByRole('button', { name: /^Close/ }).click();
    await expect(row).toHaveCount(0);

    const { text, ended } = await reading;
    expect(ended).toBe(true);
    expect(text).not.toContain('first event');
  });

  // E2E-RES-2: when the upstream stream completes while a frame is held, the end
  // of the client response waits for that frame, so it is delivered once continued.
  test('a frame held when the upstream stream completes is still delivered once continued', async ({ page, request }) => {
    const front = await forwardedSse(request, stamp(), 0);
    await openBreakpoints(page);
    await registerMatcherViaUi(page, { path: front, phases: { stream: true } });
    await expect.poll(async () => (await serverMatchers(request)).length).toBe(1);

    let ended = false;
    const reading = readStreamUntil(`${ORIGIN}${front}`, () => false).finally(() => { ended = true; });
    await page.getByRole('tab', { name: /Live Streams/ }).click();
    const rows = page.getByRole('row').filter({ hasText: front });
    await expect(rows.first()).toBeVisible();
    // The upstream may deliver both events in one chunk or two, so continue
    // whichever frame is held until the client stream ends.
    await expect.poll(async () => {
      if ((await rows.count()) > 0) {
        await rows.first().getByRole('button', { name: /^Continue/ }).click({ timeout: 2_000 }).catch(() => undefined);
      }
      return ended;
    }, { timeout: 30_000 }).toBe(true);
    const { text } = await reading;
    expect(text).toContain('first event');
    expect(text).toContain('second event');
    await expect(rows).toHaveCount(0);
  });
});


// ---------------------------------------------------------------------------
// Chaos (Resilience ▸ Chaos)
// ---------------------------------------------------------------------------

// Service chaos applies to matched FORWARD expectations, keyed on the request's
// Host header. Build a forward (front → backend mock, both on this server) and a
// unique virtual host so a test's faults only ever hit its own traffic.
async function chaosTarget(request: APIRequestContext) {
  const id = stamp();
  const svc = `svc-${id}.e2e`;
  const backend = `/e2e/chaos-backend-${id}`;
  const front = `/e2e/chaos-front-${id}`;
  await mockResponse(request, backend, 200, 'backend-ok');
  await put(request, '/mockserver/expectation', {
    httpRequest: { path: front },
    httpOverrideForwardedRequest: { requestOverride: { path: backend, headers: [{ name: 'Host', values: [`${HOST}:${PORT}`] }] } },
  });
  const hit = async () => {
    const r = await request.get(`${ORIGIN}${front}`, { headers: { Host: svc }, timeout: 15_000 });
    return { status: r.status(), body: await r.text() };
  };
  return { svc, hit };
}

async function serverServiceChaos(request: APIRequestContext): Promise<Record<string, Record<string, unknown>>> {
  const res = await request.get(`${ORIGIN}/mockserver/serviceChaos`);
  expect(res.ok()).toBeTruthy();
  return ((await res.json()) as { services?: Record<string, Record<string, unknown>> }).services ?? {};
}

async function experimentStatus(request: APIRequestContext): Promise<Record<string, unknown> | null> {
  const res = await request.get(`${ORIGIN}/mockserver/chaosExperiment`);
  if (res.status() === 404) return null;
  const body = (await res.json()) as Record<string, unknown>;
  return body['status'] === 'none' ? null : body;
}

async function openChaos(page: Page) {
  await page.goto('./#/chaos');
  await expect(page.getByText('Service Chaos', { exact: true })).toBeVisible();
}

function httpSection(page: Page) {
  return page.getByText('HTTP Service Chaos', { exact: true }).locator('xpath=ancestor::div[contains(@class,"MuiPaper-root")][1]');
}

async function expandHttp(page: Page) {
  await page.getByRole('button', { name: 'Expand HTTP chaos' }).click();
  await expect(page.getByRole('button', { name: 'Register', exact: true })).toBeVisible();
}

async function expandExperiments(page: Page) {
  await page.getByRole('button', { name: 'Expand experiments' }).click();
  await expect(page.getByLabel('Experiment name')).toBeVisible();
}

// The experiment editor's stage fields reuse labels ("Host", "Error status", …)
// that the collapsed HTTP form also renders, so scope to the editor.
function expEditor(page: Page) {
  return page.getByText('Define experiment', { exact: true }).locator('xpath=ancestor::div[contains(@class,"MuiPaper-root")][1]');
}

async function fillStage(page: Page, idx: number, f: { duration: string; host: string; status?: string; prob?: string; latency?: string }) {
  const ed = expEditor(page);
  await ed.getByLabel('Duration ms', { exact: true }).nth(idx).fill(f.duration);
  await ed.getByLabel('Host', { exact: true }).nth(idx).fill(f.host);
  if (f.status) await ed.getByLabel('Error status', { exact: true }).nth(idx).fill(f.status);
  if (f.prob) await ed.getByLabel('Error prob (0-1)', { exact: true }).nth(idx).fill(f.prob);
  if (f.latency) await ed.getByLabel('Latency ms', { exact: true }).nth(idx).fill(f.latency);
}

// Auto-halt is a server-wide circuit breaker that wipes ALL service chaos once
// destructive faults cross its threshold, so pin it per test (off unless a test
// is about auto-halt) and restore the server's original values afterwards.
async function withAutoHalt(request: APIRequestContext, cfg: { chaosAutoHaltEnabled: boolean; chaosAutoHaltErrorThreshold?: number; chaosAutoHaltWindowMillis?: number }) {
  await put(request, '/mockserver/configuration', cfg);
}

test.describe('Chaos', () => {
  let originalAutoHalt: Record<string, unknown> = {};
  test.beforeAll(async ({ request }) => {
    const cfg = (await (await request.get(`${ORIGIN}/mockserver/configuration`)).json()) as Record<string, unknown>;
    originalAutoHalt = {
      chaosAutoHaltEnabled: cfg['chaosAutoHaltEnabled'],
      chaosAutoHaltErrorThreshold: cfg['chaosAutoHaltErrorThreshold'],
      chaosAutoHaltWindowMillis: cfg['chaosAutoHaltWindowMillis'],
    };
  });
  test.beforeEach(async ({ request }) => {
    await resetAll(request);
    await withAutoHalt(request, { chaosAutoHaltEnabled: false });
    // The saved-profile library survives /mockserver/reset; drop this suite's leftovers.
    const listed = (await (await request.get(`${ORIGIN}/mockserver/chaosExperiment/profiles`)).json()) as { profiles: string[] };
    for (const n of listed.profiles.filter((p) => p.startsWith('e2e-'))) {
      await request.delete(`${ORIGIN}/mockserver/chaosExperiment/profiles/${encodeURIComponent(n)}`);
    }
  });
  test.afterAll(async ({ request }) => {
    await request.delete(`${ORIGIN}/mockserver/chaosExperiment`);
    await request.delete(`${ORIGIN}/mockserver/serviceChaos`);
    await put(request, '/mockserver/configuration', originalAutoHalt);
  });

  test('registers an HTTP chaos profile for a host, faults real forwarded traffic, and removing it restores it', async ({ page, request }) => {
    const { svc, hit } = await chaosTarget(request);
    expect(await hit()).toEqual({ status: 200, body: 'backend-ok' });

    await openChaos(page);
    await expandHttp(page);
    await expect(page.getByText('No service-scoped chaos registered.')).toBeVisible();
    await page.getByLabel('Host', { exact: true }).fill(svc);
    await page.getByLabel('Error status', { exact: true }).fill('503');
    await page.getByLabel('Error prob (0–1)', { exact: true }).fill('1');
    await page.getByRole('button', { name: 'Register', exact: true }).click();

    await expect.poll(async () => (await serverServiceChaos(request))[svc]).toMatchObject({ errorStatus: 503, errorProbability: 1 });
    await expect(httpSection(page).getByText('1 active')).toBeVisible();
    await expect(page.getByText('error 503 @ 100%')).toBeVisible();
    // The form resets after a successful registration.
    await expect(page.getByLabel('Host', { exact: true })).toHaveValue('');

    const faulted = await hit();
    expect(faulted.status).toBe(503);
    expect(faulted.body).toContain('chaos_injected');

    await page.getByRole('button', { name: `Remove chaos for ${svc}` }).click();
    await expect.poll(async () => Object.keys(await serverServiceChaos(request))).toEqual([]);
    await expect(page.getByText('No service-scoped chaos registered.')).toBeVisible();
    expect(await hit()).toEqual({ status: 200, body: 'backend-ok' });
  });

  test('edits a registered profile in place and the new fault takes effect', async ({ page, request }) => {
    const { svc, hit } = await chaosTarget(request);
    await put(request, '/mockserver/serviceChaos', { host: svc, chaos: { errorStatus: 503, errorProbability: 1 } });
    await openChaos(page);
    await expandHttp(page);
    await expect(page.getByText('error 503 @ 100%')).toBeVisible();

    await page.getByRole('button', { name: `Edit chaos for ${svc}` }).click();
    // The edit row is pre-filled from the server's profile (the register form's
    // fields with the same labels come first in the DOM).
    const editStatus = page.getByLabel('Error status', { exact: true }).nth(1);
    await expect(editStatus).toHaveValue('503');
    await editStatus.fill('429');
    await page.getByRole('button', { name: 'Apply', exact: true }).click();

    await expect.poll(async () => (await serverServiceChaos(request))[svc]?.['errorStatus']).toBe(429);
    await expect(page.getByText('error 429 @ 100%')).toBeVisible();
    expect((await hit()).status).toBe(429);
  });

  // E2E-RES-4 (fixed): the in-place editor sent a merge PATCH of only the
  // non-blank fields, so blanking a field could not remove that fault.
  test('E2E-RES-4: clearing a fault in the in-place editor removes it from the profile', async ({ page, request }) => {
    const { svc, hit } = await chaosTarget(request);
    await put(request, '/mockserver/serviceChaos', { host: svc, chaos: { errorStatus: 503, errorProbability: 1 } });
    await openChaos(page);
    await expandHttp(page);
    await page.getByRole('button', { name: `Edit chaos for ${svc}` }).click();
    await page.getByLabel('Error status', { exact: true }).nth(1).fill('');
    await page.getByLabel('Error prob (0–1)', { exact: true }).nth(1).fill('');
    await page.getByLabel('Latency ms', { exact: true }).nth(1).fill('50');
    await page.getByRole('button', { name: 'Apply', exact: true }).click();
    await expect.poll(async () => (await serverServiceChaos(request))[svc]?.['latency']).toBeTruthy();
    expect((await serverServiceChaos(request))[svc]?.['errorStatus']).toBeUndefined();
    expect((await hit()).status).toBe(200);
  });

  test('refuses invalid chaos input with a readable message and registers nothing', async ({ page, request }) => {
    await openChaos(page);
    await expandHttp(page);
    const register = page.getByRole('button', { name: 'Register', exact: true });

    await register.click();
    await expect(page.getByText('Host is required')).toBeVisible();

    await page.getByLabel('Host', { exact: true }).fill('*.example.com');
    await register.click();
    await expect(page.getByText(/wildcards such as \*\.example\.com are not supported/)).toBeVisible();

    await page.getByLabel('Host', { exact: true }).fill('api.example.com');
    await page.getByLabel('Error status', { exact: true }).fill('503');
    await page.getByLabel('Error prob (0–1)', { exact: true }).fill('2');
    await register.click();
    await expect(page.getByText('Error probability must be between 0 and 1')).toBeVisible();

    await page.getByLabel('Error status', { exact: true }).fill('');
    await page.getByLabel('Error prob (0–1)', { exact: true }).fill('0.5');
    await register.click();
    await expect(page.getByText('Error probability needs an error status (e.g. 503)')).toBeVisible();

    expect(await serverServiceChaos(request)).toEqual({});
  });

  test('a TTL auto-reverts the fault and the countdown is shown while it is active', async ({ page, request }) => {
    const { svc, hit } = await chaosTarget(request);
    await openChaos(page);
    await expandHttp(page);
    await page.getByLabel('Host', { exact: true }).fill(svc);
    await page.getByLabel('Error status', { exact: true }).fill('503');
    await page.getByLabel('Error prob (0–1)', { exact: true }).fill('1');
    await page.getByLabel('TTL ms', { exact: true }).fill('3000');
    await page.getByRole('button', { name: 'Register', exact: true }).click();

    await expect(page.getByText(/auto-revert in/)).toBeVisible();
    expect((await hit()).status).toBe(503);
    await expect.poll(async () => (await hit()).status, { timeout: 15_000 }).toBe(200);
    await expect(page.getByText('No service-scoped chaos registered.')).toBeVisible({ timeout: 15_000 });
  });

  test('Quick Chaos: one toggle faults a target host and switching it off removes the rule', async ({ page, request }) => {
    const { svc, hit } = await chaosTarget(request);
    await openChaos(page);
    await page.getByLabel('Target Host').fill(svc);
    // Turn the default 10% up to 100% so every request is faulted (deterministic).
    const slider = page.getByRole('slider', { name: 'Percentage of requests affected' });
    await slider.focus();
    await page.keyboard.press('End');
    await expect(page.getByText('100%', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '500 Errors' })).toHaveAttribute('aria-pressed', 'true');

    const toggle = page.getByRole('switch', { name: 'Enable Chaos' }).or(page.getByRole('checkbox', { name: 'Enable Chaos' }));
    // The switch is controlled by the polled server registry, so it flips only
    // once the rule exists server-side — click, then observe.
    await toggle.click();
    await expect(toggle).toBeChecked();
    await expect.poll(async () => (await serverServiceChaos(request))[svc]).toMatchObject({ errorStatus: 500, errorProbability: 1 });
    await expect(page.getByLabel('Target Host')).toBeDisabled();
    expect((await hit()).status).toBe(500);

    await toggle.click();
    await expect(toggle).not.toBeChecked();
    await expect.poll(async () => Object.keys(await serverServiceChaos(request))).toEqual([]);
    expect((await hit()).status).toBe(200);
  });

  test('Quick Chaos refuses to enable without a target host', async ({ page, request }) => {
    await openChaos(page);
    const toggle = page.getByRole('switch', { name: 'Enable Chaos' }).or(page.getByRole('checkbox', { name: 'Enable Chaos' }));
    await toggle.click();
    await expect(page.getByText('Enter a target host to enable Quick Chaos')).toBeVisible();
    await expect(toggle).not.toBeChecked();
    expect(await serverServiceChaos(request)).toEqual({});
  });

  test('Clear HTTP asks for confirmation then removes every host', async ({ page, request }) => {
    for (const h of ['a.e2e', 'b.e2e']) await put(request, '/mockserver/serviceChaos', { host: h, chaos: { errorStatus: 503 } });
    await openChaos(page);
    await expect(httpSection(page).getByText('2 active')).toBeVisible();
    await page.getByRole('button', { name: 'Clear HTTP' }).click();
    const dialog = page.getByRole('dialog', { name: 'Clear all HTTP chaos?' });
    await expect(dialog).toContainText('all 2 registered hosts');
    await dialog.getByRole('button', { name: 'Clear HTTP chaos' }).click();
    await expect.poll(async () => Object.keys(await serverServiceChaos(request))).toEqual([]);
    await expect(httpSection(page).getByText('0 active')).toBeVisible();
  });

  test('starts a chaos experiment whose stage faults real traffic, shows live status, and Stop ends it', async ({ page, request }) => {
    const { svc, hit } = await chaosTarget(request);
    const name = `e2e-exp-${stamp()}`;
    await openChaos(page);
    await expandExperiments(page);
    await page.getByLabel('Experiment name').fill(name);
    await fillStage(page, 0, { duration: '60000', host: svc, status: '503', prob: '1' });
    await page.getByRole('button', { name: 'Start Experiment' }).click();

    await expect.poll(async () => (await experimentStatus(request))?.['status']).toBe('running');
    const statusPanel = page.getByText('Experiment Status').locator('xpath=..');
    await expect(statusPanel).toContainText(name);
    await expect(statusPanel).toContainText('running');
    await expect(statusPanel).toContainText('Stage 1/1');
    await expect(page.getByText(/Remaining:/)).toBeVisible();
    expect((await hit()).status, 'the running stage faults forwarded traffic').toBe(503);

    await page.getByRole('button', { name: 'Stop', exact: true }).click();
    await expect.poll(async () => (await experimentStatus(request))?.['status']).toBe('stopped');
    await expect(statusPanel).toContainText('stopped');
    expect((await hit()).status, 'stopping clears the stage chaos').toBe(200);

    // History lists the terminated run.
    await page.getByRole('button', { name: 'Expand history' }).click();
    await expect(page.getByRole('row').filter({ hasText: name })).toContainText('stopped');
  });

  test('experiment editor validation: name and host are required, a stage needs a fault', async ({ page, request }) => {
    await openChaos(page);
    await expandExperiments(page);
    const start = page.getByRole('button', { name: 'Start Experiment' });
    await start.click();
    await expect(page.getByText('Experiment name is required')).toBeVisible();
    await page.getByLabel('Experiment name').fill('e2e-invalid');
    await expEditor(page).getByLabel('Duration ms', { exact: true }).fill('1000');
    await start.click();
    await expect(page.getByText('Stage 1: host is required')).toBeVisible();
    await expEditor(page).getByLabel('Host', { exact: true }).fill('x.e2e');
    await start.click();
    await expect(page.getByText('Stage 1: set at least one fault (error, latency, or drop)')).toBeVisible();
    expect(await experimentStatus(request)).toBeNull();
  });

  test('auto-halt trips on a fault cascade and the experiment is reported halted', async ({ page, request }) => {
    test.setTimeout(60_000);
    await withAutoHalt(request, { chaosAutoHaltEnabled: true, chaosAutoHaltErrorThreshold: 3, chaosAutoHaltWindowMillis: 60_000 });
    const { svc, hit } = await chaosTarget(request);
    const name = `e2e-halt-${stamp()}`;
    await openChaos(page);
    // The inline auto-halt controls reflect the server configuration.
    await expect(page.getByText('Armed', { exact: true })).toBeVisible();
    await expect(page.getByLabel('Error threshold')).toHaveValue('3');

    await expandExperiments(page);
    await page.getByLabel('Experiment name').fill(name);
    await fillStage(page, 0, { duration: '2000', host: svc, status: '503', prob: '1' });
    await page.getByRole('button', { name: 'Add Stage' }).click();
    await fillStage(page, 1, { duration: '60000', host: svc, latency: '1' });
    await page.getByRole('button', { name: 'Start Experiment' }).click();
    await expect.poll(async () => (await experimentStatus(request))?.['status']).toBe('running');

    // Three destructive faults reach the threshold: the breaker wipes the chaos.
    for (let i = 0; i < 3; i++) expect((await hit()).status).toBe(503);
    expect((await hit()).status, 'the breaker cleared the fault').toBe(200);

    // At the next stage boundary the orchestrator notices and halts.
    await expect.poll(async () => (await experimentStatus(request))?.['status'], { timeout: 15_000 }).toBe('halted_by_auto_halt');
    await expect(page.getByText('halted by auto halt')).toBeVisible();
    await expect(page.getByText('halted', { exact: true })).toBeVisible();
  });

  test('saves the editor as a named profile, re-applies it in one click, and deletes it', async ({ page, request }) => {
    const { svc, hit } = await chaosTarget(request);
    const name = `e2e-profile-${stamp()}`;
    await openChaos(page);
    await expandExperiments(page);
    await page.getByLabel('Experiment name').fill(name);
    await fillStage(page, 0, { duration: '60000', host: svc, status: '502', prob: '1' });
    await page.getByRole('button', { name: 'Save as Profile' }).click();

    const chip = page.getByRole('button', { name: name, exact: true });
    await expect(chip).toBeVisible();
    const profiles = (await (await request.get(`${ORIGIN}/mockserver/chaosExperiment/profiles`)).json()) as { profiles: string[] };
    expect(profiles.profiles).toContain(name);
    expect(await experimentStatus(request), 'saving does not start anything').toBeNull();

    await chip.click();
    await expect.poll(async () => (await experimentStatus(request))?.['status']).toBe('running');
    expect((await hit()).status).toBe(502);
    await page.getByRole('button', { name: 'Stop', exact: true }).click();
    await expect.poll(async () => (await experimentStatus(request))?.['status']).toBe('stopped');

    // Delete via the chip's delete icon.
    await chip.locator('svg').last().click();
    await expect(chip).toBeHidden();
    const after = (await (await request.get(`${ORIGIN}/mockserver/chaosExperiment/profiles`)).json()) as { profiles: string[] };
    expect(after.profiles).not.toContain(name);
    await request.delete(`${ORIGIN}/mockserver/chaosExperiment/profiles/${name}`);
  });

  test('keyboard-only: Tab through the HTTP form and press Enter in Host to register', async ({ page, request }) => {
    const host = `kbd-${stamp()}.e2e`;
    await openChaos(page);
    await expandHttp(page);
    await page.getByLabel('Host', { exact: true }).focus();
    await page.keyboard.type(host);
    await page.keyboard.press('Tab');
    await expect(page.getByLabel('Error status', { exact: true })).toBeFocused();
    await page.keyboard.type('503');
    await page.keyboard.press('Shift+Tab');
    await expect(page.getByLabel('Host', { exact: true })).toBeFocused();
    await page.keyboard.press('Enter');
    await expect.poll(async () => (await serverServiceChaos(request))[host]).toMatchObject({ errorStatus: 503 });
  });

  test('registers and removes a TCP-layer chaos profile for a host', async ({ page, request }) => {
    const host = `tcp-${stamp()}.e2e`;
    await openChaos(page);
    await page.getByRole('button', { name: 'Expand TCP chaos' }).click();
    const tcp = page.getByText('TCP-Layer Chaos', { exact: true }).locator('xpath=ancestor::div[contains(@class,"MuiPaper-root")][1]');
    await expect(tcp.getByText('No TCP-layer chaos registered.')).toBeVisible();
    await tcp.getByLabel('Host', { exact: true }).fill(host);
    await tcp.getByLabel('Latency ms', { exact: true }).fill('150');
    await tcp.getByRole('button', { name: 'Register', exact: true }).click();
    const listed = async () => Object.keys((((await (await request.get(`${ORIGIN}/mockserver/tcpChaos`)).json()) as { hosts?: Record<string, unknown> }).hosts) ?? {});
    await expect.poll(listed).toEqual([host]);
    await expect(tcp.getByText('1 host', { exact: false })).toBeVisible();
    await tcp.getByRole('button', { name: `Remove TCP chaos for ${host}` }).click();
    await expect.poll(listed).toEqual([]);
    await expect(tcp.getByText('No TCP-layer chaos registered.')).toBeVisible();
  });

  test('a preemption simulation cordons the data plane with 503s until it is cleared', async ({ page, request }) => {
    const path = `/e2e/preempt-${stamp()}`;
    await mockResponse(request, path, 200, 'served');
    const state = async () => ((await (await request.get(`${ORIGIN}/mockserver/preemption`)).json()) as { state: string }).state;
    try {
      await openChaos(page);
      await page.getByRole('button', { name: 'Expand preemption' }).click();
      const pre = page.getByText('Preemption Simulation', { exact: true }).locator('xpath=ancestor::div[contains(@class,"MuiPaper-root")][1]');
      await pre.getByLabel('Drain ms', { exact: true }).fill('500');
      // Dead-man's switch: the cordon lifts by itself after 20 s even if the test dies.
      await pre.getByLabel('TTL ms', { exact: true }).fill('20000');
      await pre.getByRole('button', { name: 'Start Preemption' }).click();
      await expect.poll(state).not.toBe('inactive');
      const cordoned = await request.get(`${ORIGIN}${path}`);
      expect(cordoned.status(), 'a cordoned server turns new requests away').toBe(503);

      await page.getByRole('button', { name: 'Clear Preemption' }).click();
      await expect.poll(state).toBe('inactive');
      expect((await request.get(`${ORIGIN}${path}`)).status()).toBe(200);
    } finally {
      await request.delete(`${ORIGIN}/mockserver/preemption`);
    }
  });
});

// ---------------------------------------------------------------------------
// Performance (Resilience ▸ Performance) — TINY load scenarios only: a handful
// of requests at a low rate, against this same server, capped by Max requests.
// ---------------------------------------------------------------------------

async function selectOption(scope: Page | Locator, label: string, option: string, page: Page, nth = 0) {
  await scope.getByRole('combobox', { name: label }).nth(nth).click();
  await page.getByRole('option', { name: option, exact: true }).click();
}

async function authorTinyScenario(
  page: Page,
  f: { name: string; path: string; rate?: string; durationMs?: string; maxRequests?: string; threshold?: { metric: string; comparator: string; value: string } },
) {
  await page.getByRole('tab', { name: 'Create / Edit' }).click();
  const form = page.getByTestId('load-author-form');
  await form.getByLabel('Scenario name').fill(f.name);
  await form.getByLabel('Max requests (optional)').fill(f.maxRequests ?? '5');
  // Replace the default 90 s / 10-VU profile with one low-rate stage.
  await form.getByRole('button', { name: 'Remove stage 2' }).click();
  const stage = page.getByTestId('load-stage-0');
  await selectOption(stage, 'Stage type', 'Rate (iterations/sec)', page);
  await selectOption(stage, 'Mode', 'Hold', page);
  await stage.getByLabel('Rate (iterations/sec)').fill(f.rate ?? '2');
  await stage.getByLabel('Duration (ms)').fill(f.durationMs ?? '3000');
  const step = page.getByTestId('load-step-0');
  await step.getByLabel('Path', { exact: true }).fill(f.path);
  await step.getByLabel('Target host').fill(HOST);
  await step.getByLabel('Target port').fill(PORT);
  if (f.threshold) {
    await page.getByTestId('load-add-threshold').click();
    const t = page.getByTestId('load-threshold-0');
    await selectOption(t, 'Metric', f.threshold.metric, page);
    await selectOption(t, 'Comparator', f.threshold.comparator, page);
    await t.getByRole('textbox', { name: 'Threshold' }).fill(f.threshold.value);
  }
}


type LoadEntry = { name: string; state: string; requestsSent?: number; succeeded?: number; failed?: number; verdict?: string };
async function loadRegistry(request: APIRequestContext): Promise<LoadEntry[]> {
  const res = await request.get(`${ORIGIN}/mockserver/loadScenario`);
  expect(res.ok()).toBeTruthy();
  return ((await res.json()) as { scenarios?: LoadEntry[] }).scenarios ?? [];
}
async function loadEntry(request: APIRequestContext, name: string) {
  return (await loadRegistry(request)).find((e) => e.name === name);
}

test.describe('Performance', () => {
  test.beforeEach(async ({ request }) => {
    await request.delete(`${ORIGIN}/mockserver/loadScenario`);
    await resetAll(request);
    const cfg = (await (await request.get(`${ORIGIN}/mockserver/configuration`)).json()) as Record<string, unknown>;
    requireOrSkip(cfg['loadGenerationEnabled'] === true, 'needs a server started with -Dmockserver.loadGenerationEnabled=true');
  });
  test.afterAll(async ({ request }) => {
    await request.delete(`${ORIGIN}/mockserver/loadScenario`);
  });

  test('authors a tiny RATE scenario, Load & Run shows live stats and a chart, and it completes with a PASS verdict', async ({ page, request }) => {
    const id = stamp();
    const name = `e2e-load-${id}`;
    const target = `/e2e/load-ok-${id}`;
    await mockResponse(request, target, 200, 'ok');
    await page.goto('./#/performance');
    await expect(page.getByTestId('load-run-empty')).toBeVisible();

    await authorTinyScenario(page, { name, path: target, threshold: { metric: 'Error rate (0–1)', comparator: '<', value: '0.5' } });
    // The generated-code preview tracks the form.
    await expect(page.getByTestId('load-codegen')).toContainText(name);
    await page.getByTestId('load-register-run').click();

    // Live: the run card appears on Run & Monitor with the stage readout and stats.
    const card = page.getByTestId(`load-running-${name}`);
    await expect(card).toBeVisible();
    await expect(card).toContainText('RATE');
    await expect(card).toContainText('Requests sent');
    await expect(page.getByTestId('load-chart')).toBeVisible();
    await expect(page.getByRole('group', { name: 'Chart metric toggles' })).toBeVisible();

    // It finishes on its own (Max requests = 5) with every request answered by the mock.
    await expect.poll(async () => (await loadEntry(request, name))?.state, { timeout: 20_000 }).toBe('COMPLETED');
    const done = (await loadEntry(request, name))!;
    expect(done.requestsSent).toBeGreaterThan(0);
    expect(done.requestsSent).toBeLessThanOrEqual(5);
    expect(done.succeeded).toBe(done.requestsSent);
    expect(done.verdict).toBe('PASS');

    await expect(page.getByTestId(`load-registry-state-${name}`)).toHaveText('COMPLETED');
    await expect(page.getByTestId(`load-registry-verdict-${name}`)).toHaveText('PASS');
    await expect(card).toBeHidden();
  });

  test('a breached threshold yields a FAIL verdict', async ({ page, request }) => {
    const id = stamp();
    const name = `e2e-load-fail-${id}`;
    const target = `/e2e/load-500-${id}`;
    await mockResponse(request, target, 500, 'boom');
    await page.goto('./#/performance');
    await authorTinyScenario(page, { name, path: target, maxRequests: '3', threshold: { metric: 'Error rate (0–1)', comparator: '<', value: '0.1' } });
    await page.getByTestId('load-register-run').click();

    await expect.poll(async () => (await loadEntry(request, name))?.state, { timeout: 20_000 }).toBe('COMPLETED');
    const done = (await loadEntry(request, name))!;
    expect(done.failed).toBe(done.requestsSent);
    expect(done.verdict).toBe('FAIL');
    await expect(page.getByTestId(`load-registry-verdict-${name}`)).toHaveText('FAIL');
  });

  test('Stop on the live run card stops a running scenario early', async ({ page, request }) => {
    const id = stamp();
    const name = `e2e-load-stop-${id}`;
    const target = `/e2e/load-stop-${id}`;
    await mockResponse(request, target, 200, 'ok');
    await page.goto('./#/performance');
    // 1 request/second for up to 30 s, capped at 30 requests — stopped after the first.
    await authorTinyScenario(page, { name, path: target, rate: '1', durationMs: '30000', maxRequests: '30' });
    await page.getByTestId('load-register-run').click();

    const card = page.getByTestId(`load-running-${name}`);
    await expect(card).toBeVisible();
    // "Download report (JSON)" downloads the run's live report as a file (not a new tab).
    const download = page.waitForEvent('download');
    await card.getByTestId(`load-report-json-${name}`).click();
    const report = await download;
    expect(report.suggestedFilename()).toBe(`${name}-report.json`);
    const reportPath = test.info().outputPath(`${name}-report.json`);
    await report.saveAs(reportPath);
    expect(readFileSync(reportPath, 'utf8')).toContain(name);

    await card.getByRole('button', { name: 'Stop' }).click();
    await expect.poll(async () => (await loadEntry(request, name))?.state).toBe('STOPPED');
    expect((await loadEntry(request, name))!.requestsSent ?? 0).toBeLessThan(30);
    await expect(page.getByTestId(`load-registry-state-${name}`)).toHaveText('STOPPED');
    await expect(card).toBeHidden();
  });

  test('Load registers without running; the registry starts, edits and deletes it', async ({ page, request }) => {
    const id = stamp();
    const name = `e2e-load-reg-${id}`;
    const target = `/e2e/load-reg-${id}`;
    await mockResponse(request, target, 200, 'ok');
    await page.goto('./#/performance');
    await authorTinyScenario(page, { name, path: target, maxRequests: '2' });
    await page.getByTestId('load-register').click();

    await expect(page.getByTestId(`load-registry-state-${name}`)).toHaveText('LOADED');
    expect((await loadEntry(request, name))?.state).toBe('LOADED');
    expect((await loadEntry(request, name))?.requestsSent ?? 0, 'Load must not drive traffic').toBe(0);

    // Edit loads the definition back into the form.
    await page.getByRole('button', { name: `Edit ${name}` }).click();
    await expect(page.getByTestId('load-author-form').getByLabel('Scenario name')).toHaveValue(name);
    await expect(page.getByTestId('load-step-0').getByLabel('Path', { exact: true })).toHaveValue(target);

    await page.getByRole('button', { name: `Start ${name}` }).click();
    await expect.poll(async () => (await loadEntry(request, name))?.state, { timeout: 20_000 }).toBe('COMPLETED');

    await page.getByRole('button', { name: `Delete ${name}` }).click();
    // Delete asks first; nothing is removed until it is confirmed.
    const confirm = page.getByRole('dialog', { name: `Delete ${name}?` });
    await expect(confirm).toBeVisible();
    expect((await loadEntry(request, name))?.state).toBe('COMPLETED');
    await confirm.getByRole('button', { name: 'Delete', exact: true }).click();
    await expect.poll(async () => (await loadEntry(request, name)) === undefined).toBe(true);
    await expect(page.getByTestId(`load-registry-row-${name}`)).toBeHidden();
  });

  test('form validation names the missing field and registers nothing', async ({ page, request }) => {
    await page.goto('./#/performance');
    await page.getByRole('tab', { name: 'Create / Edit' }).click();
    await page.getByTestId('load-register').click();
    await expect(page.getByTestId('load-action-error')).toContainText('Scenario name is required');

    await page.getByTestId('load-author-form').getByLabel('Scenario name').fill('e2e-invalid');
    await page.getByTestId('load-register').click();
    await expect(page.getByTestId('load-action-error')).toContainText('Step 1: target host is required');

    await page.getByTestId('load-step-0').getByLabel('Target host').fill(HOST);
    await page.getByTestId('load-step-0').getByLabel('Target port').fill('70000');
    await page.getByTestId('load-register').click();
    await expect(page.getByTestId('load-action-error')).toContainText('Step 1: target port must be 1–65535');
    expect(await loadRegistry(request)).toEqual([]);
  });

  // E2E-RES-6 (fixed): with loadGenerationEnabled=false the help was shown only
  // after a refused start, and the next successful status poll cleared it again.
  test('E2E-RES-6: with load generation switched off, Performance explains how to enable it up front', async ({ page, request }) => {
    await put(request, '/mockserver/configuration', { loadGenerationEnabled: false });
    try {
      const id = stamp();
      const name = `e2e-load-off-${id}`;
      await page.goto('./#/performance');
      const alert = page.getByTestId('load-disabled-alert');
      await expect(alert).toBeVisible();
      await expect(alert).toContainText('-Dmockserver.loadGenerationEnabled=true');
      await authorTinyScenario(page, { name, path: `/e2e/load-off-${id}`, maxRequests: '1' });
      // Running is refused up front; registering is still allowed.
      await expect(page.getByTestId('load-register-run')).toBeDisabled();
      await page.getByTestId('load-register').click();
      await expect.poll(async () => (await loadEntry(request, name))?.state).toBe('LOADED');
      // The help stays up across the next configuration poll.
      await page.waitForResponse((r) => r.url().includes('/mockserver/configuration'), { timeout: 15_000 });
      await expect(alert).toBeVisible();
    } finally {
      await put(request, '/mockserver/configuration', { loadGenerationEnabled: true });
    }
  });

  // E2E-RES-5 (fixed): the panel read the registry listing as the legacy
  // single-run status, so the header chip stayed "none" and no summary rendered.
  test('E2E-RES-5: a completed run shows its end-of-run summary and the header reflects the run state', async ({ page, request }) => {
    const id = stamp();
    const name = `e2e-load-sum-${id}`;
    const target = `/e2e/load-sum-${id}`;
    await mockResponse(request, target, 200, 'ok');
    await page.goto('./#/performance');
    await authorTinyScenario(page, { name, path: target, maxRequests: '2', threshold: { metric: 'Error rate (0–1)', comparator: '<', value: '0.5' } });
    await page.getByTestId('load-register-run').click();
    await expect.poll(async () => (await loadEntry(request, name))?.state, { timeout: 20_000 }).toBe('COMPLETED');
    await expect(page.getByTestId('load-summary')).toBeVisible();
    await expect(page.getByTestId('load-summary')).toContainText('PASS');
    await expect(page.getByTestId('load-scenario-panel').getByText('completed', { exact: true })).toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// Layout: light theme at a narrow (1024×700) window, for each main view.
// ---------------------------------------------------------------------------

test.describe('Light theme, narrow window', () => {
  test.use({ viewport: { width: 1024, height: 700 } });

  // Widest element inside the scrolling view container vs. the container itself.
  async function horizontalOverflow(page: Page): Promise<{ doc: number; panel: number }> {
    return page.evaluate(() => {
      const doc = document.documentElement.scrollWidth - document.documentElement.clientWidth;
      let panel = 0;
      document.querySelectorAll<HTMLElement>('main *, #root *').forEach((el) => {
        const cs = getComputedStyle(el);
        if ((cs.overflowX === 'auto' || cs.overflow === 'auto') && el.clientWidth > 600) {
          panel = Math.max(panel, el.scrollWidth - el.clientWidth);
        }
      });
      return { doc, panel };
    });
  }

  for (const [view, heading, shot] of [
    ['breakpoints', 'Breakpoints', 'narrow-light-breakpoints'],
    ['chaos', 'Service Chaos', 'narrow-light-chaos'],
    ['performance', 'Performance — Load Scenarios', 'narrow-light-performance'],
  ] as const) {
    test(`${view} renders in the light theme at 1024×700 without horizontal overflow`, async ({ page, request }) => {
      await resetAll(request);
      await page.goto(`./#/${view}`);
      await expect(page.getByText(heading, { exact: true })).toBeVisible();
      await page.getByRole('button', { name: 'Switch to light mode' }).click();
      await expect(page.getByRole('button', { name: 'Switch to dark mode' })).toBeVisible();
      const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
      expect(bg, 'light theme paints a light page background').not.toBe('rgb(18, 18, 18)');
      if (view === 'chaos') await expandHttp(page);
      if (view === 'performance') await page.getByRole('tab', { name: 'Create / Edit' }).click();
      await page.screenshot({ path: test.info().outputPath(`${shot}.png`), fullPage: true, animations: 'disabled' });
      expect(await horizontalOverflow(page)).toEqual({ doc: 0, panel: 0 });
    });
  }
});
