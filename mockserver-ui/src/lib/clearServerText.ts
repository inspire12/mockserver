/**
 * Confirmation copy for "Clear Server Logs" (`PUT /mockserver/clear?type=log`), shared by the
 * Clear menu and the Cmd/Ctrl+Shift+L shortcut so the two prompts cannot drift apart. Recorded
 * requests live in the same server event log as the log messages, so clearing the log removes
 * them too; only expectations survive.
 */
export const CLEAR_LOGS_CONFIRM_TITLE = 'Clear server logs?';
export const CLEAR_LOGS_CONFIRM_MESSAGE =
  'This removes the server log, including every recorded and proxied request and the log messages about them. Expectations are kept. This cannot be undone.';
export const CLEAR_LOGS_DONE_MESSAGE = 'Server logs and recorded requests cleared';
