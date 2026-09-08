# The tool-error program — a failure rate that moved, explained by pattern

Status: **spec.** Agreed 2026-08-08 between Akhil and Claude.

Sibling of `classifiers/metric_drift/PROGRAM.md`, which is the structural precedent for everything
here: per-bucket windows, two references, findings-not-firings, effect size rather than significance,
and a case only after Layer-2. Where this document says "as metric drift does", it means the same
code, not the same idea — the window state lives in the same table and the findings in the same one.

Base: `launch` at `de86fad7`.

---

## 0. What this is not

- **Not the classifier that was deleted.** `tool_error` shipped once and migration `0030` removed it
  outright rather than disabling it. It wrote a detection **per failing observation**, and every
  detection enqueued a grader run, so one failing tool re-ran a call site's entire grader set, per
  failing span, for a fact already sitting in a column. That is the mistake this program is shaped
  around, and the shape it settles on is: **a rate over a window, never a per-failing-call event.**
- **Not a list of errors.** The product does not show every failing tool call. It shows that a rate
  moved, and which *pattern* of failure moved it. A list of 412 individual timeouts is the raw
  material, not the finding.
- **Not an analytics surface.** There is no tool-error number anywhere else any more — see §8.4.

---

## 1. What counts as a failure

The program needs a definition **broader than the recorded error type**, and this is it.

Today a tool call fails if and only if `tool_call.error_type IS NOT NULL`, which `StructuralEnricher`
writes from one thing: the OTLP span status being `ERROR`. That is far too narrow for an agent
product. The most common real tool failure never touches span status at all — a framework catches the
exception, hands `{"error": "no such customer"}` back to the model, and closes the span cleanly. The
agent then reasons from a failure the platform cannot see.

> **A tool call failed if any of these is true.**
>
> | # | Source | Read from | Why it is here |
> |---|---|---|---|
> | 1 | the span reported an error status | `tool_call.error_type IS NOT NULL` or `is_error IS TRUE` | today's rule, kept whole |
> | 2 | the span carries an OTel `error.type` | `observation.attributes ->> 'error.type'` | semconv makes this *the* error signal; status may be unset. Our own SDK samples emit it and ingest throws it away |
> | 3 | an exception was recorded on the span | `observation.attributes ->> 'exception.type'` | the exporter that records rather than rethrows |
> | 4 | **the result declares itself an error** | `tool_call.result @> '{"isError": true}'`, or a non-null top-level `error` key | MCP's own error envelope, and the near-universal convention beside it |
>
> **Not a failure:** an empty result, a slow call, a retry that later succeeded, or any output whose
> *text* merely mentions the word "error".

That last line is load-bearing. Every rule above is a **structural key check** — a column, an
attribute name, a JSON key. None of them reads prose. The moment a definition starts matching text it
stops being deterministic, stops being cheap, and starts needing a jury, which is the L1 cost model
this classifier exists inside.

**Rules 2 and 3 have a known blind spot.** OTel records exceptions as span *events*, and
`OtlpSpanMapper` reads only `gen_ai.evaluation.result` events — every other event is dropped before
anything is persisted. So rule 3 catches `exception.type` only where a sender set it as an
*attribute*. Widening this means an ingest change and a schema for span events; it is recorded here
as a gap rather than papered over, because a definition whose limits are unwritten reads as complete.

### 1.1 The definition is read at query time, once

`ToolFailure.SQL_PREDICATE` is a single constant, interpolated by every reader. Not materialized into
a column at ingest, and that is a decision rather than laziness:

- **History stays consistent.** 55k tool calls already exist, written under the narrow rule. A
  broadened ingest path plus a backfill expresses the rule twice — once in Java, once in the
  migration's SQL — and the two can disagree. Worse, if the backfill is wrong or skipped, the rate
  *steps at the deploy boundary*, which is indistinguishable from the regression this detector is
  built to catch.
- **One expression cannot drift from itself.** There is no second definition to keep in step.

The cost is a jsonb check on the window being scanned. The sweep reads a bounded page (≤500 traces),
so this is not a table scan; the predicate is ordered cheapest-first and Postgres short-circuits `OR`.
If volume later makes it hurt, materializing it is a pure optimization with the rule already written
down in exactly one place — the ordering that makes that safe is the whole point.

---

## 2. Error patterns — grouping, and why it needs a signature

`error_type` is a **free-text status message**, not an enum. Production carries things like
`HTTP 500 upstream`, `timeout`, and whatever a status message happened to say. Grouping "similar"
errors therefore cannot be a `GROUP BY error_type` — every distinct id, port, retry count and URL in a
message splits one failure mode into hundreds of singletons.

So each failure gets a **signature**: a deterministic normalization of its message that erases the
parts that vary per call and keeps the parts that name the failure.

```
"HTTP 500 upstream from https://api.stripe.com/v1/charges/ch_3Ox9aB"
    -> "http <num> upstream from <url>"

"connection reset by peer at 10.2.3.4:5432"
    -> "connection reset by peer at <ip>"

"Tool 'search_docs' failed after 30014ms (attempt 3/3)"
    -> "tool <str> failed after <num>ms (attempt <num>/<num>)"
```

The normalization, in order, is: lowercase; replace URLs, IP/host:port pairs, UUIDs, hex runs of 8+,
quoted strings, and bare integer/decimal runs with their placeholder; collapse whitespace; truncate.
Placeholders are literal (`<url>`, `<ip>`, `<uuid>`, `<hex>`, `<str>`, `<num>`) so the signature reads
as a sentence rather than a hash — it is rendered verbatim on the Classifiers page, exactly as
`cause_key` is.

**A signature is evidence, never a bucket.** See §3.2. And it is deliberately not clustering: no
edit distance, no embedding, no learned template. Two messages group if and only if they normalize to
the same string. That is reproducible from the message alone, explainable to a partner in one
sentence, and costs nothing — where a similarity threshold would be a second unmeasured dial on top
of the one this classifier already cannot justify.

Rules 2–4 of §1 have no message. They signature as their own source: `error.type`'s value,
`exception.type`'s value, and `result.error` respectively.

---

## 3. Buckets

### 3.1 The key

**The bucket is the tool**, keyed by `ActionSymbol.of(kind, name, false)` — the same alphabet
`tool_duration` buckets on, so a tool-error finding and a tool-duration finding name the same thing
and a reader can hold both at once. `environment_id` is in the scope for the reason metric drift puts
it there: dev fails differently, permanently.

`isError` is passed **false** into `ActionSymbol.of` deliberately, exactly as `MetricSource.toolMetrics`
mints it. A tool's failures must stay in the same population as its successes — that population *is*
the denominator. Keying failures into their own symbol would make the rate uncomputable.

### 3.2 Why the signature is not in the key

PROGRAM.md §2.2's rule applies unchanged: **anything you want to detect a change in must not be in the
bucket key.** Two reasons it binds especially hard here:

1. A brand-new failure mode has **no history**. Keyed on `(tool, signature)`, its rate goes from
   nothing to something with no reference to compare against, so the detector is silent through the
   most alarming event it could witness. Keyed on the tool, that same event moves the tool's rate and
   fires.
2. Buckets thin out fast. A tool with six failure patterns needs six times the traffic to arm any of
   them, and `min_sample` is already the binding constraint on a rate.

So: **the rate moves per tool; the patterns explain which failures moved it.** One finding per tool,
with a ranked pattern breakdown inside it.

### 3.3 Thin buckets wait, they are not skipped

As metric drift: a bucket below `min_sample` keeps accumulating on the elapsed-event-time clock rather
than being dropped. A tool called thirty times a week gets watched on a slower clock.

---

## 4. The statistic

**Decision (Akhil, 2026-08-08): a Bernoulli CUSUM, gated on effect size.** Two statistics doing two
different jobs, and neither is redundant.

### 4.1 Why sequential rather than two windows compared

An earlier draft of this program closed fixed windows and compared them on effect size, mirroring
metric drift. That is wrong for a rate, and the reason is structural rather than statistical: **a
window is a boundary, and a boundary both delays and dilutes.** At a window of two thousand calls a
tool called two hundred times a day is judged every ten days; and a regression beginning mid-window is
averaged against its own healthy first half, so the first window after a break is the one *least*
likely to show it.

A CUSUM has no boundary. It accumulates evidence call by call and alarms as soon as there is enough,
which for a sustained doubling is one to two thousand calls whenever they arrive. It also knows **when
the shift began** — the last moment the accumulator sat at zero — which is what a case's onset should
say and what a window scheme can only approximate to its own resolution.

The precedent was `trend/CusumDetector`, which ran a CUSUM on grader pass rate — the same shape
of quantity. Metric drift's §4.5 rejects CUSUM, and that argument does not reach this classifier: it
turns on duration and cost being heavy-tailed, where a mean-shift test misses a p95 move that leaves
the median still. A proportion has no tail to miss.

### 4.2 The score

Each call contributes the log-likelihood ratio of the shifted rate against the in-control one:

```
failure -> ln(p1 / p0)                positive, large when failures are rare in control
success -> ln((1 - p1) / (1 - p0))    negative, small
S = max(0, S + score);  alarm when S >= h
```

`p0` is the pinned in-control rate, **Jeffreys-smoothed** as `(failures + 0.5)/(calls + 1)`. The
smoothing is load-bearing rather than tidy: a tool that has never failed has a raw rate of exactly
zero and `ln(p1/0)` is not a number, so the unsmoothed estimator makes the single most alarming case
in the product the one case the detector cannot score.

`p1 = max(2·p0, p0 + 0.5pp)`. The floor is what makes a clean tool watchable — twice nearly-zero is
still nearly-zero, so a purely multiplicative target tunes the detector for a shift too small to tell
from silence.

### 4.3 The operating point, and where it came from

**Decision (2026-08-11): the dial is the false-alarm budget, and `h` is derived from it per tool.**
`arl_target = 250,000` calls between false alarms. Solved by `arl.py`: Brook–Evans on a refined
integer lattice, exact to the arithmetic.

| in-control p₀ | tuned for p₁ | exact h | ARL₀ | lag at p₁ | **ARL₀ at a flat 6.0** |
|---|---|---|---|---|---|
| 0.1% | 0.6% | 6.13 | 250,299 | 1,005 | 220,246 |
| 0.5% | 1.0% | 5.79 | 250,742 | 2,569 | 308,498 |
| 1.0% | 2.0% | 6.38 | 252,385 | 1,455 | 171,778 |
| 5.0% | 10.0% | 8.05 | 250,176 | 356 | 31,700 |
| 10.0% | 20.0% | 8.93 | 252,189 | 184 | 13,792 |
| 20.0% | 40.0% | 9.56 | 257,680 | 87 | **6,936** |

**The previous version of this table said `h` is "remarkably flat in the base rate" and shipped 6.0
for everything. Read the last column.** That claim came from a table that stopped at 5%, and it is
not flat, it is a slope: a flat 6.0 spans 6,936 to 308,498, a factor of 44. Every tool noisier than
about 1% was being watched with a threshold several times too low, and §4.4 was written to mop up the
resulting alarms.

The line is `h = 11.42 + 1.088·ln(p₀)`, clamped to `[6, 12]`, which holds the realised ARL₀ between
220k and 309k — a factor of 1.4. Below ~0.5% the clamp takes over, and not as a fudge: down there the
shift *floor* rather than the multiple sets `p₁`, so the relationship changes shape and the exact
thresholds flatten out at 5.8 to 6.1. One number is the honest description of a flat stretch.

The budget itself needs no correction term. Solved exactly, ARL₀ rises by 0.99 to 1.02 in the log per
unit of `h` across the whole range, so `arl_target` moves the threshold by its log.

**It is still not a measured operating point.** Every figure above assumes independent Bernoulli
trials, and real tool failures are bursty: one upstream outage fails two hundred consecutive calls.
Autocorrelation inflates the false-alarm rate by an amount nobody has measured. So this is a
defensible starting point, the classifier seeds disabled because of it, and §12's null run is what
replaces it. Expect that run to push the constants up rather than down.

> **A note on how the earlier numbers were wrong**, because the shape of the error recurs. The old
> lattice took the *success* step as its unit, so a failure was `round(wf/ws)` steps. That is
> essentially exact when failures are rare — at 0.1% a failure is ~350 success steps — and badly
> wrong when they are not: at 20% a failure is 2.41 steps, rounds to 2, and the solved threshold
> comes out 2.7 too low. The chain was still exact; it was exact for weights that were not the
> detector's. `arl.py` now refines the unit until a failure spans at least sixty steps.

### 4.4 The magnitude gate, and why a CUSUM alone is not enough

> A CUSUM is a **sequential** test. It accumulates indefinitely, so given enough calls it detects
> *any* sustained deviation, however small.

That is the right property for "is this still the same process" and the wrong one for "does anybody
need to know". On a tool called two hundred thousand times a week it eventually crosses on a tenth of
a percentage point — **which is the failure mode this program rejects a z-test over (§4.5), arrived at
by a different route.** Discovering this is what added the gate: the first version of
`ToolErrorDetectorTest` asserted that a sustained 20.0% → 21.1% stays quiet, and a bare CUSUM failed
it, crossing after about three thousand calls.

So an alarm required **both**: the accumulator crossing `h`, and Cohen's h on the move clearing
`min_effect_size` at 0.05.

**Decision (2026-08-11): the gate is deleted. It was covering for §4.3's miscalibration, and it cost
the detector every real outage.**

The gate measured the shift with `State.rateSincePin()` — failures over every call *since the
reference was pinned*, not over the burst. On any tool with history that ratio is the baseline by
construction. A 5% tool going to 80% read as **5.001%**, an effect size of 0.00004, and was silenced.
It took 15,512 calls of sustained outage before enough failures accumulated to move a
million-call denominator far enough to clear 0.05. The denominator is capped by the replay window, so
the required damage is a fixed ~1.7% of the window and the call rate cancels: **time-to-fire was
about 11 hours regardless of throughput, and a busier tool simply burned more failed calls in the
same 11 hours.** Sustained shifts fared worse — 1% → 2%, the shift the detector is explicitly tuned
for, never fired at all.

The leak the gate existed to plug is real, and it is now plugged upstream where it belongs. The
worked case is still 20.0% → 21.1%: a flat 6.0 gave that tool an ARL₀ of 6,936, so the wobble was
riding a threshold forty-four times too low. At the derived 9.56 the accumulator's own break-even
rate does the work — a sustained rate below it drifts the statistic down and only an excursion
reaches the bar. That is the CUSUM's slack doing what slack is for, rather than a second test
apologising for the first.

**Tolerated, not impossible**, and that is the honest cost of the change. A sequential test can make
a real-but-small shift rare; it cannot make it never. A genuine sustained 21.1% on a 20% tool now
alarms roughly every 63,000 calls rather than never — about four times the healthy rate, against a
250,000-call budget. The old behaviour bought "never" by being deaf to 20% → 80% as well.

Cohen's h survives as a **reported** number on a finding and nothing branches on it. §7's blob still
carries it, because it remains the honest way to say how big a move was on a scale that behaves at
both ends of the range.

### 4.5 Not a z-test

The Vitals surface flagged tool errors with a two-proportion pooled z-test at 2.576. That surface is
gone (§8.4) and the test does not come with it. Significance inflates with sample size while effect
size does not, so on a busy tool it reports a 0.05pp move as real. §4.4 is the same hazard in the
CUSUM's own terms, and a threshold calibrated to the tool's own base rate is the answer to both.

### 4.6 One reference, both directions

Metric drift compares against two references because each is blind in one direction alone. A CUSUM
against the pinned in-control rate needs only that one: a slow boil accumulates just as a sudden break
does, only more slowly, so there is nothing for a previous-window reference to catch that this misses.

Falling failure rates alarm on the same bar, because a tool that stopped reporting errors has either
been fixed or stopped reporting. The improvement arm runs only above a 1% in-control rate — below
that there is nothing to lose, and a halving of it is undetectable at any sane run length.

---

## 5. Lifecycle — carried, with the cursor bug class taken back on purpose

> **Decision (2026-08-11) supersedes the 2026-08-08 decision below.** The accumulator is now carried
> between sweeps in `tool_error_state` (migration 0070). The original argument is kept in full because
> it is still correct about what it was arguing, and because what changed was the premises, not the
> reasoning.
>
> Three things changed them. **The accumulator is no longer capped** — it was clamped to `3h` so a
> fixed tool's case would drain and close itself in about a day, and that ceiling also pinned every
> serious outage to the same number, making §6's ranking a flat tie across every real problem. **Cases
> now stay open until a human closes them**, so nothing needs the draining behaviour the cap bought.
> **Closing or absorbing resets the accumulator**, and a reset has nowhere to live in a statistic that
> is rebuilt from scratch on the next read.
>
> The bug class comes back with the state, so it is guarded rather than hoped away: a `watermark_bucket`
> that a sweep folds strictly after, a `state_epoch` that forces a rebuild rather than a resume when
> the tuning moves, and a rebuild path kept exercised by tests. `ToolErrorTrend` stays pure — the
> carried state is a parameter and a return value, never a query.
>
> One latent bug fell out of the change. The reference was described here as *frozen, not sliding*,
> and it was frozen within a pass and re-learned on the next one from the leading buckets of a window
> that slides forward every hour. So it did slide, in hourly steps, and a tool degrading over weeks was
> being measured against a reference walking after it. It is now learned once and stored, which is what
> this section always meant.

**Decision (Akhil, 2026-08-08): no cursor and no watermark. The replay is recomputed from source on
every pass.** Superseded in part (2026-08-14): the recompute is no longer driven by a READ.

It used to run inside `ToolErrorCaseSource.detect()`, which made this the one classifier whose
findings advanced only while somebody had Triage open — a read path that wrote, a Classifiers rail
showing whatever the last case pass happened to leave, and no way to ask a project what its
tool-error findings were without changing them. `ToolErrorSweep` now drives it from
`ClassifierWorker`, on the same cadence, lease and dead-letter budget as every other classifier.

What the original decision was actually about is untouched: one `date_trunc('hour', ...)` GROUP BY
over a trailing window, replayed in memory, no cursor and no watermark. The sweep marks its job swept
with a NULL cursor because there is no position to remember, and idempotence comes from the recompute
assigning counts rather than adding them.

That works here for a specific reason worth stating, because it does **not** generalize to the other
classifiers: **the state is re-derivable from a cheap aggregate.** A failure rate is counts per hour.
Behaviour drift's fitted n-gram model is not, and metric drift's sketch is a running summary rather
than a replayable stream — both of those genuinely need a sweep. A rate does not, so it does not get
one.

What this buys: the whole class of double-count bugs disappears, because there is nothing to
double-count. It also makes the operating point live-tunable, which matters a great deal for a number
§9 openly calls a guess.

**Amended 2026-08-08: one row of state, and only one.** "No stored state" was taken literally and it
broke the correction loop. *Legitimate — absorb* had nowhere to write what a human accepted, so the next
recompute re-learned the same reference off the same leading buckets, re-alarmed, and wrote the finding
straight back — the button closed a row and changed nothing at all. `tool_error_reference` (migration
`0050`) holds the counts a human pinned, and `ToolErrorTrend.replay` uses them as the in-control
reference and resumes from the moment they were accepted.

Three things keep this from being the sweep coming back through the side door. It is written **only by a
human press**, never by the replay, so no pass can disagree with another about it. It holds **counts, not
position** — no cursor, no watermark, nothing that says how far a reader got — so the double-count class
above stays deleted; running the replay a hundred times still leaves identical rows. And it is a
**reference, not a suppression**: a tool accepted at 8% is compared against 8% and still alarms at 30%.
This is the rate's analogue of `metric_baseline`'s re-pin, which is likewise not an allowlist entry, and
`behavior_allowlist`'s un-widened cause CHECK (§`0049`) still correctly refuses `rate_shift`.

### 5.1 The three rules recompute imposes

Recompute does not remove the double-counting hazard. It **relocates** it, from "did I read this row
twice" to "did I write a number by adding". That is a much easier thing to get right — it is visible
in one function instead of spread across a cursor, a watermark and a transaction — but only if these
hold.

1. **Every persisted number is assigned, never incremented.** Metric drift's sweep *adds* to a
   finding's `trace_count` as windows close. Doing that here would make the count "how many times the
   pass ran". The upsert sets counts from the freshly recomputed aggregate. Run it a hundred times,
   get the same row.
2. **Observations refresh; judgements do not.** A finding that has been triaged, or that a human
   marked *Real deviation*, keeps `triage_verdict`, `triage_action`, `triage_summary`, `triaged_at`,
   `escalated_at` and `human_verdict_at` when the next pass rewrites its counts and evidence. Only
   what was computed gets overwritten.
3. **Onset is written once and then left alone** while the spell is unbroken. Under recompute it can
   drift by an hour or two as the replay window slides and the baseline shifts with it, and
   `GraderDegradationSource` recorded what that costs (deleted with grader Layer 1, but the lesson
   stands): an advancing onset reads to `CaseLedger` as a *fresh spell* and reopens a case a human
   just closed.

### 5.2 The accumulator is capped, or a fix takes weeks to register

An uncapped CUSUM **recovers in proportion to the damage, not to the fix.** Evidence piles up for as
long as a tool is broken, so a four-thousand-call outage at five times the normal rate drives the
accumulator to around a hundred, and draining that back under the threshold at the in-control rate
takes some thirty thousand calls. The tool is fixed on Wednesday and the case is still open a fortnight
later, with a longer outage worse without limit.

So an arm saturates at **three times the decision interval**. From saturation it drains in a few
thousand in-control calls however long the spell ran, which puts "it is fixed now" about a day away
rather than weeks. Three times keeps a genuinely broken tool comfortably above the bar while it is
still broken, so this never shortens a detection or hides an ongoing problem.

Not a config dial: it changes how fast a case **closes**, not what counts as a detection, and it cannot
affect §4.3's run lengths because the cap is only reachable well past the point of alarming.

Found by the test asserting that a recovered tool drops out of the live set. It failed, and this was why.

### 5.3 The window horizon, and the one thing it cannot see

A trailing replay cannot see a spell older than itself: it scrolls out, the elevated rate becomes the
new baseline, and the finding vanishes — so the case closes itself as recovered when nothing
recovered. This is the "broken window becomes its own baseline" failure that CUSUM fixes *within* a
replay and that returns at the replay's edge.

The grader detector accepts this at 28 days. **This one does not:** once a spell is open, the replay
anchors to that spell's own onset rather than to `now − 28d`, so an unresolved regression cannot
quietly age out. The horizon still bounds the query for every tool that is behaving.

---

## 6. Findings, not firings — and exactly one of each

One `behavior_finding` row per **cause**, reusing the table metric drift reuses:

```
cause_key = tool_error_rate:<bucket_key>:<direction>
```

No `<reference>` segment: a CUSUM has one reference (§4.6), so a segment that can only ever hold one
value would be noise in a string a human reads.

New cause kind `rate_shift`, beside `distribution_shift`. Separate rather than reused because the two
route differently: `MetricDriftSource` claims every live `distribution_shift` and files it as a
`metric_drift` case, and a tool-error case labelled `metric_drift` is a Triage row that lies about what
it is. `resolveShift` keys on `baseline_id` rather than on the cause kind, so *absorb* and *real
deviation* work on these rows unchanged.

### 6.1 What stops the same degradation becoming two of anything

**Identity is enforced by unique indexes, not by careful code.** That is the whole design: every rule
below is a constraint the database holds, so a bug upstream produces a failed write rather than a
duplicate case.

| Level | Guarantee | Enforced by |
|---|---|---|
| finding | one live row per cause | `ux_behavior_finding_baseline_cause` on `(baseline_id, cause_kind, cause_key, workflow_key) WHERE status IN ('open','blocked')` |
| case | one live case per subject | `ux_eval_case_live`, via `CaseKey(detector, subjectKind, subjectId, metric)`; `CaseLedger.open` looks for a live case on the key first |
| reopen | a closed case reopens only if the onset moved **past** the one it recorded | `CaseLedger.isNewSpell` |
| verdict | one row to write on, and `escalated_at` keeps escalation to once per cause | the finding row itself |

The case key is `("tool_error", "tool", <bucket_key>, "tool_error_rate")`.

**Direction is deliberately not in the case key.** It stays in the finding's `cause_key`, because a
rise and a fall are different causes to explain — but if it reached the `CaseKey`, a tool whose rate
rose (case opens) and later fell back would alarm on the down arm under a different key and open a
*second* case celebrating the recovery of the first. Both arms therefore collapse onto one live case
per tool, and **while a spell is open the opposite arm is read as recovery rather than as a finding.**

**Human verdict beats machine verdict.** Both land on the same row, so they cannot produce two cases;
they can still disagree, and a person who has ruled on a finding is not overruled by a later
triage of it.

### 6.2 Known, not fixed: cross-classifier overlap

A tool that starts failing frequently often gets *faster* at the same time, because a failure returns
early — so `duration_drift` can open its own case on the same tool for the same incident. Different
detector means a different `CaseKey`, so nothing dedupes them, and a partner sees one problem twice.

Metric drift solved the analogous turn-versus-tool overlap with suppression (its §6.1), but that rule
lives inside one sweep and has no cross-classifier equivalent. Building one means a shared notion of
"these two findings are the same incident", which is a larger design than this segment, and doing it
badly would suppress genuinely independent findings. **Recorded as a known duplicate rather than
built.** The environment scope is the deliberate opposite: dev and prod stay separate subjects,
because dev fails differently and permanently.

---

## 7. Evidence the finding carries

```json
{
  "measure": "tool_error_rate",
  "bucket": { "kind": "tool", "key": "tool:search_docs" },
  "reference": "pinned",
  "statistic": 128.4,
  "threshold": 8.05,
  "criticality": 48.6,
  "effect_size": 0.31,
  "delta_pp": 2.7,
  "counts_basis": "onset",
  "rate": { "ref": 0.004, "cur": 0.031 },
  "n_ref": 4210, "n_cur": 1180,
  "failures": { "ref": 17, "cur": 37 },
  "patterns": [
    { "signature": "http <num> upstream from <url>", "ref": 2, "cur": 412, "source": "span_status" },
    { "signature": "timeout after <num>ms",          "ref": 32, "cur": 38, "source": "span_status" },
    { "signature": "connection reset by peer at <ip>", "ref": 2, "cur": 11, "source": "result_error" }
  ],
  "call_sites": { "discover-sales-prospects": 380, "answer-faq": 43 },
  "since_version_id": "pv_01J...",
  "window": { "opened_at": "...", "closed_at": "...", "kind": "count" }
}
```

**`n_cur` and `failures.cur` span the run since onset, not the tool's history**, and `counts_basis`
says so explicitly rather than leaving a reader to assume. The distinction is not cosmetic: absorbing
a finding pins these as the tool's new in-control reference. Blobs written before 2026-08-11 carry the
counts since the reference was *pinned* under the same keys, so a reader that guessed wrong would
absorb a lifetime average as the normal for a tool that is on fire. Absorption refuses a blob without
the marker; such a finding is superseded by a recomputed one within a sweep, so refusing costs a wait
rather than the verb.

`criticality` is `10·ln(S)` — the ranking weight and the badge a reader sees. It is deliberately
**not** normalised by `threshold`, so a case opens near 18–25 rather than at a common zero and the same
incident scores differently on a clean tool than a noisy one. It blends severity, duration and traffic,
which is the right shape for "how much has this cost me" and the wrong shape for anything else; the
per-call question is what `effect_size` answers. `statistic` is uncapped and keeps growing while the
spell runs, which is what lets criticality separate a catastrophic outage from a mild drift — the old
`3h` ceiling pinned both to the same number within a day.

`patterns` is ranked by `cur − ref` and **capped**; the cap is stated in the blob when it bites, so a
reader never mistakes a truncated list for the whole story. Counts are carried for both windows
because the interesting pattern is the one that *changed*, not the one that is largest — a tool whose
timeouts held steady while its 5xx went up has one cause, and the ranking has to say which.

`call_sites` is the entry points whose traffic this window's failures came in through. Layer-2 needs
it for the same reason metric drift's `workload` block exists: the triage agent reads a repo, and the
repo cannot say that a caller started hitting the tool differently this week.

---

## 8. Platform integration

### 8.1 Catalog

One `ClassifierModelModule`, key `tool_error`, `Capability.TOOL_ERROR` (already declared, defaulted
on, waiting for this), `detectorFactory = null`, `Lifecycle.EXPERIMENTAL`, and **seeded disabled** for
the reason both metric classifiers are: §9.

**`Grain.WINDOW`, dispatched to `ToolErrorSweep`** — the scored unit is a stretch of one tool's
traffic tested against that tool's own earlier stretch, which is what `Grain` describes. The
`ClassifierWorker` WINDOW branch splits on the detector kind and peels this off ahead of
`MetricDriftSweep`, the same way SOP conformance is peeled off to `ConformanceSweep`; falling through
to the metric sweep instead is what once cost a duplicate set of metric baselines and a duplicate
finding per drift. This was `Grain.NONE` for as long as the recompute lived in the case source.

`callSiteFactsRead()` is empty and deliberately so. This reads `tool_call`, `observation.attributes`
and the trace spine — no `call_site` column captured from a repository, so no late-arriving fact can
invalidate it, and there is no cursor for one to rewind.

**It satisfies the L1 cost model: no per-observation API call, and no grader run, ever.** The deleted
version's fatal property was that a detection enqueued one. This writes at most one finding per tool
and nothing else.

### 8.2 The case gate

`ToolErrorCaseSource implements CaseSource`, `CaseRow.Detector.TOOL_ERROR`, `detect()` returning the
**live set** of `rate_shift` findings whose triage ruled deviation or which a human marked
*Real deviation* — the same gate `MetricDriftSource` applies, so every Triage row still means "this
survived Layer-2". A bucket whose rate returns to its reference drops out of the live
set and its case auto-closes.

### 8.3 What escalates, and when

Nothing automatically. A human presses *Run analysis*, as `87942500` made true of every Layer-2
escalation. There is no per-sweep escalation cap here and there must not be one — a cap on a path
nobody can trigger is the dead dial `de86fad7` just finished removing from the metric blobs.

### 8.4 Vitals loses its tool-error number

**Decision (Akhil, 2026-08-08): the tool-error statistic leaves the Vitals surface entirely** — the
query, the aggregate, the DTO field, the threshold, the stat card, the table column, the Triage pulse
strip. Tool errors are a classifier now, and they are shown on the Classifiers page.

This is what satisfies the requirement that the Vitals tool-error number and the detector cannot
disagree, and it satisfies it more completely than sharing a predicate would have: **there is one
number, in one place.** Two surfaces that agree today are two surfaces that can be edited apart
tomorrow. It also removes the last reader of the narrow §1 definition, so nothing in the product
disagrees about what a failed tool call is either.

It is a breaking change to `GET .../vitals`, whose response loses `tool_errors`. Acceptable at
design-partner scale, and recorded here so it is a decision rather than a surprise.

---

## 9. The operating point

**Unset.** `decision_interval = 6.0` is derived (§4.3) rather than guessed, but derived under an
assumption — independent Bernoulli trials — that real tool failures violate. Failures are bursty: one
upstream outage fails two hundred consecutive calls, and autocorrelation inflates the false-alarm rate
by an amount arithmetic cannot price. §12's null run against a real corpus is what settles it, and it
will very likely move the number **up**.

Metric drift's `w1_floor`, once measured, says nothing about this one. Different scale, different
measure, different run.

### 9.1 It ships on anyway — decision and residual risk

An earlier draft of this section said the classifier seeds **disabled**, as both metric classifiers did,
on the argument that an unmeasured operating point should not be able to fire on its own.

**That option no longer exists.** Segment D removed `Lifecycle` from the catalog: every built-in now
seeds enabled, and trust is expressed solely by who the capability flag is on for — "a classifier we do
not trust yet is one we do not hand to anyone but ourselves, which the flag already says, per org, from
a console, without a deploy."

**Decision (Akhil, 2026-08-08): ship it on.** `tool_error_enabled` keeps its platform default of on
rather than being flipped off until calibration.

Recorded plainly, because it is a real risk and not a closed one: **a partner can see this classifier
before anyone has measured what it does on their traffic.** Three things bound the damage, and none of
them removes it.

- Findings stream unbudgeted, but **Triage does not see them.** A case opens only after Layer-2 rules a
  deviation or a human presses *Real deviation* (§8.2), so the screen people are paged from is still
  gated on a judgement rather than on this threshold.
- The magnitude gate (§4.4) is independent of the CUSUM's calibration. Even a badly-set
  `decision_interval` cannot produce a finding about a move smaller than `min_effect_size`, so the
  failure mode is *too many findings about real moves*, not findings about noise.
- `decision_interval` is live-tunable per project, because there is no stored state to invalidate
  (§5). A bad number is a console edit away from a better one, not a deploy.

What is genuinely exposed: a partner whose tools fail in bursts may see findings the null run would
have told us to suppress, and the standing rule that every number the UI states about a detector is one that
was measured is not satisfied for this classifier until §12 runs.

---

## 10. Schema

**No new table, and no window state to hold** — §5 removed the thing a state table would have been
for. What persists is only what recompute cannot re-derive: the finding, and the human and machine
judgements attached to it.

The finding is a `behavior_finding` row. Metric drift's reuse of that table applies here for the same
reasons — its own doc comment is already "one CAUSE that fired, not one firing", and it carries
`call_site_id`, `cause_key`, `trace_count`, `exemplar_trace_id`, `escalated_at` and the whole
triage set.

One wrinkle: `0042` added `CHECK ((profile_id IS NULL) <> (baseline_id IS NULL))`, so every finding
must hang off either a behaviour profile or a `metric_baseline` row. A tool-error finding has neither.
**Widen that CHECK to admit a row with both null** rather than minting a vestigial `metric_baseline`
row purely to satisfy it — a state row that exists to hold no state, written by a classifier with no
sweep, is exactly the kind of thing a later reader deletes as dead and thereby breaks the constraint
from the other side. The scope column stays honest instead: null means "recomputed, not fitted".

`behavior_finding.baseline_id` being null also means `resolveShift` cannot re-pin, which is correct
here — there is no pinned reference to move. *Absorb* on a `rate_shift` records the human's ruling and
closes the finding; the baseline is re-derived on the next read regardless.

Forward-only migrations, each widening a CHECK the way `0042` and `0044` did:

- `behavior_finding.cause_kind` gains `rate_shift`
- `behavior_finding_scope_check` admits both scopes null
- `eval_case.detector` gains `tool_error`

`behavior_allowlist`'s cause CHECK is **not** widened, for the reason `0042` gives for
`distribution_shift`: an allowlist row says "this gram is fine forever", which has no meaning for a
rate whose baseline is recomputed. A `rate_shift` reaching that table would be a bug, and the
un-widened CHECK is what says so out loud.

---

## 11. Build order

1. `ToolFailure` — the §1 predicate and the §2 signature. Pure, no Spring, no database. Its tests are
   the definition's tests. **Done.**
2. `ToolErrorRate` — the counter set, JSON round-trip, bounded pattern map. **Done.**
3. `ToolErrorDetector` + `ToolErrorConfig` — the CUSUM, its magnitude gate, the onset rule. **Done.**
4. **Done.** `ToolErrorRepository` — the hourly `(tool, calls, failures, signatures)` aggregate, carrying
   `ToolFailure.SQL_PREDICATE`. One query, bounded window.
5. **Done.** `ToolErrorTrend` — replay the buckets through the detector, return the live spells. Pure given the
   buckets, so it is testable without a database and drivable by the harness.
6. **Done.** `ToolErrorEvidence` + the finding upsert, under §5.1's three rules.
7. **Done.** The catalog manifest and the `rate_shift` cause + scope migrations.
8. **Done.** `ToolErrorCaseSource` + the `eval_case.detector` migration, under §6.1's identity rules.
9. The Classifiers-page pattern breakdown (which needs `evidence` on `BehaviorFindingView`, since
   findings do not carry it to the wire today).
10. **Done, except the injection run.** The harness (§12).

Steps 4 and 5 are what the sweep would have been. That they are a query and a fold is the whole
argument of §5.

---

## 12. Evaluation

The same two runs metric drift needs, against the same kind of corpus, and neither can print a real
number until a labelled corpus exists to run them against.

1. **The null case.** Real traffic, unmodified, replayed per tool. Everything that alarms is a false
   positive by construction, so no labels are needed. The report is the **measured ARL₀** at each
   candidate `h` — calls between false alarms — which is directly comparable to the 250,000 the
   Brook–Evans solve predicts under independence. **The gap between the two is the cost of burstiness**,
   and it is the single number this run exists to produce.
2. **Injection.** Step a tool's failure rate by ×2 / ×5 / 0 → 2%, and collapse one pattern into
   another. Report detection rate per operator and the lag in calls, against the predicted lags in
   §4.3's table.

Both runs are strictly better instrumented than a floor sweep: ARL₀ is a quantity with units a human
can hold ("one false alarm per N calls"), where a count of firings at a candidate threshold is not.

The bridge runs the **shipping Java detector** over `jshell`, as metric drift's does, and for the
reason its README now spells out twice over: a Python restatement of the arithmetic measures a
detector nobody ships. It gets its **own** `bridge.jsh` rather than extending metric drift's — a
different statistic, a different config record, and segment A is editing that file.

---

## 13. What will actually bite you

- **Reading `error_type IS NOT NULL` and calling it the definition.** That is rule 1 of four, and it
  is the one that misses the caught-and-returned error — the most common real failure in an agent
  product. Go through `ToolFailure`. §1.
- **Grouping on the raw message.** Every id and duration in a status message splits one failure mode
  into hundreds of singletons, and the breakdown becomes a list of 412 things that each happened once.
  §2.
- **Putting the signature in the bucket key.** Silences the detector through a brand-new failure mode,
  which is the loudest thing it could ever see. §3.2.
- **A CUSUM with no magnitude gate.** It is a sequential test, so at volume it eventually alarms on a
  tenth of a percentage point — the z-test's own failure mode, reached from the other side. §4.4.
- **A pp threshold instead of an effect size.** Wrong at both ends, and tool-error rates live at both
  ends. §4.4.
- **An unsmoothed in-control rate.** `ln(p1/0)` is not a number, so a spotless tool becomes the one
  tool that cannot be scored — and it is the one whose failing matters most. §4.2.
- **A z-test.** Reports a 0.05pp move on a busy tool as significant. §4.5.
- **Fixed windows.** A boundary delays a thin tool by days and dilutes any shift that begins
  mid-window against its own healthy first half. §4.1.
- **Reading the failures without the denominator.** A rate needs both, from the same window, under the
  same predicate. Counting failures against a denominator built from a different filter is the classic
  way to report a rate that no query can reproduce.
- **Skipping the settle window.** A trace's tool calls arrive across several exporter flushes, so an
  early read has a partial denominator — and no reason to believe the calls that landed first fail at
  the same rate as the ones that had not. §5.
- **Anything per failing call.** A detection per failing span is exactly what `0030` deleted. §0.
- **Incrementing a persisted count.** Under recompute that counts how often the job ran, not how often
  the tool failed. Assign, never add. §5.1.
- **Letting a recomputed onset overwrite a stored one.** It reopens cases a human just closed, within
  one tick. §5.1.
- **Putting direction in the case key.** A recovery then opens a second case about the first. §6.1.
- **An uncapped accumulator.** The case then outlives the fix by as long as the outage lasted. §5.2.
