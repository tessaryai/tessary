# Ingestion wire contract — fields the platform consumes

**This is the living contract between producers (SDK, plain-OTLP apps, collectors) and the ingestion
pipeline.** Every row below is grounded in an actual read site in the ingest code — nothing listed is
speculative, and anything *not* listed is carried verbatim in the attribute bag but does not drive
ingest-time processing.

> **Doc-first rule:** changes to what the platform expects or consumes are made **here first**, then in
> code (SDK and ingestion pipeline follow the doc, never the other way around). A PR that adds or
> removes a consumed field must update this table in the same change.

Governing rules: [the principles](../principles.md) — OTEL-native hierarchy (standard attributes
only; the sole consumed `tessary.*` attribute is `tessary.call_site.id`), explicit call-site
tagging, and canonical gen_ai normalization at the edge. Deeper mapping detail:
[trace-schema.md](../trace-schema.md).

## How to read the table

- **Field** — the wire name. **One spelling per field** — the platform reads exactly the names listed
  here, no compat aliases or deprecated-spelling fallbacks.
- **Applies to** — `always` means every span; otherwise the `gen_ai.operation.name` family or the
  carrier (span event, resource) the field is only read for.
- **Origin** — `OTLP proto` (protobuf span field, not an attribute) · `OTel semconv` (general
  semantic conventions) · `gen_ai semconv` (GenAI conventions) · `OpenInference` / `Traceloop` /
  `Langfuse` (third-party dialects accepted for compat) · `Tessary` (our overlay — kept only where no
  standard exists).
- Span **events** are rows too: the event name is the field, its attributes are the indented rows
  after it.

## Consumed fields

| Layer | Field | Consumed for | Applies to | Origin |
|---|---|---|---|---|
| Identity | `trace_id` | **the trace's identity**, stored verbatim as `trace.id`; one trace = one turn | always | OTLP proto |
| Identity | `span_id` | **the span's identity**, stored verbatim as `span.id` — resolvable only alongside `trace_id`, since the key is `(project_id, trace_id, id)`; parent-graph node; side-table id derivation | always | OTLP proto |
| Identity | `parent_span_id` | span nesting → `span.parent_span_id` (+ the materialized ltree `path`) — **the** mechanism for workflow → step → sub-workflow → sub-agent depth | always | OTLP proto |
| Identity | `name` (span) | `span.name` + tool-call name; the root span names the trace | always | OTLP proto |
| Timing | `start_time_unix_nano` | `started_at` / event ordering / turn ordering / latency start | always | OTLP proto |
| Timing | `end_time_unix_nano` | `ended_at`; tool-call + step latency | always | OTLP proto |
| Status | `status.code` = ERROR, `status.message` | `span.level=ERROR` — **the ONLY error source**, and the gate on everything in the next row | error spans | OTLP proto |
| Status | `error.type` | `span.error_type` + tool-call `error_type`, one precedence: the producer's `error.type` wins, and only when it is absent does `status.message` supply the label, whitespace-collapsed and capped at 120 chars. State neither and the tool-call column keeps a placeholder (a failure with no label is still a failure) while the span column stays null. `error_type` is a LABEL for grouping failures — the full prose stays recoverable from `span_payload`. | error spans | OTel semconv |
| Hierarchy | `session.id` | **the session's identity**, stored verbatim as `session.id` and denormalized onto every trace and span | always | OTel semconv |
| Hierarchy | `gen_ai.conversation.id` | `trace.thread_id` — sub-grouping within a session is a COLUMN, not a second tree level | always | gen_ai semconv |
| Hierarchy | *(turn — no attribute)* | **the turn IS the trace**: one turn per provider trace id, with no separate node | — | — |
| Hierarchy | `user.id` | end-user handle → `session.user_id`, denormalized onto trace and span | always | OTel semconv |
| Kind | `gen_ai.operation.name` | **the single kind source** via KindNormalizer: `chat`/`text_completion`/`generate_content`→llm · `invoke_agent`/`create_agent`→agent · `invoke_workflow`→workflow · `execute_tool`→tool · `embeddings`→embedding · `retrieval`→retrieval · `rerank`→reranker · `guardrail`→guardrail · `plan`→plan · `reasoning`→reasoning · `handoff`→handoff (open/Development enum; the last six are our accepted extension values) · any name containing `memory`→memory | always | gen_ai semconv (open enum) |
| Kind | `gen_ai.tool.type` | `extension` → `mcp` kind discrimination; also persisted as `tool_call.tool_type` | `execute_tool` | gen_ai semconv |
| Tool | `gen_ai.tool.name` | `tool_call.name` (falls back to the span name) | `execute_tool` | gen_ai semconv |
| Tool | `gen_ai.tool.call.id` | `tool_call.tool_call_id` (provider correlation id) | `execute_tool` | gen_ai semconv |
| Kind | `openinference.span.kind` | OI dialect → op-name (LLM→chat, AGENT→invoke_agent, TOOL→execute_tool, RETRIEVER/RERANKER→retrieval, EMBEDDING→embeddings, CHAIN→invoke_workflow) | OI-dialect spans | OpenInference |
| Kind | `traceloop.span.kind` | fallback op-name when `gen_ai.operation.name` absent | OpenLLMetry spans | Traceloop |
| Model | `gen_ai.request.model` | `span.provided_model_name`, resolved to `span.model_id` for pricing; presence backstops kind→llm | llm-family | gen_ai semconv |
| Model | `llm.model_name` | OI-dialect model (same role as above) | OI-dialect spans | OpenInference |
| Content | `gen_ai.input.messages` / `gen_ai.output.messages` | the canonical role-tagged arrays, stored whole in `span_payload.input` / `.output` (never truncated) | chat-family | gen_ai semconv |
| Content | `gen_ai.prompt.<N>.role/.content` / `gen_ai.completion.<N>.*` | flattened-dialect messages, reconstructed when the structured arrays are absent | OpenLLMetry spans | Traceloop |
| Content | `llm.input_messages` / `llm.output_messages` (+ `message.role/.content/.contents[]`, `message.tool_calls[]`) | OI-dialect messages; first tool call lifted to `gen_ai.tool.name`/`gen_ai.tool.call.id` | OI-dialect spans | OpenInference |
| Content | `input` / `output` | generic verbatim carriers (tool args/results, non-chat steps) when messages absent | always | carrier (extension) |
| Content | `traceloop.entity.input` / `.output` | last-resort input/output for foreign framework spans | OpenLLMetry spans | Traceloop |
| Content | `llm.system` / `llm.provider` | OI dialect → `gen_ai.system`/`gen_ai.provider.name` (carried; not yet a column) | OI-dialect spans | OpenInference |
| Response | `gen_ai.response.id` | carried in `span_payload.attributes`. It was the correlation key explicit-feedback events resolved against; that feature is gone, and nothing resolves against it today | llm-family | gen_ai semconv |
| Usage | `gen_ai.usage.input_tokens` / `output_tokens` / `total_tokens` / `cache_read.input_tokens` / `cache_creation.input_tokens` | the typed token columns on `span` (`total_tokens` is generated from the buckets when the source omits it); the raw object is kept in `span_payload.provided_usage` as audit, never read for arithmetic. When the producer reports no cost of its own (the two Cost rows below), these buckets are priced on arrival from `price_book` with `cost_source='inferred'`, and never re-priced | llm-family | gen_ai semconv |
| Usage | `gen_ai.usage.reasoning_tokens` | its own typed `reasoning_tokens` column on `span`, folded into the generated `total_tokens` (five buckets, not four) | llm-family | gen_ai semconv |
| Usage | `llm.token_count.prompt` / `.completion` / `.total` | OI dialect → the `gen_ai.usage.*` keys above | OI-dialect spans | OpenInference |
| Usage | `llm.token_count.completion_details.reasoning` | OI dialect → `gen_ai.usage.reasoning_tokens` above | OI-dialect spans | OpenInference |
| Cost | `llm.cost.prompt` / `.completion` / `.prompt_details.cache_read` / `.prompt_details.cache_write` | the producer's own per-bucket USD cost, stored verbatim with `cost_source='provided'` and never re-priced (`IngestPricer.providedCost`). Per-bucket figures win over any total | llm-family | OpenInference |
| Cost | `gen_ai.usage.cost` / `gen_ai.usage.total_cost` / `llm.cost.total` | the producer's own TOTAL cost, read in that precedence when no per-bucket figure is present. A lone total lands in `input_cost`, because `span.total_cost` is generated from the four buckets and splitting it ourselves would be a derivation | llm-family | gen_ai semconv · Traceloop · OpenInference |
| Retrieval | `retrieval.documents.<N>.document.id` / `.content` / `.score` / `.metadata` | first-class `retrieved_doc` rows (1-based rank) | spans carrying these OI-indexed attributes (no kind gate — extraction runs on every span and is a no-op absent the prefix) | OpenInference |
| Call site | `tessary.call_site.id` | binds the span to its call site (and is denormalized onto its trace); auto-materializes unseen call sites. The dotted spelling roots the `tessary.call_site.*` namespace we will expand. Settable by ANY producer (plain attr) or a Collector attributes/transform rule — no SDK required | always | **Tessary (the ONLY sanctioned overlay)** |
| Bag | *(all remaining attributes, resource + span)* | carried **verbatim** into `span_payload.attributes` (never clipped) — queryable, but drives no processing | always | any |

## Handoffs are structural, not an attribute

A handoff between agents needs **no attribute**: it is already wire-encoded as an `execute_tool` span
(the transfer call) emitted by agent A, followed by an `invoke_agent` span for agent B under the same
parent (the shape every OTel agent-framework instrumentation emits for a transfer). Ingest marks both spans truthfully (`tool`,
`agent`); the handoff *relationship* is derivable from the span graph at read time. A producer that
wants to state it explicitly may emit `gen_ai.operation.name = handoff` (open-enum extension value) —
never a `tessary.*` attribute.

## Deliberately NOT consumed (do not build against these)

- **Span links (`links[]`)** — the only sanctioned future cross-trace mechanism (sub-agent as its
  own trace); deferred. Not read today. `trace.parent_trace_id` exists and is written by nothing on
  the wire: a detached sub-agent is linked by the platform, not declared by the producer.
- **`span.kind`, `trace_state`, `span.flags`, dropped counts, instrumentation-scope attributes** —
  ignored.
- **`gen_ai.system` / `gen_ai.provider.name`** — carried in `span_payload.attributes`, not promoted to
  a column.
- **`tessary.sdk`, `tessary.upstream.*`** — provenance markers; written, never read by processing.
- **`gen_ai.evaluation.result`** (span event) and the `gen_ai.evaluation.*` attributes under it —
  **no longer read.** They landed explicit user feedback as first-class rows until the feature was
  removed and its changeset `0083` (folded into the baseline in a 2026-09 partition squash)
  dropped its table. Emitting the event is harmless and has no effect; the
  keys are unclaimed rather than reserved.
- **`deployment.environment.name`** (resource attr) — **no longer read.** It scoped an ingested span to
  an Environment row until the Environment concept was removed and its changeset `0016` (since folded into
  the baseline) dropped `environment_id` from `session`, `span` and `trace`. The attribute is still ACCEPTED: a producer
  that ships it is not rejected and it survives verbatim in `span_payload.attributes` — it simply scopes
  nothing — a project is the only scope below an org, so separate environments with separate projects.

## Do not emit (no readers exist)

The consumed `tessary.*` surface is exactly one attribute: `tessary.call_site.id`. Any other
`tessary.*` attribute has no reader; the content belongs on the standard field instead:

- `tessary.session.id` → use `session.id`
- `tessary.turn.index` → turns are positional (no attribute)
- `tessary.entity` / `tessary.entity.id` → use `user.id`
- `tessary.environment` → nothing reads an environment attribute at all (see above); separate
  environments with separate projects
- `tessary.context.path` / `tessary.context.kind` → nesting is derived from `parent_span_id`, never producer-stated
- `tessary.kind` → use `gen_ai.operation.name` (open enum, incl. the extension values)
- `tessary.mcp.server` → use `gen_ai.tool.type = extension`
- `tessary.handoff.to` / `.from` → handoffs are structural (see above)
- `error` attribute → use OTLP span `status`
- `tessary.tool.call.retries`, `tessary.feedback.*`, the `tessary.agent.self_report`
  event + `tessary.self_report.*`, `tessary.trajectory` / `tessary.intent` / `tessary.memory` —
  nothing on the platform stores or exposes these.

## Invariants to preserve when evolving this contract

1. **Standard first**: a new field must use the OTel/gen_ai name when one exists; `tessary.*` is only
   for genuine gaps and requires updating [the principles](../principles.md) alongside this contract.
2. **Fail-open ingest**: absence of any non-structural field degrades a feature, never drops a span.
3. **Verbatim content**: message/tool content is never truncated at ingest (bound by count, not
   size) — the one exception is a poison-sized payload past `SpanBatchWriter`'s 8,000,000-char cap,
   which drops the whole span rather than clipping it — same never-truncate rule as
   [`principles.md`](../principles.md) § *Evaluation*.
4. **No compat aliases**: only the canonical spelling of each field is read — only the fields in the
   table drive processing. Third-party *dialects* (OpenInference, Traceloop flattened messages) are
   normalized at the edge as documented product features; ad-hoc alias spellings of canonical fields
   (e.g. `sessionId`, `userId`/`user_id`, `enduser.id`, `deployment.environment`,
   `tessary.call_site_id`) are never read.
