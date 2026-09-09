# Platform telemetry naming

How the backend names **outbound** OpenTelemetry spans and Langfuse traces it
emits for its own product operations (the agentic sandboxes, …).

**Out of scope:** customer-ingested substrate span names (OTLP / SDK push and dialect
normalizers) — those are the customer's vocabulary by design.
GenAI semantic-convention *attribute keys* (`gen_ai.*`) are also unchanged here.

Imperative one-liner for agents editing backend code:
[`backend/AGENTS.md`](../../backend/AGENTS.md) § *Logging + observability
conventions*.

## Naming rule

1. **Span name = static kebab product verb** — `<domain>-<verb>` (or a short
   product phrase), never a tenant-/project-/entity-specific display name or id.
   Bounded cardinality is the point: analytics and debugging group by *function*.
2. **`langfuse.trace.name` (when set) = the same kebab string as the root span
   name.** Do not invent a parallel colon-prefixed vocabulary
   (`rca:…`, `triage:…`).
3. **Entity identity goes in attributes / observation metadata**, not the name.
   Prefer `langfuse.observation.metadata.<key>` (via `LlmCaller`'s `meta` map) and
   `tessary.project.id` / `langfuse.observation.metadata.project_id`.
4. **Carve-out — agentic child turns:** the sandbox lanes emit a dotted child
   `agent.llm_request`, one per agent turn (`agent.author`/`agent.codegen` were
   retired — see Inventory below). The dotted form is the carve-out;
   the prefix deliberately names the ROLE, not the harness, so swapping the
   coding agent out again never renames a span customers have dashboards on.
   (These were `claude_code.*` while Claude Code was the harness.) Document any
   new child in this carve-out explicitly — do not invent a third style.

## Alloy / Langfuse admission

`observability/alloy/config.alloy` (and `config.dev.alloy`) keeps a span for Langfuse when it
has **either**:

- `gen_ai.operation.name` (set by `LlmCaller` and agentic roots), **or**
- `langfuse.trace.name` (set on non-LLM product roots such as `agentic-rca`).

A new non-LLM product span that should reach Langfuse **must** stamp
`langfuse.trace.name` (prefer that over a fake `gen_ai.operation.name`).

No platform LLM path currently calls `LlmCaller` at all: RCA and TRIAGE resolve their
model via `ProjectModelSettings#resolveAgenticModel` and the sandbox launcher, bypassing
it entirely, and the grading/judge callers that used to were removed. So `parentTrace`
is unexercised today rather than uniformly null-in-practice. Restoring run-level parent
traces (wiring the classifier/RCA workers through `LlmCaller` with a non-null
`parentTrace`) is a follow-up — see below.

## Inventory — platform emitters

| Emitter | OTel span name | `langfuse.trace.name` | Notes |
|---|---|---|---|
| `E2bRcaSandbox` | `agentic-rca` | `agentic-rca` | |
| `E2bTriageSandbox` | `layer2-triage` | `layer2-triage` | The Layer-2 ruling, on `ModelLane.TRIAGE`. Metadata: `tessary.triage.finding_id`, which is also the ledger subject the run's spend is booked against, so a cost per ruling is a join rather than an estimate. There is no lane attribute: the repo-grounded lane is gone and every run now rules on the evidence, so recording it would stamp a constant. |
| `AgentSpanTelemetry` | `agent.llm_request` | — | Per-turn child under agentic roots (carve-out) |

**Span names retired, deliberately not re-homed:** `llm-grader-run`,
`llm-grader-applies-when`, `deterministic-grader-run`, `agentic-synthesis`, `agentic-codegen`,
`agentic-drift-analysis`, `synth-automap`, `intent-name`, `intent-derive`, `cluster-name`. Their
emitters are deleted; a dashboard filtering on any of them goes empty rather than wrong. `agent.author`
and `agent.codegen` (rule §4's carve-out children) go with them — `agent.llm_request` is the only
per-turn child still emitted.

### Renamed spans

A span name is read outside this repo — a Langfuse view, a saved trace filter, a Grafana panel — and
nothing in the build can see those. A rename is therefore an external break of exactly the same class
as an MDC key rename below, and gets the same treatment: recorded here, no dual-emit window.

| Old span / trace name | New | Release |
|---|---|---|
| `layer2-adjudication` | `layer2-triage` | 2026-08 triage cutover. The lane stopped adjudicating a finding's direction and started auditing its claim, so the old name described a different operation; the `tessary.triage.finding_id` metadata key arrived with it, and the lane attribute went away because there is only one lane left to record. |
| `claude_code.author` / `claude_code.codegen` / `claude_code.llm_request` | `agent.author` / `agent.codegen` / `agent.llm_request` | Agentic carve-out children renamed off the harness name so swapping the coding agent out again doesn't rename a span customers have dashboards on — see Naming rule §4. |

## Metadata keys

| Key | Where | Purpose |
|---|---|---|
| `tessary.triage.finding_id` | observation metadata | The finding a Layer-2 ruling is about, and the ledger subject its spend books against |
| `tessary.project.id` / `project_id` | span attribute; `langfuse.observation.metadata.project_id` (LlmCaller) or `langfuse.trace.metadata.project_id` (agentic sandbox roots) | Tenant join key |

The `grader_id` / `grader_name` / `grader_kind` / `phase` keys were removed with grading.

**Renaming a metadata key is an external break of the same class as an MDC key rename**, and the
build cannot see it either. Record every rename here:

| Old key | New key | Release |
|---|---|---|
| `evals.project.id` | `tessary.project.id` | 2026-09 namespace rename. The whole `evals.*` internal attribute namespace moved to `tessary.*` so one name spans code, config and telemetry. The customer-emitted vocabulary (`tessary.call_site.id` and the `gen_ai.*` set) was already `tessary.*` and did not move. No dual-emit window: these are platform-internal spans, not customer-ingested ones. |
| `evals.rca.subject_id` / `evals.head_sha` | `tessary.rca.subject_id` / `tessary.head_sha` | 2026-09 namespace rename, as above (`E2bRcaSandbox`). |
| `evals.triage.finding_id` / `evals.triage.cost_usd` / `evals.triage.spend_cap_usd` / `evals.triage.over_spend_cap` | `tessary.triage.*` | 2026-09 namespace rename, as above (`E2bTriageSandbox`). |
| `evals.latency_ms` / `evals.agent.tools` / `evals.llm.structured_output.tool_miss` / `evals.cache.likely_expiry` | `tessary.*` | 2026-09 namespace rename, as above (`LlmCaller`, `AgentSpanTelemetry`). |

Two telemetry names were deliberately **not** renamed, because they are opaque keys that carry state
across the upgrade rather than anything an operator reads: the Hikari pool name `evals-hikari` (a
Micrometer `pool=` tag, so renaming it splits the series) and the four Grafana CPU alert `uid`s
(a `uid` is never displayed, and changing one makes Grafana provision a duplicate rule).

## Log fields (MDC)

Structured log lines carry business ids as MDC fields, and those field names are what a
Loki query or a Grafana alert filters on. The key set is defined once, in
[`LogContext`](../../backend/shared/src/main/java/ai/tessary/open/obs/LogContext.java);
`logback-spring.xml` names the same strings in `includeMdcKeyName` + `captureMdcAttributes`,
and `scripts/check-pipeline-vocabulary.sh` fails the build if the two disagree.

**Renaming a key is an external break, and the build cannot see it.** The gate keeps the two
in-repo sites consistent; a dashboard, saved query or alert outside this repo just stops
matching, silently, at deploy. So record every rename here:

| Old field | New field | Release |
|---|---|---|
| `signalKey` | `classifierKey` | 2026-08 cleanup release — the signal vocabulary left the schema, and the log field with it. No dual-emit window: production is frozen until reset day, so nothing was reading the old field across the change. |

## Do / don't

**Do**

- Route every LLM chat through `LlmCaller` with a static kebab `spanName`.
- Put job / entity ids in the `meta` map (or explicit attributes).
- Stamp `langfuse.trace.name` on non-LLM product roots that should reach Langfuse.
- Update this inventory when adding an emitter.

**Don't**

- Put entity display names, run ids, or free-text in the span / trace name.
- Rename customer-ingested substrate span names.
- Fake `gen_ai.operation.name` on non-LLM work just to pass the Alloy filter.
- Add a new separator style (dots, middots, colons) for platform product ops.

## Follow-ups (not in this change)

- Restore run-level parent traces on the classifier and RCA workers with static kebab
  `langfuse.trace.name` values.
