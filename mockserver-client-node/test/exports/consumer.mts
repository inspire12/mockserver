// An ES module consumer: each path in the exports map that has typings, imported by the package's
// name with and without its extension, must have the typings of the file it names. Compiled under
// node16 and under bundler resolution by `npm run typecheck`.
import { mockServerClient } from 'mockserver-client';
import { llmMock, completion } from 'mockserver-client/llm';
import * as root from 'mockserver-client';
import * as index from 'mockserver-client/index';
import * as indexJs from 'mockserver-client/index.js';
import * as llmByName from 'mockserver-client/llm';
import * as llmJs from 'mockserver-client/llm.js';
import * as mcp from 'mockserver-client/mcpMockBuilder';
import * as mcpJs from 'mockserver-client/mcpMockBuilder.js';
import * as a2a from 'mockserver-client/a2aMockBuilder';
import * as a2aJs from 'mockserver-client/a2aMockBuilder.js';
import * as client from 'mockserver-client/mockServerClient';
import * as clientJs from 'mockserver-client/mockServerClient.js';
import * as setup from 'mockserver-client/setupMockServer';
import * as setupJs from 'mockserver-client/setupMockServer.js';
import type { LlmMockBuilder } from 'mockserver-client/llmTypes';
import type { LlmMockBuilder as LlmMockBuilderJs } from 'mockserver-client/llmTypes.js';
import type { Expectation } from 'mockserver-client/mockServer';
import type { Expectation as ExpectationJs } from 'mockserver-client/mockServer.js';
import type * as indexFile from '../../index.js';
import type * as llmFile from '../../llm.js';
import type * as mcpFile from '../../mcpMockBuilder.js';
import type * as a2aFile from '../../a2aMockBuilder.js';
import type * as clientFile from '../../mockServerClient.js';
import type * as setupFile from '../../setupMockServer.js';
import type * as llmTypesFile from '../../llmTypes.js';
import type * as mockServerFile from '../../mockServer.js';

type Same<A, B> = 0 extends (1 & A) ? false : [A] extends [B] ? ([B] extends [A] ? true : false) : false;

export const checks: true[] = [
    true as Same<typeof root, typeof indexFile>,
    true as Same<typeof index, typeof indexFile>,
    true as Same<typeof indexJs, typeof indexFile>,
    true as Same<typeof llmByName, typeof llmFile>,
    true as Same<typeof llmJs, typeof llmFile>,
    true as Same<typeof mcp, typeof mcpFile>,
    true as Same<typeof mcpJs, typeof mcpFile>,
    true as Same<typeof a2a, typeof a2aFile>,
    true as Same<typeof a2aJs, typeof a2aFile>,
    true as Same<typeof client, typeof clientFile>,
    true as Same<typeof clientJs, typeof clientFile>,
    true as Same<typeof setup, typeof setupFile>,
    true as Same<typeof setupJs, typeof setupFile>,
    true as Same<LlmMockBuilder, llmTypesFile.LlmMockBuilder>,
    true as Same<LlmMockBuilderJs, llmTypesFile.LlmMockBuilder>,
    true as Same<Expectation, mockServerFile.Expectation>,
    true as Same<ExpectationJs, mockServerFile.Expectation>
];

export const expectation: Expectation = llmMock('/v1/messages').respondingWith(completion().withText('hi')).build();
export const built: Promise<unknown> = mockServerClient('localhost', 1080).mockAnyResponse(expectation);
