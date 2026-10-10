/**
 * Client for per-expectation control-plane operations against MockServer.
 *
 * MockServer removes a single registered expectation through the same clear
 * endpoint used for the bulk "clear all expectations" action, but scoped to one
 * expectation by passing an {@link https://www.mock-server.com ExpectationId}
 * (`{ "id": "<expectationId>" }`) as the request body:
 *
 *   PUT /mockserver/clear?type=expectations
 *   Content-Type: application/json
 *   { "id": "<expectationId>" }
 *
 * Verified live against the demo server: only the matching expectation is
 * removed; recorded requests and logs are left untouched, and the call returns
 * 200. See HttpState.clear (mockserver-core), which deserialises the body as an
 * ExpectationId and calls requestMatchers.clear(expectationId, ...).
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/**
 * Throw a descriptive Error on a non-2xx response, preferring the server's
 * `{ "error": "..." }` envelope when present (matching the convention in
 * serviceChaos.ts / drift.ts and what humanizeError expects to parse).
 */
async function ensureOk(res: Response): Promise<void> {
  if (res.ok) return;
  let message = `HTTP ${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { error?: unknown };
    if (body && typeof body.error === 'string') message = body.error;
  } catch {
    // non-JSON body — keep the status-line message
  }
  throw new Error(message);
}

/**
 * Delete a single registered expectation by its id. Throws on any non-2xx
 * response so callers can surface the failure via humanizeError.
 */
export async function deleteExpectation(params: ConnectionParams, id: string): Promise<void> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/clear?type=expectations`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id }),
  });
  await ensureOk(res);
}

/**
 * The whole registered expectation with this id, as `PUT /mockserver/retrieve?type=active_expectations`
 * returns it. Throws when the request fails or the expectation is no longer registered.
 */
export async function fetchExpectation(
  params: ConnectionParams,
  id: string,
  signal?: AbortSignal,
): Promise<Record<string, unknown>> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/retrieve?type=active_expectations&format=json`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id }),
    signal,
  });
  await ensureOk(res);
  const expectations = (await res.json().catch(() => null)) as unknown;
  const found = Array.isArray(expectations)
    ? expectations.find((e): e is Record<string, unknown> => !!e && typeof e === 'object' && (e as Record<string, unknown>)['id'] === id)
    : undefined;
  if (!found) throw new Error(`Expectation ${id} is no longer registered`);
  return found;
}

/**
 * Every active expectation on the server, whole, as `PUT /mockserver/retrieve?type=active_expectations`
 * returns it. The dashboard's live list is a capped window; this reaches the ones beyond it.
 */
export async function fetchActiveExpectations(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<Record<string, unknown>[]> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/retrieve?type=active_expectations&format=json`, {
    method: 'PUT',
    signal,
  });
  await ensureOk(res);
  const expectations = (await res.json().catch(() => null)) as unknown;
  return Array.isArray(expectations)
    ? expectations.filter((e): e is Record<string, unknown> => !!e && typeof e === 'object')
    : [];
}
