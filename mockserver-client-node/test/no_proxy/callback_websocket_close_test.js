'use strict';

/*
 * A callback, forward callback or breakpoint opens a WebSocket to MockServer that keeps the process
 * running. close(), reset() and disposing the client close those WebSockets, so a process that
 * registered one exits on its own. Each process check runs a real Node child process against the
 * MockServer the suite runs against, and fails if it has not exited by a deadline.
 */

var { describe, it } = require('node:test');
var assert = require('node:assert/strict');
var childProcess = require('child_process');
var crypto = require('crypto');
var http = require('http');
var path = require('path');
var mockServer = require('../../');
var mockServerClient = mockServer.mockServerClient;

var PACKAGE_ROOT = path.resolve(__dirname, '..', '..');
var mockServerHost = process.env.MOCKSERVER_HOST || "localhost";
var mockServerPort = parseInt(process.env.MOCKSERVER_PORT, 10) || 1080;
// generous: a WebSocket close waits up to 5 seconds for MockServer's answer
var EXIT_DEADLINE_MILLIS = 30000;

/*
 * Runs `body` in a child Node process, where `mockServer` is the package, `client` a client of the
 * suite's MockServer, `clientOverTls` one that reaches it over TLS and `path` a path of its own.
 * Resolves with the exit code and output, or with `running: true` if it is still running after
 * `deadlineMillis` (it is then killed).
 */
function runProcess(body, deadlineMillis) {
    var script = [
        "var mockServer = require(" + JSON.stringify(PACKAGE_ROOT) + ");",
        "var client = mockServer.mockServerClient(" + JSON.stringify(mockServerHost) + ", " + mockServerPort + ");",
        "var clientOverTls = mockServer.mockServerClient(" + JSON.stringify(mockServerHost) + ", " + mockServerPort + ", undefined, true);",
        "var path = " + JSON.stringify('/callback-close-' + crypto.randomUUID()) + ";",
        "function respond() { return {statusCode: 201}; }",
        "function forward(request) { return request; }",
        "(async function () {",
        body,
        "})().catch(function (error) { console.error('FAILED ' + (error && error.stack || error)); process.exit(3); });"
    ].join('\n');
    return new Promise(function (resolve) {
        var child = childProcess.spawn(process.execPath, ['-e', script], {cwd: PACKAGE_ROOT});
        var stdout = '';
        var stderr = '';
        child.stdout.on('data', function (chunk) {
            stdout += chunk;
        });
        child.stderr.on('data', function (chunk) {
            stderr += chunk;
        });
        var timer = setTimeout(function () {
            child.kill('SIGKILL');
        }, deadlineMillis);
        child.on('exit', function (code, signal) {
            clearTimeout(timer);
            resolve({code: code, running: signal === 'SIGKILL', stdout: stdout, stderr: stderr});
        });
    });
}

function assertExited(result) {
    assert.equal(result.running, false, 'still running after ' + EXIT_DEADLINE_MILLIS + 'ms\n' + result.stdout + result.stderr);
    assert.equal(result.code, 0, result.stdout + result.stderr);
    assert.doesNotMatch(result.stderr, /reconnecting/, 'a closed WebSocket reconnected');
}

function sendRequest(requestPath) {
    return new Promise(function (resolve, reject) {
        var req = http.request({host: mockServerHost, port: mockServerPort, method: 'GET', path: requestPath, timeout: 10000}, function (res) {
            res.resume();
            res.on('end', function () {
                resolve(res.statusCode);
            });
        });
        req.on('error', reject);
        req.on('timeout', function () {
            req.destroy(new Error('no answer to ' + requestPath));
        });
        req.end();
    });
}

var REGISTER_EVERY_KIND = [
    "await client.mockWithCallback({path: path}, respond);",
    "await client.mockWithForwardCallback({path: path + '/forward'}, forward);",
    "await clientOverTls.mockWithCallback({path: path + '/tls'}, respond);",
    "await client.addRequestBreakpoint({path: path + '/breakpoint'}, function (request) { return request; });"
].join('\n');

describe('closing callback WebSockets', function () {
    it('leaves a process that registered a callback running until they are closed', async function () {
        var result = await runProcess([
            "await client.mockWithCallback({path: path}, respond);",
            "console.log('registered');"
        ].join('\n'), 3000);
        assert.equal(result.running, true, 'exited (code ' + result.code + ') with its callback WebSocket open\n' + result.stderr);
        assert.match(result.stdout, /registered/);
    });

    it('lets a process exit after close()', async function () {
        assertExited(await runProcess([
            REGISTER_EVERY_KIND,
            "await client.close();",
            "console.log('closed');"
        ].join('\n'), EXIT_DEADLINE_MILLIS));
    });

    it('lets a process exit after reset() from another client of the same MockServer', async function () {
        var result = await runProcess([
            REGISTER_EVERY_KIND,
            "await mockServer.mockServerClient(" + JSON.stringify(mockServerHost) + ", " + mockServerPort + ").reset();",
            // the client's reconnect waits 2 seconds; a reconnect would register the callback again
            "await new Promise(function (resolve) { setTimeout(resolve, 3000); });",
            "console.log('active ' + JSON.stringify(await client.retrieveActiveExpectations(path)));"
        ].join('\n'), EXIT_DEADLINE_MILLIS);
        assertExited(result);
        assert.match(result.stdout, /^active \[\]$/m, 'a callback expectation came back after reset');
    });

    it('lets a process exit after await using disposes the client', async function () {
        assertExited(await runProcess([
            "{",
            "    var disposed = mockServer.mockServerClient(" + JSON.stringify(mockServerHost) + ", " + mockServerPort + ");",
            "    await disposed.mockWithCallback({path: path}, respond);",
            "    await disposed[Symbol.asyncDispose]();",
            "}",
            "console.log('disposed');"
        ].join('\n'), EXIT_DEADLINE_MILLIS));
    });

    it('opens a new WebSocket for a callback registered after close()', async function () {
        var client = mockServerClient(mockServerHost, mockServerPort);
        var requestPath = '/callback-close-' + crypto.randomUUID();
        try {
            await client.mockWithCallback({path: requestPath + '/before'}, function () {
                return {statusCode: 202};
            });
            await client.close();
            assert.equal(await sendRequest(requestPath + '/before'), 404, 'a callback answered after close()');

            await client.mockWithCallback({path: requestPath + '/after'}, function () {
                return {statusCode: 203};
            });
            assert.equal(await sendRequest(requestPath + '/after'), 203);
        } finally {
            await client.close();
        }
    });

    it('opens a new breakpoint WebSocket after close()', async function () {
        var client = mockServerClient(mockServerHost, mockServerPort);
        var requestPath = '/callback-close-' + crypto.randomUUID();
        try {
            await client.addRequestBreakpoint({path: requestPath + '/before'}, function () {
                return {statusCode: 202};
            });
            assert.equal(await sendRequest(requestPath + '/before'), 202);
            await client.close();

            await client.addRequestBreakpoint({path: requestPath + '/after'}, function () {
                return {statusCode: 203};
            });
            assert.equal(await sendRequest(requestPath + '/after'), 203);
        } finally {
            await client.close();
            await client.clearBreakpointMatchers();
        }
    });
});
