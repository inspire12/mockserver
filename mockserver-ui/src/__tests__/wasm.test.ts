import { describe, it, expect, vi, afterEach } from 'vitest';
import { listWasmModules, testWasmModule, WasmDisabledError } from '../lib/wasm';

const params = { host: '127.0.0.1', port: '1080', secure: false };

interface FetchCall {
  url: string;
  init?: RequestInit;
}

function stubFetch(status: number, body: unknown): FetchCall[] {
  const calls: FetchCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      return {
        ok: status >= 200 && status < 300,
        status,
        statusText: 'stub',
        json: async () => body,
        text: async () => (typeof body === 'string' ? body : JSON.stringify(body)),
      };
    }),
  );
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('testWasmModule', () => {
  it('POSTs the module name and sample request to /wasm/test and returns the decision', async () => {
    const calls = stubFetch(200, { matched: true });
    const result = await testWasmModule(params, 'my-rule', { method: 'GET', path: '/' });
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/wasm/test');
    expect(calls[0]?.init?.method).toBe('POST');
    expect(JSON.parse(calls[0]?.init?.body as string)).toEqual({
      moduleName: 'my-rule',
      request: { method: 'GET', path: '/' },
    });
    expect(result).toEqual({ matched: true });
  });

  it('returns a not-matched decision with any shaped response', async () => {
    stubFetch(200, { matched: false, shaped: { statusCode: 418 } });
    expect(await testWasmModule(params, 'm', { method: 'GET', path: '/' })).toEqual({
      matched: false,
      shaped: { statusCode: 418 },
    });
  });

  it('throws in the MockServer-returned shape so the error envelope surfaces', async () => {
    stubFetch(404, '{"error":"WASM module \'ghost\' not found"}');
    await expect(testWasmModule(params, 'ghost', { method: 'GET', path: '/' })).rejects.toThrow(
      "MockServer returned 404: {\"error\":\"WASM module 'ghost' not found\"}",
    );
  });
});

describe('listWasmModules', () => {
  it('returns the module names', async () => {
    stubFetch(200, ['a', 'b']);
    expect(await listWasmModules(params)).toEqual(['a', 'b']);
  });

  it('throws WasmDisabledError when the server has WASM rules turned off', async () => {
    stubFetch(403, 'WASM support is disabled; set wasmEnabled=true to enable');
    const error = await listWasmModules(params).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(WasmDisabledError);
    expect((error as Error).message).toContain('wasmEnabled=true');
  });

  it('keeps any other 403 (control-plane authorization) as a plain error carrying the server body', async () => {
    stubFetch(403, 'Forbidden for control plane');
    const error = await listWasmModules(params).catch((e: unknown) => e);
    expect(error).not.toBeInstanceOf(WasmDisabledError);
    expect((error as Error).message).toBe('MockServer returned 403: Forbidden for control plane');
  });
});
