# Performance Programme

**The central question is settled.** The healthy serving ceiling is **60,000 rps offered
(57,149 served)** at 0.179 ms median; peak **59,905** (build 464, 2026-09-27, commit
`efdc5227b`, shipped ZGC default). Per repo convention, when the remaining items below are
closed this file is deleted in the same commit — it is not an archive.

The latency tail is closed: a steady 24k run (build 473, docker bridge, no per-rung ramp)
measured client p99 0.343 ms and p99.9 1.43 ms, with no server request over 5 ms in 7.25M, so
the 10–16 ms ladder tail was a rung-onset transient in the rig, not MockServer or the bridge.
The ladder now excludes each rung's first 3 s from its published percentiles.
Every tuning candidate from the original review is closed; what remains is the table below:
CI promotion counts and the §5 server-side memory and relay fixes. Rows whose only remaining
step is a measurement run on CI — including the multi-k6 headline switch's follow-ups (#44,
#164) — moved to
[performance-measurement-backlog.md](performance-measurement-backlog.md).

Published figures and rig measurement gates are in
[docs/code/performance-measurement.md](../code/performance-measurement.md). The GC-default
decision (ZGC shipped as `ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC"`) is in
[docs/infrastructure/docker.md](../infrastructure/docker.md) and
[docs/code/startup-performance.md](../code/startup-performance.md).

## What remains

| # | Item | Blocked on |
|---|---|---|
| 133 | Browser-test the dashboard changes in this release against the built server, if time allows before the release | a browser run against the release candidate (§5) |
| 172c | The WebSocket relay closes its upstream connection outright when the client leaves, which may drop frames it has just relayed | a probe over a slow upstream, then a decision (§5) |
| 294 | Binary expectations on a relayed connection: forward too, and discard or replace the upstream's reply | where an upstream reply ends, a design of its own (§5) |
| 188a | Message framing for binary protocols other than PostgreSQL | owner's choice of the next protocol (§5) |
| 296a | The netty unit-test fork still keeps about 45 stopped servers' event logs (about 290 MB) by its end | a change of its own, test code (§5) |

## Decided against

- **Splitting the netty integration tests across parallel JVMs** — fixed ports and shared static config make this high-risk.
- **Reusing `:maven: build` output for the deploy** — pipeline steps do not share a filesystem.
- **Lite, phased-backoff or batched publishes for the event-log ring** — the earlier rejection
  benchmarked 6 saturating producers, where the consumer never sleeps; below the ceiling it sleeps
  between entries and each request's first publish paid a futex wake. In that regime (local A/B,
  4-core SUT, `logLevel ERROR`, fixed-rate k6, 2–4 runs each at 20k rps) `LiteBlockingWaitStrategy` measured +1.0% CPU
  per request against `BlockingWaitStrategy`, `PhasedBackoffWaitStrategy.withLock` +20% to +64%
  (spinning, which also counts against a container CPU quota), and publishing a request's two
  entries together +6.7%, and batching would hide a delayed response's request from `verify`
  during the delay. Conditional signalling cannot help because the consumer really is asleep at
  almost every first publish; only waking it less often does, which is what landed as
  `CoalescingWakeWaitStrategy`.
- **Flushing stdout only at the end of an event-log batch** — the console handler serves every
  logger, so startup and error output from other threads could be delayed or lost.
- **A striped counter for the graceful-shutdown in-flight count** — `LongAdder.sum()` cannot keep
  the clamp-at-zero and drain invariants; the per-request allocations around it were removed instead.
- **Virtual threads for the local callback executor** — it exists to avoid a self-deadlock on
  recursive loopback callbacks; virtual threads pin on `synchronized` on the JDK 21 clustered image,
  which would reintroduce it.
- **Changing the worker event-loop count from 5** — on the 6-core perf server, 4, 8 and 12 loops peaked at
  59,578, 59,594 and 59,484 rps against 59,805 for the default 5 (builds 485, 483, 484, 482), within
  run-to-run noise, with the same median latency at every rate.
- **Compact object headers (`-XX:+UseCompactObjectHeaders`) in the JDK 25/26 images** — build 486 peaked at
  59,603 rps with the same median latency as the default-header runs (59,484–59,805, builds 482–485), and
  its end-of-growth heap low (438 MB) sat inside their 442–598 MB spread. A local workload also showed no
  live-set change, so the saving does not justify changing six images and the AppCDS archive training.
- **Disabling Netty leak detection in the images** — build 491 vs control 495 (same host, different image
  commit: 4b18a66 vs fa667fb): peak 57,324 vs 57,029 rps and every latency/memory metric within run-to-run
  noise (heap delta confounded by a different `maxLogEntries` default in 491's image); SIMPLE sampling costs
  nothing measurable and still reports leaks in production.
- **`-XX:InitialRAMPercentage=60` as an image default** — commits 60% of the container up front,
  raising idle memory for the many short-lived test containers; users with sustained load can set it.
- **`-XX:+UseStringDeduplication` as an image default** — per-connection header sharing saves 2–3 times as much retained heap per request over HTTP/1.1, and deduplication adds almost nothing on top of it or over HTTP/2 (see [memory-management.md](../code/memory-management.md#header-sharing-across-a-connections-requests)).
- **Array-indexed Prometheus metric handles (#28 candidate C)** — resolving each `Metrics.Name`'s gauge and
  `_total` counter from an array instead of a lock plus two map lookups saved ~4 ns per increment on one thread,
  but removed the lock that was spacing out Prometheus's contended CAS on the gauge value: with six threads
  incrementing the same counter, `MetricsIncrementBenchmark` went from 0.39 to 0.72 us per increment, and
  fixed-rate CPU per request did not improve. Disabling the exemplar sampler was also measured as no gain
  (5.33 vs 5.37 ns per gauge increment).

---

## §5 — Server-side memory bounds (added 2026-09-30)

The images were OOM-killed at `--memory=512m` under load. That is fixed at the image layer (a
static HEALTHCHECK probe instead of a second JVM, and a lower default heap percentage; see
[docker.md](../infrastructure/docker.md#heap-cap)). The investigation also found several server-side
gaps that let memory outside the heap grow with load; none caused the kill on its own, and each is a
design change, not a one-line fix. The table below holds the gaps still open; rows whose only
remaining step was a measurement run moved to
[performance-measurement-backlog.md](performance-measurement-backlog.md).

| # | Item | What is known | Next step |
|---|---|---|---|
| 133 | Browser-test the dashboard changes in this release against the built server, if time allows before the release | Asked for by the owner on 2026-10-04; not performance work, placed last. The dashboard changes in the unreleased changelog have unit and component tests (vitest) but have not been driven in a real browser against a running server: the live panels and the Follow toggle, the log-pressure banner, the `logLimit` control, the rendering and filter changes including the LLM Provider filter, the removed counts, and the failed-verification entry for an incomplete event log. The dashboard is bundled into the netty jar at build time, so the test must run against a jar built from the release commit, not a dev server Also check (from rows 333/216): with a >64 KiB body, Compare, Add to Diff Pool + Diff Selected, Create Mock, Why?/Generate Stub on an unmatched row, the search note + Search Full Bodies, an LLM row >64 KiB auto-loading in Traffic, Trace, LLM Optimise and MCP Health (and one over 4 Mi characters not auto-loading), on the Dashboard log a shortened unmatched entry's Why?, Generate Stub and Create Mock acting on the whole request, and the Composer binary response with empty data registering and its Python/Rust code tabs. | Build the netty jar from the release candidate, start it, and exercise each dashboard change in Chrome (the Claude in Chrome browser tools), recording a short capture and reading the console for errors; raise each defect found as its own item; optional before the release |
| 415a | Two write-stall integration tests pass a pipeline-close mutant on macOS | ResponseWriteStallTimeoutIntegrationTest.shouldEndAStalledTlsReadersSocketAsSoonAsItsStallIsCut and shouldCloseTheTunnelAtOnceForAReaderThatResumesJustAfterItsStallIsCut claim to guard the prompt end of a cut TLS connection, but on macOS loopback the peer's late ~16 KB segment (about 5 s into a stall) flushes the ~16 KB MockServer leaves queued plus the close_notify about 1.1 s after a 3 s cut, inside their 2 s bound, so they stay green with channel.close(). WriteStallTimeoutHandlerTlsCloseTest now pins the direct close deterministically. Not checked on Linux, where the peer's late window behaviour differs. | Decide whether to reword the two tests' comments and assertion messages so they do not claim to pin the direct close, or leave them as end-to-end smoke tests |
| 172c | The WebSocket relay closes its upstream connection outright when the client leaves, which may drop frames it has just relayed | From 172b (pp-utunnel). When the client's connection ends (a reset, an exception or a close), the WebSocket relay closes the upstream channel through its pipeline at once. If the upstream has sent bytes the relay has not read (it stops reading the upstream while the client takes nothing), that close sends a reset, and the kernel discards whatever the relay had just written upstream and not yet sent: the same mechanism as row 370. Over loopback the test upstream reads at once, so RelayAfterFailedWriteIntegrationTest sees no loss; a remote or slow upstream could lose the frames the client sent just before it left. The binary relay ends its upstream with RelayLegClose.afterFlush (a FIN, then a close when the upstream closes or after 5 s) and is not affected. Not measured. | Probe with an upstream that reads slowly (a small receive buffer, reading only after the client has left); if frames are lost, end the upstream as the binary relay does. |
| 294 | Binary expectations on a relayed connection: forward too, and discard or replace the upstream's reply | From item 186, slice M1 (design slice M5). Semantics B (answer locally, also forward, discard the reply) and C (forward, replace the reply) need to know where the upstream's reply to one message ends. Item 188 frames only what the client sends (binaryMessageFraming=POSTGRESQL); the upstream's bytes are still relayed as they arrive. For PostgreSQL that needs a backend framer (typed messages, except the single-byte S, N or G answers to SSLRequest and GSSENCRequest) and a rule for a reply's end that is the protocol's, not a message's: ReadyForQuery after a simple Query, but nothing until Sync for extended-query messages, and no reply at all for Flush, CopyData and others. Expressed as one optional enum on binaryResponse, across the core schema, OpenAPI, the editor schema, seven client libraries and the UI. | Design the reply-end rule on top of a backend PostgreSQL framer (only with binaryMessageFraming=POSTGRESQL), then 2 to 3 units: framer and rule, the enum through schema and clients, the UI. |
| 188a | Message framing for binary protocols other than PostgreSQL | Item 188 shipped binaryMessageFraming with RAW and POSTGRESQL (PostgresqlMessageFramer in place of BinaryMessageGatherer, bounded by maxRequestBodySize, in-band TLS looked for only at a message boundary). The property is per MockServer instance, not per port, and frames only the client's bytes. Candidates: MySQL (3-byte length plus sequence id, server speaks first), Redis RESP, Kafka (int32 length prefix), MongoDB wire protocol (int32 length), or a generic length-prefix description (offset, width, endianness, adjustment). | Pick the next protocol from demand; each named protocol is one framer class, one enum value, its tests and docs. Per-port framing only if one MockServer must serve two binary protocols. |
| 296a | The netty unit-test fork still keeps about 45 stopped servers' event logs (about 290 MB) by its end | Found closing 296 from a heap dump at the end of the fork (paths to GC roots, 47 retained `MockServerEventLog`s). 25 are kept by Mockito inline mocks of `HttpActionHandler` (processAction) and `Scheduler` (submit) whose last recorded invocation's arguments (a `NettyResponseWriter` and its logger) reach a stopped `HttpState`, the same self-reference row 296 fixed in two classes; dozens of netty test classes mock one of them into a real pipeline. 7 are kept by `DashboardWebSocketHandler` throttle executors whose `handlerRemoved` never ran, so their scheduled task and thread live on. 5 are event logs whose disruptor thread still runs (never stopped). 7 are static fields of test classes that keep a stopped `HttpState` or `MockServer` (PortUnificationHandlerDetectionTest, BinaryMessageBoundaryTest, ReadBufferSizeByProtocolTest, BinaryBackpressureAcrossTlsUpgradeTest, BinaryInBandTlsUpgradeEventTest, McpToolsAllProvidersTest x2); nulling them alone freed only 2, since the Mockito paths also hold them. In product code, `JsonSchemaExpectationValidator` and `JsonSchemaRequestDefinitionValidator` cache a static instance built with the first server's `MockServerLogger`, which holds that server's `HttpState`, and `LoadScenarioOrchestrator.lastRun` (seen in an earlier dump) keeps the last run's sender and so its server; each keeps one stopped server, not a growing number. | Release the Mockito pipeline mocks per class (clear each in `@After`, or `stubOnly` plus clear), shut the dashboard handlers' executors in the tests that never remove them, stop the 5 event logs, null the static fields; then make the two validator singletons and `lastRun` not hold a server. Re-measure the fork's end live heap the same way (target well under 100 MB). |
