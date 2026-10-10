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

var port = 1085;

test('mock server has stopped - should fail when attempting to setup expectation', async function () {
    try {
        await mockserver.start_mockserver({serverPort: port, mockServerVersion: "6.0.0"});
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
