import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import FrameLimitBanner from '../components/FrameLimitBanner';
import { useDashboardStore } from '../store';

function state(frameLimitReached: boolean, logMessagesLimitReached: boolean) {
  useDashboardStore.setState({ frameLimitReached, logMessagesLimitReached });
}

function banner(view: 'dashboard' | 'traffic'): HTMLElement | null {
  render(<FrameLimitBanner view={view} />);
  return screen.queryByTestId('frame-limit-banner');
}

describe('FrameLimitBanner', () => {
  beforeEach(() => state(false, false));

  it('shows nothing when the update held everything', () => {
    expect(banner('traffic')).toBeNull();
    expect(screen.queryByTestId('frame-limit-banner')).toBeNull();
  });

  it('on Traffic, says nothing when only log messages were left out, since Traffic shows none', () => {
    state(false, true);
    expect(banner('traffic')).toBeNull();
  });

  it('on the Dashboard, names only the log messages when only they were left out, and does not blame bodies', () => {
    state(false, true);
    const b = banner('dashboard')!;
    expect(b).toHaveTextContent(/^Older log messages are not shown/);
    expect(b).toHaveTextContent('a message for each expectation');
    expect(b).not.toHaveTextContent(/bodies/);
  });

  it('names only the requests when only request rows were left out', () => {
    state(true, false);
    expect(banner('dashboard')).toHaveTextContent(/^Older requests are not shown/);
  });

  it('names both on the Dashboard when both were left out, and only the requests on Traffic', () => {
    state(true, true);
    const { unmount } = render(<FrameLimitBanner view="dashboard" />);
    expect(screen.getByTestId('frame-limit-banner')).toHaveTextContent(/^Older requests and log messages are not shown/);
    unmount();
    expect(banner('traffic')).toHaveTextContent(/^Older requests are not shown/);
  });
});
