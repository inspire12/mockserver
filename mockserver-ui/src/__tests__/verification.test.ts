import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  timesToWire, verifyRequest, verifySequence, buildVerifyBody, buildVerifySequenceBody,
  bodyMatcher, timesSpecProblem, MAX_VERIFICATION_COUNT,
} from '../lib/verification';
import { humanizeError } from '../lib/errorMessage';

const params = { host: '127.0.0.1', port: '1080', secure: false };

afterEach(() => { vi.restoreAllMocks(); });

describe('timesToWire', () => {
  it('maps the four modes, omitting an open bound (server treats absent as -1 = unbounded)', () => {
    expect(timesToWire({ mode: 'atLeast', count: 2 })).toEqual({ atLeast: 2 });
    expect(timesToWire({ mode: 'atMost', count: 3 })).toEqual({ atMost: 3 });
    expect(timesToWire({ mode: 'exactly', count: 1 })).toEqual({ atLeast: 1, atMost: 1 });
    expect(timesToWire({ mode: 'between', count: 2, atMost: 5 })).toEqual({ atLeast: 2, atMost: 5 });
  });

  it('sends a between upper bound as entered rather than silently raising it to the lower bound', () => {
    expect(timesToWire({ mode: 'between', count: 4, atMost: 1 })).toEqual({ atLeast: 4, atMost: 1 });
  });
});

describe('timesSpecProblem', () => {
  it('accepts every in-range spec', () => {
    expect(timesSpecProblem({ mode: 'atLeast', count: 0 })).toBeNull();
    expect(timesSpecProblem({ mode: 'exactly', count: MAX_VERIFICATION_COUNT })).toBeNull();
    expect(timesSpecProblem({ mode: 'between', count: 2, atMost: 2 })).toBeNull();
    expect(timesSpecProblem({ mode: 'between', count: 1, atMost: 3 })).toBeNull();
  });

  it('flags a between max below its min on the max field', () => {
    expect(timesSpecProblem({ mode: 'between', count: 3, atMost: 1 })).toEqual({ field: 'atMost', message: 'Must not be less than min' });
  });

  it('flags a count the server cannot parse (above a Java int)', () => {
    expect(timesSpecProblem({ mode: 'atLeast', count: 3000000000 })?.field).toBe('count');
    expect(timesSpecProblem({ mode: 'atLeast', count: MAX_VERIFICATION_COUNT + 1 })?.field).toBe('count');
    expect(timesSpecProblem({ mode: 'between', count: 1, atMost: 3000000000 })?.field).toBe('atMost');
  });

  it('ignores the max field outside between mode', () => {
    expect(timesSpecProblem({ mode: 'atLeast', count: 3, atMost: 1 })).toBeNull();
  });
});

describe('bodyMatcher', () => {
  it('is undefined for a blank body', () => {
    expect(bodyMatcher('')).toBeUndefined();
    expect(bodyMatcher('  \n ')).toBeUndefined();
  });

  it('sends plain text as a SUBSTRING matcher, not an exact string', () => {
    expect(bodyMatcher('widget')).toEqual({ type: 'STRING', string: 'widget', subString: true });
  });

  it('sends a JSON object or array as a (partial) JSON matcher', () => {
    expect(bodyMatcher(' {"order":"widget"} ')).toEqual({ type: 'JSON', json: '{"order":"widget"}' });
    expect(bodyMatcher('[1,2]')).toEqual({ type: 'JSON', json: '[1,2]' });
  });

  it('falls back to a substring for text that only looks like JSON, or a JSON scalar', () => {
    expect(bodyMatcher('{"order":')).toEqual({ type: 'STRING', string: '{"order":', subString: true });
    expect(bodyMatcher('42')).toEqual({ type: 'STRING', string: '42', subString: true });
  });
});

describe('buildVerifyBody', () => {
  it('defaults to httpRequest:{} when request is empty and no response is provided', () => {
    const body = buildVerifyBody({}, { mode: 'atLeast', count: 1 });
    expect(body).toEqual({ httpRequest: {}, times: { atLeast: 1 } });
  });

  it('includes httpRequest when a request matcher is provided', () => {
    const body = buildVerifyBody({ method: 'GET', path: '/api' }, { mode: 'exactly', count: 2 });
    expect(body).toEqual({ httpRequest: { method: 'GET', path: '/api' }, times: { atLeast: 2, atMost: 2 } });
  });

  it('omits httpRequest when request is empty and a response matcher is provided (response-only)', () => {
    const body = buildVerifyBody({}, { mode: 'atLeast', count: 1 }, { statusCode: 200 });
    expect(body).not.toHaveProperty('httpRequest');
    expect(body.httpResponse).toEqual({ statusCode: 200 });
    expect(body.times).toEqual({ atLeast: 1 });
  });

  it('includes both httpRequest and httpResponse when both are provided', () => {
    const body = buildVerifyBody({ path: '/api' }, { mode: 'atLeast', count: 1 }, { statusCode: 200 });
    expect(body.httpRequest).toEqual({ path: '/api' });
    expect(body.httpResponse).toEqual({ statusCode: 200 });
  });

  it('omits httpResponse when response is an empty object', () => {
    const body = buildVerifyBody({ path: '/api' }, { mode: 'atLeast', count: 1 }, {});
    expect(body).not.toHaveProperty('httpResponse');
  });
});

describe('buildVerifySequenceBody', () => {
  it('defaults to httpRequests with empty objects when all requests are empty and no responses are provided', () => {
    const body = buildVerifySequenceBody([{}, {}]);
    expect(body).toEqual({ httpRequests: [{}, {}] });
  });

  it('includes httpRequests when any step has a request', () => {
    const body = buildVerifySequenceBody([{ path: '/a' }, {}]);
    expect(body).toEqual({ httpRequests: [{ path: '/a' }, {}] });
  });

  it('omits httpRequests when all requests are empty and responses are provided (response-only)', () => {
    const body = buildVerifySequenceBody([{}, {}], [{ statusCode: 201 }, { statusCode: 200 }]);
    expect(body).not.toHaveProperty('httpRequests');
    expect(body.httpResponses).toEqual([{ statusCode: 201 }, { statusCode: 200 }]);
  });

  it('includes both httpRequests and httpResponses when both are present', () => {
    const body = buildVerifySequenceBody(
      [{ path: '/a' }, { path: '/b' }],
      [{ statusCode: 201 }, { statusCode: 200 }],
    );
    expect(body.httpRequests).toEqual([{ path: '/a' }, { path: '/b' }]);
    expect(body.httpResponses).toEqual([{ statusCode: 201 }, { statusCode: 200 }]);
  });

  it('includes httpRequests when requests are present even with responses', () => {
    const body = buildVerifySequenceBody(
      [{ path: '/a' }, {}],
      [{ statusCode: 201 }, undefined],
    );
    expect(body.httpRequests).toEqual([{ path: '/a' }, {}]);
    expect(body.httpResponses).toEqual([{ statusCode: 201 }, {}]);
  });
});

describe('verifyRequest / verifySequence', () => {
  it('treats 202 as verified and posts the right body to /verify', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    const res = await verifyRequest(params, { method: 'POST', path: '/o' }, { mode: 'exactly', count: 2 });
    expect(res).toEqual({ verified: true, failureMessage: null });
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/verify');
    expect(JSON.parse(init.body)).toEqual({ httpRequest: { method: 'POST', path: '/o' }, times: { atLeast: 2, atMost: 2 } });
  });

  it('propagates a network/fetch rejection', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')));
    await expect(verifyRequest(params, { path: '/o' }, { mode: 'atLeast', count: 1 })).rejects.toThrow('network down');
  });

  it('treats 406 as failed and returns the plain-text report', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 406, statusText: 'Not Acceptable', text: async () => 'Request not found exactly 2 times, expected:... but was:...' });
    vi.stubGlobal('fetch', fetchMock);

    const res = await verifyRequest(params, { path: '/o' }, { mode: 'exactly', count: 2 });
    expect(res.verified).toBe(false);
    expect(res.failureMessage).toContain('Request not found');
  });

  it('throws a 400 (bad input) as an error instead of reporting a failed verification', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 400, statusText: 'Bad Request', text: async () => 'incorrect verification json format' }));
    const err = await verifyRequest(params, { path: '/o' }, { mode: 'atLeast', count: 1 }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(Error);
    expect(humanizeError(err).message).toBe('The request was rejected as invalid.');
    expect(humanizeError(err).details).toBe('incorrect verification json format');
  });

  it('throws a 5xx as an error instead of reporting a failed verification', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 502, statusText: 'Bad Gateway', text: async () => '' }));
    await expect(verifySequence(params, [{ path: '/a' }])).rejects.toThrow('MockServer returned 502');
  });

  it('omits httpRequest from /verify body when request matcher is empty (response-only verify)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifyRequest(params, {}, { mode: 'atLeast', count: 1 }, { statusCode: 200 });

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/verify');
    const body = JSON.parse(init.body);
    expect(body).not.toHaveProperty('httpRequest');
    expect(body.httpResponse).toEqual({ statusCode: 200 });
    expect(body.times).toEqual({ atLeast: 1 });
  });

  it('posts httpRequests[] to /verifySequence', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifySequence(params, [{ method: 'POST', path: '/a' }, { method: 'GET', path: '/b' }]);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/verifySequence');
    expect(JSON.parse(init.body)).toEqual({ httpRequests: [{ method: 'POST', path: '/a' }, { method: 'GET', path: '/b' }] });
  });

  it('omits httpRequests from /verifySequence body when all request entries are empty (response-only sequence)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifySequence(
      params,
      [{}, {}],
      [{ statusCode: 201 }, { statusCode: 200 }],
    );

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/verifySequence');
    const body = JSON.parse(init.body);
    expect(body).not.toHaveProperty('httpRequests');
    expect(body.httpResponses).toEqual([{ statusCode: 201 }, { statusCode: 200 }]);
  });

  // --- Response matcher tests ---

  it('includes httpResponse in /verify body when a response matcher is provided', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifyRequest(
      params,
      { method: 'GET', path: '/api' },
      { mode: 'atLeast', count: 1 },
      { statusCode: 200, body: '{"ok":true}' },
    );

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body);
    expect(body.httpRequest).toEqual({ method: 'GET', path: '/api' });
    expect(body.httpResponse).toEqual({ statusCode: 200, body: '{"ok":true}' });
    expect(body.times).toEqual({ atLeast: 1 });
  });

  it('omits httpResponse from /verify body when no response matcher is provided', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifyRequest(params, { path: '/api' }, { mode: 'atLeast', count: 1 });

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body);
    expect(body).not.toHaveProperty('httpResponse');
  });

  it('omits httpResponse from /verify body when response matcher is an empty object', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifyRequest(params, { path: '/api' }, { mode: 'atLeast', count: 1 }, {});

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body);
    expect(body).not.toHaveProperty('httpResponse');
  });

  it('includes httpResponses in /verifySequence body when response matchers are provided', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifySequence(
      params,
      [{ method: 'POST', path: '/a' }, { method: 'GET', path: '/b' }],
      [{ statusCode: 201 }, { statusCode: 200, body: 'ok' }],
    );

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body);
    expect(body.httpRequests).toHaveLength(2);
    expect(body.httpResponses).toEqual([{ statusCode: 201 }, { statusCode: 200, body: 'ok' }]);
  });

  it('omits httpResponses from /verifySequence body when no response matchers are provided', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifySequence(params, [{ path: '/a' }, { path: '/b' }]);

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body);
    expect(body).not.toHaveProperty('httpResponses');
  });

  it('omits httpResponses from /verifySequence body when all response matchers are empty', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifySequence(
      params,
      [{ path: '/a' }, { path: '/b' }],
      [undefined, undefined],
    );

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body);
    expect(body).not.toHaveProperty('httpResponses');
  });

  it('pads sparse httpResponses with empty objects to preserve index alignment', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);

    await verifySequence(
      params,
      [{ path: '/a' }, { path: '/b' }, { path: '/c' }],
      [undefined, { statusCode: 200 }, undefined],
    );

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body);
    expect(body.httpResponses).toEqual([{}, { statusCode: 200 }, {}]);
  });
});
