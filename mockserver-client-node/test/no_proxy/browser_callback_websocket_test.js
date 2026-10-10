'use strict';

// The browser callback WebSocket, run in a context with no `require` or `module`, as in a browser,
// against a fake WebSocket each test drives: a callback registration settles only once MockServer
// has sent the client id, and rejects when the WebSocket fails, closes first or the id is late.

var { describe, it } = require('node:test');
var assert = require('node:assert/strict');
var fs = require('fs');
var path = require('path');
var vm = require('vm');

var clientSource = fs.readFileSync(path.resolve(__dirname, '..', '..', 'mockServerClient.js'), 'utf-8');
var CLIENT_ID_TYPE = 'org.mockserver.serialization.model.WebSocketClientIdDTO';
var CANT_CONNECT = "Can't connect to MockServer running on host: \"localhost\" and port: \"1080\"";

function browserClient(options) {
    var sockets = [];
    var sent = [];

    function FakeWebSocket(url) {
        this.url = url;
        this.readyState = FakeWebSocket.CONNECTING;
        this.closeCalls = 0;
        this.listeners = {close: []};
        sockets.push(this);
    }
    FakeWebSocket.CONNECTING = FakeWebSocket.prototype.CONNECTING = 0;
    FakeWebSocket.OPEN = FakeWebSocket.prototype.OPEN = 1;
    FakeWebSocket.CLOSING = FakeWebSocket.prototype.CLOSING = 2;
    FakeWebSocket.CLOSED = FakeWebSocket.prototype.CLOSED = 3;
    FakeWebSocket.prototype.addEventListener = function (name, listener) {
        this.listeners[name].push(listener);
    };
    FakeWebSocket.prototype.close = function () {
        this.closeCalls += 1;
        this.closed(1000);
    };
    FakeWebSocket.prototype.send = function () {
    };
    // what MockServer or the network does to the socket
    FakeWebSocket.prototype.opened = function () {
        this.readyState = FakeWebSocket.OPEN;
    };
    FakeWebSocket.prototype.receive = function (message) {
        this.onmessage({data: JSON.stringify(message)});
    };
    FakeWebSocket.prototype.sendClientId = function (clientId) {
        this.opened();
        this.receive({type: CLIENT_ID_TYPE, value: JSON.stringify({clientId: clientId})});
    };
    FakeWebSocket.prototype.closed = function (code) {
        if (this.readyState === FakeWebSocket.CLOSED) {
            return;
        }
        this.readyState = FakeWebSocket.CLOSED;
        if (this.onclose) {
            this.onclose({code: code, reason: ''});
        }
        this.listeners.close.forEach(function (listener) {
            listener();
        });
    };
    FakeWebSocket.prototype.failed = function () {
        this.onerror({});
        this.closed(1006);
    };

    function FakeXMLHttpRequest() {
        this.listeners = {};
    }
    FakeXMLHttpRequest.prototype.addEventListener = function (name, listener) {
        this.listeners[name] = listener;
    };
    FakeXMLHttpRequest.prototype.open = function (method, url) {
        this.method = method;
        this.url = url;
    };
    FakeXMLHttpRequest.prototype.setRequestHeader = function () {
    };
    FakeXMLHttpRequest.prototype.send = function (body) {
        var xhr = this;
        sent.push({method: xhr.method, url: xhr.url, body: body});
        setImmediate(function () {
            xhr.status = 201;
            xhr.responseText = JSON.stringify(xhr.url.indexOf('/breakpoint/') !== -1 ? {id: 'breakpoint-1'} : [{id: 'expectation-1'}]);
            xhr.listeners.load.call(xhr);
        });
    };

    var context = vm.createContext({
        XMLHttpRequest: FakeXMLHttpRequest,
        WebSocket: FakeWebSocket,
        setTimeout: setTimeout,
        clearTimeout: clearTimeout,
        console: console
    });
    vm.runInContext(clientSource, context);
    assert.equal(typeof context.require, 'undefined');
    return {client: context.mockServerClient('localhost', 1080, undefined, false, undefined, options), sockets: sockets, sent: sent};
}

// {resolved: value}, {rejected: reason} or {pending: true} when neither happens within the deadline
function settledWithin(thenable, deadlineMillis) {
    return new Promise(function (resolve) {
        var timer = setTimeout(resolve, deadlineMillis, {pending: true});
        thenable.then(function (value) {
            clearTimeout(timer);
            resolve({resolved: value});
        }, function (reason) {
            clearTimeout(timer);
            resolve({rejected: reason});
        });
    });
}

function sleep(millis) {
    return new Promise(function (resolve) {
        setTimeout(resolve, millis);
    });
}

var registrations = {
    'mockWithCallback': function (client) {
        return client.mockWithCallback({path: '/some'}, function () {
            return {statusCode: 200};
        });
    },
    'addRequestBreakpoint': function (client) {
        return client.addRequestBreakpoint({path: '/some'}, function (request) {
            return request;
        });
    }
};

describe('browser callback WebSocket registration', {timeout: 30000}, function () {
    Object.keys(registrations).forEach(function (name) {
        var register = registrations[name];

        it(name + ' rejects when the WebSocket cannot connect', async function () {
            var browser = browserClient();
            var outcome = settledWithin(register(browser.client), 1000);
            browser.sockets[0].failed();
            assert.deepEqual(await outcome, {rejected: CANT_CONNECT});
            assert.deepEqual(browser.sent, []);
        });

        it(name + ' rejects when MockServer closes the WebSocket before sending the client id', async function () {
            var browser = browserClient();
            var outcome = settledWithin(register(browser.client), 1000);
            browser.sockets[0].opened();
            browser.sockets[0].closed(1011);
            assert.match(String((await outcome).rejected), /closed the callback WebSocket before sending its client id \(1011\)/);
            assert.deepEqual(browser.sent, []);
        });

        it(name + ' rejects, and closes the WebSocket, when the client id does not arrive in time', async function () {
            var browser = browserClient({callbackWebSocketTimeoutMillis: 200});
            var outcome = settledWithin(register(browser.client), 2000);
            browser.sockets[0].opened();
            assert.match(String((await outcome).rejected), /client id within 200ms/);
            assert.equal(browser.sockets[0].closeCalls, 1);
            assert.deepEqual(browser.sent, []);
        });

        it(name + ' settles only once the client id has arrived, and registers with it', async function () {
            var browser = browserClient();
            var settled = false;
            var outcome = settledWithin(register(browser.client), 2000).then(function (result) {
                settled = true;
                return result;
            });
            browser.sockets[0].opened();
            await sleep(100);
            assert.equal(settled, false);
            assert.deepEqual(browser.sent, []);
            browser.sockets[0].sendClientId('client-one');
            var result = await outcome;
            assert.ok(!('rejected' in result) && !('pending' in result), JSON.stringify(result));
            assert.equal(browser.sent.length, 1);
            assert.match(browser.sent[0].body, /"clientId":"client-one"/);
        });
    });
});
