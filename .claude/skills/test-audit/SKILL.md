---
name: test-audit
description: "Invoke whenever writing, changing, reviewing, or sweeping tests in the tessary repo. Authoring gate for new tests plus an audit workflow for low-value, implementation-coupled, or duplicative tests and the test-only production seams they demand. Covers all four stacks: backend (JUnit/Spring/Testcontainers), frontend (vitest), Node (node:test), and Python (pytest)."
---

# Test Audit (tessary)

Adapted from openclaw's `.agents/skills/test-audit` for this repo and session.
Three modes, one value bar:

- **Authoring mode** gates every new or changed test when it's written.
- **Audit mode** runs focused sweeps for tests that re-assert source, duplicate
  stronger proof, couple behavior to implementation, or keep test-only
  production seams alive.
- **Campaign mode** prunes one whole area's test surface: a backend product
  area such as `ai.tessary.ingest`, the frontend, or one Node package. Read
  [CAMPAIGN.md](CAMPAIGN.md) before starting one.

Optimize for confidence, not deletion count. Continue broad audits as separate,
coherent follow-up changes.

## Repo rules this skill sits under

These come first. Read them in full before any mode:

- root `AGENTS.md` § Tests;
- `backend/AGENTS.md` § Testing (tiers, "Writing the test", "What to test",
  "What NOT to test", conventions);
- `frontend/AGENTS.md` § Tests;
- `devdocs/reference/test-suite.md` (running it, cost model, coverage posture,
  known flake).

Two root rules change how the upstream skill works here:

1. **Deleting or loosening a test needs the user's explicit OK.** Root
   `AGENTS.md` says: "Never weaken a test to get green: no deleting, skipping,
   loosening, or re-baselining. If a test looks wrong... stop and ask." Audit
   and campaign modes are read-only until the user approves the evidence
   ledger. An approved audit deletion is not "weakening to get green", but the
   approval must come from the user, not from this skill.
2. **Don't add tests to code you didn't change unless asked.** Authoring mode
   applies to tests that belong to your own change.

## Authoring gate

Before adding any test, answer five questions. If any answer is missing, don't
add the test yet:

1. What observable behavior, invariant, or independent contract does it protect?
2. What credible regression makes it fail? **Name the bug it catches** (root
   rule); this goes in your summary.
3. Where does the expected value come from? It must come from the requirement,
   the issue, or a hand calculation, never from running the code.
4. Why doesn't existing coverage already catch that failure? Each contract has
   one primary test owner at the strongest boundary. Another layer needs its
   own distinct risk, such as a transport, filter-chain, or Liquibase failure
   the owner can't reach. Prefer a new row in a `@ParameterizedTest`,
   `it.each`, or `pytest.mark.parametrize` table over a near-duplicate test.
   Consolidate duplicated setup in the same change.
5. Does it need a production seam (widened visibility, an export, a flag, a
   wrapper, an injection hook) that no production caller needs? If yes, move
   the test to the real boundary. Backend rule: no reflection, no
   `setAccessible`, no visibility widened for a test. If the outcome is
   invisible to callers, raise the missing seam instead.

Then check the test against every [junk pattern](#junk-patterns). A match fails
the gate unless the [retention bar](#retention-bar) names the contract it
independently guards. A test that would break under a behavior-preserving
refactor asserts implementation, not behavior; rewrite it at the owning
boundary before landing it.

**Test first, watch it fail.** Run it red for the right reason, then implement.
A bug fix's regression test must go red again when the fix is reverted; a test
on a helper the fix didn't touch doesn't count. A regression test that never
demonstrably failed proves the mock, not the fix. One regression at the owner
boundary covers the bug; don't replay the same scenario at every layer it
crosses. Report each new test's bug and red output.

**Pick the cheapest tier that reaches the behavior.** Backend: Tier 1 (plain
`new`, no Spring) is the default. Tier 2 (`@SpringBootTest`) only when the
behavior needs the database, the filter chain, or bean wiring. Reuse the shared
Spring context fingerprint; each new `properties = …` or
`@DynamicPropertySource` fingerprint costs a fresh database and a full
Liquibase run (about 15s).

## Junk patterns

One shared checklist. The authoring gate rejects a new test that matches one;
audits hunt for existing tests that do.

**General (from upstream):**

- assertion-free coverage probes (a test that runs a line but can't fail);
- self-comparisons and identity copiers;
- copied fixtures, inventories, manifests, or export lists;
- exact source, import, or string greps (but see the retention bar for
  `scripts/check-*.sh` contract gates);
- private predicate or call-shape tests duplicated at real boundaries;
- duplicate invocations of the same contract;
- per-provider replays of a shared helper (for example the same assertion
  repeated for each LLM provider in `llm-runtime` when the shared code path is
  what's tested);
- tests whose only purpose is keeping test-only exports, globals, or wrappers
  alive;
- dead production code whose only callers are tests (delete it; the
  coverage PR precedent is "dead code was deleted rather than tested");
- expected values produced by the helper or renderer under test;
- mocks that implement the asserted behavior, or one identical mock standing in
  for different APIs;
- fixtures that supply the receipt, ordering, or callback the owner should
  produce, or persistence asserted against a store the path never writes;
- capability tests that restate declared flags instead of exercising what the
  flag promises;
- negative controls that pass for an unrelated reason, such as a denial from a
  different guard (for example a tenant-isolation test rejected by auth before
  the isolation check runs);
- names or fixtures that promise more than the input exercises.

**Backend (from `backend/AGENTS.md`):**

- record constructors/getters; constants, enum values, or config defaults
  restated as asserts; log wording; pure-glue controllers; framework wiring
  (`ContextLoadsTest` covers it once); negative paths the type system already
  guards;
- `verify(...)` where the call isn't the outcome, or matching arguments the
  rule isn't about (assert state, not calls);
- `lenient()` mocks, or a `@Mock` block copied from another test;
- Mockito where a real object or a hand-written fake of our own port works;
- `assertTrue(s.contains(..))` on log lines, exception text, or prompt prose
  instead of the `ErrorCode`, structured field, or parsed value;
- `Thread.sleep` or wall-clock reads instead of an injected `Clock`;
- hidden shared state (static mutable fields) instead of `TenantFixture` or
  `@TempDir`;
- a one-off Spring context fingerprint whose properties aren't the point of
  the test.

**Frontend (from `frontend/AGENTS.md`):**

- component internals, `container.querySelector`, or whole-tree snapshots
  instead of `getByRole` / `getByLabelText`;
- mocks of our own hooks or child components (mock only at the API seam:
  `useTenant`, `projectApi`);
- fixtures that can't fail the test (a "no secret in the DOM" test with no
  secret in the fixture; a page test fed a 404);
- absence asserted before the query settles;
- real waiting instead of `findBy…` or `vi.useFakeTimers()`.

## Value bar

Tests justify their maintenance cost by protecting behavior, a credible
regression, or an independently meaningful contract. In an audit, an existing
test that must change for a behavior-preserving source reorganization is
suspect, not automatically deletable; the authoring gate still rejects new
ones.

Before judging a candidate, read the complete test and its production owner,
the entry point, callers, callees, sibling implementations, overlapping tests,
CI routing (`scripts/check.sh` manifest, `.github/workflows/check.yml`), and
relevant `git log`. When a test claims dependency-backed behavior (Spring Boot
4, Testcontainers, TanStack Query, `@opencode-ai/sdk`, E2B), read the
dependency source or types directly.

Coverage is a signal, not the bar. Baseline for this session, on `main` at
`a5d2a36`:

| Stack | Lines | Branches |
|---|---|---|
| Backend (JaCoCo aggregate) | 98.8% | 87.3% |
| Frontend (vitest v8) | 100% | 93.7% |
| `classifiers` (pytest-cov) | 100% | 100% |
| `sandbox-runner/launcher` | 100% | 82.6% |
| `sandbox-runner/agent-sandbox` | 100% | 82.5% |
| `packages/mcp` | 100% | 90.6% |

Almost all of the backend line gap is the Kafka spool, which only
`KafkaSpoolIntegrationTest` reaches (CI runs it; this container can't pull its
Redpanda image). A deletion that drops a covered line needs a named keeper that
still covers the behavior, or a reason the line is dead.

## Discovery

Keep discovery read-only and report evidence before editing. For broad scope,
run parallel read-only `Explore` agents, one per lane:

- **backend**, split by Maven module (`analysis`, `core`, `llm-runtime`,
  `product`, `shared`, `substrate`, `surfaces`, `tenancy`, `app`) or by
  product-area package (`ai.tessary.<area>`, which spans modules; the slice
  list comes from `task check -- typo`);
- **frontend** (`frontend/src/**/*.test.{ts,tsx}`);
- **Node** (`sandbox-runner/launcher/test`, `sandbox-runner/agent-sandbox/test`,
  `packages/mcp/test`);
- **Python and contract** (`classifiers/tests`, `contract/tests`; the vendored
  files under `contract/` are verbatim copies and are never edited);
- **shell gates** (`scripts/check-*.sh`), then a cross-cutting pattern sweep.

Outside campaign mode, prefer a few high-confidence candidates over a large
speculative inventory.

## Retention bar

Keep a test when it independently enforces one of these contracts:

- the public REST API or the OpenAPI spec (`OpenApiSpecDriftTest`, frontend
  route-manifest and API drift guards);
- the evals-plugin bundle contract (`contract/`, `BundleAssembler` shard
  routing, including the deliberate `Shard.IGNORE`);
- MCP / JSON-RPC wire semantics (protocol codes vs `result.isError`);
- tenant isolation (every cross-org and cross-project path);
- auth, crypto, and token verification (tampered ciphertext, wrong key, revoked
  token, `last_used_at`);
- idempotency (a repeated auth callback must not duplicate rows);
- storage: repository round-trips (insert a fully populated row, read it back,
  assert every field) and Liquibase migrations;
- identifiers and slugs (collisions, truncation edges);
- architecture and module layering (ArchUnit under `app/src/test/.../arch/`,
  `check-module-hygiene.sh`);
- config keys and defaults that another system reads;
- release, packaging, and self-host contracts (price-book path, compose
  artifact, version consistency, license headers);
- the classifier-quality doc pinned to `serve.py` and catalog thresholds.

Also keep:

- call ordering when order is observable behavior;
- regressions with a credible failure mode;
- source inspection when it's the cheapest independent guard: it fails when the
  contract changes (the user-facing key, byte, or path) and survives an
  identifier-only refactor. Most `scripts/check-*.sh` gates are this kind.
  Remember the standing rule that no gate reads a `.md` or `.mdx` file;
- a retained test that fails on the baseline: treat it as a possible product
  bug, reproduce it, and fix the owner rather than deleting it. Exception
  already documented: `MeteringIntegrationTest.dayRollupEqualsTheSumOfItsHours_viaRawReaggregation`
  is a known, order-dependent flake.

Static or slow is not a reason to delete. A test that looks like
implementation may still be the only independent contract; prove otherwise
first.

## Candidate evidence

Record every field below before editing. A missing field means the candidate
isn't ready for deletion:

- exact test name and location (`path:line`);
- which failure it can actually detect;
- non-test callers of the covered production or test-support seam;
- the stronger owner-boundary proof that remains, or why no proof is needed;
- relevant history (`git log -S`, the PR that added it) and why the test or
  seam exists;
- production or test-support code the deletion unlocks;
- risk, and the focused validation command.

Present the ledger to the user and get approval before the first edit.

## Edit shape

Choose one coherent owner-boundary batch. Delete obsolete test-only exports,
widened visibility, wrappers, and dead production paths instead of keeping
aliases (root rule: never keep the old implementation as a fallback). Move
retained regressions to their canonical owners. Consolidate repeated
assertions into one table-driven contract. Widen `TenantFixture` rather than
forking it.

Prefer net-negative production lines of code. Don't add replacement tests that
restate the same implementation, and don't turn uncertain candidates into
cleanup just to increase the deletion count. No new comments unless they
explain a non-obvious invariant.

## Validation

Never edit source or tests while a Maven, vitest, or `node --test` run is live
in the checkout.

**Environment for this session:** the backend needs JDK 25. Use
`JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` (installed via apt; the default
`java` is 21). Tier 2 tests need Docker; start it with `dockerd` if
`docker ps` fails. `KafkaSpoolIntegrationTest` can't run here because
`docker.redpanda.com` is blocked; CI covers it.

1. Run the smallest owner and sibling tests:
   - backend class: `cd backend && mvn -B -q -pl <module> -DfailIfNoTests=false -Dtest='<Class>' test`
     (`-pl app` for anything `@SpringBootTest`);
   - backend area: `task check -- <area>` (or `bash scripts/check.sh <area>`);
   - frontend: `cd frontend && pnpm exec vitest run <path>`;
   - launcher and agent sandbox: `bash scripts/check-sandbox-runner-launcher.sh`;
   - MCP bridge: `bash scripts/check-mcp-bridge.sh`;
   - classifiers: `bash scripts/check-groundedness-serve.sh`;
   - contract: `bash scripts/check-vendored-plugin-rules.sh`.
2. For a removed source grep or shell-gate assertion, run the script that owns
   the real contract.
3. Format, then `git diff --check`: `task backend:format` (Spotless) for Java,
   `cd frontend && pnpm run lint` for TypeScript, `uv run ruff` for Python.
4. For every restored or retained contract, break the production line once and
   confirm the keeper goes red, then restore it byte for byte (the coverage
   PR's standard).
5. Rerun coverage for each touched stack and compare with the baseline above:
   - backend: `mvn -B -T 2 verify` then
     `mvn -B -q -pl app -am jacoco:report-aggregate@jacoco-report-aggregate`;
     read `backend/app/target/site/jacoco-aggregate/jacoco.csv`;
   - frontend: `pnpm run coverage`;
   - Node: `NODE_OPTIONS="--require <flush-on-term.cjs>" node --test --experimental-test-coverage --test-coverage-exclude='test/**'`
     (add `--experimental-test-module-mocks` for `agent-sandbox`), where
     `flush-on-term.cjs` is `process.on('SIGTERM', () => process.exit(143));`;
   - Python: `uv run --with pytest-cov pytest --cov --cov-branch`.
6. Run the full gate before merging: `task check` (`bash scripts/check.sh`),
   the same manifest CI runs.
7. Inspect `git diff --numstat`; report production and tooling separately
   from tests and test support.
8. After the final audit edits, run `/code-review`.

## Docs to update in the same change

- A durable test-ownership rule found by the audit goes in `backend/AGENTS.md`
  or `frontend/AGENTS.md` (keep each under about 250 lines), not here.
- Reference facts (counts, coverage posture, gated suites) go in
  `devdocs/reference/test-suite.md`. One home per fact.
- Deleting or adding a `scripts/check-*.sh` gate updates the `scripts/check.sh`
  manifest (it asserts completeness against the scripts on disk).

## Landing and continuation

Commit, push, or open a PR only when the user authorizes it. Work on the
session branch (`claude/dreamy-turing-c0zaoh`) and push with
`git push -u origin <branch>`. Open PRs through the GitHub MCP tools (there is
no `gh` CLI). End commit messages and PR bodies with this session's
attribution lines. Land one coherent PR at a time; after it lands, restart the
branch from the latest `main` and rerun read-only discovery for the next
high-confidence batch.

## Handoff

Report:

- root cause and the low-value categories removed;
- production owner simplifications;
- retained false positives and why they stay;
- each new test: the bug it catches and its red output;
- focused and full proof actually run, including coverage before and after;
- production versus test lines of code;
- PR and merge state;
- named follow-ups.
