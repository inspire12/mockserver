import Alert from '@mui/material/Alert';
import { useDashboardStore } from '../store';

interface FrameLimitBannerProps {
  /** Traffic shows no log messages, so only a cut in the request rows concerns it. */
  view: 'dashboard' | 'traffic';
}

/**
 * Shown while the server's latest update reached its size limit, naming what was left out. Log
 * messages may use only part of an update, so they can be cut while every request row still fits.
 */
export default function FrameLimitBanner({ view }: FrameLimitBannerProps) {
  const requestsCut = useDashboardStore((s) => s.frameLimitReached);
  const logMessagesCut = useDashboardStore((s) => s.logMessagesLimitReached) && view === 'dashboard';
  if (!requestsCut && !logMessagesCut) return null;
  const what = requestsCut && logMessagesCut
    ? 'Older requests and log messages are'
    : requestsCut ? 'Older requests are' : 'Older log messages are';
  return (
    <Alert severity="info" role="status" sx={{ mx: 1, mt: 1, flexShrink: 0 }} data-testid="frame-limit-banner">
      {what} not shown: the latest update reached its size limit.{' '}
      {requestsCut
        ? 'Recent requests, with their bodies and log messages, filled it.'
        : 'Recent requests logged more than it has room for; for example, a request that matches no expectation logs a message for each expectation it was compared with.'}
      {' '}Filter the requests, or clear the log, to see older ones.
    </Alert>
  );
}
