// llm.js assigns an object of named members to `module.exports`, with no `default`. This file
// compiles only while llm.d.ts declares that module, and index.d.ts the same object as `llm`
// with the builder names as types alone.
import llmModule = require('../llm');
import {llmMock, Completion as CompletionFromModule} from '../llm';
import {llm, Completion, Llm, Provider, ToolUse} from '../index';

const everyMemberOfLlmIsExported: Llm = llmModule;
const sameObject: typeof llmModule = llm;
const moduleAsTheIndexTypesIt: typeof llm = llmModule;
const byName: Llm['llmMock'] = llmMock;

// @ts-expect-error llm.js has no `default` property
const noDefault = llmModule.default;

// @ts-expect-error through the index `llm` is a value, not a namespace of types
const notANamespace: llm.Completion | undefined = undefined;

// a member can be replaced, as a test that stubs a factory does: through the index and on the module
const original = llm.completion;
llm.completion = () => original().withText('stubbed');
llm.Completion = llmModule.Completion;
llm.Provider = llmModule.Provider;
llmModule.completion = original;
llmModule.Usage = llm.Usage;

// each builder name in llm.d.ts is an interface, which an augmentation of the module can add to
declare module '../llm' {
    interface Completion { augmented?: true }
    interface ToolUse { augmented?: true }
    interface Usage { augmented?: true }
    interface StreamingPhysics { augmented?: true }
    interface EmbeddingResponse { augmented?: true }
    interface LlmMockBuilder { augmented?: true }
    interface LlmConversationBuilder { augmented?: true }
    interface LlmFailoverBuilder { augmented?: true }
    interface TurnBuilder { augmented?: true }
    interface IsolationSource { augmented?: true }
}
const augmented: true | undefined = new llmModule.Usage().augmented;

// through the index `llm` is typed with the `Llm` interface, so a member an augmentation adds shows on it
declare module '../index' {
    interface Llm { addedByAugmentation?: () => void }
}
const added: (() => void) | undefined = llm.addedByAugmentation;

// each constructor is declared as one, and builds the type exported under its name
const completion: Completion = new llmModule.Completion().withText('typed');
const sameType: CompletionFromModule = new CompletionFromModule();
const toolFromModule: llmModule.ToolUse = new llmModule.ToolUse('search');
const usage: llmModule.Usage = new llmModule.Usage();
const physics: llmModule.StreamingPhysics = new llmModule.StreamingPhysics();
const embedding: llmModule.EmbeddingResponse = new llmModule.EmbeddingResponse();
const mock: llmModule.LlmMockBuilder = new llmModule.LlmMockBuilder('/v1/messages');
const conversation: llmModule.LlmConversationBuilder = new llmModule.LlmConversationBuilder();
const failover: llmModule.LlmFailoverBuilder = new llmModule.LlmFailoverBuilder();
const turn: llmModule.TurnBuilder = new llmModule.TurnBuilder(conversation);
const source: llmModule.IsolationSource = new llmModule.IsolationSource('header', 'x-session-id');
const provider: Provider = llmModule.Provider.OPENAI;
const delay: llmModule.Delay = llmModule.timeToFirstToken(100, 'MILLISECONDS');

// through the index a builder's name is a type alone: index.js has no `ToolUse`, so the name is
// free for a value, and what the type names is the interface the builders return
const {ToolUse} = llm;
const tool: ToolUse = new ToolUse('search');
const call: ToolUse = llm.toolUse('search');
