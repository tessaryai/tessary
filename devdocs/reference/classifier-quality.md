# Classifier quality: measured precision, recall, F1

The current measured quality of the encoder classifier this tree serves, with the provenance of
every number. This page carries groundedness.

**Read the regime before you read the number.** The classifier has no single quality figure: it
splits by the shape of the traffic it runs against, and quoting a single headline across regimes is
how this project has misled itself before. Where a figure is not established, this file says so
rather than substituting the nearest available number.

> **An unmeasured detector is a liability, not a feature** ([`AGENTS.md`](../../AGENTS.md) §
> *Strategic context*). A row marked *not established* is a gap in the asset, not a formality.

Last verified **2026-09-21**. Update rules at the bottom.

---

## Groundedness

Head: `tessaryai/groundedness-classifier-v1` @ `6746fa25` (public, MIT) — ModernBERT-large
fine-tuned from the published `lettucedetect-large` checkpoint on RAGTruth train plus Tessary's
verified corpora, served by `classifiers/groundedness/serve.py` as a TOKEN head: one 8,192-token
pass over every retrieved document and the whole answer, each answer token labelled O / BASELESS /
CONFLICT. Decoded as **P(unsupported) = P(BASELESS) + P(CONFLICT)** per sentence; fires on
**anything the evidence does not support**, contradicted or invented. `conflict` is returned
alongside for the narrower question. One threshold, `threshold` 0.975: an answer is flagged when
its strongest sentence reaches it, and every sentence at or above it is marked. All numbers below
use thresholds cross-validated by response — never fitted to the set they are scored on. Training,
evaluation, and the full audit live in the experiments repo:
[`tessaryai/experiments/groundedness-token-v1`](https://github.com/tessaryai/experiments/tree/main/groundedness-token-v1);
the model card on Hugging Face repeats the headline table.

<!-- pinned: groundedness_threshold=0.975 groundedness_revision=6746fa25f4f6cdb60f994f056c1919300e6c2b12 -->

| corpus, view | n | recall @ fp ≤ 2% | precision | F1 | best-F1 point |
|---|---|---|---|---|---|
| **RAGTruth test (human-labelled)**, unsupported, response | 1,775 responses / 364 positive | **0.390** | **0.826** | 0.530 | **F1 0.664** (P 0.70 / R 0.63) |
| RAGTruth test, unsupported, sentence | 9,540 sentences / 608 positive | 0.559 | 0.651 | 0.602 | F1 0.610 |
| RAGTruth test, conflict-only, sentence (the old contract) | 143 positive | 0.238 | 0.154 | 0.187 | — |

For scale: the published `lettucedetect-large` scores 0.559 / 0.404 (sentence / response,
unsupported) on the same RAGTruth split. The previous pair head (bart-large-mnli) scored 0.042 on
RAGTruth conflict/sentence, and every contradiction-only NLI checkpoint tested sat at 0.04-0.24: the
old "fires on contradiction, not on absence" contract was unreachable on human-labelled data by any
model of this size, which is why the contract changed with the head.

**The bar it ships against.** `UNSUPPORTED_GATE` in the experiments repo's
[`labels.py`](https://github.com/tessaryai/experiments/blob/main/groundedness-token-v1/labels.py):
response-level recall ≥ 0.35 at ≤ 2% false alarms with precision ≥ 0.80, best-F1 ≥ 0.65, and a fit
guard that refuses a threshold that only clears 2% on the data it was fitted to. The shipped head
passes it with no margin (0.390 / 0.018 / 0.826; 0.664). The next target, 0.50 recall at 2% FP, is a
target, not the bar.

**How findings use it.** A flagged answer is not a finding. The false alarm rate at 0.975 depends on
the domain, so each call site learns its own share of traces with a flagged answer (judging from 200
traces, learning until 1,000) and a one-sided CUSUM opens a finding when that share rises. A call
site's normal false alarms are absorbed into its baseline; a call site that is ungrounded from its
first trace learns that as normal.

**Known weakness.** Paraphrased *supported* claims can score high, so a single global threshold
at 2% FP under-fires on heavily paraphrased answers; the fix is a tuning corpus of the same
construction and per-domain calibration, not a model change. **Tool-backed answers** are in scope
under this contract and fire when the tool result is absent from the evidence — pass tool output
through as a retrieved document to ground it.

Where the set comes from: RAGTruth (MIT, `wandb/RAGTruth-processed`, Summary + QA test split).

---

## Update rules

1. A head or threshold moves → re-measure on the sets above, update the numbers, and update the
   `<!-- pinned: ... -->` comment beside them. `scripts/check-classifier-quality-doc.sh` compares
   the pins with `serve.py`'s `DEFAULT_REVISION` and `BuiltInClassifierCatalog`'s config literal and
   fails `task check` on a mismatch. Do not edit the pin without re-measuring.
2. A number that is not established stays marked *not established*; never substitute a nearby one.
3. The "last verified" date above is when someone last re-ran the measurement, not when the file was
   last edited.
