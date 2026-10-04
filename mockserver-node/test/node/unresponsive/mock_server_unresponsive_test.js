/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

'use strict';

var test = require('node:test');
var assert = require('node:assert');
var fs = require('fs');
var http = require('http');
var os = require('os');
var path = require('path');
var mockserver = require(__dirname + '/../../..');

var READINESS_PATH = '/mockserver/retrieve?type=ACTIVE_EXPECTATIONS';
var STOPPED_CHECK_PATH = '/reset';

// Every start in this file launches this stand-in for java. It never listens, and ends by itself if a
// failing test leaves it behind; given -Dstandin.exit=N it says so and exits with N at once.
// The servers the launcher polls are stubs on ports the system picks.
var STAND_IN_JAVA = [
    '#!/bin/sh',
    'for argument in "$@"; do',
    '  case "$argument" in',
    '    -Dstandin.exit=*) echo "stand-in java exiting"; exit "${argument#-Dstandin.exit=}" ;;',
    '  esac',
    'done',
    'exec sleep 120',
    ''
].join('\n');
var shimDirectory;
var previousPath;

test.before(function () {
    shimDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'mockserver-node-java-shim-'));
    fs.writeFileSync(path.join(shimDirectory, 'java'), STAND_IN_JAVA, {mode: 0o755});
    previousPath = process.env.PATH;
    process.env.PATH = shimDirectory + path.delimiter + previousPath;
});

test.after(function () {
    process.env.PATH = previousPath;
    fs.rmSync(shimDirectory, {recursive: true, force: true});
});

function pause(millis) {
    return new Promise(function (resolve) {
        setTimeout(resolve, millis);
    });
}

// respond(request, response, count) is called with the number of requests made so far to request.url
function startStub(respond) {
    var sockets = [];
    var open = 0;
    var counts = {};
    var server = http.createServer(function (request, response) {
        counts[request.url] = (counts[request.url] || 0) + 1;
        respond(request, response, counts[request.url]);
    });
    server.on('connection', function (socket) {
        sockets.push(socket);
        open++;
        socket.once('close', function () {
            open--;
        });
    });

    function close() {
        sockets.forEach(function (socket) {
            socket.destroy();
        });
        server.close();
    }

    return new Promise(function (resolve) {
        server.listen(0, function () {
            resolve({
                port: server.address().port,
                close: close,
                requestsTo: function (url) {
                    return counts[url] || 0;
                },
                connectionsClosed: async function (withinMillis) {
                    var deadline = Date.now() + withinMillis;
                    while (open > 0 && Date.now() < deadline) {
                        await pause(20);
                    }
                    return open === 0 ? 'all closed' : open + ' still open';
                }
            });
        });
    });
}

function answer(response) {
    response.writeHead(200, {'Connection': 'close', 'Content-Length': 0});
    response.end();
}

function neverAnswer() {
}

function answerWithHeadersOnly(request, response) {
    response.writeHead(200, {'Content-Length': 10});
    response.flushHeaders();
}

function trickleAnAnswer(request, response) {
    response.writeHead(200);
    var trickle = setInterval(function () {
        response.write('.');
    }, 250);
    response.once('close', function () {
        clearInterval(trickle);
    });
}

function hangUp(request) {
    request.socket.destroy();
}

function hangUpAfterHeaders(request, response) {
    response.writeHead(200, {'Content-Length': 10});
    response.flushHeaders();
    setTimeout(hangUp, 20, request);
}

function outcomeOf(promise, withinMillis) {
    var began = Date.now();
    return new Promise(function (resolve) {
        var timer = setTimeout(resolve, withinMillis, {state: 'still pending after ' + withinMillis + 'ms'});
        promise.then(function (value) {
            clearTimeout(timer);
            resolve({state: 'resolved', value: value, millis: Date.now() - began});
        }, function (error) {
            clearTimeout(timer);
            resolve({state: 'rejected', error: error, millis: Date.now() - began});
        });
    });
}

function exitOf(launched, withinMillis) {
    return new Promise(function (resolve) {
        if (launched.exitCode !== null || launched.signalCode !== null) {
            resolve('exited');
            return;
        }
        var timer = setTimeout(resolve, withinMillis, 'still running');
        launched.once('exit', function () {
            clearTimeout(timer);
            resolve('exited');
        });
    });
}

function killLaunched() {
    var leftover = mockserver.getMockServerProcess();
    if (leftover) {
        leftover.kill();
    }
}

// runForked keeps the launcher's uncaughtException handler, which exits with status 0, out of this process
function startOn(port, startupRetries, extraOptions) {
    return mockserver.start_mockserver(Object.assign({
        serverPort: port,
        jarPath: __filename,
        startupRetries: startupRetries,
        runForked: true
    }, extraOptions));
}

function printed(consoleError) {
    return consoleError.mock.calls.map(function (call) {
        return String(call.arguments[0]);
    });
}

var NOT_READY = '^MockServer did not become ready on port ';
var NO_ANSWER = ' within [\\d.]+ seconds \\(no answer to "PUT /mockserver/retrieve\\?type=ACTIVE_EXPECTATIONS" within 2 seconds\\)';

test('start waits out the time its retries allow, then gives up, when the readiness request is never answered', async function () {
    var stub = await startStub(neverAnswer);

    try {
        // an unanswered poll takes 2 seconds and is charged 20 retries: 30 retries allow two of them
        var outcome = await outcomeOf(startOn(stub.port, 30), 8000);

        assert.strictEqual(outcome.state, 'rejected');
        assert.match(outcome.error.message, new RegExp(NOT_READY + stub.port + NO_ANSWER));
        assert.match(outcome.error.message, /its java process \(pid \d+\) was stopped$/);
        assert.strictEqual(outcome.error.code, 'ETIMEDOUT');
        assert.strictEqual(outcome.error.cause.message, 'no answer to "PUT ' + READINESS_PATH + '" within 2 seconds');
        assert.strictEqual(stub.requestsTo(READINESS_PATH), 2, "polls made");
        assert.ok(outcome.millis >= 4000, "gave up after " + outcome.millis + "ms, before two polls had timed out");
        assert.strictEqual(await exitOf(mockserver.getMockServerProcess(), 10000), 'exited', "the launched process is stopped");
        assert.strictEqual(await stub.connectionsClosed(2000), 'all closed', "the launcher's connections");
    } finally {
        stub.close();
        killLaunched();
    }
});

[
    {name: 'answered with headers and no body', respond: answerWithHeadersOnly},
    {name: 'answered a byte at a time without end', respond: trickleAnAnswer}
].forEach(function (unfinished) {
    test('start gives up and stops the launched process when the readiness request is ' + unfinished.name, async function () {
        var stub = await startStub(unfinished.respond);

        try {
            var outcome = await outcomeOf(startOn(stub.port, 5), 8000);

            assert.strictEqual(outcome.state, 'rejected');
            assert.match(outcome.error.message, new RegExp(NOT_READY + stub.port + NO_ANSWER));
            assert.strictEqual(stub.requestsTo(READINESS_PATH), 1, "polls made");
            assert.strictEqual(await exitOf(mockserver.getMockServerProcess(), 10000), 'exited', "the launched process is stopped");
            assert.strictEqual(await stub.connectionsClosed(2000), 'all closed', "the launcher's connections");
        } finally {
            stub.close();
            killLaunched();
        }
    });
});

test('start succeeds when the first readiness request is never answered and the second is', async function () {
    var stub = await startStub(function (request, response, count) {
        if (count > 1) {
            answer(response);
        }
    });

    try {
        var outcome = await outcomeOf(startOn(stub.port, 30), 8000);

        assert.strictEqual(outcome.state, 'resolved');
        assert.strictEqual(await exitOf(mockserver.getMockServerProcess(), 500), 'still running', "the launched process is kept");
    } finally {
        stub.close();
        killLaunched();
    }
});

[
    {name: 'closes the connection', respond: hangUp},
    {name: 'closes the connection part way through its answer', respond: hangUpAfterHeaders}
].forEach(function (failing) {
    test('start succeeds when the server ' + failing.name + ' for three readiness requests and answers the fourth', async function () {
        var stub = await startStub(function (request, response, count) {
            if (count > 3) {
                answer(response);
            } else {
                failing.respond(request, response);
            }
        });

        try {
            var outcome = await outcomeOf(startOn(stub.port, 5), 8000);

            assert.strictEqual(outcome.state, 'resolved');
            assert.strictEqual(await exitOf(mockserver.getMockServerProcess(), 500), 'still running', "the launched process is kept");
        } finally {
            stub.close();
            killLaunched();
        }
    });
});

test('start uses every retry, however long each failed readiness request took', async function () {
    var startupRetries = 10;
    // ten retries nominally last one second: these last four, as on a host too slow to keep the interval
    var stub = await startStub(function (request, response, count) {
        if (count > startupRetries) {
            answer(response);
        } else {
            setTimeout(hangUp, 300, request);
        }
    });

    try {
        var outcome = await outcomeOf(startOn(stub.port, startupRetries), 12000);

        assert.strictEqual(outcome.state, 'resolved');
        assert.strictEqual(stub.requestsTo(READINESS_PATH), startupRetries + 1, "polls made");
        assert.strictEqual(await exitOf(mockserver.getMockServerProcess(), 500), 'still running', "the launched process is kept");
    } finally {
        stub.close();
        killLaunched();
    }
});

test('start gives up and stops the launched process when the server never becomes ready', async function (t) {
    var stub = await startStub(hangUp);
    var consoleError = t.mock.method(console, 'error', function () {
    });

    try {
        var outcome = await outcomeOf(startOn(stub.port, 5), 8000);

        assert.strictEqual(outcome.state, 'rejected');
        assert.match(outcome.error.message, new RegExp(NOT_READY + stub.port + ' within [\\d.]+ seconds \\(socket hang up\\); its java process \\(pid \\d+\\) was stopped$'));
        assert.strictEqual(outcome.error.cause.message, 'socket hang up');
        assert.strictEqual(outcome.error.code, 'ECONNRESET');
        assert.strictEqual(stub.requestsTo(READINESS_PATH), 6, "polls made");
        assert.deepStrictEqual(printed(consoleError), [outcome.error.message]);
        assert.strictEqual(await exitOf(mockserver.getMockServerProcess(), 10000), 'exited', "the launched process is stopped");
    } finally {
        stub.close();
        killLaunched();
    }
});

test('start says so when the launched process had already exited', async function (t) {
    var stub = await startStub(hangUp);
    var consoleError = t.mock.method(console, 'error', function () {
    });

    try {
        var outcome = await outcomeOf(startOn(stub.port, 20, {jvmOptions: ['-Dstandin.exit=3']}), 8000);

        assert.strictEqual(outcome.state, 'rejected');
        assert.match(outcome.error.message, new RegExp(NOT_READY + stub.port + ' within [\\d.]+ seconds \\(socket hang up\\); its java process had already exited \\(code=3, signal=null\\)$'));
        assert.deepStrictEqual(printed(consoleError), [outcome.error.message, 'last MockServer output:\nstand-in java exiting']);
    } finally {
        stub.close();
        killLaunched();
    }
});

test('start gives up and leaves the launched process running when it is waiting for a debugger', async function () {
    var stub = await startStub(hangUp);
    // only passed on to the stand-in for java, which never opens it
    var javaDebugPort = 5005;

    try {
        var outcome = await outcomeOf(startOn(stub.port, 5, {javaDebugPort: javaDebugPort}), 8000);

        assert.strictEqual(outcome.state, 'rejected');
        assert.match(outcome.error.message, new RegExp(NOT_READY + stub.port + ' within [\\d.]+ seconds'));
        assert.match(outcome.error.message, /its java process \(pid \d+\) was left running because "javaDebugPort" is set: it is waiting for a debugger to attach on port 5005$/);
        assert.strictEqual(await exitOf(mockserver.getMockServerProcess(), 1000), 'still running', "the launched process is left for the debugger");
    } finally {
        stub.close();
        killLaunched();
    }
});

test('stop waits out the time its retries allow, then gives up, when the check that the server stopped is never answered', async function () {
    var stub = await startStub(function (request, response) {
        if (request.url !== STOPPED_CHECK_PATH) {
            answer(response);
        }
    });

    try {
        await startOn(stub.port, 5);
        var launched = mockserver.getMockServerProcess();

        // the check has 100 retries: an unanswered poll takes 2 seconds and is charged 20 of them
        var outcome = await outcomeOf(mockserver.stop_mockserver({serverPort: stub.port}), 20000);

        assert.strictEqual(outcome.state, 'rejected');
        assert.match(outcome.error.message, new RegExp('^MockServer is still accepting connections on port ' + stub.port + ' [\\d.]+ seconds after it was asked to stop$'));
        assert.strictEqual(stub.requestsTo(STOPPED_CHECK_PATH), 5, "polls made");
        assert.ok(outcome.millis >= 10000, "gave up after " + outcome.millis + "ms, before five polls had timed out");
        assert.strictEqual(await exitOf(launched, 10000), 'exited', "the launched process is stopped");
        assert.strictEqual(await stub.connectionsClosed(2000), 'all closed', "the launcher's connections");
    } finally {
        stub.close();
        killLaunched();
    }
});

test('stop succeeds when the server keeps answering for a while after its stop request and then goes', async function () {
    var stub = await startStub(function (request, response, count) {
        answer(response);
        if (request.url === STOPPED_CHECK_PATH && count === 10) {
            stub.close();
        }
    });

    try {
        await startOn(stub.port, 5);
        var launched = mockserver.getMockServerProcess();

        var outcome = await outcomeOf(mockserver.stop_mockserver({serverPort: stub.port}), 20000);

        assert.strictEqual(outcome.state, 'resolved');
        assert.strictEqual(stub.requestsTo(STOPPED_CHECK_PATH), 10, "polls answered");
        assert.strictEqual(await exitOf(launched, 10000), 'exited', "the launched process is stopped");
    } finally {
        stub.close();
        killLaunched();
    }
});
