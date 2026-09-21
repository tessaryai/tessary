# SPDX-License-Identifier: Apache-2.0
"""Fold the bench_latency result files into one table: median seconds per length bucket, per config,
with parity against the fp32 torch reference. Prints markdown; `--json` prints the folded rows."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from groundedness.bench_latency import BUCKETS

ORDER = ["torch-cpu-t2", "torch-cpu-t4", "torch-cpu-t8", "torch-cpu-t12", "torch-mps-fp32", "torch-mps-fp16",
         "ort-cpu-fp32-t2", "ort-cpu-fp32-t4", "ort-cpu-fp32-t8", "ort-cpu-int8-t2", "ort-cpu-int8-t4",
         "ort-cpu-int8-t8", "ort-coreml-fp32", "http-container-cpus2", "http-container-cpus4", "http-container-cpus8", "http-container-cpus2-threads2", "http-container-cpus4-threads4",
         "http-container-cpus8-threads8", "http-container-cpus8-threads8-mem14g", "native-node-threads4",
         "native-node-threads8"]


def fold(bench_dir: Path) -> list[dict]:
    rows = []
    for label in ORDER:
        p = bench_dir / f"{label}.json"
        if not p.exists():
            continue
        d = json.loads(p.read_text())
        rows.append({
            "label": label,
            "engine": d["engine"],
            "load_s": d["load_seconds"],
            "buckets": {name: d["summary"].get(name) for name, _, _ in BUCKETS},
            "parity": d.get("parity"),
            "providers": d.get("providers"),
        })
    return rows


def markdown(rows: list[dict]) -> str:
    head = "| config | " + " | ".join(n for n, _, _ in BUCKETS) + " | whole set (median, /min) | parity vs fp32 (max Δp) |\n"
    head += "|---|" + "---|" * len(BUCKETS) + "---|---|\n"
    out = [head]
    for r in rows:
        cells = []
        meds = []
        for name, _, _ in BUCKETS:
            b = r["buckets"][name]
            if b:
                cells.append(f"{b['median_s']:.2f}s ({b['per_min']:.0f})")
                meds.append(b["median_s"])
            else:
                cells.append("—")
        whole = sorted(meds)[len(meds) // 2] if meds else None
        par = r["parity"]
        par_s = "reference" if par is None else f"{par['max_abs_delta']:.4f}"
        out.append(f"| {r['label']} | " + " | ".join(cells)
                   + f" | {'—' if whole is None else f'{whole:.2f}s, {60 / whole:.0f}/min'} | {par_s} |\n")
    return "".join(out)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bench-dir", default="artifacts/groundedness/bench")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    rows = fold(Path(a.bench_dir))
    print(json.dumps(rows, indent=1) if a.json else markdown(rows))


if __name__ == "__main__":
    main()
