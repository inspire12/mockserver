# Decision: accept HTTP/3 closing the connection over `maxHeaderSize`, not answering 431

**Decision.** When an HTTP/3 request's header section exceeds `maxHeaderSize`, MockServer
closes the whole QUIC connection with `H3_EXCESSIVE_LOAD` instead of answering `431` on the
one stream, unlike HTTP/1.1 (`431`, connection closed) and HTTP/2 (`431` on that stream,
connection carries on). This difference is accepted and stays documented; it is not fixed.

**Status:** Decided 2026-10-05 (owner). Closes performance-programme row 148 (originally
found closing row 99, commit `c8c61ef60`).

## Context

Netty's HTTP/3 codec (`Http3FrameCodec`, `Http3HeadersSink`) treats a header section over
`SETTINGS_MAX_FIELD_SECTION_SIZE` as a **connection** error, where HTTP/2 answers `431` on
the single stream and leaves the connection open. `Http3MockServerHandler.exceptionCaught`
recognises Netty's `H3_EXCESSIVE_LOAD` and logs one `WARN`; the request is never dispatched.
RFC 9114 §4.2.2 permits either behaviour — this is not a protocol violation, only an
inconsistency with how MockServer answers the same condition on the other two protocols.

See [netty-pipeline.md → Request line and header limits](../netty-pipeline.md#request-line-and-header-limits)
for the full per-protocol comparison table, and
[http3.md → Request header size cap](../http3.md) for the HTTP/3-specific mechanics (a `HEADERS`
frame over the limit is refused from its length; a section small on the wire but decoding
past the limit is refused as it decodes, measured by `Http3HeaderSectionAllocationIntegrationTest`).
The consumer-facing description is in
`jekyll-www.mock-server.com/mock_server/configuration_properties.html` (*Maximum HTTP Request
Header Size*), which already states the HTTP/3 behaviour plainly.

## Options considered

The row frames this as answering `431` versus accepting the difference:

1. **Answer `431`.** The codec is not public and takes one setting for both what it
   advertises to the client and what it enforces, so this means giving the codec a higher
   limit than the advertised one and enforcing `maxHeaderSize` in `Http3MockServerHandler`
   (which would then hold up to the higher limit per stream) — or a change in Netty.
2. **Leave it** as a documented difference. **Chosen.** RFC 9114 §4.2.2 permits either
   behaviour, and the difference is already correctly documented in three places (code
   docs, consumer docs) so a user hitting it is not surprised, only inconvenienced relative
   to HTTP/2.

Whether a Netty issue exists for this is not established by this record.

## Consequences

- An HTTP/3 client that sends headers over `maxHeaderSize` loses every other in-flight
  request on that connection, not just the oversized one. A client multiplexing many
  requests over one HTTP/3 connection to MockServer should keep headers (and large cookies
  or auth tokens especially) under the limit, or raise `maxHeaderSize`.
- No schema, codec, or handler change is pending from this decision.

## What would reopen this

- Netty changing `Http3FrameCodec` to raise a stream error for an oversized header section,
  which would let MockServer answer `431` per-stream the same way it does on HTTP/2 without
  a second limit.
- A concrete user report where the whole-connection loss on HTTP/3 causes a real problem,
  weighed against the cost of option 1 above (a second, higher codec-level limit).
- HTTP/3 support being promoted out of "experimental," which would raise the bar for
  protocol-level inconsistencies like this one.

## Pointers

- [netty-pipeline.md → Request line and header limits](../netty-pipeline.md#request-line-and-header-limits)
- [http3.md → Request header size cap](../http3.md)
- `jekyll-www.mock-server.com/mock_server/configuration_properties.html` → *Maximum HTTP Request Header Size*
- `Http3HeaderSectionAllocationIntegrationTest`, `Http3HeaderListLimitIntegrationTest` (`mockserver-netty`)
