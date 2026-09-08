# Backend architecture & package conventions

The authoritative layout convention for the Java backend **and the single home of the
per-package module inventory** (bottom of this doc). This is the rule new code follows and
the target when touching old code. [`backend/AGENTS.md`](../../backend/AGENTS.md) carries the
imperative conventions and links here; update the inventory here when the package set changes.

The backend is an **eleven-module Maven reactor**; dependencies flow downward only and Maven —
not a convention — enforces it. The full module table is in [Module inventory](#module-inventory)
below and the layering rationale in [`../modules.md`](../modules.md). Three of them frame
everything else:

- **`backend/app`** — assembly only: `EvalsApplication`, `application.yaml`, and every
  `@SpringBootTest` integration test (they need the `@SpringBootConfiguration` only this
  module has). Feature slices live in the modules *below* it, not here.
- **`backend/shared`** — the open-core cross-cutting primitives with no
  app dependencies, under `ai.tessary.evals.open.*`: `open/errors` (the error catalog + every
  domain enum), `open/obs` (log/MDC plumbing), `open/jobqueue` (the generic `LeasedJobQueue`
  seam), `open/media` (the `MediaStore` SPI).
- **`backend/contract`** (`evals-contract`) — the code-first OpenAPI surface: the checked-in
  `src/main/resources/openapi/evals-api.json` (see *The API contract* below).

The package tree this doc describes is `backend/<module>/src/main/java/ai/tessary/evals/`.

## Principle: package-by-feature, not package-by-layer

Every package is **one of three things**, and never a mix:

1. A **feature slice** — a vertical slice that owns its full stack: HTTP edge → business
   logic → persistence → its own DTOs and feature-owned records. A reader opening the package
   sees everything that feature needs and nothing it doesn't.
2. A **cross-cutting infrastructure** package — shared plumbing with no business logic.
3. The **domain-record** package (`model/`) — the bundle-schema record graph, and only that.

There are **no horizontal-layer packages.** We do not have a `service/` or a `controllers/`
bucket that collects one layer across features (see
[package-by-feature in the principles](./principles.md#backend-architecture)).

Within a slice, packages stay **flat** — no `api/` / `service/` / `persistence/` sub-packages.
Role is signalled by **naming suffix** (below), not by directory.

## The three categories

### Feature slices

Every vertical product area under `ai.tessary.evals.*` — full list in
[Module inventory](#module-inventory) below. Two SPI-seam slices are expanded
under that heading (`storage/`, `priors/`).

One of these is an **SPI-seam slice** — a feature whose core is a swappable provider
interface with implementations selected by `@ConditionalOnProperty`:

- `priors/` — cross-customer priors: aggregated, anonymized statistics. Posture is governed by `evals.intelligence-mode.single-tenant` (default
  `true`), enforced in `PriorsService` itself.

`storage/` — the streaming trace substrate — used to carry two SPI seams beside its plain
Postgres write path: a `TraceStore` SPI (Track A removed that interface's last method with the
`verdict` table) and a `VectorIndex` SPI over pgvector similarity search (`embedding_space` +
`embedding`, the `vector` extension, the durable embed-on-ingest lane). `VectorIndex` and the
vector substrate it fronted were removed entirely (#1116) — no surface in the open edition read
them — so `storage/` is plain Postgres code with no seam left in it at all.

A slice owns its controller, services, repositories, row mappers, request/response DTOs, and
any records that exist only to serve that feature.

### Cross-cutting infrastructure (no business logic)

| Package | Holds |
|---|---|
| `config` | `@ConfigurationProperties`, async/OTel config (virtual-thread task executors) |
| `db` | DataSource (HikariCP against `EVALS_JDBC_URL`) + Liquibase wiring |
| `crypto` | `SecretBox` (AES-GCM seal/open) |
| `apidoc` | springdoc/OpenAPI config (`OpenApiConfig`, `JSpecifyNullabilityConverter`) — the code-first spec generator |
| `web` | Shared HTTP plumbing **only**: `ApiResponse`, `ResponseMeta`, `ErrorBody`, `GlobalExceptionHandler` |

Two cross-cutting concerns live in the **`shared` module** (`ai.tessary.evals.open.*`), not in
`core`: `open/errors` — `ErrorCode`, `EvalsException`, `ErrorCatalog`, and **every** per-domain
error enum; and `open/obs` — `LogContext`, `Markers`, MDC plumbing, log filters. The generic
leased-queue seam (`open/jobqueue`) and the `MediaStore` SPI (`open/media`) live there too.

`web/` is the one to watch: it is HTTP plumbing, **not** a home for controllers. A controller
in `web/` is a convention violation.

### Domain records

`model/` — the record graph that mirrors the plugin's bundle schema (`Pipeline`, `CallSite`,
`Chain`, `FailureMode`, `TaxonomyNode`, …). Pure data. No services, no controllers, no
catalogs. If a "model" class has behavior or Spring annotations, it belongs in a feature slice
(this is why the LLM model-configuration feature is `llm/`, not `model/` — see below).

## Anatomy of a feature slice

Flat package; files read top-to-bottom by role via their suffix:

| Role | Suffix / shape | Example |
|---|---|---|
| HTTP edge | `<Entity>Controller` (singular) | `CaseController`, `ClassifierController` |
| Business logic | `*Service`, `*Executor`, `*Dispatcher`, `*Runner`, `*Worker`, `*Factory` | `AlertService`, `ClassifierWorker`, `RcaEngine` |
| Persistence | `*Repository` + its `*Row` | `FindingRepository` + `FindingRow` |
| Wire DTOs | `*Dtos`, `*Request`, `*Response` | `ClassifierDtos`, `AskRequest` |
| Feature records | plain records | `SweepContext`, `SweepOutcome` |

`classifier/` is the reference example of a large-but-coherent slice — large enough that it splits
into subpackages internally without becoming a Maven module; see [`../modules.md`](../modules.md) § *`classifier`
splits internally*. Every classifier files into ONE `finding` table, and its own raw detections
live in per-classifier `*_detection` tables stitched at query time by `DetectionTableRegistry` (Epic 3, #1070/#1071). (Detections used
to land in a shared judgment table with grader verdicts; that table was dropped along with the rest
of grading before the migration history was squashed into the current `0000-baseline.sql` (#759).)

## Hard rules

- **Controllers live in their feature package, never in `web/`.** `web/` is the shared
  envelope + exception advice, full stop.
- **`<Entity>Controller` is singular.** `FailureModeController`, not `FailureModesController`.
- **Every domain-error enum lives in the shared module's `open/errors/`** and is registered in
  `ErrorCatalog.REGISTERED`. (Wire form `<DOMAIN>.<NAME>` is auto-derived; see
  [`backend/AGENTS.md`](../../backend/AGENTS.md) *Error handling*.) An error enum sitting in a
  feature package is a violation.
- **Bundle-schema records live in `model/`; feature-owned records live with the feature.**
  A record that exists only to serve one feature lives in that feature's package.
- **`llm/` is LLM provider/model configuration** (`ChatModelFactory`,
  `ProviderCredential{,Controller,Repository}`, `ModelCatalog`, `PlatformCatalog`,
  `LlmCaller`, `LlmPacer`; the vendor-neutral half — `ModelLane`,
  `ServiceTier` — sits one layer down in `core`'s `llmspi/`, and rates sit in
  `substrate`'s `pricing/`; `EmbeddingModelFactory` and `TextEmbedder` were removed with the rest
  of the vector substrate, #1116). `model/` is the bundle graph. The two share a word and nothing else —
  never conflate them.
- **Jackson-bound records need no reflection registration.** The backend runs on the
  standard JVM (no GraalVM native image), so Jackson reflects over records at runtime with
  no AOT hints — just add the record and it (de)serializes.

## "Where does X go?"

| You're adding… | It goes in… |
|---|---|
| A REST controller | the feature slice it serves (e.g. `cases/CaseController`), never `web/` |
| Business logic for feature F | `F/` as `*Service` / `*Executor` / etc. |
| A DB read/write for F | `F/` as `*Repository` + `*Row` |
| A request/response body | `F/` as a DTO record (`*Request` / `*Response` / `*Dtos`) |
| A record mirroring the bundle YAML | `model/` |
| A record that only feature F uses | `F/` |
| A new error code | the existing `open/errors/<Domain>Error` enum (shared module), or a new one registered in `ErrorCatalog` |
| Shared HTTP envelope/handler change | `web/` |
| LLM provider / model wiring | `llm/` |

## Deliberate boundaries (intentional, not debt)

- **`auth/` vs `tenant/`** — `auth/` owns request-time authentication *and tenancy enforcement*
  (`TenantContext`, `TenantPathResolver`, `TenantArgumentResolver`, the filters). `tenant/` owns
  org/project/user *persistence and CRUD*. The split is "enforce at the edge" vs "store and
  manage"; keep it.
- **There is one price source, and it is `price_book`.** Both hand-maintained rate tables are
  gone: `run/PricingCatalog` (a run-level cross-provider estimate with no callers) and
  `llmspi/ModelPricingCatalog` (the per-call cost). Producer telemetry and the platform's
  own spend now read the same versioned tables through `pricing/ModelResolver` +
  `pricing/PlatformCallPricer`. Do not start a third.
- **`git/` is an integration layer with no closed loop above it.** It owns the `GitProvider`
  SPI, token/app plumbing, and the webhook *signature* adapter (HMAC verify, push parsing). The
  `observer/` slice that consumed it — the push-triggered drift worker, the `DriftAnalyzer` SPI, the
  change-request writeback — was deleted with grading in Track A, along with `GitWebhookController`.
  What still reads `git/` is the repo checkout the two agentic lanes get. Cross-feature reads
  (pipeline state) go through that feature's **service facade**, not its repositories — see the
  `jdbc_client_only_in_repositories` rule and the per-feature ArchUnit layer checks.
- **`classifier/` is the only leased async worker over the trace substrate.** Every queued kind —
  `classifier`, `rca`, `triage`, `pull`, `usage_rollup`, `behavior_profile`, `sop_compile`,
  `project_delete` (and the vestigial `embedding`) — lives in ONE `job` table
  discriminated by `kind`, and the leased kinds share the generic `LeasedJobQueue` seam
  (`open/jobqueue`, `FOR UPDATE SKIP LOCKED` claim scoped by kind, expired-lease reclaim under an
  attempt cap, dead-lettering). `classifier/` is a **continuous cursor sweep over the trace
  substrate** — on a heartbeat it reads `span` rows strictly past each classifier's `cursor_at`
  high-water mark, runs a built-in `BuiltInDetector`, and records detections in **its own table** —
  one per classifier, stitched for cross-classifier reads by `DetectionTableRegistry` (Epic 3, #1070/#1071).
  When enough of them accumulate inside its arming window it files a `finding` with the flagged
  spans as evidence, which is the only way a classifier reaches a human. It is **strictly off the
  ingest hot path**: it consumes what the substrate write path produced and is never hooked into
  `SubstrateWriter.enqueue`. For its cross-feature read of the substrate
  (`span`/`span_payload`/`trace`/`session`/`tool_call`) it owns a raw-SQL
  `SubstrateReadRepository`, which the `jdbc_client_only_in_repositories` rule allows.
- **`classifier/` extension seam is *multi-impl and open to code this repo does not build*** —
  `ClassifierSweep` (in `classifier/worker/`) plus three of the four read-side ports #839 carved out
  (`TriageSource`, `CauseResolver`, `ClassifierDebugContributor` — `ProfileSource`, the fourth, moved
  into `tessary-paid/behavior-drift` with #919 and is now that module's own internal wiring, not an
  open port) are what a
  classifier attaches through, and `ClassifierSweepRegistry` indexes every sweep on the classpath by
  the detector kinds it claims. Unlike every other seam in this file, an implementation may ship from
  a jar outside this reactor: discovery is a Spring Boot `AutoConfiguration.imports` read off the
  runtime classpath, which is what lets a self-hoster run their own classifier and what lets the paid
  classifiers leave the open tree. An unregistered kind is inert, and the catalog is never filtered by
  what is registered. Full contract in
  [classifier-extension-interface.md](./classifier-extension-interface.md).
- **`alert/` channel registry is a *multi-impl* seam, not a single-provider SPI** — the
  three SPI-seam slices above (`storage`/`intelligence`/`priors`) select **one** provider via
  `@ConditionalOnProperty`. The `AlertChannel` seam under `alert/channel/` is the other
  shape: a `ChannelFactory` (EnumMap keyed by `AlertChannelKind`: slack | webhook |
  sentry | linear | pagerduty) holds **all** registered impls at once, and a fired alert
  (the AFTER_COMMIT `AlertFiredEvent`) fans out through `AlertDeliveryListener` to
  *every* per-project channel row that's enabled — mirroring `git/GitProvider` +
  `GitProviderFactory` rather than the conditional single-provider SPIs. Delivery is
  best-effort and synchronous (no retry/DLQ); the `alert_delivery_attempt` row
  (`pending` claimed before send, then updated with status/http_status/short error) is the
  at-most-once guard and the visibility surface (`GET .../alert-channels/deliveries`).
  Every send re-runs `UrlGuard.requirePublicHttp` (SSRF/DNS-rebinding defense), credentials
  are `SecretBox`-sealed and never echoed back over the API, and the generic webhook ships a
  **versioned, HMAC-signed envelope** (`AlertPayload`) with the alert inlined as a first-class
  object. Alert rules live in one `alert_rule` table (discriminated by `rule_type`); channels
  live in `alert_channel` (discriminated by `kind`), and `alert_event` carries a real
  `alert_rule_id` FK.

## The three analysis layers, and the firewall between two of them

What live traffic means is answered in three layers, each with a different instrument and a different
question. They are separate packages on purpose: collapsing any two of them collapses the question
each one is competent to answer.

| Layer | Where | Instrument | The question | Ends in |
|---|---|---|---|---|
| **1 — Detection** | `classifier/` | No model. A cursor sweep over `span`, per-classifier detectors, arming windows | *Did something move?* | A `finding` (one row per cause), plus `finding_evidence` refs enumerating the population it measured |
| **2 — Triage** | `classifier/finding/` (`BehaviorTriageEngine`, `E2bTriageSandbox`) | A cheap model in a microVM. No repo. The substrate on demand over MCP. A workspace to write check scripts in | *Does the CLAIM hold — true, sampled enough, carried by its evidence?* | `finding.triage_*`: `positive` opens a case, `negative` / `unclear` close the finding |
| **3 — RCA** | `rca/` | A strong model in a microVM. The repository when one is connected | *What change caused it?* | An `rca_report` on the case, which may conclude that no change is locatable |

**Layer 2 never judges impact.** A cost or duration *drop* passes its gate like any other sound claim,
because "improvement" is a judgment about intent and triage has no evidence for intent. Layer 2 is also
**terminal**: there is no queue behind it and no "needs review" state, which is what makes closing on
`unclear` safe — a real cause keeps firing and comes back for a second look under the recurrence rule.
A run that did not happen writes no ruling at all (NULL + a retryable job), so an infrastructure blip
can never masquerade as a decision.

**The firewall is between 2 and 3.** RCA receives the **finding id and nothing else** — never the
triage ruling, summary, citations, or the fact that a triage pass happened. This is enforced
structurally, not by convention, and it takes **three** enforcement points rather than two, because
the lane can read through any of them. The dossier holds: the RCA dossier builder has no triage
repository dependency to read one from, and a test greps the materialized dossier for triage
vocabulary. The prompt holds, pinned by `AgenticRcaPromptTest`. **The tool surface did not, until
`#830`**: the RCA agent runs with a project-scoped admin key, so `get_finding`, `list_findings` and
`get_case` handed the ruling straight back inline and one call defeated the other two.
`BehaviorFindingView.withoutTriage()` is the redaction, applied by `McpToolRegistry` to all three and
pinned by `McpFindingToolsTest`; `triageStatus` survives on purpose, since it says only whether a
ruling exists. It is applied to every MCP caller rather than to RCA-minted keys alone, because the key
family is not distinguishable at that layer and a firewall that must identify its caller has a bypass.
The reason for all of it is that "nothing happened here" must stay a supported RCA conclusion; it is
the only independent check on the gate Layer 2 applies, and an RCA told what triage already decided
cannot provide it.

Both agent layers take the same access posture: **materialize what is finding-specific, read the
substrate on demand through MCP.** Neither hydrates traces into its prompt.

## The bundle (one path in, none out)

The **evals plugin** is a Claude Code plugin maintained in a separate repo. It runs inside a Claude
Code session against a target repo, produces a `.tessary/` bundle (sharded pipeline YAML + grader
files + `report.md`), and exits. The plugin — **not** this repo — owns the bundle schema; the
platform is a *consumer*. The plugin's contract (`output_format.md`, `pipeline_io.py`, the JSON
schemas) is vendored into `contract/` (repo root) by `scripts/sync-evals-contract.sh`, and
`contract/VERSION` records the plugin commit + schema version we support. When the plugin ships a
release that changes the output, re-vendor and absorb it following
[upgrade-contract.md](../guides/upgrade-contract.md) — the order is contract → backend records →
frontend types → views.

**The platform reads the pipeline half only.** Track A deleted the in-stack `synth/` slice, the
judge runtime and everything that could execute a grader, so `BundleAssembler` routes the bundle's
grader and quality-dimension shards to `Shard.IGNORE` rather than rejecting the bundle. That is
deliberate: the plugin is public and still emits them, and a hard reject would make every current
bundle un-importable to buy nothing.

## The API contract (code-first OpenAPI)

The wire API is **code-first**: springdoc (wired in `core`'s `apidoc/OpenApiConfig`) generates
an OpenAPI spec from the controllers and DTOs at build time, and it is **checked in** to the
`contract` module at `backend/contract/src/main/resources/openapi/evals-api.json`. A drift test
(`apidoc/OpenApiSpecDriftTest`) fails the build if the generated spec diverges from the committed
one, so the checked-in JSON is always current. The frontend's TypeScript types are **generated
from that spec** — `frontend`'s `pnpm run generate:api` runs `openapi-typescript` over
`evals-api.json` into `src/api/generated/schema.d.ts`. Regenerate and commit the spec whenever a
controller or DTO changes; the frontend types follow from it.

## Module inventory

Keep in sync with `backend/*/src/main/java/ai/tessary/evals/*/`. The package tree is the
source of truth — recount with `ls` when the set changes; **do not date-stamp** this section.
Class lists live in code (and ArchUnit); this table is purpose + entry pointer only.

### Maven modules

The reactor `backend/pom.xml` builds eleven modules in dependency order. Each depends only on the
one below it, so Maven — not a convention — is what stops a lower layer importing a higher one.
`shared` and `contract` are **open** modules (extractable to open source; a `maven-enforcer`
bannedDependencies rule keeps them free of any app/commercial dependency).

| module | holds |
|---|---|
| `app` | `EvalsApplication`, `application.yaml`, every `@SpringBootTest` integration test |
| `surfaces` | `query`, `search`, `mcp`, `ci`, `slack` (the wire surface only), `metering`, `billing`, `telemetry` (the heartbeat orchestrator half — see `core`'s `telemetry` below) |
| `analysis` | `classifier`, `onboarding`, `rca`, `cases`, `alert`, `prompt` |
| `llm-runtime` | `llm`, `priors`, `sandbox` (agent-span telemetry only) — the only module that declares a model-provider SDK |
| `product` | `pipeline`, `gate`, `sop` (the `SopIntake` seam only), `sopcompile`, `plan` |
| `substrate` | `storage`, `ingest`, `redaction`, `retention`, `pricing`, `sources`, `traces`, `git`, `usage`, `vitals` |
| `tenancy` | `tenant`, `auth`, `edition`, `featureflags`, `version` |
| `core` | `web`, `model`, `apidoc`, `ops`, `crypto`, `db`, `config`, `telemetry` (the transport/config/install-id half — see `surfaces`'s `telemetry` above), `llmspi`, the Liquibase changelog, the schema-column generator |
| `test-support` | `TestPostgres` + its context initializer; test scope everywhere |
| `contract` | `open/contract` — the checked-in canonical OpenAPI spec |
| `shared` | `open/{errors,jobqueue,media,obs}`, `detection/` (per-classifier detection-table registry: `DetectionTable`, `DetectionTableRegistry`) |

The quality gate — surefire, compiler/NullAway, forbiddenapis, SpotBugs, PMD, Spotless — is declared
once in the parent's `pluginManagement`; a module activates it with a bare `<plugin>` stub and cannot
silently opt out. [`../modules.md`](../modules.md) carries the layering rationale.

### Feature packages

Which module a package lives in is the layer it sits at; see the table above for what each module
holds. A package appears in exactly one module.

| Package | Module | Purpose | Entry |
|---|---|---|---|
| `alert/` | `analysis` | alerting engine: WHEN to fire + WHERE it lands (channel registry). Four rule types — a threshold over classifier detections, digest/brief roll-ups, and `case_opened`, which is the only one that covers the launch detectors (they write no detections to count). | [alerting.md](../concepts/alerting.md) |
| `apidoc/` | `core` | code-first OpenAPI config: `OpenApiConfig` (stable operationIds), `JSpecifyNullabilityConverter`, `OpenApiSpecDriftTest` (in tests)… | — |
| `auth/` | `tenancy` | WorkOS AuthKit login, request filters, tenancy enforcement. | [auth-and-mcp.md](./auth-and-mcp.md) |
| `billing/` | `surfaces` | the org usage rollup behind `BILLING_MANAGE` — cross-project metered totals, no charging integration (#883 deleted self-serve billing outright). | `GET /api/orgs/{orgSlug}/billing` |
| `cases/` | `analysis` | incident cases: grouping related findings into one investigable unit with a lifecycle, plus the case page's assembly — the ruling read off the finding, before/after exemplars, and the absorb verb that moves the detector's reference. | — |
| `ci/` | `surfaces` | the pre-deploy check surface (`PreDeployCheckController` → the open `product/gate` slice). The eval-delta / risk-forecast / merge-gate / PR-comment half went with grading in Track A. | `/predeploy-checks` |
| `classifier/` | `analysis` | async classifier-detection engine (Layer 1), plus the Layer-2 triage lane that rules on what it files. | [The three analysis layers](#the-three-analysis-layers-and-the-firewall-between-two-of-them) |
| `config/` | `core` | cross-cutting `@ConfigurationProperties` (~25 per-feature classes) + `AsyncConfig` (bounded virtual-thread executors),… | [config-keys.md](./config-keys.md) |
| `crypto/` | `core` | `SecretBox` (AES-GCM seal/open for at-rest secrets), `CryptoConstants`. | — |
| `db/` | `core` | `DataSourceConfig` (HikariCP against `EVALS_JDBC_URL`), `LiquibaseConfig`, SQL helpers (`SqlFilter`, `Upsert`). | — |
| `edition/` | `tenancy` | which edition (open vs. paid-overlay) this JVM is running, derived from the classpath, no property/env var behind it — `Edition`, `EditionConfig`, `EditionBanner`. | — |
| `featureflags/` | `tenancy` | the flag-override seam under the capability layer; holds no defaults of its own. Open adapter reads `org_feature_flag`; the LaunchDarkly adapter is paid. | — |
| `gate/` | `product` | the pre-deploy gate's finding store (`PreDeployCheck*`), read by the `surfaces/ci` controller. | — |
| `git/` | `substrate` | Git provider SPI (GitHub first). | — |
| `ingest/` | `substrate` | normalizing raw trace data on the write path. | `/v1/traces` |
| `llm/` | `llm-runtime` | LLM provider/model configuration (see *Hard rules*): `ProviderCredential{,Controller,Repository}`, `ChatModelFactory`,… | — |
| `llmspi/` | `core` | the provider-agnostic model seam — `ModelLane`, `LaneGroup`, `ServiceTier` (`TextEmbedder` sat here too, until #1116 removed it with the rest of the vector substrate). Everything above `llm-runtime` reaches a model through these types and never sees a vendor class. There is no per-lane default model: `llm/LanePriority` (in `llm-runtime`, since it names `ModelProvider`) orders the providers each lane reaches and the models it offers on each, and the org's configured credentials pick from that. Rates are NOT here — they are rows, in `substrate`'s `pricing/`. | — |
| `mcp/` | `surfaces` | Model Context Protocol server (Streamable HTTP). | [auth-and-mcp.md](./auth-and-mcp.md) |
| `metering/` | `surfaces` | usage metering: rollup worker + validated read API, plus the per-call `llm_call` ledger (token buckets + cost per lane) the org LLM-usage read aggregates. | `/v1/usage` |
| `model/` | `core` | the pure record graph mirroring the plugin's bundle schema (`Pipeline`, `CallSite`, `Chain`, `FailureMode`, `TaxonomyNode`,… | — |
| `onboarding/` | `analysis` | the ladder from no ingest key to first case, as an ordered `OnboardingStage` (not_connected → listening → fitting → watching → finding → case) rather than independent booleans, so the surface says one thing at a time and never walks backwards. | `/api/orgs/{orgSlug}/projects/{projectSlug}/onboarding` |
| `ops/` | `core` | repository-only persistence for operational entities (no controller/service):… | — |
| `pipeline/` | `product` | the imported `.tessary/` bundle, DB-backed per project. | — |
| `sop/` | `product` (seam) + `tessary-paid/sop` | verbatim intake of plugin-authored SOP policy files (`.tessary/sops/*.yaml`): digest-idempotent `sop_document` store + the `sop_compile` job enqueue and its worker, all paid since #842. What stays open is the `SopIntake` port both import paths call through `SopIntakeDispatch`, and `SopCompileException`, which the open `SopCompiler` port declares. | — |
| `sopcompile/` | `product` | the `SopCompiler` SPI alone, in a package of its own. It sits here rather than in `sop/` because #842 took `sop/`'s queue half paid while the interface has to stay open. Both ends are now paid and in DIFFERENT overlay modules (`tessary-paid/sop`'s worker calls it, `tessary-paid/conformance` implements it), so this package is the neutral ground that keeps them from needing an edge to each other. | — |
| `plan/` | `product` | capabilities (the single gating axis). Plan tiers and quotas moved to `tessary-paid/plan`; the open edition is uncapped. | — |
| `priors/` | `llm-runtime` | cross-customer aggregated/anonymized priors, governed by `evals.intelligence-mode.single-tenant`. | — |
| `query/` | `surfaces` | aggregation-first query API over the substrate with allow-list validation. | `/v1/query` |
| `rca/` | `analysis` | root-cause analysis: pressed on a case, reads the finding's CLAIM (never any triage ruling — the context firewall) and its evidence over MCP → grounded hypotheses + ruled-out checks. | [The three analysis layers](#the-three-analysis-layers-and-the-firewall-between-two-of-them) |
| `redaction/` | `substrate` | PII redaction: rule authoring/testing + write-path guard (`RedactionService.redactBatch` called by the `SubstrateWriter` drainer, immediately before the write). | [pii-redaction.md](../concepts/pii-redaction.md) |
| `retention/` | `substrate` | Retention enforcement: `RetentionResolver` (platform default TTL, overridden by a `retention_policy` row), `RetentionSweeper` (the bounded hourly delete), and `RetentionController` (Settings → Data retention: reads the resolver, writes the override rows; `RETENTION_MANAGE` to write, #1205). One resolver, so the sweep and the customer-facing answer cannot differ. | [ingest-runbook.md](../guides/ingest-runbook.md) |
| `pricing/` | `substrate` | Model identity + versioned rates: `PriceBookImporter` (idempotent boot + daily import of the checked-in snapshots into `price_book`/`model_price`), `ModelResolver` (reported model name → `model.id`, exact → region-strip → vendor-strip), `PriceBookRepository`. The manual override book layers over the vendored LiteLLM one. | — |
| `prompt/` | `evaluation` | prompt prose read from `prompt-craft/<purpose>/` on the classpath; the resource-owning module ships its own. Only prose lives there, never composition logic. | [prompt-craft.md](./prompt-craft.md) |
| `sandbox/` | `llm-runtime` | `AgentSpanTelemetry` and nothing else: the Langfuse-shaped generation stamping every agentic lane emits through. The out-of-process grader-JS runner SPI (`SandboxRunner` + its E2B/Lambda implementations) went with grading in Track A. | — |
| `search/` | `surfaces` | global ⌘K search: one tenant-scoped full-text read over a project's content entities, feeding the command palette. | `/api/orgs/{orgSlug}/projects/{projectSlug}/search` |
| `slack/` | `tessary-paid/slack` | The PLATFORM half of Slack: workspace→project routing (`slack_install`), the `Capability.SLACK` gate, what to reply to an `@mention`, and the channel post — all paid since #842, joined by the route itself in #920 once the per-edition spec decision (#917) retired the reason it had to stay open. With no implementation on the classpath, the endpoint does not exist and `AuthFilter` does not bypass its path either — "not bypassed and not served." The open seam that remains is `auth/SelfAuthenticatingPath` (`tenancy`), the port `AuthFilter` consults instead of a hard-coded literal; `tessary-paid/slack/SlackAuthBypass` is its one implementation. The Slack protocol itself lives out of process in `tessary-paid/slack-service/` (private) — nothing here can reach Slack. | `POST /internal/slack/mention` (adapter callback, paid) |
| `sources/` | `substrate` | persistence + REST for `IngestionSource`. | — |
| `storage/` | `substrate` | the streaming trace substrate (see *SPI-seam slices* above). | — |
| `tenant/` | `tenancy` | orgs/projects/users, membership, API keys / MCP tokens (one `api_key` store via `ApiKeyService`), RBAC. Environments were removed in Track A: a project is the only scope below an org. | [auth-and-mcp.md](./auth-and-mcp.md) |
| `telemetry/` | `core` + `surfaces` | the `home.tessary.ai` heartbeat ping, replacing `analytics` (#858): `core` holds `TelemetryProperties`/`HomeTessaryClient`/`InstallIdRepository` (no `tenancy`/`substrate` dependency to build on), `surfaces` holds `TelemetryHeartbeat`/`TelemetryBuckets` (the `@Scheduled` orchestrator, next to `MeteringWorker`, which needs both). | [telemetry-contract.md](./telemetry-contract.md) |
| `traces/` | `substrate` | read-side trace explorer over the substrate. | — |
| `usage/` | `substrate` | the metered record of consumption — rollups and the per-call LLM ledger. The grading spend ceiling and its breaker went with grading in Track A; nothing here caps anything now. | — |
| `version/` | `tenancy` | project-version timeline (one row per commit SHA). | — |
| `vitals/` | `substrate` | token and cost accounting over observed spans — `TokenUsage`, `TokenPriceBook` and the pricing catalogue behind them. | — |
| `web/` | `core` | shared HTTP plumbing ONLY: `ApiResponse` envelope, `ResponseMeta`, `ErrorBody`, `GlobalExceptionHandler`. | — |
