# Test-pruning campaign (tessary)

Campaign mode prunes one area's whole test surface in one PR: a backend
product area (a package under `ai.tessary`, which spans Maven modules), the
frontend, or one Node package. The value bar, retention bar, candidate
evidence, and validation in [SKILL.md](SKILL.md) apply to every lane. This file
adds the order of work. Each step ends on its completion criterion; don't start
the next step early.

**Steps 1 through 4 are read-only.** Show the user the ledger and lane plans,
and get approval before step 5. Root `AGENTS.md` forbids deleting or loosening
a test without asking.

## 1. Baseline

At a pinned `main` SHA, record:

- the area's test and test-support line counts;
- every test file's pass/fail state;
- the area's coverage (see SKILL.md § Validation step 5).

Keep baseline failures in their own list. Separate known environment failures
(for example `KafkaSpoolIntegrationTest` in this container) and the documented
metering flake from real ones.

Done when every in-scope test file has a recorded baseline result.

## 2. Lanes and inventory

Split the surface into **lanes** along production owner boundaries, not file
names. For a backend area that usually means: Tier 1 unit tests per owning
module, Tier 2 `@SpringBootTest` tests in `app`, repository round-trips, REST
controllers, and any MCP or JSON-RPC surface. For the frontend: routes/pages,
shared components, and API client code. Include the area's cases at shared
boundaries (`TenantFixture`, `testsupport`, ArchUnit, `scripts/check-*.sh`
gates that name it).

Done when every test file the area owns belongs to exactly one lane.

## 3. Read-only ledger per lane

Give each lane to its own read-only `Explore` agent. The agent reads every
assigned test in full, including parameter tables, plus the production owners,
their entry points, callers, history, and CI routing. Each test declaration
gets one mark in a written **ledger**. A `@ParameterizedTest`, `it.each`, or
`pytest.mark.parametrize` counts as one declaration unless its rows need
different marks; then mark each row.

- `R`: retain, naming the contract and the bug it catches. A retained test that
  only moves to a better-named file stays `R`, with the move noted.
- `F`: retain the contract but fix the assertion, such as a vacuous negative
  that passes when only one of several items is missing, or a `contains()` on
  message text that should assert the `ErrorCode`.
- `C`: consolidate, naming the owner that absorbs the assertion first: a
  sibling table row, a stronger boundary suite, or the shared owner in another
  module.
- `D`: delete, naming the proof that remains, or why no contract exists.

Judge a test by its assertions, not its name.

Done when every declaration in the lane has a mark and an evidence line.

## 4. Layer plan per lane

Treat the ledger as input, not as the edit list. A second read-only pass,
starting from the ledger, looks for the redundant **layer**. A common one here:
a Tier 2 `@SpringBootTest` replaying logic a Tier 1 test already pins, paying a
Spring context for nothing. Name the **keeper** suite for each contract. Prefer
the real boundary with a fake (the Testcontainers Postgres, a hand-written fake
of our own port, a fake Docker daemon) over a mocked collaborator. Correct any
ledger errors this pass finds.

Done when each lane plan names its retired files, its keeper per contract, the
assertions to carry into keepers, the test-only production seams unlocked, and
the Spring context fingerprints retired.

## 5. Cutover

Edit lane by lane. Serialize changes to shared harnesses (`TenantFixture`,
`backend/test-support`, `frontend/src/test`) through one owner. With each lane,
remove the test-only production seams it unlocks: injection parameters,
getters, widened visibility, reset exports, and indirection layers. If a suite
moves, confirm `scripts/check.sh` slice routing still selects it (a slice is a
package, so a class moved out of `ai.tessary.<area>` drops out of that slice).
Put durable test-ownership rules this campaign actually found in
`backend/AGENTS.md` or `frontend/AGENTS.md`.

Done when every lane plan is applied and each lane's keepers pass.

## 6. Preservation review

Before claiming completion, have independent reviewers compare deleted
coverage against the keepers, one reviewer per boundary group. They look for
contracts that lost their only proof, and for new assertions that can't fail,
such as a rejection row the production code never reaches. Compare coverage
against the step 1 baseline; every newly uncovered line needs a reason.

For each restored contract, make one deliberate **mutation** of the production
owner and confirm the keeper goes red. Then restore the source byte for byte.

Done when every reported gap is restored or rejected with source evidence, and
every restored contract has a caught mutation.

## 7. Product defects

A baseline failure that survives into a keeper is a bug report. Fix it at its
owner as a separate commit, and prove it through the real user flow with a
**control** run that reverts the fix and shows the old behavior. Record
unrelated product problems as follow-ups (suggest them as separate tasks)
instead of fixing them in the campaign.

Done when each fixed defect has a failing control and a passing candidate on
the same harness.

## 8. Reconcile and hand off

Campaigns outlive many `main` commits. Merge `main` into the branch; never
rebase or force-push a shared branch. When `main` modified a file the campaign
deleted, keep the deletion, port the new contract into the keeper, and confirm
every new regression `main` added still has a home. Rerun the whole area's
suite (`task check -- <area>`), then the full `task check`, on the merged head.

Expect review tooling to see a truncated file list on a diff this large.

Hand off with the [SKILL.md](SKILL.md) report, plus:

- baseline and final test and test-support line counts, with production
  counted separately;
- baseline and final coverage for the area;
- lanes, retired layers, and keepers;
- Spring context fingerprints retired, and the suite wall-clock change;
- preservation gaps found and their mutations;
- product defects with control and candidate proof.
