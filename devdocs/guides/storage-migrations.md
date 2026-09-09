# Storage migration triggers — the ClickHouse gate

> This is the gate of record (see [the principles](../reference/principles.md#data--storage)).
> Owner: backend. Reviewed when any one threshold
> below is breached for **two consecutive weeks** on a single tenant.

Storage is treated as a **migration, not greenfield**. We ship a **tuned-Postgres** interim and
adopt **ClickHouse** only when a quantified volume threshold is crossed — never on intuition.
(The vector-corpus trigger this gate used to carry alongside the ones below, tracking pgvector
behind the `VectorIndex` SPI, was retired with the rest of the vector substrate. This
gate now governs only the trace substrate.)

**There is no longer a seam to swap.** A `TraceStore` SPI used to sit in front of the substrate
so adoption would be a bean swap; its last remaining method was `insertVerdict`, so removing the
`verdict` table took the interface with it, and the write path is plain Postgres code now.
A columnar adoption is therefore real work — repository by repository — not a configuration
change. Say that honestly when proposing one.

This document is the gate. A ClickHouse substrate may be proposed **only** when one or more of
the triggers below fires; until then, the answer is "tune Postgres."

## Triggers (any ONE sustained ⇒ open the ClickHouse-impl decision)

| # | Signal | Threshold (per tenant) | Why this number | How to measure |
|---|---|---|---|---|
| T1 | **Ingestion volume** | **> 50M spans/day** (≈ 580 spans/s sustained) writing through the substrate write path (`ingest/substrate/v2/SpanBatchWriter`) | Below this, tuned Postgres with JDBC-batched writes (one batch per table, not per span — `ingest/substrate/v2/SpanBatchWriter`) keeps up; above it, write-batch overhead and autovacuum churn dominate. (Table partitioning is deferred, not yet implemented — see `devdocs/concepts/substrate-model.md`.) | `count(span)` per 24h window, per `project_id`. |
| T2 | **p95 analytical-read latency** | **> 2 s p95** on the headline analytical queries — the filtered trace list (`TraceV2Repository.list`, the reads behind `/traces` and the `list_traces` MCP tool) and the filtered span search (`QueryRepository` via `QueryService.search`, the `/v1/query` search endpoint and the `list_spans` MCP tool) — after generated-columns + GIN/expression indexes are in place | Tuned Postgres should answer these sub-second at our cardinality; a sustained 2 s p95 means index-tuning has run out of headroom and a columnar store is the next lever. | OTel span duration histogram on those read methods. |
| T3 | **Ingestion IOPS / write pressure** | **> 8k write IOPS** sustained on the trace tablespace, or autovacuum unable to keep dead-tuple ratio **< 20%** on `span`/`span_payload` | The point where Postgres write-path tuning (fillfactor, HOT updates, and eventually partitioning — currently deferred, see `devdocs/concepts/substrate-model.md`) stops absorbing the write rate and WAL/vacuum becomes the bottleneck. | DB host IOPS + `pg_stat_user_tables.n_dead_tup` ratio. |
| T4 | **Storage footprint** | **> 2 TB** of trace data per tenant on hot storage | Beyond this, Postgres cold-data management (and backup/restore time) gets painful; ClickHouse's compression + TTL tiering wins decisively. | `pg_total_relation_size('trace') + ...('span') + ...('span_payload')` per tenant. |

## When a trigger fires

1. Confirm it is **sustained** (two consecutive weeks) and tenant-scoped, not a one-off spike.
2. Open a decision doc. Scoping it starts with re-introducing a seam: there is no `TraceStore`
   interface to implement any more, so the first milestone is extracting one from
   `TraceV2Repository` and `QueryRepository` — the repositories the reads in T2 name — and only
   then adding a ClickHouse implementation behind it.
   Budget that extraction as part of the work rather than assuming a bean swap.
3. Model is **append-only immutable events** (not `ReplacingMergeTree`).
4. Reads migrate behind the new seam **after** writes are dual-writing and backfilled; the read
   methods named in T2 are the migration surface (T2 is the read-side trigger).

## Configuration keys (this seam)

**None.** There is no store-selection key and no interface behind one — `tessary.storage.trace-store`
was reserved for a gate that never landed, and the `TraceStore` SPI it would have selected is gone.
A key here is something the ClickHouse work would introduce along with the seam it selects, not
something a deployment can set today.
