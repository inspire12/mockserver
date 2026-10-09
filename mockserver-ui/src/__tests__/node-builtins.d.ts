/**
 * Minimal ambient declarations for the handful of Node built-ins used by the
 * Node-run tests (the execution-equivalence proof in pythonCodegen.test.ts and
 * the Rust crate version check in rustClientVersion.test.ts).
 *
 * The UI package is browser-only and deliberately ships no `@types/node`. Rather
 * than pull that dependency in, we declare only the exact members those tests
 * need. This is a script-context `.d.ts` (no top-level
 * import/export) so these are ambient MODULE declarations, not augmentations.
 * No browser/runtime code imports these modules.
 */
declare module 'child_process' {
  export function execFileSync(
    command: string,
    args?: readonly string[],
    options?: { stdio?: string; encoding?: string },
  ): string;
}
declare module 'fs' {
  export function mkdtempSync(prefix: string): string;
  export function writeFileSync(path: string, data: string): void;
  export function existsSync(path: string): boolean;
  export function readFileSync(path: string, encoding: 'utf8'): string;
}
declare module 'os' {
  export function tmpdir(): string;
}
declare module 'path' {
  export function join(...paths: string[]): string;
  export function resolve(...paths: string[]): string;
}
declare const process: { cwd(): string };
