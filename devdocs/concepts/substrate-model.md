# Substrate — Span / Trace / Session

> **Status: implemented and live since 2026-08-14.** This is the schema the code writes and
> reads, and the schema the production database runs: the cutover shipped in commit `b7bc21a6`
> (#758), and the baseline squash that folded the migration chain into a single starting point
> followed in commit `f858d66a` (#759, 2026-08-15). The `context` and `observation` tables and
> the old vocabulary described in §2.2 and §11 are gone from the schema, not merely superseded
> in code.
>
> Three sections were corrected by building them, and are worth reading as written rather
> than as first drafted: §6.1 and §7.1 (the trace row is locked before its spans are written —
> the reverse order deadlocks through `fk_span_trace`), and §7.3's settle protocol.

## 1. Scope and principles

This spec covers the trace substrate: the tables that hold ingested telemetry and the write
path that populates them. It does not cover classifiers, findings, or cases, except
where they reference substrate rows.

Five rules govern every decision below.

1. **A read is a filter, a sort, and a page.** It is never an arithmetic. If a number appears
   on a list surface, a writer already computed it and stored it on the row being listed.
2. **Facts are typed columns.** A value the product displays or filters on has its own column
   with its own type. JSON is for what we do not model.
3. **Cost is priced on arrival and never repriced.** A recorded cost is a fact about what a
   call was billed at, not a derivation that moves when a rate changes.
4. **Aggregates are replaced, never accumulated.** Any stored sum is recomputed wholesale from
   its source rows. No column is maintained by adding deltas. The one deliberate exception:
   `min`/`max` fields are idempotent under re-application and may be maintained incrementally
   (§7.1).
5. **Identity is the producer's, scoped by project.** Span, trace, and session ids are stored
   verbatim as the producer sent them; every primary key leads with `project_id`. There are no
   platform-minted surrogate ids in the substrate.

## 2. The model

Three levels, fixed. No polymorphic grouping table, no parent pointer above the span.

| Level | Grain | Cardinality | Answers |
|---|---|---|---|
| `session` | One continuous interaction with one user | Thousands per project; up to ~1,000 traces each | "What has this user been doing with us?" |
| `trace` | One turn — one thing the user asked for and waited on | Millions per project | "What did they ask for, and what did that request cost and take?" |
| `span` | One step — an LLM call, a tool call, a sub-agent | Tens of millions | "What work happened, in what order, nested how deep?" |

**Sessions never nest.** A session id is a flat string. Sub-grouping within a session (a
provider's conversation or thread id) is a column on `trace`, not a second tree level.

**Spans nest arbitrarily, within a trace only.** A span's parent is always another span of the
same trace. Nesting never crosses a trace boundary.

### 2.1 Identity

All three ids are the producer's, verbatim:

- `span.id` — the OTel span id (8 bytes, hex).
- `trace.id` / `span.trace_id` — the OTel trace id (16 bytes, hex, random per W3C Trace
  Context).
- `session.id` / `span.session_id` — the producer's session string, as sent.

Uniqueness is `(project_id, …)` in every case. OTel ids are random 128-bit values, so
collision within a project is negligible, and a buggy producer can only collide with itself —
project scoping is inside every key, so cross-project mixing is structurally impossible.

**Ingest paths that carry no OTel ids** (pull/upload) synthesize them deterministically from
the source content, so a re-upload produces the same ids and deduplicates like any redelivery.

### 2.2 Nomenclature

| Today | v2 | Note |
|---|---|---|
| `observation` | `span` | Direct rename |
| `trace` | `trace` | Name unchanged; rollup columns become populated |
| `context` (kind `turn`) | — | Folded into `trace`; already the same grain |
| `context` (kind `session`) | `session` | Concrete table, no `kind` discriminator |
| `context` (kind `conversation`) | `trace.thread_id` | A column |
| `context` (kinds `project`, `task`, `run`, `group`) | — | Removed; never written |

## 3. `span`

The only high-volume table. Correlation handles are denormalized onto every row so no read
joins upward. Usage and cost are typed columns so no read parses JSON per row.

```sql
CREATE TABLE span (
    -- identity: the producer's ids, project-scoped
    project_id           text        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    trace_id             text        NOT NULL,
    id                   text        NOT NULL,   -- the producer's span id
    parent_span_id       text,                   -- the producer's parent span id, verbatim.
                                                 -- NULL means the producer sent none: a root.
                                                 -- Deliberately no FK: parents arrive after
                                                 -- children (§6.4).

    -- derived structure (platform-owned; never set from an arrival)
    path                 ltree,                  -- materialized ancestry; NULL = unresolved (§6.4)
    path_state           text        NOT NULL DEFAULT 'pending',  -- resolver terminal marker, §6.4
    depth                integer GENERATED ALWAYS AS
                             (CASE WHEN path IS NULL THEN NULL ELSE nlevel(path) - 1 END) STORED,

    -- correlation handles, denormalized onto every row
    -- (environment_id was one of these; Track A dropped it from span, trace and session)
    session_id           text,
    correlation_state    text        NOT NULL DEFAULT 'pending',  -- resolver terminal marker, §6.3
    user_id              text,
    project_version_id   text,
    call_site_id         text,
    trace_name           text,

    -- classification
    kind                 text        NOT NULL,   -- llm | tool | agent | retrieval | ...
    name                 text,
    is_logical_root      boolean     NOT NULL DEFAULT false,  -- §9
    status               text,
    level                text,
    error_type           text,
    error_message        text,          -- the producer's status message, capped at write;
                                        -- error_type holds the CLASS

    -- timing
    started_at           timestamptz NOT NULL,
    ended_at             timestamptz,
    latency_ms           bigint,
    ttft_ms              bigint,

    -- model
    provided_model_name  text,                   -- verbatim, never rewritten
    model_id             text        REFERENCES model(id),   -- resolved at ingest

    -- usage: one column per bucket. All-null yields NULL, never 0.
    input_tokens         bigint,
    output_tokens        bigint,
    cache_read_tokens    bigint,
    cache_write_tokens   bigint,
    reasoning_tokens     bigint,
    total_tokens         bigint GENERATED ALWAYS AS (
                             CASE WHEN input_tokens IS NULL AND output_tokens IS NULL
                                   AND cache_read_tokens IS NULL AND cache_write_tokens IS NULL
                                   AND reasoning_tokens IS NULL
                                  THEN NULL
                                  ELSE coalesce(input_tokens, 0) + coalesce(output_tokens, 0)
                                     + coalesce(cache_read_tokens, 0)
                                     + coalesce(cache_write_tokens, 0)
                                     + coalesce(reasoning_tokens, 0)
                             END) STORED,

    -- cost: priced at write, one column per bucket. All-null yields NULL, never 0.
    input_cost           numeric(18,12),
    output_cost          numeric(18,12),
    cache_read_cost      numeric(18,12),
    cache_write_cost     numeric(18,12),
    total_cost           numeric(18,12) GENERATED ALWAYS AS (
                             CASE WHEN input_cost IS NULL AND output_cost IS NULL
                                   AND cache_read_cost IS NULL AND cache_write_cost IS NULL
                                  THEN NULL
                                  ELSE coalesce(input_cost, 0) + coalesce(output_cost, 0)
                                     + coalesce(cache_read_cost, 0)
                                     + coalesce(cache_write_cost, 0)
                             END) STORED,
    cost_source          text        NOT NULL DEFAULT 'unpriced',
    price_book_version   text        REFERENCES price_book(version),

    -- previews, capped at write, so list surfaces are single-table reads
    input_preview        text,                   -- 200 chars of the LAST user turn
    output_preview       text,                   -- first 200 characters

    -- versioning
    event_ts             timestamptz NOT NULL,
    is_deleted           boolean     NOT NULL DEFAULT false,
    created_at           timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_span_cost_source
        CHECK (cost_source = ANY (ARRAY['provided','inferred','unpriced'])),
    CONSTRAINT pk_span PRIMARY KEY (project_id, trace_id, id),
    CONSTRAINT fk_span_trace FOREIGN KEY (project_id, trace_id)
        REFERENCES trace (project_id, id) ON DELETE CASCADE
);
```

The primary key **is** the natural key: a retry or a late update of the same span hits the same
row and resolves through the upsert (§6.2). It also physically clusters a trace's spans
together, which is what the rollup recompute (§7.2) and the trace view read.

`fk_span_trace` is satisfiable because the trace row is get-or-created before any of its spans
commit (§6.1).

### 3.1 Cost source

`cost_source` is never inferred by a reader; it is written explicitly and is the only correct
way to interpret a null cost.

| Value | Meaning |
|---|---|
| `provided` | The producer sent cost. Stored verbatim. `price_book_version` is null. |
| `inferred` | We priced it at ingest. `price_book_version` records which book. |
| `unpriced` | We hold no rate for this model. All cost columns null — **never zero.** |

### 3.2 Partitioning

Deferred. Postgres requires the partition key inside every unique constraint, which would force
`started_at` into the primary key. Partition when table size demands it, and treat the key
change as a deliberate trade at that point.

## 4. `span_payload`

Raw payload, 1:1 with `span`. Read when a single span is opened, and by Global Search's
full-text leg (`ix_span_payload_fts`) — the one list-shaped surface that does touch it.

```sql
CREATE TABLE span_payload (
    project_id      text  NOT NULL,
    trace_id        text  NOT NULL,
    span_id         text  NOT NULL,
    input           text,       -- full prompt / message array
    output          text,       -- full completion
    attributes      jsonb,      -- everything else the producer sent, minus the promoted carriers
    provided_usage  jsonb,      -- the producer's raw usage object, kept as a receipt
    event_ts        timestamptz NOT NULL,

    CONSTRAINT pk_span_payload PRIMARY KEY (project_id, trace_id, span_id),
    CONSTRAINT fk_span_payload_span FOREIGN KEY (project_id, trace_id, span_id)
        REFERENCES span (project_id, trace_id, id) ON DELETE CASCADE
);
```

`provided_usage` is an audit copy and is **never read for arithmetic**. When an unmodelled
token bucket starts mattering, promote it to a real column on `span` and backfill from here. It
is also where a producer's own usage numbers survive verbatim after the §6.5 cache-inclusive
correction rewrites `span.input_tokens`.

`attributes` **excludes a message carrier whose bytes `input` / `output` already hold** —
keeping it stored every prompt and completion twice, which was most of the table's bytes and
told nobody anything the typed columns did not.

The test is **value identity**, not "the typed column is populated". Only the canonical
`gen_ai.input.messages` / `gen_ai.output.messages` ride into the column verbatim. The
OpenLLMetry indexed-flattened family and the OpenInference arrays are re-encoded on the way in
and the re-encoding is lossy — it keeps `role` and `content` and leaves tool calls, finish
reasons and non-text content parts behind — so those keys stay in the bag. Stripping them
because the column happened to be non-blank deleted the last copy of a tool call or an image
reference, and permanently: the payload is written once and never rewritten on read. The
pricing receipt's keys (`gen_ai.usage.*` / `llm.token_count.*` / `llm.cost.*`) and
`error.type` / `exception.type` are never candidates at all.

The payload row is written in the same transaction as its span row, under the same
last-write-wins rule (§6.2), so span and payload can never disagree about which version they
hold.

## 5. `trace` and `session`

### 5.1 `trace`

Identity plus rollups. The rollup columns are maintained per §7.

```sql
CREATE TABLE trace (
    project_id          text        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    id                  text        NOT NULL,   -- the producer's trace id
    session_id          text,
    parent_trace_id     text,                   -- async sub-agent, §9
    thread_id           text,                   -- provider conversation id
    name                text,
    user_id             text,
    project_version_id  text,
    status              text,

    -- timing: maintained incrementally (§7.1); latency derives automatically
    started_at          timestamptz NOT NULL,
    ended_at            timestamptz,
    latency_ms          bigint GENERATED ALWAYS AS (
                            CASE WHEN ended_at IS NULL THEN NULL
                                 ELSE (extract(epoch FROM (ended_at - started_at)) * 1000)::bigint
                            END) STORED,

    -- sums and counts: written only by the rollup worker (§7.2), as replacements.
    -- numeric(18,12) caps a total near $999,999; acceptable per trace, revisit if
    -- any surface ever sums traces into a stored column.
    span_count          integer,
    error_count         integer,
    input_tokens        bigint,
    output_tokens       bigint,
    cache_read_tokens   bigint,
    cache_write_tokens  bigint,
    reasoning_tokens    bigint,
    total_tokens        bigint,
    input_cost          numeric(18,12),
    output_cost         numeric(18,12),
    total_cost          numeric(18,12),
    unpriced_spans      integer,

    -- copied from the root span at rollup (§7.2)
    input_preview       text,
    output_preview      text,
    call_site_id        text,

    -- rollup bookkeeping, on the same row as the counters
    rollup_due_at       timestamptz,
    rolled_up_at        timestamptz,
    rolled_up_through   timestamptz,
    is_settled          boolean     NOT NULL DEFAULT false,
    has_root_span       boolean     NOT NULL DEFAULT false,

    event_ts            timestamptz NOT NULL,
    is_deleted          boolean     NOT NULL DEFAULT false,

    CONSTRAINT pk_trace PRIMARY KEY (project_id, id),
    CONSTRAINT fk_trace_session FOREIGN KEY (project_id, session_id)
        REFERENCES session (project_id, id),
    CONSTRAINT fk_trace_parent FOREIGN KEY (project_id, parent_trace_id)
        REFERENCES trace (project_id, id)
);
```

`unpriced_spans` is load-bearing, not diagnostic. A trace containing models we hold no rate for
carries its total **and** a count of what could not be priced on the same row and on the wire,
so nothing that reads both can mistake a low number for a cheap turn. §7.3 defines exactly
which spans the count includes.

`fk_trace_session` is enforced only when `session_id` is non-null, and is satisfiable because
sessions are get-or-created before traces (§6.1).

### 5.2 `session`

Identity only. No rollup — see §7.5.

```sql
CREATE TABLE session (
    project_id        text        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    id                text        NOT NULL,   -- the producer's session string
    user_id           text,
    started_at        timestamptz NOT NULL,
    last_activity_at  timestamptz NOT NULL,   -- maintained incrementally (§7.1)
    event_ts          timestamptz NOT NULL,
    is_deleted        boolean     NOT NULL DEFAULT false,

    CONSTRAINT pk_session PRIMARY KEY (project_id, id)
);
```

A session row exists only when a producer sends a session id; a trace without one carries a
null `session_id` and belongs to no session.

### 5.3 Pricing tables

Rates live in the database, versioned, replacing any hardcoded catalogue in application code.

```sql
CREATE TABLE price_book (
    version       text        NOT NULL PRIMARY KEY,   -- snapshot id, e.g. 'litellm-2026-08-12'
    source        text        NOT NULL,               -- 'litellm' | 'manual'
    published_at  timestamptz NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE model_price (
    price_book_version    text NOT NULL REFERENCES price_book(version) ON DELETE CASCADE,
    model_id              text NOT NULL REFERENCES model(id),
    input_per_mtok        numeric(18,10),
    output_per_mtok       numeric(18,10),
    cache_read_per_mtok   numeric(18,10),
    cache_write_per_mtok  numeric(18,10),
    PRIMARY KEY (price_book_version, model_id)
);
```

A null rate means "this model is never billed for that bucket" and contributes zero. A missing
`model_price` row means "we hold no rate", which yields `cost_source = 'unpriced'`.

## 6. Ingest

### 6.1 Resolution order and atomicity

Ingest drains in batches. For each batch, in order:

1. **Get-or-create sessions** referenced by the batch's spans:
   `INSERT INTO session ... ON CONFLICT (project_id, id) DO NOTHING`.
2. **Get-or-create traces**: `INSERT INTO trace ... ON CONFLICT (project_id, id) DO NOTHING`,
   identity fields only — never timing or rollup columns, which belong to §7 alone.
3. **Apply the batch-coalesced trace updates (§7.1) first, then upsert spans and payloads**,
   in one transaction.

Rows are created before anything references them, so every FK is satisfiable regardless of
arrival order.

**Lock ordering: the trace update must precede the span upserts inside the transaction.**
`fk_span_trace` makes every span insert take a `KEY SHARE` lock on its trace row, so writing
spans first and then taking §7.1's exclusive `FOR UPDATE` is a lock *upgrade* on a row both
concurrent transactions already hold — and no amount of sorted key ordering makes that safe.
Two overlapping batches deadlock reproducibly. Taking the exclusive trace lock first costs
nothing and removes the upgrade. (Earlier drafts of this section listed spans first; that
ordering is wrong.)

**Atomicity invariant: a span's row write, the trace update that reflects it, and the
get-or-create of the trace and session identity rows it was folded from all commit in the same
transaction.** The settle protocol (§7.4) is correct only under this invariant — a span row that
became visible without its trace re-arm would be silently excluded from a settling rollup. Only
co-commit is required, not a particular order, so the lock ordering above is free to differ from
the reading order. This must hold in code and be covered by a test.

The identity half of that invariant was added after it was violated. Step 2's get-or-create ran on
its own autocommit ahead of step 3's transaction, so a batch that rolled back left a trace row with
no spans: a shell that can never gain one, and — under a settle predicate that then required
`span_count > 0` — could never reach a terminal state either, so the §7.4 reaper re-armed it every
grace period indefinitely. **A trace row must not be able to outlive the spans that caused it to
exist.** Prevention is the only mechanism used here; there is deliberately no janitor for empty
traces, and the settle predicate stays a statement about the deadline alone (§7.2).

### 6.2 Upsert semantics — last write wins

A span's completed version must replace the partial version that arrived first. Version by
`event_ts`, ties going to the latest arrival:

```sql
INSERT INTO span (...) VALUES (...)
ON CONFLICT (project_id, trace_id, id) DO UPDATE
   SET ...   -- see SET list below
 WHERE excluded.event_ts >= span.event_ts;
```

- **SET list:** every producer-sourced column, including `parent_span_id`. **Excluded:** the
  platform-derived `path`, `correlation_state`, `path_state`, `depth` (generated), and
  `created_at`. A newer version replaces what the producer said, never what the platform derived.
- **Ties (`>=`):** second-granularity SDK clocks make equal timestamps common; the latest
  arrival wins, and a replay rewriting identical content is a no-op in effect.
- **Backward clock steps** on the producer can still discard a final version. Accepted as a
  bounded risk; the §7.6 histogram measures it.
- **Both paths re-arm.** The trace update (§7.1) runs whether the span took the insert path or
  the conflict path — a version replacement changes token counts, so it must un-settle the
  trace exactly like a new span.
- The payload row is written in the same transaction under the same `event_ts` guard.

Trace and session upserts follow the same shape but may touch **identity fields only**. Ingest
remains at-least-once and idempotent: re-delivering a batch is a no-op.

### 6.3 Correlation propagation

Every span carries its own `trace_id`, `session_id`, `user_id` and `trace_name`. Our SDK places
these in OpenTelemetry Baggage so each span self-describes and no server-side join is needed to
populate them.

For third-party OTel producers that do not propagate, a micro-batch job backfills correlation
columns onto spans that arrived before their trace was known. This is the fallback path, not
the primary one; the partial index `ix_span_uncorrelated` (§8) keeps it cheap. A span whose
trace settles with no session id is marked `correlation_state = 'none'` — a terminal state, not
left pending forever — which is what keeps the index near-empty for permanently anonymous
traffic instead of it accumulating there indefinitely.

### 6.4 Roots, parents, and paths

`parent_span_id` is the producer's statement, stored verbatim on every row and never modified
by the platform. **Root means the producer sent no parent** — `parent_span_id IS NULL` — never
"the parent has not arrived yet", which is a property of `path` instead.

A batch exporter flushes a span when it **ends**, so a parent ships after its children and a
root span ships last. No repair of `parent_span_id` is needed — the pointer is always present.
What resolves late is the **derived ancestry**:

- `path` is computed as the parent's path extended by the span's own id, when the parent's path
  is known. A span whose parent row (or parent's path) is absent keeps `path = NULL`. A span
  whose parent never arrives is marked `path_state = 'orphan'` once its trace settles, the same
  terminal-state device as `correlation_state = 'none'` (§6.3) — otherwise orphans would sit
  permanently in the unresolved-path index and eventually starve the fixpoint of genuinely
  resolvable rows.
- The resolver **iterates to a fixpoint**: each pass resolves one level, and chains arrive
  deepest-first, so a chain of depth *n* resolves over at most *n* passes. The partial index
  `ix_span_unresolved_path` (§8) makes each pass a small indexed scan.
- **A pass claims only spans whose parent path is already resolved.** The parent-is-resolved
  test belongs in the statement that picks the batch, not only in the join that updates it —
  otherwise an unordered `LIMIT` spends its whole budget on the first entries of the partial
  index, the update matches none of them, and rows deeper in the index are never reached. That
  is permanent head-of-line blocking rather than a slow pass: the pending count freezes while
  the resolver keeps ticking. With the test in the claim, every claimed row is resolvable and
  each pass makes progress.
- Readers must treat a null `path` as "ancestry not yet resolved", not as "root". Subtree
  queries (§9) are complete only for settled traces.

`trace.has_root_span` is set when a span with `parent_span_id IS NULL` lands. It drives the
rollup timer (§7.1).

### 6.5 Pricing at write

In order:

0. A span of kind `agent` or `workflow` is always priced `unpriced`, whatever usage or cost it
   reports: these producers put the whole subtree's cumulative numbers on the container span,
   and §7.2 sums every span of a trace, so storing them here would double-count the subtree into
   `trace.total_cost`. The cumulative figures survive verbatim in `span_payload.provided_usage`.
1. If the producer sent cost, store it verbatim, `cost_source = 'provided'`.
2. Otherwise resolve `provided_model_name` to a `model_id`, look up the rate in the price book
   currently in force, compute each bucket, `cost_source = 'inferred'`, and stamp
   `price_book_version`.
3. Otherwise leave every cost column null, `cost_source = 'unpriced'`.

Cost is never recomputed on read. Repricing history is an explicit job scoped by
`price_book_version`, run deliberately, and out of scope for the read path.

**`span.input_tokens` is stored FRESH-ONLY.** The OTel gen_ai semantic convention defines
`gen_ai.usage.input_tokens` as the **cache-inclusive** prompt size — fresh tokens plus
everything served from or written to cache — while every rate table prices the three buckets
independently. So on the OTLP path both cache buckets are subtracted at ingest and the
remainder is what the column holds; the producer's original numbers survive verbatim in
`span_payload.provided_usage`. Pricing the reported input as-is instead would bill the cached
tokens twice, once at the full input rate and again at their own read/write rate, and
`total_tokens` (§3, a generated sum of all five buckets) would double-count them too.

The subtraction is unconditional rather than guessed from the model name, with one exception
that is a proof and not a heuristic: a total cannot be smaller than its parts, so
`input < cache_read + cache_write` establishes that the producer is sending disjoint buckets in
violation of the convention. That case is **logged and left alone**, never corrected —
subtracting there would strip real fresh tokens. The converse is not a test: a disjoint sender
with a large fresh prompt also satisfies `input >= read + write`.

## 7. Trace rollups

### 7.1 Incremental columns — `min` / `max` only, coalesced per batch

`trace.started_at`, `trace.ended_at`, `session.started_at` and `session.last_activity_at` are
maintained in place. Re-applying `min` or `max` to a value already folded in changes nothing,
so these are idempotent under replay.

They are **not** updated once per span. Ingest pre-aggregates each batch in memory — one
min/max pair per trace touched — and issues **one update per (trace, batch)**. A 1,000-span
batch touching 50 traces performs 50 trace updates, not 1,000. To make deadlocks impossible,
the batch locks its trace rows in sorted `(project_id, id)` order before updating — and does
so **before writing any span in that batch**, because a span insert already holds `KEY SHARE`
on its trace through `fk_span_trace` and taking `FOR UPDATE` afterwards is a lock upgrade that
sorted ordering cannot rescue (§6.1):

```sql
SELECT 1 FROM trace
 WHERE (project_id, id) IN (:touched_traces)
 ORDER BY project_id, id
   FOR UPDATE;

UPDATE trace t SET
    started_at    = LEAST(t.started_at, v.min_started_at),
    ended_at      = GREATEST(t.ended_at, v.max_ended_at),      -- GREATEST ignores NULLs
    has_root_span = t.has_root_span OR v.has_root,
    rollup_due_at = LEAST(
                        COALESCE(t.rollup_due_at, 'infinity'::timestamptz),
                        now() + CASE WHEN v.has_root OR t.has_root_span
                                     THEN interval '2 seconds'
                                     ELSE interval '10 seconds' END),
    is_settled    = false
FROM (VALUES ...) AS v (trace_id, min_started_at, max_ended_at, has_root)
WHERE t.project_id = :project_id AND t.id = v.trace_id;
```

No sums, no counts, no arithmetic beyond min/max. Token and cost totals **never ride the write
path** — they are written only by the worker (§7.2).

**The deadline only ever moves earlier, never later.** That is the entire rule, and it is what
`LEAST(COALESCE(...))` encodes. A span arriving into an already-armed trace does not postpone
the rollup; it only un-settles it. A root span, which means the trace is almost certainly
finished, pulls the deadline in.

Session rows get the same treatment in the same batch: one
`LEAST(started_at, ...)` / `GREATEST(last_activity_at, ...)` update per session touched, in
sorted key order. The two halves are symmetric — `LEAST` on the start, `GREATEST` on the last
activity — so the window only ever **widens** and both are idempotent under replay. The
get-or-create in §6.1 only ever *seeds* them, on the row it creates, and never updates them
(`ON CONFLICT DO NOTHING`): whichever batch happened to win the create would otherwise stamp
its own window permanently — a late-arriving span of an old session dragging the session's
recency backwards, and an earlier span arriving later never correcting a start time that is
already too late. Correcting both is what this update is for.

```sql
UPDATE session s SET
    started_at       = LEAST(s.started_at, v.min_started_at),
    last_activity_at = GREATEST(s.last_activity_at, v.seen)
FROM (VALUES ...) AS v (session_id, min_started_at, seen)
WHERE s.project_id = :project_id AND s.id = v.session_id;
```

### 7.2 Recomputed columns — everything else

`span_count`, `error_count`, all token columns, all cost columns and `unpriced_spans` are
**never incremented.** They are recomputed from the trace's spans and written as a replacement.

Accumulation is prohibited because a running sum has no repair path: one double-add or one
missed add is permanent and undetectable. A full recompute is idempotent by construction,
survives retries and replays, and self-heals after any bug.

### 7.3 The worker

Runs on a short interval. Claims due traces with `FOR UPDATE SKIP LOCKED` so several workers
can run concurrently:

```sql
WITH due AS (
    SELECT project_id, id
      FROM trace
     WHERE rollup_due_at <= now()
     ORDER BY rollup_due_at
     LIMIT 500
       FOR UPDATE SKIP LOCKED
)
UPDATE trace SET rollup_due_at = NULL
  FROM due
 WHERE trace.project_id = due.project_id AND trace.id = due.id
RETURNING trace.project_id, trace.id;
```

**Claiming clears `rollup_due_at`.** This is what makes the settle check in the next statement
correct: any span arriving from this moment on re-arms the deadline to a non-null value (§7.1),
which is detectable.

For each claimed trace:

```sql
WITH agg AS (
    SELECT count(*)                                        AS span_count,
           count(*) FILTER (WHERE status = 'error')        AS error_count,
           sum(input_tokens)                               AS input_tokens,
           sum(output_tokens)                              AS output_tokens,
           sum(cache_read_tokens)                          AS cache_read_tokens,
           sum(cache_write_tokens)                         AS cache_write_tokens,
           sum(reasoning_tokens)                           AS reasoning_tokens,
           sum(total_tokens)                               AS total_tokens,
           sum(input_cost)                                 AS input_cost,
           sum(output_cost)                                AS output_cost,
           sum(total_cost)                                 AS total_cost,
           count(*) FILTER (WHERE cost_source = 'unpriced'
                              AND total_tokens > 0)         AS unpriced_spans,
           max(event_ts)                                   AS through
      FROM span
     WHERE project_id = :project_id
       AND trace_id   = :trace_id
       AND NOT is_deleted
), root AS (
    SELECT input_preview, output_preview, call_site_id
      FROM span
     WHERE project_id     = :project_id
       AND trace_id       = :trace_id
       AND parent_span_id IS NULL
       AND NOT is_deleted
     ORDER BY started_at, id
     LIMIT 1
)
UPDATE trace t
   SET span_count         = agg.span_count,
       error_count        = agg.error_count,
       input_tokens       = agg.input_tokens,
       output_tokens      = agg.output_tokens,
       cache_read_tokens  = agg.cache_read_tokens,
       cache_write_tokens = agg.cache_write_tokens,
       reasoning_tokens   = agg.reasoning_tokens,
       total_tokens       = agg.total_tokens,
       input_cost         = agg.input_cost,
       output_cost        = agg.output_cost,
       total_cost         = agg.total_cost,
       unpriced_spans     = agg.unpriced_spans,
       input_preview      = CASE WHEN t.has_root_span THEN root.input_preview
                                  ELSE t.input_preview END,
       output_preview     = CASE WHEN t.has_root_span THEN root.output_preview
                                  ELSE t.output_preview END,
       call_site_id       = CASE WHEN t.has_root_span THEN root.call_site_id
                                  ELSE t.call_site_id END,
       rolled_up_at       = now(),
       rolled_up_through  = agg.through,
       is_settled         = (t.rollup_due_at IS NULL)
  FROM agg LEFT JOIN root ON true
 WHERE t.project_id = :project_id AND t.id = :trace_id;
```

The previews and call site are copied down from the root span, not stored by ingest — that is
what keeps the traces list a single-table read (rule 1) rather than a join to find each row's
entry point. Gated on `has_root_span`, so a trace whose root hasn't landed yet keeps its prior
value.

The aggregate reads one trace's spans through the primary key prefix — an indexed, clustered
scan. `rollup_due_at` is never written here: the claim already cleared it, and if a span has
re-armed it since, that value must survive so the trace fires again.

`unpriced_spans` counts spans **of any kind** that reported **more than zero** tokens and could
not be priced — an unpriced embedding or rerank span is spend too.

The threshold is `> 0` rather than "reported usage at all". Producers emit placeholder spans
carrying an explicit `input_tokens = 0` / `output_tokens = 0`, and zero is not null, so the
generated `total_tokens` (§3) is `0` rather than NULL. Those spans are not spend and are
deliberately excluded: counting them tripped the marker on traces with nothing unpriced about
them, which in turn withheld those traces from cost-drift scoring.

### 7.4 Settle semantics

The null-and-check on `rollup_due_at` is the correctness crux. The claim clears it; the write
settles the trace only if it is *still* clear. A span arriving anywhere between those two
statements re-arms it (in the same transaction as its own row, per §6.1), the settle does not
apply, and the trace re-fires with that span included.

The numbers written are correct either way, because they are a replacement as of the read
rather than a delta applied to a prior value. There is no lost update.

**Timer summary:**

| Event | Effect on `rollup_due_at` |
|---|---|
| First span of an unarmed trace | Set to `now() + 10s` |
| Further spans while armed | Unchanged — the deadline never moves later |
| Root span arrives | Pulled in to `now() + 2s` |
| Rollup settles the trace | Cleared to null |

**There is no hard cap, and none is needed.** Because the deadline only moves earlier, a trace
that never goes quiet still rolls up on schedule: it fires at its deadline, the next span
re-arms it, and it fires again. A long-running turn is therefore refreshed roughly every 10
seconds with current numbers, rather than showing nothing until it finishes.

This is also why `is_settled` stays honest. It means precisely "nothing has arrived since the
last rollup" — never "we gave up waiting."

**Late spans after settle** set `is_settled = false` and re-arm `rollup_due_at` through the
normal §7.1 update. Because the rollup is a replacement, a re-fire is always safe — including
the case where a late span bridges what appeared to be two separate periods of activity.

**Crash recovery — the reaper.** A worker that dies between claim and write leaves a
fingerprint no legitimate state produces: `is_settled = false`, `rollup_due_at IS NULL`, and no
recent `rolled_up_at`. A periodic sweep re-arms such rows:

```sql
UPDATE trace
   SET rollup_due_at = now()
 WHERE is_settled = false
   AND rollup_due_at IS NULL
   AND (rolled_up_at IS NULL OR rolled_up_at < now() - interval '5 minutes');
```

The sweep is idempotent and safe to run at any frequency; re-arming a healthy in-flight claim
merely causes one redundant recompute. Every rollup remains a replacement, so recovery can
never corrupt totals.

### 7.5 No session rollup

Session totals are computed at read time by summing the already-materialized rollup columns of
that session's traces — an indexed read of up to ~1,000 trace rows for a single session view,
never a scan over spans.

The completion model does not transfer: a trace goes quiet in seconds, whereas a session may be
resumed days later. There is no gap of inactivity that reliably means a session is finished, so
a session rollup could never legitimately settle.

Two consequences are contractual:

- A session read reports `unsettled_traces` — the count of its traces with
  `is_settled = false` — alongside its totals, the same honesty device as `unpriced_spans`.
- **No surface may list sessions sorted by cost or tokens.** That is the one read shape this
  design does not serve; building it requires a session materialization with its own staleness
  contract, as a separate piece of work.

`session.last_activity_at` (§7.1) supports listing and sorting sessions by recency without any
rollup.

### 7.6 Required instrumentation

Emit a histogram of **span lateness**: `span.event_ts` minus the `rolled_up_through` of its
trace at the moment it arrives. Spans landing after a settle are the tail of that histogram; a
producer whose clock stepped backward (§6.2) appears as negative lateness.

The 10-second and 2-second values in §7.4 are starting points. The histogram, not intuition,
sets their final values, and a growing tail is the signal that they are wrong.

## 8. Indexes

```sql
-- span: the primary key (project_id, trace_id, id) already serves per-trace reads,
-- the rollup recompute, and payload joins. Additional indexes:
CREATE INDEX ix_span_project_started   ON span (project_id, started_at DESC);
CREATE INDEX ix_span_path              ON span USING gist (path);
CREATE INDEX ix_span_call_site         ON span (call_site_id, started_at DESC)
                                          WHERE call_site_id IS NOT NULL;
CREATE INDEX ix_span_unresolved_path   ON span (project_id, trace_id)
                                          WHERE path IS NULL AND path_state = 'pending';
CREATE INDEX ix_span_uncorrelated      ON span (project_id, trace_id)
                                          WHERE session_id IS NULL
                                            AND correlation_state = 'pending';
CREATE INDEX ix_span_name_trgm         ON span USING gin (name gin_trgm_ops);

-- span_payload
CREATE INDEX ix_span_payload_fts       ON span_payload USING gin (to_tsvector('simple',
                                          left(coalesce(input,''),100000) || ' ' ||
                                          left(coalesce(output,''),100000)));  -- backs Global Search

-- trace: the primary list surface, plus the rollup queue
CREATE INDEX ix_trace_project_started  ON trace (project_id, started_at DESC);
CREATE INDEX ix_trace_session          ON trace (project_id, session_id, started_at)
                                          WHERE session_id IS NOT NULL;
CREATE INDEX ix_trace_rollup_due       ON trace (rollup_due_at)
                                          WHERE rollup_due_at IS NOT NULL;

-- session
CREATE INDEX ix_session_project_active ON session (project_id, last_activity_at DESC);
```

The partial indexes stay near-empty by construction: `ix_trace_rollup_due` holds only traces
awaiting rollup, `ix_span_unresolved_path` only spans whose ancestry is pending, and
`ix_span_uncorrelated` only spans awaiting the correlation backfill.

## 9. Sub-agents

A sub-agent invoked **within** the parent's turn is a span of kind `agent` with its children
nested beneath it. It is not a session and not a separate trace. The user asked once and waited
once, so the sub-agent's cost belongs to the turn that spawned it; splitting it elsewhere makes
that turn under-report its own cost.

This matches the OpenTelemetry GenAI convention, where an agent invocation is a span
(`gen_ai.operation.name = invoke_agent`) and delegation is expressed purely through span
parentage.

Depth is unbounded and costs nothing: `path` answers "everything this sub-agent did" for any
depth in one indexed query.

```sql
SELECT * FROM span WHERE project_id = :project_id AND path <@ :sub_agent_path;
```

Subtree reads are complete only once the trace's ancestry has resolved (§6.4); on a settled
trace they are always complete.

A sub-agent that **outlives** the turn — background or fire-and-forget work the user never
waited on — is a separate unit of latency and gets its own trace. The column and
`fk_trace_parent` exist for linking it via `parent_trace_id`; as of this writing no ingest path
populates `parent_trace_id` from producer traffic, so a fire-and-forget sub-agent's trace is not
yet linked to its parent automatically.

**The test:** did the user wait on it? If yes, spans in their trace. If no, a trace of its own.

`is_logical_root` marks a span as the start of an application-level unit even when it has a
physical parent, so a sub-agent boundary is queryable without inventing an entity for it.

## 10. Retention

Retention policy values are a product decision and not fixed here; the mechanism is.

- Deletion runs as a background job in bounded key-range batches — never one large `DELETE`,
  and never through the FK cascade for bulk paths. Project deletion goes through the same
  batcher; the `ON DELETE CASCADE` clauses remain as a correctness backstop, not the bulk
  mechanism.
- `span_payload` ages out ahead of `span`: it is most of the bytes and the least of the reads.
  A span whose payload has been purged remains fully functional on every list and rollup
  surface.
- Purged spans are hard-deleted; `is_deleted` is a versioning marker (§6.2), not a retention
  mechanism.

## 11. Removed from the current schema

- The `context` table in full — `parent_id`, `depth`, `seq`, its `ltree`, and all seven `kind`
  values.
- Platform-minted surrogate ids for spans, traces, and observations, and the
  `external_span_id` / `external_trace_id` columns that paired with them — the producer's id,
  project-scoped, is the identity (§2.1).
- `observation.usage` and `observation.cost` as JSONB. Every modelled bucket becomes a column;
  the raw producer object survives only as `span_payload.provided_usage`.
- The rollup columns on `context`.
- Any hardcoded pricing catalogue in application code, and its SQL mirror. Rates become rows.
- Every read-time `GROUP BY` over spans on a list path.
