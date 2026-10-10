import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { createElement, useEffect } from 'react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';

// A stand-in for @monaco-editor/react's DiffEditor that, like the real wrapper,
// hands a Monaco instance to onMount and disposes the diff widget on unmount —
// recording the order in which the widget and the two text models go away.
type FakeModel = { path: string; disposed: boolean; isDisposed(): boolean; dispose(): void };
const events: string[] = [];
const models = new Map<string, FakeModel>();
let lastProps: Record<string, unknown> | undefined;

function model(path: string): FakeModel {
  let m = models.get(path);
  if (!m) {
    m = {
      path,
      disposed: false,
      isDisposed() {
        return this.disposed;
      },
      dispose() {
        this.disposed = true;
        events.push(`dispose model ${path.endsWith('-original.json') ? 'original' : 'modified'}`);
      },
    };
    models.set(path, m);
  }
  return m;
}

const fakeMonaco = {
  Uri: { parse: (path: string) => path },
  editor: { getModel: (uri: string) => models.get(uri) ?? null },
};

vi.mock('@monaco-editor/react', () => ({
  loader: { config: vi.fn() },
  DiffEditor: (props: Record<string, unknown>) => {
    lastProps = props;
    const { original, modified, originalModelPath, modifiedModelPath, keepCurrentOriginalModel, keepCurrentModifiedModel, onMount } =
      props as {
        original: string;
        modified: string;
        originalModelPath: string;
        modifiedModelPath: string;
        keepCurrentOriginalModel?: boolean;
        keepCurrentModifiedModel?: boolean;
        onMount?: (editor: unknown, monaco: unknown) => void;
      };
    useEffect(() => {
      const o = model(originalModelPath);
      const m = model(modifiedModelPath);
      onMount?.({ setModel: (m: unknown) => events.push(m === null ? 'detach models' : 'attach models') }, fakeMonaco);
      return () => {
        // the real wrapper's order: models first (unless kept), then the widget
        if (!keepCurrentOriginalModel) o.dispose();
        if (!keepCurrentModifiedModel) m.dispose();
        events.push('dispose widget');
      };
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    return createElement('div', { 'data-testid': 'monaco-diff' }, [
      createElement('textarea', { key: 'o', 'data-testid': 'monaco-diff-original', readOnly: true, value: original }),
      createElement('textarea', { key: 'm', 'data-testid': 'monaco-diff-modified', readOnly: true, value: modified }),
    ]);
  },
}));

import JsonDiffViewer from '../components/JsonDiffViewer';

function renderViewer(props: Partial<React.ComponentProps<typeof JsonDiffViewer>> = {}) {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <JsonDiffViewer original="{}" modified={'{\n  "a": 1\n}'} {...props} />
    </ThemeProvider>,
  );
}

describe('JsonDiffViewer', () => {
  beforeEach(() => {
    events.length = 0;
    models.clear();
    lastProps = undefined;
  });

  it('renders both original and modified panes', () => {
    renderViewer();
    expect(screen.getByTestId('json-diff-viewer')).toBeInTheDocument();
    expect(screen.getByTestId('monaco-diff-original')).toHaveValue('{}');
    expect(screen.getByTestId('monaco-diff-modified')).toHaveValue('{\n  "a": 1\n}');
  });

  it('renders an accessible label when provided', () => {
    renderViewer({ label: 'Changes', ariaLabel: 'Changes preview' });
    expect(screen.getByText('Changes')).toBeInTheDocument();
    expect(screen.getByLabelText('Changes preview')).toBeInTheDocument();
  });

  it('detaches its text models from the live diff widget before either is disposed', () => {
    // Disposing models the widget still holds makes Monaco throw "TextModel got
    // disposed before DiffEditorWidget model got reset"; disposing them after the
    // widget wakes its disposed gutter ("AbstractContextKeyService has been disposed").
    const { unmount } = renderViewer();
    unmount();
    expect(events).toEqual(['detach models', 'dispose model original', 'dispose model modified', 'dispose widget']);
  });

  it('lets Tab leave the read-only diff and names both panes', () => {
    renderViewer({ ariaLabel: 'Changes preview' });
    const options = lastProps?.options as Record<string, unknown>;
    expect(options.tabFocusMode).toBe(true);
    expect(options.originalAriaLabel).toBe('Changes preview (before)');
    expect(options.modifiedAriaLabel).toBe('Changes preview (after)');
  });
});
