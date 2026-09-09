# Platform principles

The standing rules of the system — each one a constraint the codebase currently enforces or
a design posture new work must honor. Every entry is the rule plus the one-line why.

## Product & positioning

> Positioning, market framing and the product thesis are **not maintained here** — they live in
> the *Agent Reliability* brief in Notion, which is authoritative. This section carries only the
> durable engineering constraints that fall out of them. See [`AGENTS.md`](../../AGENTS.md)
> § *Strategic context* for the working summary a design call needs.

- **Detectors are transparent, ownable, and portable.** Customers can inspect every classifier's
  threshold and the evidence behind every finding, and take the pipeline bundle with them if they
  leave. An opaque detector dies at the buyer's code review and is unadoptable by regulated buyers.
  The proprietary asset is the trained classifier weights and the cross-customer priors, not any
  individual threshold. *(This read "graders" until they were removed; the principle transferred
  to what the product actually detects with.)*
- **Ingestion is push, never pull.** Customers forward OTel traces (direct OTLP or the
  substrate `sdk` source); we do not reach into vendor APIs to fetch them. This is an
  integration constraint, not a positioning claim — a design that polls another vendor's store
  should be surfaced before implementing.
- **No shared-model training — a code-asserted guarantee, not a config knob.**
  `PriorsService.noSharedModelTrainingPermitted()` returns `true` unconditionally, and
  `NoSharedModelTrainingGuaranteeTest` is the tripwire proving customer data reaches no
  training/fine-tune sink. There is deliberately no property to turn this off, because a
  "shared-model training" mode must not exist as a product configuration. The only
  customer-derived signal that crosses a tenant boundary is the consented, k-anonymity +
  DP-ε-gated aggregate `DerivedPrior`, and it is never a training input. Two tests are the
  code-side evidence: `NoSharedModelTrainingGuaranteeTest` proves a contribution reaches only the
  platform's own store and no second sink, and `PriorContributionGuardTest` proves the only
  cross-tenant type carries aggregates, never raw content. That the *inference provider* in the
  path does not train on our traffic is an external contract claim, not something this code can
  assert — which provider is in the path is a deployment choice, so it is the operator's to
  confirm against their own configuration.
- **Single-tenant intelligence mode is default-on and fail-closed.**
  `tessary.intelligence-mode.single-tenant` (default `true`) is the coarse tenancy gate sitting above
  the governed pooling pipeline's own switch (`tessary.priors.enabled`, default `false`) — two
  independent gates, the outer one in the safe state, so an accidentally enabled `tessary.priors.*`
  can never pool across tenants. With it on, `PriorsService` refuses every cross-tenant operation
  before storage is touched: `optIn` is a no-op, `contribute` returns `false`, `derive` returns
  `Optional.empty()`. `SingleTenantModeTest` asserts it with no database — even with pooling
  enabled, consent reported and a publishable cohort, the mocked repository sees zero interactions.
  Boot-time posture is evidenced in `intelligence_mode_audit`.

## The eval contract

- **One versioned contract is the plugin↔platform interface.** The plugin owns the contract; the
  platform consumes a vendored copy in `contract/` (see `contract/AUTHORING_CONTRACT.md`) and
  validates every import against it (schema plus cross-field invariants).
- **The platform reads the pipeline half and ignores the rest.** Everything that
  could execute a grader was removed, so `BundleAssembler` routes the bundle's grader and quality-dimension
  shards to `Shard.IGNORE`. The contract is unchanged and the plugin still emits them; a hard
  reject would make every existing bundle un-importable and buy nothing.

## Data & storage

- **Postgres is the trace substrate.** Tuned Postgres serves all trace storage; a columnar store
  may be proposed only when a quantified volume trigger in
  [storage-migrations](../guides/storage-migrations.md) is sustained for two consecutive weeks
  on a single tenant. There used to be a `TraceStore` SPI in front of it so adoption would be a
  bean swap; that method was removed with the `verdict` table, so the seam is
  gone and a columnar adoption is a real piece of work again. State that honestly when proposing
  one.
> **The vector/embedding lane left the platform.** Two principles used to stand here:
> vectors lived in pgvector (HNSW) behind a `VectorIndex` SPI, and the embedding lane was one
> queue (`embedding_job`), one worker (`EmbeddingJobWorker`), and one closed per-dataset enum
> (`EmbeddingDataset`). The durable embed-on-ingest lane was off by default and nothing in the
> open edition read the vector store back — global search's semantic leg and the query API's
> `mode=semantic` are gone with it, both surfaces are lexical/keyword-only now, and the `vector`
> extension is dropped from the schema (the Postgres image itself is unchanged).

## Trace model & ingestion

- **`gen_ai.*` is the canonical trace model — on the wire and in storage.** Everything the
  platform stores, grades, and re-exports speaks the OTel GenAI semantic conventions. Foreign
  formats (OpenInference, Traceloop, vendor adapters) normalize to `gen_ai.*` at the ingest
  edge, so every downstream layer reads one shape. Never invent an attribute where a standard
  one exists; overlay attributes exist only where the semconv is silent and are named to be
  renamable once the standard catches up.
- **Observation kind is segregated by `gen_ai.operation.name`.** `ingest/KindNormalizer` is
  the single seam mapping operation names to internal kinds, as an open enum: unknown values
  degrade to `null`, never throw. Every new source routes through it.
- **One canonical OTLP format; the platform is the spec of record.** Platform-side normalizers
  (`ingest/`) fold every producer's dialect to `gen_ai.*` (plus the small documented carriers:
  `invoke_workflow` for workflow/task steps, OpenInference `retrieval.documents.*` for
  retrieved documents, plain `input`/`output` for non-LLM step content) — so any
  OTel-GenAI-aware backend receives coherent data, not a vendor mix. Producers are expected to
  emit canonical gen_ai where they can; the normalizers are the foreign-sender fallback.
- **"OTLP round-trip" means normalized and lossless, not verbatim protobuf.** Inbound OTLP
  re-materializes losslessly into the agent-native substrate and re-emits to the same
  `gen_ai.*` span. Intentionally not preserved: vendor-proprietary attributes with no
  canonical mapping, the original wire trace/span id bytes (provenance rides
  `source_trace_id` / external ids), and intra-batch arrival ordering.
- **Ingest is fail-open.** Spans are never rejected for missing optional attributes; features
  that need an attribute skip untagged spans instead.

## Hierarchy & wire contract

- **Standard OTel attributes only — one spelling per field.** Cross-trace hierarchy is
  correlated entirely through standard attributes: `session.id` (session root),
  `gen_ai.conversation.id` (sub-grouping within a session) and `user.id` (entity). Only the
  attributes in the [ingestion contract](./ingestion-contract/README.md) are read; there are no
  compat aliases or alternate spellings.
- **Three levels, fixed — no polymorphic grouping table and no recursive spine.** `session` →
  `trace` → `span`, each keyed `(project_id, …)` on the producer's own id, with no parent
  pointer above the span. The turn IS the trace: there is no node between a session and a
  trace. Below a turn, structure is nested spans within one trace (`parent_span_id`, plus a
  materialized ltree `path` for `path <@` ancestry reads; kind from `gen_ai.operation.name`).
  `gen_ai.conversation.id` is not a materialized tier — it lands as the plain `trace.thread_id`
  column. The recursive `context` table this section used to describe was dropped along with
  v1 `trace`, `observation`, `message`, `message_block`, and `feedback` (the
  2026-08-13 substrate-v1 teardown, commit `fe37a325`) — the baseline changelog has no `context`
  table today. See [the substrate model](../concepts/substrate-model.md) for why.
- **Turns are positional.** There is no turn attribute: one turn per distinct provider trace,
  `seq` numbered per parent. Display ordering keys on `started_at` where arrival order could
  mislead.
- **Re-ingest is first-write-wins.** The substrate is append-only and never re-parents
  (re-parenting would mutate descendant ltrees).
- **Handoffs are structural.** An agent handoff is derivable from the span graph (a transfer
  `execute_tool` span preceding an `invoke_agent` span); there is no handoff overlay
  attribute.
- **Span links are the only sanctioned cross-trace execution mechanism.** A sub-agent emitted
  as its own trace lands in the right session via attributes; if its execution edge to the
  spawning span is ever represented, it is via OTLP span links. `trace.parent_trace_id` is not
  OTEL-native: the real OTLP ingest path never populates it (`TraceV2Row.of` always inserts
  `null`, and no worker back-fills it), but it is not otherwise dead — `SubstrateReadRepository`'s
  turn-root predicate reads it as one of three structural facts distinguishing a turn's root
  span from a nested sub-agent trace, and the row's own javadoc reserves it for a sub-agent
  that outlives its turn. On any project ingesting today the read is a vacuous `IS NULL` check.

## Call sites

- **Call-site resolution is the explicit `tessary.call_site.id` tag — and nothing else.**
  The dotted spelling is the only one read (it roots the expandable `tessary.call_site.*`
  namespace). There is no filepath, span-name, or other inference: an untagged span resolves
  to `null` (explicit-or-nothing), because guessing mis-attributes against an authoritative
  source.
- **The tag is a plain span attribute — no SDK required.** Any producer can set it directly
  or stamp it fleet-wide with an OTel Collector attributes/transform rule. A resolved tag
  with no matching `call_site` row materializes a minimal call site, so tagged spans
  are immediately attributable and visible; the metric-drift baselines are keyed on the stamped
  `call_site_id`.
- **Untagged spans still ingest fully.** Only call-site-scoped features (per-call-site baselines,
  behaviour profiles) skip them — degradation, not rejection.

## Evaluation

Graders, judging, datasets, golden labels, review queues and the CI merge gate were removed from
the platform. The principles that governed them are gone with them — a rule about grader
calibration is not a durable constraint once nothing calibrates. Three survive because they were
never really about grading:

- **Multimodal is handled at the ingest/export boundary, not inside analysis.** A new modality
  that maps to a content type is a routed case in `ContentExtractor` at ingestion and render,
  never an analysis redesign. The model-facing media boundary was removed along with
  grading (`llm/ContentBlocks` and its 422 reject on `UNSUPPORTED_CONTENT_TYPE` are gone with
  the judge's request build — `JudgeError.UNSUPPORTED_CONTENT_TYPE`/`MEDIA_NOT_FOUND` stay
  declared in the wire catalogue but nothing raises them); an unrecoverable media block now
  degrades to a labeled placeholder on export (`[image: <url>]` / `[document omitted: ...]`),
  lossless-or-labeled, never a silent collapse to plain text.
- **LLM inputs are never truncated.** Trace/span content fed to any platform LLM lane is bounded
  by count (fewer items), never by clipping content.
- **Cheap detection runs on all traffic; LLM work is the escalation.** Deterministic
  pattern/telemetry detectors and the shared ONNX encoder heads served CPU-side by
  classify-service's `/classify` run unsampled — model cost paid at train/serve time, not per
  event. (The per-project trained centroid classifier was removed along with the vector
  substrate; the only user-authored classifier kind today is the regex detector.) An
  LLM only runs once a cheap detector has already filed a finding. This was the "online
  grading is opt-in and layered" principle, and it outlived the grading half: the layering was
  always the point, and Layer 2 is triage now.

## Signals & subsystems

- **Signal evaluation is an async leased sweep, strictly off the ingest hot path.** A
  heartbeat worker claims coalesced jobs `FOR UPDATE SKIP LOCKED` (multi-backend safe, with
  expired-lease reclaim and dead-lettering), evaluates over the enriched substrate, and writes
  detections idempotently (first-write-wins natural key). Sweep cursors are keyset
  `(created_at, id)` pairs — a timestamp-only cursor silently drops rows that share a
  timestamp across a batch boundary.
- **The change-history risk model was removed and is not yet rebuilt.**
  `SignalSource`/`RiskNeighborhood` and the recency-decayed, Laplace-smoothed scoring they
  backed (`analysis/risk/`, `analysis/trend/`) were deleted with grading; a rebuild is deferred to
  post-launch. Any future version must keep the same honesty constraints:
  never fabricate confidence, report `insufficient_history` with no ranks on an empty model,
  label low support and neighbor-borrowed evidence.
- **Alert destinations are a multi-impl fan-out registry.** Every configured destination is
  active at once (type enum + SPI + factory registry — the co-resident-registry pattern, not
  the single-active-provider seam), and one fired event fans out to all of them. The
  post-commit listener hands off to a separate bounded `@Async` bean (a self-invoked `@Async`
  method silently runs inline); at-most-once delivery is claimed via a UNIQUE-keyed insert
  before sending.
- **Outbound, credential-bearing sends follow strict invariants.** `UrlGuard` re-validates the
  destination URL on every send (not just at config time — DNS rebind), redirects stay
  disabled, credentials are `SecretBox`-sealed at rest and write-only over the API (responses
  expose presence booleans, never config), and failure logging in credential-bearing slices is
  categorical — no throwable attached, because a wrapped cause can embed decrypted secrets in
  shipped logs.
- **Tool-rejection is operational telemetry, not a human gesture.** A tool that ran and errored is a
  fact about the tool call, and its home is the `tool_call` row and the trace. This was written when
  the platform had a `feedback` table to keep it out of; that table is gone, so today the rule
  reads forward instead: whatever reintroduces explicit human feedback must not be fed by machine
  outcomes, and a user permission-deny qualifies only if a typed, stable signal distinguishes a human
  deny from a policy deny AND a first-class per-call correlation field exists.

## Backend architecture

- **Seams-first: a capability with more than one plausible provider is reached through an
  interface.** Each swappable capability lives behind its own SPI in its feature slice with
  one active implementation selected by configuration — so a provider swap is a bean change,
  never a re-architecture. SPI seams are for open provider sets; a closed set gets a closed
  enum. When documenting a seam's default, quote `matchIfMissing` from the annotation — a
  class name never tells you which impl is the default.
- **The backend is package-by-feature; no horizontal layer buckets.** Every package is
  exactly one of: a feature slice owning its full stack, a cross-cutting infra package
  (`config`, `db`, `crypto`, `errors`, `obs`, `web`), or the `model/` record graph. Packages
  stay flat — role is signalled by naming suffix (`*Controller` / `*Service` / `*Repository`
  / `*Row` / `*Dtos`), not by sub-package. Full convention: [architecture](./architecture.md).
- **Plain JVM (Java LTS) + Loom virtual threads; no native-image/AOT.** The workload is
  I/O-bound fan-out, so virtual threads carry it; there is no reflection registry or AOT step
  to maintain. `StructuredTaskScope` is not used while it remains a preview feature — fan-out
  uses `SimpleAsyncTaskExecutor(virtualThreads, concurrencyLimit)` or
  `newVirtualThreadPerTaskExecutor()` + `Semaphore`.
- **Concurrency bounds are load-bearing.** Executor bounds guard provider RPM/TPM limits,
  the bounded sandbox-VM set, and the JDBC pool ceiling. An unbounded virtual-thread executor
  where a bounded pool belongs is a defect, not a simplification.

## Backend hygiene

- **Jackson: inject the shared strict `@Primary` `ObjectMapper`** (`config/JacksonConfig`).
  A bare `new ObjectMapper()` is acceptable only in non-Spring static utilities, and every
  such site carries an inline justification comment.
- **Column constants are adopt-on-touch.** Any repository you touch adopts the generated
  `db/schema/*Columns.java` constants in the same PR; mass migration is unbounded churn for
  latent benefit.
- **NullAway scope: the pom is authoritative.** The `AnnotatedPackages` arg in the backend pom
  is the single source of truth for coverage; prose docs reference it and never restate the
  package list (restated lists go stale). Fix findings with real annotations, zero
  `@SuppressWarnings`.
- **Every deferral names its re-trigger.** "Deferred" without the concrete event that reopens
  it is how debt becomes invisible — write the wake-up condition next to the deferral.
- **Point-in-time audit docs are replaced, not patched.** A current-state assessment names the
  commit it was verified against, re-verifies every claim at write time, and is deleted and
  replaced by its successor — stale numbers in an authoritative-looking doc redirect effort at
  phantom targets.
