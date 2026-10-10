'use strict';

// The browser transport (XMLHttpRequest), run in a context with no `require`
// or `module`, as in a browser, against a fake XMLHttpRequest whose answer
// each test sets.

var { describe, it } = require('node:test');
var assert = require('node:assert/strict');
var fs = require('fs');
var path = require('path');
var vm = require('vm');

var clientSource = fs.readFileSync(path.resolve(__dirname, '..', '..', 'mockServerClient.js'), 'utf-8');

// answer: {status, body} for a response, or {networkError: true}
function browserClient(answer) {
    var sent = [];
    function FakeXMLHttpRequest() {
        this.listeners = {};
        this.headers = {};
    }
    FakeXMLHttpRequest.prototype.addEventListener = function (name, listener) {
        this.listeners[name] = listener;
    };
    FakeXMLHttpRequest.prototype.open = function (method, url) {
        this.method = method;
        this.url = url;
    };
    FakeXMLHttpRequest.prototype.setRequestHeader = function (name, value) {
        this.headers[name] = value;
    };
    FakeXMLHttpRequest.prototype.send = function (body) {
        var xhr = this;
        sent.push({method: xhr.method, url: xhr.url, headers: xhr.headers, body: body});
        setImmediate(function () {
            if (answer.networkError) {
                if (xhr.listeners.error) {
                    xhr.listeners.error.call(xhr);
                }
            } else {
                xhr.status = answer.status;
                xhr.responseText = answer.body;
                xhr.listeners.load.call(xhr);
            }
        });
    };
    var context = vm.createContext({XMLHttpRequest: FakeXMLHttpRequest, setTimeout: setTimeout, console: console});
    vm.runInContext(clientSource, context);
    assert.equal(typeof context.require, 'undefined');
    return {client: context.mockServerClient('localhost', 1080), sent: sent};
}

function settledWithin(thenable) {
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

// Settles with {resolved: value} or {rejected: reason}, or {pending: true}
// when neither callback fires within the deadline; copied out of the client's
// context, whose objects have that context's prototypes.
function outcomeOf(thenable) {
    return settledWithin(thenable).then(function (outcome) {
        return JSON.parse(JSON.stringify(outcome));
    });
}

// one call per transport: PUT with a JSON body, GET, DELETE and PUT with a binary body
var calls = {
    'PUT': function (client) {
        return client.clear('/somePath');
    },
    'GET': function (client) {
        return client.scrapeMetrics();
    },
    'DELETE': function (client) {
        return client.deleteLoadScenario('someScenario');
    },
    'binary PUT': function (client) {
        return client.uploadGrpcDescriptor(new Uint8Array([1, 2, 3]));
    }
};

describe('browser transport answer handling', function () {
    Object.keys(calls).forEach(function (name) {
        var call = calls[name];

        it(name + ' rejects with "404 Not Found" when MockServer answers 404', async function () {
            var browser = browserClient({status: 404, body: 'no such thing'});
            assert.deepEqual(await outcomeOf(call(browser.client)), {rejected: '404 Not Found'});
            assert.equal(browser.sent.length, 1);
        });

        it(name + ' rejects with the body when MockServer answers 500', async function () {
            var browser = browserClient({status: 500, body: 'java.lang.IllegalStateException: boom'});
            assert.deepEqual(await outcomeOf(call(browser.client)), {rejected: 'java.lang.IllegalStateException: boom'});
        });

        it(name + ' rejects with the body when MockServer answers 401', async function () {
            var browser = browserClient({status: 401, body: 'Unauthorized for control plane'});
            assert.deepEqual(await outcomeOf(call(browser.client)), {rejected: 'Unauthorized for control plane'});
        });

        it(name + ' rejects when the request gets no response', async function () {
            var browser = browserClient({networkError: true});
            assert.deepEqual(await outcomeOf(call(browser.client)), {rejected: "Can't connect to MockServer running on host: \"localhost\" and port: \"1080\""});
        });
    });

    it('a PUT resolves with the status and body when MockServer answers 2xx', async function () {
        var browser = browserClient({status: 200, body: 'cleared'});
        assert.deepEqual(await outcomeOf(browser.client.clear('/somePath')), {resolved: {statusCode: 200, body: 'cleared'}});
        assert.equal(browser.sent[0].method, 'PUT');
        assert.equal(browser.sent[0].url, 'http://localhost:1080/mockserver/clear');
        assert.equal(browser.sent[0].headers['Content-Type'], 'application/json; charset=utf-8');
    });

    it('a GET resolves with the body when MockServer answers 2xx', async function () {
        var browser = browserClient({status: 200, body: 'mock_server_requests_total 1'});
        assert.deepEqual(await outcomeOf(browser.client.scrapeMetrics()), {resolved: 'mock_server_requests_total 1'});
        assert.equal(browser.sent[0].method, 'GET');
        assert.equal(browser.sent[0].url, 'http://localhost:1080/mockserver/metrics');
    });

    [400, 404, 500].forEach(function (status) {
        it('a ' + status + ' never reaches the success callback when no error callback is given', async function () {
            var browser = browserClient({status: status, body: 'failed'});
            var succeeded = [];
            browser.client.clear('/somePath').then(function (value) {
                succeeded.push(value);
            });
            await new Promise(function (resolve) {
                setImmediate(setImmediate, resolve);
            });
            assert.equal(browser.sent.length, 1);
            assert.deepEqual(succeeded, []);
        });
    });

    it('pactVerify still receives a 406 report, whose transport resolves every status', async function () {
        var browser = browserClient({status: 406, body: JSON.stringify({verified: false, interactions: []})});
        assert.deepEqual(await outcomeOf(browser.client.pactVerify({consumer: {name: 'c'}})), {resolved: {verified: false, interactions: []}});
    });
});
