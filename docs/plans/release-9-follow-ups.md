# Release 9.0.0 Follow-ups

**Outcome.** The smaller follow-ups found while fixing the dashboard end-to-end sweep, plus the
release-time step for the 9.0.0 security advisory. Each row is a self-contained change. This file
is deleted once every row is closed. The Netty pull request and issue (backlog rows 91a and 540a)
stay in [performance-measurement-backlog.md](performance-measurement-backlog.md) for after 9.0.0.

## What remains

| # | Item | Blocked on |
|---|---|---|
| F1 | `McpToolTargetPrivateNetworkIntegrationTest` fails 3 tests on a developer Mac (also on master) but passes in CI | a reproduction and fix (§2) |
| F2 | OpenAPI spec fetchers other than the import path still run a blocking fetch on the event loop | a change (§2) |
| F3 | With HTTP/1.1 pipelining, a later exchange's end on the same connection can release an SSE response still streaming, so `stop()` may cut it off | a change (§2) |
| F4 | gRPC server streams have only a unit test for in-flight release at shutdown; no end-to-end test | a test (§2) |
| F5 | The perf freshness check ignores a newer manual `[perf-run]` build whose measurement step failed, instead of counting it as failed | a CI change, owner approval (§2) |
| F6 | Publish security advisory GHSA-jg86-hv4g-8h78 (`forwardProxyBlockPrivateNetworks` bypass, fixed in 9.0.0) | the 9.0.0 release (§2) |

## Detail

| # | Item | Context | Next |
|---|---|---|---|
| F1 | `McpToolTargetPrivateNetworkIntegrationTest` fails 3 tests on a developer Mac (also on master) but passes in CI | Found 2026-10-10 while verifying the e2e fix batches. The three `...IsRefusedALoopbackTargetOnlyWithTheSettingOn` tests expect one "blocked by SSRF policy" warning and capture none; the refusal itself works. It fails when the class runs alone on master, so it is not from a recent change. A local-environment difference (likely how the Mac resolves `localhost`, or a VPN interface) is the suspect. | Find why no warning is captured locally; fix the test or the code so it passes on macOS too, without weakening it. |
| F2 | OpenAPI spec fetchers other than the import path still run a blocking fetch on the event loop | Found 2026-10-10 reviewing the library fix (E2E-LIB-14), which moved the dashboard/REST OpenAPI URL import off the event loop after a self-served spec stalled about one import in six for ~60 s. Other callers of the spec fetch (for example `PUT /mockserver/openapi` with a URL, `loadScenario/generateFromOpenAPI`, MCP tools) may still fetch on an event-loop thread. | List every caller of the spec fetch, move each blocking fetch off the event loop, and add a single-event-loop test for each that fetches a spec served by the same MockServer. |
| F3 | With HTTP/1.1 pipelining, a later exchange's end on the same connection can release an SSE response still streaming, so `stop()` may cut it off | Found 2026-10-10 reviewing the direct-write in-flight release fix (c955e2e34). The release is keyed to the connection's exchange-ended event, so a pipelined request that ends after an SSE stream starts releases the SSE's slot early. The worst case equals the behaviour before that fix. | Key the release to the exchange that owns the stream, with a pipelining test. |
| F4 | gRPC server streams have only a unit test for in-flight release at shutdown; no end-to-end test | Found 2026-10-10 reviewing c955e2e34: SSE, WebSocket and LLM streams have integration coverage that `stop()` does not wait out `stopDrainMillis`, gRPC streams only an `EmbeddedChannel` unit test. | Add an integration test: a gRPC server-streaming mock on a kept-alive HTTP/2 connection, then `stop()` returns well under `stopDrainMillis`, and a stream still being written is waited for. |
| F5 | The perf freshness check ignores a newer manual `[perf-run]` build whose measurement step failed, instead of counting it as failed | Found 2026-10-10 reviewing 2fb303255 (freshness check accepts a measured manual run). A manual build whose `perf-run` step failed skips compare, so it is not counted; that matches how manual builds were always treated, but it means a failing manual run never turns the check red. | Count a manual `[perf-run]` master build whose measurement steps failed as a failed run; extend the selection tests. Control change: owner approval before commit. |
| F6 | Publish security advisory GHSA-jg86-hv4g-8h78 (`forwardProxyBlockPrivateNetworks` bypass, fixed in 9.0.0) | The draft advisory is prepared: CVSS 3.1 `AV:N/AC:H/PR:N/UI:N/S:C/C:L/I:N/A:N` (4.0 Medium), CWE-918, affected `org.mock-server:mockserver-netty` `< 9.0.0`, patched `9.0.0`; the fix is d0e0c521b. | When 9.0.0 is published, confirm the patched version, request a CVE if wanted, and publish the advisory (owner approval). |
