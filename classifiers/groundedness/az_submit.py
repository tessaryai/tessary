# SPDX-License-Identifier: Apache-2.0
"""Submit the same training job to Azure ML instead of SageMaker.

WHY A SECOND PROVIDER. The AWS account holds one `ml.g5.xlarge` of training quota in ap-south-1, and
on 2026-09-16 that region had no capacity for it for hours — the token-classifier run sat in
"waiting for capacity" while the budget sat unused. Azure ML is the second lane: the infra repo's
`azure-ml-training` stack stands up a scale-to-zero A100 cluster (and a T4), and this script sends
the identical job there. Nothing in the training code changes — `sm_entry.py` reads the same
environment variables on either side; only the container's model directory comes from the job's
output mount instead of `/opt/ml/model`.

SAME LEDGER, SAME CEILING. Every Azure job reserves against `sm_budget` with the A100's price, so
the $150 cap is one number across both clouds. Azure has no per-job billing API that is worth a
dependency, so an Azure entry is settled from the job's wall-clock with `--settle <job>` once it
finishes (the reservation stands until then — never the smaller of the two).

AUTH. `az login` once; `azure-identity`'s DefaultAzureCredential picks it up. Workspace and
resource group come from `terraform output` in the infra repo, via TESSARY_AZ_WORKSPACE and
TESSARY_AZ_RESOURCE_GROUP (plus ARM_SUBSCRIPTION_ID).
"""

from __future__ import annotations

import argparse
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from . import sm_budget
from .sm_submit import build_source_dir, parse_duration, require_env

#: On-demand list prices, eastus, 2026-09. The A100 box (NC24ads_A100_v4: 1x A100 80GB, 24 vCPU)
#: is priced above SageMaker's g5.xlarge but is ~3x the GPU, so a run costs about the same.
COMPUTES = {
    "a100": {"vm_size": "Standard_NC24ads_A100_v4", "price_per_hour": 3.67},
    "t4gpu": {"vm_size": "Standard_NC4as_T4_v3", "price_per_hour": 0.53},
    # CPU, paid from sponsorship credits: the one compute the sponsored subscription can scale.
    "cpu64": {"vm_size": "STANDARD_E64DS_V4", "price_per_hour": 4.61},
}
#: Azure ML curated image with torch 2.x + CUDA 12; the job pip-installs requirements.txt on top,
#: exactly as the SageMaker image does.
BASE_IMAGE = "mcr.microsoft.com/azureml/curated/acpt-pytorch-2.2-cuda12.1:latest"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--task", default="token", choices=("pair", "token"))
    ap.add_argument("--compute", default="a100", choices=tuple(COMPUTES))
    ap.add_argument("--base", default=None)
    ap.add_argument("--rows", type=int, default=0)
    ap.add_argument("--epochs", type=float, default=3.0)
    ap.add_argument("--batch", type=int, default=None)
    ap.add_argument("--accum", type=int, default=None)
    ap.add_argument("--max-length", type=int, default=None)
    ap.add_argument("--lr", type=float, default=None)
    ap.add_argument("--conflict-weight", type=float, default=1.0)
    ap.add_argument("--include-data2txt", action="store_true")
    ap.add_argument("--generated-mix", type=int, default=0)
    ap.add_argument("--template", default="plain", choices=("plain", "lettuce"))
    ap.add_argument("--premise-docs", type=int, default=1)
    ap.add_argument("--compound-mix", type=float, default=0.0)
    ap.add_argument("--ragtruth-repeat", type=int, default=0)
    ap.add_argument("--bf16", action="store_true")
    ap.add_argument("--grad-checkpoint", action="store_true")
    ap.add_argument("--max-run", type=parse_duration, default="3h")
    ap.add_argument("--smoke", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--credits", action="store_true",
                    help="paid from sponsorship credits: ledger entry carries $0/h so it does not draw on the cash cap")
    ap.add_argument("--settle", metavar="JOB", default=None,
                    help="record a finished job's actual cost from its duration, then exit")
    args = ap.parse_args()

    token = args.task == "token"
    base = args.base or ("answerdotai/ModernBERT-base" if token else "cross-encoder/nli-deberta-v3-base")
    batch = args.batch or (8 if token else 32)
    accum = args.accum or (2 if token else 1)
    maxlen = args.max_length or (4096 if token else 256)
    if args.smoke:
        args.rows, args.epochs, args.max_run = (300 if token else 2000), 1.0, parse_duration("20m")

    sub = require_env("ARM_SUBSCRIPTION_ID", "subscription_id")
    rg = require_env("TESSARY_AZ_RESOURCE_GROUP", "resource_group_name")
    ws = require_env("TESSARY_AZ_WORKSPACE", "workspace_name")

    from azure.ai.ml import MLClient
    from azure.identity import DefaultAzureCredential

    client = MLClient(DefaultAzureCredential(), sub, rg, ws)

    if args.settle:
        job = client.jobs.get(args.settle)
        started = job.creation_context.created_at
        # The job object carries no end time; the status transition to Completed/Failed is the
        # nearest thing and lives in the run's properties. Fall back to the max-run reservation.
        from .sm_budget import _load, _save, LEDGER
        entries = _load(LEDGER)
        for e in entries:
            if e.job_name == args.settle and e.provider == "azure":
                ended = datetime.now(timezone.utc) if job.status not in ("Completed", "Failed", "Canceled") \
                    else datetime.fromisoformat(job.properties.get("azureml.endTime", datetime.now(timezone.utc).isoformat()))
                secs = max(0.0, (ended - started).total_seconds())
                e.actual_usd = round(min(e.reserved_usd, secs / 3600 * e.price_per_hour), 4)
                print(f"{args.settle}: status {job.status}, {secs / 3600:.2f}h -> ${e.actual_usd:.2f}")
        _save(LEDGER, entries)
        return 0

    compute = dict(COMPUTES[args.compute])
    if args.credits:
        compute["price_per_hour"] = 0.0
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    job_name = f"groundedness-{'token' if token else 'vitaminc'}-{'smoke' if args.smoke else 'train'}-az-{stamp}"
    worst = sm_budget.cost_of(args.max_run, compute["price_per_hour"])
    print(f"job:        {job_name}")
    print(f"compute:    {args.compute} ({compute['vm_size']}) @ ${compute['price_per_hour']}/h")
    print(f"ceiling:    {args.max_run / 3600:.2f}h  ->  worst case ${worst:.2f}")
    print(f"budget:     ${sm_budget.spent():.2f} spent of ${sm_budget.BUDGET_USD:.2f}, "
          f"${sm_budget.remaining():.2f} remaining")
    print(f"training:   {base}, task={args.task}, rows={args.rows or 'all'}, epochs={args.epochs}, "
          f"batch={batch}x{accum}, maxlen={maxlen}\n")
    if args.dry_run:
        print("dry run: nothing submitted")
        return 0

    entry = sm_budget.reserve(job_name, args.max_run,
                              note=("azure smoke" if args.smoke else "azure") + (" (sponsorship credits)" if args.credits else ""),
                              provider="azure", price_per_hour=compute["price_per_hour"])
    print(f"reserved ${entry.reserved_usd:.2f}; ${sm_budget.remaining():.2f} left after this job")

    from azure.ai.ml import Output, command
    from azure.ai.ml.entities import Environment

    env_vars = {
        "TRAIN_TASK": args.task,
        "TRAIN_BASE": base,
        "TRAIN_ROWS": str(args.rows),
        "TRAIN_EPOCHS": str(args.epochs),
        "TRAIN_BATCH": str(batch),
        "TRAIN_ACCUM": str(accum),
        "TRAIN_MAXLEN": str(maxlen),
        "TRAIN_LR": str(args.lr if args.lr is not None else (3e-5 if token else 2e-5)),
        "TRAIN_CONFLICT_WEIGHT": str(args.conflict_weight),
                "TRAIN_INCLUDE_DATA2TXT": "1" if args.include_data2txt else "0",
        "TRAIN_GENERATED_MIX": str(args.generated_mix),
                "TRAIN_TEMPLATE": args.template,
        "TRAIN_ATTN": "sdpa",
        "TRAIN_PREMISE_DOCS": str(args.premise_docs),
        "TRAIN_COMPOUND_MIX": str(args.compound_mix),
        "TRAIN_RAGTRUTH_REPEAT": str(args.ragtruth_repeat),
        "TRAIN_BF16": "1" if args.bf16 else "0",
        "TRAIN_GRAD_CHECKPOINT": "1" if args.grad_checkpoint else "0",
        "DATA_REV": os.environ.get("DATA_REV", "main"),
        "HF_HOME": "/tmp/hf",
    }
    try:
        with tempfile.TemporaryDirectory(prefix="az-src-") as tmp:
            src = build_source_dir(Path(tmp))
            job = command(
                name=job_name,
                display_name=job_name,
                experiment_name="groundedness-classifier",
                code=str(src),
                # The output mount is the model dir; sm_entry reads SM_MODEL_DIR on both clouds.
                command="pip install -r requirements.txt && SM_MODEL_DIR=${{outputs.model}} python sm_entry.py",
                environment=Environment(image=BASE_IMAGE, name="groundedness-train-env"),
                environment_variables=env_vars,
                compute=args.compute,
                outputs={"model": Output(type="uri_folder")},
            )
            # Azure's own kill switch, mirroring SageMaker max_run.
            from azure.ai.ml.entities import CommandJobLimits

            job.limits = CommandJobLimits(timeout=int(args.max_run))
            submitted = client.jobs.create_or_update(job)
    except Exception:
        sm_budget.release(job_name)
        raise
    print(f"\nsubmitted (not waiting): {submitted.studio_url}\nFollow with:\n"
          f"  az ml job show -n {job_name} -g {rg} -w {ws} --query status -o tsv\n"
          f"  uv run --extra azure python -m groundedness.az_submit --settle {job_name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
