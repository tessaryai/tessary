# The metric-drift program — windowed distribution drift on duration and cost

Status: **spec, not built.** Agreed 2026-07-30 between Akhil and Claude.
Sibling of the behavior_drift PROGRAM note (overlay-owned since #1293, not part of this repo's
public export), which is the structural precedent for everything here: per-project fitting, an
epoch lifecycle, findings-not-firings, and an
alert budget instead of a guessed threshold.

Base: **`main` at `1c3b0071`.** PR #652 ("Land the cases redesign on real production data") has
merged, so the `cases/` slice this classifier plugs into is on main and there is no branch to
track. Reviewed against main as of 2026-07-30, which also carries #653 (behaviour-drift
saturation) and #654 (abstention cursor rewind) — both changed assumptions this spec had made.

---

## 0. What this is not

- **Not a per-trace outlier detector.** An earlier draft of this program fitted a distribution
  per bucket and labelled the traces past a tail threshold. That was dropped deliberately.
  Slow is not bad and expensive is not bad — a 40-second research run and a $0.40 turn are
  both routinely correct. There is no per-trace label to assign, so assigning one produces a
  detector whose every firing needs a human to explain it away.
- **Not a threshold alarm.** "Alert when p95 > 10s" requires someone to know what 10s means for
  a call site they have never read. The bar is the population's own recent past.
- **Not an analytics surface.** Vitals already answers "what are my numbers". This answers
  "did they move, when, and by how much", and stays silent otherwise.

What it *is*: two classifiers, four measures between them, each comparing a bucket's recent
distribution against that same bucket's earlier distribution and reporting the move.

---

## 1. The one hard problem

**Duration and cost vary enormously for legitimate reasons, and almost all of that variance is
structural rather than temporal.**

A RAG lookup and a thirty-step agent run differ by two orders of magnitude in both measures.
Pool them and the only thing a detector learns is that long-shaped traces are long: it fires
forever on the most complex call site and never once on a regression.

So the program is mostly about **what you compare against what**. The statistics are the easy
part — they are a hundred years old (Shewhart's common-cause vs special-cause, 1924) and the
divergence metric is a one-liner. The bucketing is where this succeeds or fails.

---

## 2. Buckets — the decision that matters more than the model

### 2.1 The key

**The bucket is the entry-point call site**, resolved exactly as behaviour drift resolves it:
`BehaviorSubstrateRepository.SELECT_TRACE_HEAD`'s lateral join, root-span-first, with the
`seq → started_at → created_at` fallback chain.

Do not re-derive this. That ordering exists because `seq` is NULL on every OTLP-ingested
observation, and without the fallback 443 traces whose roots all carried one call site were
scattered across six.

The reason the entry point wins over any child span: a trace legitimately spans several call
sites, and a baseline scoped to a child models "traces that happened to contain this tool"
rather than "traffic that entered here".

### 2.2 The rule for everything else

> **Anything you want to detect a change in must not be in the bucket key.**

Put a dimension in the key and you have told the detector that differences along it are normal.
A cost baseline keyed on model compares pricey traces only to other pricey traces, so the day a
route silently switches to a model costing 30× more, the detector goes quiet — the exact event
it exists to catch.

| Dimension | Where it goes | Why |
|---|---|---|
| entry-point `call_site_id` | **bucket key** | permanent population difference |
| `environment_id` | **bucket key** | dev has cold starts and attached debuggers |
| `project_version_id` | **epoch boundary**, not key | else every deploy is a cold start; mirrors `behavior_profile.opened_by_version_id` |
| `call_site.model` | **cost predictor / drill-down**, not key | in the key, a silent reroute redefines normal |
| span count, kind mix | **not a key**, and **not a covariate either** — see §3.2 | an agent choosing to do more *is* the signal |

### 2.3 Thin buckets

A tool called 30 times a week bounces on luck alone. Decision: **wait, don't skip.** A bucket
below the minimum sample keeps accumulating — by elapsed time rather than by count — until it
has enough to compare. A rare tool gets watched on a slower clock. This is behaviour drift's
`min_support` / `graduation_sessions` posture and ADWIN's native behaviour.

### 2.4 Unattributed traces

`__unattributed__` is **not a bucket**. Behaviour drift lumps them together, which is right for a
sequence model and wrong here: the pile is a mixture of everything the instrumentation missed,
so its distribution moves whenever the mix moves.

Instead, report its **size** as a coverage number in `vitals/` — "18% of traces aren't
attributed" is the actionable fact, and instrumenting those paths closes the blind spot
permanently in a way that watching a mixture never would.

---

## 3. What gets measured

### 3.0 Where the numbers come from — one accessor, column-preferred

**Decision (Akhil, 2026-07-30): build as though the rollup columns are populated.** They are the
intended source and will be filled.

The state of play today, so the guard is written knowingly rather than discovered in staging:

- **The underlying facts are all present.** `observation.started_at`, `ended_at`, `usage` and
  `model` are populated on the OTLP path, and `tool_call.latency_ms` is genuinely written (derived
  from the span end time). Nothing about this program is blocked.
- **The pre-computed rollups are not.** `trace` / `observation` / `context` all carry
  `latency_ms`, `total_cost`, `total_tokens`, `error_count`, and `StructuralEnricher` passes
  literal `null` for them at the write site. `VitalsRepository` says the same, verified against
  production. The traces UI shows real cost because `TracesController` computes it in the query,
  not because the column holds it.

So: **one accessor, `MetricSource`, that prefers the column and derives when it is null.** Not a
hedge — a two-line guard behind a single seam. It matters for two reasons beyond today: a rollup
backfill will not reach historical rows, and the pull/upload ingest path supplies no span end time
at all, so a derivation has to exist regardless.

| Measure | Column | Derivation when null |
|---|---|---|
| turn duration | `trace.latency_ms` | the **root span's own** `ended_at - started_at`; `DISTINCT ON` earliest-starting root for the ~0.1% of traces with several |
| tool-call duration | `observation.latency_ms` | that span's own `ended_at - started_at` |
| cost | `trace.total_cost` | **SUM over leaf spans** of `TokenUsage.of(usage, model).nonOverlapping()` priced through `TokenPriceBook` |
| token buckets | `trace.total_tokens` (total only) | same leaf sum, per bucket — the buckets have no column and are always derived |

Two consequences worth stating outright, and they apply to the column just as much as to the
derivation — whoever fills the rollups has to get these right too:

- **Do not compute duration as `max(ended_at) - min(started_at)` over the trace.** Measured against
  production, that min/max envelope inflated p95 by ~10% versus the root span, because async
  children outlive their parent. The root span encloses its children and is what Jaeger, Tempo and
  Datadog report.
- **Root spans carry no model.** `VitalsRepository` documents a bug from exactly this: bucketing
  cost by the span's own call site while counting turns by root span mixes two populations. Cost is
  summed from llm leaves; the bucket key still comes from the root's entry-point call site (§2.1).
  Never try to read a model off the root.

**Unfinished traces are counted, not dropped.** `ended_at IS NULL` on a root span means the turn
never completed. Vitals counts these as their own population rather than silently excluding them,
and so must this: excluding them removes precisely the traffic a duration detector most wants to
see, and does it invisibly.

### 3.1 Two classifiers, four measures

**Decision (Akhil, 2026-07-30): two on/off switches, not one and not four.** A classifier is one
decision a human makes; the measures under it are how that decision is implemented.

| Signal key (persisted) | Measure | Sweep grain | Bucket kind |
|---|---|---|---|
| `duration_drift` | `turn_duration` | `TURN` | call site |
| | `tool_duration` | `OBSERVATION` | `kind:tool-name` via `ActionSymbol` |
| `cost_drift` | `cost` (headline) | `TURN` | call site |
| | `tok_input` / `tok_output` / `tok_cache_read` / `tok_cache_write` | `TURN` | call site |

Naming rule (standing): wire and Java say *classifier*, the DB and persisted strings stay
*signal*. These keys are persisted signal keys and are never renamed. Note the measure names are
persisted too — they are a column on `metric_baseline` (§10) — so the same rule applies.

**Why the turn/tool split lives inside one switch.** Measuring at both layers is what makes a
finding actionable: "this turn was slow" sends someone to read traces, whereas "this turn was slow
and 34 of the 38 seconds were one `search_docs` call against a p50 of 400ms" names the fix. They
are two halves of one question, so they are one decision. The same argument makes the token
buckets ride under cost.

**One consequence to respect:** a switch covering two grains must not fire twice for one event. A
turn that is slow *because* one tool is slow is one cause, not two. See §6 on suppression.

### 3.2 The conditioning rule

> **Condition on the ask. Never condition on the answer.**

Inputs the user controls — message length, thread depth, input tokens — are fair to normalize
away; a five-question turn taking five times as long is not a regression.

The agent's own choices are not. An agent decomposing "what's my balance" into eleven tool calls
**is the bug**. Regress duration on span count and that bug becomes the explanation, the residual
goes flat, and the detector reports nothing.

### 3.3 Cost decomposition

`$`/turn is the headline. Underneath, the same machinery runs per token bucket so a firing
explains itself: input / output / cache-read / cache-write.

Three hard requirements, all from `vitals/TokenUsage`:

1. **Read `TokenUsage.of(usage, model).nonOverlapping()`, never the raw `observation.usage` blob.**
   `StructuralEnricher` normalizes every provider onto one `gen_ai.usage.*` vocabulary, so an
   OpenAI generation arrives wearing Anthropic key names while still carrying OpenAI's
   cache-*inclusive* input count. Trusting the spelling already billed OpenAI cache reads twice.
   Untreated, a call site that switches provider shows a large input-token jump for purely
   bookkeeping reasons.
2. **`tok_cache_write` abstains where the family does not report it.** Anthropic and Bedrock bill
   cache creation explicitly; OpenAI's automatic caching has no write charge and emits no write
   count; Gemini bills cache storage per hour. Zero there means "not reported", not "no writes".
3. **Watch cache read as a ratio**, `cache_read / (cache_read + input)`. The most common silent
   cost regression is a prompt-prefix edit that stops the cache hitting; on the ratio it reads as
   a clean collapse from ~0.8 to ~0.0, and unlike the raw count it does not move with traffic
   volume.

Also: a model with **no rate in the price book is unpriced, not free**. Since cost is summed from
leaves (§3.0), the NULL to respect is a missing `TokenPriceBook` entry, not a NULL column. An
unpriced trace leaves the distribution rather than joining it at $0 — the posture `verdict.n`
already takes, and the counterpart to vitals counting unpriced calls rather than reading them as
free.

**And that abstention strands history.** This is #654's bug in a new place: the sweep's keyset
cursor only moves forward, so every trace abstained on for a missing rate is unscoreable forever,
and a price book that gains the model later never recovers them. Two mitigations, in order of
preference:

1. Do not let it happen — resolve the rate at **sweep** time against the current book, so the gap
   window is only as long as the deploy that closes it.
2. If a gap is observed, treat a price-book change the way `ClassifierService.rewindForCallSiteFact`
   treats a call-site fact: rewind the cost baselines. Note that rewinding a *distribution* baseline
   is materially messier than rewinding a per-observation cursor, because the sketch is a running
   summary rather than a replayable stream — the current window can be rebuilt, the pinned reference
   cannot. Prefer (1).

---

## 4. The statistic

### 4.1 Metric

**Signed Wasserstein-1 distance on `log(value)`**, between two windows of the same bucket.

`W₁` on logs has the property that makes it reportable: `e^W₁` is the multiplicative shift, so
the finding sentence writes itself — "1.4× slower". Direction is the sign.

### 4.2 Not a p-value

Do not use a KS test, and do not use any test whose output is a p-value. With 100k traces in a
window, KS reports "significant" for a three-millisecond shift. Significance inflates with
sample size; effect size does not. This is the single most common way distribution monitoring
fails in production.

### 4.3 Two references, always

| Reference | Catches | Misses alone |
|---|---|---|
| the rolling control — the last three weeks of closed windows, weighted | sudden breaks | slow boil — a change spread over a fortnight is absorbed into a fortnight of memory |
| the window pinned after the last deploy | cumulative creep since a known-good point | nothing, but it screams forever once something legitimately changes |

Both run every close. `project_version_id` is what the pinned reference hangs on.

The short-horizon reference was one previously-closed window until `control_json` replaced it. One
window was as noisy as the window it judged, took whatever shape the clock gave it (a quiet night
became the bar for a busy morning), and forgot a step change immediately, since the next window's
previous IS the new level.

The control is one slot per UTC day over 21 days, each an exact merge of the windows that closed in
it, weighted `2^(-age/7)` per sample and reported to the detector at Kish's effective sample size
`(Σ nᵢdᵢ)²/Σ nᵢdᵢ²`. Days per day rather than a slot per window, because a busy bucket closes one
every few minutes and a bounded per-window ring would span hours — back to judging a morning against a
night.

**Days a confirmed regression ran through are excluded**, so a shift under investigation cannot become
the bar the next window is judged against. Windows ruled *expected* fold in normally. Exclusion is
decided on every read rather than when the day was folded, because a ruling lands well after the
window closed — the ring stays an exact record and the late verdict is retroactive.

The wire word stays `previous`. It is the last segment of every `cause_key` ever written, so renaming
it would split one bucket's history into two causes and reopen everything already resolved.

### 4.4 Both directions, same bar

Faster and cheaper alarms exactly as loudly as slower and dearer. Expect a trickle of "yes, we
optimized that" dismissals; that is the price of catching an agent that quietly stopped doing its
verification step — the one regression that reads as a win on every dashboard in the product.

### 4.5 Sequential detection is a different question

CUSUM / EWMA / Page-Hinkley answer "has the population shifted", which is the same family of
question but produces no per-subject label. Note that **`trend/CusumDetector` used to exist** on this
branch and drove `GraderDegradationSource` (both deleted with grader Layer 1). It ran on grader pass
rate — a bounded mean — where a mean-shift test is appropriate.

It is not a substitute here: a latency regression frequently moves p95 while leaving the median
still, and a mean-shift test on a heavy-tailed measure is blind to exactly that. Reuse
`CusumParams`' *shape* (per-project dials, clamped, `EXPERIMENT(...)`-tagged) rather than its
detector.

---

## 5. Lifecycle — windows and epochs

Modelled on the behaviour-drift epoch, with the window as the extra inner loop.

```
observe → settle → fold into current window → close window → compare ×2 → finding
                                                    ↓
                                          current becomes prev
```

- **Settle — and it does not apply uniformly.** `vitals/` already worked this out and the split is
  load-bearing:
  - **Cost and the token buckets SUM over a trace's spans**, so they need every span to have
    arrived. Window them on `trace_settle_seconds`. Measuring early reads as cheap, which would
    surface as a permanent drift toward cheaper whenever ingest lags.
  - **Duration does not need a settle window.** It is read off the root span, which carries its own
    start and end — its arrival *is* the completion signal. Applying a settle horizon here buys
    nothing and delays every duration finding by the horizon.

    **But the sweep has to actually wait for that arrival**, and two things stood in the way when this
    was built (both fixed; recorded here because the sentence above reads as "no waiting at all"
    otherwise). A `trace` row is created by its FIRST span, and a batch exporter flushes on span END,
    so the root ships last — the gap between the row and its root is the turn's own duration. First,
    ingest degrades a child whose parent has not landed to a NULL `parent_observation_id` (keeping the
    stated parent in `parent_external_span_id`, migration 0035), so a read that only tests
    `parent_observation_id IS NULL` takes that child for the root and reports a 30-second turn as the
    1-second child that flushed first. `MetricSourceRepository.turnFacts` therefore requires
    `parent_external_span_id IS NULL` too. Second, with that in place a mid-flight trace honestly has
    no root, and the sweep holds its page there rather than stepping over it — bounded by
    `settle_seconds` so a root that will never arrive cannot park a forward-only cursor. Conditional,
    not blanket: a trace whose root is already in is swept on the pass it appears in. Both failures are
    length-biased toward long turns, which is why neither is survivable — the detector would go quieter
    as a latency regression got worse.
- **Window close: on event time, not ingest time.** This is #653's lesson generalized. A backfill
  lands a whole corpus in one burst, so by `created_at` "the last hour of traffic" is an artefact of
  the writer's chunking — a time-cut window would swallow a month of traffic in one window and
  compare it against nothing. Cut windows on `COALESCE(started_at, created_at)`, the same
  `event_at` expression `BehaviorSubstrateRepository` already uses and for the same reason: every
  span-of-time rule has to read the agent's real timeline.
  The **keyset cursor stays on `created_at`**, because ingest order is what is monotonic and
  gap-free. Two clocks, two jobs — do not collapse them.
- **Window close criteria.** By count where traffic is thick, by elapsed event time where it is
  thin, with a minimum-sample floor below which nothing is compared.
- **Epoch.** A deploy does not reset the window, it re-pins the reference. The baseline changelog
  records every re-pin, as behaviour drift's does.
- **Watermark.** `counted_through_at` / `counted_through_id` share the counters' lifetime, so a
  re-sweep cannot double-count. Behaviour drift's profile needed this after observing
  `trace_count` 565 against 443 distinct traces; do not repeat the mistake.

---

## 6. Findings, not firings

One row per **cause**, never per trace. Reuse `behavior_finding` — its own doc comment is already
"one CAUSE that fired, not one firing", and it carries every column this needs: `call_site_id`,
`cause_key`, `trace_count`, `exemplar_trace_id`, `since_version_id`, `escalated_at`, and the
triage set.

```
cause_key = <measure>:<bucket_key>:<direction>:<reference>
```

A shift persisting across twenty windows stays one row with a climbing `trace_count`. That is
what makes the "no alert budget" decision (§9) survivable.

**`cause_key` is user-visible.** `ClassifiersPage.tsx` renders it verbatim in mono as the finding's
headline, followed by the raw `causeKind`. So the separator format above is not an internal
detail — it is the sentence a human reads. Either keep it legible
(`turn_duration:discover-sales-prospects:slower:pinned` reads acceptably) or land the frontend case
in §8.3. Do not pick a compact opaque key.

### 6.1 One event, one finding — the two suppression rules

Because each classifier spans several measures (§3.1), the same real-world event can present on
more than one of them. Both rules below are about not reporting one thing twice.

**Token buckets are evidence, never findings.** `cost` is the only measure under `cost_drift` that
opens a finding. The four token buckets are computed every window and attached to that finding's
evidence as the decomposition (§7). A prompt edit that kills caching would otherwise produce five
rows — cost, input, cache-read, and whatever else moved — for one change.

**Tool duration suppresses the turn it explains.** If a turn-duration shift on call site `X` is
accounted for by a tool-duration shift on a tool inside `X`'s traces, emit the *tool* finding and
attach the turn shift to it. The tool row names the fix; the turn row only restates the symptom.
Accounting means the tool's shift covers most of the turn's — compare `Δ` in absolute time, and
keep the threshold generous, because partial explanation is still explanation.

Emit the turn-duration finding on its own when nothing explains it — that is the "eleven tool calls
where three used to do" case, where every individual call is fast and the *count* moved. That case
is real, is not visible at the tool grain, and is the reason turn duration is measured at all.

Add `BehaviorFindingRow.Cause.DISTRIBUTION_SHIFT` alongside `novelty` / `surprisal` / `omission`.
This is the generalization DESIGN-DIRECTION §Classifiers already calls for — "drift's grammar
generalized to all classifiers".

**Do not reuse `behavior_profile`.** Its fitted state is an n-gram alphabet, a VOMM and a
surprisal reservoir; a metric baseline is a numeric sketch per (bucket × measure). Same lifecycle
vocabulary, unrelated payload. Forcing both into `reservoir_json` recreates the two-writers-one-blob
bug that `BehaviorFitCarry` exists to avoid.

**Renaming `behavior_finding` → `classifier_finding` is out of scope.** It drags the frontend's
`/findings` endpoint with it. Note it as follow-up.

---

## 7. Evidence the finding must carry

The correction loop (§9) rules a workload change `expected`. The triage agent reads the repo, and
the repo will never say that users got chattier this week. So the finding must carry the input
side or the triage agent has to guess — and it will rule `deviation` every time.

```json
{
  "measure": "turn_duration",
  "bucket": { "kind": "call_site", "key": "discover-sales-prospects" },
  "reference": "pinned",
  "w1_log": 0.34,
  "ratio": 1.40,
  "direction": "up",
  "n_ref": 4210, "n_cur": 1180,
  "quantiles": { "p50": [2100, 2940], "p95": [9000, 21400] },
  "workload": {
    "input_tokens_p50":   [1240, 1290],
    "user_msg_chars_p50": [88, 91],
    "prior_turns_p50":    [3, 3]
  },
  "since_version_id": "pv_01J...",
  "window": { "opened_at": "...", "closed_at": "...", "kind": "count" }
}
```

Flat inputs beside moved outputs is the entire argument for "the agent changed, not the traffic".

---

## 8. Platform integration

### 8.1 Cases — the `CaseSource` seam

`cases/CaseSource` is the cause-neutral extension point: implement it, add a
`CaseRow.Detector` constant, and Triage, the case page, resolve/mute and the activity trail all
pick it up with no further change. `GraderDegradationSource` was the closest template — a
statistical detector feeding cases — until it was deleted with grader Layer 1; the live templates are
`MetricDriftSource` and `ToolErrorCaseSource`.

**Decision (Akhil, 2026-07-30): a case opens only after Layer-2 rules the finding a real
deviation.** Not on detection. This follows the existing precedent exactly —
`CaseRow.Detector.BEHAVIOR_DRIFT` is documented as "a behaviour-drift finding *that survived
triage*" — and it is what makes the no-alert-budget decision (§9) safe: findings stream
freely onto the Classifiers page, but Triage is the screen people get paged from and only sees the
triaged subset. So `detect()` returns findings whose `triage_verdict` is `sound`,
plus any a human marked `not_expected` directly.

**Amended (launch segment B, 2026-08-08): triage no longer requires a repository, and the
case says what it ruled with.** The original Layer 2 needed a git integration, a clone token, a
sandbox and a committed bundle; a partner who reaches us by pointing an OTel exporter at a URL has
none of them, so under the original decision their findings could never open a case at all.

There is now **one triage path and no repository at all**: a sandbox, the finding's claim as a two-file
dossier, the platform's read-only MCP surface for the traffic behind it, and a `checks/` directory the
agent writes its own scripts into. For a distribution shift that is a genuinely sufficient basis rather
than a degraded one: the workload block (§7) is what separates "the agent changed" from "the traffic
changed", and it is read off the traces, not off source. What triage rules on is the CLAIM — true,
sufficiently sampled, properly evidenced — so a duration or cost *drop* is as `sound` as a rise, and
whether the change was welcome is a question for the human who reads the case.

**The contract is "currently firing", not "newly fired".** `detect(projectId)` returns the full
live set every pass and `CaseReconciler` closes the cases whose detections dropped out. This is a
real design constraint, and it maps cleanly: a bucket whose current window has returned to its
pinned reference stops appearing, and its case auto-closes. Implement the live-set semantics
directly — do not emit a stream of new findings and expect reconciliation to work.

Fields to fill on `CaseDetection`:

- `title` — a sentence, not a metric: `"discover-sales-prospects turns are 1.4× slower"`.
- `basis` — why this crossed **its own** bar, in this detector's terms. The ranked list mixes
  detectors that share no threshold, so say "distribution shift against the window pinned at the
  last deploy, W₁ 0.34 on logs over 1,180 turns" rather than normalizing to someone else's scale.
- `onsetAt` — when the spell began. The close time of the first window that showed the shift,
  not when the sweep noticed.
- `severity` — 0..1, for ordering only, never rendered as a number.

### 8.2 Catalog

**Two** `ClassifierModelModule` entries in `BuiltInClassifierCatalog.MODULES` — `duration_drift`
and `cost_drift` — each with `detectorFactory = null` and dispatched by the new sweep, exactly how
behaviour drift is dispatched by `BehaviorDriftSweep` through the `TrajectoryDetector` seam rather
than through `BuiltInDetector`.

The measures under each module are declared in its `defaultConfigJson`, not as separate modules.
That is what makes one switch govern two grains, and it keeps `Grain` — which the sweep reads to
pick its candidate query — a property of the *measure* rather than of the module. `grainFor()`
returning one value per detector kind means the metric sweep cannot use it; it reads grain per
measure from its own config and does not consult the catalog's map.

Both seed **enabled**, as every built-in now does. They used to seed disabled off a
`Lifecycle.EXPERIMENTAL` marker; that field and `BuiltIn.seedsEnabled()` are gone, because "we have
not measured this" and "this org does not get this" were being expressed twice. The only thing
holding an unmeasured operating point back is now `duration_drift_enabled` / `cost_drift_enabled`,
targeted per org. **That is a console setting, not a code guarantee** — until §9's null case runs,
those flags being on for anyone but us is the risk.

Both modules satisfy the L1 cost model: **no per-observation API call.** These are arithmetic over
facts that already exist.

**`callSiteFactsRead()`: declare nothing, deliberately.** #654 added this seam to `BuiltInDetector`
for detectors that gate on a `call_site` column captured from the repo (`output_schema`, `shape`) —
a fact landing later rewinds the sweep cursor of exactly the signals that declare it, and a catalog
test asserts that a detector reading such a column without declaring it fails. Both read
`observation` columns and the trace spine only: the bucket key comes from `observation.call_site_id`
(an ingest fact, present from the first trace), and the model from `observation.model`. No
`call_site` fact is read, so the declared set is genuinely empty. State this in the module comment
so the next reader does not assume it was an oversight. The analogous hazard for these detectors is
the price book, handled in §3.3.

### 8.3 The Classifiers surface

DESIGN-DIRECTION §Classifiers already specifies this UI. Findings render as one row per cause with
exactly two verbs, and the detail rail carries a baseline changelog. **No new frontend surface is
needed.**

What actually shipped on main is simpler than the pre-merge draft: one file,
`frontend/src/views/classifiers/ClassifiersPage.tsx`, with no `adapt.ts` and no view-model layer.
The finding row renders `causeKey` in mono, then a literal ` — {causeKind}`, then the call site;
the chain string is assembled inline from `traceCount` / `adjudicationStatus` /
`recurrencesSinceVerdict`.

So a `distribution_shift` finding **renders with no frontend change at all**, reading as
`turn_duration:discover-sales-prospects:slower:pinned — distribution_shift`. Ugly, not broken. The
frontend follow-up is a small one: a label map for `causeKind` and a sentence for the shift causes
("turns are 1.4× slower since Jul 28"). Land the backend first and let the copy follow — nothing is
blocked on it.

---

## 9. The correction loop

`POST /findings/{id}/resolution` exists and today allowlists an n-gram or pins it in
quarantine. It needs a branch on `cause_kind`.

| Verb (UI label) | Action string | Drift causes (today) | `distribution_shift` (new) |
|---|---|---|---|
| **Legitimate — absorb** | `expected` | allowlists the gram permanently, skips the graduation wait | **re-pins the reference**: `pinned_sketch ← current`, stamp `pinned_at` / `pinned_by_version_id`, append a baseline-changelog row |
| **Real deviation** | `not_expected` | pins in quarantine so it never graduates and keeps firing | leaves the reference alone, marks for escalation. The reference must **not** move, or the next window silently normalizes the regression |

Both labels and both action strings already exist in `ClassifiersPage.tsx`; the branch is entirely
server-side.

**The triage agent may not re-pin by itself.** `BehaviorTriageVerdict` states that its
confidence is read "never as authority to mutate the baseline", and no feedback path from
triage to the profile exists. An `expected` ruling *surfaces* the absorb verb; a human
presses it. Automating that contradicts an existing deliberate constraint and needs its own
decision, not a quiet addition here.

**Alert budget: none, by decision.** Findings are written unbudgeted so thresholds can be tuned
against real firings rather than a guessed number. **The valve is a human instead.** Layer-2
triage costs a sandbox run — an agent in a microVM, with a repo clone when there is one — and no
sweep enqueues one: a shift is CHANGE, not a problem — slow is not bad and expensive is not bad — so
the sweep writes the finding and stops. A person presses **Run analysis** on the classifier's rail
(`POST /findings/{id}/analysis`), and `escalated_at` keeps that to once per cause.

The one way that valve opens without a person is `triage_automatic_enabled`, off for every org
until a targeting rule says otherwise. `TriageAutoEscalator` then presses the same button on a
slow tick under three bounds — a recurrence bar, a rolling per-project budget, and a per-tick cap —
each of which logs what it withheld. It calls `BehaviorDriftService.analyze` rather than enqueueing,
so automatic mode is the manual path pressed by a scheduler and cannot drift away from it.

`max_escalations_per_sweep` is gone with the automatic path: a per-sweep ceiling on microVMs only
means something when a sweep can spend them, and a click is its own rate limit. This is the lesson
`tool_error` was deleted over, applied one layer further out — that detector enqueued a grader run
per failing span for a fact already sitting in a column.

---

## 10. Schema

Forward-only. Never edit an applied changeset to remove schema — add a DROP migration instead.

```sql
-- 00NN-metric-baseline.sql
CREATE TABLE metric_baseline (
    id                   text PRIMARY KEY,
    project_id           text NOT NULL,
    classifier_id            text NOT NULL,   -- FK classifier(id)
    measure              text NOT NULL,   -- turn_duration | tool_duration | cost
                                          -- | tok_input | tok_output | tok_cache_read | tok_cache_write
    bucket_kind          text NOT NULL,   -- call_site | tool
    bucket_key           text NOT NULL,
    environment_id       text,
    state                text NOT NULL,   -- learning | armed | stale

    pinned_sketch_json   text,            -- reference pinned after last deploy
    pinned_at            text,
    pinned_by_version_id text,
    prev_sketch_json     text,            -- RETIRED: held the previously closed window; see control_json
    current_sketch_json  text,            -- window being filled
    control_json         text,            -- rolling control: one slot per UTC day, 21 days, §4.3

    current_opened_at    text,
    current_count        bigint NOT NULL DEFAULT 0,
    counted_through_at   text,
    counted_through_id   text,
    last_event_at        text,
    created_at           text NOT NULL,
    updated_at           text NOT NULL
);

CREATE UNIQUE INDEX metric_baseline_scope
  ON metric_baseline (project_id, classifier_id, measure, bucket_kind, bucket_key,
                      COALESCE(environment_id, ''));

-- 00NN-finding-distribution-shift.sql
-- behavior_finding.cause_kind gains 'distribution_shift'. If a CHECK constraint enumerates
-- the kinds, this is a DROP + re-ADD in a forward changeset.
```

**Sketch payload**: a t-digest or fixed log-spaced histogram over `log(value)`. It must be
mergeable and bounded — a raw sample list grows without limit across a month-long window on a
thin bucket.

Per the documentation policy, a schema change updates `docs/reference/data-model.md` in the
same PR, and controller/DTO changes regenerate the OpenAPI spec.

---

## 11. Build-out order (PR-sized)

1. `MetricSketch` + its `W₁` — pure, no Spring, fully unit-testable. Land with the null-case test
   from §12 before anything touches a database.
2. `MetricSource` — the column-preferred accessor of §3.0, with the derivation behind it. Its test
   must cover the null-column path against a fixture that leaves the rollups unset, because that is
   production today and fixtures otherwise hide it.
3. `metric_baseline` migration, `MetricBaselineRow`, `MetricBaselineRepository` (lease + keyset
   upsert, mirroring `BehaviorProfileRepository`).
4. `MetricDriftSweep` + `MetricDriftConfig` + `MetricDriftDetector` (the pure compare-and-decide
   step, so the eval can drive it without a database). `turn_duration` measure only.
5. The `duration_drift` catalog manifest; `DISTRIBUTION_SHIFT` cause; the `resolve(...)` branch and
   the baseline-changelog write.
6. `tool_duration` measure + the §6.1 suppression rule (the first point at which one classifier
   spans two grains, so it is the first point the rule can be tested).
7. `cost_drift`: the manifest, the `TokenUsage.nonOverlapping()` path, cache-write abstention, and
   the token buckets as evidence rather than findings.
8. `MetricDriftSource implements CaseSource` + the `CaseRow.Detector` constant, gated on
   triage per §8.1.
9. Unattributed-coverage number in `vitals/`.

---

## 12. Evaluation — a number without labels

There is no gold set for "this distribution moved for a bad reason" and there cannot be one, so
the eval is synthetic injection against replayed real traffic — behaviour drift's approach.

1. **Injection.** Multiply a bucket's durations by 1.2 / 1.5 / 2.0; collapse a cache-read ratio;
   inject a retry loop. Report detection rate per operator at a fixed alert volume, plus the lag
   in windows before firing.
2. **The null case.** Unmodified traffic, split in half. Everything that fires is a false
   positive, and that number sets the `W₁` floor. This is the run that says whether "no alert
   budget" is livable; if it is not, the finding is that the floor was too low, not that the
   decision was wrong.
3. **Replay a real deploy.** Take a known past regression from production traces, confirm it
   fires, and confirm `since_version_id` lands on the right deploy.
4. **Gate.** `task check -- classifier` before commit; CI runs the full gate on every PR.

---

## 13. What will actually bite you

- **Reading the rollup columns without the fallback.** They are unpopulated today, so a detector
  that reads them bare abstains on everything and never fires — and it looks correct in every unit
  test, because fixtures populate what ingestion does not. Go through `MetricSource`. §3.0.
- **Reading the raw `usage` blob.** §3.3. The key names lie about their own semantics across
  providers, and the failure is silent mispricing rather than an exception.
- **Treating an unpriced model as free.** Makes a price-book gap look like a cost improvement — and
  the abstention that avoids it strands that history behind a forward-only cursor. §3.3.
- **Applying the settle window to duration.** It is not needed there (the root span's arrival is the
  completion signal) and it delays every duration finding for nothing. Omitting it from *cost* is
  the opposite error and produces a permanent drift toward cheaper whenever ingest lags. §5.
- **Cutting windows on ingest time.** A backfill collapses a month into minutes, so one window
  swallows the corpus and compares it against nothing. §5.
- **Duration as `max(end) - min(start)`.** Inflated p95 by ~10% against production, because async
  children outlive their parent. §3.0.
- **Dropping traces whose root span never ended.** Silently removes the population a duration
  detector most wants. Count them. §3.0.
- **Bucketing on model or version.** Silences the detector through exactly the events it exists
  to catch. §2.2.
- **Regressing on span count.** Explains the bug away. §3.2.
- **Emitting new findings instead of the live set.** `CaseSource` reconciliation depends on
  `detect()` being complete every pass; a delta-only source leaves every case it opens to be
  closed by hand. §8.1.
- **Letting the triage agent re-pin.** Contradicts an explicit existing constraint. §9.
