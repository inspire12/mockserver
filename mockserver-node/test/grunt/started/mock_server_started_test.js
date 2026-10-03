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
var mockserver = require(__dirname + '/../../..');
var sendRequest = require(__dirname + '/../../sendRequest.js');

var port = 1080;

test('mock server should have started - should allow expectation to be setup', async function () {
    try {
        await mockserver.start_mockserver({
            serverPort: port,
            jvmOptions: [
                '-Dmockserver.enableCORSForAllResponses=true',
                '-Dmockserver.corsAllowMethods="CONNECT, DELETE, GET, HEAD, OPTIONS, POST, PUT, PATCH, TRACE"',
                '-Dmockserver.corsAllowHeaders="Allow, Content-Encoding, Content-Length, Content-Type, ETag, Expires, Last-Modified, Location, Server, Vary, Authorization"',
                '-Dmockserver.corsAllowCredentials=true -Dmockserver.corsMaxAgeInSeconds=300'
            ],
            mockServerVersion: "6.0.0"
        });
        var response = await sendRequest("PUT", "localhost", port, "/expectation", {
            'httpRequest': {
                'path': '/somePath'
            },
            'httpResponse': {
                'statusCode': 202,
                'body': JSON.stringify({name: 'first_body'})
            }
        });
        assert.strictEqual(response.statusCode, 201, "allows expectation to be setup");

        var matchResponse = await sendRequest("GET", "localhost", port, "/somePath");
        assert.strictEqual(matchResponse.statusCode, 202, "expectation matched successfully");
    } finally {
        await mockserver.stop_mockserver({serverPort: port});
    }
});
