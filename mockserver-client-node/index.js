/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

(function () {
    "use strict";

    var client = require('./mockServerClient');
    var mockServerClient = client.mockServerClient;
    var MockMode = client.MockMode;
    var setupMockServer = require('./setupMockServer').setupMockServer;
    var llm = require('./llm');
    var mcpMock = require('./mcpMockBuilder').mcpMock;
    var a2aMock = require('./a2aMockBuilder').a2aMock;

    // values are identifiers only: Node reads an ES module importer's names from this literal
    // and stops at the first value that is not one
    module.exports = {
        mockServerClient: mockServerClient,
        MockMode: MockMode,
        setupMockServer: setupMockServer,
        llm: llm,
        mcpMock: mcpMock,
        a2aMock: a2aMock
    };
})();
