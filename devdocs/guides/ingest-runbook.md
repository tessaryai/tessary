# Ingest runbook

What ingest is supposed to do, how to see whether it is doing it, and what to do when it is not.

Written for someone who did not build it. Every step names the exact query, log field or command —
"check the dashboard" is not a runbook step.

---

## 1. The objectives

These are the numbers ingest is held to. They were absent until launch requirement J2 asked for them,
which is why "is ingest healthy?" had no answer that was not a shrug.

| # | Objective | Number | Where it comes from |
|---|---|---|---|
| O1 | **Accepted-write latency** — `POST /v1/traces` returns without writing to the substrate | p99 < 250 ms | in `memory` mode `enqueue` is a non-blocking hand-off; in `kafka` mode it waits for the broker's acknowledgement, bounded by `publish-timeout-ms`. The request thread never writes to Postgres, never redacts |
| O2 | **Sustained drain rate** — spans persisted per second, single instance | ≥ 200 spans/s | the write path's stated drain budget, exercised by `SubstrateWriteIntegrationTest` against the reference Postgres; in `kafka` mode the drain runs `evals.ingest.spool.kafka.consumers` drainers, so it scales with cores |
| O3 | **Shed rate** — batches dropped because the queue was full | 0 over any 5-minute window under normal load | `ingest.throughput` field `shed_batches` |
| O4 | **Spool lag** — accepted data is reaching the substrate | `spool_oldest_age_ms` under `evals.ingest.spool.max-lag-ms` | `ingest.throughput` fields `spool_oldest_age_ms`, `queue_bytes`, `queue_max_bytes`; `oldestAgeMs` on the `/actuator/health/ingest` group (platform staff) |
| O5 | **Write failures** — batches that exhausted their retries | 0 | `ingest.throughput` field `failed_batches` |
| O6 | **Ingest availability** — the front door answers | ≥ 99.5% non-5xx on `/v1/traces` | `http_server_requests_seconds_count{uri="/v1/traces"}` |

**O2 is a single-instance number and the deployment is single-replica** (Tessary's
hosted deployment runs one backend replica). 200 spans/s is roughly 17 million spans a day, which
is about two orders of magnitude above current production traffic — the objective exists so that a
regression is detectable, not because we are near it.

**What the objectives deliberately do not say.** There is no objective on end-to-end time from span
emission to a case opening. That interval is dominated by the detector's window arithmetic and the
triage agent's queue, neither of which is ingest, and folding them in here would hide an ingest
regression behind a detector's normal slowness.

### The degradation contract

Ingest buffers accepted batches in an `IngestSpool` (#984): the in-process spool by default, or the
Kafka-API spool (`KafkaSpool`, #1299) when `evals.ingest.spool.mode=kafka` names a broker, the bundled
Redpanda under `docker compose --profile kafka` being the shipped one. The contract differs by mode, so
the first thing to know about an install is which one it runs
(`evals.ingest.spool.mode`, `spool_mode` on the throughput line, `spool` on the
`/actuator/health/ingest` group, which is platform-staff only like every management path):

| Mode | Accepted (200) means | On restart | Shed means |
|---|---|---|---|
| `memory` (default) | queued in this process, bounded by `queue-max-bytes` | what was queued is lost; the producer was told 200 and is not told again | the byte budget is full; `503 + Retry-After` |
| `kafka` (opt-in, a Kafka-API broker) | persisted by the broker before the response, on as many disks as `evals.ingest.spool.kafka.replication-factor` asks for | nothing acknowledged is lost; the consumer resumes from the last ack | the broker refused the publish (down, or at its storage cap); `503 + Retry-After` |

The bundled Redpanda is one node, so the spool creates its topics at `replication-factor` 1 — an
acknowledged batch is on one disk, and that node's loss is data loss even though the producer was told
200. Pointing `bootstrap-servers` at a real multi-broker cluster is the case the key exists for: set
`evals.ingest.spool.kafka.replication-factor=3` there BEFORE the first boot, because the value is
applied at topic creation and an existing topic keeps whatever it was made with (`rpk topic alter-config`
and a partition reassignment are what change one after the fact). Above 1 the spool also sets
`min.insync.replicas=2`, so `acks=all` means two copies rather than a leader acknowledging alone while
its followers lag.

In both modes a drainer does the same work: claim, redact, write, ack. `memory` mode runs one
drainer (same-project batches must not race each other); `kafka` mode runs
`evals.ingest.spool.kafka.consumers` of them (default 4), each a member of the consumer group, so
the broker spreads the partitions across them and one project's partition is drained by exactly one
at a time. Measured (`KafkaSpoolIntegrationTest`, 2,000 spans over 8 projects, laptop Postgres): 4
drainers drained the same burst 2.2x faster than 1 (the test only gates that 4 is not slower than 1 by
more than 2x, because a shared CI box shares its cores three ways); the reference Postgres, not the
drainer count, is what caps the ratio, so raise `consumers` with cores, never past the partition count
(8), and watch O2 rather than expecting 4x. The
`/actuator/health/ingest` group is the one number that answers "is accepted data reaching the substrate":
`oldestAgeMs` is the age of the oldest unprocessed batch, `deadLettered` counts batches that
exhausted their retries, and the component is DOWN when any drainer is dead (`drainersAlive` of `drainers` says which count) or `oldestAgeMs`
passes `evals.ingest.spool.max-lag-ms`.

In `memory` mode ingest is designed to **shed rather than block, and to say so on the wire**. When the hand-off queue
fills, the batch is dropped, the counter moves, and a WARN names the project and span count:

```
substrate write buffer full — shed batch project=… spans=256
```

That is the intended behaviour, not a fault: the alternative is back-pressuring the customer's exporter
and slowing their production agent. A shed batch is recoverable — the whole write path is idempotent on
natural keys, so re-ingesting the same spans fills in what was lost.

**The shed is reported to the producer, and that is what makes the recovery automatic.** The ingest edge
answers a shed with a retryable status — HTTP `503` plus `Retry-After`, gRPC `UNAVAILABLE` — never a
`200`. Stock OTLP exporters retry both unconditionally, so the resend happens without anyone being told.
Until 2026-08 a shed answered `200`: the batch was gone, the producer believed it had succeeded, and
nothing anywhere recorded which spans were missing. A single bulk upload lost 5,835 spans that way while
every export reported success. If you are reading this because a corpus looks short, check `shedBatches`
against the producer's own span count — a gap with no `503`s in the access log predates this change.

`UNAVAILABLE` rather than `RESOURCE_EXHAUSTED` on gRPC is deliberate: the OTLP spec makes
`RESOURCE_EXHAUSTED` retryable only when the server attaches a `google.rpc.RetryInfo`, so sent bare it is
a permanent drop wearing a non-OK status.

Observed 2026-08-08 under a 2 GB Docker limit at ~20k spans: it shed and said so, rather than
truncating silently or falling over.

**Shedding is a capacity signal, and it is now the only reason a span does not land.** `shed_batches`
moving means we could not keep up. There is no longer a second, benign explanation to rule out first:
Track A removed sampling entirely, so `sampled_out` is gone from the heartbeat and every span a
producer sends is either written or shed.

---

## 2. The dashboard

`observability/grafana/dashboards/tessary-ingest.json`. Import it the same way as the others
(Grafana Cloud → Dashboards → Import → upload the JSON; pick the Prometheus and Loki datasources when
prompted).

It has two halves, and they come from different places:

- **Prometheus** — request rate, latency percentiles and status classes for `/v1/traces`. These exist
  already, via the Micrometer OTLP registry → Alloy → Grafana Cloud.
- **Loki** — throughput, shedding, queue depth, redaction cost and retention deletions. These
  come from structured log lines rather than meters, because `micrometer-core` arrives with
  `spring-boot-starter-actuator` and only the `app` module declares it; instrumenting `substrate` with a
  meter would mean pulling that dependency down a layer to draw a graph. Loki already indexes every
  structured field as a key-value pair.

### The log lines the dashboard reads

| Event | Emitted by | Fields |
|---|---|---|
| `ingest.throughput` | `IngestThroughputReporter`, once a minute, silent when nothing moved | `batches` `spans` `unwritable_spans` `shed_batches` `failed_batches` `queue_depth` `queue_bytes` `queue_max_bytes` `spool_mode` `spool_oldest_age_ms` `spool_dead_lettered` `refused_batches` `oversize_batches` `drainer_alive` `drainer_restarts` `unparseable_tool_args` `unparseable_tool_results`, the rollup counters (`rollup_claimed` `rollup_recomputed` `rollup_settled` `rollup_failed` `rollup_rearmed` `rollup_queue_depth` `rollup_overdue_ms`) and the span-lateness histogram buckets |
| `redaction.batch` | `RedactionService`, per drained batch | `entries` `rules` `bytes` `durationMs` |
| `retention.sweep` | `RetentionSweeper`, hourly | `projects` `deleted` `durationMs` |
| `retention.deleted` | `RetentionSweeper`, per project-class that removed anything | `project` `data_class` `ttl_days` `from_policy` `cutoff` `deleted` `truncated` |
| `encoder.dependency` | `EncoderDependencyReporter`, on boot and daily | `projects` `orgs` `classifiers` `decommissionable` |

All are counters and gauges for one interval, not running totals — plot the field directly.

### The two background components behind those counters

Both are part of the write path, not optional extras, and both are on by default.

- **`TraceRollupWorker`** — the reason a traces list is a filter, a sort and a page rather than an
  aggregate. A span write arms its trace with a `rollup_due_at`; the worker claims what is due, and
  **recomputes every counter wholesale from the trace's spans** rather than adding a delta. That is the
  whole repair story: a running sum has none, since one double-add is permanent and undetectable,
  whereas a full recompute self-heals after any bug. `rollup_claimed` minus `rollup_settled` over an
  interval is spans landing mid-rollup — normal in small numbers, and the same finding the lateness
  buckets report from the span's side.
- **`TraceRollupReaper`** — the crash sweep. A worker that dies between claiming a trace and settling it
  leaves the row claimed and unarmed, so it would never be folded again. The reaper re-arms those after
  a grace period. **`rollup_rearmed` is steady-state zero**; anything else means a worker is dying
  mid-claim, and that is the number to look at first. It also logs an ERROR when the oldest due deadline
  falls further behind than `rollup-stale-after-ms`, because a rollup queue that stops draining is
  otherwise invisible: every trace surface keeps serving the last numbers written, with no gap and no
  error.

Both have kill switches (`evals.ingest.substrate.rollup-enabled`, and the reaper's own cadence keys —
see [config-keys.md](../reference/config-keys.md)). Turning the worker off leaves the deadlines armed on
the rows, so turning it back on drains the backlog rather than losing it.

---

## 3. Diagnosis

Start here. Each branch ends in an action, not an observation.

### "Traces are not arriving"

1. **Is the front door answering?** Prometheus:
   `sum by (status) (rate(http_server_requests_seconds_count{uri="/v1/traces"}[5m]))`.
   - No series at all → nothing is being sent. It is the customer's exporter, not us. Walk them through
     `docs/self-hosting/setup.mdx` § *Confirm traces are arriving*; the three usual causes are the wrong
     endpoint suffix, `X-Api-Key` instead of `Authorization: Bearer`, and the gRPC port.
   - `401` → revoked token or a token for a different project.
   - `404` → the deployment is `evals.ingest.otlp.transport=grpc` and the HTTP route is off. Check the
     config, not the customer.
   - `2xx` but nothing visible in the product → the writes are being accepted and lost downstream. Go
     to the next question.
2. **Are they being written?** Loki: `{service_name="tessary-backend"} | json | event="ingest.throughput"`.
   - `batches` moving, `spans` at zero → the drainer is failing. Check `failed_batches` and look
     for `substrate batch write failed` / `substrate batch dropped before write`. Almost always
     Postgres: connection pool exhaustion or disk.
   - No lines at all while `/v1/traces` returns 2xx → the reporter is silent because nothing moved,
     which with 2xx responses means every batch was empty.
3. **Were they written and then deleted?** Loki: `event="retention.deleted"` for that project. A TTL
   shorter than the window someone is looking at will do this and look exactly like ingest loss. Check
   the effective policy: `SELECT * FROM retention_policy WHERE project_id = '…';` — no row means the
   platform default from `evals.retention.*`. Settings → Data retention (or
   `GET /api/orgs/{org}/projects/{project}/retention`) shows the same answer per class, with the
   install default beside any override (#1205).

### "Ingest is shedding" (O3 breached)

`shed_batches` is non-zero. In order:

1. **Are the drainers alive?** Read `drainer_alive`, `drainers_alive`/`drainers` and `drainer_restarts` on
   `ingest.throughput`. Every drainer dead means the queue never falls and ingest has stopped, while the process stays up and keeps
   answering. It is now supervised — a restart is logged at ERROR and counted — and the JVM dumps and
   exits on an `OutOfMemoryError` rather than limping on with the thread gone, so any non-zero
   `drainer_restarts` is a bug to chase, not a routine event.
2. **Is Postgres the bottleneck?** `retention.sweep` `durationMs` climbing, or Hikari pool saturation on
   the CPU dashboard. The write pool is deliberately bounded at 10 connections in production so that
   pressure surfaces as back-pressure rather than thread starvation.
3. **Is it genuine volume?** If drain rate is at O2 and the queue is still full, the instance is at
   capacity. Short-term levers, in order of preference:
   - raise `evals.ingest.substrate.queue-max-bytes` (default 64 MiB) — buys burst headroom only, and
     spends it directly on heap, since a queued batch holds its payloads inline. It is the only
     admission bound: a batch can be 10 spans or 825, so there is no count to tune;
   - shorten that project's trace retention to relieve table and index size;
   - ask the noisiest project to send less. There is no server-side sampling lever any more — Track A
     removed it, deliberately, because "we watch every trace" cannot be true beside a knob that
     silently makes it false.
4. **Is it a 2 GB Docker limit?** Locally, yes — this is expected and is what the shed path is for.

### "Redaction is burning CPU"

`redaction.batch` `durationMs` high relative to `bytes`. The built-in rule set is cheap: measured
(`SubstrateWriteIntegrationTest`, 500 spans with 4 KB bodies, all ten built-ins enabled against no
rules) the drain time ratio was 0.87, within the run-to-run noise of the write itself (the test gates
at 5x, the point where a quadratic rule shows), so a ratio well above 1 points at a rule, not at the guard. This has happened once, in production, and the
cause was a rule with unbounded quantifiers scanning megabyte bodies. Check for a recently added
**custom** rule (`SELECT * FROM pii_redaction_rule WHERE project_id = '…' AND built_in = false;`).
Disable it (`enabled = false`) and the compiled-rule cache invalidates on the next mutation. The kill
switch for the whole guard is `evals.redaction.enabled=false`, which is a data-protection decision and
not one to take alone.

### "A partner says data disappeared"

Retention and shedding look identical from outside. Distinguish them:

| Symptom | Check | Meaning |
|---|---|---|
| Old data gone, recent data fine | `event="retention.deleted"` for that project | Working as configured. Show them the TTL. |
| Recent data has gaps under load | `shed_batches` non-zero | Our fault. Re-ingest recovers it; the write path is idempotent. |
| Recent data thin but present, uniformly | — | Not us. This used to mean a sampling policy; there is no longer any server-side path that thins a project's traffic, so look at the producer's own exporter. |

### "Can we scale classify-service to zero?"

Loki: `event="encoder.dependency"`. Only when `decommissionable=true`. The field counts projects with an
*enabled* encoder-backed classifier row whose org also holds the capability — if it is above zero,
turning the service off silently stops those classifiers producing, which reads like a quiet week
rather than an outage.

---

## 4. Levers

| Lever | Key | Default | Effect |
|---|---|---|---|
| Write retries | `evals.ingest.substrate.max-attempts` | 3 | Attempts per batch before it counts as failed |
| Throughput heartbeat | `evals.ingest.substrate.report-ms` | 60000 | How often `ingest.throughput` is emitted |
| Retention kill switch | `evals.retention.enabled` | true | `false` deletes nothing |
| Retention cadence | `evals.retention.interval-ms` | 3600000 | Sweep interval |
| Retention work bound | `evals.retention.batch-size` / `.max-batches-per-sweep` | 5000 / 20 | Rows per statement / statements per project-class per sweep |
| Default TTLs | `evals.retention.trace-ttl-days` / `.detection-` | 90 / 90 | Platform defaults; a `retention_policy` row overrides per project. `0` keeps forever. (`.embedding-ttl-days` was retired with the vector substrate, #1116) |
| Redaction kill switch | `evals.redaction.enabled` | true | `false` persists content unredacted |

Every key is also listed in [config-keys.md](../reference/config-keys.md).

---

## 5. Retention, as enforced

Worth stating plainly, because these were rows nobody read until launch requirement J4.

**Retention** is a bounded hourly sweep. Deleting a `trace` row cascades to its spans via
`ON DELETE CASCADE`, but the traces class sweep still runs three statements per pass — payloads first
(`deleteSpanPayloads`, staged ahead of the cascade for a future shorter payload TTL), then `trace` itself
(which cascades to `span`), then a cleanup of `finding_evidence` rows left orphaned by the deletes above.
The side tables are producer-keyed rather than FK'd and are swept alongside it.
It deletes on **event** time, not insert time, so a backfilled export ages on the clock of when it
happened. A trace that an **open** finding names as its exemplar is never
deleted — a live case whose evidence links are dead is worse than a slightly larger table.

**Sampling is gone.** It honoured exactly one kind (uniform `probabilistic`) and read three others only
to warn and ignore them, because a *biased* sampler would make a detector fire on our own sampling
policy. The default was no sampling at all, which is what "we watch every trace" has to mean to be
true — so Track A removed the remaining knob rather than keep a lever whose only honest setting was
off. `SamplingGate`, `sampling_policy` and `evals.ingest.sampling.*` are all deleted; a customer who
wants less ingested sends less.

---

## See also

- [config-keys.md](../reference/config-keys.md) — every key and the class that binds it
- `docs/self-hosting/setup.mdx` — the published, partner-facing side of the same system
