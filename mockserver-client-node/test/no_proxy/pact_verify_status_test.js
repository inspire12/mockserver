'use strict';

// pactVerify against a local HTTP server standing in for MockServer, so each
// status and body the server could answer is under the test's control.

var { describe, it } = require('node:test');
var assert = require('node:assert/strict');
var http = require('http');
var mockServerClient = require('../../').mockServerClient;

function startStubServer(statusCode, contentType, body) {
    return new Promise(function (resolve) {
        var server = http.createServer(function (req, res) {
            req.resume();
            req.on('end', function () {
                res.writeHead(statusCode, {'Content-Type': contentType, 'Connection': 'close'});
                res.end(body);
            });
        });
        server.listen(0, '127.0.0.1', function () {
            resolve({server: server, port: server.address().port});
        });
    });
}

// Settles with {resolved: value} or {rejected: reason}, or {pending: true} when
// neither callback fires within the deadline.
function outcomeOf(thenable) {
    return new Promise(function (resolve) {
        var timer = setTimeout(resolve, 5000, {pending: true});
        thenable.then(function (value) {
            clearTimeout(timer);
            resolve({resolved: value});
        }, function (reason) {
            clearTimeout(timer);
            resolve({rejected: reason});
        });
    });
}

async function pactVerifyAgainst(statusCode, contentType, body) {
    var stub = await startStubServer(statusCode, contentType, body);
    try {
        return await outcomeOf(mockServerClient('127.0.0.1', stub.port).pactVerify({consumer: {name: 'c'}}));
    } finally {
        stub.server.close();
    }
}

describe('pactVerify answer handling', function () {
    it('rejects with the body when MockServer answers 500 with plain text', async function () {
        var outcome = await pactVerifyAgainst(500, 'text/plain', 'java.lang.IllegalStateException: boom');
        assert.deepEqual(outcome, {rejected: 'java.lang.IllegalStateException: boom'});
    });

    it('rejects with the body when MockServer answers 401 with plain text', async function () {
        var outcome = await pactVerifyAgainst(401, 'text/plain', 'Unauthorized for control plane');
        assert.deepEqual(outcome, {rejected: 'Unauthorized for control plane'});
    });

    it('rejects with the error message when MockServer answers 400 with an error report', async function () {
        var outcome = await pactVerifyAgainst(400, 'application/json', JSON.stringify({error: 'invalid pact'}));
        assert.deepEqual(outcome, {rejected: 'invalid pact'});
    });

    it('rejects when MockServer answers 202 with a body that is not JSON', async function () {
        var outcome = await pactVerifyAgainst(202, 'text/plain', 'not a report');
        assert.deepEqual(outcome, {rejected: 'not a report'});
    });

    it('rejects naming the status when MockServer answers 500 with no body', async function () {
        var outcome = await pactVerifyAgainst(500, 'text/plain', '');
        assert.deepEqual(outcome, {rejected: 'pactVerify failed with status 500'});
    });

    it('resolves with the report when MockServer answers 202', async function () {
        var outcome = await pactVerifyAgainst(202, 'application/json', JSON.stringify({verified: true, interactions: []}));
        assert.deepEqual(outcome, {resolved: {verified: true, interactions: []}});
    });

    it('resolves with the report when MockServer answers 406', async function () {
        var outcome = await pactVerifyAgainst(406, 'application/json', JSON.stringify({verified: false, interactions: []}));
        assert.deepEqual(outcome, {resolved: {verified: false, interactions: []}});
    });
});
