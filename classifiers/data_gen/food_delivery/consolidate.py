# SPDX-License-Identifier: Apache-2.0
"""Merge every shard under ``classifiers/data/food_delivery/shards/`` into the single
``classifiers/data/food_delivery/conversations.jsonl`` the emitter reads from.

Dedupes by ``conversation_id`` (shard files never produce the same id twice — each is stamped
with a shard tag plus a random suffix — but dedup makes this safe to rerun at any point during a
still-running generation job without risking a corrupt or partially-duplicated output file).

Run:  cd classifiers && python -m data_gen.food_delivery.consolidate
"""

from __future__ import annotations

import json
from pathlib import Path

from .generate import DATA_DIR, OUT_PATH

SHARD_DIR = DATA_DIR / "shards"


def consolidate(shard_dir: Path = SHARD_DIR, out_path: Path = OUT_PATH) -> int:
    seen: dict[str, dict] = {}
    for shard in sorted(shard_dir.glob("*.jsonl")):
        for line in shard.read_text().splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue
            cid = record.get("conversation_id")
            if not cid:
                continue
            seen.setdefault(cid, record)

    tmp_path = out_path.with_suffix(".jsonl.tmp")
    with tmp_path.open("w") as fh:
        for record in seen.values():
            fh.write(json.dumps(record) + "\n")
    tmp_path.replace(out_path)
    return len(seen)


def main() -> int:
    n = consolidate()
    print(f"[consolidate] wrote {n} conversations to {OUT_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
