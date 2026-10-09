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
| 294n | Python, Go, .NET, Rust and PHP website tabs are not checked against the REST API tab | a check change (§5) |
| 294p | Python client's HttpRequest and HttpResponse raise AttributeError for headers given as a dict, as many website Python examples do | a client change (§5) |
| 294q | Decide crate-wide #[non_exhaustive] and #[serde(other)] for the Rust client's enums before 9.0.0 ships | an owner decision, then a client change (§5) |

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
| 294n | Python, Go, .NET, Rust and PHP website tabs are not checked against the REST API tab | Found closing 294k. Only the Ruby tabs are compared with the REST API tab; the Python, Go, .NET and Rust gaps fixed in 294k were found by hand. Running a Python or Go tab against a local server and comparing the active expectations with those the REST API tab leaves (the 294k harness did this) found only defaults differing (Python adds statusCode 200 and reason OK, Go adds a Content-Type for a JSON body). Some Python tabs still send what the REST tab does not (Expectation.from_dict turns keyMatchStyle into a header or parameter, so any other raw-JSON tab relying on it is wrong). | Extend the website example check, or add a sibling for Python (the client runs in-process, like Ruby) and Go, with the same allowlist rules; sweep the findings. |
| 294p | Python client's HttpRequest and HttpResponse raise AttributeError for headers given as a dict, as many website Python examples do | Found closing 294m. HttpResponse(status_code=302, headers={"Location": ["x"]}).to_dict() raises AttributeError: 'str' object has no attribute 'to_dict', because headers must be a list of KeyToMultiValue. The first typed Python example in creating_expectations.html (line 102) does this, and about 40 Python blocks across the site pass headers={...} (some of them to requests, not the client, so each needs checking). The Ruby client fixed the same gap in b3f360598 by accepting a Hash. | Make the Python HttpRequest and HttpResponse accept headers, query parameters and cookies as a dict (as the Ruby client does), with round-trip tests, then run every typed Python website example through the client. |
| 294q | Decide crate-wide #[non_exhaustive] and #[serde(other)] for the Rust client's enums before 9.0.0 ships | Found closing 294m. 294m made LoadThresholdMetric #[non_exhaustive] when it added CheckFailureRate (owner decision; 9.0.0 is major). No other enum in the crate is #[non_exhaustive], so adding a variant to any of them later breaks exhaustive matches, and none has a #[serde(other)] fallback, so a wire value the client does not know still fails deserialisation of the whole object. | Decide whether to mark the client's wire enums #[non_exhaustive] and give them an Unknown #[serde(other)] variant in 9.0.0, while a breaking change is allowed; apply it with tests that read an unknown value. |
