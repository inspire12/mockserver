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
| 294k | REST API and other-language tabs the Ruby example check found wrong | a docs change (§5) |
| 294l | Conditional request example is rejected by MockServer in every tab; Ruby lacks conditional matchers and load-step checks | a client and docs change (§5) |

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
| 294k | REST API and other-language tabs the Ruby example check found wrong | Found closing 294j. The Ruby example check (mockserver-client-ruby/spec/website_examples) allowlists these REST API tab defects, so fixing each one makes its allowlist entry stale and the check fails until the entry is removed. Cannot be sent as written: forward_action_code_examples.html javascript_templated_forward, ..._strip_path_prefix, ..._with_delay and javascript_velocity_templated_forward build the template with Java-style string concatenation, which is not JSON; seven -d bodies hold single quotes the shell strips (forward_action response_template_forward and response_override_template_forward, response_action status_code_and_reason_phrase ("I'm a teapot"), stateful_scenarios three_step_order_flow, response_templates mustache, velocity and javascript basic templates); response_action file_store_api sends the raw file with ?name= where /mockserver/files/store needs name and content JSON; mockserver_clients bind_to_additional_free_port has no closing quote on its URL. Odd one out among the tabs: verification_summary verify_req_at_least_twice, at_most_twice, exactly_twice and never_received and mockserver_clients client_verify_reqs verify /simple; retrieve_code_example recorded_expectations_as_json and recorded_reqs_as_json leave out the POST method; request_matcher cookies_value_json_schema matches literal values, not schemas; stateful_scenarios sequential_cycling, switch_after and weighted_responses leave out the GET method (and SEQUENTIAL); cross_protocol_trigger uses other bodies. Other-language tabs with the gaps the Ruby tabs had: request_matcher_code_examples.html Python tabs of match_request_by_priority, negative_priority, path_exactly_twice and exactly_once_in_the_next_60_seconds leave out times, time to live and priority; the Python, Go, .NET, Rust and PHP tabs of match_request_by_header_by_matching_key send keyMatchStyle as a header. | Fix each REST API tab (escape a single quote as '\'' or use double quotes, write templates as one JSON string), delete its allowlist entry, and rerun the check; fix the listed Python, Go, .NET, Rust and PHP tabs the same way the Ruby tabs were fixed. |
| 294l | Conditional request example is rejected by MockServer in every tab; Ruby lacks conditional matchers and load-step checks | Found closing 294j (not run against a server). creating_expectations.html button_conditional_request_definition puts conditionalRequestDefinition at the top level of the expectation in every tab; the expectation schema has no such property (additionalProperties false) and the server reads a conditional matcher as httpRequest holding if, then and else (RequestDefinitionDTODeserializer). The Ruby tab posts raw JSON over Net::HTTP because the Ruby client has no conditional request definition model; the Ruby LoadStep likewise has no checks field, so load_injection.html button_load_step_checks submits a raw Hash. | Run the example against a server to confirm, rewrite every tab to put if/then/else inside httpRequest, add a ConditionalRequestDefinition (read by deserialize_request_definition when httpRequest has if) and LoadStep checks to the Ruby client with round-trip specs, and switch both Ruby tabs to the typed models. |
