/*
 * Checks on what `npm publish` would ship for a package, shared by this package's suite and
 * mockserver-client-node's. The tarball is whatever the `files` list in package.json selects, so a
 * file can exist in the source tree, pass every other test and still not be published. The
 * published typings are also compared with what the modules beside them export at run time.
 *
 * No network: `npm pack --dry-run` only lists what would be packed.
 */
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const childProcess = require('child_process');
const fs = require('fs');
const path = require('path');

const NPM_TIMEOUT_MILLIS = 120000;
const SCRIPT_SUFFIXES = ['', '.js', '.json', '/index.js'];
const TYPINGS_SUFFIXES = ['.d.ts', '/index.d.ts', ''];
const packedByRoot = new Map();

function npmCommand() {
  // under `npm test` reuse the npm that is running, otherwise the one on the PATH
  const running = process.env.npm_execpath;
  if (running && /npm-cli\.js$/.test(running)) {
    return { file: process.execPath, args: [running], shell: false };
  }
  return { file: 'npm', args: [], shell: process.platform === 'win32' };
}

function listPackedFiles(packageRoot) {
  const npm = npmCommand();
  const result = childProcess.spawnSync(
    npm.file,
    npm.args.concat(['pack', '--dry-run', '--json', '--ignore-scripts', '--offline']),
    {
      cwd: packageRoot,
      encoding: 'utf8',
      timeout: NPM_TIMEOUT_MILLIS,
      maxBuffer: 16 * 1024 * 1024,
      shell: npm.shell,
      env: Object.assign({}, process.env, { npm_config_update_notifier: 'false' })
    });
  assert.ifError(result.error);
  assert.strictEqual(result.status, 0, 'npm pack --dry-run failed: ' + result.stderr);

  let report;
  try {
    report = JSON.parse(result.stdout);
  } catch (error) {
    assert.fail('npm pack --dry-run --json did not print JSON (' + error.message + '): ' + result.stdout);
  }
  const entries = Array.isArray(report) ? report : [report];
  assert.strictEqual(entries.length, 1, 'expected one packed package in: ' + result.stdout);
  assert.ok(Array.isArray(entries[0].files) && entries[0].files.length > 0, 'no file list in: ' + result.stdout);
  return new Set(entries[0].files.map(function (file) {
    return path.posix.normalize(String(file.path).replace(/\\/g, '/'));
  }));
}

/**
 * The paths `npm pack` would put in the tarball, relative to the package root, '/' separated.
 * npm runs once for a package: a failure is kept and thrown again, so the time bound is not
 * paid by every test.
 */
function packedFiles(packageRoot) {
  if (!packedByRoot.has(packageRoot)) {
    let outcome;
    try {
      outcome = { files: listPackedFiles(packageRoot) };
    } catch (error) {
      outcome = { error: error };
    }
    packedByRoot.set(packageRoot, outcome);
  }
  const outcome = packedByRoot.get(packageRoot);
  if (outcome.error) {
    throw outcome.error;
  }
  return outcome.files;
}

/** Every file package.json names as an entry point, with the field that names it. */
function declaredPaths(manifest) {
  const declared = [{ field: 'main', path: manifest.main || 'index.js' }];
  ['module', 'browser', 'types', 'typings'].forEach(function (field) {
    if (typeof manifest[field] === 'string') {
      declared.push({ field: field, path: manifest[field] });
    }
  });
  const bin = typeof manifest.bin === 'string' ? { [manifest.name]: manifest.bin } : (manifest.bin || {});
  Object.keys(bin).forEach(function (name) {
    declared.push({ field: 'bin.' + name, path: bin[name] });
  });
  (function walk(node, field) {
    if (typeof node === 'string') {
      // Node completes no extension of an exports target
      declared.push({ field: field, path: node, exact: true });
    } else if (node && typeof node === 'object') {
      Object.keys(node).forEach(function (key) {
        walk(node[key], field + '[' + JSON.stringify(key) + ']');
      });
    }
  })(manifest.exports, 'exports');
  return declared;
}

/**
 * The exports map that names each published module as `./name` and `./name.js` (and index.js as
 * `.` too), with the typings beside it as `types`, plus `./package.json`. A .d.ts with no module
 * beside it is named with `types` alone. `unexported` lists published modules left out.
 */
function expectedExports(files, unexported) {
  const expected = {};
  Array.from(files).filter(function (file) {
    return file.endsWith('.js') ? !unexported.includes(file)
      : file.endsWith('.d.ts') && !files.has(file.replace(/\.d\.ts$/, '.js'));
  }).sort().forEach(function (file) {
    const base = file.replace(/(?:\.d\.ts|\.js)$/, '');
    const conditions = {};
    if (files.has(base + '.d.ts')) {
      conditions.types = './' + base + '.d.ts';
    }
    if (files.has(base + '.js')) {
      ['require', 'import', 'default'].forEach(function (condition) {
        conditions[condition] = './' + base + '.js';
      });
    }
    (base === 'index' ? ['.'] : []).concat(['./' + base, './' + base + '.js']).forEach(function (subpath) {
      expected[subpath] = conditions;
    });
  });
  expected['./package.json'] = './package.json';
  return expected;
}

/** True when `target` is in `files` as written, or as Node or TypeScript would complete it. */
function isPacked(files, target, suffixes) {
  const normalised = path.posix.normalize(target);
  if (normalised.includes('*')) {
    const pattern = new RegExp('^' + normalised.split('*').map(function (part) {
      return part.replace(/[.+?^${}()|[\]\\]/g, '\\$&');
    }).join('.+') + '$');
    return Array.from(files).some(function (file) {
      return pattern.test(file);
    });
  }
  return suffixes.some(function (suffix) {
    return files.has(normalised + suffix);
  });
}

const NAME = '([A-Za-z_$][\\w$]*)';
const DECLARATION = new RegExp('^(export\\s+)?(?:declare\\s+)?(?:abstract\\s+)?(function|const\\s+enum|const|let|var|class|enum|interface|type)\\s+' + NAME);
const UNBALANCED = 'export in a file or namespace whose braces or brackets do not balance';
// `import(` opens a type, not a statement
const STATEMENT_START = /^[ \t]*(?:export|declare|import(?!\s*\()|function|const|let|var|class|enum|interface|type|namespace|module|abstract)\b/;
// in an interface a member may end at a line break, before a line that starts another member
const MEMBER_START = /^[ \t]*(?:[A-Za-z_$][\w$]*\s*\??\s*[(:<]|(?:readonly|get|set|new)\b|[[('"])/;
const MEMBER = /^(?!new\s*[(<])(?:readonly\s+|[gs]et\s+(?=[A-Za-z_$]))?([A-Za-z_$][\w$]*|\[Symbol\.[A-Za-z_$][\w$]*\]|'[^']*'|"[^"]*")\s*(\?)?\s*[(:<]/;

/**
 * The statements of `text` that are outside any braces. `head` is the statement with comments and
 * the contents of its braces removed, `block` the contents of its first braces. A statement ends
 * at a semicolon, or at a line break before a line that `start` matches: by default one that
 * starts a declaration, import or export.
 */
function statementsOf(text, start) {
  // comments are blanked, and so is punctuation in string literals, so that it can be counted
  const source = text.replace(/'(?:[^'\\\n]|\\.)*'|"(?:[^"\\\n]|\\.)*"|\/\*[\s\S]*?\*\/|\/\/.*$/gm, function (found) {
    return found.replace(found[0] === '/' ? /[^\n]/g : /[{}()[\];]/g, ' ');
  });
  const statements = [];
  let head = '';
  let block;
  let braces = 0;
  let brackets = 0;
  let opened = 0;

  function end() {
    if (head.trim()) {
      statements.push({ head: head.trim(), block: block || '' });
    }
    head = '';
    block = undefined;
  }

  for (let i = 0; i < source.length; i++) {
    const character = source[i];
    if (character === '{') {
      if (braces++ === 0) {
        opened = i + 1;
        head += '{';
      }
    } else if (character === '}') {
      if (--braces === 0) {
        block = block === undefined ? source.slice(opened, i) : block;
        head += '}';
      }
    } else if (braces === 0) {
      brackets += '(['.includes(character) ? 1 : (')]'.includes(character) ? -1 : 0);
      if (character === ';' || (character === '\n' && brackets === 0 && (start || STATEMENT_START).test(source.slice(i + 1, i + 80)))) {
        end();
      } else {
        head += character;
      }
    }
  }
  end();
  if (braces !== 0 || brackets !== 0) {
    // what follows the imbalance was not split into statements: reported as an unread export
    statements.push({ head: UNBALANCED, block: '' });
  }
  return statements;
}

/**
 * What a .d.ts exports, read from its text, not compiled. `kinds` maps each exported name,
 * 'default' included, to 'value', 'type' or 'unknown', following `export ... from` into the file
 * named; with `export = name` the names are the members of every `declare namespace name` block. `unread` lists the export statements and namespace
 * members that are in none of the forms read here, and those of a file reached by `export *`.
 * Not read: members of interfaces and of object types, and any declarator after the first in
 * `const a: A, b: B`. A `const enum` is a type: it leaves nothing behind at run time.
 */
function describeTypings(file, visiting) {
  const kinds = new Map();
  const local = new Map();
  const namespaces = new Map();
  const unread = new Set();
  const followed = new Map();
  const inProgress = (visiting || []).concat(file);

  function note(names, name, kind) {
    // a name declared as both a type and a value: the value is what has to exist at run time
    if (names.get(name) !== 'value') {
      names.set(name, kind);
    }
  }

  function follow(specifier) {
    if (!followed.has(specifier)) {
      const base = path.resolve(path.dirname(file), specifier);
      const target = [base + '.d.ts', path.join(base, 'index.d.ts')].find(fs.existsSync);
      const known = specifier.startsWith('.') && target && !inProgress.includes(target);
      followed.set(specifier, known ? describeTypings(target, inProgress) : undefined);
    }
    return followed.get(specifier);
  }


  function readDeclaration(statement, declared, exported) {
    const declaration = DECLARATION.exec(statement.head);
    if (declaration) {
      const kind = /^(?:interface|type|const\s+enum)$/.test(declaration[2]) ? 'type' : 'value';
      note(declared, declaration[3], kind);
      if (declaration[1]) {
        note(exported, declaration[3], kind);
      }
    }
    return Boolean(declaration);
  }

  const statements = statementsOf(fs.readFileSync(file, 'utf8'));

  // declarations first: an export list or a default export may name one that is declared after it
  const others = statements.filter(function (statement) {
    const opening = new RegExp('^(?:declare\\s+)?namespace\\s+' + NAME + '\\s*\\{\\}$').exec(statement.head);
    if (opening) {
      namespaces.set(opening[1], (namespaces.get(opening[1]) || []).concat(statement.block));
    }
    return !opening && !readDeclaration(statement, local, kinds);
  });

  others.forEach(function (statement) {
    const head = statement.head;
    const byDefault = /^export\s+default\s+([\s\S]*)$/.exec(head);
    const list = /^export\s+(type\s+)?\{\}(?:\s*from\s*(['"])([^'"]+)\2)?$/.exec(head);
    const star = /^export\s*\*\s*from\s*(['"])([^'"]+)\1$/.exec(head);
    const assignment = new RegExp('^export\\s*=\\s*' + NAME + '$').exec(head);

    if (byDefault) {
      const named = new RegExp('^' + NAME + '$').exec(byDefault[1]);
      if (/^(?:abstract\s+)?(?:function|class)\b/.test(byDefault[1])) {
        note(kinds, 'default', 'value');
      } else if (/^interface\b/.test(byDefault[1])) {
        note(kinds, 'default', 'type');
      } else {
        note(kinds, 'default', (named && local.get(named[1])) || 'unknown');
      }
    } else if (list) {
      const origin = list[1] ? undefined : (list[3] ? (follow(list[3]) || {}).kinds : local);
      statement.block.split(',').map(function (specifier) {
        return specifier.trim();
      }).filter(Boolean).forEach(function (specifier) {
        const parts = new RegExp('^(type\\s+)?' + NAME + '(?:\\s+as\\s+' + NAME + ')?$').exec(specifier);
        if (!parts) {
          unread.add('export { ' + specifier + ' }');
        } else if (list[1] || parts[1]) {
          note(kinds, parts[3] || parts[2], 'type');
        } else {
          note(kinds, parts[3] || parts[2], (origin && origin.get(parts[2])) || 'unknown');
        }
      });
    } else if (star) {
      const origin = follow(star[2]);
      if (!origin) {
        unread.add(head);
        return;
      }
      // every export of that file comes through, so what is unread in it is unread here
      origin.unread.forEach(function (statement) {
        unread.add(star[2] + ': ' + statement);
      });
      origin.kinds.forEach(function (kind, name) {
        if (name !== 'default') {
          note(kinds, name, kind);
        }
      });
    } else if (assignment) {
      const members = new Map();
      (namespaces.get(assignment[1]) || []).forEach(function (block) {
        statementsOf(block).forEach(function (member) {
          // in a `declare namespace` every declaration is exported, with or without `export`
          if (!readDeclaration(member, members, members)) {
            unread.add('in namespace ' + assignment[1] + ': ' + member.head);
          }
        });
      });
      members.forEach(function (kind, name) {
        note(kinds, name, kind);
      });
    } else if (/^export\b/.test(head)) {
      unread.add(head);
    }
  });

  return { kinds: kinds, unread: Array.from(unread) };
}

/**
 * The names a described .d.ts exports as functions or values, each of which the module beside it
 * must export. A default export is left out: comparing it compares none of its members.
 */
function declaredValues(description) {
  return Array.from(description.kinds.keys()).filter(function (name) {
    return description.kinds.get(name) === 'value' && name !== 'default';
  });
}

/**
 * The members of every `interface name` block in a .d.ts. `members` maps each name to whether it
 * is optional; a member keyed by a well-known symbol is named `[Symbol.x]`. `unread` lists the
 * members in no form read here (index, call and construct signatures) and an `extends` clause,
 * whose members are not read. Members separated by commas are not split.
 */
function describeInterface(file, name) {
  const members = new Map();
  const unread = [];
  let found = false;

  statementsOf(fs.readFileSync(file, 'utf8')).forEach(function (statement) {
    const opening = new RegExp('^(?:export\\s+)?(?:declare\\s+)?interface\\s+' + name + '\\b([^{]*)\\{\\}$').exec(statement.head);
    if (!opening) {
      return;
    }
    found = true;
    if (/\bextends\b/.test(opening[1])) {
      unread.push('interface ' + name + opening[1].replace(/\s+/g, ' ').trimEnd());
    }
    statementsOf(statement.block, MEMBER_START).forEach(function (member) {
      const parts = MEMBER.exec(member.head);
      if (!parts) {
        unread.push(member.head);
        return;
      }
      const key = parts[1].replace(/^(['"])([\s\S]*)\1$/, '$2');
      // an overload makes a member required unless every declaration of it is optional
      members.set(key, Boolean(parts[2]) && members.get(key) !== false);
    });
  });

  return { found: found, members: members, unread: unread };
}

/**
 * How an interface names a symbol key: a well-known symbol by identity, as `[Symbol.x]`, because
 * its description differs between Node versions (`nodejs.dispose` on Node 22 for Symbol.dispose).
 */
function symbolName(key) {
  const wellKnown = Object.getOwnPropertyNames(Symbol).find(function (property) {
    return Symbol[property] === key;
  });
  return wellKnown ? '[Symbol.' + wellKnown + ']' : '[unnamed symbol ' + String(key.description) + ']';
}

/** The names a caller reaches on an object, inherited ones included; a symbol is named by symbolName. */
function memberNames(object) {
  const names = new Set();
  for (let level = object; level && level !== Object.prototype && level !== Function.prototype; level = Object.getPrototypeOf(level)) {
    for (const key of Reflect.ownKeys(level)) {
      if (key !== 'constructor') {
        names.add(typeof key === 'symbol' ? symbolName(key) : key);
      }
    }
  }
  return names;
}

/**
 * Compares `object` with `interface name` of the .d.ts `file`. `problems` lists what the
 * interface requires and the object lacks, and what of the interface was not read; `undeclared`
 * the names the object has that the interface does not declare, except those starting with `_`,
 * which are taken to be private state.
 */
function compareWithInterface(file, name, object) {
  const label = path.basename(file) + ' ' + name;
  const declared = describeInterface(file, name);
  const names = memberNames(object);
  const problems = [];

  if (!declared.found) {
    problems.push(path.basename(file) + ' declares no interface ' + name);
  }
  declared.unread.forEach(function (member) {
    problems.push(label + ': a member in a form this check does not read: ' + member);
  });
  declared.members.forEach(function (optional, member) {
    if (!optional && !names.has(member)) {
      problems.push(label + ' declares ' + member + ', which the object does not have');
    }
  });
  return {
    problems: problems,
    undeclared: Array.from(names).filter(function (member) {
      return !member.startsWith('_') && !declared.members.has(member);
    }).sort()
  };
}

/** Each published .d.ts whose module is beside it in the tarball, with what that module exports. */
function typedModules(packageRoot) {
  const files = packedFiles(packageRoot);
  return Array.from(files).filter(function (typings) {
    return typings.endsWith('.d.ts') && files.has(typings.replace(/\.d\.ts$/, '.js'));
  }).map(function (typings) {
    const script = typings.replace(/\.d\.ts$/, '.js');
    return {
      typings: typings,
      script: script,
      declared: describeTypings(path.join(packageRoot, typings)),
      exported: Object(require(path.join(packageRoot, script)))
    };
  });
}

/**
 * Registers the checks for the package at `packageRoot` with node:test. Three options name what a
 * check is known not to hold for; each must match what is found exactly, so an entry that no
 * longer applies fails as a new mismatch does.
 * `acceptedDefaultExports`: files declaring a default export their module does not have.
 * `acceptedUncheckedTypings`: files in which no exported function or value was found to compare.
 * `acceptedUndeclaredExports`: by .d.ts file, the names its module exports and it does not declare.
 * `acceptedUnexported`: published modules the exports map does not name.
 * `builtObjects` lists objects the package builds, each compared with the interface that types
 * it: `{ typings, name, build, acceptedUndeclared }`, where `build()` returns the object and
 * `acceptedUndeclared` lists the names it has that the interface does not declare.
 */
function registerTests(packageRoot, options) {
  const acceptedDefaultExports = (options && options.acceptedDefaultExports) || [];
  const acceptedUncheckedTypings = (options && options.acceptedUncheckedTypings) || [];
  const acceptedUndeclaredExports = (options && options.acceptedUndeclaredExports) || {};
  const builtObjects = (options && options.builtObjects) || [];
  const acceptedUnexported = (options && options.acceptedUnexported) || [];
  const manifest = JSON.parse(fs.readFileSync(path.join(packageRoot, 'package.json'), 'utf8'));

  test('every path package.json declares is in the published tarball', function () {
    const files = packedFiles(packageRoot);
    const missing = declaredPaths(manifest).filter(function (declared) {
      return declared.exact ? !declared.path.startsWith('./') || !isPacked(files, declared.path.slice(2), [''])
        : !isPacked(files, declared.path, SCRIPT_SUFFIXES);
    }).map(function (declared) {
      return declared.field + ': ' + declared.path;
    });

    assert.deepStrictEqual(missing, [], 'declared in package.json but not selected by its "files" list');
  });

  test('the exports map names every published module with and without its extension, and package.json', function () {
    // a deep import the map does not name fails, so the map must follow the "files" list
    const files = packedFiles(packageRoot);
    assert.deepStrictEqual(acceptedUnexported.filter(function (file) {
      return !files.has(file);
    }), [], 'accepted as unexported but not published');
    const expected = expectedExports(files, acceptedUnexported);
    assert.deepStrictEqual(manifest.exports, expected);
    const misordered = Object.keys(expected).filter(function (subpath) {
      return Object.keys(Object(manifest.exports[subpath])).join() !== Object.keys(Object(expected[subpath])).join();
    });
    assert.deepStrictEqual(misordered, [], 'conditions are matched in order: types first, default last');
  });

  test('the typings beside a published module are in the published tarball', function () {
    const files = packedFiles(packageRoot);
    const missing = Array.from(files).map(function (file) {
      return file.replace(/\.js$/, '.d.ts');
    }).filter(function (typings) {
      return typings.endsWith('.d.ts') && fs.existsSync(path.join(packageRoot, typings)) && !files.has(typings);
    });

    assert.deepStrictEqual(missing, [], 'beside a published module but not selected by the "files" list');
  });

  test('every relative require or import in a published file resolves inside the tarball', function () {
    const files = packedFiles(packageRoot);
    const reference = /(?:\brequire\s*\(|\bfrom\s|\bimport\s*\(?)\s*(['"])(\.{1,2}\/[^'"\n]*)\1/g;
    const missing = [];

    files.forEach(function (file) {
      if (!/\.(?:[cm]?js|d\.ts)$/.test(file)) {
        return;
      }
      const suffixes = file.endsWith('.d.ts') ? TYPINGS_SUFFIXES : SCRIPT_SUFFIXES;
      const source = fs.readFileSync(path.join(packageRoot, file), 'utf8');
      let match;
      while ((match = reference.exec(source)) !== null) {
        if (!isPacked(files, path.posix.join(path.posix.dirname(file), match[2]), suffixes)) {
          missing.push(file + ' -> ' + match[2]);
        }
      }
    });

    assert.deepStrictEqual(missing, [], 'loaded by a published file but not selected by the "files" list');
  });

  test('every function or value a published .d.ts declares is exported by the module beside it', function () {
    const problems = [];
    const unchecked = [];

    typedModules(packageRoot).forEach(function (typed) {
      const values = declaredValues(typed.declared);
      typed.declared.unread.forEach(function (statement) {
        problems.push(typed.typings + ': an export or namespace member in a form this check does not read: ' + statement);
      });
      typed.declared.kinds.forEach(function (kind, name) {
        if (kind === 'unknown') {
          problems.push(typed.typings + ' exports ' + name + ', which could not be traced to a declaration');
        }
      });
      values.forEach(function (name) {
        if (!(name in typed.exported)) {
          problems.push(typed.typings + ' declares ' + name + ', which ' + typed.script + ' does not export');
        }
      });
      if (values.length === 0) {
        unchecked.push(typed.typings);
      }
    });

    assert.deepStrictEqual(problems, []);
    assert.deepStrictEqual(unchecked.sort(), acceptedUncheckedTypings.slice().sort(),
      'the .d.ts files in which no exported function or value was found to compare (expected: the accepted ones)');
  });

  test('every name a published module exports is declared as a function or value by the .d.ts beside it', function () {
    const undeclared = {};
    const accepted = {};

    typedModules(packageRoot).forEach(function (typed) {
      const names = Object.keys(typed.exported).filter(function (name) {
        return typed.declared.kinds.get(name) !== 'value';
      }).sort();
      if (names.length > 0) {
        undeclared[typed.typings] = names;
      }
    });
    Object.keys(acceptedUndeclaredExports).forEach(function (typings) {
      accepted[typings] = acceptedUndeclaredExports[typings].slice().sort();
    });

    assert.deepStrictEqual(undeclared, accepted,
      'the names a module exports that the .d.ts beside it does not declare (expected: the accepted ones)');
  });

  test('a published .d.ts with no module beside it declares types alone', function () {
    // nothing is loaded when such a file is imported, so a function or value it declared could not exist
    const files = packedFiles(packageRoot);
    const problems = [];

    Array.from(files).filter(function (typings) {
      return typings.endsWith('.d.ts') && !files.has(typings.replace(/\.d\.ts$/, '.js'));
    }).forEach(function (typings) {
      const declared = describeTypings(path.join(packageRoot, typings));
      declared.unread.forEach(function (statement) {
        problems.push(typings + ': an export or namespace member in a form this check does not read: ' + statement);
      });
      declared.kinds.forEach(function (kind, name) {
        if (kind !== 'type') {
          problems.push(typings + ' declares ' + name + ' as a function or value, or in a way that could not be traced');
        }
      });
    });

    assert.deepStrictEqual(problems, []);
  });

  test('an object the package builds has every member its interface requires, and no other undeclared', function () {
    const problems = [];
    const undeclared = {};
    const accepted = {};

    builtObjects.forEach(function (built) {
      const label = built.typings + ' ' + built.name;
      const compared = compareWithInterface(path.join(packageRoot, built.typings), built.name, built.build());
      Array.prototype.push.apply(problems, compared.problems);
      if (compared.undeclared.length > 0) {
        undeclared[label] = compared.undeclared;
      }
      if ((built.acceptedUndeclared || []).length > 0) {
        accepted[label] = built.acceptedUndeclared.slice().sort();
      }
    });

    assert.deepStrictEqual(problems, []);
    assert.deepStrictEqual(undeclared, accepted,
      'the members an object has that its interface does not declare (expected: the accepted ones)');
  });

  test('a published .d.ts declares a default export only when the module beside it has one', function () {
    // `module.exports = { ... }` has no `default`: typings that declare one compile a default import
    // into a read of `undefined`, and reject the `require` form that works
    const mismatched = typedModules(packageRoot).filter(function (typed) {
      return typed.declared.kinds.get('default') === 'value' && !('default' in typed.exported);
    }).map(function (typed) {
      return typed.typings;
    });

    assert.deepStrictEqual(mismatched.sort(), acceptedDefaultExports.slice().sort(),
      'the .d.ts files declaring a default export their module does not have (expected: the accepted ones)');
  });
}

module.exports = {
  compareWithInterface: compareWithInterface,
  declaredValues: declaredValues,
  describeInterface: describeInterface,
  describeTypings: describeTypings,
  memberNames: memberNames,
  packedFiles: packedFiles,
  registerTests: registerTests
};
