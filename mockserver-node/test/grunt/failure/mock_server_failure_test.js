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
var net = require('net');
var os = require('os');
var path = require('path');
var exec = require('child_process').exec;
var execFile = require('child_process').execFile;
var execOptions = {
    cwd: path.join(__dirname)
};

test('mock server fails to start - should fail start if configuration missing', function (t, done) {
    exec('../../../node_modules/.bin/grunt start_mockserver:missing_ports', execOptions, function (error, stdout, stderr) {
        stderr = stderr.replace(/\(node:\d*\) ExperimentalWarning: queueMicrotask\(\) is experimental\.\n/, '');
        assert.strictEqual(
            stderr,
            "Please specify \"serverPort\", for example: \"start_mockserver({ serverPort: 1080 })\"\n" +
            "\n" +
            "mockserver-node - you must at least specify serverPort, for example:\n" +
            "start_mockserver: {\n" +
            "    options: {\n" +
            "        serverPort: 1080\n" +
            "    }\n" +
            "}\n" +
            "\n"
        );
        done();
    });
});

test('mock server fails to stop - should fail stop if configuration missing', function (t, done) {
    exec('../../../node_modules/.bin/grunt stop_mockserver:missing_ports', execOptions, function (error, stdout, stderr) {
        stderr = stderr.replace(/\(node:\d*\) ExperimentalWarning: queueMicrotask\(\) is experimental\.\n/, '');
        assert.strictEqual(
            stderr,
            "Please specify \"serverPort\", for example: \"stop_mockserver({ serverPort: 1080 })\"\n" +
            "\n" +
            "mockserver-node - you must at least specify serverPort, for example:\n" +
            "stop_mockserver: {\n" +
            "    options: {\n" +
            "        serverPort: 1080\n" +
            "    }\n" +
            "}\n" +
            "\n"
        );
        done();
    });
});

test('mock server fails to start - should fail start, saying why, if there is no java on the PATH', function (t, done) {
    var noJava = fs.mkdtempSync(path.join(os.tmpdir(), 'mockserver-node-grunt-no-java-'));
    t.after(function () {
        fs.rmSync(noJava, {recursive: true, force: true});
    });
    // a port nothing listens on
    var unused = net.createServer();
    unused.listen(0, function () {
        var port = unused.address().port;
        unused.close(function () {
            execFile(process.execPath, ['../../../node_modules/grunt/bin/grunt', '--gruntfile', 'GruntfileNoJava.js', 'start_mockserver'], {
                cwd: __dirname,
                env: {PATH: noJava, MOCKSERVER_TEST_PORT: String(port)},
                // a start that polled on with the default retries would take 11 seconds to fail
                timeout: 8000
            }, function (error, stdout, stderr) {
                assert.strictEqual(error && error.killed, false, "the task ended by itself");
                assert.strictEqual(error.code, 3, "the status Grunt gives a failed task");
                assert.match(stdout, /Task "start_mockserver" failed/);
                assert.strictEqual(
                    stderr.split('\n')[0],
                    'MockServer could not be started: no "java" command was found. The launcher runs "java" from ' +
                    'the PATH of this process (PATH=' + noJava + '); it does not use JAVA_HOME (not set) and has no ' +
                    'option for the location of java. Install Java 17 or later and add its bin directory to PATH, ' +
                    'or use the "mockserver" command of this package, which needs no Java.'
                );
                assert.doesNotMatch(stderr, /you must at least specify serverPort/);
                done();
            });
        });
    });
});
