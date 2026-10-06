import { useCallback } from 'react';
import { useConnectionParams } from './useConnectionParams';
import { useDashboardStore } from '../store';
import { fetchFullMessages, hasTruncatedBodies, withFullMessages } from '../lib/fullBody';
import type { JsonListItem } from '../types';

/**
 * Loads the whole request and response of a row whose bodies the server shortened, keeps them in the
 * store (so the row shows them from then on) and returns the row with them. A row sent whole is
 * returned as it is, without a request. Replay, Repeat, bulk Clear, Capture as Mock and Copy as curl
 * call this first and do nothing if it fails, so they never act on a shortened body. Compare, the
 * Create Mock launchpad and LLM parsing do not: they use the row as shown until it is loaded.
 */
export function useLoadFullRow(): (item: JsonListItem) => Promise<JsonListItem> {
  const params = useConnectionParams();
  const applyFullMessages = useDashboardStore((s) => s.applyFullMessages);
  return useCallback(
    async (item: JsonListItem) => {
      if (!hasTruncatedBodies(item)) return item;
      const full = await fetchFullMessages(params, item);
      applyFullMessages(item.key, full);
      return withFullMessages(item, full);
    },
    [params, applyFullMessages],
  );
}
