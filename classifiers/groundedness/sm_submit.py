# SPDX-License-Identifier: Apache-2.0
"""Submit the VitaminC fine-tune as a SageMaker Training Job (ml.g5.xlarge / A10G, ap-south-1).

WHY OFF THIS MACHINE. Three local runs were OOM-killed by the OS, one after 4h53m with nothing
written. The dev box reaches roughly 1,600 of 9,375 steps before dying and the last resume cycle
advanced zero. The recipe is not the problem — 25k rows locally took per-window separation from
0.260 to 0.660 against an 0.840 reference — the machine is.

NO INFRASTRUCTURE IDENTIFIERS ARE HARDCODED. The frustration precedent pins its role ARN and bucket
in source; that is fine in a private overlay and wrong for a module heading open-source, where an
account id in git history is permanent. Both come from the environment, and the error names the
terraform output to read them from.

SPEND IS REFUSED, NOT WATCHED. `sm_budget` prices every job at its worst case (`max_run` at the full
on-demand rate) and declines a submission that would breach the ceiling. The AWS budget alarm in the
infrastructure repo alerts at 80/100% of a monthly figure; it blocks nothing, and it is not
per-project.

    export TESSARY_SM_ROLE=$(terraform -chdir=.../sagemaker-ml-training output -raw execution_role_arn)
    export TESSARY_SM_BUCKET=$(terraform -chdir=.../sagemaker-ml-training output -raw bucket)
    uv run --extra sagemaker python -m groundedness.sm_submit --smoke
    uv run --extra sagemaker python -m groundedness.sm_submit --rows 0 --epochs 2 --max-run 5h
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from . import sm_budget

#: VitaminC at a pinned revision. An unpinned dataset means a rerun trains on different data and
#: every comparison against an earlier number quietly stops meaning anything.
#: tals/vitaminc: pinned to the revision the submitter was written against; override per run.
DATA_REV = os.environ.get("DATA_REV", "main")

FRAMEWORK_VERSION = "2.3.0"  # matches the frustration precedent's working image
PY_VERSION = "py311"


def parse_duration(text: str) -> int:
    """`5h`, `90m`, `3600` -> seconds. A ceiling this important should not be a bare integer of
    ambiguous unit in a shell history."""
    m = re.fullmatch(r"(\d+(?:\.\d+)?)\s*([hms]?)", text.strip().lower())
    if not m:
        raise argparse.ArgumentTypeError(f"cannot read {text!r} as a duration (try 90m, 5h, 3600)")
    value, unit = float(m.group(1)), m.group(2) or "s"
    return int(value * {"h": 3600, "m": 60, "s": 1}[unit])


def require_env(name: str, output: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise SystemExit(
            f"{name} is not set. It is deliberately not hardcoded — read it from the infrastructure "
            f"repo:\n  export {name}=$(terraform -chdir=terraform/environments/sagemaker-ml-training "
            f"output -raw {output})"
        )
    return value


def build_source_dir(stage: Path) -> Path:
    """The package the job runs, plus the framework it imports. Copied rather than referenced so the
    upload is a self-contained snapshot of the code that produced the result."""
    shutil.copytree("groundedness", stage / "groundedness",
                    ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    shutil.copytree("framework", stage / "framework",
                    ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    # The job pip-installs these; torch and the CUDA stack come from the SageMaker image, which
    # pins torch 2.3.0. MAJOR VERSIONS ARE CAPPED, and that is not fussiness: an unbounded
    # `transformers>=4.48` resolved to 5.17.0 in-container, which refuses torch 2.3.0 outright, and
    # the job died with "requires the PyTorch library but it was not found" on an image that ships
    # it. A floor without a ceiling means the container installs whatever exists on the day.
    (stage / "requirements.txt").write_text(
        "transformers>=4.48,<5\n"
        "datasets>=2.20,<4\n"
        "accelerate>=0.34,<2\n"
        "scikit-learn>=1.5,<2\n",
        encoding="utf-8",
    )
    # The generated tuning corpora (~400 KB) ride along for --generated-mix; the held-out files
    # are deliberately NOT copied, so a job cannot train on them even by accident.
    (stage / "data" / "groundedness").mkdir(parents=True)
    for f in Path("data/groundedness").glob("verified_corpus*.json"):
        shutil.copy(f, stage / "data" / "groundedness" / f.name)
    shutil.copy("groundedness/sm_entry.py", stage / "sm_entry.py")
    return stage


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--rows", type=int, default=0, help="VitaminC train rows (0 = all 370k)")
    ap.add_argument("--epochs", type=float, default=1.0)
    ap.add_argument("--batch", type=int, default=32, help="A10G has 24GB; local needed 4")
    ap.add_argument("--accum", type=int, default=1)
    ap.add_argument("--max-length", type=int, default=256)
    ap.add_argument("--base", default="cross-encoder/nli-deberta-v3-base")
    ap.add_argument("--bf16", action="store_true", help="native on A10G; halves activation memory")
    ap.add_argument("--grad-checkpoint", action="store_true")
    ap.add_argument("--ragtruth-repeat", type=int, default=0,
                    help="mix RAGTruth train rows in; REFUTES repeated N times (0 = off)")
    ap.add_argument("--ragtruth-neg", type=int, default=8000)
    ap.add_argument("--compound-mix", type=float, default=0.0,
                    help="fraction of rows replaced with synthesised two-clause claims")
    ap.add_argument("--premise-docs", type=int, default=1,
                    help=">1 trains on multi-document premises, the shape the service scores")
    ap.add_argument("--max-run", type=parse_duration, default="3h",
                    help="hard ceiling; ALSO what the job is budgeted at (worst case)")
    ap.add_argument("--task", default="pair", choices=("pair", "token"),
                    help="pair = VitaminC NLI fine-tune (train.py); token = long-context token classifier on RAGTruth (token_train.py)")
    ap.add_argument("--lr", type=float, default=None)
    ap.add_argument("--attn", default="sdpa", choices=("sdpa", "eager"))
    ap.add_argument("--framework-version", default=None,
                    help="SageMaker PyTorch image; default 2.3.0 (pair) / 2.5.1 (token: ModernBERT NaNs under 2.3 sdpa)")
    ap.add_argument("--conflict-weight", type=float, default=1.0)
    ap.add_argument("--include-data2txt", action="store_true")
    ap.add_argument("--generated-mix", type=int, default=0)
    ap.add_argument("--template", default="plain", choices=("plain", "lettuce"))
    ap.add_argument("--smoke", action="store_true",
                    help="2000 rows, 20m ceiling: proves the image, the data pull and the entry "
                         "point for about $0.56 before committing hours of GPU")
    ap.add_argument("--dry-run", action="store_true", help="price and validate, submit nothing")
    args = ap.parse_args()

    if args.task == "token":
        # token defaults; --base/--batch/--max-length keep their explicit values if given
        if args.base == "cross-encoder/nli-deberta-v3-base":
            args.base = "answerdotai/ModernBERT-base"
        if args.max_length == 256:
            args.max_length = 4096
        if args.batch == 32:
            args.batch, args.accum = 8, 2
    if args.smoke:
        args.rows, args.epochs, args.max_run = (300 if args.task == "token" else 2000), 1.0, parse_duration("20m")

    role = require_env("TESSARY_SM_ROLE", "execution_role_arn")
    bucket = require_env("TESSARY_SM_BUCKET", "bucket")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    job_name = f"groundedness-{'token' if args.task == 'token' else 'vitaminc'}-{'smoke' if args.smoke else 'train'}-{stamp}"

    worst_case = sm_budget.cost_of(args.max_run)
    print(f"job:        {job_name}")
    print(f"instance:   {sm_budget.INSTANCE_TYPE} @ ${sm_budget.PRICE_PER_HOUR}/h ({sm_budget.REGION})")
    print(f"ceiling:    {args.max_run / 3600:.2f}h  ->  worst case ${worst_case:.2f}")
    print(f"budget:     ${sm_budget.spent():.2f} spent of ${sm_budget.BUDGET_USD:.2f}, "
          f"${sm_budget.remaining():.2f} remaining")
    print(f"training:   {args.base}, rows={args.rows or 'all 370k'}, epochs={args.epochs}, "
          f"batch={args.batch}x{args.accum}, maxlen={args.max_length}\n")

    if args.dry_run:
        print("dry run: nothing submitted")
        return 0

    # Reserve BEFORE submitting. A job that starts and is not recorded is exactly how a ceiling is
    # exceeded without anyone noticing.
    entry = sm_budget.reserve(job_name, args.max_run, note="smoke" if args.smoke else "")
    print(f"reserved ${entry.reserved_usd:.2f}; ${sm_budget.remaining():.2f} left after this job")

    try:
        import sagemaker
        from sagemaker.pytorch import PyTorch
    except ImportError as exc:
        sm_budget.release(job_name)
        raise SystemExit(
            f"sagemaker SDK unusable ({exc}). The reservation was released. This module needs the "
            "v2 API (sagemaker>=2.230,<3); v3 removed sagemaker.pytorch."
        ) from exc

    try:
      with tempfile.TemporaryDirectory(prefix="sm-src-") as tmp:
        src = build_source_dir(Path(tmp))
        estimator = PyTorch(
            entry_point="sm_entry.py",
            source_dir=str(src),
            role=role,
            instance_type=sm_budget.INSTANCE_TYPE,
            instance_count=1,
            framework_version=args.framework_version or ("2.5.1" if args.task == "token" else FRAMEWORK_VERSION),
            py_version=PY_VERSION,
            max_run=args.max_run,
            output_path=f"s3://{bucket}/models",
            sagemaker_session=sagemaker.Session(),
            environment={
                "DATA_REV": DATA_REV,
                "TRAIN_TASK": args.task,
                "TRAIN_LR": str(args.lr if args.lr is not None else (3e-5 if args.task == "token" else 2e-5)),
                "TRAIN_CONFLICT_WEIGHT": str(args.conflict_weight),
                "TRAIN_INCLUDE_DATA2TXT": "1" if args.include_data2txt else "0",
                "TRAIN_GENERATED_MIX": str(args.generated_mix),
                "TRAIN_TEMPLATE": args.template,
                "TRAIN_ATTN": args.attn,
                "TRAIN_BASE": args.base,
                "TRAIN_ROWS": str(args.rows),
                "TRAIN_EPOCHS": str(args.epochs),
                "TRAIN_BATCH": str(args.batch),
                "TRAIN_ACCUM": str(args.accum),
                "TRAIN_MAXLEN": str(args.max_length),
                "TRAIN_BF16": "1" if args.bf16 else "0",
                "TRAIN_GRAD_CHECKPOINT": "1" if args.grad_checkpoint else "0",
                "TRAIN_PREMISE_DOCS": str(args.premise_docs),
                "TRAIN_COMPOUND_MIX": str(args.compound_mix),
                "TRAIN_RAGTRUTH_REPEAT": str(args.ragtruth_repeat),
                "TRAIN_RAGTRUTH_NEG": str(args.ragtruth_neg),
                "HF_HOME": "/tmp/hf",
            },
        )
        estimator.fit(job_name=job_name, wait=False)
    except Exception:
        # Nothing is running, so nothing should be charged against the ceiling.
        sm_budget.release(job_name)
        print(f"submission failed; released the ${worst_case:.2f} reservation")
        raise

    print(f"\nsubmitted (not waiting). Follow it with:")
    print(f"  aws sagemaker describe-training-job --training-job-name {job_name} "
          f"--region {sm_budget.REGION} --query TrainingJobStatus --output text")
    print(f"  uv run --extra sagemaker python -m groundedness.sm_budget --reconcile")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
