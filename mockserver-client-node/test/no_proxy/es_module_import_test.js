'use strict';

/*
 * An ES module can import each name the package's CommonJS modules export, not only the default.
 * Node reads those names from the module's source without running it, so a module whose exports
 * Node cannot read still loads under require and fails only when imported by name.
 */

var { describe, it, before, after } = require('node:test');
var assert = require('node:assert/strict');
var childProcess = require('child_process');
var fs = require('fs');
var os = require('os');
var path = require('path');
var url = require('url');
var vm = require('vm');

var PACKAGE_ROOT = path.resolve(__dirname, '..', '..');
var manifest = require(path.join(PACKAGE_ROOT, 'package.json'));
var publishedModules = manifest.files.filter(function (file) {
    return file.endsWith('.js');
});

// names Node adds to every CommonJS module's namespace; 'module.exports' since Node 23
function namedExports(namespace) {
    return Object.keys(namespace).filter(function (name) {
        return name !== 'default' && name !== 'module.exports';
    }).sort();
}

describe('ES module import', function () {
    publishedModules.forEach(function (file) {
        it('names every export of ' + file, async function () {
            var required = require(path.join(PACKAGE_ROOT, file));
            var imported = await import(url.pathToFileURL(path.join(PACKAGE_ROOT, file)).href);
            assert.deepEqual(namedExports(imported), Object.keys(required).sort());
            Object.keys(required).forEach(function (name) {
                assert.equal(imported[name], required[name], name + ' is the value require returns');
            });
        });
    });

    describe('of the installed package by name', function () {
        var directory;

        before(function () {
            directory = fs.mkdtempSync(path.join(os.tmpdir(), 'mockserver-client-esm-'));
            fs.mkdirSync(path.join(directory, 'node_modules'));
            fs.symlinkSync(PACKAGE_ROOT, path.join(directory, 'node_modules', 'mockserver-client'), 'junction');
        });

        after(function () {
            fs.rmSync(directory, {recursive: true, force: true});
        });

        it('runs named imports, a named re-export and the default import', function () {
            fs.writeFileSync(path.join(directory, 'consumer.mjs'), [
                "import mockServer, { mockServerClient, MockMode, setupMockServer, llm, mcpMock, a2aMock } from 'mockserver-client';",
                "import { llmMock, completion, Role } from 'mockserver-client/llm.js';",
                "import * as llmNamespace from 'mockserver-client/llm.js';",
                "export { Completion } from 'mockserver-client/llm.js';",
                "const checks = {",
                "    sameAsDefault: mockServerClient === mockServer.mockServerClient && llm === mockServer.llm,",
                "    builtClient: typeof mockServerClient('localhost', 1080).mockAnyResponse === 'function',",
                "    others: MockMode.SPY === 'SPY' && typeof setupMockServer === 'function' && typeof mcpMock === 'function' && typeof a2aMock === 'function',",
                "    llm: llmMock === llm.llmMock && llmNamespace.completion === completion && Role === llm.Role,",
                "    builtExpectation: llmMock('/v1/messages').respondingWith(completion().withText('hi')).build().httpRequest.path === '/v1/messages'",
                "};",
                "console.log(JSON.stringify(checks));"
            ].join('\n'));

            var result = childProcess.spawnSync(process.execPath, ['consumer.mjs'], {cwd: directory, encoding: 'utf8'});

            assert.equal(result.status, 0, result.stderr);
            assert.deepEqual(JSON.parse(result.stdout), {
                sameAsDefault: true,
                builtClient: true,
                others: true,
                llm: true,
                builtExpectation: true
            });
        });
    });

    it('leaves llm.js exporting to the global in a browser, where there is no module', function () {
        var browser = {window: {}};
        vm.runInNewContext(fs.readFileSync(path.join(PACKAGE_ROOT, 'llm.js'), 'utf8'), browser);
        assert.deepEqual(Object.keys(browser.window.mockServerLlm).sort(), Object.keys(require('../../llm')).sort());
        assert.equal(typeof browser.window.mockServerLlm.llmMock('/v1').build, 'function');
    });
});
