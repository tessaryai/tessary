# Canonical Trace Schema — `gen_ai.*` (+ `tessary.*` overlay)

> This is the *authority* the ingest/export code conforms to, realized in code by
> `ingest/GenAiAttributes.java`. The governing rules live in [the principles](./principles.md).

## The model

The **OpenTelemetry GenAI semantic conventions (`gen_ai.*`) are canonical** — the vocabulary of the
data model on the wire and in storage, not merely an import format. Everything the platform stores,
grades, and re-exports speaks `gen_ai.*`. A namespaced **`tessary.*` overlay** is defined **only where
gen_ai is silent** — exactly ONE consumed attribute
(`tessary.call_site.id`). Foreign trace
formats — notably **OpenInference (`llm.*` / `openinference.*`)** — are accepted on ingest and
**normalized to `gen_ai.*` at the edge**, so a native `gen_ai.*` span and an OpenInference span produce
the **same canonical shape**.

Two rules govern every change to this schema:

1. **Never invent an attribute where a standard `gen_ai.*` one exists.** Audit attr-by-attr against the
   semconv before minting any `tessary.*` name.
2. **`tessary.*` names are chosen to be _renamable_ to the emerging OTel GenAI *agent* convention** —
   not refactored — once it standardizes.

## Versions verified (per "verify latest stable before building")

| Spec | Version / status verified | Notes |
|---|---|---|
| OTel GenAI semconv | **v1.41.x**, GenAI conventions in **Development** | open/Development enums → unknown values degrade to `null`, never throw. |
| Provider attribute | `gen_ai.system` **→ renamed** `gen_ai.provider.name` (since v1.37) | Export keeps `gen_ai.system` (evals-plugin Path-A reader keys on it); live/OTLP span path emits `gen_ai.provider.name`. Both named in `GenAiAttributes`. |
| OpenInference semconv | Arize OpenInference spec (verified live) | key set below. |

> **Wire contract:** the single-table reference of every field producers send that the platform
> actually consumes lives at [ingestion-contract/](./ingestion-contract/README.md) — doc-first;
> update it before changing producer or ingest code. This file explains the *how* behind that table.

## Canonical `gen_ai.*` vocabulary (what the platform speaks)

| Attribute | Meaning | Where it lives on `RawEntry` |
|---|---|---|
| `gen_ai.operation.name` | operation discriminator (`chat`, `text_completion`, `generate_content`, `embeddings`, `execute_tool`, `retrieval`, `create_agent`, `invoke_agent`, `invoke_workflow`) | `operationKind` (via `KindNormalizer`) |
| `gen_ai.system` / `gen_ai.provider.name` | provider/system (`anthropic`, `openai`, `google`, …) | a declared system in `metadata` is authoritative; else derived via `TraceSpanMapper.inferSystem(model)` |
| `gen_ai.request.model` | requested model id | `model` |
| `gen_ai.input.messages` | input messages, JSON `[{role, parts:[{type,content}]}]` | `input` |
| `gen_ai.output.messages` | output messages (carry `finish_reason`) | `output` |
| `gen_ai.usage.input_tokens` | prompt tokens | `metadata` |
| `gen_ai.usage.output_tokens` | completion tokens | `metadata` |
| `gen_ai.usage.cache_read.input_tokens` / `gen_ai.usage.cache_creation.input_tokens` | cached input tokens read / written | `metadata` |
| `gen_ai.usage.reasoning_tokens` | reasoning/thinking tokens, billed apart from output (OpenInference spells it `llm.token_count.completion_details.reasoning`) | `metadata` |
| `gen_ai.usage.cost` | the producer's own USD cost for the call, stored verbatim and never repriced. Aliases read: `gen_ai.usage.total_cost` (OpenLLMetry), `llm.cost.total` and the per-bucket `llm.cost.prompt` / `llm.cost.completion` / `llm.cost.prompt_details.cache_read` / `llm.cost.prompt_details.cache_write` (OpenInference) | `metadata` |
| `gen_ai.tool.name` | invoked tool/function name | `metadata` |
| `gen_ai.tool.call.id` | tool-call correlation id | `metadata` |
| `session.id` | session/thread id (standard OTel attr, **not** re-namespaced); roots the context spine | `metadata` |
| `gen_ai.conversation.id` | conversation id (standard GenAI attr); a `kind='conversation'` context under the session, or the root itself when no `session.id` is stated | `metadata` |
| `user.id` | end-user/entity handle (standard OTel attr — the only spelling read) → denormalized `user_id` on `session`/`trace`/`span` | `metadata` |

`gen_ai.operation.name` ⇄ internal kind is the single seam `KindNormalizer` — the SINGLE kind
source (no `tessary.*` overlay participates):
`chat`/`text_completion`/`generate_content` → `llm`; `invoke_agent`/`create_agent` → `agent`;
`execute_tool` → `tool` (or `mcp` with `gen_ai.tool.type=extension`); `embeddings` → `embedding`;
`retrieval` → `retrieval`; `invoke_workflow` → `workflow`; open-enum extension values
`rerank`/`guardrail`/`plan`/`reasoning`/`handoff` → their kinds; any other name containing `memory` →
`memory`; unknown/absent → `null`. The inverse
(`KindNormalizer.operationName`) is used by the export to re-emit a
truthful operation name instead of a hard-coded `chat`.

## `tessary.*` overlay (only where gen_ai is silent)

These name structure the OTel GenAI **agent** conventions do not yet standardize. They are seeded
as named constants in `GenAiAttributes` so the agent-native substrate builds on one vocabulary.
**Hierarchy correlation carries NO overlay attributes**: the context spine derives entirely
from standard attributes — `session.id` → `kind='session'` root, `gen_ai.conversation.id` →
`kind='conversation'` (under the session, or the root when no session is stated), turns are positional
(one per provider trace, `seq` numbered per parent), `user.id` → the denormalized `user_id` column on
session/trace/span.

The consumed overlay is exactly ONE attribute: `tessary.call_site.id`. Every other `tessary.*`
name is unread — the [ingestion contract](./ingestion-contract/README.md) carries the full
do-not-emit list and the standard field each one's content belongs on.

| Overlay attribute | Why no gen_ai standard | Future standard to track |
|---|---|---|
| `tessary.call_site.id` | the ONE consumed overlay: linkage from a span to a project-defined call site (the dotted spelling roots the expandable `tessary.call_site.*` namespace and is the only spelling read) | — |

### Call-site & code provenance — emitted by producers

Producers stamp **OTel-standard** semconv for source provenance — these are
**not** overlay names, so rule 1 (never invent where a standard exists) holds:

| Attribute | Standard | Source |
|---|---|---|
| `code.filepath` / `code.function` / `code.lineno` | OTel code semconv | the producer's call-site capture |
| `vcs.repository.url` / `vcs.ref.head.revision` | OTel vcs semconv | resource attributes set by the producer (commit SHA from `TESSARY_COMMIT_SHA` or `.git/HEAD`) |

`code.filepath` / `code.function` are persisted on the span attributes for provenance, but they are
**not** used to resolve a call site. Call-site binding is the explicit
`tessary.call_site.id` tag only; a source that genuinely needs the raw attribute can still target it via a
`metadata.code.filepath` rule. Only the project-defined `tessary.call_site.id` linkage is an overlay
(above) — everything else here is standard semconv.

## OpenInference (`llm.*` / `openinference.*`) → `gen_ai.*` mapping

Normalized at the `RawEntry`-construction boundary by `ingest/OpenInferenceNormalizer.java`. **Dormant**
unless OpenInference keys are present. Keys verified against the Arize OpenInference spec.

| OpenInference | Canonical `gen_ai.*` | Lands on `RawEntry` |
|---|---|---|
| `openinference.span.kind` (`LLM`/`AGENT`/`TOOL`/`RETRIEVER`/`RERANKER`/`EMBEDDING`/`CHAIN`/…) | `gen_ai.operation.name` (`chat`/`invoke_agent`/`execute_tool`/`retrieval`/`embeddings`/`invoke_workflow`) | `operationKind` (via `KindNormalizer`) |
| `llm.model_name` | `gen_ai.request.model` | `model` |
| `llm.system` / `llm.provider` | `gen_ai.system` / `gen_ai.provider.name` (declared system carried as authoritative; `TraceSpanMapper` prefers it over `inferSystem(model)`) | `metadata` |
| `llm.input_messages[]` (`message.role`, `message.content`, `message.contents[].message_content.text`) | `gen_ai.input.messages` | `input` |
| `llm.output_messages[]` (+ `message.tool_calls[]`) | `gen_ai.output.messages` | `output` |
| `llm.token_count.prompt` | `gen_ai.usage.input_tokens` | `metadata` |
| `llm.token_count.completion` | `gen_ai.usage.output_tokens` | `metadata` |
| `llm.token_count.total` | `gen_ai.usage.total_tokens` *(no standard `gen_ai.usage.total_tokens` in semconv; carried under that explicit key — not invented onto a standard slot)* | `metadata` |
| `tool_call.function.name`, `tool_call.id` | `gen_ai.tool.name`, `gen_ai.tool.call.id` (first tool call on the span; re-emitted as structured attrs by `TraceSpanMapper`) | `metadata` |
| `tool_call.function.arguments` (+ name) | folded into the output-message text (`[tool_call <name> <args>]`), so the full argument payload is never truncated | `output` |
| `session.id` (OpenInference uses bare `session.id`) | `session.id` (already standard) | `metadata` |

`openinference.span.kind` values with no gen_ai operation analogue (`GUARDRAIL`, `EVALUATOR`, `PROMPT`)
normalize to `null` kind (unknown), per the open-enum contract. A span with a `llm.model_name` but an
unmapped/absent span-kind falls back to `chat`/`llm` (single-LLM-call backstop).

## Flattened message encoding (OpenLLMetry/Traceloop `gen_ai.prompt.N.*` / `gen_ai.completion.N.*`)

Some OTLP exporters (the OpenLLMetry/Traceloop convention) emit messages **not** as the canonical
`gen_ai.input.messages` / `gen_ai.output.messages` JSON arrays, but as **indexed, flattened span
attributes**: `gen_ai.prompt.0.role`, `gen_ai.prompt.0.content`, `gen_ai.prompt.1.role`, …, and
`gen_ai.completion.N.role|content` for outputs. These are a **foreign-but-known input encoding** (like the
OpenInference `llm.*` keys), **not** canonical OTel semconv — never treat `gen_ai.prompt.*` as a canonical
message key.

`OtlpSpanMapper` reads-and-reconstructs them at the OTLP edge into the same canonical `[{role, content}]`
array the structured path produces, ordered by the index N, and threads the result onto
`RawEntry.inputMessagesJson` / `outputMessagesJson` — so the writer stays encoding-agnostic and the same
canonical message array lands in `span_payload` regardless of the exporter's encoding style.

**Precedence: the structured array always wins.** When a span carries *both* the structured
`gen_ai.input.messages` array and the flattened `gen_ai.prompt.N.*` family, the structured array is used and
the flattened duplicate is ignored (the reconstruction runs only when the structured field is absent). A
non-integer / malformed index degrades the reconstruction fail-open (skipped, never thrown); content is
carried whole (never-truncate invariant). The constants for the key family live in `GenAiAttributes`
(`TRACELOOP_PROMPT_PREFIX` / `TRACELOOP_COMPLETION_PREFIX`), labeled ingest-only.

## Explicit feedback events — removed

The platform used to land `gen_ai.evaluation.result` span events as first-class `feedback` rows and
bridge each into a human verdict. **That feature is gone.** The writer went with the v1 enricher, and
migration 0083 (folded into the baseline in the 2026-09 epic-3 partition squash) dropped the `feedback` table, the `pre_deploy_check.feedback_observation_id` anchor, and
the `feedback` member of the `embedding` and `saved_view` vocabularies (`saved_view` itself was later dropped by the model-lane cutover, with deep search). A producer that still emits the
event is not rejected — the event is simply not read.

The `gen_ai.evaluation.*` keys are therefore **unclaimed**, not reserved: anything that reintroduces
explicit feedback starts from the [ingestion contract](./ingestion-contract/README.md), not from a
partially-live mapping.

## Agent self-reports

There is no agent self-report surface: nothing reads a `tessary.agent.self_report` event or
`tessary.self_report.*` attributes, and no table, API, or UI exposes one. Introducing agent
self-diagnostics requires a standard (or newly documented) carrier and an
[ingestion-contract](./ingestion-contract/README.md) update first.

## Round-trip guarantee (acceptance)

A native `gen_ai.*` span and an OpenInference `llm.*` span carrying the same content **produce the same
canonical `RawEntry`** (same `operationKind`, `model`, message structure), which re-emits via
`TraceSpanMapper` to the same `gen_ai.*` span. Proven by `OpenInferenceNormalizerTest` (round-trip
parity) — see `backend/substrate/src/test/java/ai/tessary/evals/ingest/`.

## Conformance map (code ↔ schema)

| Code | Role |
|---|---|
| `ingest/GenAiAttributes.java` | the single attribute-key surface (canonical + overlay + OpenInference input keys); the OI-span-kind → operation-name map |
| `ingest/KindNormalizer.java` | `gen_ai.operation.name` ⇄ internal kind (both directions) |
| `ingest/OpenInferenceNormalizer.java` | `llm.*`/`openinference.*` → canonical `RawEntry` at the ingest edge |
| `ingest/export/TraceSpanMapper.java` | canonical `RawEntry` → `gen_ai.*` span (honors `operationKind`; emits usage/tool attrs) |
| `ingest/otlp/OtlpSpanMapper.java` | OTLP/HTTP push path: decoded OTLP span → canonical `RawEntry` (OpenInference reuse, else native `gen_ai.*`) at the ingest edge |
| `ingest/TraceloopNormalizer.java` | Traceloop/OpenLLMetry `gen_ai.*` conventions → canonical `RawEntry` at the ingest edge |
| `ingest/substrate/v2/SpanBatchWriter.java` | canonical `RawEntry` → persisted span rows (the write side of the edge) |
| `sandbox/AgentSpanTelemetry.java` | emits `gen_ai.*` (uses the renamed `gen_ai.provider.name`) |

## Substrate storage

The canonical `RawEntry`/span shape above is **structurally persisted** by the substrate. The edge
normalizes everything to the `gen_ai.*` `RawEntry`; `ingest/substrate/v2/SpanBatchWriter` then lands it
as typed rows, one transaction per batch. Shapes (verify against the `*Row.java` records under
`storage/`, and against [`substrate-model.md`](../concepts/substrate-model.md) for how the current
schema came to be):

**Three levels, fixed, and every id is the producer's own.** There is no polymorphic grouping table and
no parent pointer above the span; a key is `(project_id, …)` in every case.

- **`session`** — one continuous interaction with one user, keyed `(project_id, id)` where `id` is the
  producer's session string verbatim. `user_id`, `started_at`, `last_activity_at`.
  Sessions never nest: a provider's conversation/thread id is `trace.thread_id`, a column.
- **`trace`** — one turn, keyed `(project_id, id)` on the OTel trace id. Identity (`session_id`,
  `parent_trace_id`, `thread_id`, `name`, `user_id`, denormalized `project_version_id` /
  `call_site_id`), timing (`started_at`, `ended_at`, generated `latency_ms`),
  and the **rollups** a list surface reads without arithmetic: `span_count`, `error_count`, the five
  token buckets, `input_cost`/`output_cost`/`total_cost`, `unpriced_spans`, and previews of the root
  span's input/output. Rollups are written by the rollup worker as wholesale replacements; NULL means
  "not rolled up yet", never 0. `rollup_due_at` / `rolled_up_at` / `rolled_up_through` / `is_settled`
  are that worker's bookkeeping.
- **`span`** — one step, keyed `(project_id, trace_id, id)` on the OTel span id. **All three columns
  are the key**: a span id alone is half of one. The `kind` column is the **closed 14-value step
  typology** (`llm`, `reasoning`, `tool`, `mcp`, `retrieval`, `embedding`, `reranker`, `agent`,
  `workflow`, `plan`, `memory`, `handoff`, `guardrail`, `step`) — enforced by `KindNormalizer` in
  application code, not a DB CHECK constraint. Nests within its trace via
  `parent_span_id` + a materialized ltree `path`, with `path_state` / `correlation_state` marking the
  resolvers' terminal states. Correlation handles (`session_id`, `user_id`, `project_version_id`,
  `call_site_id`, `trace_name`) are denormalized onto every row so no read joins upward. There was an
  `environment_id` beside them until `0016` dropped it from all three levels with the Environment
  concept — see [`ingestion-contract/README.md`](./ingestion-contract/README.md) for what a producer
  that still ships `deployment.environment.name` gets.
  Typed usage and cost columns — the five token buckets, per-bucket costs, generated
  `total_tokens`/`total_cost`, plus `cost_source` and `price_book_version` recording how and at what
  rate the row was priced.
- **`span_payload`** — `input`, `output`, jsonb `attributes`, and `provided_usage` (an audit copy of
  the producer's usage object, never read for arithmetic), keyed the same three ways and written in the
  same transaction under the same `event_ts` guard. Split out because it is most of the bytes and the
  least of the reads, and it ages out first: a span whose payload is purged still lists and rolls up.
- **`tool_call`** — hung off its span by `(project_id, trace_id, span_id)`; provider `tool_call_id`,
  `tool_type`, `scope` (client|server), `is_error`, `error_type` (the failure's CLASS) and
  `error_message` (its prose, capped at write); jsonb `arguments` (+ `arguments_raw` keeping the
  verbatim, possibly-non-JSON args), jsonb `result`, jsonb `attributes`. Its `id` is derived from
  the producer keys, so redelivery collides on the primary key.
- **`retrieved_doc`** — same keying and same derived id; `list_role` (candidate|result), 1-based
  `rank`, `title`, `source_uri`, `data_source_id`; jsonb `metadata`.
- **`media_object`** — inline **base64 media is externalized** here (content-addressed, deduped per
  `(project_id, digest)`). The payload keeps an `image_ref` (or, since #985, `document_ref`) node naming the id; `media_ref` records
  that reference as a row so the bytes can be found, and collected, by something other than a string
  search.

**What is not here any more.** `context`, the v1 `trace`, `observation`, `message`, `message_block` and
`feedback` were dropped by migration 0083 (folded into the baseline in the 2026-09 epic-3 partition
squash). Message content is not normalized into rows: it lives in
`span_payload.input`/`.output` as the canonical `[{role, content}]` arrays the edge produced. The turn
IS the trace, so there is no spine node between a session and a trace.

## Out of scope (here)

- Transport details. Both OTLP transports are implemented: the HTTP/protobuf receiver
  (`POST /v1/traces`, `ingest/otlp/`, always on — no enable/disable knob; not a security boundary
  since every push needs a write-scoped bearer token) and an opt-in gRPC receiver (port 4317,
  `OtlpGrpcServerConfig`, enabled via the receiver's `transport` knob).
- Trajectory/memory attributes: not consumed; introduce doc-first if a real
  consumer emerges (the Entity/Memory tables stay deferred; `user.id` lands only as the denormalized
  `user_id` column on session/trace/span).
