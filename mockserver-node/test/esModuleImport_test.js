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
var publishedModules = require('../package.json').files.filter(function (file) {
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
      "import { downloadJar } from 'mockserver-node/downloadJar.js';",
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
});
