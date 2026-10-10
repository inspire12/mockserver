import { expect, test } from '@playwright/test';

// A test that needs something only the full e2e topology provides (a second
// server, a server flag) skips with the reason when run by hand against a bare
// server, but FAILS in CI: there the topology must provide it, and a skip would
// be a silent gap in the gate.
export function requireOrSkip(condition: boolean, reason: string): void {
  if (process.env.CI) {
    expect(condition, `${reason} (the CI e2e topology must provide this)`).toBe(true);
  } else {
    test.skip(!condition, reason);
  }
}
