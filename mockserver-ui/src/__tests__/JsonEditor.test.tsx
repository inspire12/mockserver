import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';

// Monaco cannot run inside jsdom (it needs real layout + web workers), so mock
// the @monaco-editor/react wrapper with a lightweight textarea stand-in. The
// stand-in exposes the props the component passes in (value/onChange/language/
// onValidate/onMount) so tests can drive editing and simulate validation
// markers exactly as the real Monaco would deliver them via onValidate.
type Marker = { message: string; startLineNumber: number; severity: number };
type EditorMockProps = {
  value: string;
  language?: string;
  onChange?: (value: string | undefined) => void;
  onMount?: (editor: unknown, monaco: unknown) => void;
  onValidate?: (markers: Marker[]) => void;
  options?: Record<string, unknown>;
};

// Captured so a test can fire validation after render, mimicking Monaco's async
// diagnostics callback.
let lastOnValidate: ((markers: Marker[]) => void) | undefined;
// The Monaco options the component passed, so keyboard/a11y options can be asserted.
let lastOptions: Record<string, unknown> | undefined;

// A functional jsonDefaults stub whose setDiagnosticsOptions actually mutates the
// backing `schemas` array, so tests can assert per-editor schema registration and
// its removal on unmount (M1p) rather than just that the setter was called.
type SchemaEntry = { uri?: string; fileMatch?: string[]; schema?: unknown };
const monacoStub = {
  languages: {
    json: {
      jsonDefaults: {
        diagnosticsOptions: { schemas: [] as SchemaEntry[] },
        setDiagnosticsOptions(opts: { schemas?: SchemaEntry[] }) {
          monacoStub.languages.json.jsonDefaults.diagnosticsOptions.schemas = opts.schemas ?? [];
        },
      },
    },
  },
};

const registeredSchemas = () => monacoStub.languages.json.jsonDefaults.diagnosticsOptions.schemas;

vi.mock('@monaco-editor/react', () => ({
  loader: { config: vi.fn() },
  default: ({ value, language, onChange, onMount, onValidate, options }: EditorMockProps) => {
    lastOnValidate = onValidate;
    lastOptions = options;
    onMount?.({}, monacoStub);
    return (
      <textarea
        data-testid="monaco-textarea"
        data-language={language}
        value={value}
        onChange={(e) => onChange?.(e.target.value)}
      />
    );
  },
}));

vi.mock('monaco-editor', () => ({
  MarkerSeverity: { Hint: 1, Info: 2, Warning: 4, Error: 8 },
  languages: { json: { jsonDefaults: { diagnosticsOptions: { schemas: [] }, setDiagnosticsOptions: vi.fn() } } },
}));

vi.mock('monaco-editor/editor/editor.worker?worker', () => ({ default: class {} }));
vi.mock('monaco-editor/language/json/json.worker?worker', () => ({ default: class {} }));

import JsonEditor from '../components/JsonEditor';

function renderEditor(props: Partial<React.ComponentProps<typeof JsonEditor>> = {}) {
  const onChange = props.onChange ?? vi.fn();
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <JsonEditor value={props.value ?? ''} onChange={onChange} {...props} />
    </ThemeProvider>,
  );
  return { onChange };
}

describe('JsonEditor', () => {
  beforeEach(() => {
    lastOnValidate = undefined;
    lastOptions = undefined;
    monacoStub.languages.json.jsonDefaults.diagnosticsOptions.schemas = [];
  });

  it('renders the editor with the supplied value and language', () => {
    renderEditor({ value: '{"a":1}', language: 'json', label: 'JSON body matcher' });
    const ta = screen.getByTestId('monaco-textarea') as HTMLTextAreaElement;
    expect(ta.value).toBe('{"a":1}');
    expect(ta.getAttribute('data-language')).toBe('json');
    expect(screen.getByText('JSON body matcher')).toBeInTheDocument();
  });

  it('lets Tab leave the editor and names its input after the field (no keyboard trap)', () => {
    renderEditor({ value: '{}', label: 'Response body', ariaLabel: 'Response body' });
    // Monaco keeps Tab for indentation unless tabFocusMode is on, which traps a
    // keyboard user inside the field (WCAG 2.1.2).
    expect(lastOptions?.tabFocusMode).toBe(true);
    // Without ariaLabel Monaco announces its input as "Editor content".
    expect(lastOptions?.ariaLabel).toBe('Response body');
    expect(screen.getByRole('group', { name: 'Response body' })).toBeInTheDocument();
  });

  it('names the editor input after the visible label when no ariaLabel is given', () => {
    renderEditor({ value: '{}', label: 'JSON body matcher' });
    expect(lastOptions?.ariaLabel).toBe('JSON body matcher');
  });

  it('propagates edits through onChange', async () => {
    const onChange = vi.fn();
    renderEditor({ value: '', onChange });
    const ta = screen.getByTestId('monaco-textarea');
    await userEvent.type(ta, 'x');
    expect(onChange).toHaveBeenCalledWith('x');
  });

  it('shows no error summary when JSON is valid (no markers)', () => {
    renderEditor({ value: '{"a":1}', language: 'json' });
    act(() => lastOnValidate?.([]));
    expect(screen.queryByTestId('json-editor-errors')).not.toBeInTheDocument();
  });

  it('flags invalid JSON inline with an error summary', () => {
    renderEditor({ value: '{ bad json', language: 'json' });
    act(() =>
      lastOnValidate?.([
        { message: 'Expected comma or closing brace', startLineNumber: 1, severity: 8 },
      ]),
    );
    const errors = screen.getByTestId('json-editor-errors');
    expect(errors).toHaveTextContent('Line 1');
    expect(errors).toHaveTextContent('Expected comma or closing brace');
  });

  it('summarises multiple problems and ignores sub-warning severities', () => {
    renderEditor({ value: '{}', language: 'json', schema: { type: 'object', required: ['name'] } });
    act(() =>
      lastOnValidate?.([
        { message: 'Missing property "name"', startLineNumber: 1, severity: 8 },
        { message: 'Another problem', startLineNumber: 2, severity: 4 },
        { message: 'just a hint', startLineNumber: 3, severity: 1 },
      ]),
    );
    const errors = screen.getByTestId('json-editor-errors');
    // 2 problems counted (the severity-1 hint is filtered out).
    expect(errors).toHaveTextContent('2 problems');
    expect(errors).toHaveTextContent('Missing property "name"');
  });

  describe('global monaco schema registration (M1p)', () => {
    const renderWithSchema = () =>
      render(
        <ThemeProvider theme={buildTheme('light')}>
          <JsonEditor value="{}" onChange={vi.fn()} language="json" schema={{ type: 'object' }} />
        </ThemeProvider>,
      );

    it('registers a schema entry on mount and removes it on unmount', () => {
      expect(registeredSchemas()).toHaveLength(0);
      const { unmount } = renderWithSchema();
      expect(registeredSchemas()).toHaveLength(1);
      unmount();
      expect(registeredSchemas()).toHaveLength(0);
    });

    it('does not accumulate entries across repeated mount/unmount cycles', () => {
      for (let i = 0; i < 3; i++) {
        const { unmount } = renderWithSchema();
        expect(registeredSchemas()).toHaveLength(1);
        unmount();
        expect(registeredSchemas()).toHaveLength(0);
      }
    });

    it('removes only the unmounted editor’s entry when several are mounted', () => {
      const a = renderWithSchema();
      const b = renderWithSchema();
      expect(registeredSchemas()).toHaveLength(2);
      a.unmount();
      expect(registeredSchemas()).toHaveLength(1);
      b.unmount();
      expect(registeredSchemas()).toHaveLength(0);
    });
  });

  it('renders the placeholder in the readable secondary text colour in dark mode', () => {
    render(
      <ThemeProvider theme={buildTheme('dark')}>
        <JsonEditor value="" onChange={vi.fn()} placeholder='{"hello":"world"}' />
      </ThemeProvider>,
    );
    // Stand in for the placeholder node Monaco renders inside its editor element.
    const editor = document.createElement('div');
    editor.className = 'monaco-editor';
    const placeholder = document.createElement('div');
    placeholder.className = 'editorPlaceholder';
    editor.appendChild(placeholder);
    screen.getByTestId('json-editor').appendChild(editor);
    expect(getComputedStyle(placeholder).color).toBe(buildTheme('dark').palette.text.secondary.replace(/,(?=\S)/g, ', '));
  });
});
