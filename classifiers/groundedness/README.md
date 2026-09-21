# groundedness — the eval that decides whether this classifier is worth shipping

The offline harness for the `groundedness` classifier: a claim scored against the source content a
trace actually produced. `labels.py` is the contract; everything else imports it.

**The shipped contract (2026-09-18 onward): the classifier fires on anything the evidence does not
SUPPORT.** The served head is a token classifier; its response score is the strongest sentence's
P(BASELESS) + P(CONFLICT), gated by `labels.UNSUPPORTED_GATE`, and a true fact from a tool call that
was not passed as evidence is unsupported by that definition. See "The long-context token classifier"
and "Serving the token head" below for the numbers and the wire.

**The earlier programme, kept as the audit trail:** everything up to that section evaluates the
contradiction-only contract (`labels.GATE`, positive class `contradiction`), under which a sentence
the source does not mention was not a finding. Those sections explain why the contract changed: on
human-labelled data the contradiction-only question was unreachable by any model of this size.

## What is the real detector, and what is not

| Piece | Where it runs |
|---|---|
| the head, its checkpoint and pinned revision | **classify-service**, `models.json` + `SCORERS` in `classify.js` |
| premise tiling, claim clamping, window reduction | **`classify.js`**, and copied into `sweep_corpus.py` |
| the band (`threshold_high` / `threshold_low`) | **`BuiltInClassifierCatalog.java`**, applied to P(unsupported) (the pair head applied it to `1 - support`) |
| claim extraction (which sentences get scored at all) | `VerifiableClaims`, **not in this tree** |
| evidence selection (which documents become the premise) | `SubstrateReadRepository.groundingEvidence`, **not exercised here** |

Rows three to five are the hazards. This harness measures **the head and the tiling**, on premises
this module authors. It does not measure which sentences production decides to score, or which
documents production decides to score them against. A number from here is an upper bound on the
end-to-end classifier, never a substitute for it.

`sweep_corpus.py` is a shipping copy of the tiling and is pinned to the real JS by
`tests/test_groundedness_sweep_corpus.py`, which runs `classify.js` in node and compares character
for character. That pin found a real divergence on its first run.

**The input format is a sixth thing that has to match, and it is easy to get wrong.** `classify.js`
hands the encoder ONE string, `premiseChunk + eos_token + claim` — MiniCheck's format, inherited
through the head swap. An NLI checkpoint is normally fed through the tokenizer's own pair path
(`text_pair`), which puts the segment boundary where the model was trained to expect it. These are
different inputs and they produce different scores. `Config.input_format` defaults to `concat`
because **the rig must measure what ships**; the `pair` arm exists to decide whether `classify.js`
should change, not to assume it has. Measuring with `pair` while the service uses `concat` produces
numbers that do not transfer, which is exactly the mistake the first run of this module made.

## The gate

```python
GATE = Gate(min_recall=0.66, max_fp=0.02)   # the contradiction-only bar; see labels.py for why 0.66
```

Recall on the contradiction class while flagging at most 2% of everything else. The FP budget is
the binding constraint and it is chosen first: this is a per-observation filter on every trace, so
2% of scored observations is already a large absolute number of findings a human has to dismiss.
Recall is what the budget buys, not the other way round.

## The data

| script | output | source | license |
|---|---|---|---|
| `pairs.py` | 208 pairs from 52 claims | authored in this repo | Apache-2.0 (ours) |

**Authored, not sourced, and the reason is the distractors.** A public NLI or faithfulness corpus
gives short crowd-written premises. Production gives several thousand characters of retrieved
policy prose, of which one passage is relevant and the rest are adjacent subjects from the same
corpus — mentioning the same units (days, amounts, percentages) about a different rule. That
adjacency is the entire difficulty and no public set reproduces it, so `pairs.py` writes 20
documents across four domains to produce it deliberately.

**The pairs are not independent, and the report says so.** Each of 52 claims is instantiated in
four premise shapes (`single`, `three_first`, `four_mid`, `six_deep`), so a confidence interval on
recall uses the 24 positive **claims**, not the 208 pairs. `eval_ablations.py` prints a Wilson
interval on the claim count for exactly this reason. At n=24 that interval is wide, and no number
out of this module should be published as a recall figure without saying so.

Claim kinds, and which are positive:

| kind | n | label | what it tests |
|---|---|---|---|
| `contradiction` | 16 | 1 | the target class |
| `compound` | 8 | 1 | two assertions in one sentence, one contradicted — the headroom in claim decomposition |
| `support` | 12 | 0 | restatement must stay quiet |
| `neutral_addition` | 8 | 0 | the source is silent — OUT OF SCOPE, must stay quiet |
| `tool_fact` | 8 | 0 | a figure only a tool call could produce — OUT OF SCOPE, must stay quiet |

`decontextualized` is a slice rather than a kind: 8 claims lead with a pronoun the premise cannot
resolve ("It expires five years after you earn it"), and they span both labels.

## Running it

```bash
cd classifiers
uv sync --extra train                       # torch, for the local checkpoints
uv run python -m groundedness.pairs         # the authored set, and its shape
uv run --extra train python -m groundedness.eval_ablations
uv run pytest tests/test_groundedness_sweep_corpus.py

# label quality and the generated corpus (needs a judge: TESSARY_JUDGE=cli uses the
# logged-in Claude Code subscription, no API key)
TESSARY_JUDGE=cli uv run python -m groundedness.judge_labels      # kappa vs the authored labels
TESSARY_JUDGE=cli uv run python -m groundedness.generate_corpus   # long documents, new domains
TESSARY_JUDGE=cli uv run python -m groundedness.verify_corpus     # independent pass, drops misses
uv run python -m groundedness.generated_pairs                     # what survived
uv run --extra train python -m groundedness.eval_ablations --corpus generated
```

The generated corpus attacks two gaps the authored one has: it comes from a different process
(eight domains the authored set never touches) and its documents run 3,500-4,000 characters, so a
six-document premise lands near the real 20,000-character clamp and the window budget finally does
something. Its claims carry INTENT labels from the generator, so `verify_corpus.py` relabels them
with a **different model** and drops every claim the two read differently; the surviving corpus
carries its own kappa. `data/` is gitignored, so both are regenerated rather than committed.

To score the **served** head instead of a local checkpoint, publish classify-service to loopback
(see `classifiers/README.md`) and use `ClassifyServiceScorer(head="groundedness").score_pairs(...)`
— that path exercises the real tiling and reduction rather than this module's copy.

## Results

Recall on the contradiction class at fp <= 0.02, on the **authored** corpus (208 pairs / 52 claims,
24 positive). `passes` is forward passes per run — the per-event cost each recall lever trades
against. All arms use the shipping input format (`concat`) unless named otherwise.

| checkpoint | arm | recall | fp | prec | passes | 95% CI (24 claims) | gate |
|---|---|---|---|---|---|---|---|
| `facebook/bart-large-mnli` (incumbent) | served today (MAX) | 0.562 | 0.018 | 0.964 | 338 | [0.39, 0.76] | fail |
| | reducer fixed (MIN) | 0.594 | 0.018 | 0.966 | 338 | [0.39, 0.76] | fail |
| | + 8 chunks | 0.594 | 0.018 | 0.966 | 338 | [0.39, 0.76] | fail |
| | + doc-aligned | 0.542 | 0.000 | 1.000 | 728 | [0.35, 0.72] | fail |
| | + pair-encoding | 0.552 | 0.018 | 0.964 | 338 | [0.35, 0.72] | fail |
| `microsoft/deberta-large-mnli` | served today (MAX) | 0.573 | 0.018 | 0.965 | 338 | [0.39, 0.76] | fail |
| | reducer fixed (MIN) | 0.562 | 0.018 | 0.964 | 338 | [0.39, 0.76] | fail |
| | + doc-aligned | 0.740 | 0.018 | 0.973 | 728 | [0.55, 0.88] | PASS |
| **`MoritzLaurer/DeBERTa-v3-large-mnli-fever-anli-ling-wanli`** | served today (MAX) | 0.708 | 0.018 | 0.971 | 338 | [0.51, 0.85] | PASS |
| | **reducer fixed (MIN)** | **0.833** | **0.018** | **0.976** | **338** | **[0.64, 0.93]** | **PASS** |
| | + 8 chunks | 0.833 | 0.018 | 0.976 | 338 | [0.64, 0.93] | PASS |
| | + doc-aligned | 0.875 | 0.018 | 0.977 | 728 | [0.69, 0.96] | PASS |
| | + pair-encoding | 0.833 | 0.018 | 0.976 | 338 | [0.64, 0.93] | PASS |

Per-slice at the FEVER checkpoint with the reducer fix and doc-aligned tiling:

```
by kind:  compound=0.84  contradiction=0.83  neutral_addition=fp0.00  support=fp0.04  tool_fact=fp0.00
by shape: single=1.00  three_first=0.88  four_mid=0.75  six_deep=0.71
```

### On realistic premise lengths — the generated corpus

585 pairs from 117 verified claims (45 positive), premises 4,179-24,119 characters. This is the
corpus that matters: `EVIDENCE_ROWS` x `EVIDENCE_CHARS_PER_ROW` is 6 x 4,000, so production
assembles premises in this range, and the authored corpus above tops out at 2,704.

| checkpoint | arm | recall | fp | prec | passes | 95% CI (45 claims) | gate |
|---|---|---|---|---|---|---|---|
| `DeBERTa-v3-mnli-fever-anli` | served today (MAX) | 0.182 | 0.014 | 0.891 | 2334 | [0.09, 0.31] | fail |
| | reducer fixed (MIN) | 0.387 | 0.014 | 0.946 | 2334 | [0.25, 0.52] | fail |
| | + 8 chunks | 0.427 | 0.019 | 0.932 | 4206 | [0.29, 0.57] | fail |
| | + pair-encoding | 0.387 | 0.014 | 0.946 | 2334 | [0.25, 0.52] | fail |
| | **+ doc-aligned** | **0.724** | **0.017** | **0.964** | **9262** | **[0.59, 0.84]** | **PASS** |
| | + para-aligned | 0.733 | 0.019 | 0.959 | 27119 | [0.59, 0.84] | PASS |
| `facebook/bart-large-mnli` | reducer fixed (MIN) | 0.169 | 0.019 | 0.844 | 2334 | [0.09, 0.31] | fail |
| | + doc-aligned (para) | 0.307 | 0.014 | 0.932 | 27119 | [0.20, 0.46] | fail |

Recall by premise shape is the whole story:

```
byte-tiled (shipping):  single=0.80  three_first=0.76  four_mid=0.04  six_deep=0.18  six_last=0.16
document-aligned:       single=0.71  three_first=0.71  four_mid=0.73  six_deep=0.73  six_last=0.73
```

**Byte tiling does not degrade with premise size, it falls off a cliff.** Four windows evenly
spaced across 16,000-24,000 characters read about a quarter of the evidence, chosen by byte offset
rather than by relevance. When the contradicting document lands between sampled windows the recall
is gone before the model is consulted — `four_mid` at 0.04 is the source document sitting almost
exactly in a gap. Document alignment removes the variance entirely: 0.71-0.73 regardless of how
much evidence was retrieved.

### What the table says

- **The checkpoint is the dominant factor, not the plumbing.** No arm rescues the incumbent; every
  arm of the FEVER checkpoint clears the gate, including today's broken reducer.
- **The reducer fix is worth +0.125 recall at zero cost** (0.708 -> 0.833, same 338 passes). It is
  the best return in the table.
- **Document-aligned tiling IS worth a contract change — reversed on the longer corpus.** On the
  authored set it bought +0.042 for 2.15x the passes and the answer was no. On realistic premise
  lengths it buys **+0.337** (0.387 -> 0.724) for 4x, and it is the only configuration that clears
  the gate at all. The earlier "no" was an artifact of premises too short for the sampler to lose
  anything. Paragraph alignment reaches the same recall (0.733) for 11.6x, so the document is the
  right unit: nearly all the recall, a third of the cost.
- **The input format does not need to change either.** `concat` (what `classify.js` sends) and
  `pair` (the tokenizer's own pair path) both give 0.833 on the FEVER checkpoint — identical. The
  format matters slightly for the incumbent (0.594 vs 0.552) and it fails either way. So the
  MiniCheck-inherited string join can stay.
- **The out-of-scope classes hold.** `tool_fact` and `neutral_addition` fire at 0.00 — the scope in
  `labels.py` is doing what it claims, and this is the failure mode that made the old head fire on
  28% of production traffic.
- **Recall decays with premise size, gently.** 1.00 single-document, 0.71 at six. The bake-off's
  single-claim probe suggested distractors had stopped mattering; on the full set they still cost
  something, just far less than they cost the incumbent.

### What the table does NOT say

- **The gate is cleared on a point estimate, not at confidence.** The interval on 0.833 is
  [0.64, 0.93], whose lower bound is *below* the 0.70 gate. This set cannot establish that the bar
  is met; it can only say the bar is plausibly met and the incumbent plausibly cannot meet it.
- **The set is authored by one person, and that person wrote both the claims and the documents they
  contradict.** Partially addressed — see "Are the labels right?" below — but the claims themselves
  still come from one hand, and a model writing to a spec is not a production agent hallucinating.
- **The 8-chunk arm is untested by this data.** The largest premise here is 2,704 characters, which
  4 chunks already cover, so the arm is a no-op rather than a negative result. A set with
  6 x 4,000-character documents would be needed to move it.
- **This is the head and the tiling only.** Claim extraction and evidence selection are not
  exercised — see "Not yet built".

## Choosing a checkpoint: the licensing wall

Five three-way NLI checkpoints were screened on a single-claim probe that measures what actually
decides this classifier: **per-window separation** — the margin between the true contradiction and
the highest-scoring irrelevant passage from the same corpus.

| checkpoint | license | training data | margin | doc-aligned recall |
|---|---|---|---|---|
| `MoritzLaurer/DeBERTa-v3-large-mnli-fever-anli-ling-wanli` | MIT tag | MNLI, **ANLI (CC BY-NC 4.0)**, FEVER, LingNLI, WANLI | **+0.904** | **0.724** |
| `microsoft/deberta-large-mnli` | MIT | MNLI | +0.198 | 0.022 |
| `FacebookAI/roberta-large-mnli` | MIT | MNLI | +0.326 | not run |
| `cross-encoder/nli-deberta-v3-large` | Apache-2.0 | MNLI, SNLI | **-0.687 inverted** | not run |
| `facebook/bart-large-mnli` (incumbent) | MIT | MNLI | -0.423 inverted | 0.307 |

**Per-window precision is the property, not general NLI quality.** Document-aligned tiling scores 16
windows per observation instead of 4, so every window is another chance for a false fire. A model
with a small margin has its threshold forced up to hold the FP budget, and recall collapses — which
is why `deberta-large-mnli` scores 0.120 under byte tiling and **0.022** under document alignment.
Finer granularity only pays for a model that is precise per window, and that is what evidence-
verification training (FEVER) buys where plain MNLI training does not.

**Which is the problem.** Every FEVER-trained NLI checkpoint on the hub bundles ANLI, which is
**CC BY-NC 4.0**. The authors tag the weights MIT, and whether weights are a derivative work of
their training data is unsettled — but a commercial product resting on a third party's MIT tag over
non-commercial data is a risk taken knowingly, not a clean dependency. No permissively-trained
checkpoint screened here has the margin.

**The clean path, if one is wanted.** `tals/vitaminc` (VitaminC) is **CC BY-SA 3.0**, purpose-built
for evidence-based fact verification with contrastive pairs, and three-way
(supports / refutes / not-enough-info) — the same shape including the abstain. With MultiNLI
(CC BY 3.0 / MIT) it is a permissive fact-verification mix with no non-commercial term. Note this
is CC BY-SA, not Apache/CC0, so it sits outside the rule in `classifiers/README.md` ("only
Apache/CC0/owned data may ever be *trained* on") — adopting it means revisiting that rule
deliberately rather than quietly.

**Not an option, and worth recording why.** `lytang/MiniCheck-Flan-T5-Large` (MIT) is purpose-built
for grounding and much stronger than the MiniCheck-RoBERTa this classifier used to serve, but it is
BINARY. A binary support head collapses "the source does not mention it" into "not supported", which
is the exact failure that made the old head fire on 28% of production traffic. Three-way is a
requirement, not a preference.

## Owning the weights instead: the VitaminC fine-tune

`train.py` fine-tunes a permissive three-way NLI head on data with no non-commercial term, so the
checkpoint is ours and the licensing wall above stops being load-bearing.

| input | licence | why this one |
|---|---|---|
| base `cross-encoder/nli-deberta-v3-base` | Apache-2.0 (MultiNLI + SNLI) | already three-way, so the head transfers; no restrictive training data |
| data `tals/vitaminc` | **CC BY-SA 3.0** | purpose-built evidence verification, three-way, contrastive |

**Why VitaminC is the right data and not merely the available data.** It is built from contrastive
Wikipedia revisions: near-identical evidence where one figure changes and the label flips with it.
That is exactly the discrimination this detector lives on — "this passage contradicts the claim"
versus "this passage is about something else" — and it is what plain MNLI training never teaches,
at any model size. Its three labels (SUPPORTS / REFUTES / NOT ENOUGH INFO) map onto
entailment / contradiction / neutral, so the abstain survives; a binary fact-checking head cannot
express it, which is why `MiniCheck-Flan-T5-Large` is not a candidate however strong it is.

**A rule is being widened here, deliberately.** `classifiers/README.md` says only Apache/CC0/owned
data may ever be TRAINED on, and CC BY-SA 3.0 is none of those. It carries no non-commercial term,
which is the restriction that actually blocks the alternative, but share-alike is not nothing and
the rule should be amended openly rather than quietly stretched. Recorded here so the next reader
sees a decision rather than an oversight.

**The label map is derived from the base model's own `id2label`, never hardcoded**, and the run
fails if the base is not three-way. A retrain that permuted the classes would otherwise invert every
score while still looking valid — the same class of silent failure as the window reducer.

### Measured: does the permissive fine-tune work?

`separation.py` scores every positive claim against its source document and against each distractor
retrieved in the same conversation. **Hit rate** — the fraction of claims whose contradicted
document outranks EVERY distractor — is the headline, because document-aligned scoring reduces by
max-contradiction, so one inversion is a false fire no threshold can undo.

| checkpoint | hit rate | mean margin | median | p10 |
|---|---|---|---|---|
| `DeBERTa-v3-mnli-fever-anli` (NC data, reference) | 0.840 | 0.425 | 0.605 | -0.044 |
| `cross-encoder/nli-deberta-v3-base` (the base) | 0.260 | -0.185 | -0.080 | -0.966 |
| `vitaminc-25k` (ours, 25k rows, 1 epoch, laptop) | 0.660 | 0.148 | 0.106 | -0.104 |
| **`vitaminc-train-20260911` (ours, 370k rows, 2 epochs, A10G)** | **0.780** | **0.256** | 0.137 | -0.074 |

**The permissive recipe works, and it scales with data.** 24 minutes on 7% of VitaminC took a base
that is worse than useless (a distractor outranks the real contradicted document three times in
four) to 0.660. Full data for two epochs on an A10G reached **0.780** — within 0.060 of the
NC-licensed reference, which is also a size class LARGER (v3-large against our v3-base). Held-out
VitaminC: 0.902 accuracy, 0.888 contradiction precision, 0.897 recall.

The remaining gap is therefore plausibly model size rather than data or licence, which is a
testable claim and not a hopeful one.

**Not shippable yet.** 0.780 hit rate still sits below what the gate needs — the reference's 0.840
corresponded to 0.724 recall under document alignment — and p10 of -0.104 says the hard decile still
inverts. Every axis that usually matters (data volume, epochs, model size) is at its floor.

**Screen on hit rate, never on a single claim.** Early screening used one hand-built claim against
eight distractors and put this model's margin at +0.035 against the reference's +0.904, implying
near-total failure. Across 50 claims the real gap is 0.148 versus 0.425. One unlucky distractor
moves a single-claim margin by half; `separation.py` exists because that anecdote nearly cost a
correct decision.

**Known gap.** VitaminC evidence is short single sentences; the serving path scores premise windows
of ~1,700 characters. The FEVER checkpoint was also trained on short evidence and transferred
fine, so this is a risk rather than a defect, but it is the first thing to check if the fine-tune
screens well and then underperforms on the long-premise corpus.

## Serving the chosen checkpoint

`export_onnx.py` exports to the format classify-service runs; `parity_onnx.py` checks the export
scores the same as the torch checkpoint every recall figure here was measured on, and writes a
reference fixture for a serving-side test.

| runtime | max \|diff\| vs torch | mean | decision flips at the operating point |
|---|---|---|---|
| onnx fp32 | **0.0000** | 0.0000 | **0/40** |
| onnx int8 (dynamic, arm64) | 0.1156 | 0.0393 | **3/40** |

**The fp32 export is exact**, so every number in this file transfers to the served path. That was an
open risk and it is closed: the 0.297 divergence seen earlier between `facebook/bart-large-mnli` in
torch and `Xenova/bart-large-mnli` in ONNX was the input format plus a third-party conversion, not
ONNX itself.

**int8 is rejected on measurement.** It changes the detection decision on 7.5% of observations and
buys only 11% over fp32 ONNX (`cost.py`). A tenth of the runtime for a different detector is a bad
trade.

## What one observation costs

`cost.py`, 2 threads (the starter host in `docs/self-hosting/deployment.mdx` is 2 vCPU):

| runtime | tiling | passes/obs | sec/obs | sec per 1k obs |
|---|---|---|---|---|
| torch fp32 | byte | 4.0 | 1.861 | 1,861 |
| torch fp32 | document | 16.1 | 7.300 | 7,300 |
| onnx fp32 | byte | 4.0 | 1.148 | 1,148 |
| **onnx fp32** | **document** | **16.0** | **4.410** | **4,410** |
| onnx int8 | document | 16.0 | 3.969 | 3,969 |

ONNX buys 1.66x over torch, not the 2-4x folklore suggests. Document alignment is ~73 minutes of
continuous compute per 1,000 scored claims, so one classify-service task absorbs roughly 20k
claims/day. One observation is ONE claim, so a turn asserting several checkable sentences costs a
multiple of this. Measured on Apple Silicon threads; production is ARM64 Graviton, typically slower
per core, so treat this as optimistic.

## Is the evaluation flattering us? An audit

Asked directly, and the answer is: yes, in three measurable ways, one of which changes the
headline. Everything below is recorded in `data/groundedness/experiments.json` as `cv/*` rows.

**1. The threshold was fitted to the set it was scored on.** `operating_point_at_fixed_fp` picks
the lowest threshold whose FP rate stays under 2% — on the same data it then reports recall from.
With ~825 negative pairs that threshold is tuned to admit exactly the 16 false fires the test set
happens to permit. `cv_threshold.py` fits the threshold on held-out claims (5-fold, clustered by
claim) and re-reads the numbers:

| arm | in-sample recall | CV recall | CV fp | CV F1 | gate |
|---|---|---|---|---|---|
| serving-shape + decompose(40) | 0.679 | **0.662** | **0.023** | 0.781 | **fail** |
| serving-shape + decompose(25) | 0.637 | 0.631 | 0.029 | 0.754 | fail |

The recall optimism is modest (+0.017) but the FP rate slips over the ceiling once the threshold is
honest. **"Clears the gate" was an artifact of threshold selection.** The comparison between arms
was always fair — both were inflated the same way — but every absolute figure and every pass/fail
verdict above this section inherits the bias. Read them as upper bounds.

**2. The corpus has a lexical shortcut worth 0.28 recall.** A TF-IDF + logistic-regression model
reading the CLAIM ALONE — never seeing the source — reaches recall 0.284 at fp<=0.02. The generator
writes contradictions in a detectable style: `days, per, months, within, up to, fee` predict a
contradiction because the generator was told to "change a number or a deadline". Part of every
model's recall on this corpus may be style detection. The `--hard` generation mode (subtle
contradictions, heavily paraphrased support) was built to remove it, and it does: on the hard
held-out corpus the same claim-only shortcut reaches **0.051**. Model results on that corpus are
therefore results about contradiction detection, not about the generator's habits — and the drop
from the tuning corpus to the hard one is the honest size of the style effect.

**3. Every compound claim is a contradiction, by construction.** `and` is the single strongest
shortcut feature (+1.41), because `compound` is a positive kind and compounds always contain "and".
In production, plenty of two-clause sentences are fully supported. This specifically inflates the
decomposition result: it triggers on exactly the sentences that are always positive here.
`compound_support` (label 0, same shape) is the control, added after this audit. It works: on a
supplement balanced 12/12 between `compound` and `compound_support`, the `and` coefficient falls
from +1.41 to +0.17. Every later decomposition figure is measured against a corpus that contains it.

**Not fixed, stated:** the clause-length floor of 40 was chosen from {25, 40, 60} by looking at
test results, so it is a selection on test; the fresh-domain corpus is the held-out check. The
gate was lowered from 0.70 to 0.66 after observing the reference score 0.668 — a decision made
with the number in view. Generator and verifier are both Claude models and may share blind spots.
And a corpus where the contradicted document is always IN the premise says nothing about retrieval
failure, which is a different classifier's problem.

## Held-out results: the numbers that count

Everything above this line was measured on the corpus the configuration was tuned on, with the
threshold fitted to it. These are measured on corpora no decision was made against, with the
threshold cross-validated by claim. **They are the only figures in this file that should be quoted.**

All rows: plain document-aligned tiling, no decomposition, `reducer_fixed`. Gate: recall >= 0.66
at fp <= 0.02.

| model | licence | corpus | CV recall | CV fp | CV prec | CV F1 | gate |
|---|---|---|---|---|---|---|---|
| **ours, v3-base serving-shape** | **permissive** | fresh domains (92 pos) | **0.774** | 0.016 | 0.967 | **0.860** | **PASS** |
| ours, v3-large serving-shape | permissive | fresh domains | 0.737 | 0.020 | 0.958 | 0.833 | PASS |
| NC reference (v3-large, FEVER+ANLI) | non-commercial | fresh domains | 0.552 | 0.021 | 0.941 | 0.696 | fail |
| ours, v3-base + compound-mix 15% | permissive | fresh domains | 0.785 | 0.019 | 0.963 | 0.865 | PASS |
| **ours, v3-base serving-shape** | **permissive** | hard (79 pos) | **0.600** | 0.022 | 0.940 | 0.733 | fail |
| ours, v3-base + compound-mix 15% | permissive | hard | 0.582 | 0.018 | 0.950 | 0.722 | fail |
| ours, v3-large serving-shape | permissive | hard | 0.597 | 0.022 | 0.940 | 0.731 | fail |
| NC reference | non-commercial | hard | 0.281 | 0.025 | 0.867 | 0.424 | fail |

Paired comparisons (cluster bootstrap by claim, 2,000 rounds):

| comparison | corpus | difference | 95% CI | verdict |
|---|---|---|---|---|
| ours (base) vs NC reference | fresh | **+0.193** | [+0.085, +0.304] | **real** |
| ours (base) vs NC reference | hard | **+0.286** | [+0.142, +0.425] | **real** |
| v3-large vs v3-base (ours) | fresh | +0.029 | [-0.035, +0.093] | not distinguishable |
| v3-large vs v3-base (ours) | hard | +0.027 | [-0.081, +0.139] | not distinguishable |
| compound-mix vs v3-base (ours) | fresh | +0.045 | [-0.026, +0.120] | not distinguishable |
| compound-mix vs v3-base (ours) | hard | +0.034 | [-0.068, +0.139] | not distinguishable |
| compound-mix vs v3-base (ours) | compound control | -0.041 | [-0.167, +0.083] | not distinguishable |
| decompose(40) vs plain | fresh | -0.004 | [-0.093, +0.085] | not distinguishable |
| decompose(40) vs plain | compound control | -0.131 | [-0.292, +0.025] | not distinguishable, wrong sign |

**Three conclusions, each at 95% or explicitly not.**

1. **The permissive model beats the NC reference, significantly, on both held-out corpora.** The
   reference's edge on the tuning corpus was style detection: with the style tell removed its
   `contradiction` recall drops 0.76 -> 0.33 while ours holds at 0.70. The licensing wall this
   programme spent two days on was protecting a model that is worse.
2. **Scale buys nothing.** v3-large is indistinguishable from v3-base on both corpora (P=0.80 and
   0.67), costs 3x at inference, and carries more threshold optimism (+0.076 vs +0.011 on fresh
   domains). That run's cost is the price of knowing that. **v3-base is the model to ship.**
3. **Decomposition is withdrawn** (see below); the two-clause deficit it targeted is a training-data
   gap, and the compound-mix run addressed it at source — with a trade rather than a free gain.
   Compound recall rose on both corpora (**fresh 0.61 -> 0.83, hard 0.38 -> 0.51**), and plain
   `contradiction` recall fell (fresh 0.91 -> 0.83, hard 0.70 -> 0.68). Out-of-scope kinds stayed at
   fp <= 0.04. Net, overall recall moved +0.045 / +0.034 — the right direction on both corpora, not
   significant on either. It is the same architecture at the same inference cost, so it is a
   reasonable default over the plain serving-shape model if two-clause claims matter in production;
   it is not a demonstrated improvement, and it costs something on single-clause contradictions.

**Programme status (2026-09-15).** Every planned experiment has run and been recorded. Final GPU
The evaluation harness (`cv_threshold`, `paired`, held-out corpora,
`compound_support` control, `--hard` generation) is the durable output alongside the model.

**Where the pass is and is not.** On contradictions written the way a generator naturally writes
them, the model clears the gate with margin. On contradictions rewritten to be subtle, it does
not (0.600). Production sits between those; the hard corpus is the bound to design against.

**Decomposition is withdrawn.** It showed +0.042 on the tuning corpus (paired P=93%) and the
audit predicted why: every compound claim there is a contradiction, and "and" was the strongest
shortcut feature. On held-out fresh domains the paired difference is −0.004 (P(better) = 45.6%,
McNemar 47 vs 50), and on the balanced compound control it HURTS: −0.131 (P(better) = 4.8%,
McNemar 4 vs 20). The `VerifiableClaims` change it seemed to justify should not be made. This is
what a held-out set is for, and it is the second lever this session that a control has killed.

**The compound control is small** (24 positives) and its recall of 0.492 says compound claims are
genuinely harder for the model — a real deficit, just not one decomposition fixes. `compound_support`
fires at 0.00, so the model is not mistaking supported two-clause sentences for contradictions.

**The reference on the same fresh-domain corpus: CV recall 0.552, fp 0.021, F1 0.696 — fail.**
So 0.774 is not an easier corpus; it is a better model on it. Paired by claim (cluster bootstrap,
2000 rounds): our model beats the NC reference by **0.193 recall, 95% CI [0.085, 0.304],
P(reference better) = 0.0%**, McNemar 121 vs 31. This is the first model comparison in this
programme that is statistically significant, and it goes the right way: the permissively-licensed
model we own outperforms the non-commercially-trained one we could not ship.

**Caveats that survive.** 92 positive claims gives an interval of roughly ±0.09 around 0.774, so
the pass is comfortable on recall and narrower on FP (0.016 against a 0.020 ceiling).
**The hard corpus is the honest lower bound — and it is where the reference falls apart.** With
the style tell removed (claim-only shortcut 0.051), our configuration scores 0.600, under the bar.
The NC reference on the same corpus scores **0.281** (fp 0.025, F1 0.424): its `contradiction`
recall drops from 0.76 on the tuning corpus to **0.33**. Paired by claim, ours beats it by
**0.286 recall, 95% CI [0.142, 0.425], P(reference better) = 0.0%**, McNemar 159 vs 44.

Read together with the tuning-corpus numbers (reference 0.668, ours 0.662), this says the
reference's apparent parity was largely **style detection**: it was reading the generator's habits,
and when those were removed it lost more than half its recall. Ours lost a tenth. Serving-shape
training on VitaminC produced a model that is robust to the tell in a way the FEVER+ANLI model is
not — which is a stronger claim than "as good as the reference", and it is the claim the licensing
question turned on. The truth for production lies between
0.600 and 0.774 and depends on how subtle real contradictions are. What is consistent across ALL
three held-out sets is the shape of the deficit: plain `contradiction` reaches 0.70 even on the
hard set, while `compound` sits at 0.38-0.48. VitaminC contains no two-clause claims, so the model
has never been trained on one. That is a training-data gap with a cheap fix, not a modelling one.

## External validity: the first corpora no Claude model wrote

Everything above — the tuning corpus, both held-out sets, the `--hard` set — was generated by one
Claude model and verified by another. That controls for a generator's habits; it cannot control for
what two language models writing to a spec both fail to imagine. Two external corpora close that gap,
one of them human-labelled.

**RAGTruth** (Niu et al., MIT). 17,790 responses from six production LLMs over real retrieved
context, span-annotated by human annotators. Loader: `ragtruth_pairs.py`. Summary and QA only
(`Data2txt` renders JSON, which the catalog withdraws as evidence); one pair per response sentence;
positive iff a *Conflict* span overlaps it; *Baseless* spans are negatives, because `labels.py` puts
unsupported additions out of scope and this is the first corpus where humans drew that line. Test
split: 1,775 responses, 9,540 sentences, **143 conflicts (1.5% prevalence)**, 136 of them "Evident".

**RAGBench** (Galileo, CC BY 4.0). Broad (12 source domains) but labelled by GPT-4o, so it is the
same kind of evidence as our generated corpora from a different model family. Its positive class is
"not fully supported", which merges conflict and baseless — and is so strict that 73% of all
sentences carry it. Loader: `ragbench_pairs.py`; the `strict` view keeps only contradictions,
confirmed by the independent judge from `verify_corpus` over the 225 sentences whose GPT-4o
explanation used conflict wording (`ragbench_relabel.py`; 39 confirmed — the keyword filter alone
was 17% precise). 9 prose configs, 39 positives, 3,203 supported negatives.

Thresholds cross-validated by response (`cluster` field in the dumps; `cv_threshold` and `paired`
read it and fall back to the five-shapes-per-claim rule for the generated corpora).

| corpus | model | recall @ fp ≤ 2% (CV) | 95% CI | prec | F1 | AUC |
|---|---|---|---|---|---|---|
| RAGTruth test | claim-only shortcut (no premise) | 0.077 | | | | |
| RAGTruth test | v3-base serving-shape (031747) | **0.042** | [0.02, 0.09] | 0.031 | 0.036 | 0.59 |
| RAGTruth test | compound-mix (063928) | 0.056 | [0.03, 0.11] | 0.041 | 0.047 | |
| RAGTruth test | NC reference (v3-large FEVER/ANLI) | 0.049 | [0.02, 0.10] | 0.036 | | 0.67 |
| RAGTruth test | 031747, 800-char windows | 0.056 | | | | |
| RAGTruth test | 031747, 400-char windows (3× passes) | 0.063 | | | | |
| RAGTruth test | **+ RAGTruth-train fine-tune (134832)** | **0.147** | [0.10, 0.21] | 0.102 | 0.120 | 0.77 |
| RAGTruth test | + heavier mix (165814: 3 ep, conflicts ×8, VitaminC 10k) | 0.147 | [0.10, 0.21] | 0.100 | 0.119 | 0.76 |
| RAGBench strict | 031747 | 0.128 | [0.06, 0.27] | 0.074 | 0.093 | |
| RAGBench strict | NC reference | 0.179 | [0.09, 0.33] | 0.099 | 0.127 | |

**What this says.** On the first human-labelled corpus the serving-shape model is at chance, and so
is the reference: the "base beats reference" result above held only on Claude-generated data. Every
optimism control passes (CV optimism +0.000; the claim-only shortcut is 0.077) — the number is not an
artefact of the rig. The **oracle** run (a one-off script, not in the repo; recorded in the ledger as
`oracle/cited-source-sentence`) hands the model the annotator's own cited source sentence instead of
the article window, i.e. perfect retrieval: base reaches 0.176, the reference 0.321. So the loss is
not in finding the passage — the window ladder (1,800 → 800 → 400 chars: 0.042 → 0.056 → 0.063)
confirms that — it is in what the model thinks a contradiction is. VitaminC teaches "a number or name
was swapped in a Wikipedia sentence"; production LLMs do something else to a news article ("denied
bond" for "bond set at $10,000"; the victim was the former student when it was the suspect).

**The one lever that moved.** Continuing 031747 on RAGTruth's *train* split
(`ragtruth_train.py`: 1,162 human-labelled conflict sentences paired with the serving-shape window
they conflict with, ×4, plus 8k SUPPORTS / 8k NEI and a 20k VitaminC replay; 1 epoch, on
ml.g5.xlarge) took RAGTruth recall from 0.042 to **0.147** (paired: +0.105, CI [+0.058, +0.156],
McNemar 15:0) and AUC from 0.59 to 0.77 — and did not cost the generated corpora (fresh domains
0.774 → 0.878 CV; hard 0.600 → 0.463 at a stricter fp of 0.013). It is a real, licensable gain, and
it is nowhere near the 0.66 bar.

**And it does not scale.** Tripling the epochs and doubling the conflict weight (165814)
landed on the identical 0.147 (paired vs 134832: −0.001, CI [−0.050, +0.046], McNemar 6:6) with a
slightly lower AUC — while the generated held-outs fell (fresh domains 0.878 → 0.737 CV, hard
0.463 → 0.385). The in-domain signal this architecture can absorb from 1,162 windowed conflicts is
exhausted at one epoch; pushing harder only trades away what VitaminC taught. That is the plateau of
the lineage, measured, not inferred.

**Precision at realistic prevalence.** At 1.5% prevalence a 2% FP budget on 9,397 negatives is ~188
false fires against at most 143 true ones: precision cannot exceed ~0.43 even at perfect recall, and
at the measured recall it is 0.10. That is what a deployment with realistic traffic will see, and any
published number has to say so.

**Where this leaves the approach.** Three configurations of a 512-token NLI cross-encoder — ours, the
reference, and ours with in-domain fine-tuning — score 0.04–0.15 on the corpus that counts. Encoder
classifiers are not the wrong tool for this task in general: published token-level long-context
encoders trained on RAGTruth report ~0.79 F1 on it. What they have and this lineage does not is
(a) training on real hallucinations from the start rather than as a late mix-in, and (b) the whole
context in one pass rather than 1,800-character windows. The decision — more in-domain training on
this architecture, a long-context token classifier, an LLM judge, or the classifier as a cheap first
pass in front of a judge — is recorded in the programme close-out; the numbers here are the input to
it.

## The long-context token classifier — and what the published models say

After the pair-head lineage plateaued at 0.15, the next architecture was the one the strongest
small RAGTruth models use: an 8k-context encoder (`answerdotai/ModernBERT-base`, Apache-2.0) that
reads the whole context and the whole response in ONE pass and labels every response token as
O / BASELESS / CONFLICT, trained only on RAGTruth's human-labelled train split. Modules:
`token_data.py` (examples + label alignment), `token_train.py` (Trainer, NaN guard, class weight,
Data2txt and generated-corpus mix-ins), `token_eval.py` (scores it on the SAME sentences, metric and
CV folds as the pair head; one pass per response instead of ~3.5 per sentence). Trained on SageMaker
(torch 2.5.1 — the 2.3 image produced NaN logits under bf16 SDPA from step 1) and, as a credits-only
replica, on the Azure ML `cpu64` cluster (`az_submit.py`; ~7 h on 64 vCPU vs 35 min on an A10).

| run | training | RAGTruth CV recall @ fp ≤ 2% | prec | F1 | AUC | fresh domains | hard |
|---|---|---|---|---|---|---|---|
| pair head, best (134832) | VitaminC + RAGTruth mix | 0.147 | 0.102 | 0.120 | 0.77 | 0.878 | 0.463 |
| token #1 (102442) | RAGTruth, weight 1 | **0.196** | 0.133 | 0.158 | 0.775 | 0.054 | 0.033 |
| token #2 (130628) | + CONFLICT loss weight ×10 | 0.168 | 0.115 | 0.136 | | 0.013 | 0.010 |
| token #3 (141549) | + Data2txt conflicts + generated corpora ×3 | 0.210 | 0.140 | 0.168 | | 0.013 | 0.122 |
| token #3, unsupported/sentence | same model, contract 2 | 0.470 | 0.614 | 0.533 | | | |
| **token #4 (az-170521)** | generated ×1, 8k context, tool_fact→BASELESS; Azure CPU, credits | **0.231** | 0.150 | 0.182 | | 0.235 | 0.144 |
| token #4, unsupported/sentence | same model, contract 2 | **0.502** | 0.635 | 0.561 | | 0.167 | 0.172 |
| token #4, unsupported/response | same model, contract 2 | **0.379** (best-F1 0.608) | 0.821 | 0.519 | | | |
| **token #5 (102552)** | lettucedetect-large base, its template, run-#4 recipe; A10 | **0.238** | 0.154 | 0.187 | | **0.630** | 0.132 |
| token #5, unsupported/sentence | same model, contract 2 | **0.559** | 0.651 | 0.602 | | **0.573** | 0.051 |
| token #5, unsupported/response | same model, contract 2 | **0.390** (best-F1 **0.664**) | 0.826 | 0.530 | | | |

Paired, #2 vs #1: −0.02, CI [−0.07, +0.03] — the class weight did nothing but hurt the generated
corpora. Paired, #3 vs #1: conflict/sentence +0.02 [−0.04, +0.09], unsupported/sentence −0.02
[−0.05, +0.01], unsupported/response +0.02 [−0.02, +0.05] — a wash on the human data. On the
generated held-outs #3 learned the corpora's STYLE, not their evidence: `support` sentences moved
from 0.05 to 0.72 P(CONFLICT) (a product-style claim over a long premise is a contradiction 40% of
the time in those corpora, whole-sentence-labelled), and `tool_fact` from 0.91 to 0.08 P(unsupported)
because the training label map said O while the contract-2 evaluation says positive — a bug, fixed
(`token_data._KIND_LABEL`). #4 is the corrected, ×1, untruncated (8k) retry on credits. The token model learned RAGTruth's news/QA and nothing about our own domain (0.05 on the
generated sets the pair head passes at 0.88); #3 mixes the generated tuning corpora in for that
reason.

**#4 is the first run that moved everything at once**: on the human data +0.04 conflict/sentence and
+0.08 unsupported/response over #1, and on our own held-outs conflict/sentence 0.054 → 0.235 (fresh) and
0.033 → 0.144 (hard), with the best-F1 operating point now at a *real* threshold (0.99 / 0.59) rather
than the 0.01 that meant "flag everything". What changed from #3: the generated corpora at ×1 instead
of ×3, the tool-fact label made consistent with the contract, and 8k context so no document is
truncated. Trained on the Azure `cpu64` cluster in 15.2 h on sponsorship credits.

**#5 = the published best + our recipe, and it holds both.** Starting from `lettucedetect-large`
(its 2-label head re-initialised to our three, its own input template — `token_data.lettuce_prompt`,
recorded in provenance so serving cannot drift), trained with #4's data: on RAGTruth it matches the
published checkpoint on contract 2 (0.559 unsupported/sentence; +0.053 over #4, CI [+0.02, +0.08])
and edges it at the response level (best-F1 0.664 vs 0.661); on our fresh-domain held-out it goes
from 0.25 to **0.63** conflict/sentence and 0.17 to 0.57 unsupported/sentence (CI [+0.32, +0.46] and
[+0.37, +0.47]) — the first run to approach the old 0.66 bar anywhere. The one place it is worse is
the `hard` set under contract 2 (0.025 vs 0.171): its `support` claims — written to defeat style
cues — score 0.59 mean / 1.00 p90 "unsupported", so at 2% FP the threshold sits at ~1.0. That set
is held out and cannot be trained on; a tuning corpus of the same construction is the obvious next
data ask.

**Calibration against the published state of the art.** Both LettuceDetect checkpoints re-scored in
their OWN input template (numbered passages + question / summary template + whole answer;
a one-off evaluation script, not in the repo): the format fix moved base by ≤1 point, so the earlier numbers
were not a formatting artefact. `lettucedetect-large` (MIT) is the strongest published checkpoint on
contract 2 — unsupported/sentence 0.559 @ fp ≤ 2% (prec 0.66), unsupported/response 0.404 (prec 0.84,
best-F1 0.661) — at 3× base's serving cost; on our generated held-outs it collapses exactly as ours
did before #4 (base: 0.042 / 0.014). FactCG-DeBERTa-v3-Large (MIT; 82.3 balanced accuracy on RAGTruth
per LLM-AggreFact) does NOT reproduce from its public checkpoint: 0.52–0.53 balanced accuracy on our
split scored the leaderboard's way, in both input orders, head confirmed loaded — dropped. `KRLabsOrg/lettucedect-base-modernbert-en-v1`
(MIT; ModernBERT-base trained on RAGTruth by its authors, reported ~0.79 example-level F1) scored on
our exact metric (one-off script, not in the repo), ledger row `calibration/lettucedetect-…`:

| target, RAGTruth Summary+QA test | ours (#1) | LettuceDetect |
|---|---|---|
| sentence-level, conflict-only, recall @ fp ≤ 2% (our gate; baseless = negative) | 0.196 | 0.056 |
| same, baseless sentences excluded from the negatives | | 0.147 |
| response-level "any hallucination" (conflict or baseless), best F1 — the published target | 0.605 | 0.589 |

Two things follow, and they are the programme's conclusion so far:

1. **Our training is at parity with the published model** on its own target (0.605 vs 0.589; the
   published 0.79 includes the Data2txt task, which is not in scope here), and ahead on ours. The
   modelling is not the deficit.
2. **No known model of this class reaches the contract.** The bar — 0.66 sentence recall at 2% false
   alarms on conflict-only, human-labelled data, with baseless additions counted as negatives — is
   not met by the best published small model either; it fires on baseless sentences at mean
   probability 0.67, which is exactly the class this classifier promised not to flag, and spends its
   FP budget there. What the literature reports as "0.79" is a different, easier question
   (is anything in this response unsupported?) at a different granularity.

**The contract and its gate (decided 2026-09-18).** The project moved to the response-level
"unsupported" contract (`labels.UNSUPPORTED_SPEC`), and its ship bar is `labels.UNSUPPORTED_GATE`,
two parts that must both hold on RAGTruth test, response-level, thresholds cross-validated by
response:

| part | rule | why | run #5 |
|---|---|---|---|
| B — a case that opens is usually right | recall ≥ 0.35 at fp ≤ 2% **and** precision ≥ 0.80 | one false case per fifty clean responses, four of five opened cases real — the property a user feels | 0.354 / fp 0.018 / 0.832 → **pass, no margin** |
| A — at parity with the field | best-F1 ≥ 0.65 | the operating point papers report, so a reader can check it against the published models | 0.664 → pass |

B is judged on the pooled held-out fp rate with fold thresholds fitted a guard band (0.25 pp) under
2% — fitted at exactly 2%, the held-out rate straddles the line (1.9-2.1% depending on which 28
negatives a fold holds) at recall 0.39, and a gate must not turn on that coin. The price of the
guard is ~3.5 points of recall. Run #4 (0.346) fails B by the same hair that #5 passes it, which is
the honest reading: the current model is *at* the bar. The stated next target — not the bar — is
0.50 recall at 2% FP, which needs per-domain calibration and a hard-set tuning corpus. An LLM judge
is out of scope.

## Serving the token head (done 2026-09-18)

The evaluated model is the served model, and each link in that chain is pinned:

| step | where | pin |
|---|---|---|
| Checkpoint | `artifacts/groundedness/groundedness-token-train-20260917-102552` (run #5); model card in its `README.md`; published as `tessaryai/groundedness-token-v1` @ `6746fa25` (public since 2026-09-21, MIT) | `training_provenance.json` records base, data, `template: lettuce` |
| ONNX export | `onnx/model.onnx`, fp32, 1.58 GB, opset 17, eager attention (optimum's exporter is incompatible with torch 2.14; `torch.onnx.export` directly) | torch vs onnxruntime on 40 RAGTruth responses: max abs Δp 2.6e-5, response decisions 40/40; 0.42 s/response onnxruntime on M-series |
| Encoding | `classify-service/groundedness.js` builds `[CLS] ctx [SEP] ans [SEP]` by hand (transformers.js has no only-first truncation, no offsets), context laid out by `token_data.lettuce_prompt` | `tests/test_groundedness_token_head.py` runs the JS under node against the checkpoint tokenizer: ids and sentence token ranges equal Python's, including a truncated 8,192-token case |
| Served scores | transformers.js `AutoModelForTokenClassification` over the ONNX | the two smoke responses score identically to `token_eval` to six decimals (0.999434 / 0.084706 / 0.000187 / 0.000061) |
| Wire | `GroundingEvidenceReads.Evidence(documents, …)`: one entry per retrieved row, `text()` derived for the pair-era caller | backend `test-compile` green |
| Catalog | `BuiltInClassifierCatalog` groundedness v5: contract "unsupported", band `threshold_high 0.975` (2% FP, recall 0.41, precision 0.83) / `threshold_low 0.5` (F1-optimal 0.66) on P(unsupported) | `check-classifier-quality-doc.sh` marker updated; the paid quality page must be re-pinned |

Request/response contract and the reasoning for a passages LIST are in `classify-service/README.md`
("Token head"). Done since (2026-09-21, in the open tree): the detector builds `responses` and must
move to `responses` (passages = `Evidence.documents()`, question = the user turn, answer = the
assistant turn); `models.json` in the paid manifest needs the new entry (`dtype: fp32`, no
`model_max_length` clamp for this head); the paid quality page's pins.

## Are the labels right?

Every number above rests on the authored labels being correct, and the author checking their own
work is not evidence. `judge_labels.py` relabels all 52 claims with an independent judge (the
logged-in `claude` CLI, no API key) and measures Cohen's kappa through `framework/agreement.py`:

```
cohen_kappa[authored|judge]=1.000 (reliable)  raw=1.000  items=52  annotators=2

agreement by kind:
  compound 8/8   contradiction 16/16   neutral_addition 8/8   support 12/12   tool_fact 8/8
```

Perfect agreement, including on the two out-of-scope kinds where a general reader is most likely to
diverge — `neutral_addition` and `tool_fact` are exactly the claims that read as "wrong" unless you
know the classifier fires on contradiction only.

**What that does and does not establish.** The judge was given `labels.SPEC`, which spells out the
tricky cases in as many words ("a claim the source is SILENT on is NOT contradicted"). So this
confirms the labels are applied **consistently with the stated policy**. It is not independent
evidence that the *policy* is right, and it is not a second human. A kappa of 1.000 also says the
set is unambiguous, which is a property of authored data and not of production traffic.

## Not yet built

An explicit list, so the next person does not rediscover gaps already known:

- **Coverage is unmeasured and unreported.** Nothing counts how many observations reach the head at
  all, versus abstaining for want of evidence, for want of a verifiable claim, or for call-site
  shape. "Why does this never fire" is the first question an operator asks and there is no
  instrument that answers it. Needs the Java side.
- **Claim extraction is not exercised.** `VerifiableClaims` decides which sentences get scored;
  this module hands the head one sentence at a time. The `compound` kind measures the headroom, not
  the shipped behaviour.
- **Evidence selection is not exercised.** Production picks the nearest prior trace in the
  conversation that retrieved anything. A topic shift hands the answer the wrong premise, and a
  contradiction found against the wrong document is a confident, specific, wrong finding. Nothing
  here measures how often that happens.
- **Document-aligned tiling is measured, decided, and NOT reachable.** It is the only configuration
  that clears the gate on realistic premises, and production cannot do it today:
  `SubstrateReadRepository` joins evidence rows with a bare `"\n"`, so document boundaries are
  destroyed before they reach the wire. `Evidence` would have to carry a list instead of a
  concatenated string, and `/classify` would need a pair shape that accepts one. That is the single
  highest-value open item in this module.
- **No multimodal evidence.** `retrieved_doc` rows with empty `content` are filtered out, so
  image- or chart-backed answers abstain silently. Every premise here is text.
- **English only.** The candidate checkpoints are MNLI-family. Non-English traffic is unmeasured.
- **The catalog band is not re-derived here.** `eval_ablations.py` chooses a threshold at the gate's
  FP budget; nothing yet writes that back to `BuiltInClassifierCatalog`, and
  `scripts/check-classifier-quality-doc.sh` would need the quality page to pin it against.
