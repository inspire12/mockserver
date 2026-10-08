import { describe, it, expect } from 'vitest';
import {
  buildExpectationJson,
  standardToCurl,
  standardToJava,
  standardToJson,
  type StandardActionPayload,
  type StandardMatcher,
} from '../lib/standardCodegen';
import { standardToNode } from '../lib/codegen/node';
import { standardToPython } from '../lib/codegen/python';
import { standardToGo } from '../lib/codegen/go';
import { standardToCsharp } from '../lib/codegen/csharp';
import { standardToRuby } from '../lib/codegen/ruby';
import { standardToRust } from '../lib/codegen/rust';

// A binary response with no data means "this message has no reply". Every generator must emit a
// binary response without data for it, never an empty or "undefined" payload.

const matcher: StandardMatcher = {
  id: '', method: 'GET', path: '/api', headers: '', queryString: '', cookies: '',
  pathParams: '', body: '', bodyBinary: false, bodyMatcherType: 'string',
  priority: 0, times: 0,
};
const URL = 'http://localhost:1080';
const empty: StandardActionPayload = { type: 'binary_response', binaryResponse: { binaryData: '' } };
const blank: StandardActionPayload = { type: 'binary_response', binaryResponse: { binaryData: '   ' } };

describe('a binary response with no data', () => {
  it('is sent as a binary response without binaryData', () => {
    expect(buildExpectationJson(matcher, empty)['binaryResponse']).toEqual({});
    expect(buildExpectationJson(matcher, blank)['binaryResponse']).toEqual({});
    expect(standardToJson(matcher, empty)).not.toContain('binaryData');
    expect(standardToCurl(matcher, empty, URL)).not.toContain('binaryData');
    expect(standardToNode(matcher, empty, URL)).not.toContain('binaryData');
  });

  it('Java builds binaryResponse() without data', () => {
    const code = standardToJava(matcher, empty);
    expect(code).toContain('binaryResponse()');
    expect(code).not.toContain('withBinaryData');
  });

  it('Python builds BinaryResponse() without binary_data', () => {
    const code = standardToPython(matcher, empty, URL);
    expect(code).toContain('binary_response=BinaryResponse()');
    expect(code).not.toContain('binary_data');
    expect(code).not.toContain('undefined');
  });

  it('Python still passes data that is present', () => {
    const code = standardToPython(matcher, { type: 'binary_response', binaryResponse: { binaryData: 'SGk=' } }, URL);
    expect(code).toContain('binary_data="SGk="');
  });

  it('Go builds a BinaryResponse without BinaryData', () => {
    const code = standardToGo(matcher, empty, URL);
    expect(code).toContain('BinaryResponse: &mockserver.BinaryResponse{');
    expect(code).not.toContain('BinaryData');
  });

  it('C# builds a BinaryResponse without BinaryData', () => {
    const code = standardToCsharp(matcher, empty, URL);
    expect(code).toContain('BinaryResponse');
    expect(code).not.toContain('BinaryData');
  });

  it('Ruby builds BinaryResponse.new without binary_data', () => {
    const code = standardToRuby(matcher, empty, URL);
    expect(code).toContain('BinaryResponse.new');
    expect(code).not.toContain('binary_data');
  });

  it('Rust builds BinaryResponse::new() instead of decoding an empty string', () => {
    const code = standardToRust(matcher, empty, URL);
    expect(code).toContain('.respond_binary(BinaryResponse::new())');
    expect(code).not.toContain('from_base64');
  });

  it('Rust still decodes data that is present', () => {
    const code = standardToRust(matcher, { type: 'binary_response', binaryResponse: { binaryData: 'SGk=' } }, URL);
    expect(code).toContain('.respond_binary(BinaryResponse::from_base64("SGk="))');
  });
});
