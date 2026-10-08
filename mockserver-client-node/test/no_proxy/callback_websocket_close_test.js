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
var net = require('net');
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
        "var thens = 0;",
        "function counted(registration) { return new Promise(function (resolve, reject) { registration.then(function (result) { thens += 1; resolve(result); }, reject); }); }",
        "function send(method, requestPath) { return new Promise(function (resolve, reject) {",
        "    var req = require('http').request({host: " + JSON.stringify(mockServerHost) + ", port: " + mockServerPort + ", method: method, path: requestPath}, function (res) {",
        "        var body = ''; res.on('data', function (chunk) { body += chunk; }); res.on('end', function () { resolve({statusCode: res.statusCode, body: body}); });",
        "    });",
        "    req.on('error', reject); req.end();",
        "}); }",
        "function sleep(millis) { return new Promise(function (resolve) { setTimeout(resolve, millis); }); }",
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

function sendRawRequest(method, requestPath) {
    return new Promise(function (resolve, reject) {
        var req = http.request({host: mockServerHost, port: mockServerPort, method: method, path: requestPath, timeout: 10000}, function (res) {
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

function sendRequest(requestPath) {
    return sendRawRequest('GET', requestPath);
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

/*
 * A TCP proxy to the suite's MockServer that can cut the connections of callback WebSockets, as a network
 * fault would, while MockServer keeps the expectations.
 */
function startDroppingProxy() {
    var openWebSockets = [];
    var allSockets = [];
    var registrations = [];
    var waiting = [];
    var registered = function (registration) {
        var waiter = waiting.shift();
        if (waiter) {
            waiter(registration);
        } else {
            registrations.push(registration);
        }
    };
    var server = net.createServer(function (downstream) {
        var upstream = net.connect(mockServerPort, mockServerHost);
        allSockets.push(downstream, upstream);
        var destroyBoth = function () {
            downstream.destroy();
            upstream.destroy();
        };
        downstream.on('error', destroyBoth);
        upstream.on('error', destroyBoth);
        downstream.on('close', destroyBoth);
        upstream.on('close', destroyBoth);
        downstream.once('data', function (chunk) {
            var upgradeRequest = chunk.toString('latin1');
            if (upgradeRequest.indexOf('/_mockserver_callback_websocket') !== -1) {
                openWebSockets.push(downstream, upstream);
                var answer = '';
                var onAnswer = function (data) {
                    answer += data.toString('latin1');
                    if (answer.indexOf('WebSocketClientIdDTO') !== -1) {
                        upstream.removeListener('data', onAnswer);
                        registered({upgradeRequest: upgradeRequest});
                    }
                };
                upstream.on('data', onAnswer);
            }
            upstream.write(chunk);
            downstream.pipe(upstream);
        });
        upstream.pipe(downstream);
    });
    return new Promise(function (resolve) {
        server.listen(0, '127.0.0.1', function () {
            resolve({
                port: server.address().port,
                dropWebSockets: function () {
                    registrations.length = 0;
                    openWebSockets.splice(0).forEach(function (socket) {
                        socket.destroy();
                    });
                },
                // resolves when the next callback WebSocket through the proxy has its client id
                nextWebSocketRegistered: function (deadlineMillis) {
                    if (registrations.length > 0) {
                        return Promise.resolve(registrations.shift());
                    }
                    return new Promise(function (resolveRegistration, reject) {
                        var timer = setTimeout(function () {
                            reject(new Error('no callback WebSocket registered within ' + deadlineMillis + 'ms'));
                        }, deadlineMillis);
                        waiting.push(function (registration) {
                            clearTimeout(timer);
                            resolveRegistration(registration);
                        });
                    });
                },
                close: function () {
                    allSockets.forEach(function (socket) {
                        socket.destroy();
                    });
                    return new Promise(function (closed) {
                        server.close(function () {
                            closed();
                        });
                    });
                }
            });
        });
    });
}

/*
 * MockServer closes a callback's WebSocket when it removes the callback's expectation (used up, cleared or
 * reset) and when the connection drops. The client reconnects only while MockServer still holds the
 * expectation, so nothing MockServer removed comes back. Each wait outlasts the client's first reconnect
 * delay of 2 seconds.
 */
describe('reconnecting callback WebSockets', function () {
    var REGISTER_COUNTED = [
        "await counted(client.mockWithCallback({path: path}, respond, {unlimited: true}));",
        "await counted(client.mockWithForwardCallback({path: path + '/forward'}, forward, {unlimited: true}));",
        "await counted(client.mockWithForwardAndResponseCallback({path: path + '/forwardAndResponse'}, forward, function (request, response) { return response; }, {unlimited: true}));",
        "await counted(clientOverTls.mockWithCallback({path: path + '/tls'}, respond, {unlimited: true}));",
        "await counted(client.addRequestBreakpoint({path: path + '/breakpoint'}, function (request) { return request; }));"
    ].join('\n');

    it('keeps a reset made over the REST API reset, and lets the process exit', async function () {
        var result = await runProcess([
            REGISTER_COUNTED,
            "console.log('reset ' + (await send('PUT', '/mockserver/reset')).statusCode);",
            "await sleep(3000);",
            "var active = JSON.parse((await send('PUT', '/mockserver/retrieve?type=ACTIVE_EXPECTATIONS&format=JSON')).body);",
            "console.log('active ' + active.filter(function (expectation) { return expectation.httpRequest.path.indexOf(path) === 0; }).length);",
            "var matchers = JSON.parse((await send('GET', '/mockserver/breakpoint/matchers')).body).matchers;",
            "console.log('breakpoints ' + matchers.filter(function (matcher) { return matcher.httpRequest.path.indexOf(path) === 0; }).length);",
            "console.log('thens ' + thens);"
        ].join('\n'), EXIT_DEADLINE_MILLIS);
        assertExited(result);
        assert.match(result.stdout, /^reset 200$/m);
        assert.match(result.stdout, /^active 0$/m, 'a callback expectation came back after reset');
        assert.match(result.stdout, /^breakpoints 0$/m, 'a breakpoint came back after reset');
        assert.match(result.stdout, /^thens 5$/m, 'a registration\'s then() ran again');
    });

    it('answers many callback requests over one WebSocket', async function () {
        var result = await runProcess([
            "await counted(client.mockWithCallback({path: path}, respond, {unlimited: true}));",
            "var clientIdOf = async function () { return JSON.parse((await send('PUT', '/mockserver/retrieve?type=ACTIVE_EXPECTATIONS&format=JSON')).body).filter(function (expectation) { return expectation.httpRequest.path === path; })[0].httpResponseObjectCallback.clientId; };",
            "var before = await clientIdOf();",
            "var statusCodes = {};",
            "for (var i = 0; i < 50; i++) { var statusCode = (await send('GET', path)).statusCode; statusCodes[statusCode] = (statusCodes[statusCode] || 0) + 1; }",
            "await sleep(3000);",
            "console.log('statusCodes ' + JSON.stringify(statusCodes));",
            "console.log('same WebSocket ' + (before === await clientIdOf()));",
            "console.log('thens ' + thens);",
            "await client.close();"
        ].join('\n'), EXIT_DEADLINE_MILLIS);
        assertExited(result);
        assert.match(result.stdout, /^statusCodes \{"201":50\}$/m);
        assert.match(result.stdout, /^same WebSocket true$/m);
        assert.match(result.stdout, /^thens 1$/m);
    });

    it('does not register a used-up callback again, and lets the process exit', async function () {
        var result = await runProcess([
            "await counted(client.mockWithCallback({path: path}, respond, 3));",
            "var statusCodes = [];",
            "for (var i = 0; i < 4; i++) { statusCodes.push((await send('GET', path)).statusCode); }",
            "await sleep(3000);",
            "statusCodes.push((await send('GET', path)).statusCode);",
            "console.log('statusCodes ' + statusCodes.join(','));",
            "console.log('active ' + JSON.stringify(await client.retrieveActiveExpectations(path)));",
            "console.log('thens ' + thens);"
        ].join('\n'), EXIT_DEADLINE_MILLIS);
        assertExited(result);
        assert.match(result.stdout, /^statusCodes 201,201,201,404,404$/m);
        assert.match(result.stdout, /^active \[\]$/m, 'a used-up callback expectation came back');
        assert.match(result.stdout, /^thens 1$/m);
    });

    it('opens a new breakpoint WebSocket after a REST reset closed the old one', async function () {
        var client = mockServerClient(mockServerHost, mockServerPort);
        var requestPath = '/callback-reconnect-' + crypto.randomUUID();
        try {
            await client.addRequestBreakpoint({path: requestPath + '/before'}, function () {
                return {statusCode: 202};
            });
            assert.equal(await sendRequest(requestPath + '/before'), 202);
            assert.equal(await sendRawRequest('PUT', '/mockserver/reset'), 200);
            // lets the client see MockServer close the WebSocket
            await new Promise(function (resolve) {
                setTimeout(resolve, 1000);
            });

            await client.addRequestBreakpoint({path: requestPath + '/after'}, function () {
                return {statusCode: 203};
            });
            assert.equal(await sendRequest(requestPath + '/after'), 203);
        } finally {
            await client.close();
            await client.clearBreakpointMatchers();
        }
    });

    it('reconnects a dropped callback WebSocket with its client id while MockServer keeps the expectation', { timeout: 30000 }, async function () {
        var proxy = await startDroppingProxy();
        // the dropping proxy listens in this process, not on the MockServer host
        var client = mockServerClient('127.0.0.1', proxy.port);
        var direct = mockServerClient(mockServerHost, mockServerPort);
        var requestPath = '/callback-reconnect-' + crypto.randomUUID();
        var thens = 0;
        try {
            await new Promise(function (resolve, reject) {
                client.mockWithCallback({path: requestPath}, function () {
                    return {statusCode: 201};
                }, 5).then(function () {
                    thens += 1;
                    resolve();
                }, reject);
            });
            assert.equal(await sendRequest(requestPath), 201);
            var before = await direct.retrieveActiveExpectations(requestPath);
            assert.equal(before.length, 1);
            assert.equal(before[0].times.remainingTimes, 4);
            var clientId = before[0].httpResponseObjectCallback.clientId;

            proxy.dropWebSockets();
            var reconnected = await proxy.nextWebSocketRegistered(20000);
            assert.match(reconnected.upgradeRequest, new RegExp('^x-client-registration-id: ' + clientId + '\\r$', 'mi'), 'reconnected with another client id');

            assert.equal(await sendRequest(requestPath), 201);
            var after = await direct.retrieveActiveExpectations(requestPath);
            assert.equal(after.length, 1, 'the callback expectation was registered again');
            assert.equal(after[0].id, before[0].id);
            assert.equal(after[0].httpResponseObjectCallback.clientId, clientId);
            assert.equal(after[0].times.remainingTimes, 3, 'the callback did not keep its remaining times');
            assert.equal(thens, 1, 'then() ran again');
        } finally {
            await client.close();
            await direct.clear(requestPath);
            await proxy.close();
        }
    });
});
