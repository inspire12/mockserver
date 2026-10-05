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
    // llm.d.ts declares `export default llm` while llm.js assigns the object to `module.exports`.
    // It is right through the package's index, which re-exports it as `llm`, and wrong only for
    // the deep import `mockserver-client/llm`. It is published, so changing it is a breaking
    // change to the typings.
    acceptedDefaultExports: ['llm.d.ts']
});
