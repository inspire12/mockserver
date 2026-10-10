// A CommonJS consumer under node16 resolution: each path in the launcher's exports map that has
// typings, required by the package's name with and without its extension, has its file's typings.
import root = require('mockserver-node');
import index = require('mockserver-node/index');
import indexJs = require('mockserver-node/index.js');
import binary = require('mockserver-node/downloadBinary');
import binaryJs = require('mockserver-node/downloadBinary.js');
import indexFile = require('../../index');
import binaryFile = require('../../downloadBinary');

type Same<A, B> = 0 extends (1 & A) ? false : [A] extends [B] ? ([B] extends [A] ? true : false) : false;

export const checks: true[] = [
    true as Same<typeof root, typeof indexFile>,
    true as Same<typeof index, typeof indexFile>,
    true as Same<typeof indexJs, typeof indexFile>,
    true as Same<typeof binary, typeof binaryFile>,
    true as Same<typeof binaryJs, typeof binaryFile>
];

export const started: Promise<unknown> = root.start_mockserver({serverPort: 1080});
