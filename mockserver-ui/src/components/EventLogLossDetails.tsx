import Box from '@mui/material/Box';
import {
  EVENT_LOG_LOSS_ALTERNATIVES,
  EVENT_LOG_LOSS_LEAD,
  eventLogLossCauses,
  type EventLogLoss,
} from '../lib/eventLogLoss';
import { monospaceFontFamily } from '../theme';

/**
 * Shown inside a failed-verification log entry when the verification could not
 * be trusted because the event log was incomplete: which bound was reached, how
 * many entries were lost to it, and the setting to change. It is the same
 * information the REST client gets in the verification failure message.
 */
interface EventLogLossDetailsProps {
  loss: EventLogLoss;
}

export default function EventLogLossDetails({ loss }: EventLogLossDetailsProps) {
  return (
    <Box
      role="note"
      aria-label="What the event log lost and how to fix it"
      data-testid="event-log-loss"
      sx={{
        // a log row does not wrap; this is prose, so it must
        whiteSpace: 'normal',
        maxWidth: '110ch',
        mt: 0.5,
        ml: 0.5,
        pl: 1,
        borderLeft: 2,
        borderColor: 'warning.main',
        color: 'text.primary',
        fontFamily: monospaceFontFamily,
      }}
    >
      {EVENT_LOG_LOSS_LEAD}
      <Box component="ul" sx={{ my: 0.5, pl: 2.5 }}>
        {eventLogLossCauses(loss).map((cause) => (
          <li key={cause.id} data-testid={`event-log-loss-${cause.id}`}>
            {cause.lost} {cause.fix}
          </li>
        ))}
      </Box>
      {EVENT_LOG_LOSS_ALTERNATIVES}
    </Box>
  );
}
