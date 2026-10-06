import { useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import type { SxProps, Theme } from '@mui/material/styles';
import type { TruncatedBody } from '../types';
import { formatCharacters, isLoadable } from '../lib/fullBody';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import HumanErrorAlert from './HumanErrorAlert';

interface TruncatedBodyNoticeProps {
  marker: TruncatedBody;
  /** Loads the whole body; omitted when it cannot be loaded (an expectation in a log message). */
  onLoad?: () => Promise<void>;
  sx?: SxProps<Theme>;
}

/** Says a body was shortened in the live update, with a button that loads it whole. */
export default function TruncatedBodyNotice({ marker, onLoad, sx }: TruncatedBodyNoticeProps) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<HumanError | null>(null);

  const load = async () => {
    if (!onLoad) return;
    setLoading(true);
    setError(null);
    try {
      await onLoad();
    } catch (e) {
      setError(humanizeError(e));
    } finally {
      setLoading(false);
    }
  };

  const loadable = !!onLoad && isLoadable(marker);
  return (
    <>
      <Alert
        severity="info"
        variant="outlined"
        sx={{ py: 0, my: 0.5, ...(sx as object) }}
        data-testid="truncated-body-notice"
        action={loadable ? (
          <Button
            size="small"
            onClick={load}
            disabled={loading}
            startIcon={loading ? <CircularProgress size={12} /> : undefined}
            data-testid="load-full-body"
          >
            Load Full Body
          </Button>
        ) : undefined}
      >
        Body shortened: showing the first {formatCharacters(marker.shownLength)} of {formatCharacters(marker.originalLength)}.
      </Alert>
      {error && <HumanErrorAlert error={error} sx={{ my: 0.5 }} />}
    </>
  );
}
