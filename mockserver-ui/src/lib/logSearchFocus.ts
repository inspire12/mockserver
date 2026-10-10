/**
 * Lets the Cmd/Ctrl+K shortcut (owned by App) focus the Log Messages search field (owned by
 * LogPanel, deep inside the Dashboard view). LogPanel registers its input while mounted; a focus
 * request made while no field is mounted (another view is showing) is held and applied when the
 * field registers, so the shortcut can switch to the Dashboard and land in the field.
 */
// A held request expires so a field that mounts much later (the user navigated away and back)
// does not steal focus out of nowhere.
const PENDING_TTL_MS = 2000;

let input: HTMLInputElement | null = null;
let pendingSince: number | null = null;

/** Registers the mounted search field; returns the matching unregister for the unmount cleanup. */
export function registerLogSearchInput(el: HTMLInputElement): () => void {
  input = el;
  if (pendingSince !== null) {
    const fresh = Date.now() - pendingSince <= PENDING_TTL_MS;
    pendingSince = null;
    if (fresh) el.focus();
  }
  return () => {
    if (input === el) input = null;
  };
}

/** Focuses the log search field now if it is mounted; returns false (and holds the request) if not. */
export function requestLogSearchFocus(): boolean {
  if (input && input.isConnected) {
    pendingSince = null;
    input.focus();
    return true;
  }
  pendingSince = Date.now();
  return false;
}

/** Test seam: forget any registered field and held request. */
export function resetLogSearchFocus(): void {
  input = null;
  pendingSince = null;
}
