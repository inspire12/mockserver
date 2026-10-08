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
| 172e | An upgraded binary relay's upstream leg may be closed, not half-closed, after its close_notify | a probe with a TLS upstream that sends after the client leaves, then a decision (§5) |
| 294d | Composer: kept action fields are invisible, cannot be cleared, and are missing from snippets | edit fidelity for kept fields (§5) |

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
| 172e | An upgraded binary relay's upstream leg may be closed, not half-closed, after its close_notify | From 172d. When the client leaves after MockServer's TLS handshake with the upstream, BinaryRelay.endUpstream calls SslHandler.closeOutbound and RelayLegClose.afterWritten. In Netty 4.2.18 closeOutbound calls safeClose, which, with closeNotifyReadTimeout at 0 (the default), closes the channel through the pipeline once the close_notify is flushed, or after closeNotifyFlushTimeout (3 s) if it is not (read from the bytecode, not tested). If so the leg is closed rather than its output ended, so docs/code/netty-pipeline.md (End of the session row) overstates it, and an upstream that is still sending when the client leaves may be answered with a reset; a clear relay half-closes. The 3 s flush bound is also why the afterWritten mutant in 172d survives. | Probe with a TLS upstream that sends after receiving the close_notify (does it get a reset?); if so, decide whether the TLS leg should stay readable until the upstream closes (for example a closeNotifyReadTimeout, or ending the output without SslHandler's close), or correct the doc. |
| 294d | Composer: kept action fields are invisible, cannot be cleared, and are missing from snippets | Found while fixing 294c. Saving an edited mock now keeps untouched action fields (delay, primary, extra nested fields), and the JSON and curl tabs show them. The Java tab builds the action from the form's state, not from the merged JSON, so it leaves out kept fields for every action type except binaryResponse. The JSON-driven targets (Node, Python, Go, C#, Ruby, Rust) render action-level delay and primary unevenly from one action type to another. Kept fields on actions other than binaryResponse are not shown in the form, so a user cannot see or clear them there. Arrays the form edits (WebSocket messages, SSE events, gRPC messages) are replaced whole when changed, which drops per-item fields the form does not show (for example a per-message delay). | Drive each target's action rendering from the merged JSON, or add a NOTE naming the fields it cannot show. List the kept action fields in the form, as the binary response note does, with a way to clear them. Merge edited arrays item by item, or show the per-item fields. Add a per-action, per-language test that delay and primary appear. One or two UI units. |
