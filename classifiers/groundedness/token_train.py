# SPDX-License-Identifier: Apache-2.0
"""Fine-tune a long-context encoder as a token classifier on RAGTruth train.

BASE. `answerdotai/ModernBERT-base` (Apache-2.0, 8,192-token context, 149M params) by default;
`-large` (395M) by flag. Both are permissively licensed and CPU-servable. No FEVER/ANLI/VitaminC
lineage anywhere: every label the model learns from is a human's.

VALIDATION IS CARVED FROM TRAIN, BY RESPONSE. RAGTruth's test split is the number that counts and
is never read here. The last 10% of train responses (fixed seed) is held out for the in-training
metric, which is token-level precision/recall on CONFLICT — the class the head fires on.
"""

from __future__ import annotations

import argparse
import json
import random
import time
from pathlib import Path

from .token_data import CONFLICT_ID, LABELS, TEMPLATES, build, generated_examples

DEFAULT_BASE = "answerdotai/ModernBERT-base"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--out", required=True)
    ap.add_argument("--max-length", type=int, default=4096)
    ap.add_argument("--epochs", type=float, default=3.0)
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--accum", type=int, default=2)
    ap.add_argument("--lr", type=float, default=3e-5)
    ap.add_argument("--limit", type=int, default=0, help="responses (0 = all); smoke runs")
    ap.add_argument("--seed", type=int, default=17)
    ap.add_argument("--bf16", action="store_true")
    ap.add_argument("--save-steps", type=int, default=500)
    ap.add_argument("--template", default="plain", choices=TEMPLATES,
                    help="context layout; 'lettuce' when the base is a lettucedetect checkpoint")
    ap.add_argument("--attn", default="sdpa", choices=("sdpa", "eager"),
                    help="attention kernel; ModernBERT under sdpa on torch<=2.3 produced NaN logits on GPU")
    ap.add_argument("--include-data2txt", action="store_true",
                    help="add RAGTruth's Data2txt responses (JSON contexts) to TRAINING for their conflict spans")
    ap.add_argument("--generated-mix", type=int, default=0,
                    help="mix in our generated TUNING corpora as token examples, repeated N times (0 = off)")
    ap.add_argument("--conflict-weight", type=float, default=1.0,
                    help="loss weight on the CONFLICT class (2% of response tokens)")
    args = ap.parse_args()

    import numpy as np
    import torch
    from datasets import Dataset
    from transformers import (
        AutoModelForTokenClassification,
        AutoTokenizer,
        DataCollatorForTokenClassification,
        Trainer,
        TrainingArguments,
    )

    out = Path(args.out)
    tok = AutoTokenizer.from_pretrained(args.base)
    model = AutoModelForTokenClassification.from_pretrained(
        args.base, num_labels=len(LABELS), attn_implementation=args.attn,
        id2label=dict(enumerate(LABELS)), label2id={l: i for i, l in enumerate(LABELS)},
        # A lettucedetect base carries a 2-label head (hallucinated / not); ours is 3-label, so the
        # head is re-initialised and only the encoder — the part that learned RAGTruth — is kept.
        ignore_mismatched_sizes=True,
    )

    t0 = time.time()
    examples = build(tok, "train", args.max_length, args.limit, args.include_data2txt, args.template)
    rng = random.Random(args.seed)
    rng.shuffle(examples)
    n_val = max(1, len(examples) // 10)
    val, train = examples[:n_val], examples[n_val:]
    if args.generated_mix > 0:
        gen = generated_examples(tok, args.max_length, args.template)
        train = train + gen * args.generated_mix
        rng.shuffle(train)
        print(f"generated tuning corpora mixed in: {len(gen)} examples x{args.generated_mix}", flush=True)
    n_conf = sum(l == CONFLICT_ID for ex in train for l in ex["labels"])
    n_resp = sum(l >= 0 for ex in train for l in ex["labels"])
    print(f"base: {args.base}\ntrain responses: {len(train)}  val: {len(val)}  "
          f"response tokens: {n_resp}  CONFLICT tokens: {n_conf} ({n_conf / n_resp:.1%})  "
          f"[built in {time.time() - t0:.0f}s]\n", flush=True)
    train_ds = Dataset.from_list(train)
    val_ds = Dataset.from_list(val)

    def metrics(pred):
        logits, labels = pred
        preds = np.argmax(logits, axis=-1)
        mask = labels != -100
        p, y = preds[mask], labels[mask]
        tp = int(((p == CONFLICT_ID) & (y == CONFLICT_ID)).sum())
        fp = int(((p == CONFLICT_ID) & (y != CONFLICT_ID)).sum())
        fn = int(((p != CONFLICT_ID) & (y == CONFLICT_ID)).sum())
        prec = tp / (tp + fp) if tp + fp else 0.0
        rec = tp / (tp + fn) if tp + fn else 0.0
        return {"token_accuracy": float((p == y).mean()),
                "conflict_token_precision": prec, "conflict_token_recall": rec,
                "conflict_token_f1": 2 * prec * rec / (prec + rec) if prec + rec else 0.0}

    class WeightedTrainer(Trainer):
        def compute_loss(self, model, inputs, return_outputs=False, **kw):
            labels = inputs.pop("labels")
            outputs = model(**inputs)
            w = torch.ones(len(LABELS), device=outputs.logits.device)
            w[CONFLICT_ID] = args.conflict_weight
            loss = torch.nn.functional.cross_entropy(
                outputs.logits.view(-1, len(LABELS)), labels.view(-1), weight=w, ignore_index=-100)
            return (loss, outputs) if return_outputs else loss

    from transformers import TrainerCallback

    class NaNGuard(TrainerCallback):
        """Abort on the first non-finite logged loss/grad instead of billing hours for garbage.
        The first GPU run logged loss 0.0 / grad_norm nan from step 25 and would have run to the end."""

        def on_log(self, args_, state, control, logs=None, **kw):
            import math

            g = (logs or {}).get("grad_norm")
            if g is not None and (math.isnan(g) or math.isinf(g)):
                raise RuntimeError(f"non-finite grad_norm at step {state.global_step}: {logs}")

    trainer = WeightedTrainer(
        model=model,
        args=TrainingArguments(
            output_dir=str(out / "_checkpoints"),
            num_train_epochs=args.epochs,
            per_device_train_batch_size=args.batch,
            gradient_accumulation_steps=args.accum,
            per_device_eval_batch_size=args.batch,
            learning_rate=args.lr,
            warmup_ratio=0.06,
            weight_decay=0.01,
            eval_strategy="epoch",
            save_strategy="steps", save_steps=args.save_steps, save_total_limit=1,
            bf16=args.bf16,
            logging_steps=25,
            seed=args.seed,
            report_to=[],
            dataloader_num_workers=2,
        ),
        train_dataset=train_ds,
        eval_dataset=val_ds,
        data_collator=DataCollatorForTokenClassification(tok),
        compute_metrics=metrics,
        callbacks=[NaNGuard()],
    )
    trainer.train()
    final = trainer.evaluate()
    print(json.dumps(final, indent=2))

    out.mkdir(parents=True, exist_ok=True)
    trainer.save_model(str(out))
    tok.save_pretrained(str(out))
    (out / "training_provenance.json").write_text(json.dumps({
        "task": "token-classification", "labels": list(LABELS),
        "base": args.base, "base_license": "apache-2.0",
        "dataset": "wandb/RAGTruth-processed (ParticleMedia/RAGTruth)", "dataset_license": "MIT",
        "split": "train (Summary+QA, quality=good); 10% of responses held out for validation",
        "responses": len(train), "epochs": args.epochs, "max_length": args.max_length,
        "batch": args.batch, "accum": args.accum, "lr": args.lr, "conflict_weight": args.conflict_weight,
        "attn": args.attn, "torch": __import__("torch").__version__,
        "include_data2txt": args.include_data2txt,
        "generated_mix": args.generated_mix,
        "template": args.template,
        "validation": final,
    }, indent=2), encoding="utf-8")
    print(f"saved {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
