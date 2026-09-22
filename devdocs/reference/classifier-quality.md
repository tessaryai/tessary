# Classifier quality: measured precision, recall, F1

The current measured quality of the encoder classifier this tree serves, with the provenance of
every number. The overlay's copy of this page adds frustration; this page carries what the
open manifest binds.

**Read the regime before you read the number.** The classifier has no single quality figure: it
splits by the shape of the traffic it runs against, and quoting a single headline across regimes is
how this project has misled itself before. Where a figure is not established, this file says so
rather than substituting the nearest available number.

> **An unmeasured detector is a liability, not a feature** ([`AGENTS.md`](../../AGENTS.md) §
> *Strategic context*). A row marked *not established* is a gap in the asset, not a formality.

Last verified **2026-09-21**. Update rules at the bottom.

---

## Groundedness

Head: `tessaryai/groundedness-token-v1` @ `6746fa25` (public, MIT) — ModernBERT-large fine-tuned
from the published `lettucedetect-large` checkpoint on RAGTruth train plus Tessary's verified
corpora, served as a TOKEN head: one 8,192-token pass over every retrieved document and the whole
answer, each answer token labelled O / BASELESS / CONFLICT. Decoded as **P(unsupported) =
P(BASELESS) + P(CONFLICT)** at the strongest sentence; fires on **anything the evidence does not
support**, contradicted or invented. `conflict` is returned alongside for the narrower question.
Bands `threshold_high` 0.975 / `threshold_low` 0.5. All numbers below use thresholds
cross-validated by response — never fitted to the set they are scored on. Full audit:
[`classifiers/groundedness/README.md`](../../classifiers/groundedness/README.md); the model card on
Hugging Face repeats the headline table.

<!-- pinned: groundedness_high=0.975 groundedness_low=0.5 groundedness_revision=6746fa25f4f6cdb60f994f056c1919300e6c2b12 -->

| corpus, view | n | recall @ fp ≤ 2% | precision | F1 | best-F1 point |
|---|---|---|---|---|---|
| **RAGTruth test (human-labelled)**, unsupported, response | 1,775 responses / 364 positive | **0.390** | **0.826** | 0.530 | **F1 0.664** (P 0.70 / R 0.63) |
| RAGTruth test, unsupported, sentence | 9,540 sentences / 608 positive | 0.559 | 0.651 | 0.602 | F1 0.610 |
| RAGTruth test, conflict-only, sentence (the old contract) | 143 positive | 0.238 | 0.154 | 0.187 | — |
| Tessary fresh-domain held-out (generated, verified), conflict, sentence | 242 claims | **0.630** | 0.945 | 0.756 | F1 0.844 |
| Tessary fresh-domain held-out, unsupported, sentence | | 0.573 | 0.986 | 0.725 | F1 0.938 |
| Tessary hard held-out (style-cue-free), conflict, sentence | 215 claims | 0.132 | 0.800 | 0.226 | F1 0.711 |

For scale: the published `lettucedetect-large` scores 0.559 / 0.404 (sentence / response, unsupported)
on the same RAGTruth split and 0.036 / 0.011 on the two Tessary held-outs — the fine-tune is what
teaches the product/support domain. The previous pair head (bart-large-mnli) scored 0.042 on
RAGTruth conflict/sentence, and every contradiction-only NLI checkpoint tested sat at 0.04-0.24: the
old "fires on contradiction, not on absence" contract was unreachable on human-labelled data by any
model of this size, which is why the contract changed with the head.

**The bar it ships against.** `classifiers/groundedness/labels.py` `UNSUPPORTED_GATE`: response-level
recall ≥ 0.35 at ≤ 2% false alarms with precision ≥ 0.80, best-F1 ≥ 0.65, and a fit guard that
refuses a threshold that only clears 2% on the data it was fitted to. The shipped head passes it
with no margin (0.390 / 0.018 / 0.826; 0.664). The next target, 0.50 recall at 2% FP, is a target,
not the bar.

**Known weakness.** On the hard held-out, deliberately paraphrased *supported* claims score high
(mean 0.59 unsupported), so a single global threshold at 2% FP under-fires there; the fix is a
tuning corpus of the same construction and per-domain calibration, not a model change. **Tool-backed
answers** are in scope under this contract and fire when the tool result is absent from the
evidence — pass tool output through as a retrieved document to ground it.

Where the sets come from: RAGTruth (MIT, `wandb/RAGTruth-processed`, Summary + QA test split);
Tessary held-outs are `classifiers/data/groundedness/heldout_*.json` (Claude-generated,
independently verified, never trained on).

---

## Update rules

1. A head or threshold moves → re-measure on the sets above, update the numbers, and update the
   `<!-- pinned: ... -->` comment beside them. `scripts/check-classifier-quality-doc.sh` compares
   the pins with `classify-service/models.json` and `BuiltInClassifierCatalog`'s config literal and
   fails `task check` on a mismatch, for every head the manifest binds. Do not edit the pin without
   re-measuring.
2. A number that is not established stays marked *not established*; never substitute a nearby one.
3. The "last verified" date above is when someone last re-ran the measurement, not when the file was
   last edited.
