# SPDX-License-Identifier: Apache-2.0
"""Concurrent multi-shard driver for ``generate.py``.

Bedrock calls are network-bound, so a thread pool gets real concurrency despite the GIL. Each
shard is an independent, resumable ``generate()`` run against its own file under
``classifiers/data/food_delivery/shards/`` — rerunning this script is safe and idempotent: every
shard just tops itself up to its slice of ``--target`` based on how many lines are already on
disk, and shards that already meet their target are a no-op.

Meant to be launched detached in a normal shell, since it can run for a long time:
  cd classifiers && nohup python -m data_gen.food_delivery.run_parallel \
      --target 1200 --shards 16 --workers 10 > /tmp/fd_run_parallel.log 2>&1 &

Then poll classifiers/data/food_delivery/shards/*.jsonl line counts and the per-shard logs under
classifiers/data/food_delivery/shards/logs/ to watch progress.

If your execution environment kills background processes when the invoking shell call ends
(no true detached daemons — e.g. a sandboxed tool with a hard per-call wall-clock timeout), pass
``--max-calls-per-shard 1`` and a small ``--batch-min/--batch-max`` (so one Bedrock call safely
finishes inside the timeout) and invoke this command repeatedly — each invocation does exactly one
concurrent round across whichever shards haven't hit their slice of ``--target`` yet, then exits
cleanly. This is still idempotent and resumable: shards already at target are a fast no-op.
"""

from __future__ import annotations

import argparse
import math
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from . import generate

SHARD_DIR = generate.DATA_DIR / "shards"
LOG_DIR = SHARD_DIR / "logs"


def run(
    target: int,
    shards: int,
    workers: int,
    max_calls_per_shard: int,
    seed: int,
    batch_min: int = 6,
    batch_max: int = 10,
    max_tokens: int = 16000,
    total_shards: int | None = None,
    shard_start: int = 1,
) -> None:
    SHARD_DIR.mkdir(parents=True, exist_ok=True)
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    total_shards = total_shards or shards
    per_shard_target = math.ceil(target / total_shards)
    print(
        f"[run_parallel] target={target} total_shards={total_shards} "
        f"shard_range=[{shard_start},{shard_start + shards - 1}] workers={workers} "
        f"per_shard_target={per_shard_target} batch=[{batch_min},{batch_max}] "
        f"max_calls_per_shard={max_calls_per_shard}"
    )

    jobs = []
    for i in range(shard_start, shard_start + shards):
        out_path = SHARD_DIR / f"part{i}.jsonl"
        log_path = LOG_DIR / f"part{i}.log"
        jobs.append((i, out_path, log_path))

    started = time.time()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {
            pool.submit(
                generate.generate,
                per_shard_target,
                max_calls_per_shard,
                seed + i,
                out_path,
                f"part{i}",
                batch_min,
                batch_max,
                max_tokens,
                log_path,
            ): i
            for i, out_path, log_path in jobs
        }
        for fut in as_completed(futures):
            i = futures[fut]
            try:
                written = fut.result()
                print(f"[run_parallel] shard part{i} finished with {written} conversations")
            except Exception as exc:  # noqa: BLE001 — one shard's crash shouldn't kill the others
                print(f"[run_parallel] shard part{i} raised: {exc!r}")

    total = sum(
        sum(1 for line in p.read_text().splitlines() if line.strip())
        for _, p, _ in jobs
        if p.exists()
    )
    elapsed = time.time() - started
    print(
        f"[run_parallel] shard range [{shard_start},{shard_start + shards - 1}] done in "
        f"{elapsed:.0f}s — {total} conversations in that range"
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", type=int, default=1200)
    ap.add_argument("--shards", type=int, default=16)
    ap.add_argument("--workers", type=int, default=10)
    ap.add_argument("--max-calls-per-shard", type=int, default=40)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--batch-min", type=int, default=6)
    ap.add_argument("--batch-max", type=int, default=10)
    ap.add_argument("--max-tokens", type=int, default=16000)
    ap.add_argument("--total-shards", type=int, default=None)
    ap.add_argument("--shard-start", type=int, default=1)
    args = ap.parse_args()
    run(
        args.target,
        args.shards,
        args.workers,
        args.max_calls_per_shard,
        args.seed,
        batch_min=args.batch_min,
        batch_max=args.batch_max,
        max_tokens=args.max_tokens,
        total_shards=args.total_shards,
        shard_start=args.shard_start,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
