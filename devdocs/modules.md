# Backend modules

The backend is an eleven-module Maven reactor. Dependencies flow **downward only**, and Maven — not a
convention or an ArchUnit rule — is what enforces it: a module can only import what its own pom
depends on, and each depends on exactly one module below it.

## The layering

Artifact ids are the bare module name: the groupId is already `ai.tessary`, so an `evals-` prefix
said it twice.

```
app          EvalsApplication, application.yaml, integration tests, ArchitectureRulesTest
surfaces     query, search, mcp, ci, metering, billing, telemetry  (slack's wire surface left for tessary-paid/ in #920)
analysis     classifier, rca, cases, alert, onboarding, prompt
llm-runtime  llm, priors, sandbox      (the ONLY module declaring a provider SDK or AWS client)
product      pipeline, gate, sop (the SopIntake seam + the compile failure type), sopcompile, plan
substrate    storage, ingest, redaction, retention, pricing, sources, traces, git, usage, vitals
tenancy      tenant, auth, edition, featureflags, version            (declares bcrypt)
core         web, model, apidoc, ops, crypto, db, config, telemetry, llmspi
             + the Liquibase changelog and the schema-column generator that reads it
test-support TestPostgres, TestcontainersPostgresInitializer, OpenApiCanonicalizer  (test scope everywhere)
shared       open/{errors,obs,jobqueue,media}, detection  ·  contract  open/contract
```

### Why packages sit where they do

**`telemetry` splits across `core` and `surfaces` (#858).** It replaced `analytics` — the Mixpanel
per-event emitter several packages imported from below — with the
`home.tessary.ai` heartbeat ping (devdocs/reference/telemetry-contract.md). Nobody imports `telemetry`
from below any more: the ping is self-scheduled, not called per-request, so the whole "which packages
import the emitter" question the old paragraph answered no longer has an object. The split itself is
forced, not stylistic: `core` has no dependency on `tenancy` or `substrate` (by design — see its own
pom), so the dumb transport, config, and install-id persistence (`TelemetryProperties`,
`HomeTessaryClient`, `InstallIdRepository`) sit in `core` where `analytics` used to, but the orchestrator
that actually gathers org/project/trace-volume counts (`TelemetryHeartbeat`, `TelemetryBuckets`) has
to sit above both — `surfaces`, next to `MeteringWorker`, the existing precedent for a plain
`@Scheduled` heartbeat with no dedicated executor.

**`metering` and `billing` are in `surfaces`.** `metering` imports `llm` and `plan` because it measures
platform LLM spend, and `billing` is now a thin read over it — after #883 deleted self-serve billing, the surviving
`BillingController` imports only `metering` and `tenancy`. A meter is a *consumer* of the thing it measures, never
a platform primitive underneath it.

**`llm` splits into `llm-spi` (L0) and `llm-runtime` (L4).** `storage` needs
the `llmspi` vocabulary (`ModelLane`, `ServiceTier`) — two pure enums, for usage/pricing
attribution (`LlmUsageAccountant`, `PlatformCallPricer`) — but no provider clients. Splitting is
what lets that vocabulary live in the substrate without dragging the LLM runtime under it. (This
used to also carry `EmbeddingModelFactory`, the embedding lane's own provider factory; that class,
the durable embedding lane it served, and the whole `classifier/model/` package that lane trained
against (`CentroidModel`, `ModelService`, `ModelStore`, and the rest) were removed outright — #1116 —
but the split still earns its keep on the usage/pricing reason alone.)

**`trend`, `observer`, `risk`, `prediction` and `routing` are gone, not moved.** Track A removed
grading, the git observer and the change-history risk model outright, and those five packages went
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
| ingest must refuse a push past the plan's span cap | `ingest/IngestQuotaGate` | `substrate` | open: `product/plan/UncappedIngestQuotaGate` (no cap). Hosted: `tessary-paid/plan/CapabilityIngestQuotaGate` |
| authoring a custom redaction rule is capability-gated | `redaction/CustomRuleGate` | `substrate` | `product/plan/CapabilityCustomRuleGate` |
| the SOP-compile queue must fit a conformance bundle | `sopcompile/SopCompiler` | `product` | `tessary-paid/conformance/.../compile/ConformanceCompileService` |
| a bundle import must store the SOPs in it | `sop/SopIntake` | `product` | `tessary-paid/sop/SopIntakeService` |
| an unauthenticated path can announce itself | `auth/SelfAuthenticatingPath` | `tenancy` | `tessary-paid/slack/SlackAuthBypass` |

Six narrow interfaces, not one general-purpose listener — the callers want different things, and a
single wide interface would have forced each implementor to stub the half it does not care about. There
was a seventh, `ingest/FeedbackObserver`, announcing user feedback upward to the pre-deploy gate; it
went with the substrate's feedback table, and a since-squashed migration dropped the gate's
now-single-valued `source` column with it.

The second-to-last is not a layering seam at all — both ends sit in the same layer. It is an OPEN/PAID
seam, added by #842 when the implementation went to the overlay while the caller could not follow:
`ImportController` and `SopIntakeDispatch` serve open paths. `SopIntakeDispatch` holds `SopIntake`
through an `ObjectProvider`, because a required constructor parameter with no candidate bean is an
unsatisfied dependency in Spring, not a null — so a direct injection would make the open edition fail
to BOOT rather than degrade. The
last row, by contrast, IS an ordinary layering seam of the first four's shape: `AuthFilter` (`tenancy`)
is genuinely a lower layer than the paid module that answers it, unlike the sop/slack-controller pairs
that motivated the "not a layering seam" carve-out in the first place. It replaced a hard-coded
`"/internal/slack/mention".equals(path)` literal in `AuthFilter` (#920) — the same
`ObjectProvider.orderedStream()` empty-safe convention, for the same boot-vs-degrade reason.

Two of the first four exist for the same reason: `plan/CapabilityService` sits in `product`, so anything in
`substrate` that must be capability- or quota-gated has no downward call to make. That is why both
`custom_redaction_enabled` and `ingested_spans_monthly` shipped declared-but-unenforced — the policy was
resolvable and the enforcement point was a module below it.

The SOP-compile edge had the same shape and a different origin: `SopCompileWorker` drains the
`sop_compile` queue and belonged beside the `sop_document` store it reads (`product`), while
everything a compile touches — the substrate read that renders the fit corpus, the compile-service
client, the artifact store and its bundle loader — was `analysis`. Rather than move the worker up (and
put queue mechanics in the analysis layer) or duplicate the substrate read down, the queue owns a
narrow verb and the conformance stack implements it.

Since #842 that port has no open end at all: the worker is `tessary-paid/sop` and the implementation is
`tessary-paid/conformance`, two overlay modules with no dependency between them. `SopCompiler` stays in
open `product` precisely because of that — it is the neutral ground both paid modules can name without
naming each other, and an open module could name neither if it moved. `SopCompileException` stays with
it for the same reason, plus one more: this port declares it in its `@throws`.

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
| `finding/` | 36 | the shared findings + triage feature every classifier serves through: the `finding`/`finding_evidence` store, the read surface and its DTOs, the Layer-2 queue, engine, worker, sandbox client and auto-escalator, and the two ports a classifier attaches through (`TriageSource`, `CauseResolver`) — see its `package-info.java` for the seam contract |
| `metric/` | 16 | duration + cost drift |
| `toolerror/` | 13 | tool-error rate drift |
| `substrate/` | 13 | trajectories, thread assembly, observation text, call-site reads, the trace/action reader all three drift classifiers share |
| `detector/` | 10 | encoder + deterministic detectors |
| `encoder/` | 2 | the embedding seam and its HTTP implementation, neutral to any one classifier |
| `catalog/` | 8 | `BuiltInClassifierCatalog`, `ClassifierModelModule`, `BuiltInDetector`, `DetectorSupplier`, `ClassifierMethodCard`, `OpenDetectionTables`, and provisioning's two triggers — `ClassifierSeedListener` (once, on project creation) + `ClassifierCatalogWorker` (the periodic reconcile over every active project). Provisioning lives here rather than in `worker/` because it answers to the capability flag layer, not to the sweep: which classifiers a project HAS is a licensing question, and which of them sweep is a traffic question |
| `worker/` | 9 | `ClassifierWorker`, job claim, and the fitting tier's extension seam — the `ClassifierSweep` port with its `SweepContext`/`SweepOutcome` records, and `ClassifierSweepRegistry`, which indexes every sweep on the classpath by the detector kinds it claims. See its `package-info.java` and `devdocs/reference/classifier-extension-interface.md` |
| `debug/` | 4 | the debug rail's read surface and the per-family contributor port it reads fitted state through |

9 files stay at the root — `Classifier{Controller,Service,Repository,Row,Field,Dtos,DetectionRepository,DetectionWriteRepository}`
plus `EncoderDependencyReporter`, the classifier concept itself. The test tree mirrors the same shape.

**Two classifiers left this table entirely.** #841 moved 48 of `conformance/`'s 51 files to
`tessary-paid/conformance/`, package `ai.tessary.paid.classifier.conformance` — the compiled-SOP
schema and parser (`sop/` 9), the rows, repositories, artifact bundle and fit report (`store/` 12), the
scoring engine (`scoring/` 12), the ONNX serving implementation (`encoder/` 1), the compile-service
client and the `SopCompiler` implementation (`compile/` 3), and 11 at the root (sweep, baseline audit,
triage seam and its dossier, shadow mode, substrate read, turn/stream/tool-call shapes, the rulebook
service) — plus `cases/ConformanceCaseSource`. #1072 moved 5 more — conversation intent resolution, the
whole subpackage that used to sit beside `encoder/` above — to the same paid module, bringing the
total to 53. The last 3 — `ConformanceController`, `ConformanceDtos` and the `FitReportSource` port —
stayed open for one reason: **the checked-in OpenAPI spec is generated from the OPEN application
context**, so a controller behind the boundary is a path deleted from the document, exactly as the four
`/plan` paths vanished when `PaidPlanController` moved. That exception was itself reversed by #945: the
open spec sheds the `/conformance` route entirely, and the remaining 3 files (plus `package-info.java`)
moved to `tessary-paid/conformance/` too. `classifier/` has no `conformance/` subpackage left at all —
`ConformanceController`, `ConformanceDtos` and `FitReportSource` now live at
`tessary-paid/conformance/.../conformance/`.

**`behavior/` is not in that table any more.** #840 moved all 20 files of it to
`tessary-paid/behavior-drift/`, package `ai.tessary.paid.classifier.behavior` — the epoch store, the fit
and sweep, the detector, and the four adapters that attach it to `finding/` and `debug/`, plus
`cases/BehaviorDriftSource`. Nothing open names any of them; the classifier attaches through
`ClassifierSweep`, `CauseResolver`, `ClassifierDebugContributor` and `CaseSource`. Since #919,
`ProfileSource` is no longer one of these cross-boundary attachments: it and its one route,
`/behavior/profiles`, moved into `tessary-paid/behavior-drift` too, along with the
`BehaviorProfileView` wire record that route alone served — that record is no longer in the checked-in
OpenAPI spec. Its jar is discovered off the runtime classpath by `BehaviorDriftAutoConfiguration`. Only
the catalog entry and the rest of the `Behavior*` wire records (findings, resolution, baseline) stay
open — see `devdocs/reference/classifier-extension-interface.md` §8.

**One package per feature, since #839.** `behavior/` used to hold the shared findings and triage
feature as well as behaviour drift, because drift shipped first and the generic parts inherited its
name — 19 of its 34 files served every classifier. Eighteen now live in `finding/` and the
nineteenth, the trace/action reader all three drift classifiers share, in `substrate/`. Each package is
now one feature: `metric/` and `toolerror/` are the classifiers that remain here, behaviour drift and SOP
conformance are classifiers in the overlay, `finding/` is the store and pipeline they all serve through,
and the direction between them is one-way. The `behavior/` ↔ `conformance/` import cycle that made
either one unextractable is gone — which is what let #840 and #841 move both of them out as plain
`git mv`s.

**The `Behavior`/`Metric` prefixes stay, and since #839 they mark wire lineage rather than package
membership.** `BehaviorDtos` and `BehaviorTriage*` sit in `finding/` and are named for the classifier
that shipped them first, not the package they are in — `FindingController` (renamed from
`BehaviorController` in #921, once #919 left it as the only thing under `finding/` still named
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
form **`test-support`**, which sits beside the kernel and is consumed with `test` scope by every
module. Its sources live in `src/main` rather than a test-jar, so the dependency is readable and
resolves in an IDE without extra configuration.

Fixtures that build a tenant, a trace or a classifier are NOT there. They would drag their layer's
types down with them and put the module above the features it exists to support. They stay in the
test tree of the module that owns those types. A paid `@SpringBootTest` reaches them through `app`'s
test-jar, which `tessary-paid/assembly` consumes at test scope (#882).

**Integration tests live in `app`, or in the paid overlay's `tessary-paid/assembly`.** Anything
annotated `@SpringBootTest` needs the `@SpringBootConfiguration` that only `app` has, so an open one
lives in `app`'s test tree. A paid one cannot, because `app` may never depend on the overlay; it lives
in `tessary-paid/assembly`, a test-only module that depends on the plain `app` jar plus every paid
module and so carries the paid image's classpath (`PaidOpenApiSpecTest` is the first). A module's own
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
- **No module above `llm-runtime` declares a PROVIDER SDK.** `langchain4j-bedrock` / `-open-ai` /
  `-anthropic` and the AWS clients are declared there and nowhere else. `langchain4j-core` is
  declared higher up and that is not a contradiction: its `ChatMessage` / `ChatRequest` types are
  the vendor-neutral shape a prompt is built in, and no vendor class is reachable through them. `LlmCaller`
  lives there because it handles `AnthropicTokenUsage` / `BedrockTokenUsage` / `OpenAiTokenUsage`;
  `LlmPacer` does too, because it paces every provider call rather than one lane's. A provider swap therefore cannot reach a caller above.

### What each pom declares

A library module (`core` … `surfaces`) declares the **APIs it compiles against** — `spring-context`,
`spring-jdbc`, `jackson-databind`, `slf4j-api`. It does not declare Spring Boot starters: a starter is
a runtime bundle, and the runtime belongs to `app`, which assembles the application. So `app`'s pom
looks dependency-heavy and every module below it looks thin, which is the intended shape.

Two exceptions, both deliberate:

- **An aggregator stays when it is the right unit.** `langchain4j-bedrock` pulls a dozen AWS SDK
  artifacts; `llm-runtime` depends on the aggregator, not on `sdk-core` / `regions` / `identity-spi`
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

The reactor is not the only place that enumerates modules. Four others do, and none of them fails
loudly when a module is missing — they build a jar with no classes in it, or resolve nothing:

| file | what it enumerates | symptom if stale |
|---|---|---|
| `backend/pom.xml` | `<modules>` | the module is simply not built |
| `backend/Dockerfile` | a `COPY <mod>/pom.xml` and `COPY <mod>/src` per module | prod image build fails at `package` |
| `backend/Dockerfile.dev` | a `COPY <mod>/pom.xml` per module | dev image build fails — Maven will not read an aggregator whose declared module directory is missing |
| `docker-compose.dev.yml` | a bind mount per module `src` and `pom.xml`, **and** the `command:` that overrides the image CMD | dev container installs part of the chain; `spring-boot:run` dies on the first unresolved sibling |
| `tessary-paid/docker-compose.dev.yml` | the same pair of mounts, for PAID modules only | same symptom, in the paid edition; the open file must name no `./tessary-paid/...` source at all (#886) |
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

Every ArchUnit rule scans `ai.tessary.evals`, which now arrives in `app` as module JARs rather than
local sources. A classpath that stopped carrying them would make all sixteen rules vacuously true and
the file a green no-op. `the_scan_reaches_every_module` asserts the scan's own reach (~1261 classes
today, floor 1100 — moved down from 1500 by Track A, which deleted a whole Maven module and 213 main
classes), so that failure mode is loud instead of silent.

## The paid overlay (`tessary-paid/`)

The open-core split adds a **thirteenth** reactor member that is not under `backend/`:
`tessary-paid/`, the closed-source overlay. Open-core decision D2 keeps development in one repo, so
the difference between the two editions is exactly this one directory, and the public export is a
folder filter. Delete it and the rest of the tree must still build. The boundary at component grain
lives in the open-core plan at the repo root (this repo only; the public export omits it).

`tessary-paid/pom.xml` is both the aggregator for the paid modules and their parent, inheriting
`backend/pom.xml` — so paid code sits under the same dependencyManagement and the same quality gate
as everything above. `backend/pom.xml` carries a `paid` profile activated by that pom simply
existing on disk, which adds `../tessary-paid` to the reactor. One reactor, no `mvn install`
handoff, and after the cutover the public repo has no such directory, so the same pom builds the
open edition unedited.

```bash
task check                # the whole gate, both editions: everything CI runs, cheapest first
task check:open           # the same pipeline, OPEN edition — what epic 1's gate names
task backend:check        # the backend leg alone, open + paid, one reactor
task backend:check:open   # the backend leg alone, open edition: check-backend.sh -P '!paid'
task check:open-boundary  # the direction check, under a second
```

The three `:open` targets answer three different questions and are easy to confuse.
`check:open-boundary` reads source TEXT and build inputs for references that point into the
overlay; it takes under a second and never compiles anything. `backend:check:open` builds and tests
the Maven reactor with the `paid` profile off. `check:open` is the whole pipeline — every gate in
`scripts/check.sh` with the paid ones declared out, the backend leg above, and the classifier
port-parity gate narrowed to the pins that are open on both sides. It is the one epic 1's gate
names, and `scripts/check.sh`'s manifest is where each gate's per-edition disposition is declared.

All three run with `tessary-paid/` still on disk, which makes them a proxy rather than a proof: the
Python import seam extends a package `__path__` when the directory exists, and the Maven profile
activates when the overlay pom exists, so neither can be simulated by a flag. Physically detaching
the directory and re-running `bash scripts/check.sh --edition open` is the proof.

**Direction is one-way and mechanical.** Every open module inherits an
`enforce-open-to-paid-direction` banned-dependency rule from the aggregator: declaring a dependency
on `ai.tessary.paid:*` fails `mvn validate` before a line compiles. Since a Java class cannot be
imported without the dependency carrying it, that closes the Java half outright — the same trick as
the two invariants above, applied to the open/paid axis rather than the layering one.
`scripts/check-open-boundary.sh` covers only what a reactor cannot see: paid package names left in
open source text, the frontend (which has no Maven), the open image's build inputs, and the profile
wiring itself going missing.

When open code needs paid behaviour, the move is the same one the layering already uses: an SPI
interface in the open module with a working open default, implemented by a paid module. That is
what makes `analysis` able to ship its default classifiers with the paid ones absent.

Two consequences worth knowing before they surprise you:

- **Paid modules are absent from `backend/Dockerfile` on purpose.** Per D4 the published open image
  carries only open code and the paid image layers on top of it (epic 5).
  `scripts/check-module-hygiene.sh` skips out-of-tree module paths for exactly this reason.
- **The dev container must see the same reactor the host does**, so the overlay is mounted at
  `/tessary-paid`. Those mounts live in `tessary-paid/docker-compose.dev.yml`, not in the open
  `docker-compose.dev.yml`, and are merged only when the overlay is on disk. Without them the `paid`
  profile finds nothing inside the container and deactivates, and the identical `mvn` command then
  builds a different module set there than it does on your machine — so module hygiene fails if a
  mount goes missing. They moved out of the open file in #886 for a reason worth knowing: a compose
  short-syntax bind CREATES a missing source as a directory, so in a checkout with no overlay
  dockerd manufactured a ghost `tessary-paid/` tree, and `backend/pom.xml`'s `<file><exists>` probe
  — true for a directory — switched the `paid` profile back on over modules that did not exist. It does
  **not** follow that the dev backend runs paid code: the boot is `-pl app -am`, and app's
  dependency closure can never contain a paid module, because the enforcer forbids app from
  declaring one, and it never will. Paid code reaches a running backend a different way, landed in
  #881: a Spring Boot `AutoConfiguration.imports` inside the paid jar, read off the **runtime**
  classpath, which Boot honours from any jar regardless of package. The dev and production recipes
  for assembling that classpath are in the overlay's own README — note
  that both compose TWO classpaths, because app's closure cannot carry the paid module's own
  dependencies either. This is not the classifier extension registry (#847/#876); that is a separate
  seam for classifiers, and it does not load beans.

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
  in `tessary-paid/db`, applied strictly after the open one by its own `SpringLiquibase` bean
  (Epic 3, #1074); its ordering against the open lane is a written contract, not implicit.
