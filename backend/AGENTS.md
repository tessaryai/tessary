# Backend agent guide

Imperative rules for any agent touching `backend/`. The backend is Spring Boot
4.0.x on Java 25 (JVM + Loom virtual threads), Maven **multi-module**:

The reactor is eleven modules; dependencies flow downward only and Maven enforces it, so a module
can only import what sits below it. Full rationale in [`devdocs/modules.md`](../devdocs/modules.md).

- `shared/`, `contract/` — open foundation: `ai.tessary.evals.open.{errors,jobqueue,media,obs}` and
  the checked-in canonical OpenAPI spec.
- `test-support/` — `TestPostgres` + its context initializer. Test scope everywhere; holds only
  fixtures that depend on nothing in the platform, plus `OpenApiCanonicalizer`, the one canonical form both
  OpenAPI spec guards pin through.
- `core/` — `web`, `model`, `apidoc`, `ops`, `crypto`, `db`, `config`, `analytics`, `llmspi`, plus the
  Liquibase changelog and the schema-column generator that reads it.
- `tenancy/` — `tenant`, `auth`, `featureflags`, `version`. Declares bcrypt. The `featureflags` seam
  holds NO defaults — the open adapter is `org_feature_flag` rows and the capability default lives in
  `product/plan/CapabilityService`; the LaunchDarkly adapter is in `tessary-paid/plan`.
- `substrate/` — `storage`, `ingest`, `redaction`, `retention`, `pricing`, `sources`, `traces`,
  `git`, `usage`, `vitals`.
- `product/` — `pipeline`, `gate`, `plan`, `sop`, `sopcompile` (the open `SopCompiler` seam, kept out
  of `sop/` because that package goes paid).
- `llm-runtime/` — `llm`, `priors`, `sandbox` (agent-span telemetry only). The ONLY module that
  declares a provider SDK.
- `analysis/` — `classifier`, `rca`, `cases`, `alert`, `onboarding`, `prompt`.
- There is no `evaluation/`. It held judge, run, compile, synth, regrade, experiment, review and
  annotation, and Track A deleted all of them; the reactor is eleven modules.
- `surfaces/` — `query`, `search`, `mcp`, `ci`, `metering`, `billing`, `telemetry` (`slack`'s wire surface left for `tessary-paid/` in #920).
- `app/` — `EvalsApplication`, `application.yaml`, and every `@SpringBootTest` integration test
  (they need the `@SpringBootConfiguration` only this module has). A PAID `@SpringBootTest` lives in
  `tessary-paid/assembly`, which depends on this module's plain jar.

Plus one reactor member outside `backend/`: `tessary-paid/`, the closed-source overlay (open-core
decision D2). It joins the reactor via a file-activated `paid` profile in `backend/pom.xml`, and
every open module inherits an enforcer rule that fails the build on a dependency into it — open
never depends on paid, so reach for an SPI with an open default instead. That also means `app` cannot
wire a paid bean through the build: paid code reaches a running backend only via a Spring Boot
`AutoConfiguration.imports` in the paid jar, read off the runtime classpath (#881). `task backend:check:open`
is the open edition alone. Details in [`docs/modules.md`](../devdocs/modules.md#the-paid-overlay-tessary-paid).

The app is organized **package-by-feature** (vertical slices), not
package-by-layer: every package owns its controller → service → repository →
DTOs, or is cross-cutting infra (`config`, `db`, `crypto`, `web`), or the pure
`model/` record graph. [`docs/reference/architecture.md`](../devdocs/reference/architecture.md)
is the authoritative convention + full per-package inventory — read it before
adding a package or a controller. Config-key map:
[`docs/reference/config-keys.md`](../devdocs/reference/config-keys.md).

**Find the rule:** [Java](#java-rules) · [Errors](#error-handling) · [LLM](#llm-integration) · [Multimodal](#multimodal-traces-images) · [Logging](#logging--observability-conventions) · [Pipeline](#pipeline--curation-db-backed-project-scoped) · [Auth](#auth--tenancy) · [Static analysis](#static-analysis) · [Testing](#testing) · [Recipes](#recipes) · [Commit](#before-you-commit)

## Java rules

- **NEVER use raw `Object` / `var` at API boundaries.** Strong types on records, controllers, services. The only place `Object` is acceptable is the `applies_to` field on `ImplicitInvariant` (polymorphic by design: string or list).
- **Never throw raw `RuntimeException`** from services or controllers. Wrap with `EvalsException(ErrorCode, args)` so the wire response stays consistent.
- **Snake_case on the wire, camelCase in Java.** `@JsonProperty("snake_case")` on every record field that doesn't already match Jackson defaults. This is YAML-format compat — the `.tessary/` bundle and `curation.yaml` are the binding artifacts.
- **JVM runtime + Project Loom (no native image).** Reflection and dynamic class loading just work — Jackson-bound records need no registration. Bias I/O-bound fan-out toward virtual threads, but keep a concurrency bound (a `SimpleAsyncTaskExecutor` concurrency limit or a `Semaphore`) on anything that hits a rate-limited upstream (Bedrock/Anthropic/GitHub) or the bounded JDBC pool — see `config/AsyncConfig`. Use only stable Loom APIs (`Executors.newVirtualThreadPerTaskExecutor()` / `SimpleAsyncTaskExecutor.setVirtualThreads(true)`); no preview `StructuredTaskScope`, no `--enable-preview`.
- **All config via `@ConfigurationProperties`** (env-var prefix `EVALS_*` through relaxed binding). No hardcoded paths; no `@Value` outside narrow per-bean cases. The documented exceptions and the full prefix → class map live in [`docs/reference/config-keys.md`](../devdocs/reference/config-keys.md).

## Error handling

- **Every error path returns the `ApiResponse` envelope.** Never return `ResponseEntity.notFound()` / `.badRequest()` / bare HTTP statuses from a controller. Throw `EvalsException(ErrorCode, args)` and let `web/GlobalExceptionHandler` build the response.
- **Hierarchical error codes**: per-domain enum implementing `ErrorCode`. Wire form is `<DOMAIN>.<NAME>` — the domain is auto-derived from the enum's declaring class (`PipelineError` → `PIPELINE`). The system lives in the **shared module**: `backend/shared/src/main/java/ai/tessary/evals/open/errors/` (`ErrorCode`, `EvalsException`, `ErrorCatalog`, all per-domain enums).
- **Adding a new error domain**: create the enum in `open/errors/` (each constant: `(HttpStatus, "message template with %s")`), register it in `ErrorCatalog.REGISTERED`. Compiler enforces within-domain uniqueness; `ErrorCatalog`'s `@PostConstruct` enforces cross-domain uniqueness at boot.
- **`@Valid @RequestBody`** at the controller boundary only. Services receive validated records and assume invariants — drop manual null/blank checks once a field has Jakarta constraints. The handler turns `MethodArgumentNotValidException` into `COMMON.VALIDATION_FAILED` with `details: { field: message }`.

## LLM integration

- **All LLM calls go through LangChain4j's `ChatModel`.** Never instantiate a provider-specific SDK directly. Models are built (and cached) per project by `llm/ChatModelFactory` from the active provider credential (`llm/ProviderCredential*` — one key per provider, configured in Settings → Providers). `LlmCaller` (in `llm/`, the `llm-runtime` module) is the one LLM-call seam — it wraps `ChatModel.chat` in a GenAI OTel span (cost/usage); route new call sites through it.
- **Model selection lives in the DB, not Spring profiles.** Pickable (platform, model) pairs are curated in `llm/ModelCatalog` + `llm/PlatformCatalog`; `ChatModelFactory` resolves project default → system default → the platform's own ambient Bedrock default. Adding a platform = a `PlatformCatalog` descriptor + a `ModelCatalog` entry + (if not OpenAI-wire-compatible) a build branch in `ChatModelFactory` using the matching LangChain4j module. Only local Ollama runs on the platform's own credentials. `GenerationModelResolver` picks the platform-funded model for in-process utilities (issue/intent naming).
- **The platform-funded lanes span TWO Bedrock endpoints, and a model's own descriptor says which.** `llm/BedrockModelProfile` is the capability matrix for the models the platform funds; its `endpoint` field — not the provider enum — decides the build path, because `bedrock-runtime` (Converse, cross-region inference profiles, explicit cache points) and `bedrock-mantle` (OpenAI Responses, bare model ids, implicit caching) differ in host, wire, SigV4 service name, IAM action and region. Adding a platform model is now one entry there and nothing else — **there is exactly one price source in the codebase and it is the `price_book` / `model_price` tables** (0075). Ingested spans price from it on arrival and never again; the platform's own spend (`llm_call`, `verdict.cost_usd`, `LlmCaller`) prices from the same book through `pricing/ModelResolver` + `pricing/PlatformCallPricer` and stamps `llm_call.price_book_version` (0084). A rate is changed by a reviewed diff to `pricing/litellm-model-prices.json`, never by a table in Java — if you find yourself typing a dollar figure into a `.java` file, that is the bug. There is no hand-maintained correction file any more (#1032 retired `manual-overrides.json`); a model LiteLLM prices under a different spelling than a producer reports it (mantle's `bedrock_mantle/` route is the precedent) needs a producer-side fix, not a pricing-layer override. Two things do not follow the SDK's defaults: mantle requests are signed by `llm/MantleHttpClient` (langchain4j's OpenAI client cannot sign), and every mantle model must set `store(false)` or Bedrock retains the request and response for 30 days. Capabilities are transcribed from what the endpoint actually accepts — `scripts/probe_mantle_capabilities.py` and `scripts/probe_nova_capabilities.py` are how, and both are re-runnable gates.
- **Pinned to LangChain4j `1.14.1`.** Bump only when there's a feature we want.
- **Structured outputs**: build `ResponseFormat` with `JsonObjectSchema` for the response bean. Strict-mode catalog entries enforce the schema at the token level; other providers fall back to best-effort prompt augmentation.
- **Provider rate limits are real** for the paced compat tiers (OpenRouter/Ollama/Moonshot) — `LlmPacer` is the sliding-window limiter (RPM + TPM, backoff on 429). `SelfTestRunner` is sequential by design — don't add parallelism without retry/backoff.
- **System prompts live in Java text blocks** near their caller. Move to resources only if prompt iteration gets painful.

## Multimodal traces (images, documents)

Traces carry media through the whole pipeline (ingest → store → display → export). v1 ingests and renders **text, images, and PDF documents**; other modalities (audio, video) are deferred behind an explicit-failure seam. Full contract: [`docs/reference/media-contract.md`](../devdocs/reference/media-contract.md); schema detail: [`docs/reference/trace-schema.md`](../devdocs/reference/trace-schema.md).

- **There is no model-facing media boundary left.** Track A removed grading, and with it the judge's request build — the one place a `ContentBlock` was mapped into an LLM message, and the one place an unsupported block type was rejected (`JUDGE.UNSUPPORTED_CONTENT_TYPE` → 422) rather than silently flattened. `llm/ContentBlocks`, which did that mapping, had no caller afterwards and is deleted; `JudgeError.UNSUPPORTED_CONTENT_TYPE` / `MEDIA_NOT_FOUND` stay in the wire catalogue with nothing raising them. What survives is the ingest-and-read half: a new modality arrives by adding a routed case in `ContentExtractor`, never an unrouted `ContentBlock` constant. `ContentBlock.isMedia()` is the single source of truth for "is this a media block" (`isImage()`/`isDocument()` narrow to a specific modality — see their own doc for why they stay separate predicates).
- **Producers emit media inline** (`data:` URI, raw base64 content block, or an `https://` URL) in `RawEntry`; `ingest/MediaResolver` resolves out-of-band provider media refs that still appear in some dialect payloads (e.g. legacy Langfuse/Braintrust token shapes) — always on, cheap no-op when a payload has no media. Large payloads externalize through the `MediaStore` SPI (`shared` `open/media/`, default `storage/PostgresMediaStore`) via `ingest/MediaExternalizer`, which also runs `open/media/PdfTextExtractor` once per document at ingest so the persisted `document_ref` node carries pre-extracted text.
- **Every outbound media fetch passes `ingest/UrlGuard.requirePublicHttp`** (SSRF guard: rejects RFC1918, link-local, IMDS) and a streaming byte cap (`evals.ingest.*` bounds). `spring.servlet.multipart.max-file-size` is 25 MB (== `max-request-size`) so a JSONL row carrying a base64 image/document clears Tomcat before the parser runs.
- **Export fidelity (`ingest/export/TraceSpanMapper`)**: real bytes are inlined as a base64 `data:` URI in the existing plain-string content field for both images and documents where recoverable (inline base64 needs no store; a `_ref` block rehydrates via `MediaStore`), falling back to a labeled placeholder (`[image: <url>]` / `[document omitted: <mediaType>]`) + a `has_media:true` flag when they cannot be — lossless-or-labeled, never a silent collapse.

## Logging + observability conventions

SLF4J + Logback; the `production` profile ships JSON to Grafana Alloy → Loki. The obs infra (`LogContext`, `Markers`, `MdcTaskDecorator`, `OpsOrWarnLogFilter`) lives in the shared module at `open/obs/`.

- **Manual SLF4J loggers, no Lombok.** `private static final Logger log = LoggerFactory.getLogger(X.class);` — one declaration style everywhere.
- **`StructuredLog`, with a human message AND structured fields.** Both, always — they serve different readers and repeating a value in each is deliberate, not duplication to remove.
  - `.message(...)` is a **sentence about what happened**, in the words someone tailing a terminal wants: `"swept 120 observations for groundedness in 41ms, 3 fired"`. Not an identifier. The dotted event name travels as an `event` field, where it still groups and filters.
  - `.field(k, v)` carries the same facts structured, so Loki can filter and graph them. Stable, terse keys (`findingId`, `caseId`, `durationMs`) consistent with the MDC keys.
  - Emit them as **key-value pairs, never interpolated into the message**. Both egress paths already understand them (`LogstashEncoder` renders JSON fields; the OTel appender has `captureKeyValuePairAttributes`), and Loki's native OTLP ingestion turns every attribute into structured metadata with no allowlist. A `key={}` baked into the message string looks structured and is not: it cannot be filtered or graphed, only regexed.
- **Log OUTCOMES and COST, not intent.** A line that says work *started* earns its place only at DEBUG. On 2026-07-31 `signal.sweep.start` + `signal.sweep.empty` were **77% of all production log volume** and said nothing an operator could act on. The completion line — what was scanned, what fired, how long it took — carries every fact the start line did, plus the answer.
- **Anything whose cost scales with size must log SIZE and DURATION.** Row counts hide byte volume: the ingest path looked trivial by span count while carrying megabytes of inline base64 per entry, and the redaction package logged *nothing at all* — so a CPU saturation that pinned both vCPUs took a profiler to locate rather than a grep. `durationMs` and `bytes` as numeric fields (unquoted, so they are graphable, not merely greppable).
- **Every new feature ships with enough logging to debug it at every step, in the same PR.** For each stage of a new flow ask: if this stalls, errors, or silently does nothing in production, what line tells me — and does it say *which* step, *how much* work, and *how long*? If the answer is "attach a profiler" or "read the code", the logging is not done. Absence of logs is the failure mode that costs the most: nothing alerts, nothing appears wrong, and the first symptom is an outage.
- **Level semantics:** ERROR — a job/request failed in a way that needs a human; always pass the throwable as the last arg. WARN — a recoverable degradation we tolerated (one unit failing is WARN; the whole job failing is ERROR). INFO — lifecycle milestones and per-unit outcomes safe at volume (bounded cardinality). DEBUG — developer detail, off in prod; when unsure between INFO and DEBUG, pick DEBUG. TRACE — effectively unused.
- **No credentials, tokens, or PII at any level.** Log a fingerprint/prefix or a count, not the value (see `SecretBox`'s key-fingerprint pattern).
- **WARN+ egress to Loki is a data-egress surface.** Only WARN+ or `Markers.OPS`-marked events leave the box; plain INFO stays local. Keep egressed messages categorical (ids and counts, not free text). The OTEL appender captures attached throwables' messages + stacks, so for exceptions whose message embeds user/model content (e.g. Jackson parse failures echoing model output) log categorically with **no** throwable attached and put detail at DEBUG. For infrastructure exceptions (DB, IO, upstream) the throwable arg is the right call.
- **Bind business context with `LogContext`, don't repeat ids in every message.** Async jobs start with empty MDC — open `try (var ctx = LogContext...)` at the top of the async entrypoint; executors propagate MDC via `MdcTaskDecorator`. New MDC keys must be added to both lists in `app/src/main/resources/logback-spring.xml` (`includeMdcKeyName` + `captureMdcAttributes`) or they're silently dropped.
- **Mark safe-to-ship operational INFO with `Markers.OPS`** — only events whose *every* field is PII-free and bounded.
- **Platform OTel / Langfuse names are static kebab product verbs** (`rca-run`, `layer2-triage`, …) — never entity display names; put identity in observation metadata. Full inventory + rules: [`docs/reference/telemetry-naming.md`](../devdocs/reference/telemetry-naming.md).

## Pipeline (DB-backed, project-scoped)

- **The DB is the runtime source of truth.** The `.tessary/` bundle is an *import format* only — `POST .../import` classifies the multipart shards into a `Pipeline`; `PipelineRepository.upsert(...)` writes them.
- **Reading: always go through `pipelineService.getPipeline(projectId)`.** Returns `Pipeline.empty()` for fresh projects; treat empty as a normal state.
- **The bundle still carries shards this tree cannot use.** The plugin is public and keeps emitting grader and quality-dimension shards; `BundleAssembler` routes them to `Shard.IGNORE` rather than rejecting the bundle. Do not "fix" that into a hard reject — it would make every existing bundle un-importable and buy nothing.
- **There is no curation overlay.** `curation_entry`, `CurationService` and the accept/edit/reject model went with graders in Track A. An imported pipeline is what the bundle says it is.

## Auth + tenancy

Factual inventory: [`../devdocs/reference/auth-and-mcp.md`](../devdocs/reference/auth-and-mcp.md).

- **WorkOS AuthKit** drives sign-in. Local AES-GCM sealed cookie session (`SessionCipher`, name `evals-session`, SameSite=Lax); no JWKS fetch on the request path — refresh against WorkOS only past expiry.
- **Tenancy model**: `app_user → org_membership → organization → project`. There is no scope below a project — Track A removed the `environment` concept outright. Every domain table carries `project_id NOT NULL`. Cross-project leaks are guarded by `TenantPathResolver` at the controller boundary — a controller resolves `resolver.requireProject(...)` before touching data.
- **MCP + headless API** share one `api_key` store via `tenant/ApiKeyService` (`tsy_w_` / `tsy_q_` / `tsy_a_…`). Settings → MCP tokens and the plugin device-link mint admin-scoped keys; Settings → API keys mint scoped keys. `AuthFilter` populates `TenantContext`; every MCP tool reads `ctx.projectId()`.
- **`AuthFilter.shouldNotFilter`** bypasses `/auth/login|callback|logout`, `/auth/link/start|poll` (plugin device handshake; the secret `device_code` is the credential), and `/actuator/health` plus its two probes, enumerated exactly — **not** the rest of `/actuator/`, **not** health groups/components (`show-details: always` must not publish `/actuator/health/db`), and **not** the bare `/actuator` index, which Spring Boot serves as a HAL listing of every exposed endpoint and which `startsWith("/actuator/")` does not match (#929). Widening `management.endpoints.web.exposure` therefore cannot widen the unauthenticated surface. `/mcp` is bearer-only (cookies ignored). Since #924 an unconfigured WorkOS does NOT by itself bypass anything: the filter additionally requires `EVALS_AUTH_DISABLED=true`, and without it every guarded path answers 401. Absent configuration is the normal state of an open-edition self-host, so it is not read as consent. In the `production` profile `AuthRequiredInProdGuard` still refuses to boot with no provider at all.
- **Org membership** CRUD lives in `OrganizationController` (owner-gated; last-owner demotion/removal blocked). Inviting an email that has never authenticated creates a pending `org_invitation`, consumed into a membership on that address's first sign-in (`TenantService#consumePendingInvitations`, matched case-insensitively).
- **Sign-up policy** (#1226): `SignupPolicyService#admit` runs after a provider has authenticated someone and before any principal, org or project exists for them, on all three creation paths (`POST /auth/signup` ahead of the password provider's insert, `POST /auth/login`'s first-time path, `GET /auth/callback`). Invariants: an existing principal is never refused; the first account is always admitted; a pending invitation admits in every mode; the policy is instance-wide and lives on the install's first organization's `settings.signupPolicy`, written only through `PUT /api/orgs/{slug}/signup-policy` (the raw settings PATCH refuses it). A refusal is `AUTH.SIGNUP_REFUSED` and writes nothing.

## Static analysis

The conventions above are **machine-enforced** — `mvn -B verify` (and CI) hard-fails on a violation. All tools are build-time only.

- **Spotless** (palantir-java-format) — `task backend:format` fixes formatting in place.
- **ArchUnit** (`app/src/test/.../arch/ArchitectureRulesTest`) — the package-by-feature rules as JUnit tests (controllers-in-features, `web/`-plumbing-only, `model/`-pure-data, error codes in `open/errors/`, loggers `private static final`, `JdbcClient`-only-in-`*Repository`), plus the `ErrorCatalog.REGISTERED` parity check. **Add a rule when you add a convention.**
- **forbidden-apis** — bans default-charset/locale APIs (pass `Locale.ROOT`), `System.out/err`, `java.util.logging`.
- **SpotBugs** + **PMD** (`backend/config/`) — fix real findings, don't add silent suppressions.
- **Error Prone + NullAway** — compile-time; NullAway package set is the `AnnotatedPackages` arg in the parent `backend/pom.xml` (widen one package at a time as packages get JSpecify `@Nullable`).
- **OpenAPI drift**: any controller/DTO change that shifts the wire contract requires `task contract:openapi` to regenerate the checked-in spec — `OpenApiSpecDriftTest` fails `backend:check` until you do.

## Testing

Two tiers. Keep them clean — don't mix. **Do not add or modify tests unless explicitly requested** (root rule; recap because it bites here most).

### Tier 1 — unit tests

`*Test.java` colocated with the class under test, no Spring context, milliseconds. Use for pure logic with non-trivial branching: cipher round-trips, slug/ULID generators, JSON-RPC dispatch, overlay merging, content-block parsing. JUnit 5 + plain `assertX`.

### Tier 2 — integration tests

`@SpringBootTest`-driven; each Spring context gets a uniquely-named database in the JVM-singleton pgvector Testcontainers Postgres (`db/TestPostgres`, wired by `TestcontainersPostgresInitializer` via `META-INF/spring.factories`); Liquibase applies per context boot. **Requires Docker.** Prefer two classes with distinct concerns over one giant fixture. Bootstrap tenants via `testsupport.TenantFixture.bootstrap(...)` — widen the helper rather than fork it.

**A distinct `@SpringBootTest(properties = …)` or unique `@DynamicPropertySource` fingerprint is a distinct Spring context, and each one costs a fresh database plus a full Liquibase run (~15s).** Reuse the shared fingerprint unless the properties are the point of the test — that single decision dominates how long the suite takes. Cost model: [`../devdocs/reference/test-suite.md`](../devdocs/reference/test-suite.md).

MockMvc in Boot 4: build manually (`MockMvcBuilders.webAppContextSetup(wac)`) and **explicitly add filters** (`addFilters(authFilter)`) — Boot 4 dropped `@AutoConfigureMockMvc`.

### What to test

Anything that could silently break a pipeline: identifiers/slugs (collisions, truncation edges), crypto round-trips (tampered ciphertext, wrong key), token verification (revoked rejection, `last_used_at`), tenant isolation (every cross-org/cross-project path), idempotency (auth callback twice must not duplicate rows), JSON-RPC wire semantics (protocol codes vs `result.isError`), repository round-trips (**insert a fully populated row, read it back, assert every field** — the one repository bug class that consistently bites).

### What NOT to test

Record constructors/getters; controllers that are pure glue; framework wiring (`ContextLoadsTest` covers it once); external HTTP clients (mocking `HttpClient` to test our own JSON proves nothing); negative paths already guarded by the type system.

### Conventions

- One class per test class, `<ClassUnderTest>Test` (no `*Tests`); `@Nested` only when it maps to nested behaviour.
- Method names read as sentences: `verify_rejectsRevokedToken`. No `testX` prefixes.
- Assertion messages whenever the failure mode isn't obvious from the line.
- Shared state only via `TenantFixture` or `@TempDir` — never static mutable fields.
- A new repository write method gets at least one full-column round-trip test.

## Recipes

### New REST endpoint

1. Add the method to the controller **in its feature slice** — never in `web/`. Return `ApiResponse<T>`; keep the handler thin, delegate to a service.
2. Request-body record gets Jakarta constraints; the parameter takes `@Valid @RequestBody`.
3. Throw `EvalsException(SomeError, args)` on failure — never error `ResponseEntity` shapes.
4. Snake_case field names via `@JsonProperty`.
5. Regenerate the contract: `task contract:openapi`, then in `frontend/`: `pnpm run generate:api`; add the client method in `frontend/src/api/client.ts`.

### New error domain

1. Create `backend/shared/src/main/java/ai/tessary/evals/open/errors/<Name>Error.java` — enum implementing `ErrorCode`; each constant `(HttpStatus, "template with %s")`.
2. Add `<Name>Error.class` to `ErrorCatalog.REGISTERED`.
3. Throw at the call site — `GlobalExceptionHandler` maps it to the envelope automatically.

## Before you commit

Run `task check -- <areas>` from the repo root for the packages you touched — `task check -- rca,metering` runs both their unit and integration tests. Nothing enforces this automatically, and CI only runs the full gate on a weekly cron (plus manual dispatch) — **your local run is the per-change gate**. Bare `task check` runs the same scripts CI does, and `backend:check` alone is `mvn -B verify` (tests + the full static-analysis gate). Docker must be reachable (Testcontainers). See [`../devdocs/reference/test-suite.md`](../devdocs/reference/test-suite.md).
