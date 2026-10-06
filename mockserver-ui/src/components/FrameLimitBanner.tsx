import Alert from '@mui/material/Alert';
import { useDashboardStore } from '../store';

/**
 * Shown while the server's latest update reached its size limit: large bodies filled it, so older
 * rows were left out. Bodies are shortened in updates, so this takes many large requests at once.
 */
export default function FrameLimitBanner() {
  const frameLimitReached = useDashboardStore((s) => s.frameLimitReached);
  if (!frameLimitReached) return null;
  return (
    <Alert severity="info" role="status" sx={{ mx: 1, mt: 1, flexShrink: 0 }} data-testid="frame-limit-banner">
      Older requests and log messages are not shown: the latest update reached its size limit because
      recent requests have large bodies. Filter the requests, or clear the log, to see older ones.
    </Alert>
  );
}
