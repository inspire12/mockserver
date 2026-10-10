/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

'use strict';

// Run as a child process by mock_server_launch_failure_test.js: a plain script that calls the launcher.
// It reports each thing it sees as a line of JSON on stdout, and fails its own exit status when the
// start is rejected, as a setup script would.
var fs = require('fs');
var EventEmitter = require('events');
var mockserver = require(__dirname + '/../../..');
var scenario = JSON.parse(process.argv[2]);

function report(event) {
    fs.writeSync(1, JSON.stringify(event) + '\n');
}

function messageOf(error) {
    return String((error && error.message) || error);
}

// not emitted when the process is ended by process.exit() or by an uncaught exception
process.once('beforeExit', function () {
    report({event: 'nothing left to do'});
});

// What Node's spawn returns when the process has too few file descriptors left (EMFILE, ENFILE): a child
// with no pid and no output streams, and its 'error' on the next tick. Until then a signal sent to that
// child goes to the process group of the caller; after it, nowhere.
if (scenario.spawnFailsForWantOfFileDescriptors) {
    require('child_process').spawn = function () {
        var child = new EventEmitter();
        var failureEmitted = false;
        child.kill = function () {
            if (!failureEmitted) {
                report({event: 'signalled a process that was never launched'});
            }
            return false;
        };
        process.nextTick(function () {
            var error = new Error('spawn java EMFILE');
            error.code = 'EMFILE';
            failureEmitted = true;
            child.emit('error', error);
        });
        return child;
    };
}

report({event: 'calling start'});

mockserver.start_mockserver(scenario.options).then(function () {
    report({event: 'start', state: 'resolved', pid: mockserver.getMockServerProcess().pid});
}, function (error) {
    var launched = mockserver.getMockServerProcess();
    process.exitCode = 3;
    report({
        event: 'start',
        state: 'rejected',
        message: messageOf(error),
        code: error && error.code,
        exitCode: error && error.exitCode,
        signal: error && error.signal,
        pid: (launched && launched.pid) || undefined
    });
}).then(function () {
    if (scenario.thenThrow) {
        setTimeout(function () {
            throw new Error('thrown by the calling script');
        }, 0);
        return;
    }
    if (scenario.noStop) {
        setTimeout(report, 100, {event: 'later work ran'});
        return;
    }
    return mockserver.stop_mockserver({serverPort: scenario.options.serverPort}).then(function () {
        report({event: 'stop', state: 'resolved'});
    }, function (error) {
        report({event: 'stop', state: 'rejected', message: messageOf(error)});
    }).then(function () {
        setTimeout(report, 100, {event: 'later work ran'});
    });
});
