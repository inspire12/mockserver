import type { FullResult, Reporter, TestCase, TestResult } from '@playwright/test/reporter';

// CI-only reporter: fails the run when any test is skipped other than by
// test.fixme (a known open defect, named by its id in the title). A plain or
// conditional test.skip in CI means the e2e topology did not provide something a
// test needs, and a green run would hide that the test never executed.
export default class NoSilentSkipReporter implements Reporter {
  private readonly skipped: string[] = [];

  onTestEnd(test: TestCase, result: TestResult): void {
    if (result.status !== 'skipped') return;
    if (test.annotations.some((a) => a.type === 'fixme')) return;
    const reason = test.annotations.find((a) => a.type === 'skip')?.description ?? 'no reason given';
    this.skipped.push(`${test.titlePath().filter(Boolean).join(' › ')} (${reason})`);
  }

  async onEnd(result: FullResult): Promise<{ status: FullResult['status'] } | undefined> {
    if (this.skipped.length === 0) return undefined;
    console.error(`\n[e2e] ${this.skipped.length} test(s) skipped in CI without test.fixme — failing the run:`);
    for (const line of this.skipped) console.error(`  - ${line}`);
    return { status: result.status === 'passed' ? 'failed' : result.status };
  }
}
