# Backend test suite: shape and cost model

What the suite is made of, what each way of running it costs, and why `task check` is scoped by
package rather than by test annotations. Conventions for *writing* tests live in
[`../../backend/AGENTS.md`](../../backend/AGENTS.md) § *Testing*; this page is the factual
inventory behind them.

## Shape

Tiers are distinguished only by `@SpringBootTest` (integration) vs no Spring (unit). The suite
is **unit-heavy (~⅔ of methods)**; integration is the minority but dominates wall-clock via
Spring contexts. Absolute counts drift every PR — recount when you need them:

```bash
# *Test.java classes across every module in the reactor
find backend/*/src/test -name '*Test.java' | wc -l
# of those with @SpringBootTest (they all live in `app` — only it has the @SpringBootConfiguration)
rg -l '@SpringBootTest' backend/app/src/test --glob '*Test.java' | wc -l
```

Also gated (not in those counts): ArchUnit under `app/src/test/.../arch/`,
`packages/mcp` `node --test` (the `mcp-bridge` row),
`contract/tests` (the `vendored-plugin-rules` row), and live ITs (`*LiveIT.java`).
`JevDecisionClientLiveIT` (the frustration classifier's decision call) is one of those live ITs: it
skips unless `TYPESAFE_API_KEY` or `OPENROUTER_API_KEY` is set, runs each gateway only with its own
key, and is run from `backend/` with `mvn test -pl llm-runtime -Dtest=JevDecisionClientLiveIT`.

## Running it

`task check` is the only entry point. With no argument it is the full gate. With an argument it
narrows to a comma-separated list of **slices**, where a slice is a backend product area (a
package under `ai.tessary`) or the literal `frontend`.

| Command | Runs | Docker |
|---|---|---|
| `task check` | The full gate (17 checks): backend `mvn verify`, frontend, groundedness-serve, groundedness-setup, sandbox-runner, mcp-bridge, vendored-plugin-rules, classifier-quality-doc, blob-links, module-hygiene, license-headers, pipeline-vocabulary, contract-consistency, version-consistency, price-book-contract, Caddyfile validate, compose-artifact. The manifest in `scripts/check.sh` is the authoritative list: 17 RUN rows of the 30 scripts it declares. **No gate reads a `.md` or `.mdx` file**: a standing rule documented in that script's header. `docs-links`, `connect-route`, `selfhost-health`, `required-inputs` and `readme-front-door` were deleted under it, so they have no row there | yes |
| `task check -- rca` | spotless, compile, every test in `ai.tessary.rca.**` | yes |
| `task check -- rca,metering` | both areas | yes |
| `task check -- frontend` | OpenAPI + route-manifest drift guards, `tsc --noEmit`, vitest, vite build, plus repo-wide license-headers/price-book-contract/compose-artifact | no |
| `task check -- rca,frontend` | one backend area plus the frontend gate | yes |
| `task check -- typo` | fails immediately and prints the valid slice names | no |
| `d=$(bash scripts/lib/export-simulate.sh) && (cd "$d/frontend" && pnpm install) && (cd "$d" && bash scripts/check.sh)` | The same pipeline on the EXPORT CANDIDATE | yes |

An unknown slice fails before anything runs, so a typo can never silently select nothing.

**One gate needs host Python tooling.** `vendored-plugin-rules` runs `contract/tests` against the
vendored evals-plugin validator, so it needs `python3` with `pyyaml` and `pytest`; it says so and
stops if either is missing. The plugin freshness half (a diff against `tessaryai/plugins@main`) is not in `task check`:
it runs via `task contract:plugin` and `drift-checks.yml`. See
[`contract/tests/README.md`](../../contract/tests/README.md).

**A narrowed backend slice is not full `mvn verify`.** It runs spotless + test-compile + the
package's tests. Static analysis bound to the `verify` phase (SpotBugs, PMD, forbidden-apis),
module-hygiene
(`scripts/check-module-hygiene.sh`), the classifier-pipeline vocabulary gate
(`scripts/check-pipeline-vocabulary.sh`), the classifier-quality doc gate
(`scripts/check-classifier-quality-doc.sh`, which pins
the classifier-quality reference page to the served model revisions and catalog
thresholds, and fails when that page is missing), and root-package tests such as `ContextLoadsTest` run
only on bare `task check` / `backend:check`. Error Prone and NullAway are compiler-plugin checks
bound to the `compile` phase instead, so they run on every narrowed slice too — any
`mvn test-compile`/`test` triggers `compile` first. Prefer the slices you touched for the inner
loop; use the full gate before merging.

**CI runs the same gate on every pull request.** There is still no pre-commit hook, but
`.github/workflows/check.yml` calls `scripts/check.sh` — the same manifest `task check` runs — on
`pull_request:`, so local green ⇒ CI green by construction. `secret-scan.yml` (gitleaks) also
runs on every PR, and `codeql.yml` runs weekly. Nothing is merge-blocking: branch protection and rulesets are plan-gated on this repo,
so a red check has to be respected rather than enforced.

One gate is deliberately not on that per-PR path and lives in the dispatch-only
`.github/workflows/drift-checks.yml`:

- `vendored-plugin` — its freshness half fetches `tessaryai/plugins` over the network and hard-fails
  on `$CI`, so per PR it reds pull requests over upstream drift unrelated to the diff. Its offline
  rules half runs per PR as the `vendored-plugin-rules` row.

Run `gh workflow run drift-checks.yml` before a risky merge, and periodically to catch drift.

For a single test class, go straight to Maven — there is no task for it. `-pl` is the module
that owns the test (`app` for anything `@SpringBootTest`):

```bash
cd backend && mvn -B -q -pl llm-runtime -DfailIfNoTests=false -Dtest='BedrockModelProfileTest' test
```

## Contract / OpenAPI gates

These are part of the same CI story but are easy to miss in the shape table:

| Guard | Where | Trigger |
|---|---|---|
| Vendored evals-synth contract consistency | `scripts/check-contract-consistency.sh` | CI `contract` / bare `task check` |
| Checked-in OpenAPI drift | `apidoc/OpenApiSpecDriftTest` | full backend verify (`backend:check`) |
| Frontend types vs OpenAPI | `scripts/check-frontend.sh` regenerates `schema.d.ts` and diffs | `task check -- frontend` |
| SQL naming a relation the classifier-pipeline cutover dropped | `scripts/check-pipeline-vocabulary.sh` | CI `pipeline-vocabulary` / bare `task check` |

The last one is the only guard that can see inside a string literal. A query naming
`behavior_finding`, `conformance_finding`, `signal_event_v`, `signal_trend_rollup`,
`grader_run_trigger`, `source_ref`/`sourceRef` or `trace_v2` compiles, boots, and fails the first
time it runs — see the script's own header for what is exempt and why.

Regenerate the wire contract with `task contract:openapi` after controller/DTO changes; then
`pnpm run generate:api` in `frontend/`.

## Cost model

Fixed costs dominate; individual tests are nearly free.

```
  ~5s   spotless:apply
  ~7s   test-compile when nothing changed
  ~5s   JVM + Maven startup, once per `mvn` invocation
 ~40s   frontend gate (tsc --noEmit ~20s, then tsc -b + vite build ~20s)
 ~15s   EACH distinct Spring context: a fresh database + a full Liquibase run
  ~0s   each additional test inside a context you are already paying for
```

The Spring cost is per **context**, not per class. `TestcontainersPostgresInitializer` is
registered globally in `META-INF/spring.factories` and calls
`TestPostgres.createIsolatedDatabase()` on every context initialization. Spring's test context
cache key includes `@SpringBootTest(properties = …)` **and** `@DynamicPropertySource`
fingerprints — most integration classes share one fingerprint (and one DB), while a distinct
`properties = …` or a unique `DynamicPropertySource` set pays the ~15s again. Prefer reusing the
shared fingerprint unless the properties are the point of the test.

It also means **adding a slice is linear in distinct contexts, not in test count**: a second
product area that reuses the shared fingerprint is nearly free; one that brings its own
`@SpringBootTest(properties=…)` or unique `DynamicPropertySource` pays another ~15s.

**Treat all of these as ratios, not guarantees.** Absolute times on a laptop drift badly under
sustained load: the same `task check` measured 242s early in a session and 539s after an hour of
continuous Maven runs, then returned to 248s once the machine cooled. Any A/B below was run back
to back to cancel that drift.

## Why slices are packages, not tags

An earlier attempt tagged every `@SpringBootTest` class so a Spring-free "unit lane" could be
selected. It was reverted. Two measurements killed it:

**Selecting fewer integration tests saves nothing.** One trivial `@SpringBootTest` class costs
about as much as a whole product area, because the context is the cost.

**A unit-only lane answers the wrong question.** A product area contains both kinds, so running
only the unit half of the area you just edited gives false confidence exactly where you most want
certainty. Selecting by package runs both kinds for the code you touched, which is what you
actually want, and needs no annotations to maintain.

If tests ever shard across CI machines, revisit this — sharding is a real reason for tags that
inner-loop speed is not.

## Coverage posture (intentional coldspots)

Dense today: `ingest`, `classifier`, `judge`, `mcp`, `tenant`. Frontend has a vitest runner
(`pnpm run test`, wired into `scripts/check-frontend.sh` between lint and build): component and
unit tests plus a route-render smoke test that mounts every view in the route manifest
(and the case, finding and RCA pages once more on real payloads, since the manifest pass only
reaches their not-found branch) and fails on a render error or un-allowlisted console.error. Every
frontend line is covered (`pnpm run coverage` reports it); the gate runs OpenAPI/route-manifest drift +
`tsc` + vitest + vite build. Auth filter/device-link paths
are covered lightly (crypto + path resolver + MCP bearer integration) rather than per-filter
classes; treat deeper auth coverage as product work, not a docs-audit obligation. Packages with
near-zero tests are thin wrappers or UI-facing glue; a test there has to name the bug it catches
(root `AGENTS.md` § Tests).

### JaCoCo baseline

`jacoco-maven-plugin` is wired into exactly the **10 open backend modules** — `shared`,
`contract`, `core`, `tenancy`, `substrate`, `product`, `llm-runtime`, `analysis`,
`surfaces`, `app` — as a `mvn verify` side effect. (It was 11 until `evaluation` was deleted.) It is a **baseline, not a gate**: no threshold
is enforced anywhere, and nothing fails the build on a coverage number. The point is to have a
number before further module extractions continue, so a module being pulled out of the reactor can be
checked against what it actually exercised rather than what its tests merely claim to.

Per-module HTML/XML reports land at `backend/<module>/target/site/jacoco/`. Each one credits only
that module's own tests, so code the `app` module's `@SpringBootTest` suite runs shows as uncovered
in the module that owns it. The aggregate report at `backend/app/target/site/jacoco-aggregate/`
merges every module's exec data against every open module's classes; read that one for a module's
real number. Refresh them locally
with `task backend:coverage` (equivalent to `task backend:check` — same reactor — kept as its own target so refreshing coverage mid-extraction doesn't need to wait on
CI's cadence). CI additionally uploads the reports as a build artifact
(`backend-jacoco-coverage`), from `check.yml`'s single job, so it lands on every pull request rather
than on the old weekly cron. `if: always()`, so a red run still leaves a baseline.

`backend/test-support` carries no `<build><plugins>` block and no `*Test.java` files, so it isn't
instrumented — there's nothing to measure.

## The schema-column generator

`backend/core/tools/schemagen/SchemaColumnGenerator.java` runs at `generate-sources` on every Maven
invocation and emits 61 interfaces of column constants for the open reactor. It
writes a file **only when the content
changed**. Writing unconditionally reset every generated file's mtime, which invalidated the
compiler's staleness check and forced a full recompile every time. Measured A/B on a
no-change `test-compile` (taken while the backend was still one module; the ratio is the point):

| | run 1 | run 2 | run 3 |
|---|---|---|---|
| Unconditional write | 79.5s | 77.0s | 56.3s |
| Write-if-changed | 8.6s | 7.0s | 6.0s |

Every build paid that, including each `task check`. If you change the generator, preserve the
write-if-changed property.

## Known flake

`MeteringIntegrationTest.dayRollupEqualsTheSumOfItsHours_viaRawReaggregation` fails
intermittently with `NoSuchElementException` when metering runs alongside other areas. Its helper
does `jobs.claimBatch(50, 600) … .filter(…).findFirst().orElseThrow()`, and classes sharing a
Spring context share one database, so when enough other rollup jobs are queued the test's own job
falls outside the 50 it claims. Order-dependent, pre-existing, and unrelated to which slice you
select.
