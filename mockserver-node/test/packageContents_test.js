/*
 * The published mockserver-node tarball holds every file its package.json, its typings and its own
 * modules point at. See packageContents.js for the checks.
 */
'use strict';

const test = require('node:test');
const assert = require('node:assert');
const path = require('path');

const packageContents = require('./packageContents');

const PACKAGE_ROOT = path.join(__dirname, '..');

packageContents.registerTests(PACKAGE_ROOT);

test('the Grunt task that grunt.loadNpmTasks(\'mockserver-node\') loads is in the published tarball', function () {
  assert.ok(
    packageContents.packedFiles(PACKAGE_ROOT).has('tasks/mockServer.js'),
    'tasks/mockServer.js is not selected by the "files" list');
});
