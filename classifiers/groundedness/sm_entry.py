# SPDX-License-Identifier: Apache-2.0
"""In-container entry for the SageMaker training job. Runs on the GPU box, not here.

Both datasets are PUBLIC, so unlike the frustration precedent this needs no HF token and no S3
staging: the container pulls them directly, revision-pinned (`DATA_REV` for VitaminC, `RAGTRUTH_REV`
for RAGTruth). The pin matters more than the convenience — an unpinned dataset means a rerun trains
on different data and the comparison against earlier numbers quietly stops meaning anything.
"""
from __future__ import annotations

import os
import subprocess
import sys

DATASET = "tals/vitaminc"
#: wandb/RAGTruth-processed as of its last commit (2024-11-28), the revision every token run used.
RAGTRUTH_REV = os.environ.get("RAGTRUTH_REV", "eb4f4b9d1b68eb7092d3e1a61c0cd82d9808737b")


def token_main(model_dir: str) -> int:
    """The long-context token classifier: RAGTruth only, no VitaminC."""
    from datasets import load_dataset

    rt = load_dataset("wandb/RAGTruth-processed", revision=RAGTRUTH_REV)
    print("[sm_entry] wandb/RAGTruth-processed: " + ", ".join(f"{k}={len(v)}" for k, v in rt.items()), flush=True)
    cmd = [
        sys.executable, "-m", "groundedness.token_train",
        "--base", os.environ.get("TRAIN_BASE", "answerdotai/ModernBERT-base"),
        "--out", model_dir,
        "--max-length", os.environ.get("TRAIN_MAXLEN", "4096"),
        "--epochs", os.environ.get("TRAIN_EPOCHS", "3"),
        "--batch", os.environ.get("TRAIN_BATCH", "8"),
        "--accum", os.environ.get("TRAIN_ACCUM", "2"),
        "--lr", os.environ.get("TRAIN_LR", "3e-5"),
        "--limit", os.environ.get("TRAIN_ROWS", "0"),
        "--conflict-weight", os.environ.get("TRAIN_CONFLICT_WEIGHT", "1.0"),
        "--attn", os.environ.get("TRAIN_ATTN", "sdpa"),
        "--generated-mix", os.environ.get("TRAIN_GENERATED_MIX", "0"),
        "--template", os.environ.get("TRAIN_TEMPLATE", "plain"),
        "--save-steps", os.environ.get("TRAIN_SAVE_STEPS", "500"),
    ]
    if os.environ.get("TRAIN_BF16") == "1":
        cmd.append("--bf16")
    if os.environ.get("TRAIN_INCLUDE_DATA2TXT") == "1":
        cmd.append("--include-data2txt")
    print("[sm_entry] " + " ".join(cmd), flush=True)
    return subprocess.call(cmd)


def main() -> int:
    model_dir = os.environ.get("SM_MODEL_DIR", "/opt/ml/model")
    rev = os.environ.get("DATA_REV", "main")
    if os.environ.get("TRAIN_TASK") == "token":
        return token_main(model_dir)

    # Warm the dataset cache inside the container so a network blip fails here, loudly, rather than
    # mid-training after GPU time has already been billed.
    from datasets import load_dataset

    ds = load_dataset(DATASET, revision=rev)
    print(f"[sm_entry] {DATASET}@{rev}: " + ", ".join(f"{k}={len(v)}" for k, v in ds.items()), flush=True)

    base = os.environ.get("TRAIN_BASE", "cross-encoder/nli-deberta-v3-base")
    if base.startswith("s3://"):
        # Continue from one of our own checkpoints: the job's model.tar.gz, pulled and unpacked here.
        import tarfile

        import boto3

        bucket, key = base[5:].split("/", 1)
        local = "/tmp/base"
        os.makedirs(local, exist_ok=True)
        boto3.client("s3").download_file(bucket, key, "/tmp/base.tar.gz")
        with tarfile.open("/tmp/base.tar.gz") as tar:
            tar.extractall(local, members=[m for m in tar if not m.name.startswith("_checkpoints")])
        print(f"[sm_entry] base checkpoint {base} -> {local}: {sorted(os.listdir(local))}", flush=True)
        base = local
    if os.environ.get("TRAIN_RAGTRUTH_REPEAT", "0") != "0":
        rt = load_dataset("wandb/RAGTruth-processed", revision=RAGTRUTH_REV)
        print("[sm_entry] wandb/RAGTruth-processed: " + ", ".join(f"{k}={len(v)}" for k, v in rt.items()), flush=True)

    cmd = [
        sys.executable, "-m", "groundedness.train",
        "--base", base,
        "--out", model_dir,
        "--subsample", os.environ.get("TRAIN_ROWS", "0"),
        "--epochs", os.environ.get("TRAIN_EPOCHS", "1"),
        "--batch", os.environ.get("TRAIN_BATCH", "32"),
        "--accum", os.environ.get("TRAIN_ACCUM", "1"),
        "--max-length", os.environ.get("TRAIN_MAXLEN", "256"),
        "--eval-rows", os.environ.get("TRAIN_EVAL_ROWS", "6000"),
        "--premise-docs", os.environ.get("TRAIN_PREMISE_DOCS", "1"),
        "--compound-mix", os.environ.get("TRAIN_COMPOUND_MIX", "0"),
        "--ragtruth-repeat", os.environ.get("TRAIN_RAGTRUTH_REPEAT", "0"),
        "--ragtruth-neg", os.environ.get("TRAIN_RAGTRUTH_NEG", "8000"),
        # Checkpoint into SageMaker's checkpoint dir when one is configured, so a max_run stop or a
        # spot interruption leaves something behind instead of billing for nothing.
        "--save-steps", os.environ.get("TRAIN_SAVE_STEPS", "500"),
    ]
    if os.environ.get("TRAIN_BF16") == "1":
        cmd.append("--bf16")
    if os.environ.get("TRAIN_GRAD_CHECKPOINT") == "1":
        cmd.append("--grad-checkpoint")
    print("[sm_entry] " + " ".join(cmd), flush=True)
    return subprocess.call(cmd)


if __name__ == "__main__":
    raise SystemExit(main())
