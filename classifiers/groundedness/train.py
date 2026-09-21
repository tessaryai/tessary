# SPDX-License-Identifier: Apache-2.0
"""Fine-tune a permissively-licensed three-way NLI head on VitaminC.

WHY THIS EXISTS. Screening five off-the-shelf checkpoints found exactly one with the per-window
precision this classifier needs, and it was trained on `facebook/anli` (CC BY-NC 4.0). Every
permissively-licensed alternative scored a margin too small to survive document-aligned tiling,
where 16 windows per observation means 16 chances to fire wrongly. So the choice is to depend on
someone else's licence tag over non-commercial data, or to own the weights. This is the second.

WHY VITAMINC IS THE RIGHT DATA, not merely the available data. It is built from contrastive
Wikipedia revisions: near-identical evidence where one figure changes and the label flips with it.
That is the exact discrimination plain MNLI never teaches and the one this detector lives or dies
on — telling "this passage contradicts the claim" from "this passage is about something else".
Three-way (SUPPORTS / REFUTES / NOT ENOUGH INFO) maps onto entailment / contradiction / neutral, so
the abstain survives, which a binary fact-checking head cannot express.

LICENCE POSTURE, STATED RATHER THAN ASSUMED. VitaminC is CC BY-SA 3.0 and MultiNLI is CC BY 3.0 /
MIT. Neither carries a non-commercial term. CC BY-SA is NOT in the set `classifiers/README.md`
permits for training ("only Apache/CC0/owned data may ever be trained on"), so using it is a
deliberate widening of that rule and needs to be recorded there, not slipped in here.

THE LABEL MAP IS DERIVED, NEVER HARDCODED. A base checkpoint's class order is its own business, and
a retrain that silently permuted the classes would invert every score while still looking valid.
The mapping is read from the base model's own `id2label`, and the run fails if it is not three-way.

Run (MPS on this dev box; a real run belongs on a GPU):
    uv run --extra train python -m groundedness.train --subsample 60000
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

ARTIFACTS = Path("artifacts/groundedness")

#: Apache-2.0, trained on MultiNLI + SNLI only. Chosen as the INIT rather than the answer: it
#: already speaks three-way NLI (so the head transfers) and carries no restrictive training data.
#: Its large sibling scored an INVERTED margin on the screening probe, which is the gap VitaminC is
#: meant to close — starting from a weak-but-clean base is the point, not an oversight.
DEFAULT_BASE = "cross-encoder/nli-deberta-v3-base"

#: VitaminC's three labels, in the vocabulary the base model's `id2label` uses.
VITC_TO_NLI = {
    "SUPPORTS": "entailment",
    "REFUTES": "contradiction",
    "NOT ENOUGH INFO": "neutral",
}


def synthesise_compounds(train, fraction: float, seed: int):
    """Replace `fraction` of rows with two-clause claims built from same-page pairs."""
    import random
    from collections import defaultdict

    from datasets import Dataset

    rng = random.Random(seed)
    rows = train.to_list()
    by_page = defaultdict(lambda: defaultdict(list))
    for r in rows:
        by_page[r["page"]][r["label"]].append(r)

    n_target = int(len(rows) * fraction)
    made = []
    pages = [p for p, d in by_page.items() if len(d["SUPPORTS"]) >= 1]
    rng.shuffle(pages)
    for page in pages:
        if len(made) >= n_target:
            break
        d = by_page[page]
        sup = d["SUPPORTS"]
        # one false clause -> REFUTES; both true -> SUPPORTS. Whichever label is behind is made
        # next, so the mix stays balanced even though many pages carry no REFUTES row. Order
        # rotates so "false clause second" is not a position tell.
        n_ref = sum(1 for m in made if m["label"] == "REFUTES")
        want_refutes = n_ref <= len(made) - n_ref
        if want_refutes and d["REFUTES"]:
            a, b = rng.choice(d["REFUTES"]), rng.choice(sup)
            label = "REFUTES"
        elif len(sup) >= 2:
            a, b = rng.sample(sup, 2)
            label = "SUPPORTS"
        elif d["REFUTES"]:
            a, b = rng.choice(d["REFUTES"]), rng.choice(sup)
            label = "REFUTES"
        else:
            continue
        if len(made) % 4 in (1, 2):
            a, b = b, a
        # No case-folding of the second clause: it starts with a proper noun often enough that
        # lowercasing corrupts the entity ("ed Westwick"). A capital after "and" is the lesser evil.
        claim = f"{a['claim'].rstrip('. ')} and {b['claim'].rstrip('. ')}."
        made.append({**a, "claim": claim, "evidence": a["evidence"] + " " + b["evidence"], "label": label})

    keep = rows[: len(rows) - len(made)]
    out = keep + made
    rng.shuffle(out)
    n_ref = sum(1 for m in made if m["label"] == "REFUTES")
    print(f"synthesised {len(made)} compound rows ({n_ref} REFUTES, {len(made) - n_ref} SUPPORTS), "
          f"replacing {len(made)} of {len(rows)}", flush=True)
    return Dataset.from_list(out)


def label_map(model) -> dict[str, int]:
    """VitaminC label -> the base model's own class index. Derived, so a permuted head fails loudly."""
    by_name = {v.lower(): int(k) for k, v in model.config.id2label.items()}
    if len(by_name) != 3 or not {"entailment", "neutral", "contradiction"} <= set(by_name):
        raise ValueError(
            f"base is not three-way NLI: id2label={model.config.id2label}. A binary head cannot "
            "express the abstain this classifier depends on."
        )
    return {vit: by_name[nli] for vit, nli in VITC_TO_NLI.items()}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--out", default=None)
    ap.add_argument("--subsample", type=int, default=25000, help="train rows (0 = all 370k)")
    ap.add_argument("--eval-rows", type=int, default=4000)
    ap.add_argument("--epochs", type=float, default=1.0)
    ap.add_argument("--batch", type=int, default=8, help="per-device; effective batch is this x accum")
    ap.add_argument("--accum", type=int, default=2, help="gradient accumulation steps")
    ap.add_argument("--save-steps", type=int, default=100)
    ap.add_argument("--resume", action="store_true", help="continue from the last checkpoint in --out")
    ap.add_argument("--lr", type=float, default=2e-5)
    ap.add_argument("--max-length", type=int, default=256)
    ap.add_argument("--seed", type=int, default=17)
    # The two levers that fit a 435M-param model in the A10G's 24GB. v3-large OOM'd at batch 16 x
    # accum 2 in fp32; the frustration precedent hit the identical wall and solved it the same way.
    # bf16 is native on A10G (Ampere), so it halves activation memory AND runs faster, unlike fp16
    # which would need loss scaling.
    ap.add_argument("--bf16", action="store_true")
    ap.add_argument("--grad-checkpoint", action="store_true",
                    help="trade ~25% speed for a large cut in activation memory")
    #: TRAIN ON THE SERVING SHAPE. VitaminC evidence is one short sentence; the serving path scores a
    #: claim against a ~1,700-char window of multi-topic retrieved prose. The model has therefore
    #: never seen the input it is asked to score, and the symptom fits: our v3-large RANKS better
    #: than the NC reference (separation hit rate 0.880 vs 0.840) while THRESHOLDING far worse
    #: (recall 0.404 vs 0.668, its threshold at 0.187 against the reference's 0.912). A score
    #: distribution fitted to short single-topic premises need not transfer to long multi-topic ones.
    #: With N>1 each row's true evidence is padded with evidence sentences from OTHER rows, so the
    #: premise becomes multi-topic while the label is unchanged — the true evidence is still in it.
    ap.add_argument("--premise-docs", type=int, default=1,
                    help="evidence sentences per premise; >1 simulates a multi-document premise")
    #: SYNTHESISE TWO-CLAUSE CLAIMS. Across every held-out corpus the deficit has the same shape:
    #: single contradictions reach ~0.70 recall, two-clause claims ("X and Y", one clause false)
    #: sit at 0.38-0.48. VitaminC has no such rows, so the model has never seen one. Claim-level
    #: decomposition at inference was tried and falsified on held-out data. This addresses it at
    #: training time instead, from VitaminC alone: a REFUTES row joined with a SUPPORTS row from
    #: the same page becomes a REFUTES compound (one clause false); two SUPPORTS rows become a
    #: SUPPORTS compound; the evidences are concatenated. Fully programmatic, no generator, same
    #: CC BY-SA licence.
    ap.add_argument("--ragtruth-repeat", type=int, default=0,
                    help="mix in RAGTruth train rows (groundedness.ragtruth_train); REFUTES rows repeated N times")
    ap.add_argument("--ragtruth-neg", type=int, default=8000,
                    help="SUPPORTS and NOT ENOUGH INFO rows kept from RAGTruth, each")
    ap.add_argument("--compound-mix", type=float, default=0.0,
                    help="fraction of train rows to REPLACE with synthesised two-clause claims")
    args = ap.parse_args()

    import numpy as np
    import torch
    from datasets import load_dataset
    from transformers import (
        AutoModelForSequenceClassification,
        AutoTokenizer,
        DataCollatorWithPadding,
        Trainer,
        TrainingArguments,
    )

    out = Path(args.out) if args.out else ARTIFACTS / "vitaminc-deberta-v3-base"
    device = "mps" if torch.backends.mps.is_available() else "cpu"
    print(f"base: {args.base}\ndevice: {device}\nout: {out}\n")

    tok = AutoTokenizer.from_pretrained(args.base)
    model = AutoModelForSequenceClassification.from_pretrained(args.base)
    mapping = label_map(model)
    print(f"label map (VitaminC -> base class index): {mapping}\n")

    ds = load_dataset("tals/vitaminc")
    train = ds["train"].shuffle(seed=args.seed)
    if args.subsample:
        train = train.select(range(min(args.subsample, len(train))))
    if args.compound_mix > 0:
        train = synthesise_compounds(train, args.compound_mix, args.seed)
    val = ds["validation"].shuffle(seed=args.seed).select(range(min(args.eval_rows, len(ds["validation"]))))

    # Human-labelled, in-domain rows. They already carry a serving-shape premise (a document window),
    # so the --premise-docs padding below must NOT be applied to them: `realistic` marks them.
    train = train.add_column("realistic", [False] * len(train))
    if args.ragtruth_repeat > 0:
        import random

        from datasets import Dataset, concatenate_datasets

        from .ragtruth_train import build

        rt = build()
        rng = random.Random(args.seed)
        by = {k: [r for r in rt if r["label"] == k] for k in ("REFUTES", "SUPPORTS", "NOT ENOUGH INFO")}
        picked = by["REFUTES"] * args.ragtruth_repeat
        for k in ("SUPPORTS", "NOT ENOUGH INFO"):
            picked += rng.sample(by[k], min(args.ragtruth_neg, len(by[k])))
        rng.shuffle(picked)
        extra = Dataset.from_dict({
            "evidence": [r["evidence"] for r in picked], "claim": [r["claim"] for r in picked],
            "label": [r["label"] for r in picked], "realistic": [True] * len(picked),
        })
        keep_cols = ["evidence", "claim", "label", "realistic"]
        train = train.remove_columns([c for c in train.column_names if c not in keep_cols])
        train = concatenate_datasets([train.cast(extra.features), extra]).shuffle(seed=args.seed)
        print(f"ragtruth rows mixed in: {len(picked)} "
              f"(REFUTES {len(by['REFUTES'])} x{args.ragtruth_repeat}, {args.ragtruth_neg} SUPPORTS, "
              f"{args.ragtruth_neg} NEI)\n")

    # Distractor pool for --premise-docs: evidence sentences drawn from the shuffled train split.
    # Deterministic (seeded shuffle, index arithmetic) so a rerun trains on the same premises, and
    # drawn from rows OTHER than the one being encoded so the padding is genuinely off-topic.
    pool = list(train["evidence"]) if args.premise_docs > 1 else []

    def encode(batch, idxs=None):
        premises = batch["evidence"]
        realistic = batch.get("realistic") or [False] * len(premises)
        if args.premise_docs > 1 and pool:
            built = []
            for j, ev in enumerate(premises):
                if realistic[j]:
                    built.append(ev)
                    continue
                base = (idxs[j] if idxs else j) * 7919  # a prime stride: spreads picks across the pool
                extras = [pool[(base + k * 104729) % len(pool)] for k in range(1, args.premise_docs)]
                # True evidence placed at a rotating position, never always first: a model trained
                # on "the answer is at the top" learns position, not contradiction.
                at = (idxs[j] if idxs else j) % args.premise_docs
                parts = extras[:at] + [ev] + extras[at:]
                built.append("\n\n".join(parts))
            premises = built
        enc = tok(premises, batch["claim"], truncation=True, max_length=args.max_length)
        enc["labels"] = [mapping[l] for l in batch["label"]]
        return enc

    keep = ["input_ids", "attention_mask", "labels"]
    train = train.map(encode, batched=True, with_indices=True, remove_columns=train.column_names)
    val = val.map(encode, batched=True, with_indices=True, remove_columns=val.column_names)
    train = train.remove_columns([c for c in train.column_names if c not in keep])
    val = val.remove_columns([c for c in val.column_names if c not in keep])
    print(f"train rows: {len(train)}   eval rows: {len(val)}\n")

    def metrics(pred):
        preds = np.argmax(pred.predictions, axis=-1)
        labels = pred.label_ids
        contradiction = mapping["REFUTES"]
        tp = int(((preds == contradiction) & (labels == contradiction)).sum())
        fp = int(((preds == contradiction) & (labels != contradiction)).sum())
        fn = int(((preds != contradiction) & (labels == contradiction)).sum())
        return {
            "accuracy": float((preds == labels).mean()),
            # The contradiction class is the only one this classifier fires on, so it is reported
            # on its own rather than hidden inside a macro average.
            "contradiction_precision": tp / (tp + fp) if (tp + fp) else 0.0,
            "contradiction_recall": tp / (tp + fn) if (tp + fn) else 0.0,
        }

    trainer = Trainer(
        model=model,
        args=TrainingArguments(
            output_dir=str(out / "_checkpoints"),
            num_train_epochs=args.epochs,
            per_device_train_batch_size=args.batch,
            gradient_accumulation_steps=args.accum,
            per_device_eval_batch_size=args.batch * 2,
            learning_rate=args.lr,
            warmup_ratio=0.06,
            eval_strategy="no",
            # CHECKPOINT, and keep only the latest. An earlier run with save_strategy="no" was
            # OOM-killed by the OS at 4h53m and wrote nothing at all — on a box whose swap this
            # training itself exhausts, "save at the end" means "save if nothing goes wrong", which
            # is not a property this machine has. save_total_limit=1 keeps the disk cost to one
            # copy, which is what made "no" tempting in the first place.
            save_strategy="steps",
            save_steps=args.save_steps,
            save_total_limit=1,
            bf16=args.bf16,
            gradient_checkpointing=args.grad_checkpoint,
            # use_reentrant=False is REQUIRED, not a tuning choice. DeBERTa's disentangled attention
            # re-reads saved tensors, and the default reentrant checkpoint implementation frees them
            # after the first backward, so training dies with "Trying to backward through the graph a
            # second time" on step 0.
            gradient_checkpointing_kwargs=({"use_reentrant": False} if args.grad_checkpoint else None),
            logging_steps=50,
            seed=args.seed,
            use_mps_device=(device == "mps"),
            report_to=[],
        ),
        train_dataset=train,
        eval_dataset=val,
        data_collator=DataCollatorWithPadding(tok),
        compute_metrics=metrics,
    )
    # Unbuffered: a run whose progress sits in a pipe buffer cannot be told apart from a run that
    # has hung, which cost real time diagnosing earlier in this work.
    print("training (checkpointing every "
          f"{args.save_steps} steps, resume with --resume)", flush=True)
    trainer.train(resume_from_checkpoint=args.resume or None)

    print("\nevaluating on held-out VitaminC validation:")
    result = trainer.evaluate()
    for k, v in sorted(result.items()):
        if isinstance(v, float):
            print(f"  {k:<32} {v:.4f}")

    out.mkdir(parents=True, exist_ok=True)
    trainer.save_model(str(out))
    tok.save_pretrained(out)
    (out / "training_provenance.json").write_text(
        json.dumps(
            {
                "base": args.base,
                "base_license": "apache-2.0 (MultiNLI + SNLI)",
                "dataset": "tals/vitaminc",
                "dataset_license": "cc-by-sa-3.0",
                "note": (
                    "No non-commercial term in any input. CC BY-SA is outside the Apache/CC0 rule "
                    "in classifiers/README.md and its use here is a deliberate widening of that "
                    "rule, recorded in groundedness/README.md."
                ),
                "rows": len(train),
                "epochs": args.epochs,
                "max_length": args.max_length,
                "premise_docs": args.premise_docs,
                "ragtruth_repeat": args.ragtruth_repeat,
                "ragtruth_neg": args.ragtruth_neg if args.ragtruth_repeat else 0,
                "compound_mix": args.compound_mix,
                "label_map": mapping,
                "validation": {k: v for k, v in result.items() if isinstance(v, float)},
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"\nsaved {out}")
    print("next: screen it on the separation probe, then eval_ablations --checkpoint", out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
