'use strict';

/*
 * The published mockserver-client tarball holds every file its package.json, typings and modules
 * point at, and the functions and values its typings export exist in its modules. The checks are
 * the launcher's.
 */

var path = require('path');

var REPO_ROOT = path.resolve(__dirname, '..', '..', '..');
var PACKAGE_ROOT = path.join(REPO_ROOT, 'mockserver-client-node');
var packageContents = require(path.join(REPO_ROOT, 'mockserver-node', 'test', 'packageContents.js'));
var mockServerClient = require(path.join(PACKAGE_ROOT, 'mockServerClient.js')).mockServerClient;

function client() {
    // building a client opens no connection
    return mockServerClient('localhost', 1080);
}

packageContents.registerTests(PACKAGE_ROOT, {
    // mockServerClient.js also exports the three builders the index gets from their own modules,
    // and three internals for this package's tests
    acceptedUndeclaredExports: {
        'mockServerClient.d.ts': ['llm', 'mcpMock', 'a2aMock',
            'routeBreakpointMessage', 'extractBreakpointHeaders', 'NON_HTTP_RESPONSE_ACTION_KEYS']
    },
    // each object a caller reaches through the client, compared with the interface that types it
    builtObjects: [
        {typings: 'mockServerClient.d.ts', name: 'MockServerClient', build: client},
        {typings: 'mockServerClient.d.ts', name: 'ForwardChainExpectation', build: function () { return client().when({path: '/x'}); }},
        {typings: 'mockServerClient.d.ts', name: 'ScenarioHandle', build: function () { return client().scenario('s'); }},
        {
            typings: 'llmTypes.d.ts', name: 'Llm', build: function () { return client().llm; },
            // index.d.ts declares the constructors on llm beside Llm
            acceptedUndeclared: ['Completion', 'EmbeddingResponse', 'IsolationSource', 'LlmConversationBuilder',
                'LlmFailoverBuilder', 'LlmMockBuilder', 'StreamingPhysics', 'ToolUse', 'TurnBuilder', 'Usage']
        },
        {typings: 'llmTypes.d.ts', name: 'LlmMockBuilder', build: function () { return client().llm.llmMock('/x'); }},
        {typings: 'llmTypes.d.ts', name: 'Completion', build: function () { return client().llm.completion(); }},
        {typings: 'llmTypes.d.ts', name: 'ToolUse', build: function () { return client().llm.toolUse('t'); }},
        {typings: 'llmTypes.d.ts', name: 'Usage', build: function () { return client().llm.usage(); }},
        {typings: 'llmTypes.d.ts', name: 'StreamingPhysics', build: function () { return client().llm.streamingPhysics(); }},
        {typings: 'llmTypes.d.ts', name: 'EmbeddingResponse', build: function () { return client().llm.embedding(); }},
        {typings: 'llmTypes.d.ts', name: 'IsolationSource', build: function () { return client().llm.header('x'); }},
        {typings: 'llmTypes.d.ts', name: 'LlmConversationBuilder', build: function () { return client().llm.conversation(); }},
        {
            typings: 'llmTypes.d.ts', name: 'TurnBuilder', build: function () { return client().llm.conversation().turn(); },
            // the turn's state, read by the conversation that builds it
            acceptedUndeclared: ['chaos', 'completion', 'containsToolResultFor', 'latestMessageContains', 'latestMessageMatches',
                'latestMessageRole', 'normalization', 'semanticMatchAgainst', 'turnIndex']
        },
        {typings: 'llmTypes.d.ts', name: 'LlmFailoverBuilder', build: function () { return client().llm.llmFailover(); }},
        {typings: 'mcpMockBuilder.d.ts', name: 'McpMockBuilder', build: function () { return client().mcpMock('/m'); }},
        {typings: 'mcpMockBuilder.d.ts', name: 'McpToolBuilder', build: function () { return client().mcpMock('/m').withTool('t'); }},
        {typings: 'mcpMockBuilder.d.ts', name: 'McpResourceBuilder', build: function () { return client().mcpMock('/m').withResource('r'); }},
        {typings: 'mcpMockBuilder.d.ts', name: 'McpPromptBuilder', build: function () { return client().mcpMock('/m').withPrompt('p'); }},
        {typings: 'a2aMockBuilder.d.ts', name: 'A2aMockBuilder', build: function () { return client().a2aMock('/a'); }},
        {typings: 'a2aMockBuilder.d.ts', name: 'A2aSkillBuilder', build: function () { return client().a2aMock('/a').withSkill('s'); }},
        {typings: 'a2aMockBuilder.d.ts', name: 'A2aTaskHandlerBuilder', build: function () { return client().a2aMock('/a').onTaskSend(); }}
    ]
});
