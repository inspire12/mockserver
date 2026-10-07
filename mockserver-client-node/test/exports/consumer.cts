// A CommonJS consumer under node16 resolution: each path in the exports map that has typings,
// required by the package's name with and without its extension, has the typings of its file.
import root = require('mockserver-client');
import index = require('mockserver-client/index');
import indexJs = require('mockserver-client/index.js');
import llmByName = require('mockserver-client/llm');
import llmJs = require('mockserver-client/llm.js');
import mcp = require('mockserver-client/mcpMockBuilder');
import mcpJs = require('mockserver-client/mcpMockBuilder.js');
import a2a = require('mockserver-client/a2aMockBuilder');
import a2aJs = require('mockserver-client/a2aMockBuilder.js');
import client = require('mockserver-client/mockServerClient');
import clientJs = require('mockserver-client/mockServerClient.js');
import setup = require('mockserver-client/setupMockServer');
import setupJs = require('mockserver-client/setupMockServer.js');
import type { LlmMockBuilder } from 'mockserver-client/llmTypes';
import type { LlmMockBuilder as LlmMockBuilderJs } from 'mockserver-client/llmTypes.js';
import type { Expectation } from 'mockserver-client/mockServer';
import type { Expectation as ExpectationJs } from 'mockserver-client/mockServer.js';
import indexFile = require('../../index');
import llmFile = require('../../llm');
import mcpFile = require('../../mcpMockBuilder');
import a2aFile = require('../../a2aMockBuilder');
import clientFile = require('../../mockServerClient');
import setupFile = require('../../setupMockServer');
import type * as llmTypesFile from '../../llmTypes';
import type * as mockServerFile from '../../mockServer';

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

export const expectation: Expectation = llmByName.llmMock('/v1/messages').respondingWith(llmByName.completion().withText('hi')).build();
export const built: Promise<unknown> = root.mockServerClient('localhost', 1080).mockAnyResponse(expectation);
