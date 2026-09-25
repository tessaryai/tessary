# Backend modules

The backend is an eleven-module Maven reactor. Dependencies flow **downward only**, and Maven — not a
convention or an ArchUnit rule — is what enforces it: a module can only import what its own pom
depends on, and each depends on exactly one module below it.

## The layering

Artifact ids are the bare module name: the groupId is already `ai.tessary`, so a `tessary-` prefix
said it twice.

```
app          TessaryApplication, application.yaml, integration tests, ArchitectureRulesTest
surfaces     query, search, mcp, ci, metering, billing, telemetry
analysis     classifier, rca, cases, alert, onboarding, prompt
llm-runtime  llm, sandbox              (the ONLY module declaring a provider SDK or AWS client)
product      pipeline, gate, plan
substrate    storage, ingest, redaction, retention, pricing, sources, traces, git, usage, vitals
tenancy      tenant, auth, edition, featureflags, version            (declares bcrypt)
core         web, model, apidoc, ops, crypto, db, config, telemetry, llmspi
             + the Liquibase changelog and the schema-column generator that reads it
test-support TestPostgres, TestcontainersPostgresInitializer, OpenApiCanonicalizer  (test scope in app)
shared       open/{errors,obs,jobqueue,media}, detection  ·  contract  open/contract
```

### Why packages sit where they do

**`telemetry` splits across `core` and `surfaces`.** It replaced `analytics` — the Mixpanel
per-event emitter several packages imported from below — with the
`home.tessary.ai` heartbeat ping (devdocs/reference/telemetry-contract.md). Nobody imports `telemetry`
from below any more: the ping is self-scheduled, not called per-request, so the whole "which packages
import the emitter" question the old paragraph answered no longer has an object. The split itself is
forced, not stylistic: `core` has no dependency on `tenancy` or `substrate` (by design — see its own
pom), so the dumb transport, config, and instance-id persistence (`TelemetryProperties`,
`HomeTessaryClient`, `InstanceIdRepository`) sit in `core` where `analytics` used to. The orchestrator
(`TelemetryHeartbeat`) sits in `surfaces`, next to `MeteringWorker`, the existing precedent for a plain
`@Scheduled` heartbeat with no dedicated executor. It has to sit above `core`: the install-wide
counts it sends come from `tenancy` (projects), `analysis` (findings, cases) and `substrate` (the
`metric_rollup` span and detection totals), and the price book check it runs is `substrate`'s
`pricing/PriceBookFetcher`.

**`metering` and `billing` are in `surfaces`.** `metering` imports `llm` and `plan` because it measures
platform LLM spend, and `billing` is now a thin read over it — since self-serve billing was deleted, the surviving
`BillingController` imports only `metering` and `tenancy`. A meter is a *consumer* of the thing it measures, never
a platform primitive underneath it.

**`llm` splits into `llm-spi` (L0) and `llm-runtime` (L4).** `storage` needs
the `llmspi` vocabulary (`ModelLane`, `ServiceTier`) — two pure enums, for usage/pricing
attribution (`LlmUsageAccountant`, `PlatformCallPricer`) — but no provider clients. Splitting is
what lets that vocabulary live in the substrate without dragging the LLM runtime under it. (This
used to also carry `EmbeddingModelFactory`, the embedding lane's own provider factory; that class,
the durable embedding lane it served, and the whole `classifier/model/` package that lane trained
against (`CentroidModel`, `ModelService`, `ModelStore`, and the rest) were removed outright —
but the split still earns its keep on the usage/pricing reason alone.)

**`trend`, `observer`, `risk`, `prediction` and `routing` are gone, not moved.** Grading, the git
observer and the change-history risk model were removed outright, and those five packages went
with them. The one finding table every classifier files into is what replaced the trend rollup.

**`ci` is a stub.** `PreDeployCheck*` is a store of gate findings → `gate` package in `product`;
`PreDeployCheckController` is all that is left at `surfaces`, the CI merge-gate having gone with
grading.

**`OtlpGrpcServerConfig` moves `config` → `ingest`.** `config` holds properties; wiring for one
wire protocol belongs with that protocol.

### Where a lower layer must trigger a higher one

Some dependencies genuinely point up: ingest must notify whatever consumes a trace. Each is expressed
as an interface **owned by the lower layer** and implemented above, wired in `app`:

| edge | interface | owned by | implemented by |
|---|---|---|---|
| ingest needs to resolve a call site | `ingest/CallSiteRegistry` | `substrate` | `product/pipeline/PipelineService` |
| ingest must refuse a push past the plan's span cap | `ingest/IngestQuotaGate` | `substrate` | `product/plan/UncappedIngestQuotaGate` (no cap); a capability-gated implementation exists outside this tree |
| authoring a custom redaction rule is capability-gated | `redaction/CustomRuleGate` | `substrate` | `product/plan/CapabilityCustomRuleGate` |

Three narrow interfaces, not one general-purpose listener — the callers want different things, and a
single wide interface would have forced each implementor to stub the half it does not care about. There
was a fourth, `ingest/FeedbackObserver`, announcing user feedback upward to the pre-deploy gate; it
went with the substrate's feedback table, and a since-squashed migration dropped the gate's
now-single-valued `source` column with it.

The last two exist for the same reason: `plan/CapabilityService` sits in `product`, so anything in
`substrate` that must be capability- or quota-gated has no downward call to make. That is why both
`custom_redaction_enabled` and `ingested_spans_monthly` shipped declared-but-unenforced — the policy was
resolvable and the enforcement point was a module below it.

The other upward edges dissolved rather than needing a seam: `ContentExtractor` moved to `core/model`,
so that import now points down. And
`classifier → gate` needs no interface at all, because `gate` sits in `product` (L3) while
`classifier` is in `analysis` (L6) — that call was always downward once `gate` was placed correctly.

This is the pattern that makes the design survive extension: a new consumer of ingest implements the
interface; it does not add an import to `ingest`.

## `classifier` splits internally

~26,700 lines in one package is a module-sized problem inside a module. Not a Maven split — the parts
share a lifecycle — but a package split, so the module boundary stops it regressing:

| subpackage | files | holds |
|---|---|---|
| `finding/` | 34 | the shared findings + triage feature every classifier serves through: the `finding`/`finding_evidence` store, the read surface and its DTOs, the Layer-2 queue, engine, worker, sandbox client and auto-escalator, and the port a classifier attaches through (`TriageSource`) — see its `package-info.java` for the seam contract |
| `metric/` | 16 | duration + cost drift |
| `toolerror/` | 13 | tool-error rate drift |
| `substrate/` | 8 | call-site reads, the trace/action reader all three drift classifiers share |
| `detector/` | 10 | encoder + deterministic detectors |
| `catalog/` | 8 | `BuiltInClassifierCatalog`, `ClassifierModelModule`, `BuiltInDetector`, `DetectorSupplier`, `ClassifierMethodCard`, `OpenDetectionTables`, and provisioning's two triggers — `ClassifierSeedListener` (once, on project creation) + `ClassifierCatalogWorker` (the periodic reconcile over every active project). Provisioning lives here rather than in `worker/` because it answers to the capability flag layer, not to the sweep: which classifiers a project HAS is a licensing question, and which of them sweep is a traffic question |
| `worker/` | 9 | `ClassifierWorker`, job claim, and the fitting tier's extension seam — the `ClassifierSweep` port with its `SweepContext`/`SweepOutcome` records, and `ClassifierSweepRegistry`, which indexes every sweep on the classpath by the detector kinds it claims. See its `package-info.java` and `devdocs/reference/classifier-extension-interface.md` |
| `debug/` | 3 | the debug rail's read surface |

9 files stay at the root — `Classifier{Controller,Service,Repository,Row,Field,Dtos,DetectionRepository,DetectionWriteRepository}`
plus `EncoderDependencyReporter`, the classifier concept itself. The test tree mirrors the same shape.

**Two classifiers left this table entirely.** SOP conformance moved out of `classifier/` in full —
the compiled-SOP schema and parser, the rows, repositories, artifact bundle and fit report, the
scoring engine, the ONNX serving implementation and the compile client, plus `cases/ConformanceCaseSource` and the conversation-intent-resolution
subpackage that used to sit beside the since-removed `encoder/`. `ConformanceController`, `ConformanceDtos` and
the `FitReportSource` port stayed open for a while for one reason: **the checked-in OpenAPI spec is
generated from the OPEN application context**, so a controller behind the boundary is a path deleted
from the document, exactly as the four `/plan` paths vanished when `PaidPlanController` moved. That
exception was itself reversed once the open spec shed the `/conformance` route entirely, and those
last few files moved out too. `classifier/` has no `conformance/` subpackage left at all.

**`behavior/` is not in that table any more.** All of it moved out of this tree — the epoch store, the
fit and sweep, the detector, and the four adapters that attach it to `finding/` and `debug/`, plus
`cases/BehaviorDriftSource`. Nothing open names any of them; the classifier attaches through
`ClassifierSweep` and `CaseSource`. `ProfileSource` is
no longer one of these cross-boundary attachments either: it and its one route, `/behavior/profiles`,
moved out too, along with the `BehaviorProfileView` wire record that route alone served — that record
is no longer in the checked-in OpenAPI spec. Its jar is discovered off the runtime classpath by
`BehaviorDriftAutoConfiguration`. Its catalog entry has left the open tree as well; only the
`Behavior*` wire records for findings and their resolution stay open.

**One package per feature.** `behavior/` used to hold the shared findings and triage
feature as well as behaviour drift, because drift shipped first and the generic parts inherited its
name — 19 of its 34 files served every classifier. Eighteen now live in `finding/` and the
nineteenth, the trace/action reader all three drift classifiers share, in `substrate/`. Each package is
now one feature: `metric/` and `toolerror/` are the classifiers that remain here, behaviour drift and SOP
conformance are classifiers in the overlay, `finding/` is the store and pipeline they all serve through,
and the direction between them is one-way. The `behavior/` ↔ `conformance/` import cycle that made
either one unextractable is gone — which is what let both move out as plain `git mv`s.

**The `Behavior`/`Metric` prefixes stay, marking wire lineage rather than package
membership.** `BehaviorDtos` and `BehaviorTriage*` sit in `finding/` and are named for the classifier
that shipped them first, not the package they are in — `FindingController` (renamed from
`BehaviorController` once it was left as the only thing under `finding/` still named
`Behavior`) is the one exception, a deliberate wire rename, not the general rule. Stripping the
remaining prefixes looks like free simplification, and it is not available: the DTO records are in
the checked-in OpenAPI spec
by simple name, `ArchitectureRulesTest` matches `BehaviorTriage(Engine|Worker)` by name to hold the
Layer-2 invariant, and `PromptResourceParityTest` derives its golden filenames from
`BehaviorTriageEngine.getSimpleName()`. A rename is a deliberate wire decision with a spec
regeneration behind it, not a tidy-up. They also still collide across subpackages (`FindingRow`,
`DriftSweep`, `Dtos`, `Controller`…), and the catalog, worker and debug code that dispatches across
detectors references both sides in one file, where the prefix is what makes the comparison readable.

```
classifier/
  catalog/      BuiltInClassifierCatalog, ClassifierModelModule, seeding + catalog reconcile
  worker/       ClassifierWorker, job claim, the ClassifierSweep port + its registry
  detector/     encoder + deterministic detectors
  metric/       duration + cost drift
  finding/      the shared findings + triage feature every classifier serves through
```

## Test support

`TestPostgres` and `TestcontainersPostgresInitializer` depend on nothing in the platform, so they
form **`test-support`**, which sits beside the kernel and is consumed with `test` scope by `app`. Its sources live in `src/main` rather than a test-jar, so the dependency is readable and
resolves in an IDE without extra configuration.

Fixtures that build a tenant, a trace or a classifier are NOT there. They would drag their layer's
types down with them and put the module above the features it exists to support. They stay in the
test tree of the module that owns those types.

**Integration tests live in `app`.**
Anything annotated `@SpringBootTest` needs the `@SpringBootConfiguration` that only `app` has, so it
lives in `app`'s test tree. A module's own
tests are the ones that need no Spring context, which is also the vast majority of them.

## How the boundary is held

The quality gates — surefire, compiler/NullAway, forbiddenapis, SpotBugs, PMD, Spotless — are declared
once in the parent `pluginManagement`. A new module activates them with a bare `<plugin>` stub and
cannot silently opt out of them.

### Two invariants the poms enforce

Both were unstatable while the backend was one module, and are now compile errors rather than
conventions:

- **A record of consumption sits with the record, not above it.** `usage` and `vitals` are in
  `substrate` beside the other stores. They measure what was consumed; putting them higher would
  make `analysis` import sideways to write them.
- **No module above `llm-runtime` declares a PROVIDER SDK.** The AWS `bedrock` client is declared
  there and nowhere else, so a provider swap cannot reach a caller above.

### What each pom declares

A library module (`core` … `surfaces`) declares the **APIs it compiles against** — `spring-context`,
`spring-jdbc`, `jackson-databind`, `slf4j-api`. It does not declare Spring Boot starters: a starter is
a runtime bundle, and the runtime belongs to `app`, which assembles the application. So `app`'s pom
looks dependency-heavy and every module below it looks thin, which is the intended shape.

Two exceptions, both deliberate:

- **An aggregator stays when it is the right unit.** The AWS `bedrock` client pulls a dozen AWS SDK
  artifacts; `llm-runtime` depends on the client, not on `sdk-core` / `regions` / `identity-spi`
  individually. Listing transitive plumbing is noise, not precision.
- **A module depends on the one below it, not on everything it uses.** `analysis` reads `core` types
  through `llm-runtime`. The chain is fixed by design, so re-declaring each layer would add n² lines
  and say nothing new.

**`mvn dependency:analyze` cannot be trusted on its own here.** It reads bytecode, so anything needed
only at runtime or only through reflection is reported as an unused declaration. Deleting on its word
compiles cleanly and fails at runtime — `grpc-protobuf` in `substrate` is the worked example: it is
invisible to bytecode analysis, backs the generated `TraceServiceGrpc` stub, and removing it turns
`OtlpGrpcTraceServiceTest` into a `NoClassDefFoundError`. Its pom entry says so. Treat the report as a
list of questions, and let the test suite answer them.

Test-only libraries carry `<scope>test</scope>` — logback in `analysis` (six tests in
`ClassifierWorkerLoggingTest` capture log output) and the OTel SDK in `llm-runtime` (production emits
through the API; only tests need an in-memory reader). The scope is what stops production code
reaching for them.

### Version pins

Every third-party version lives in the parent — as a `<properties>` entry, a BOM import, or a
`<dependencyManagement>` pin. A module pom names an artifact, never a version. A module that
redeclares a version property shadows the parent for itself alone and the build stays green while
that one module silently runs the old version, so the rule is: **no `*.version` property outside
`backend/pom.xml`.**

Two dependencies are deliberately held back, each with the reason in the pom beside it:

| held | at | why |
|---|---|---|
| `swagger-annotations-jakarta` | 2.2.55 | 2.2.53 emits `"default": ""` on 26 enum and array-item schemas that have no default — wrong API documentation, and it reaches every generated client as a bogus `@default` tag |
| `json-schema-validator` | 1.5.6 | 3.x removes `DisallowSchemaLoader`, which is how the malformed-output classifier refuses to resolve a remote `$ref`. The replacement SPI needs a migration with a test proving remote fetches still fail |

### Adding a module

The reactor is not the only place that enumerates modules. Three others do, and none of them fails
loudly when a module is missing — they build a jar with no classes in it, or resolve nothing:

| file | what it enumerates | symptom if stale |
|---|---|---|
| `backend/pom.xml` | `<modules>` | the module is simply not built |
| `backend/Dockerfile` | a `COPY <mod>/pom.xml` and `COPY <mod>/src` per module | prod image build fails at `package` |
| `backend/Dockerfile.dev` | a `COPY <mod>/pom.xml` per module | dev image build fails — Maven will not read an aggregator whose declared module directory is missing |
| `docker-compose.dev.yml` | a bind mount per module `src` and `pom.xml`, **and** the `command:` that overrides the image CMD | dev container installs part of the chain; `spring-boot:run` dies on the first unresolved sibling |
| `scripts/check.sh` | nothing — it globs `backend/*/src/test/...` | (safe by construction) |

`scripts/check.sh` is the model to copy: glob the reactor rather than list it. The two Docker files
list explicitly because Docker's `COPY` has no usable glob for this, so both carry a comment saying
a new module must be added there too.

None of this is left to the comment. `scripts/check-module-hygiene.sh` runs in the full gate and
fails the build if a module is in the reactor but missing from any of the three Docker files, if any
module pom declares a `*.version` property, or if the dev install command is anything other than
`-pl app -am install`. Note the last one: `docker-compose.dev.yml` **overrides the image CMD**, so
fixing `Dockerfile.dev` alone does not fix the dev stack. Both rules exist because both were already broken once — the
version-property shadowing silently confined a round of dependency upgrades to all but one module.

### The scan guard

Every ArchUnit rule scans `ai.tessary`, which now arrives in `app` as module JARs rather than
local sources. A classpath that stopped carrying them would make all sixteen rules vacuously true and
the file a green no-op. `the_scan_reaches_every_module` asserts the scan's own reach (~1261 classes
today, floor 1100 — moved down from 1500 when a whole Maven module and 213 main classes were
deleted), so that failure mode is loud instead of silent.

## The paid edition

The paid edition is not part of this tree. It lives in a sibling repository that merges this one,
so nothing here (no Maven profile, enforcer rule, compose mount or check script) names it, and every
gate builds and tests the open edition directly.

When open code needs paid behaviour, the move is the same one the layering already uses: an SPI
interface in the open module with a working open default, implemented by a paid module. That is
what makes `analysis` able to ship its default classifiers with the paid ones absent. Paid code
reaches a running backend through a Spring Boot `AutoConfiguration.imports` inside the paid jar,
read off the **runtime** classpath, which Boot honours from any jar regardless of package. This is
not the classifier extension registry, which is a separate seam for classifiers and does not load
beans.

## Decisions taken

- **Features own their controllers.** `web` is kernel, so a feature module can depend on it, and a
  feature that owns its HTTP surface is self-contained. `app` keeps only wiring.
- **`model` stays inside `core`.** It is 22 files of pure records with no Spring; a separate module
  would be more honest but buys nothing a package boundary does not.
- **`alert` is `analysis`.** It is coupled to classifier output today; if it grows a delivery side (email,
  webhook), that side moves to L7 and the analysis side stays.
- **The open Liquibase changelog lives in `core`, as one changelog.** The open schema is a single
  ordered history that every open module's repositories bind to, so it belongs at the bottom with
  the `db` package rather than at the top with assembly. Per-module changelogs would make ordering
  implicit and are not worth that. The one deliberate second changelog is the paid overlay's,
  applied strictly after the open one by its own `SpringLiquibase` bean; its ordering against the
  open lane is a written contract, not implicit.
