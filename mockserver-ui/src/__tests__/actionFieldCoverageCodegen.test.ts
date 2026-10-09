/**
 * The Python, Ruby and Rust code tabs never drop an action field silently. For an edit
 * whose loaded action carries every schema field, each snippet renders a field through
 * its client's model or names it in a NOTE; a field no model knows is named the same way.
 * Where python3 / ruby and the in-repo clients are present, the snippets run against the
 * real client models and must rebuild the JSON tab's expectation, less the named fields.
 */
// Node built-ins are ambiently declared in the co-located node-builtins.d.ts.
import { describe, it, expect } from 'vitest';
import { execFileSync } from 'child_process';
import { mkdtempSync, writeFileSync, existsSync } from 'fs';
import { tmpdir } from 'os';
import { join, resolve } from 'path';
import { buildExpectationJson } from '../lib/standardCodegen';
import { standardToPython } from '../lib/codegen/python';
import { standardToRuby } from '../lib/codegen/ruby';
import { standardToRust } from '../lib/codegen/rust';
import { actionFieldCombos, FULL_ACTIONS } from '../lib/codegen/actionFieldCases';
import type { Combo } from '../lib/codegen/extractParityCases';
import { actionFieldGolden } from '../lib/codegen/__fixtures__/actionFieldGolden';

type Wire = Record<string, unknown>;
type Emit = (c: Combo) => string;

const EMITTERS: Record<string, Emit> = {
  python: (c) => standardToPython(c.matcher, c.action, c.baseUrl),
  ruby: (c) => standardToRuby(c.matcher, c.action, c.baseUrl),
  rust: (c) => standardToRust(c.matcher, c.action, c.baseUrl),
};

const actionKey = (c: Combo): string => c.name.replace('all-fields-', '');

/** The wire paths a Python or Ruby snippet's NOTE names. */
function notedPaths(code: string): string[] {
  const note = code.match(/^# NOTE: the (?:Python|Ruby) snippet omits field\(s\) its client model cannot hold: (.*) -- see the JSON tab\.$/m);
  return note ? note[1]!.split(', ') : [];
}

/** Each inline Rust NOTE, as `<Model>: <fields>`. */
function rustNotes(code: string): string[] {
  return Array.from(code.matchAll(/\/\* NOTE: the Rust (\w+) model has no (.*?); omitted \*\//g), (m) => `${m[1]}: ${m[2]}`);
}

/** What the Python and Ruby models cannot hold (identical for both clients). */
const MODEL_GAPS: Record<string, string[]> = {
  httpResponse: ['httpResponse.statusCodeRange', 'httpResponse.generateFromSchema', 'httpResponse.recoverAfter'],
  httpForward: ['httpForward.delay.template', 'httpForward.delay.templateType'],
  httpResponseTemplate: ['httpResponseTemplate.responseOverride', 'httpResponseTemplate.responseModifier'],
  httpWebSocketResponse: ['httpWebSocketResponse.templateType', 'httpWebSocketResponse.graphqlSubscriptionFilter'],
  httpSseResponse: ['httpSseResponse.templateType'],
  httpForwardTemplate: ['httpForwardTemplate.delay.template', 'httpForwardTemplate.delay.templateType'],
  httpForwardClassCallback: ['httpForwardClassCallback.delay.template', 'httpForwardClassCallback.delay.templateType'],
};

/** What the Rust models cannot hold; every other field is typed or carried in an `extra` map. */
const RUST_GAPS: Record<string, string[]> = {
  httpForward: ['Delay: template, templateType'],
  httpResponseTemplate: ['HttpTemplate: responseOverride, responseModifier', 'Delay: distribution'],
  httpForwardTemplate: ['Delay: template, templateType'],
  httpForwardClassCallback: ['Delay: template, templateType'],
};
const rustGaps = (key: string): string[] => RUST_GAPS[key] ?? ['Delay: distribution'];

describe('all-fields action edits', () => {
  it.each(actionFieldCombos.map((c) => [c.name, c] as const))('%s: the saved expectation carries every field', (_name, c) => {
    expect(buildExpectationJson(c.matcher, c.action)[actionKey(c)]).toEqual(FULL_ACTIONS[actionKey(c)]);
  });

  for (const [language, emit] of Object.entries(EMITTERS)) {
    it.each(actionFieldCombos.map((c) => [c.name, c] as const))(`${language}: %s matches the golden`, (_name, c) => {
      expect(emit(c)).toBe(actionFieldGolden[language]![c.name]);
    });
  }

  for (const language of ['python', 'ruby']) {
    it.each(actionFieldCombos.map((c) => [c.name, c] as const))(`${language}: %s names exactly what its model cannot hold`, (_name, c) => {
      expect(notedPaths(EMITTERS[language]!(c))).toEqual(MODEL_GAPS[actionKey(c)] ?? []);
    });
  }

  it.each(actionFieldCombos.map((c) => [c.name, c] as const))('rust: %s names exactly what its models cannot hold', (_name, c) => {
    expect(rustNotes(EMITTERS['rust']!(c))).toEqual(rustGaps(actionKey(c)));
  });
});

// ---------------------------------------------------------------------------
// A field no client model knows, added to each object of each action in turn.
// ---------------------------------------------------------------------------

const UNKNOWN = 'futureField';

/** The wire path of every object inside `value`, `at` included. */
function objectPaths(value: unknown, at: string): string[] {
  if (Array.isArray(value)) return value.flatMap((v, n) => objectPaths(v, `${at}[${n}]`));
  if (!value || typeof value !== 'object') return [];
  return [at, ...Object.entries(value as Wire).flatMap(([k, v]) => objectPaths(v, `${at}.${k}`))];
}

function withUnknownAt(c: Combo, path: string): Combo {
  const original = structuredClone((c.action.editOriginal as Wire)) as Wire;
  let node: unknown = original;
  for (const seg of path.match(/[^.[\]]+/g)!) node = (node as Record<string, unknown>)[seg];
  (node as Wire)[UNKNOWN] = 'future-value';
  return { ...c, action: { ...c.action, editOriginal: original } };
}

/** A snippet carries the unknown field verbatim when it renders its object as a JSON / Hash literal. */
const carriedVerbatim = (code: string): boolean => code.includes(`"${UNKNOWN}"`);

describe('a field no client model knows is never dropped silently', () => {
  const cases = actionFieldCombos.flatMap((c) => objectPaths(FULL_ACTIONS[actionKey(c)], actionKey(c)).map((p) => [p, c] as const));

  it('covers every object of every action', () => {
    expect(cases.length).toBeGreaterThan(60);
  });

  for (const language of ['python', 'ruby']) {
    it.each(cases)(`${language}: an unknown field at %s is named or carried`, (path, c) => {
      const code = EMITTERS[language]!(withUnknownAt(c, path));
      const noted = notedPaths(code);
      const omittedAncestor = noted.some((p) => path === p || path.startsWith(`${p}.`) || path.startsWith(`${p}[`));
      expect(noted.includes(`${path}.${UNKNOWN}`) || carriedVerbatim(code) || omittedAncestor, code).toBe(true);
    });
  }

  it.each(cases)('rust: an unknown field at %s is named or carried', (path, c) => {
    const code = EMITTERS['rust']!(withUnknownAt(c, path));
    const namedFields = rustNotes(code).flatMap((n) => n.split(': ')[1]!.split(', '));
    // A field inside one the snippet already names (a Delay's distribution) goes with it.
    const insideNamedField = path.match(/[^.[\]]+/g)!.slice(1).some((seg) => namedFields.includes(seg));
    expect(namedFields.includes(UNKNOWN) || carriedVerbatim(code) || insideNamedField, code).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// Execution against the real Python and Ruby client models (gated on the toolchain).
// ---------------------------------------------------------------------------

const REPO_ROOT = resolve(process.cwd(), '..');
const PYTHON_CLIENT = join(REPO_ROOT, 'mockserver-client-python');
const RUBY_CLIENT = join(REPO_ROOT, 'mockserver-client-ruby');

function available(cmd: string): boolean {
  try {
    execFileSync(cmd, ['--version'], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

/** Drops the paths the NOTE names, then compares under the client's list encodings (the Ruby verifier mirrors it). */
const PY_VERIFIER = String.raw`
import json, sys, copy, re
CLIENT_DIR, MANIFEST = sys.argv[1], sys.argv[2]
sys.path.insert(0, CLIENT_DIR)
import mockserver

ALIASES = {'requestOverride': 'httpRequest', 'responseOverride': 'httpResponse'}

def norm_kmv(v):
    pairs = []
    if isinstance(v, dict):
        for k, vals in v.items():
            pairs.append([k, list(vals) if isinstance(vals, list) else [vals]])
    elif isinstance(v, list):
        for item in v:
            pairs.append([item['name'], list(item.get('values', []))] if isinstance(item, dict) and 'name' in item else item)
    pairs.sort(key=lambda p: json.dumps(p, sort_keys=True))
    return pairs

def norm(x):
    if isinstance(x, dict):
        return {ALIASES.get(k, k): norm_kmv(v) if k in ('headers', 'queryStringParameters', 'trailers') else norm(v) for k, v in x.items()}
    if isinstance(x, list):
        return [norm(i) for i in x]
    return x

def drop(obj, path):
    segs = re.findall(r'[^.\[\]]+', path)
    for s in segs[:-1]:
        obj = obj[int(s)] if isinstance(obj, list) else obj[s]
    del obj[segs[-1]]

def capture(code):
    captured = {}
    class Recorder:
        def __init__(self, *a, **k): pass
        def upsert(self, *exps):
            captured['dict'] = exps[0].to_dict()
    orig = mockserver.MockServerClient
    mockserver.MockServerClient = Recorder
    try:
        exec(compile(code, '<generated>', 'exec'), {})
    finally:
        mockserver.MockServerClient = orig
    return captured['dict']

entries = json.load(open(MANIFEST))
failed = []
for e in entries:
    expected = copy.deepcopy(e['expected'])
    for p in e['omitted']:
        drop(expected, p)
    actual = capture(e['code'])
    if norm(actual) != norm(expected):
        failed.append({'name': e['name'], 'actual': actual})
neg = copy.deepcopy(entries[0])
neg['expected'][neg['key']]['primary'] = False
print(json.dumps({'total': len(entries), 'failed': failed, 'negativeControlDetected': norm(capture(neg['code'])) != norm(neg['expected'])}))
`;

const RB_VERIFIER = String.raw`
require 'json'
CLIENT_DIR, MANIFEST = ARGV
$LOAD_PATH.unshift(File.join(CLIENT_DIR, 'lib'))
require 'mockserver-client'

module MockServer
  class Client
    def initialize(*_args, **_kwargs); end
    def upsert(*expectations)
      $captured = expectations.first.to_h
      expectations
    end
  end
end

def norm_kmv(v)
  pairs = if v.is_a?(Hash)
            v.map { |k, vals| [k, vals.is_a?(Array) ? vals : [vals]] }
          else
            v.map { |item| item.is_a?(Hash) && item.key?('name') ? [item['name'], item['values'] || []] : item }
          end
  pairs.sort_by { |p| JSON.generate(p) }
end

def norm(x)
  case x
  when Hash then x.to_h { |k, v| [k, %w[headers queryStringParameters trailers].include?(k) ? norm_kmv(v) : norm(v)] }
  when Array then x.map { |i| norm(i) }
  else x
  end
end

def drop(obj, path)
  segs = path.scan(/[^.\[\]]+/)
  segs[0..-2].each { |s| obj = obj.is_a?(Array) ? obj[s.to_i] : obj[s] }
  obj.delete(segs[-1])
end

def capture(code)
  $captured = nil
  eval(code, Object.new.instance_eval { binding })
  JSON.parse(JSON.generate($captured))
end

entries = JSON.parse(File.read(MANIFEST))
failed = []
entries.each do |e|
  expected = Marshal.load(Marshal.dump(e['expected']))
  e['omitted'].each { |p| drop(expected, p) }
  actual = capture(e['code'])
  failed << { 'name' => e['name'], 'actual' => actual } unless norm(actual) == norm(expected)
end
neg = Marshal.load(Marshal.dump(entries.first))
neg['expected'][neg['key']]['primary'] = false
puts JSON.generate({ 'total' => entries.size, 'failed' => failed, 'negativeControlDetected' => norm(capture(neg['code'])) != norm(neg['expected']) })
`;

interface Verdict { total: number; failed: unknown[]; negativeControlDetected: boolean }

function runVerifier(cmd: string, script: string, clientDir: string, emit: Emit, ext: string): Verdict {
  const manifest = actionFieldCombos.map((c) => {
    const code = emit(c);
    return { name: c.name, key: actionKey(c), code, expected: buildExpectationJson(c.matcher, c.action), omitted: notedPaths(code) };
  });
  const dir = mkdtempSync(join(tmpdir(), 'action-field-codegen-'));
  writeFileSync(join(dir, 'manifest.json'), JSON.stringify(manifest));
  writeFileSync(join(dir, `verify.${ext}`), script);
  const out = execFileSync(cmd, [join(dir, `verify.${ext}`), clientDir, join(dir, 'manifest.json')], { encoding: 'utf8' });
  return JSON.parse(out.trim().split('\n').pop() as string) as Verdict;
}

describe('all-fields snippets rebuild the expectation through the real client models', () => {
  const canPython = available('python3') && existsSync(join(PYTHON_CLIENT, 'mockserver', '__init__.py'));
  const canRuby = available('ruby') && existsSync(join(RUBY_CLIENT, 'lib', 'mockserver-client.rb'));

  (canPython ? it : it.skip)('python', () => {
    const verdict = runVerifier('python3', PY_VERIFIER, PYTHON_CLIENT, EMITTERS['python']!, 'py');
    expect(verdict.total).toBe(actionFieldCombos.length);
    expect(verdict.failed).toEqual([]);
    expect(verdict.negativeControlDetected).toBe(true);
  });

  (canRuby ? it : it.skip)('ruby', () => {
    const verdict = runVerifier('ruby', RB_VERIFIER, RUBY_CLIENT, EMITTERS['ruby']!, 'rb');
    expect(verdict.total).toBe(actionFieldCombos.length);
    expect(verdict.failed).toEqual([]);
    expect(verdict.negativeControlDetected).toBe(true);
  });
});
