/**
 * Ctrl+M (Ctrl+Shift+M on macOS) switches ONE editor between Tab-moves-focus and
 * Tab-indents. The chord is registered per editor with addAction (Monaco scopes an
 * action's keybinding to its own editor and returns a disposable), so with several
 * editors on a page each toggles only itself, and an unmounted editor's binding goes.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { createElement, useEffect } from 'react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';

const KeyMod = { CtrlCmd: 2048, Shift: 1024, Alt: 512, WinCtrl: 256 };
const KeyCode = { KeyM: 43 };

interface FakeAction {
  id: string;
  keybindings: number[];
  keybindingContext?: string;
  run: (editor: unknown) => void;
  disposed: boolean;
}
interface FakeEditorHandle {
  label: string;
  actions: FakeAction[];
}
const editors: FakeEditorHandle[] = [];

const fakeMonaco = {
  KeyMod,
  KeyCode,
  languages: { json: { jsonDefaults: { diagnosticsOptions: { schemas: [] }, setDiagnosticsOptions: vi.fn() } } },
};

vi.mock('@monaco-editor/react', () => {
  // Like the real wrapper: one editor per mounted component, onMount once, options re-applied each render.
  function MonacoEditorStandIn({ options, onMount }: { options?: { tabFocusMode?: boolean; ariaLabel?: string }; onMount?: (e: unknown, m: unknown) => void }) {
    useEffect(() => {
      const editor: FakeEditorHandle & { addAction: (d: Omit<FakeAction, 'disposed'>) => { dispose(): void } } = {
        label: options?.ariaLabel ?? '',
        actions: [],
        addAction(descriptor) {
          const action: FakeAction = { ...descriptor, disposed: false };
          editor.actions.push(action);
          return { dispose: () => { action.disposed = true; } };
        },
      };
      editors.push(editor);
      onMount?.(editor, fakeMonaco);
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    return createElement('textarea', {
      'aria-label': options?.ariaLabel,
      'data-tab-focus-mode': String(options?.tabFocusMode),
      readOnly: true,
    });
  }
  return { loader: { config: vi.fn() }, default: MonacoEditorStandIn };
});

vi.mock('monaco-editor', () => ({
  MarkerSeverity: { Hint: 1, Info: 2, Warning: 4, Error: 8 },
  languages: { json: { jsonDefaults: { diagnosticsOptions: { schemas: [] }, setDiagnosticsOptions: vi.fn() } } },
}));
vi.mock('monaco-editor/editor/editor.worker?worker', () => ({ default: class {} }));
vi.mock('monaco-editor/language/json/json.worker?worker', () => ({ default: class {} }));

import JsonEditor from '../components/JsonEditor';

function renderTwo() {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <JsonEditor value="{}" onChange={vi.fn()} ariaLabel="Request body" />
      <JsonEditor value="{}" onChange={vi.fn()} ariaLabel="Response body" />
    </ThemeProvider>,
  );
}

const tabFocusMode = (name: string) => screen.getByRole('textbox', { name }).getAttribute('data-tab-focus-mode');
const editorNamed = (name: string) => editors.find((e) => e.label === name)!;

describe('JsonEditor Tab-focus toggle', () => {
  afterEach(() => {
    editors.length = 0;
    vi.restoreAllMocks();
  });

  it('toggles only the editor whose action runs, and back', () => {
    renderTwo();
    expect(tabFocusMode('Request body')).toBe('true');
    expect(tabFocusMode('Response body')).toBe('true');

    const response = editorNamed('Response body');
    expect(response.actions).toHaveLength(1);
    act(() => response.actions[0]!.run(response));
    expect(tabFocusMode('Response body')).toBe('false');
    expect(tabFocusMode('Request body')).toBe('true');

    act(() => response.actions[0]!.run(response));
    expect(tabFocusMode('Response body')).toBe('true');
  });

  it('registers one action per editor, bound to Ctrl+M (Ctrl+Shift+M on macOS) while that editor has text focus', () => {
    for (const [userAgent, chord] of [
      ['Mozilla/5.0 (Windows NT 10.0; Win64; x64)', KeyMod.CtrlCmd | KeyCode.KeyM],
      ['Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)', KeyMod.WinCtrl | KeyMod.Shift | KeyCode.KeyM],
    ] as const) {
      vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(userAgent);
      const { unmount } = renderTwo();
      for (const editor of editors) {
        expect(editor.actions.map((a) => [a.id, a.keybindings, a.keybindingContext])).toEqual([
          ['mockserver.toggleTabMovesFocus', [chord], 'editorTextFocus'],
        ]);
      }
      unmount();
      editors.length = 0;
      vi.restoreAllMocks();
    }
  });

  it('disposes its action when the editor unmounts', () => {
    const { unmount } = renderTwo();
    const registered = editors.flatMap((e) => e.actions);
    expect(registered.every((a) => !a.disposed)).toBe(true);
    unmount();
    expect(registered.every((a) => a.disposed)).toBe(true);
  });
});
