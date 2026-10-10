// An ES module consumer: each path in the launcher's exports map that has typings, imported by the
// package's name with and without its extension, must have the typings of the file it names.
// Compiled under node16 and bundler resolution by the client's `npm run typecheck`, as the
// launcher has no TypeScript of its own.
import * as root from 'mockserver-node';
import * as index from 'mockserver-node/index';
import * as indexJs from 'mockserver-node/index.js';
import * as binary from 'mockserver-node/downloadBinary';
import * as binaryJs from 'mockserver-node/downloadBinary.js';
import { ensureBinary } from 'mockserver-node/downloadBinary';
import type * as indexFile from '../../index.js';
import type * as binaryFile from '../../downloadBinary.js';

type Same<A, B> = 0 extends (1 & A) ? false : [A] extends [B] ? ([B] extends [A] ? true : false) : false;

export const checks: true[] = [
    true as Same<typeof root, typeof indexFile>,
    true as Same<typeof index, typeof indexFile>,
    true as Same<typeof indexJs, typeof indexFile>,
    true as Same<typeof binary, typeof binaryFile>,
    true as Same<typeof binaryJs, typeof binaryFile>
];

export const binaryPath: Promise<string> = ensureBinary('9.0.0');
