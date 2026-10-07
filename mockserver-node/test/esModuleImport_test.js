/*
 * An ES module can import each name the launcher's CommonJS modules export, not only the default.
 * Node reads those names from a module's source without running it, so a module whose exports Node
 * cannot read still loads under require and fails only when an ES module imports it by name. Each
 * check runs a real .mjs in a child Node process.
 */
'use strict';

var test = require('node:test');
var assert = require('node:assert');
var childProcess = require('child_process');
var fs = require('fs');
var os = require('os');
var path = require('path');
var url = require('url');

var PACKAGE_ROOT = path.join(__dirname, '..');
var manifest = require('../package.json');
var publishedModules = manifest.files.filter(function (file) {
  return file.endsWith('.js');
});

function runModule(directory, source) {
  fs.writeFileSync(path.join(directory, 'consumer.mjs'), source.join('\n') + '\n');
  var result = childProcess.spawnSync(process.execPath, ['consumer.mjs'], {cwd: directory, encoding: 'utf8'});
  assert.strictEqual(result.status, 0, result.stderr);
  return JSON.parse(result.stdout);
}

test('ES module import', async function (t) {
  var directory = fs.mkdtempSync(path.join(os.tmpdir(), 'mockserver-node-esm-'));
  t.after(function () {
    fs.rmSync(directory, {recursive: true, force: true});
  });
  fs.mkdirSync(path.join(directory, 'node_modules'));
  fs.symlinkSync(PACKAGE_ROOT, path.join(directory, 'node_modules', 'mockserver-node'), 'junction');

  await t.test('names every member require returns, with the same value', function () {
    assert.deepStrictEqual(publishedModules, ['index.js', 'downloadJar.js', 'downloadBinary.js']);
    var compared = runModule(directory, [
      "import { createRequire } from 'node:module';",
      "import { fileURLToPath } from 'node:url';",
      "const require = createRequire(import.meta.url);",
      "const result = {};",
      "for (const file of " + JSON.stringify(publishedModules) + ") {",
      "    const location = " + JSON.stringify(url.pathToFileURL(PACKAGE_ROOT).href + '/') + " + file;",
      "    const imported = await import(location);",
      "    const required = require(fileURLToPath(location));",
      "    result[file] = {",
      "        imported: Object.keys(imported).filter((name) => name !== 'default' && name !== 'module.exports').sort(),",
      "        required: Object.keys(required).sort(),",
      "        different: Object.keys(required).filter((name) => imported[name] !== required[name])",
      "    };",
      "}",
      "console.log(JSON.stringify(result));"
    ]);
    publishedModules.forEach(function (file) {
      assert.ok(compared[file].required.length > 0, file + ' exports nothing under require');
      assert.deepStrictEqual(compared[file].imported, compared[file].required, file + ' names');
      assert.deepStrictEqual(compared[file].different, [], file + ' values');
    });
  });

  await t.test('runs named imports, a named re-export and the default import of the installed package', function () {
    var checks = runModule(directory, [
      "import mockserver, { start_mockserver, stop_mockserver, getMockServerProcess, getMockServerExit, getMockServerOutput } from 'mockserver-node';",
      "import * as launcher from 'mockserver-node';",
      "import { downloadJar } from 'mockserver-node/downloadJar';",
      "import { ensureBinary, resolvePlatform } from 'mockserver-node/downloadBinary.js';",
      "export { runBinary } from 'mockserver-node/downloadBinary.js';",
      "console.log(JSON.stringify({",
      "    sameAsDefault: start_mockserver === mockserver.start_mockserver && stop_mockserver === mockserver.stop_mockserver,",
      "    namespace: launcher.getMockServerOutput === getMockServerOutput && launcher.default === mockserver,",
      "    beforeAnyStart: getMockServerProcess() === undefined && getMockServerExit() === undefined && getMockServerOutput() === '',",
      "    downloads: typeof downloadJar === 'function' && typeof ensureBinary === 'function' && typeof resolvePlatform === 'function'",
      "}));"
    ]);
    assert.deepStrictEqual(checks, {sameAsDefault: true, namespace: true, beforeAnyStart: true, downloads: true});
  });

  await t.test('requires and imports each path of the exports map as the module file itself', function () {
    var files = {};
    Object.keys(manifest.exports).forEach(function (subpath) {
      if (typeof manifest.exports[subpath] === 'object') {
        files[subpath] = path.join(PACKAGE_ROOT, manifest.exports[subpath].require);
      }
    });
    assert.deepStrictEqual(Object.keys(files).sort(),
      ['.', './downloadBinary', './downloadBinary.js', './downloadJar', './downloadJar.js', './index', './index.js']);
    var compared = runModule(directory, [
      "import { createRequire } from 'node:module';",
      "const require = createRequire(import.meta.url);",
      "const result = {};",
      "for (const [subpath, file] of Object.entries(" + JSON.stringify(files) + ")) {",
      "    const name = 'mockserver-node' + subpath.slice(1);",
      "    const imported = await import(name);",
      "    const required = require(file);",
      "    result[subpath] = {",
      "        sameRequired: require(name) === required,",
      "        imported: Object.keys(imported).filter((key) => key !== 'default' && key !== 'module.exports').sort(),",
      "        required: Object.keys(required).sort(),",
      "        different: Object.keys(required).filter((key) => imported[key] !== required[key])",
      "    };",
      "}",
      "console.log(JSON.stringify(result));"
    ]);
    Object.keys(files).forEach(function (subpath) {
      assert.strictEqual(compared[subpath].sameRequired, true, subpath + ' required by name');
      assert.ok(compared[subpath].required.length > 0, subpath + ' exports nothing');
      assert.deepStrictEqual(compared[subpath].imported, compared[subpath].required, subpath + ' names');
      assert.deepStrictEqual(compared[subpath].different, [], subpath + ' values');
    });
  });

  await t.test('reads package.json by name, and loads no path the exports map does not name', function () {
    var checks = runModule(directory, [
      "import { createRequire } from 'node:module';",
      "const require = createRequire(import.meta.url);",
      "const imported = await import('mockserver-node/package.json', { with: { type: 'json' } });",
      "const refused = {};",
      "for (const name of ['mockserver-node/tasks/mockServer.js', 'mockserver-node/test/sendRequest.js', 'mockserver-node/downloadBinary.d.ts']) {",
      "    let required = 'loaded', importedCode = 'loaded';",
      "    try { require(name); } catch (error) { required = error.code; }",
      "    try { await import(name); } catch (error) { importedCode = error.code; }",
      "    refused[name] = [required, importedCode];",
      "}",
      "console.log(JSON.stringify({",
      "    versions: [require('mockserver-node/package.json').version, imported.default.version],",
      "    refused: refused",
      "}));"
    ]);
    var refusal = ['ERR_PACKAGE_PATH_NOT_EXPORTED', 'ERR_PACKAGE_PATH_NOT_EXPORTED'];
    assert.deepStrictEqual(checks, {
      versions: [manifest.version, manifest.version],
      refused: {
        'mockserver-node/tasks/mockServer.js': refusal,
        'mockserver-node/test/sendRequest.js': refusal,
        'mockserver-node/downloadBinary.d.ts': refusal
      }
    });
  });
});
