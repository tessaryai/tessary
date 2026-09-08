# tool_error — the eval that can set the threshold instead of assuming independence

The offline harness for the `tool_error` classifier. `PROGRAM.md` is the design contract; this is §12
of it.

**Why it exists.** `ToolErrorConfig.DEFAULT_DECISION_INTERVAL` is `6.0`, and unlike metric drift's
`w1_floor` it is not a guess — `arl.py` computes it exactly, and h=6 buys a false alarm about every
250,000 tool calls. But that computation assumes **independent Bernoulli trials**, and real tool
failures are bursty: one upstream outage fails two hundred consecutive calls. Autocorrelation inflates
the false-alarm rate by an amount no amount of arithmetic can price.

So there are two numbers, and the whole point of this harness is the gap between them:

| | what it answers | where |
|---|---|---|
| **analytic ARL₀** | what h would buy if failures were coin flips | `arl.py`, exact (Brook–Evans) |
| **measured ARL₀** | what h actually buys on this traffic | `eval_null.py`, needs a corpus |

**The measured one is unset.** No run has been made against a real corpus. Deliberately, this README
carries no number: one written down without a run behind it would be indistinguishable from the
assumption it is supposed to test.

## What is the real detector, and what is not

| Piece | Where it runs |
|---|---|
| the CUSUM, the magnitude gate, the onset rule, Jeffreys smoothing (`ToolErrorDetector`) | **the shipping Java class**, over the bridge |
| the reference/replay rules (`ToolErrorTrend`'s freeze-then-judge) | **reimplemented in `bridge.jsh`** — it copies `replay`, and can drift from it |
| the failure definition (`ToolFailure.SQL_PREDICATE`) | **restated in `corpus.py`**, and pinned by `tests/test_tool_error_bridge.py` |

The second and third rows are the hazards. A corpus counted under a different definition of failure, or
replayed under different reference rules, measures a detector nobody ships — the mistake behaviour
drift made the expensive way.

## Setup

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25      # `java` is not on PATH on the Mac dev box
cd backend && mvn -q -pl app -am compile           # once — the bridge reads analysis/target/classes
cd ../classifiers && uv sync
uv run pytest tests/test_tool_error_bridge.py      # SKIPS if the JVM is missing
```

If that reports skips, the bridge is not reachable and none of the runs below would measure the real
detector. Fix that before reading any number.

## Getting a corpus

```bash
mkdir -p data/tool_error
psql "$DATABASE_URL" -Aqt -v project_id="'prj_...'" \
  -c "$(uv run python -c 'from tool_error.corpus import EXPORT_SQL as q; print(q)')" \
  > data/tool_error/buckets.jsonl
```

Export a stretch long enough that a busy tool clears `min_baseline_calls` (500) and then keeps going —
realistically several thousand calls per tool, or the run measures nothing but the warm-up.

## Run 1 — the null case (do this first; it sets the threshold)

```bash
uv run python -m tool_error.eval_null --corpus data/tool_error/buckets.jsonl
```

Unmodified traffic, so **everything that alarms is a false positive by construction** and no labels are
needed. The report is measured ARL₀ per candidate `h`. Read the lowest one that clears the target, and
read the shortfall against `arl.py`'s prediction as the cost of burstiness.

## Run 2 — injection

Not built. It measures detection rate and lag against a stepped failure rate, and `arl.py`'s table
already predicts those under independence, so it is the second-most useful run rather than the first.
The null case is what decides whether the shipped threshold is wrong.
