/**
 * Active Expectations items whose long bodies the server shortened in a dashboard update
 * (`truncatedExpectation`). Their values are for display only: editing, duplicating or testing one loads
 * the whole expectation by id first, and the Composer refuses to save over a shortened value.
 */
import { fetchExpectation } from './expectations';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import type { JsonListItem } from '../types';

const shortenedValues = new WeakSet<object>();

/** Remembers the values of the shortened items in a dashboard update. */
export function rememberShortenedExpectations(items: JsonListItem[]): void {
  for (const item of items) {
    if (item.truncatedExpectation) shortenedValues.add(item.value);
  }
}

/** Whether this is the value of a shortened Active Expectations item, as the server sent it. */
export function isShortenedExpectation(value: unknown): boolean {
  return !!value && typeof value === 'object' && shortenedValues.has(value);
}

/** The item with its whole expectation, loaded by id when the server shortened it; otherwise the item itself. */
export async function loadWholeExpectation(params: ConnectionParams, item: JsonListItem): Promise<JsonListItem> {
  if (!item.truncatedExpectation) return item;
  const value = await fetchExpectation(params, item.truncatedExpectation.expectationId);
  const whole: JsonListItem = { ...item, value };
  delete whole.truncatedExpectation;
  return whole;
}
