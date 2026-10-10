import { defineConfig, devices, type PlaywrightTestConfig } from '@playwright/test';

// End-to-end tests that drive the SERVED dashboard in a real browser against a
// REAL MockServer (the runnable netty JAR) — real REST + real WebSocket, no
// mocked fetch and no jsdom. This is the browser-level backstop the 178
// jsdom/vitest specs cannot provide.
//
// Topology (all same-origin, so no CORS and no dev-server proxy):
//   http://${HOST}:${PORT}/mockserver/dashboard/   ← the dashboard the JAR serves
//   http://${HOST}:${PORT}/mockserver/*            ← control plane (REST)
//   ws://${HOST}:${PORT}/_mockserver_ui_websocket  ← live log feed
//
// Two servers, both booted from the JAR by e2e/start-mockserver.mjs (the
// `webServer` entries) — locally and in CI alike:
//   • main       127.0.0.1:1084 (E2E_MS_PORT): the server under test, at INFO,
//                with load generation and SLO tracking on and metrics off.
//   • secondary  127.0.0.1:1114 (E2E_UPSTREAM_PORT): the proxied upstream for the
//                library / verify tests and the metrics-on server for the
//                Metrics view.
// The observe spec boots a third, short-lived server itself for its log-pressure
// test (E2E_JAVA is the java it runs). With E2E_EXTERNAL_SERVER=1 nothing is
// booted: point E2E_MS_HOST/E2E_MS_PORT (and E2E_UPSTREAM_HOST/E2E_UPSTREAM_PORT)
// at servers started by hand. A test that needs a server or flag that is missing
// skips with the reason locally, and fails in CI.

const HOST = process.env.E2E_MS_HOST || '127.0.0.1';
const PORT = process.env.E2E_MS_PORT || '1084';
const BASE_ORIGIN = `http://${HOST}:${PORT}`;
const EXTERNAL_SERVER = process.env.E2E_EXTERNAL_SERVER === '1';

const config: PlaywrightTestConfig = {
  testDir: '.',
  testMatch: '**/*.spec.ts',
  // Every spec resets and inspects the same two servers (expectations, the log
  // ring buffer, server-wide chaos and load scenarios): run serially so tests
  // never race on that shared state.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI
    ? [
        ['list'],
        // Relative to this config's directory (e2e/): the step's artifact glob and
        // the junit-annotate step read mockserver-ui/test-reports/.
        ['junit', { outputFile: '../test-reports/e2e-results.xml' }],
        ['./no-silent-skip-reporter.ts'],
      ]
    : [['list']],
  timeout: 60_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: `${BASE_ORIGIN}/mockserver/dashboard/`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  // A wide viewport keeps the full grouped navigation + toolbar action icons on
  // screen (below the `lg` breakpoint the dashboard collapses the nav into a
  // hamburger), so the AppBar controls the tests click are always present.
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1600, height: 1000 },
        // Normally Playwright's own bundled Chromium (`npx playwright install
        // chromium`). E2E_BROWSER_CHANNEL=chrome points at an installed Chrome
        // instead, for a machine that cannot download it. Unset in CI.
        ...(process.env.E2E_BROWSER_CHANNEL
          ? { channel: process.env.E2E_BROWSER_CHANNEL }
          : {}),
      },
    },
  ],
};

if (!EXTERNAL_SERVER) {
  const UPSTREAM_PORT = process.env.E2E_UPSTREAM_PORT || '1114';
  // cwd defaults to this config file's directory (e2e/), so the launcher is
  // referenced by its bare name; it resolves all its own paths from __dirname.
  // Playwright starts these in order, polling each `url` with GET until it
  // answers 2xx/3xx; the dashboard GET is a good readiness signal for a server.
  config.webServer = [
    {
      command: 'node start-mockserver.mjs main',
      url: `${BASE_ORIGIN}/mockserver/dashboard/`,
      // Generous: locally the JAR may need a Maven build on first run. In CI the
      // pipeline builds it first, so boot is just a JVM start.
      timeout: 300_000,
      reuseExistingServer: !process.env.CI,
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      command: 'node start-mockserver.mjs secondary',
      url: `http://127.0.0.1:${UPSTREAM_PORT}/mockserver/dashboard/`,
      timeout: 120_000,
      reuseExistingServer: !process.env.CI,
      stdout: 'pipe',
      stderr: 'pipe',
    },
  ];
}

export default defineConfig(config);
