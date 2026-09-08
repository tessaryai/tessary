# Deviation math — how the three launch classifiers decide

`duration_drift`, `cost_drift` and `tool_error` all answer "did this get worse", and none of them
knows what "good" looks like. Each compares a call site, or a tool, against its own recent past. This
is the arithmetic behind that, why each choice was made, and which numbers are measured versus
assumed.

Operating points: [`config-keys.md`](../reference/config-keys.md). Every constant below has
its rationale on the Java constant that holds it; this document is the shape they form together.

**Three classifiers, two engines.** Duration and cost are the same machine on different units — both
compare two *distributions*. Tool error is a different machine, because a failure rate is a single
proportion with no spread to compare.

---

## 1. Distribution shift — `duration_drift`, `cost_drift`

### The statistic

**Signed Wasserstein-1 distance on `log(value)`**, between two windows of the same bucket. Plain
reading: how far you would slide the reference curve sideways to land on the current one.

On a shared log grid both CDFs are sampled at the same slot edges and every slot is `ln(r)` wide, so
the integral collapses to a sum:

```
W1 = ln(r) · Σᵢ |CDF_ref(i) − CDF_cur(i)|
```

Sign comes off the clamped mean, because a distance has none. Magnitude comes off the CDFs. Those two
can honestly disagree — W₁ can fire on a shift living entirely in the tail while the median holds —
which is why `MetricSuppression` tests the sign of millisecond deltas rather than the reported
direction.

Because the samples are logs, `e^W1` is the **multiplicative** shift, so the finding writes itself:
`W1 = 0.336` is "1.4× slower", and that means the same thing for a 200 ms tool call and a 40-second
research run.

**Not a p-value, deliberately.** With 100k traces in a window a KS test calls a three-millisecond
shift significant, because significance inflates with sample size while effect size does not. The
floor is on the same scale the finding reports.

### The sketch

Fixed geometric bins: bin `i` covers `[lo·r^i, lo·r^(i+1))`, which in log space is a uniform grid.

| | |
|---|---|
| `r` | 1.05 — quantiles good to ±2.5% |
| bins | 320 — spans `1.05^320 ≈ 6.0e6`, six orders of magnitude |
| duration `lo` | 1 ms, so the range reaches ≈ 1.7 h |
| cost `lo` | $0.00001, so the range reaches ≈ $60 |

Constant memory, exact merges, no seeds or ordering effects — chosen over a t-digest because the eval
has to replay one corpus twice and get the same number. Out-of-range samples increment a dedicated
edge counter and still count toward `count()`: dropping them would make a bucket that moved *out* of
range look unchanged, and folding them into the end bin would hide that the range is wrong.

### Windows and buckets

A **bucket** is what gets watched. Turn grain is the `call_site` alone — it was `(call_site,
environment)` until Track A dropped `environment_id` off the substrate. Tool grain is an
`ActionSymbol` `kind:name` and carries **no call site** — a tool's latency is a tool's latency
whoever dispatched it, and scoping per call site would shatter one tool into five thin populations.

A **window** closes on either criterion, and neither is read until `min_sample`:

```
count >= window_target_count          (500)
elapsed_event_time >= window_max_hours (24h)
```

Cut on the **event clock**, never the exporter's: a backfill lands a whole corpus in one ingest burst,
so a time-cut window on ingest time would swallow a month of traffic and compare it against nothing.
The keyset cursor stays on ingest time, because that is the clock that is monotonic and gap-free.

### Two references, both on every close

Each is provably blind in one direction alone:

- **the rolling control** (wire word `previous`) — catches sudden breaks, never notices a slow boil,
  because a change spread over a fortnight is simply absorbed into a fortnight of memory.
- **pinned** — catches cumulative creep from a known-good point, then screams forever once something
  legitimately changed.

At most one finding per closed window, and the pinned view wins when both fire: it is the one a human
can settle. Only a person moves the pin — *Legitimate — absorb*. Layer-2 confidence is explicitly
never authority to mutate a baseline, because an automatic re-pin would let the very next window
normalize a real regression.

### The rolling control

The short-horizon reference is **not** one previously-closed window. It is a weighted merge of the
closed windows of the last three weeks, held as one slot per UTC day:

```
weight(day) = 2^(-age_in_days / 7)          half-life 7 days, retained 21
nEff        = (Σ nᵢdᵢ)² / Σ nᵢdᵢ²           Kish's effective sample size
```

Three things were wrong with one window, and the control fixes all three:

- It was **as noisy as the window it judged**, so half the comparison's sampling noise came from the
  bar rather than from the thing being measured. `nEff` above is what the bar is scaled by, so a
  thicker control raises the effective window size and lowers the bar honestly.
- It had **whatever shape the clock gave it** — a window that closed over a quiet night was the bar a
  busy morning got judged against. Seven days of memory spans whole diurnal and weekday cycles.
- A step change **fired against it exactly once**, because the next window's previous IS the new
  level. The control fades a change out over a fortnight instead, and holds a confirmed one out
  indefinitely.

**Days a confirmed regression ran through are excluded.** A window whose finding Layer 2 ruled a
`deviation`, or that a human blocked, never enters the control — otherwise the shift under
investigation quietly becomes the bar the next window is judged against, which is the silent
normalization the pin exists to prevent, arriving through the other reference. Windows ruled
*expected* fold in normally, so an "expected" ruling does real work.

The exclusion is applied **at read time, not at fold time**. A ruling lands well after the window
closed, so the ring stores an exact record of what closed when and every pass re-decides what to leave
out. That is what makes a late verdict retroactive. Weights are on the **wall clock**, so a backfill
that lands a month of event time in one pass does not resolve to a ring whose every day is
simultaneously fresh and ancient.

Weighting applies to the measure sketch alone. The workload and token blocks are merged exactly over
the same retained days — they are context for a human, never an input to the decision, and a median of
the reference period's prompt sizes does not become more honest for being tilted toward Tuesday.

### The bar: the move, raised when the windows are thin

Two windows drawn from the **same** distribution do not produce a W₁ of zero. They produce sampling
noise, and it grows as the windows thin: an empirical CDF is uncertain by `sqrt(F(1−F)/n)` at every
point, and comparing two independent windows adds their variances — `F(1−F)·(1/nRef + 1/nCur)` — so
noise falls as `1/sqrt(nEff)` with `nEff` the **harmonic** mean of the two counts.

Harmonic, not arithmetic, and that is the load-bearing part: a 500-sample reference cannot rescue a
100-sample current window. The arithmetic mean would say 300 and set the bar as though it could.

```
floor = w1_floor · max(1, sqrt(window_target_count / nEff))
```

Held flat instead, the false-alarm rate runs from 1% at 500-against-500 to 5% at 500-against-200 and
20% at 500-against-100. Scaled, it stays at or just under 1% across that whole range:

| nRef | nCur | Bar | Move | False alarms | Catches 2× |
|---|---|---|---|---|---|
| 500 | 500 | 0.139 | 15% | 1.00% | 100% |
| 500 | 200 | 0.184 | 20% | 0.93% | 100% |
| 500 | 100 | 0.241 | 27% | 0.91% | 100% |
| 100 | 100 | 0.311 | 36% | 0.78% | 100% |

That is what lets a bucket close on a 24-hour clock and still be judged honestly. Small moves on thin
traffic stay invisible and no schedule changes that — 100 samples cannot resolve a 15% shift — but
large ones surface in a day rather than a week.

`max(1, …)` means it only ever tightens. A bucket thicker than the target could support a smaller bar,
but then `w1_floor` would stop meaning "the smallest move we will tell you about" and start meaning
"…on a bucket of average thickness".

### Why the dial is a move and not a false-alarm rate

Noise also scales **linearly** with how spread out the traffic is. Measured on this statistic and grid,
the whole picture is one line:

```
noise quantile = c(rate) · σ · sqrt(2 / nEff)
```

with `c` depending on nothing but the rate — fitted across σ from 0.30 to 1.10 and window mixes from
500/500 to 100/100, it varied by **±1.3%**:

| rate | c | | rate | c |
|---|---|---|---|---|
| 20% | 1.596 | | 2% | 2.458 |
| 10% | 1.880 | | 1% | 2.688 |
| 5% | 2.143 | | 0.5% | 2.900 |
| | | | 0.2% | 3.189 |

So the bar *could* be derived per bucket from its own σ, holding one false-alarm rate everywhere. It
deliberately is not, for two reasons:

1. **The move is the promise a person can hold.** "We tell you about 15% moves" is predictable. "We
   tell you at a 1% false-alarm rate" is not, and it makes the reportable move differ per bucket — so
   two people looking at two call sites get different answers to "why didn't this fire".
2. **σ misreads a bimodal bucket.** A call site with a fast cached path and a slow uncached one has a
   large σ that is *structure*, not noise. A σ-derived bar would inflate exactly where a shift matters
   most.

σ is still measured (`MetricSketch.stdDevLog()`) and **reported**: the tuning surface uses it to tell
an operator what false-alarm rate their chosen move implies on their own traffic, via
`MetricDriftDetector.impliedFalseAlarmRate`. It explains the bar; it never sets it.

`w1_floor` defaults to 0.139 because that is the 99th percentile of the null on wide traffic — the 1%
bar for a call site whose p95 is ≈3.7× its median. On tighter traffic the same move is stricter and
costs fewer false alarms, and that variation is the price of the move being the promise.

### One event, one finding

`duration_drift` spans two grains, so one slow tool can present as both a tool shift and a turn shift.
When a tool's Δ covers at least `explained_by_fraction` (0.5) of the turn's Δ — same call site, same
sign, compared in **absolute milliseconds** rather than log units — only the tool finding is written
and the turn shift rides on it as evidence. The tool row names the fix; the turn row restates the
symptom.

An unexplained turn shift still fires alone, and that case is the whole reason turn duration is
measured: *eleven tool calls where three used to do*, where every call is as fast as ever and only the
count moved — invisible at tool grain by construction.

Suppression is a **within-pass** judgement, over windows that closed together. When the two grains
rotate in different sweeps the failure mode is one extra finding, not a missing one.

### Cost is priced at write time

Cost is summed over a trace's generations, priced at ingest by `IngestPricer` against the versioned
price book, into each generation's `span.total_cost` (rolled up into `trace.total_cost`). There is no
read-time re-pricing fallback.

A cost recomputed on every read moves with the deploy: a pinned reference held dollars from an older
book, so a rate refresh shifted every bucket against its own reference at once and read as a
fleet-wide regression nothing had caused. Pricing on arrival makes a recorded cost a fact about what
that call was billed at, and makes a later vendor price change a *real* change in spend rather than a
retroactive edit to history. The cost of that: a generation that arrived without a price is
unscoreable and stays unscoreable — nothing backfills it.

Unpriced is null, never zero. $0 would turn a price-book gap into a cost improvement.

---

## 2. Rate shift — `tool_error`

### What counts as a failure

Four structural rules, none reading prose: span status, an OTel `error.type`, a recorded
`exception.type`, or a result payload that declares itself an error. The fourth matters most — a
framework catching the exception, handing `{"error": …}` back to the model and closing the span
cleanly was invisible before, and is probably the most common real tool failure in an agent product.

### Why a CUSUM and not two windows

A window is a boundary, and a boundary both delays and dilutes. At a window of 2,000 calls a tool
called 200 times a day is judged every ten days, and a regression starting mid-window is averaged
against its own healthy first half — so the very first window after a break is the one least likely to
show it.

A CUSUM has no boundary. It accumulates evidence call by call and alarms as soon as the evidence is
sufficient. It also knows **when the shift began** — the last moment the statistic sat at zero — which
is what a case's onset should say and what a window scheme can only approximate to its own resolution.

Metric drift rejects CUSUM for duration and cost, and that argument does not reach here: it turns on
those measures being heavy-tailed, where a mean-shift test misses a p95 move that leaves the median
still. A failure rate is a bounded proportion with no tail to miss.

### The score

In-control rate, Jeffreys-smoothed so a tool that has never failed is still scoreable:

```
p0 = (failures + 0.5) / (calls + 1)
p1_up   = min(max(p0 · shift_multiple, p0 + shift_floor), 0.99)
p1_down = max(p0 / shift_multiple, 1e-6)

failure -> ln(p1 / p0)
success -> ln((1 − p1) / (1 − p0))
S = max(0, S + score)           alarm when S >= h
```

At a 1% in-control rate a failure adds 0.693 and a success subtracts 0.0102 — 68 clean calls to erase
one failure. Healthy traffic drifts *downward* and sits at zero; a doubled rate climbs at 0.0039 per
call and reaches `h ≈ 6.4` (the derived threshold at a 1% base rate) in roughly 1,400–1,800 calls.

Scoring in log-likelihood ratio gets most of the way to one `h` across every tool, and not all of the
way. Holding the false-alarm run length at 250,000 calls, the required threshold is 5.8 at a 0.5% base
rate, 6.4 at 1%, 8.1 at 5% and 9.6 at 20%. So `h` is **derived per tool** rather than configured:

```
h(p0) = clamp(11.42 + 1.088·ln(p0) + ln(arl_target / 250000), 6, 12)
```

The dial is `arl_target`, the calls a healthy tool should run between false alarms. A flat 6.0 — what
this shipped until 2026-08-11 — delivers an ARL₀ of 308,498 at 0.5% and 6,936 at 20%, a spread of 44x;
the line holds it between 220k and 309k, a spread of 1.4x.

**The accumulator is uncapped, and cases do not close themselves.** There used to be a `3h` ceiling so
that a fixed tool's case drained and closed within about a day. It also pinned every serious outage to
the same number, which made the ranking below a flat tie across every real problem. Cases now stay open
until a human closes them, and closing one resets the accumulator outright.

### One gate, and what the second one was covering for

Crossing `h` says the process changed, and on a busy tool it will eventually cross on a move from
1.00% to 1.05%. That used to be handled by a second gate on Cohen's h at `min_effect_size` = 0.05.

**That gate is gone.** It measured the shift over every call since the reference was pinned, which on a
tool with history is the baseline by construction: a 5% tool going to 80% read as 5.001%, h=0.00004,
and was silenced for 15,512 calls. It also silenced 1% → 2% permanently, which is the shift the
detector is explicitly tuned for. The small-move leak it was covering for was the miscalibrated flat
threshold above, and it is now fixed there — a rate below the accumulator's own break-even point
cannot reach the bar at all.

Cohen's h remains on every finding as a **reported** number and nothing branches on it:

```
h = 2·(asin(sqrt(p_cur)) − asin(sqrt(p_ref)))
```

`p_cur` is measured over the run since the accumulator left zero, never over the tool's history.

### Ranking

```
criticality = 10·ln(S)
```

Read after the crossing, never at it. Every alarm crosses from below, so the statistic at that moment
says only that the bar was reached — a trivial drift and a total outage both alarm at S ≈ 6.2, and what
separates them is that one reaches 18 in 22 calls and the other in 6,186. Unnormalised by `h`, so a
case opens near 18–25 rather than at a common zero. It blends severity, duration and traffic, which is
the honest shape for "how much has this cost me" and not an answer to "how bad is it per call".

### Replay, not sweep

There is no window, no cursor and no stored state. The last 28 days of hourly `(calls, failures)`
tallies are replayed on every pass, bucketed on `tool_call.started_at` — the event clock, with no
fallback. Bucketing on ingest time is not an approximation but a different question: a backfill lands
months of traffic in one burst, and hourly buckets cut on ingest time pile that whole history into the
hour it arrived. A call with no start time is skipped and counted, never guessed at; that is safe for a
rate because a missing timestamp is a property of the producer rather than of the call's outcome, so
numerator and denominator drop together. The reference is built from the leading buckets until it holds
`min_baseline_calls`, then **frozen** — a reference that moved with the traffic would drift along with
a slow degradation and never notice it.

Recompute-per-read deletes the cursor/watermark/double-count bug class rather than defending against
it, and a retuned threshold takes effect on the next pass instead of invalidating stored state. The
grouped-per-hour scoring is a real approximation in one direction: the accumulator's floor at zero
applies once per bucket rather than once per call, so a grouped replay is very slightly slower to
forget a burst. It is never more sensitive.

Only tools alarming **at the end of the replay** are reported. A tool that degraded mid-window and has
since recovered is not firing now, and reporting it would open a case nothing will ever close.

---

## 3. What is measured and what is not

| Number | Status |
|---|---|
| W₁ noise law `c(rate)·σ·sqrt(2/nEff)` | **Measured** — synthetic null, ±1.3% across σ and window mixes |
| `c(rate)` table | **Measured** — 40k null comparisons per point |
| `w1_floor` = 0.139 | A **choice** expressed as a move; equals the 1% bar on wide traffic |
| Real-corpus null run | **Owed.** PLAN.md §9 — real traffic split in half, unmodified, where every firing is by construction a false positive. Synthetic traffic is lognormal by assumption; real traffic is not. |
| `arl_target` = 250,000 | A **choice**, and the only threshold dial. One false alarm per corpus-and-a-half at the project the launch is measured against. |
| `h(p0) = 11.42 + 1.088·ln(p0) + ln(arl_target/250000)` | **Derived**, by `classifiers/tool_error/arl.py`, to hold `arl_target` at every base rate — but under an independence assumption real traffic violates. Failures are bursty, and autocorrelation inflates false alarms by an amount arithmetic cannot price. The gap between predicted and measured ARL₀ is what a null-case replay exists to produce. |
| `min_effect_size` = 0.05 | A **landmark**, no longer a gate. Nothing branches on it; it is kept because it is the scale a reader judges a reported effect size against. |
| `criticality = 10·ln(S)` | A **choice** of scale. Natural log so every 10 points is 2.72x more evidence; unnormalised by design. |

**Silence always carries a reason.** Both detectors enumerate why they did not fire — no reference
yet, below the sample floor, inside the bar, grid mismatch; no baseline, below the baseline minimum,
in control. A detector that returns "did not fire" without saying why is
indistinguishable, in a log or an eval report, from one that was never asked.

**Findings are deduplicated by cause, not by firing.** One `finding` row per `cause_key`,
with `onset_at` holding the onset of the **current spell**: frozen for as long as the detection
keeps firing, and moved only when the row goes unrefreshed for longer than its detector's quiet window
(6h for tool errors, `window_max_hours` for metric drift).

That gap **is** the recovery observation. Nothing writes "this came back": a recovered detection simply
stops appearing, so its finding stops being bumped. Freezing the onset within a spell is what stops a
recomputed onset from reopening a case a human just closed; moving it across a gap is what lets a
detection that recovered and re-fired reopen one. The reopen test in `CaseLedger` reads "did the onset
move", which under this rule means "did we watch this recover and break again". See
[`alerting.md`](./alerting.md) for how a finding becomes a case.
