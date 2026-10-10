/**
 * Shared, language-agnostic helpers for the per-language client-code emitters
 * (node / python / go / csharp / ruby / rust) plus the verification and
 * load-scenario code generators.
 *
 * These live here — rather than inside any one per-language emitter module — so
 * they remain a stable dependency surface while the individual emitters are
 * rewritten independently. `standardCodegen.ts` re-exports all four under their
 * original names, so existing import sites that pull them from `./standardCodegen`
 * keep working unchanged.
 */

/** The `mockserver-client` crate requirement Rust snippets name in their Cargo.toml comment: the crate's
 *  major version, set from mockserver-client-rust/Cargo.toml by Vite's `define` or, for the Node-run
 *  emitter scripts, by scripts/define-build-constants.mjs (see build-constants.ts). */
export function rustClientVersionRequirement(): string {
  if (typeof __RUST_CLIENT_MAJOR_VERSION__ === 'string') return __RUST_CLIENT_MAJOR_VERSION__;
  throw new Error('__RUST_CLIENT_MAJOR_VERSION__ is not set: build with Vite, or import scripts/define-build-constants.mjs first');
}

/** Derive the client host/port from a base URL, defaulting to localhost:1080
 *  (or :443 for https) and falling back to localhost:1080 on a parse failure. */
export function clientHostPort(baseUrl: string): { host: string; port: number } {
  try {
    const u = new URL(baseUrl);
    return {
      host: u.hostname || 'localhost',
      port: u.port ? Number(u.port) : (u.protocol === 'https:' ? 443 : 1080),
    };
  } catch {
    return { host: 'localhost', port: 1080 };
  }
}

/** Re-indents every line of `block` after the first by `pad` spaces (the first line is already
 *  positioned by the caller). */
export function indentAfterFirst(block: string, pad: number): string {
  return block.split('\n').join('\n' + ' '.repeat(pad));
}

/** Renders a JSON-compatible value as a Python literal (true/false/null → True/False/None). */
export function toPythonLiteral(value: unknown, indent: number): string {
  const pad = ' '.repeat(indent);
  const pad2 = ' '.repeat(indent + 4);
  if (value === null || value === undefined) return 'None';
  if (typeof value === 'boolean') return value ? 'True' : 'False';
  if (typeof value === 'number') return String(value);
  if (typeof value === 'string') return JSON.stringify(value);
  if (Array.isArray(value)) {
    if (value.length === 0) return '[]';
    return '[\n' + value.map((v) => pad2 + toPythonLiteral(v, indent + 4)).join(',\n') + '\n' + pad + ']';
  }
  const entries = Object.entries(value as Record<string, unknown>);
  if (entries.length === 0) return '{}';
  return '{\n' + entries.map(([k, v]) => pad2 + JSON.stringify(k) + ': ' + toPythonLiteral(v, indent + 4)).join(',\n') + '\n' + pad + '}';
}

/** Wraps `s` in a Rust raw string literal, using as many `#`s as needed so the content can't
 *  prematurely terminate it. */
export function rustRawString(s: string): string {
  let hashes = '#';
  while (s.includes('"' + hashes)) hashes += '#';
  return `r${hashes}"${s}"${hashes}`;
}

/**
 * Wire fields a snippet leaves out because its client model cannot hold them. Each emitter
 * records them while it renders, then names them in one NOTE instead of dropping them silently.
 */
export class OmittedFields {
  private readonly paths: string[] = [];

  /** Records each key of `o` outside `known`, as `<at>.<key>`. */
  unknown(at: string, o: Record<string, unknown>, known: readonly string[]): void {
    for (const key of Object.keys(o)) if (!known.includes(key)) this.paths.push(`${at}.${key}`);
  }

  /** `<comment> NOTE: …` naming every recorded field, or undefined when none was recorded. */
  note(comment: string, language: string): string | undefined {
    if (this.paths.length === 0) return undefined;
    return `${comment} NOTE: the ${language} snippet omits field(s) its client model cannot hold: ${this.paths.join(', ')} -- see the JSON tab.`;
  }
}
