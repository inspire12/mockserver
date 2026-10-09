import { readFileSync } from 'fs';
import { resolve } from 'path';
import { describe, it, expect } from 'vitest';
import { rustClientVersionRequirement } from './shared.ts';
import { standardToRust } from './rust.ts';
import { verifyToRust } from '../verificationCodegen.ts';
import { loadToRust } from '../loadScenarioCodegen.ts';

// The crate's own manifest is the source of truth for the version a Rust snippet should depend on.
function crateMajorVersion(): string {
  const manifest = readFileSync(resolve(process.cwd(), '..', 'mockserver-client-rust', 'Cargo.toml'), 'utf8');
  const version = /^version = "(\d+)\.\d+\.\d+"/m.exec(manifest)?.[1];
  expect(version, 'version line in mockserver-client-rust/Cargo.toml').toBeDefined();
  return version!;
}

describe('Rust snippets name the current mockserver-client crate', () => {
  it('requires the major version the crate is published at', () => {
    expect(rustClientVersionRequirement()).toBe(crateMajorVersion());
  });

  it('puts that requirement in every Rust generator\'s Cargo.toml comment', () => {
    const expected = `// Cargo.toml: mockserver-client = "${crateMajorVersion()}"`;
    const baseUrl = 'http://localhost:1080';
    const snippets = [
      standardToRust(
        { id: '', method: 'GET', path: '/api', headers: '', queryString: '', cookies: '', pathParams: '', body: '', bodyBinary: false, bodyMatcherType: 'string', priority: 0, times: 0 },
        { type: 'static', static: { statusCode: 200, body: 'hello', contentType: 'text/plain', bodyFromFile: false, filePath: '', fileTemplateType: '' } },
        baseUrl,
      ),
      verifyToRust({
        mode: 'single',
        httpRequest: { path: '/api' },
        httpResponse: {},
        times: { mode: 'atLeast', count: 1 },
        httpRequests: [],
        httpResponses: [],
        baseUrl,
      }),
      loadToRust({ scenario: { name: 'smoke', profile: { stages: [] }, steps: [] }, baseUrl }),
    ];
    for (const snippet of snippets) {
      expect(snippet.split('\n')[0]).toContain(expected);
    }
  });
});
