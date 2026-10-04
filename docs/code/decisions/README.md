# Decision Records

A decision record captures a choice that was investigated and settled — typically a
"considered and declined" outcome — so a future contributor does not re-investigate
or re-propose it from scratch. Unlike a plan in `docs/plans/`, a decision record is not
tracking open work: it has no "What remains" table and nothing here is expected to
change unless the underlying code or constraint changes. When that happens, update the
record in place rather than filing a new one.

Use a decision record instead of a plan when:

- the investigation is finished and the answer is "no, and here is why" (or "yes, and
  here is the design"), with no residual task;
- the reasoning is worth keeping so the same question is not re-litigated; and
- there is no further implementation work to track (if there is, that work belongs in
  a `docs/plans/` entry, which can link to the decision record for the "why").

Each record follows the Pyramid Principle (`.opencode/rules/documentation-style.md`):
the decision first, then context, the options considered, why, consequences, and what
would have to change to revisit it.

## Index

| Record | Decision |
|--------|----------|
| [`recover-after-is-response-only.md`](recover-after-is-response-only.md) | `recoverAfter` stays an `HttpResponse`-only clause; extending it to `FORWARD`-family actions is deferred as an M–L cross-cutting change, not implemented piecemeal |
