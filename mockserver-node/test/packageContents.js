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
      declared.push({ field: field, path: node });
    } else if (node && typeof node === 'object') {
      Object.keys(node).forEach(function (key) {
        walk(node[key], field + '[' + JSON.stringify(key) + ']');
      });
    }
  })(manifest.exports, 'exports');
  return declared;
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

function eachMatch(pattern, text, visit) {
  let match;
  while ((match = pattern.exec(text)) !== null) {
    visit(match);
  }
}

/**
 * What a .d.ts exports, read from its text, not compiled. `kinds` maps each exported name,
 * 'default' included, to 'value', 'type' or 'unknown', following `export ... from` into the file
 * it names; with `export = name` the names are the functions and values of `declare namespace
 * name`. `unread` lists the export statements that are in none of the forms read here.
 * Members of interfaces and of object types are not read.
 */
function describeTypings(file, visiting) {
  const source = fs.readFileSync(file, 'utf8');
  const kinds = new Map();
  const local = new Map();
  const unread = [];
  const inProgress = (visiting || []).concat(file);

  function note(names, name, kind) {
    // a name declared as both a type and a value: the value is what has to exist at run time
    if (names.get(name) !== 'value') {
      names.set(name, kind);
    }
  }

  function exportsOf(specifier) {
    const base = path.resolve(path.dirname(file), specifier);
    const target = [base + '.d.ts', path.join(base, 'index.d.ts')].find(fs.existsSync);
    if (!specifier.startsWith('.') || !target || inProgress.includes(target)) {
      return undefined;
    }
    return describeTypings(target, inProgress).kinds;
  }

  eachMatch(/^(export\s+)?(?:declare\s+)?(function|const|let|var|class|enum|interface|type)\s+([A-Za-z_$][\w$]*)/gm, source, function (match) {
    const kind = (match[2] === 'interface' || match[2] === 'type') ? 'type' : 'value';
    note(local, match[3], kind);
    if (match[1]) {
      note(kinds, match[3], kind);
    }
  });

  eachMatch(/^export\s+default\s+(.*)$/gm, source, function (match) {
    const named = /^([A-Za-z_$][\w$]*)\s*;?\s*$/.exec(match[1]);
    if (/^(?:abstract\s+)?(?:function|class)\b/.test(match[1])) {
      note(kinds, 'default', 'value');
    } else if (/^interface\b/.test(match[1])) {
      note(kinds, 'default', 'type');
    } else {
      note(kinds, 'default', (named && local.get(named[1])) || 'unknown');
    }
  });

  eachMatch(/^export\s+(type\s+)?\{([^}]*)\}(?:\s*from\s*(['"])([^'"]+)\3)?/gm, source, function (match) {
    const origin = match[4] ? exportsOf(match[4]) : local;
    match[2].split(',').map(function (specifier) {
      return specifier.trim();
    }).filter(Boolean).forEach(function (specifier) {
      const parts = /^(type\s+)?([A-Za-z_$][\w$]*)(?:\s+as\s+([A-Za-z_$][\w$]*))?$/.exec(specifier);
      if (!parts) {
        unread.push('export { ' + specifier + ' }');
      } else if (match[1] || parts[1]) {
        note(kinds, parts[3] || parts[2], 'type');
      } else {
        note(kinds, parts[3] || parts[2], (origin && origin.get(parts[2])) || 'unknown');
      }
    });
  });

  eachMatch(/^export\s*\*\s*from\s*(['"])([^'"]+)\1/gm, source, function (match) {
    const origin = exportsOf(match[2]);
    if (!origin) {
      unread.push(match[0]);
      return;
    }
    origin.forEach(function (kind, name) {
      if (name !== 'default') {
        note(kinds, name, kind);
      }
    });
  });

  const assigned = /^export\s*=\s*([A-Za-z_$][\w$]*)/m.exec(source);
  if (assigned) {
    const opening = new RegExp('^declare\\s+namespace\\s+' + assigned[1].replace(/\$/g, '\\$') + '\\s*\\{', 'm').exec(source);
    if (opening) {
      let end = opening.index + opening[0].length;
      for (let depth = 1; depth > 0 && end < source.length; end++) {
        depth += source[end] === '{' ? 1 : (source[end] === '}' ? -1 : 0);
      }
      eachMatch(/^\s*(?:export\s+)?(?:declare\s+)?(?:function|const|let|var|class|enum)\s+([A-Za-z_$][\w$]*)/gm,
        source.slice(opening.index + opening[0].length, end), function (match) {
          note(kinds, match[1], 'value');
        });
    }
  }

  const readForms = /^export\s+(?:(?:declare\s+)?(?:function|const|let|var|class|enum|interface|type)\s+[A-Za-z_$]|default\s|(?:type\s+)?\{|\*\s*from\b)|^export\s*=/;
  eachMatch(/^export\b.*$/gm, source, function (match) {
    if (!readForms.test(match[0])) {
      unread.push(match[0]);
    }
  });

  return { kinds: kinds, unread: unread };
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
 * Registers the checks for the package at `packageRoot` with node:test. Two options name the
 * .d.ts files a check is known not to hold for; each list must match what is found exactly, so an
 * entry that no longer applies fails as a new mismatch does.
 * `acceptedDefaultExports`: files declaring a default export their module does not have.
 * `acceptedUncheckedTypings`: files in which no exported function or value was found to compare.
 */
function registerTests(packageRoot, options) {
  const acceptedDefaultExports = (options && options.acceptedDefaultExports) || [];
  const acceptedUncheckedTypings = (options && options.acceptedUncheckedTypings) || [];
  const manifest = JSON.parse(fs.readFileSync(path.join(packageRoot, 'package.json'), 'utf8'));

  test('every path package.json declares is in the published tarball', function () {
    const files = packedFiles(packageRoot);
    const missing = declaredPaths(manifest).filter(function (declared) {
      return !isPacked(files, declared.path, SCRIPT_SUFFIXES);
    }).map(function (declared) {
      return declared.field + ': ' + declared.path;
    });

    assert.deepStrictEqual(missing, [], 'declared in package.json but not selected by its "files" list');
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
      let values = 0;
      typed.declared.unread.forEach(function (statement) {
        problems.push(typed.typings + ': an export in a form this check does not read: ' + statement);
      });
      typed.declared.kinds.forEach(function (kind, name) {
        if (kind === 'unknown') {
          problems.push(typed.typings + ' exports ' + name + ', which could not be traced to a declaration');
        } else if (kind === 'value') {
          values++;
          // whether a declared default export exists is the next test's subject
          if (name !== 'default' && !(name in typed.exported)) {
            problems.push(typed.typings + ' declares ' + name + ', which ' + typed.script + ' does not export');
          }
        }
      });
      if (values === 0) {
        unchecked.push(typed.typings);
      }
    });

    assert.deepStrictEqual(problems, []);
    assert.deepStrictEqual(unchecked.sort(), acceptedUncheckedTypings.slice().sort(),
      'the .d.ts files in which no exported function or value was found to compare (expected: the accepted ones)');
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
  packedFiles: packedFiles,
  registerTests: registerTests
};
