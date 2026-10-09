/**
 * Fields an edit keeps from the loaded expectation (the delay and primary every action
 * inherits, and per-message delays) must appear in every generated snippet, so the code a
 * user copies registers the expectation the dashboard would save.
 */
import { describe, it, expect } from 'vitest';
import {
  buildExpectationJson,
  keptActionFields,
  standardToJava,
  type StandardActionPayload,
  type StandardMatcher,
} from '../lib/standardCodegen';
import { standardToNode } from '../lib/codegen/node';
import { standardToPython } from '../lib/codegen/python';
import { standardToGo } from '../lib/codegen/go';
import { standardToCsharp } from '../lib/codegen/csharp';
import { standardToRuby } from '../lib/codegen/ruby';
import { standardToRust } from '../lib/codegen/rust';

const matcher: StandardMatcher = {
  id: 'kept', method: 'GET', path: '/kept', headers: '', queryString: '', cookies: '',
  pathParams: '', body: '', bodyBinary: false, bodyMatcherType: 'string',
  priority: 0, times: 0,
};
const URL = 'http://localhost:1080';
const ACTION_DELAY = { timeUnit: 'SECONDS', value: 4321 };
const ITEM_DELAY = { timeUnit: 'SECONDS', value: 8765 };

/** Each action type as the form holds it after loading; the kept fields are added to the original below. */
const FORMS: [string, StandardActionPayload, string[]][] = [
  ['httpResponse', { type: 'static', static: { statusCode: 201, body: 'ok', contentType: 'text/plain', delayValue: 0, delayUnit: 'MILLISECONDS' } }, []],
  ['httpForward', { type: 'forward', forward: { scheme: 'HTTP', host: 'up.example.com', port: 8080 } }, []],
  ['httpOverrideForwardedRequest', { type: 'forward_override', forwardOverride: { overrideMethod: '', overrideHost: '', overrideScheme: '', overridePath: '/x', overrideQueryString: '', overrideHeaders: '', overrideBody: '' } }, []],
  ['httpForwardWithFallback', { type: 'forward_fallback', forwardFallback: { scheme: 'HTTP', host: 'up.example.com', port: 80, fallbackStatusCode: 503, fallbackBody: '', fallbackOnStatusCodes: '', fallbackOnTimeout: true } }, []],
  ['httpResponseClassCallback', { type: 'callback', callback: { callbackClass: 'com.example.Callback' } }, []],
  ['httpResponseTemplate', { type: 'template', template: { templateType: 'MUSTACHE', template: '{"a":1}' } }, []],
  ['httpError', { type: 'error', error: { dropConnection: true, responseBytesB64: '', delayValue: 0, delayUnit: 'MILLISECONDS' } }, []],
  ['httpWebSocketResponse', { type: 'websocket', websocket: { subprotocol: '', messages: 'hi', closeConnection: true, matchers: [{ frameType: 'TEXT', textMatcher: 'ping', responses: 'pong' }] } }, ['messages', 'matchers.responses']],
  ['httpSseResponse', { type: 'sse', sse: { statusCode: 200, headers: '', events: [{ event: '', data: 'd', id: '', retry: '' }], closeConnection: true } }, ['events']],
  ['binaryResponse', { type: 'binary_response', binaryResponse: { binaryData: 'SGk=' } }, []],
  ['dnsResponse', { type: 'dns_response', dnsResponse: { responseCode: 'NOERROR', answerRecords: '' } }, []],
  ['httpForwardTemplate', { type: 'forward_template', forwardTemplate: { templateType: 'VELOCITY', template: 'x' } }, []],
  ['httpForwardClassCallback', { type: 'forward_class_callback', forwardClassCallback: { callbackClass: 'com.example.Forward' } }, []],
  ['grpcStreamResponse', { type: 'grpc_stream', grpcStream: { statusName: 'OK', statusMessage: '', headers: '', messages: '{"a":1}', closeConnection: false } }, ['messages']],
];

function withItemDelay(items: unknown): unknown[] {
  return (items as Record<string, unknown>[]).map((i) => ({ ...i, delay: ITEM_DELAY }));
}

/** An edit of `key` whose original carries delay, primary and (where listed) per-item delays the form does not show. */
function editWithKeptFields(key: string, form: StandardActionPayload, itemLists: string[]): StandardActionPayload {
  const baseline = buildExpectationJson(matcher, form)[key] as Record<string, unknown>;
  const original: Record<string, unknown> = { ...structuredClone(baseline), delay: ACTION_DELAY, primary: true };
  for (const list of itemLists) {
    if (list === 'matchers.responses') {
      original['matchers'] = (original['matchers'] as Record<string, unknown>[]).map((m) => ({ ...m, responses: withItemDelay(m['responses']) }));
    } else {
      original[list] = withItemDelay(original[list]);
    }
  }
  return {
    ...form,
    editOriginal: { id: 'kept', httpRequest: { method: 'GET', path: '/kept' }, [key]: original },
    editActionModeled: true,
    editActionBaseline: { [key]: baseline },
  };
}

/** Per language: how primary appears. */
const EMITTERS: [string, (a: StandardActionPayload) => string, RegExp][] = [
  ['Java', (a) => standardToJava(matcher, a), /withPrimary\(true\)/],
  ['Node', (a) => standardToNode(matcher, a, URL), /"primary": true/],
  ['Python', (a) => standardToPython(matcher, a, URL), /primary=True/],
  ['Go', (a) => standardToGo(matcher, a, URL), /Primary: /],
  ['C#', (a) => standardToCsharp(matcher, a, URL), /Primary = true|WithPrimary\(true\)|""primary"":true/],
  ['Ruby', (a) => standardToRuby(matcher, a, URL), /primary: true|"primary" => true/],
  ['Rust', (a) => standardToRust(matcher, a, URL), /\.primary\(true\)|primary: Some\(true\)|extra\.insert\("primary"/],
];

describe('fields kept through an edit', () => {
  it.each(FORMS)('%s: the saved expectation carries them', (key, form, itemLists) => {
    const edit = editWithKeptFields(key, form, itemLists);
    expect(buildExpectationJson(matcher, edit)[key]).toEqual((edit.editOriginal as Record<string, unknown>)[key]);
    const kept = keptActionFields(matcher, edit).map((f) => f.path.join('.'));
    expect(kept).toEqual(expect.arrayContaining(['delay', 'primary']));
  });

  for (const [language, emit, primary] of EMITTERS) {
    it.each(FORMS)(`${language}: %s shows the kept delay, primary and per-message delays`, (key, form, itemLists) => {
      const code = emit(editWithKeptFields(key, form, itemLists));
      expect(code).toContain('4321');
      expect(code).toMatch(primary);
      expect(code).not.toMatch(/NOTE: .*(has no|no typed Go field)/);
      const itemDelays = (code.match(/8765/g) ?? []).length;
      expect(itemDelays).toBe(itemLists.length);
      expect(code).not.toContain('NOTE: the Java preview omits');
    });
  }

  it('the Java snippet names a kept field it cannot render', () => {
    const form = FORMS.find(([k]) => k === 'httpResponse')!;
    const edit = editWithKeptFields('httpResponse', form[1], []);
    const original = (edit.editOriginal as Record<string, Record<string, unknown>>)['httpResponse']!;
    original['trailers'] = { t: ['v'] };
    expect(standardToJava(matcher, edit)).toContain(
      '// NOTE: the Java preview omits field(s) kept from the loaded expectation: httpResponse.trailers -- see the JSON tab.',
    );
  });
});
