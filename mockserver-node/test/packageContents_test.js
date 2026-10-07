/*
 * The published mockserver-node tarball holds every file its package.json, its typings and its own
 * modules point at. See packageContents.js for the checks.
 */
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const packageContents = require('./packageContents');

const PACKAGE_ROOT = path.join(__dirname, '..');

packageContents.registerTests(PACKAGE_ROOT, {
  // the internals downloadBinary.js exposes to its own tests
  acceptedUndeclaredExports: { 'downloadBinary.d.ts': ['_internal'] },
  // run by npm as a command and loaded by Grunt from its file, neither through the exports map
  acceptedUnexported: ['bin/mockserver.js', 'tasks/mockServer.js']
});

test('the Grunt task that grunt.loadNpmTasks(\'mockserver-node\') loads is in the published tarball', function () {
  assert.ok(
    packageContents.packedFiles(PACKAGE_ROOT).has('tasks/mockServer.js'),
    'tasks/mockServer.js is not selected by the "files" list');
});

test('the typings reader reports what it cannot read, and does not count a default export as compared', function () {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'mockserver-node-typings-'));
  const describe = function (name, lines) {
    fs.writeFileSync(path.join(directory, name), lines.join('\n') + '\n');
    return packageContents.describeTypings(path.join(directory, name));
  };

  try {
    describe('types.d.ts', [
      'export interface Shape { side: number }',
      'export declare const enum Erased { One }',
      'export declare function build(): Shape;',
      'export declare namespace hidden { function unreachable(): void; }']);
    const listed = describe('listed.d.ts', [
      'import { Shape } from \'./types\';',
      'declare const local: number;',
      'export { Shape, local, build as make, missing } from \'./types\';',
      'export { Shape as Imported, local as renamed };']);
    const followed = describe('followed.d.ts', [
      'export * from \'./types\';',
      'export declare function real(): void;']);
    const assigned = describe('assigned.d.ts', [
      'declare namespace assigned {',
      '  interface Options { name: string }',
      '  type Loaded =',
      '    import(\'./types\').Shape;',
      '  function first(options: { name: string }): void;',
      '  namespace inner { const value: number; }',
      '}',
      '/** a second block of the same namespace */',
      'declare namespace assigned {',
      '  const second: number',
      '  export { first as alias };',
      '}',
      'export = assigned;']);
    const unbalanced = describe('unbalanced.d.ts', [
      'export declare function before(): void;',
      'export type Brace = `}`;',
      'export declare function after(): void;']);
    const onlyDefault = describe('onlyDefault.d.ts', [
      'declare const settings: { verbose: boolean };',
      'export default settings;']);

    assert.deepStrictEqual(Array.from(followed.kinds),
      [['real', 'value'], ['Shape', 'type'], ['Erased', 'type'], ['build', 'value']]);
    // a name that is not declared in the file named, or only imported into this one, is not traced
    assert.deepStrictEqual(Array.from(listed.kinds), [['Shape', 'type'], ['local', 'unknown'],
      ['make', 'value'], ['missing', 'unknown'], ['Imported', 'unknown'], ['renamed', 'value']]);
    assert.deepStrictEqual(followed.unread, ['./types: export declare namespace hidden {}']);
    assert.deepStrictEqual(Array.from(assigned.kinds), [['Options', 'type'], ['Loaded', 'type'], ['first', 'value'], ['second', 'value']]);
    assert.deepStrictEqual(assigned.unread,
      ['in namespace assigned: namespace inner {}', 'in namespace assigned: export {}']);
    assert.deepStrictEqual(unbalanced.unread, ['export in a file or namespace whose braces or brackets do not balance']);
    assert.strictEqual(onlyDefault.kinds.get('default'), 'value');
    assert.deepStrictEqual(packageContents.declaredValues(onlyDefault), []);
    assert.deepStrictEqual(packageContents.declaredValues(assigned), ['first', 'second']);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('an object is compared with every member its interface declares, in each form read', function () {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'mockserver-node-interface-'));
  const file = path.join(directory, 'client.d.ts');
  fs.writeFileSync(file, [
    'export interface Client {',
    '  /** a comment; with { braces } */',
    '  call(path: string): Promise<{ status: number; body?: string }>;',
    '  call(path: string, retries: number): Promise<void>;',
    '  readonly settings: { verbose: boolean }',
    '  maybe?(): void',
    '  \'quoted-name\': string;',
    '  get size(): number;',
    '  configure(',
    '    handler?: ((request: object) => object) | null,',
    '    timeout?: number',
    '  ): Client;',
    '  [Symbol.dispose]?(): void;',
    '  [Symbol.iterator](): Iterator<string>;',
    '}',
    'export interface Client { later(): void; }',
    'export interface Builder extends Client {',
    '  [key: string]: unknown;',
    '  (call: string): void;',
    '  new (seed: number): Builder;',
    '}'].join('\n') + '\n');

  class Built {
    constructor() {
      this._state = 1;
      this.settings = {};
      this['quoted-name'] = '';
      this.extra = 2;
    }
    call() {}
    configure() {}
    get size() { return 0; }
    later() {}
    [Symbol.iterator]() {}
  }
  // a well-known symbol is named by identity: its description is `nodejs.dispose` on some Node versions
  if (typeof Symbol.dispose === 'symbol') {
    Built.prototype[Symbol.dispose] = function () {};
  }
  Built.prototype[Symbol('custom')] = function () {};

  try {
    const client = packageContents.describeInterface(file, 'Client');
    assert.deepStrictEqual(Array.from(client.members), [['call', false], ['settings', false], ['maybe', true],
      ['quoted-name', false], ['size', false], ['configure', false], ['[Symbol.dispose]', true],
      ['[Symbol.iterator]', false], ['later', false]]);
    assert.deepStrictEqual(client.unread, []);
    assert.deepStrictEqual(packageContents.describeInterface(file, 'Builder').unread,
      ['interface Builder extends Client', '[key: string]: unknown', '(call: string): void', 'new (seed: number): Builder']);
    assert.strictEqual(packageContents.describeInterface(file, 'Missing').found, false);

    assert.deepStrictEqual(packageContents.compareWithInterface(file, 'Client', new Built()),
      { problems: [], undeclared: ['[unnamed symbol custom]', 'extra'] });
    const partial = { call: function () {}, configure: function () {} };
    assert.deepStrictEqual(packageContents.compareWithInterface(file, 'Client', partial).problems, [
      'client.d.ts Client declares settings, which the object does not have',
      'client.d.ts Client declares quoted-name, which the object does not have',
      'client.d.ts Client declares size, which the object does not have',
      'client.d.ts Client declares [Symbol.iterator], which the object does not have',
      'client.d.ts Client declares later, which the object does not have']);
    assert.deepStrictEqual(packageContents.compareWithInterface(file, 'Missing', {}).problems,
      ['client.d.ts declares no interface Missing']);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
