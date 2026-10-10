import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AsyncApiDialog from '../components/AsyncApiDialog';

const params = { host: '127.0.0.1', port: '1080', secure: false };

afterEach(() => {
  vi.unstubAllGlobals();
});

function respond(byPath: Record<string, { status: number; body: string }>) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      const path = new URL(url).pathname;
      const r = byPath[path] ?? { status: 200, body: '{"loaded":true,"channels":[]}' };
      return {
        ok: r.status < 300,
        status: r.status,
        statusText: r.status === 400 ? 'Bad Request' : 'OK',
        json: async () => JSON.parse(r.body),
        text: async () => r.body,
      };
    }),
  );
}

describe('AsyncApiDialog', () => {
  it('teaches the verification shape the server reads: count constraints under "count"', () => {
    respond({});
    render(<AsyncApiDialog open onClose={() => {}} connectionParams={params} />);
    const field = screen.getByLabelText('Verification request (JSON)');
    expect(field).toHaveAttribute('placeholder', '{\n  "channel": "orders",\n  "count": { "atLeast": 1 }\n}');
    expect(screen.getByText(/Count constraints go under "count"/)).toBeInTheDocument();
  });

  it('shows the server\'s reason when it refuses a verification request', async () => {
    const reason = "'atMost' must be inside \"count\", e.g. {\"channel\":\"orders\",\"count\":{\"atMost\":1}}";
    respond({ '/mockserver/asyncapi/verify': { status: 400, body: JSON.stringify({ error: reason }) } });
    const user = userEvent.setup();
    render(<AsyncApiDialog open onClose={() => {}} connectionParams={params} />);
    const field = screen.getByLabelText('Verification request (JSON)');
    await user.click(field);
    await user.paste('{"channel":"orders","atMost":0}');
    await user.click(screen.getByRole('button', { name: 'Verify messages' }));
    expect(await screen.findByText(/'atMost' must be inside "count"/)).toBeInTheDocument();
  });

  it('shows the server\'s parse error when a spec cannot be loaded', async () => {
    const reason = 'failed to load AsyncAPI spec: while parsing a flow node\n in reader, line 1, column 11:\n    garbage: [';
    respond({ '/mockserver/asyncapi': { status: 400, body: JSON.stringify({ error: reason }) } });
    const user = userEvent.setup();
    render(<AsyncApiDialog open onClose={() => {}} connectionParams={params} />);
    await user.click(screen.getByLabelText('AsyncAPI spec (JSON / YAML)'));
    await user.paste('garbage: [');
    await user.click(screen.getByRole('button', { name: 'Load spec' }));
    expect(await screen.findByText(/failed to load AsyncAPI spec: while parsing a flow node/)).toBeInTheDocument();
  });
});
