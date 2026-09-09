# Metric drift — implementation plan

Execution plan for `classifiers/metric_drift/PROGRAM.md`. The program says *what and why*; this
says *in what order, in which files, and how each step proves itself*.

Base: `main` at `1c3b0071`. Nine PRs, each independently reviewable and each leaving the tree
green. Nothing is user-visible until PR 5, and nothing reaches Triage until PR 8.

**Settled decisions this plan assumes** (all from PROGRAM.md, do not relitigate):

| | |
|---|---|
| Two classifiers | `duration_drift` (turn + tool measures), `cost_drift` (cost + 4 token measures) |
| Sketch | fixed log-spaced histogram, hand-rolled, behind an interface |
| Cases | open only after Layer-2 rules `deviation` |
| Alert budget | none on findings; the cap is on escalation |
| Metric source | column-preferred with derivation fallback |
| Windows | cut on event time; cursor on ingest time |

---

## 0. Before starting

- Fresh worktree off `origin/main`; never work on `main` directly.
- `export JAVA_HOME=/opt/homebrew/opt/openjdk@25` — `java` is not on PATH, and `task check` fails
  confusingly without it.
- `task check -- classifier` before every commit. CI runs the full gate per PR.
- Do not start Docker unless a step below says an integration test needs it.

---

## 1. PR 1 — `MetricHistogram`: the sketch and its distance

**Goal.** A pure, dependency-free summary of a set of `log(value)` samples, plus signed W₁ between
two of them. No Spring, no database, no project concepts.

**Files** — `backend/analysis/src/main/java/ai/tessary/classifier/metric/`

- `MetricSketch.java` — the interface. `add(double logValue)`, `merge(MetricSketch)`, `count()`,
  `quantile(double)`, `toJson()` / `fromJson()`. Exists so a t-digest can replace the histogram
  later without touching callers; PROGRAM.md §"sketch payload" argues why we are not starting there.
- `MetricHistogram.java` — the implementation. Fixed log-spaced bins over a compile-time range,
  plus underflow/overflow counters.
- `MetricDistance.java` — `signedW1(MetricSketch ref, MetricSketch cur)`.

**Design notes.**

- **Bins.** Bin `i` covers `[lo · r^i, lo · r^(i+1))`. With `r = 1.05` (5% per bin), 1 ms → 1 hour
  is `ln(3.6e6) / ln(1.05) ≈ 310` bins. Round to **320 bins**, `lo = 1 ms`. Cost reuses the same
  machinery over `log($)` with its own `lo` — do not share one range across measures.
- **Underflow / overflow are counted, never clipped silently.** A bucket whose traffic is pinned in
  the overflow bin is a configuration bug, and the eval must be able to see it.
- **W₁ on a shared bin grid is a sum, not an integral**:
  `W₁ = Σ_i |CDF_ref(i) − CDF_cur(i)| · binWidth_i`, with `binWidth_i = ln(r)` constant in log
  space — so it collapses to `ln(r) · Σ_i |CDF_ref(i) − CDF_cur(i)|`. Sign from the difference of
  means. Both sketches must share a grid; assert it rather than interpolating.
- `e^W₁` is the reported ratio. Keep the raw `w1` too — the evidence blob carries both.

**Tests** (`MetricHistogramTest`, `MetricDistanceTest`)

1. Identical inputs → `W₁ == 0`, sign 0.
2. Every sample multiplied by 1.4 → `W₁ ≈ ln(1.4)` within one bin width. **This is the test that
   proves the whole statistic** — write it first.
3. Merge is exact and associative: `merge(a, merge(b,c))` equals `merge(merge(a,b), c)` bin for bin.
4. Round-trip `toJson`/`fromJson` preserves every bin.
5. Grid mismatch throws rather than returning a plausible number.
6. Empty and single-sample sketches do not divide by zero.

**Acceptance.** No Spring on the classpath of these tests. Test 2 passes at 1.1×, 1.4× and 3×.

---

## 2. PR 2 — `MetricSource`: where the numbers come from

**Goal.** One accessor per measure, column-preferred with a derivation fallback (PROGRAM.md §3.0).

**Files**

- `metric/MetricSource.java` — the read seam. One method per measure family, batched per sweep page.
- `metric/MetricSourceRepository.java` — the SQL.

**Design notes.**

- Turn duration: `trace.latency_ms`, else the **root span's own** `ended_at − started_at`,
  `DISTINCT ON (trace_id)` ordered `started_at ASC` for the ~0.1% of traces with several roots.
  Never `max(end) − min(start)`.
- Tool duration: `observation.latency_ms`, else that span's own `ended_at − started_at`.
- Cost: `trace.total_cost`, else `SUM` over leaf spans of
  `TokenUsage.of(usage, model).nonOverlapping()` priced through `TokenPriceBook`.
- Token measures: always derived; there is no per-bucket column.
- **Abstain, and say why.** Return an explicit `Absent(reason)` rather than a null — `NO_END_TIME`,
  `UNPRICED_MODEL`, `BUCKET_NOT_REPORTED`. The reasons are counted per sweep and logged; a measure
  silently abstaining on 100% of traffic is the exact failure PROGRAM.md §13 warns about, and a
  counter is what makes it visible in one glance rather than one investigation.
- **Unfinished traces** (`ended_at IS NULL` on the root) are returned as their own category and
  counted, never dropped.

**Tests** (`MetricSourceTest`, integration — needs Postgres)

1. Rollup column populated → used verbatim.
2. **Rollup column NULL → derivation used.** Fixture must leave the rollups unset; this is
   production today, and a fixture that fills them hides the whole failure mode.
3. Multi-root trace → earliest-starting root wins.
4. Async child outliving the root → duration is the root's, not the envelope.
5. Unpriced model → `Absent(UNPRICED_MODEL)`, never `0`.
6. OpenAI-shaped usage → cache reads not double-counted (assert against `nonOverlapping()`).

**Acceptance.** Test 2 and test 5 are the ones a reviewer should look at first.

---

## 3. PR 3 — `metric_baseline`: schema and repository

**Goal.** Persistence for per-(bucket × measure) window state.

**Files**

- `db/changelog/changes/NNNN-metric-baseline.sql` (number it from whatever is next) + master yaml entry.
- `classifier/metric/MetricBaselineRow.java`, `classifier/metric/MetricBaselineRepository.java`.
- `docs/reference/data-model.md` — same-PR co-update, per the documentation policy.

**Design notes.**

- Columns per PROGRAM.md §10. `measure` is a persisted string; it never gets renamed.
- Unique index on `(project_id, classifier_id, measure, bucket_kind, bucket_key, COALESCE(environment_id, ''))`.
- Keyset watermark `counted_through_at` / `counted_through_id` shares the counters' lifetime, so a
  re-sweep cannot double-count. Behaviour drift needed this after logging `trace_count` 565 against
  443 distinct traces — do not rediscover it.
- Forward-only migrations. Never edit an applied changeset.

**Tests.** Repository integration test: upsert idempotence under a replayed page; the unique index
actually collides on a NULL environment.

---

## 4. PR 4 — the sweep, `turn_duration` only

**Goal.** End-to-end for one measure, behind a classifier that is not yet in the catalog. Nothing
user-visible.

**Files**

- `classifier/metric/MetricDriftSweep.java` — mirrors `BehaviorDriftSweep`: **it reuses the ordinary signal
  job's cursor and lease.** No new scheduler, no new job table. `ClassifierWorker` already claims
  the job; this adds a dispatch branch.
- `classifier/metric/MetricDriftConfig.java` — the policy record, parsed from `defaultConfigJson`.
- `classifier/metric/MetricDriftDetector.java` — **pure**: takes two sketches plus config, returns a
  decision. The eval drives this without a database.

**Dispatch.** `ClassifierWorker` currently reads `catalog.grainFor(signal.detector())` and branches
`Grain.TRACE → behaviorSweep`. `grainFor` returns one grain per detector kind, but `duration_drift`
spans TURN and OBSERVATION — so it cannot be dispatched that way. **Add `Grain.WINDOW`** and a
branch for it. This is an honest addition to the enum rather than a workaround: for these
classifiers the scored unit genuinely is a window of a bucket, not a span, a turn or a trace.
Each measure's own candidate grain lives in `MetricDriftConfig`, read by the sweep.

**Settle.** Cost measures use `trace_settle_seconds`; **duration measures do not** — the root span's
arrival is the completion signal. Two paths, deliberately (PROGRAM.md §5).

**Windows.** Cut on `COALESCE(started_at, created_at)`. The cursor stays on `created_at`. Two
clocks; a comment at each site saying which and why.

**Config defaults** — all `EXPERIMENT(metric-drift-tuning)`-tagged, in the style of `CusumParams`,
with clamps so a live-edited blob cannot drive the detector into nonsense:

```json
{
  "window_target_count": 500,
  "window_max_hours": 168,
  "min_sample": 150,
  "w1_floor": 0.18,
  "settle_seconds": 300,
  "hist_bins": 320
}
```

`w1_floor = 0.18` is `≈ 1.2×`. **It is a guess and is labelled as one** — PR 9's null-case run sets
the real value. Do not present it as measured.

**Tests.** Detector unit tests on synthetic sketches (fires at 1.4×, silent at 1.02×, silent below
`min_sample`). One sweep integration test proving the cursor advances and the watermark prevents
double-counting on replay.

**Acceptance.** Sweep runs against a seeded corpus, writes baselines, opens no findings yet.

---

## 5. PR 5 — findings: `duration_drift` in the catalog

**Goal.** First user-visible output.

**Files**

- `BuiltInClassifierCatalog.java` — the `duration_drift` module. `detectorFactory = null`,
  `Grain.WINDOW`, measures in `defaultConfigJson`. (Seeds ENABLED as of the catalog's lifecycle
  removal; the flag is what holds it back.)
  Comment must state that `callSiteFactsRead()` is deliberately empty (#654's seam) — these read
  `observation` columns only.
- `classifier/finding/FindingRow.java` — `Cause.DISTRIBUTION_SHIFT`.
- a changeset widening the `cause_kind` CHECK, if one enumerates the kinds.
- `BehaviorDriftService.java` / `classifier/finding/FindingRepository.java` — the `cause_kind` branch in
  `resolve(...)`: `expected` re-pins the reference and appends a baseline-changelog row;
  `not_expected` leaves the reference alone. Both UI labels and both action strings already exist
  in `ClassifiersPage.tsx`; this is entirely server-side.

**`cause_key` is user-visible** — rendered verbatim in mono. Keep it legible:
`turn_duration:discover-sales-prospects:slower:pinned`.

**Tests.** Resolve-branch test: `expected` moves `pinned_sketch_json` and writes a changelog row;
`not_expected` moves neither. A catalog test asserting the module declares a capability flag.

**Acceptance.** A seeded shift produces exactly one finding, and pressing *Legitimate — absorb*
stops it recurring.

---

## 6. PR 6 — `tool_duration` and the suppression rule

**Goal.** The second measure under the same switch, and the first point where one classifier spans
two grains — so it is the first point §6.1 can be tested.

- Bucket key from `ActionSymbol`'s `kind:normalized-name`, so it matches drift's alphabet.
- Suppression: a turn-duration shift explained by a tool-duration shift inside the same call site
  emits the **tool** finding with the turn shift attached. Threshold generous — partial explanation
  is still explanation.
- The unexplained turn shift still fires on its own. That is the "eleven tool calls where three used
  to do" case, invisible at tool grain, and the reason turn duration is measured at all.

**Tests.** Both branches, plus the negative: a turn shift with no tool shift is not suppressed.

---

## 7. PR 7 — `cost_drift`

- Second catalog module, same shape.
- `cost` is the only measure that opens a finding. The four token measures are computed every window
  and attached as evidence (§6.1). A prompt edit that kills caching must produce **one** row.
- Cache-write abstains where the family does not report it — not zero.
- Cache read reported as a ratio, `cache_read / (cache_read + input)`.
- Resolve rates at **sweep** time against the current price book, so a price-book gap is only as
  long as the deploy that closes it (§3.3).

**Tests.** Cache-collapse fixture → one cost finding whose evidence names the cache-read bucket.
OpenAI-family fixture → cache-write absent, not zero.

---

## 8. PR 8 — `MetricDriftSource implements CaseSource`

**Goal.** Findings reach Triage — the first point anyone gets paged.

- New `CaseRow.Detector.METRIC_DRIFT` constant.
- **Gated on triage.** `detect()` returns findings whose `triage_verdict` is
  `sound`, plus any a human marked `not_expected`. Matches `BEHAVIOR_DRIFT`'s documented
  posture.
- **Return the live set, not a delta.** `CaseReconciler` closes cases whose detections dropped out;
  a delta-only source leaves every case it opens to be closed by hand.
- `title` a sentence: `"discover-sales-prospects turns are 1.4× slower"`.
- `basis` in this detector's own terms — "distribution shift against the window pinned at the last
  deploy, W₁ 0.34 on logs over 1,180 turns". Never normalized to another detector's scale.
- `onsetAt` = close time of the first window that showed the shift, not when the sweep noticed.
- **Escalation valve here.** Findings are unbudgeted; cap what reaches
  `BehaviorTriageWorker`, which is an agentic sandbox run with a repo clone per finding.

**Tests.** Recovery closes the case on the next pass. An untriaged finding opens nothing.

---

## 9. PR 9 — the eval, and setting the real thresholds

Python, under `classifiers/metric_drift/`, mirroring `behavior_drift`'s harness.

1. **Null case first.** Real traffic split in half, unmodified. Everything that fires is a false
   positive. **This run sets `w1_floor`** — until it lands, the value in PR 4 is a guess and the
   classifier stays disabled.
2. **Injection.** Multiply a bucket's durations by 1.2 / 1.5 / 2.0; collapse a cache-read ratio;
   inject a retry loop. Report detection rate per operator and lag in windows.
3. **Replay a real deploy.** A known past regression fires, with `since_version_id` on the right
   deploy.
4. Write the measured operating point back into PROGRAM.md, as `behavior_drift` §12.5 does. Only
   then consider turning `duration_drift_enabled` / `cost_drift_enabled` on for anyone but us.

---

## 10. Deferred, deliberately

- **Frontend copy.** A `distribution_shift` finding renders today as
  `turn_duration:…:slower:pinned — distribution_shift`. Ugly, not broken. A label map and a sentence
  are a small follow-up; nothing is blocked.
- **Renaming `behavior_finding` → `classifier_finding`.** The right eventual move, drags the
  `/findings` endpoint with it.
- **Automatic re-pin from triage.** `BehaviorTriageVerdict` says its confidence is
  "never authority to mutate the baseline". Changing that is its own decision, not a quiet addition.
- **Rollup backfill.** Orthogonal. `MetricSource` works either way.

---

## 11. Risks worth naming up front

| Risk | Signal it is happening | Response |
|---|---|---|
| `w1_floor` guessed too low | PR 9's null case fires on ordinary traffic | It is a config value; move it. Until then the classifier's flag is the only thing keeping it off other orgs. |
| Tool buckets too thin to ever arm | Most buckets never leave `learning` | Expected for rare tools; verify the coverage number rather than lowering `min_sample`. |
| Suppression hides a real turn regression | An injected span-count increase is swallowed by a tool shift | PR 6's negative test exists for this; tighten the "explained by" threshold. |
| Sketch range wrong for cost | Overflow bin non-empty in the null run | Counted, not clipped — that is what the overflow counter is for. |
