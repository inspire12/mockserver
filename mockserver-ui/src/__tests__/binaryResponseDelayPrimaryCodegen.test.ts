import { describe, it, expect } from 'vitest';
import {
  buildExpectationJson,
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

// A binary response loaded with `delay` and `primary` must keep both in the saved JSON and in every
// generated snippet, so the code a user copies registers the same expectation the dashboard shows.

const matcher: StandardMatcher = {
  id: '', method: 'GET', path: '/api', headers: '', queryString: '', cookies: '',
  pathParams: '', body: '', bodyBinary: false, bodyMatcherType: 'string',
  priority: 0, times: 0,
};
const URL = 'http://localhost:1080';
const carried: StandardActionPayload = {
  type: 'binary_response',
  binaryResponse: { binaryData: 'SGk=', delay: { timeUnit: 'SECONDS', value: 3 }, primary: true },
};
const plain: StandardActionPayload = { type: 'binary_response', binaryResponse: { binaryData: 'SGk=' } };

describe('a binary response with delay and primary', () => {
  it('is saved with both fields', () => {
    expect(buildExpectationJson(matcher, carried)['binaryResponse']).toEqual({
      binaryData: 'SGk=', delay: { timeUnit: 'SECONDS', value: 3 }, primary: true,
    });
    expect(standardToNode(matcher, carried, URL)).toContain('"primary": true');
  });

  it('Java chains withDelay and withPrimary and imports TimeUnit', () => {
    const code = standardToJava(matcher, carried);
    expect(code).toContain('.withDelay(TimeUnit.SECONDS, 3)');
    expect(code).toContain('.withPrimary(true)');
    expect(code).toContain('import java.util.concurrent.TimeUnit;');
  });

  it('Python passes delay and primary', () => {
    const code = standardToPython(matcher, carried, URL);
    expect(code).toContain('delay=Delay(time_unit="SECONDS", value=3)');
    expect(code).toContain('primary=True');
  });

  it('Go sets Delay and Primary', () => {
    const code = standardToGo(matcher, carried, URL);
    expect(code).toContain('Delay:');
    expect(code).toContain('Primary:');
  });

  it('C# sets Delay and Primary as typed properties', () => {
    const code = standardToCsharp(matcher, carried, URL);
    expect(code).toContain('Delay = ');
    expect(code).toContain('Primary = true');
  });

  it('Ruby passes delay and primary', () => {
    const code = standardToRuby(matcher, carried, URL);
    expect(code).toContain('delay: MockServer::Delay.new');
    expect(code).toContain('primary: true');
  });

  it('Rust chains delay and primary', () => {
    const code = standardToRust(matcher, carried, URL);
    expect(code).toContain('BinaryResponse::from_base64("SGk=")');
    expect(code).toContain('.delay(Delay::seconds(3))');
    expect(code).toContain('.primary(true)');
  });

  it('none of the generators invent them when absent', () => {
    expect(buildExpectationJson(matcher, plain)['binaryResponse']).toEqual({ binaryData: 'SGk=' });
    const java = standardToJava(matcher, plain);
    expect(java).not.toContain('withDelay');
    expect(java).not.toContain('withPrimary');
    expect(java).not.toContain('TimeUnit');
    for (const code of [
      standardToPython(matcher, plain, URL),
      standardToRuby(matcher, plain, URL),
      standardToRust(matcher, plain, URL),
    ]) {
      expect(code).not.toMatch(/delay|primary/i);
    }
    expect(standardToCsharp(matcher, plain, URL)).not.toMatch(/Primary|Delay =/);
  });
});
