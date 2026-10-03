# LLM Provider Wire-Shape Fixes

## Outcome

Every `httpLlmResponse` provider should return what the real provider API returns, and accept
what real SDKs send, so client libraries, agent frameworks and gateways work against a mock
without special cases. GitHub discussion
[#2757](https://github.com/mock-server/mockserver-monorepo/discussions/2757) found that
`BEDROCK` returned Anthropic's Messages shape where the docs promised the AWS Converse API. An
audit of every provider on 2026-10-03 then found the same class of bug elsewhere. The items
below are worked in batches, most SDK-breaking first. The release is **not** gated on finishing
this plan: each item ships when it lands. When every item is closed this file is deleted in the
same commit.

## Why this class of bug got through

```mermaid
flowchart LR
    C["Codec emits a shape"] --> G["Golden files and tests\nrecorded from the codec"]
    G --> P["Tests pass"]
    R["Real provider API"] -. "never compared" .-> G
```

The codec tests derive their expected values from the codec itself. Examples:
`shouldEncodeCanonicalTokenUsageCounts` checks only non-streaming bodies and excludes the
OpenAI-compatible aliases, and the Responses tests set `call_id` equal to `id`. Item 16 closes
this gap.

## What remains

| # | Item | Batch |
|---|---|---|
| 4 | Embeddings: per-provider input fields and one vector per input | 1, after item 1 lands |
| 5 | Proxied streaming LLM calls are never costed | 2 |
| 6 | Gemini thinking tokens counted wrongly | 2 |
| 7 | Ollama `done_reason` missing | 3 |
| 8 | OpenAI-compatible providers drop provider-specific fields | 3 |
| 9 | Anthropic and Bedrock stop reasons not mapped | 3 |
| 10 | Rerank ignores request `top_n`/`top_k`; Cohere v2 path unknown | 3 |
| 11 | Request-decode gaps leave conversation matchers blind | 3 |
| 12 | Bedrock follow-ups from item 1's review | 3 |
| 13 | Dashboard LLM traffic parsing misses real paths and streams | 4 |
| 14 | Suspected shape gaps to verify | 5 |
| 15 | Azure GA path detected as OpenAI | 5 |
| 16 | Contract tests against recorded real-provider responses | 5 |
| 17 | Streamed Responses turns are not stored, so they cannot be chained or retrieved | 2 |
| 18 | Codecs other than Responses ignore a configured `ToolUse.id` | 3 |
| 19 | `OpenAiResponsesStore` has no byte bound | 5 |

## Items

| # | Problem | Fix |
|---|---|---|
| 1 | **Closed.** Converse and ConverseStream shapes ship, with the request picking the shape. Evidence: `BedrockConverseCodecTest`, the `bedrock-converse` golden files, `LlmCodecStructuralContractTest.shouldEncodeBedrockConverseStructureOnConversePaths`, and the netty `LlmAgentLoopE2eTest` Converse tests, which include the discussion's exact expectation. `BedrockCodec` delegated to `AnthropicCodec`, so `/model/{model}/converse` returned Anthropic's Messages body (no `usage.totalTokens`, no `output.message`), and Converse requests decoded to an empty conversation. | A Converse codec chosen per request by path (body shape on other paths), raw-JSON ConverseStream event-stream frames, Converse request decode, and Converse response parsing in `BedrockLlmClient`. `/invoke` is unchanged. |
| 2 | **Closed.** Every `function_call` carries a `call_id` distinct from its `fc_…` id (the configured `ToolUse.id` when set, else a generated `call_…`; the other codecs still ignore a configured id, item 18), every streamed event a `sequence_number`, and `response.created`/`.in_progress`/`.completed` the full Response object with `output`; arguments stream as `function_call_arguments` delta/done, and decode and the `previous_response_id` store link results by `call_id`. Evidence: `OpenAiResponsesCodecWireShapeTest`, the Responses assertions in `LlmCodecStructuralContractTest`, `OpenAiResponsesStateTest` (issued and configured `call_id` chaining) and the netty `OpenAiResponsesAgentLoopEndToEndTest` (Agents SDK loop, non-streaming, streaming and chained). Before: `OpenAiResponsesCodec` emitted `function_call` items without `call_id` and no `function_call_arguments` events, and `response.completed` carried no `output` or `sequence_number`. Decode keyed the call by `id` and the result by `call_id`, so `containsToolResultFor` never matched an unchained turn. The OpenAI Agents SDK reads its final turn from `response.completed`, so its loop ended. | Emit the full Responses object and event set with a distinct `call_id`, and link tool results by `call_id`. |
| 3 | **Closed.** With `stream_options.include_usage: true` the stream ends with a `choices: []` usage chunk before `[DONE]` and every other chunk carries `"usage": null`; without it no chunk has `usage`, as from the real API. Evidence: `OpenAiChatCompletionsCodecStreamingTest`, the `openai/streaming-text-include-usage.jsonl` golden, `LlmCodecGoldenFileTest.shouldEncodeStreamingUsageChunkForOpenAiChatFamilyWhenIncludeUsageRequested` (OpenAI, Azure and all six aliases, streaming and non-streaming counts), and the netty `LlmAgentLoopE2eTest` include_usage tests. `OpenAiChatCompletionsCodec` streaming never sent usage, even with `stream_options.include_usage`. This affected OPENAI, AZURE_OPENAI and the six OpenAI-compatible aliases. Token counts read zero in the Vercel AI SDK, LangChain `stream_usage`, LiteLLM and Langfuse. | With `include_usage`, send the final usage chunk (`choices: []`) before `[DONE]`. |
| 4 | `HttpLlmResponseActionHandler.extractInputFromRequest` reads only a top-level `input`. Gemini `content.parts`, Titan `inputText`, Cohere `texts[]` and Ollama `prompt` therefore become `""`, so every document gets the same vector. Every codec emits one vector even for array input. Also: OpenAI `model` is hard-coded, Cohere-on-Bedrock lacks `id`/`response_type`/`texts` and ignores `embedding_types`, `startsWith("cohere")` misses region-prefixed IDs, Titan v2 lacks `embeddingsByType`, and Gemini `batchEmbedContents` is unsupported. | Read each provider's input field and return one vector per input in each provider's real shape. |
| 5 | `emitForwardGenAiSpan` parses a forwarded LLM response with a single JSON read that throws on SSE, and the throw is swallowed. Ollama NDJSON reads only the first line. Streaming agents (coding CLIs always stream) record zero tokens and zero cost, so `llmCostBudgetUsd` never trips, although the docs say it applies on every forward path. | Parse each provider's streamed usage (final SSE event, Ollama `done:true` line). |
| 6 | `GeminiCodec` sets `candidatesTokenCount` to all output and adds `thoughtsTokenCount` on top, so clients that add the two overcount. `GeminiLlmClient` ignores thoughts, so proxied Gemini 2.5 cost is undercounted. | Split output into candidates plus thoughts on encode; count thoughts as output on parse. |
| 7 | `OllamaCodec` never writes `done_reason`, so a `stopReason` such as `length` cannot be tested. MockServer's own `OllamaLlmClient` reads it. | Emit `done_reason` on the final message. |
| 8 | `OpenAiCompatibleChatCodec` delegates everything to the OpenAI codec. Some of these providers also stream usage without `stream_options.include_usage` (Vercel's `@ai-sdk/mistral` reads streamed usage but never sends the opt-in; OpenRouter reportedly does the same), while MockServer applies OpenAI's opt-in to all of them. DeepSeek `reasoning_content` and cache-hit usage, Groq `reasoning`, timing usage and `x_groq`, and the xAI and OpenRouter extras are dropped. The docs say these providers produce identical bodies. | Per-provider additions over the OpenAI shape; correct the docs. |
| 9 | `AnthropicCodec` passes `stopReason` through verbatim, so a shared expectation using `stop`, `length` or `tool_calls` produces invalid Anthropic values. | Map to `end_turn`, `max_tokens`, `stop_sequence`, `tool_use` and so on, as the OpenAI and Gemini codecs already normalise. |
| 10 | Rerank applies only the expectation's `topN`, ignoring the request's Cohere `top_n` and Voyage `top_k`. Voyage ignores `return_documents` and hard-codes the model. Cohere's current `/v2/rerank` is unknown to the docs and `ProviderDetector`. | Honour the request fields; add the v2 path. |
| 11 | Gemini decode ignores `systemInstruction`. The OpenAI Chat decoder maps the `developer` role to user (the Responses decoder's `developer` role and `instructions` were fixed with item 2, and the Responses codec now uses a configured `ToolUse.id` as the `call_id`; the other codecs still generate their tool-call ids). Ollama `/api/generate` (`prompt` in, `response` out) decodes to an empty conversation. | Decode each into the conversation model. |
| 12 | From item 1's review: on Converse paths, the chaos content-filter block returns the Anthropic refusal body. Chaos `errorStatus` and structured-output enforcement errors use the Anthropic `{"type":"error"}` envelope for BEDROCK on both APIs (`LlmErrorBodies.bodyFor`). | A Converse refusal (HTTP 200, `stopReason` `content_filtered` or `guardrail_intervened`) and AWS error envelopes, by passing the request into `chaosErrorResponseOrNull`. |
| 13 | `mockserver-ui/src/lib/llmTraffic.ts`: `isBedrockPath` matches only `/model/anthropic.*/invoke`, missing Converse, other models and region-prefixed IDs. `parseOllamaRequest` parses streamed NDJSON as one JSON value and fails. `isGeminiPath` lacks the Vertex `/publishers/google/models/` path that `ProviderDetector` knows. | Match the server's detection; parse NDJSON streams. |
| 14 | Not yet confirmed: Gemini `streamGenerateContent` without `alt=sse` returns a JSON array, but MockServer always sends SSE. Strict typed SDKs (Rust async-openai, openai-go) may reject Responses bodies missing `parallel_tool_calls`, `tool_choice`, `tools` or `annotations` (all now emitted, with item 2; still to confirm against those SDKs). Groq streaming usage may arrive as `x_groq.usage`. OpenRouter adds `provider`, `reasoning` and SSE comments. | Confirm each against official docs or a recorded response, then fix or drop. |
| 15 | Azure's GA `/openai/v1/chat/completions` path and `*.openai.azure.com` hosts are detected as OPENAI, not AZURE_OPENAI, which affects pricing. Suspected, not confirmed. | Confirm, then extend `ProviderDetector`. |
| 16 | Nothing compares a codec's output with what the real provider sends. | Contract tests against recorded, redacted real-provider requests and responses per provider, streaming included, alongside the golden files (see [llm-codec-fixtures.md](../code/llm-codec-fixtures.md)). |
| 17 | A streamed `OPENAI_RESPONSES` turn is never recorded in `OpenAiResponsesStore`: `HttpLlmResponseActionHandler.handleStreaming` returns the events without calling `recordOpenAiResponsesStateIfApplicable`, which only the non-streaming path does. A streaming client that chains with `previous_response_id` and sends only the `function_call_output` therefore gets no match on `whenContainsToolResultFor`, and `GET /v1/responses/{id}` does not find a streamed response. | Record the streamed turn from the response object of its `response.completed` event, honouring `store:false`. |
| 18 | Only the Responses codec uses a user-set `ToolUse.id` (as the `call_id`, item 2). Every other codec ignores it and generates its own tool-call id, so a test cannot fix the id its client sends back with the tool result. | Use the configured `ToolUse.id` as the tool-call id in each provider that has one, and generate an id only when it is unset. |
| 19 | `OpenAiResponsesStore` is bounded by count only (10,000 responses, least recently used evicted first). Since item 2 each stored turn also holds the request's echoed `tools`, `instructions` and `metadata`, so a long run that sends large tool definitions can retain far more heap than the count suggests (see [memory-management.md](../code/memory-management.md#openai-responses-store)). | Add a byte budget next to the count cap, evicting least recently used first. |
