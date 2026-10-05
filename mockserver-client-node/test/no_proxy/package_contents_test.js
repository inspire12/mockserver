'use strict';

/*
 * The published mockserver-client tarball holds every file its package.json, typings and modules
 * point at, and the functions and values its typings export exist in its modules. The checks are
 * the launcher's.
 */

var path = require('path');

var REPO_ROOT = path.resolve(__dirname, '..', '..', '..');
var packageContents = require(path.join(REPO_ROOT, 'mockserver-node', 'test', 'packageContents.js'));

packageContents.registerTests(path.join(REPO_ROOT, 'mockserver-client-node'), {
    // mockServerClient.js also exports the three builders the index gets from their own modules,
    // and three internals for this package's tests
    acceptedUndeclaredExports: {
        'mockServerClient.d.ts': ['llm', 'mcpMock', 'a2aMock',
            'routeBreakpointMessage', 'extractBreakpointHeaders', 'NON_HTTP_RESPONSE_ACTION_KEYS']
    }
});
