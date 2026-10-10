/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

import * as types from './llmTypes';

/*
 * What `require('mockserver-client/llm')` returns. llm.js assigns an object with these members to
 * `module.exports`, as the package's other modules do, so they are named exports and there is no
 * default export. They are declared with `let` because a member of that object can be assigned,
 * as a test that stubs a factory does.
 */

export { Delay, Llm } from './llmTypes';

export type Provider = types.Provider;
export declare let Provider: types.Llm['Provider'];
export type Role = types.Role;
export declare let Role: types.Llm['Role'];

/** The factories, each typed by the member of `Llm` with its name. */
export declare let llmMock: types.Llm['llmMock'];
export declare let conversation: types.Llm['conversation'];
export declare let llmFailover: types.Llm['llmFailover'];

export declare let completion: types.Llm['completion'];
export declare let toolUse: types.Llm['toolUse'];
export declare let usage: types.Llm['usage'];
export declare let inputTokens: types.Llm['inputTokens'];
export declare let outputTokens: types.Llm['outputTokens'];
export declare let streamingPhysics: types.Llm['streamingPhysics'];
export declare let tokensPerSecond: types.Llm['tokensPerSecond'];
export declare let jitter: types.Llm['jitter'];
export declare let timeToFirstToken: types.Llm['timeToFirstToken'];
export declare let embedding: types.Llm['embedding'];

export declare let header: types.Llm['header'];
export declare let queryParameter: types.Llm['queryParameter'];
export declare let cookie: types.Llm['cookie'];

export declare let defaultErrorBody: types.Llm['defaultErrorBody'];

/*
 * The builders and models. Each name is the constructor llm.js exports and the type it builds. The
 * type is an interface, not an alias, so that a module augmentation of this module can still add
 * to it; it extends the one in llmTypes.d.ts, which is what the factories and the builders' own
 * methods return.
 */
export interface Completion extends types.Completion {}
export declare let Completion: new () => Completion;
export interface ToolUse extends types.ToolUse {}
export declare let ToolUse: new (name: string) => ToolUse;
export interface Usage extends types.Usage {}
export declare let Usage: new () => Usage;
export interface StreamingPhysics extends types.StreamingPhysics {}
export declare let StreamingPhysics: new () => StreamingPhysics;
export interface EmbeddingResponse extends types.EmbeddingResponse {}
export declare let EmbeddingResponse: new () => EmbeddingResponse;
export interface LlmMockBuilder extends types.LlmMockBuilder {}
export declare let LlmMockBuilder: new (path: string) => LlmMockBuilder;
export interface LlmConversationBuilder extends types.LlmConversationBuilder {}
export declare let LlmConversationBuilder: new () => LlmConversationBuilder;
export interface LlmFailoverBuilder extends types.LlmFailoverBuilder {}
export declare let LlmFailoverBuilder: new () => LlmFailoverBuilder;
export interface TurnBuilder extends types.TurnBuilder {}
export declare let TurnBuilder: new (parent: LlmConversationBuilder) => TurnBuilder;
export interface IsolationSource extends types.IsolationSource {}
export declare let IsolationSource: new (kind: string, name: string) => IsolationSource;
