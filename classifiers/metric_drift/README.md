# metric_drift — the eval that can set the threshold instead of guessing it

The offline harness for the `duration_drift` and `cost_drift` classifiers. `PROGRAM.md` in this
folder is the design contract and `PLAN.md` is the build order; this is §9 of that plan, the last
step, and the one the other eight are waiting on.

**Why it exists.** `MetricDriftConfig.DEFAULT_W1_FLOOR` is `0.18` — "about a 20% move" — and it is a
guess. Nobody has measured what an unmodified week of production traffic does to that number. Both
classifiers therefore seed **disabled**, which is the honest default for an unmeasured operating
point, and they stay disabled until the null run below says what the floor should be.

## The measured operating point

**Still unset**, and deliberately so: a floor written down here without the right run behind it
would be indistinguishable from the guess it is supposed to replace, and PROGRAM.md §"operating
point" is left empty for the same reason.

A run has now been made against **real traffic** — 5,788 paperclip agent runs via
`paperclip_corpus.mjs` — and it does not set the floor, for two reasons that are worth separating.
The first is the one this README already states: that corpus is built one step upstream of ingest,
so its buckets are agent slugs rather than resolved call sites. The second is a property of the
traffic itself and would apply to a perfect export of it: **a paperclip run is one whole issue, so
run-to-run duration and cost variance is the workload, not drift.** No candidate floor up to 2.2×
silences it on any measure. That is a fact about paperclip, not a verdict on the detector — but it
does mean the null case wants a project whose turns are comparable to one another, which is what
"stable traffic" in A2 means and what a partner's per-turn traffic looks like.

What that run did establish, all of it about the shipping code rather than the corpus:

| | |
|---|---|
| **The harness measures the real thing again** | the bridge reaches `MetricDriftDetector`, `MetricHistogram` and `MetricSuppression`, and all three runs complete against real traffic |
| **The duration grid's top is reachable** | 51 of 5,214 turn samples overflow — the grid spans to ≈1.7 h and paperclip has runs past it |
| **The cost grid's top is reachable** | 30 of 3,864 cost samples overflow — the grid spans to ≈$58 |
| **Both are ~1% of samples, and neither is acted on here** | the grid id is part of a sketch's identity, so moving `lo` or `ratio` invalidates every persisted sketch; and an agent-run-per-issue workload is the far end of the distribution these bounds were chosen for. Recorded as PLAN.md §11's signal, to be re-read against a partner-shaped corpus |
| **Every injection operator fires, at lag 0** | all five, on the one bucket with enough traffic to close windows either side of an onset. The apparatus works end to end against real traffic |

Run 2 on the same corpus makes the "not null" point more sharply than run 1 does, and it is the
clearest argument for getting the real export. A ×1.2 duration injection is reported as **2.58×
slower**, and each operator drags **15 distinct tool buckets** with it as collateral on call sites
nothing touched. Neither is the detector misbehaving: it is measuring a population whose baseline is
already moving that much, so the injected factor is swamped and the collateral is other real drift.
A detection rate obtained here would be a detection rate against noise of the corpus's own making.

When the null run lands, the value goes into `MetricDriftConfig.DEFAULT_W1_FLOOR` **and both
catalog blobs in `BuiltInClassifierCatalog` behind a version bump** — the two disagreeing is a
seeded project running on a floor the code does not think it has — the measured curve into
PROGRAM.md, and only then is turning `duration_drift_enabled` / `cost_drift_enabled` on for a
partner org a conversation. (The catalog's `Lifecycle` field is gone — segment D collapsed trust
onto the capability flag, so the flag is the only guard left.)

**What stands between here and that run, as of 2026-08-08.** The harness itself is no longer the
obstacle: it had never been executed, and three years of repo drift had quietly broken it (the
bridge pointed at a module layout that no longer exists, and the export queries had diverged from
the reads they mirror — see the two fix commits on this file's history). All three runs now
complete end to end on `--synthetic`, and `export_corpus.py` will produce a real corpus in one
command. The only thing missing is **a route to the production database**: the export is a `psql`
reach into it, and neither the platform's HTTP API nor its MCP surface can substitute — both return
compact metadata, not the per-turn root-span intervals and llm-leaf usage the corpus is made of.
Run it from wherever `DATABASE_URL` resolves, and A2 through A4 follow within the hour.

## What is the real detector, and what is not

This matters more than anything else in the harness, so it is stated first and precisely.

| Piece | Where it runs | Consequence |
|---|---|---|
| the sketch (`MetricHistogram`), its log grid, edge counters, quantiles | **the shipping Java class**, over the bridge | a floor measured here is a floor on the number the sweep computes |
| signed W₁, the floor test, the min-sample test, the grid check (`MetricDistance`, `MetricDriftDetector`) | **the shipping Java class** | firing and silence are decided by the code that ships, including which `Silence` reason a quiet window carries |
| the cross-grain suppression rule (`MetricSuppression`) | **the shipping Java class** | "a tool explained this turn" means what it means in production |
| `cause_key`, the finding title, p50/p95 in raw units (`MetricFindingEvidence`) | **the shipping Java class** | the strings in an eval report are the strings on the Classifiers page |
| **window assembly** — when a window closes, what pins, which reference a close is compared against | **reimplemented in Python** (`windows.py`) | this can drift from `MetricDriftSweep` and nothing would fail; `classifiers/tests/test_metric_drift_windows.py` pins the rules it copies |
| **reading the numbers off traces** (`MetricSource`'s column-preferred accessor, abstention reasons, `TokenUsage.nonOverlapping()`, the price book) | **not run at all** — the corpus is exported already-measured | an export that computes duration the wrong way (a min/max envelope over the trace, say) would feed this harness numbers the sweep would never produce. Use the queries in `corpus.py` |

The bridge is `bridge.py` + `bridge.jsh`: one `jshell` process per batch, running against the build
output of the backend modules `bridge.MODULES` names — `analysis` for the detector, the sketch, the
suppression rule and `ActionSymbol`, `substrate` for `TokenUsage`, `TokenPriceBook` and the vendored
price snapshot, and `core` + `shared` beneath both. It is a read of build output, never a build —
nothing here invokes Maven, because a 15-minute gate attached to an eval script is a gate nobody
runs the eval behind.

If the bridge cannot start, every entry point **fails loudly** rather than falling back to a Python
restatement of the arithmetic. behaviour drift is why: its Java port and its Python harness silently
diverged for a release, and every offline number measured in that window described a detector that
does not ship.

## Setup

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25      # `java` is not on PATH on the Mac dev box
cd backend && mvn -q -pl analysis,substrate -am compile   # once — the bridge reads build output
cd ../classifiers && uv sync
uv run pytest tests/test_metric_drift_windows.py   # the bridge test SKIPS if the JVM is missing
```

If that last command reports skips, the bridge is not reachable and none of the runs below would
measure the real detector. Fix that before reading any number.

## Getting a corpus

Everything runs off one JSONL file, exported from the platform's own substrate. The export is a
`psql` reach into the production database, so it runs wherever that is reachable — not from a
laptop with no route to it:

```bash
export DATABASE_URL=postgres://...
uv run python -m metric_drift.export_corpus --project-id prj_... --since 2026-05-01
```

That runs both queries from the constants in `corpus.py`, writes
`data/metric_drift/turns.jsonl`, and then **describes what it exported** — call sites, how many of
them carry enough traffic to be compared at all, whether a deploy boundary is in the stretch, and
what share of turns will abstain. Read that summary before running anything: a null run over a
corpus where no bucket clears `min_sample` twice is silent because it measured nothing, and silence
for that reason looks exactly like silence for the good one.

Turn rows and tool rows can share one file in either order — the loader attaches each span to its
trace afterwards — and tool spans nested under their turn as `"tools": [...]` are accepted too, which
is the easier shape to hand-write a fixture in.

**Loading an export needs the JVM**, for two things SQL cannot answer and this side must not guess:

- **Tool bucket keys.** The sweep keys a tool bucket on `ActionSymbol.of(kind, name, false)`, which
  merges `retrieval:vecs/policy-2024.pdf` with `retrieval:vecs/handbook-2025.pdf` and `search_docs_3`
  with `search_docs_4`. Keying on the raw `kind:name` would give the eval more, thinner buckets than
  production has — more of them below `min_sample`, and the ones that arm noisier — which moves the
  false-positive count the null run reports and therefore the floor it sets.
- **Cost.** `trace.total_cost` is null on every production row (`StructuralEnricher` passes literal
  null), so the export ships each turn's llm leaves as `(model, usage)` pairs and the loader sums them
  through `TokenUsage.nonOverlapping()` and prices them through the vendored `TokenPriceBook`. A turn
  with one unpriced leaf loads with `cost_usd: null` and abstains, exactly as the sweep would — an
  unpriced model is unpriced, not free, and a zero would read as a cost improvement.

Both ride one batched `bridge` call at load time, so it is one extra JVM start per corpus. A corpus
written back out with `write_turns_jsonl` carries the resolved values and loads again without one, as
do hand-written fixtures and `--synthetic`.

Export a stretch that spans **at least one deploy** (so run 3 has a boundary to attribute to) and
**several windows per bucket** (a bucket needs ≥ `min_sample` twice before anything is compared —
at the defaults, 300 turns minimum and realistically 1,500+).

### When the database is out of reach

`paperclip_corpus.mjs` builds a corpus from the **local Paperclip run logs** instead:

```bash
node metric_drift/paperclip_corpus.mjs --limit 12000 --out data/metric_drift/paperclip.jsonl
```

Paperclip's `evals-tracer` is what produced paperclip's production traces — it reads each agent
run's `claude` stream-json log and builds the `gen_ai.*` span tree it POSTs to `/v1/traces` — and
those logs are on the dev box. This script drives **that tracer's own builder**, imported rather
than reimplemented, and maps the spans it emits into the JSONL the loader reads. The durations,
models, token counts and call sites are the real ones.

**It sits one step upstream of the corpus the export produces, and that bounds it.** The span tree
is the *input* to ingest: `call_site_id` here is the tracer's `tessary.call_site.id` (the agent
slug) rather than the call site the platform resolves, `tool_call` normalization and
`StructuralEnricher` have not run, and every run is its own trace — so the turn population is agent
runs, not turns within a conversation. There are also no deploy boundaries in it, so run 3 has
nothing to attribute to. Use it to exercise the harness against real traffic and to get an
indicative curve; **the floor of record still wants `export_corpus.py`**, or these same spans
replayed through a local ingest.

Every entry point also takes `--synthetic`, which generates lognormal traffic with an agent
product's shape. That path exists to smoke-test the harness and prints a banner saying so. **It cannot set an
operating point**: a false-positive rate measured against a generator is a property of the generator.

## Run 1 — the null case (do this first; it sets the floor)

```bash
uv run python -m metric_drift.eval_null --corpus data/metric_drift/turns.jsonl
```

Real traffic, unmodified, replayed through the real detector. Everything that fires is a false
positive by construction, so no labels are needed — which is the point, since there is no gold set
for "this distribution moved for a bad reason" and there cannot be one.

The report is a curve per measure: at each candidate floor, how many findings the run produced over
how many comparisons. Read the lowest floor with zero findings as the floor that silences ordinary
traffic — **but only once the run says it can name one.** Zero findings over `c` comparisons bounds
the false-positive rate below roughly `3/c`, not below zero, so under 1,000 comparisons the run
declines to recommend and prints the bound instead: at that size every candidate floor is clean and
the curve's lowest point wins by an accident of sample size. Also read:

- **`silence=`** — a run that is quiet because every window was `BELOW_MIN_SAMPLE` has measured
  nothing at all, and is a different thing entirely from one that is quiet at `WITHIN_FLOOR`.
- **grid edges** — a non-empty overflow counter means the sketch's range is wrong for this corpus
  (PLAN.md §11). Underflow and overflow are counted rather than clipped precisely so this is visible.

`--mode split` runs PROGRAM.md §12.2 literally instead: each bucket's traffic cut chronologically in
half, one comparison per bucket, none of this harness's window bookkeeping in the way. When the two
modes disagree, the windowing is what to look at.

Each measure is counted on its own. Suppression can only ever merge two firings into one, so
per-measure counts are the conservative reading — and a floor should be set from the conservative
reading.

## Run 2 — injection

```bash
uv run python -m metric_drift.eval_injection --corpus data/metric_drift/turns.jsonl --w1-floor <from run 1>
```

Every operator in `inject.py`, applied to every call site with enough traffic to close windows either
side of the onset:

| operator | what it models | what it is really testing |
|---|---|---|
| `scale_duration` ×1.2 / ×1.5 / ×2.0 | everything in a bucket got slower | ln(1.2) = 0.18 is the guessed floor, so ×1.2 sits exactly on it: this reports whether the floor is where the config says |
| `collapse_cache` | a prompt-prefix edit stops the cache hitting | the most common silent cost regression, and it moves no duration at all |
| `retry_loop` | eleven tool calls where three used to do | that §6.1's suppression rule does **not** swallow the one case turn duration exists for — every tool bucket stays flat, so nothing at tool grain can explain the turn |

Reported per operator: detection rate, lag in windows (0 = the first window that saw the regression
reported it), and collateral firings on buckets nobody touched. Pass `--w1-floor` with what run 1
supports — a detection rate at an unmeasured floor says nothing, since any operator is detectable at
a floor low enough.

Two things to know when reading it. The headline ratio is the **first firing window's**, and that
window usually straddles the onset, so it understates the injected factor; and `collapse_cache`'s
dollar figures are modelled from token counts (the cache-read *ratio* is exact, the repricing assumes
output tokens cost 5× input and cache reads 0.1× — both constants are named in `inject.py` and
printed in the report).

## Run 3 — deploy replay

```bash
uv run python -m metric_drift.eval_deploy --corpus data/metric_drift/turns.jsonl \
    --bucket discover-sales-prospects --deploy pv_01J...
```

A known past regression, and the question a human actually asks in front of a finding: **since when?**
The run prints every finding on that bucket with its `since_version_id` and its onset (the close time
of the first window that showed the shift — what `CaseDetection.onsetAt` carries, not when the sweep
noticed), and says whether the attribution lands on the deploy before the regression.

With no known regression to hand, `--simulate 1.5` breaks the corpus at a real deploy boundary. That
makes "it fired" true by construction and is labelled as such in the output; what it still tests is
where the finding points.

## Runtime

A JVM start is ~2s and every run batches all of its comparisons into one. Run 1 is a single batch.
Run 2 is one batch per (operator × call site) because each needs its own injected corpus, so it
scales as operators × sites — a few minutes on a real corpus, and the JSON it ships to the JVM
carries every sample, so a very large export is worth trimming to a few weeks per bucket.

## Deliberate gaps

- **`MetricSource` is not exercised.** Its abstention reasons (`UNPRICED_MODEL`, `NO_END_TIME`) and
  the column-preferred rule are covered by `MetricSourceTest` on the Java side; this harness starts
  from numbers that have already been read. A corpus exported any way other than the queries in
  `corpus.py` can therefore feed it durations the sweep would never compute.
- **No human correction loop.** *Legitimate — absorb* re-pins the reference, and there is nobody to
  press it in an offline replay, so a persisting shift re-fires on every close here. That is also
  what the product does until somebody absorbs it.
- **Suppression pairing is by event-time overlap**, not by sweep page. The sweep pairs whatever
  rotated in one page; a replay has no pages. The rule itself is unchanged — only which closes are
  offered to it together.
- **No `CaseSource` replay.** Cases open only after Layer-2 rules a finding a real deviation
  (PROGRAM.md §8.1), and triage is an agentic sandbox run this harness deliberately does not
  stand up. What reaches Triage is a subset of what fires here.
