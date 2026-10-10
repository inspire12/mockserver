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
var http = require('http');
var mockserver = require(__dirname + '/../../..');
var sendRequest = require(__dirname + '/../../sendRequest.js');

test('should fail when attempting to setup expectation after stop', async function () {
    var port = 1084;

    try {
        await mockserver.start_mockserver({serverPort: port});
    } finally {
        await mockserver.stop_mockserver({serverPort: port});
    }

    // wait for the server to fully shut down
    await new Promise(function (resolve) { setTimeout(resolve, 500); });

    await assert.rejects(
        sendRequest("PUT", "localhost", port, "/expectation", {
            'httpRequest': {
                'path': '/somePath'
            },
            'httpResponse': {
                'statusCode': 201,
                'body': JSON.stringify({name: 'first_body'})
            }
        }),
        function () {
            // Any rejection (connection refused) means the server is stopped - this is expected
            return true;
        },
        "did not allow expectation to be setup"
    );
});

function listen(server) {
    return new Promise(function (resolve) {
        server.listen(0, function () {
            resolve(server.address().port);
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

test('should stop the launched MockServer when the stop request is answered 400', async function () {
    var port = 1086;
    var answering400 = http.createServer(function (request, response) {
        response.writeHead(400, {'Connection': 'close', 'Content-Length': 0});
        response.end();
    });
    var stubPort = await listen(answering400);

    try {
        await mockserver.start_mockserver({serverPort: port});
        var exited = exitOf(mockserver.getMockServerProcess(), 10000);

        await assert.rejects(
            mockserver.stop_mockserver({serverPort: stubPort}),
            function (error) {
                assert.strictEqual(error, 400);
                return true;
            }
        );

        assert.strictEqual(await exited, 'exited', "the launched MockServer is stopped");
    } finally {
        answering400.close();
        killLaunched();
    }
});

function answering(statusCode) {
    return http.createServer(function (request, response) {
        var body = 'failed with ' + statusCode;
        response.writeHead(statusCode, {'Connection': 'close', 'Content-Type': 'text/plain', 'Content-Length': body.length});
        response.end(body);
    });
}

test('should reject when the stop request is answered with any status that is not 2xx', async function () {
    var port = 1089;
    var answering500 = answering(500);
    var answering403 = answering(403);
    var answering302 = answering(302);
    var stub500Port = await listen(answering500);
    var stub403Port = await listen(answering403);
    var stub302Port = await listen(answering302);

    try {
        await mockserver.start_mockserver({serverPort: port});
        var exited = exitOf(mockserver.getMockServerProcess(), 10000);

        for (var stub of [{port: stub500Port, status: 500}, {port: stub403Port, status: 403}, {port: stub302Port, status: 302}]) {
            var outcome = await new Promise(function (resolve) {
                var timer = setTimeout(resolve, 20000, 'still pending');
                mockserver.stop_mockserver({serverPort: stub.port}).then(function () {
                    clearTimeout(timer);
                    resolve('resolved');
                }, function (error) {
                    clearTimeout(timer);
                    resolve(error);
                });
            });
            assert.strictEqual(outcome, stub.status, 'stop answered ' + stub.status + ' rejects with that status');
        }

        assert.strictEqual(await exited, 'exited', "the launched MockServer is stopped");
    } finally {
        answering500.close();
        answering403.close();
        answering302.close();
        killLaunched();
    }
});

test('should stop the launched MockServer when the stop request is never answered', async function () {
    var port = 1087;
    var sockets = [];
    var neverAnswering = http.createServer(function () {
    });
    neverAnswering.on('connection', function (socket) {
        sockets.push(socket);
    });
    var stubPort = await listen(neverAnswering);

    try {
        await mockserver.start_mockserver({serverPort: port});
        var launched = mockserver.getMockServerProcess();

        var outcome = await new Promise(function (resolve) {
            var timer = setTimeout(resolve, 20000, 'still pending');
            mockserver.stop_mockserver({serverPort: stubPort}).then(function () {
                clearTimeout(timer);
                resolve('resolved');
            }, function (error) {
                clearTimeout(timer);
                resolve(String(error && error.message));
            });
        });

        assert.match(outcome, /did not answer "PUT \/stop" within 10 seconds/);
        assert.strictEqual(await exitOf(launched, 10000), 'exited', "the launched MockServer is stopped");
    } finally {
        sockets.forEach(function (socket) {
            socket.destroy();
        });
        neverAnswering.close();
        killLaunched();
    }
});

test('should stop the launched MockServer after the caller left bytes on a pooled connection', async function () {
    var port = 1088;

    try {
        await mockserver.start_mockserver({serverPort: port});
        var exited = exitOf(mockserver.getMockServerProcess(), 10000);

        // a body written on a GET goes out unframed, so it stays on the connection the global agent then pools
        await new Promise(function (resolve, reject) {
            var req = http.request({method: 'GET', host: 'localhost', port: port, path: '/somePath'});
            req.once('response', function (response) {
                response.resume();
                response.once('end', resolve);
            });
            req.once('error', reject);
            req.write('""');
            req.end();
        });

        await mockserver.stop_mockserver({serverPort: port});

        assert.strictEqual(await exited, 'exited', "the launched MockServer is stopped");
    } finally {
        killLaunched();
    }
});
