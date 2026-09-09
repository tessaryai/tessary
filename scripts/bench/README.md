# Ingest load harness

Reproduces the ingest capacity numbers quoted in
[`devdocs/guides/ingest-runbook.md`](../../devdocs/guides/ingest-runbook.md) § *The objectives* and in
the `tessary.redaction.parallelism` / `tessary.auth.token-cache.*` entries of
[`devdocs/reference/config-keys.md`](../../devdocs/reference/config-keys.md).

It exists because those numbers were quoted before anything here could re-derive them. A capacity
figure with no way to re-run it is a claim, not a measurement.

## What it is

| File | What it does |
|---|---|
| `otlp-load.js` | Open-loop OTLP/HTTP generator. Hand-encodes protobuf (same approach as `scripts/emit-span.js`) so it depends on nothing but Node. |
| `projects.sh` | Mints N projects in one org and writes one write-scoped token per line to `tokens.txt`. |
| `ramp.sh` | Steps offered load up a ladder, recording accepted/refused, rows actually written, and container CPU per step. |
| `spike.sh` | Holds a baseline rate, applies a burst, and records the second the first `503` appeared. |
| `mem-probe.sh` | cgroup, heap and NMT figures at a stated load. Needs a sidecar JDK — see below. |
| `../../docker-compose.bench.yml` | Pins the reference box and turns on JFR + native memory tracking. |

**Open-loop is the point.** A closed-loop generator (N workers each waiting for its response) slows
down exactly when the server does, so it can never observe a rejection rate at a stated offered load.

## Running it

```bash
# 1. Bring the stack up on the pinned reference box (4 vCPU / 8 GiB; backend 2 vCPU / 2 GiB)
HTTP_PORT=8000 docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d

# 2. Mint projects + tokens (1 for single-project runs, 8 for anything about drainers)
cd scripts/bench && bash projects.sh 1

# 3. A rate ladder
bash ramp.sh myrun 180 400 460 500

# 4. Burst headroom from a 420 spans/s baseline
bash spike.sh myburst 420 30 2 3 4 6
```

`otlp-load.js --tokens tokens.txt` round-robins across projects; `--token <one>` pins a single one.
That distinction matters: the spool partitions by project, so a single-project run measures a single
drainer no matter what else is configured.

## Reproducing the published comparisons

Both were A/B'd by toggling one property and changing nothing else, with the substrate truncated
between arms so index size does not confound the result.

```bash
# Redaction parallelism (config-keys.md: 476 -> 1021 spans/s, 2.14x)
SPRING_APPLICATION_JSON='{"tessary":{"redaction":{"parallelism":1}}}' \
  docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d --force-recreate backend
node otlp-load.js --token "$(head -1 tokens.txt)" --rate 1200 --spans-per-request 40 --duration 90 --warmup 15
# then the same with parallelism 4

# Token cache (the p50 61 ms -> 2.6 ms control)
SPRING_APPLICATION_JSON='{"tessary":{"auth":{"token-cache":{"enabled":false}}}}' ...
```

Swapping an older *image* in as the control does not work: an older build cannot boot against a
forward-migrated schema, because its Liquibase changelog no longer matches. Toggle the property.

## Reading the results

The generator prints a JSON summary; `--out` also writes a per-second JSONL. What each number means:

- **`accepted_span_rate`** — spans the front door took. This is the capacity figure.
- **`drain`** (rows counted straight out of Postgres) — what actually landed. It should track accepted;
  a gap means the queue was still draining when the run ended.
- **`refused`** — `503`s. Every one is a retryable answer the exporter will resend.
- **`other`** — anything else, and **any non-zero value here is a finding**: `500` is not in OTLP's
  retryable set, so those spans are lost rather than resent.

Server-side, `event="ingest.throughput"` carries `queue_bytes`, `shed_batches` and
`spool_oldest_age_ms`; the bench overlay drops its cadence to 10 s so a ladder step is visible.

## `mem-probe.sh` needs a sidecar JDK

The shipped backend image is a **JRE** — it has `java` and `jfr` but no `jcmd`, so the live JVM cannot
be asked for a native-memory summary. A sidecar JDK sharing the backend's PID namespace can, but
HotSpot's attach handshake goes through a socket in the target's `/tmp`, which a sidecar in its own
mount namespace cannot see. The bench overlay mounts `/tmp` as a named volume both containers share,
which is what completes the handshake. That mount exists for this and nothing else.

JDK Mission Control is not required to read the results — the `jfr` CLI does it — but the dumped
`.jfr` files open in JMC if you want its automated-analysis rules.

## What the numbers are not

Measured on a developer machine (arm64) against the self-host reference shape. Tessary's own hosted
deployment is a burstable `t3.small`, so a sustained figure from here does not transfer to it — the
CPU-credit behaviour alone breaks the comparison. Re-measure on the box you care about.
