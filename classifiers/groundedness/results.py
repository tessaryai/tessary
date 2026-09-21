# SPDX-License-Identifier: Apache-2.0
"""An append-only record of every groundedness experiment, with precision, recall and F1.

WHY A LEDGER AND NOT A README TABLE. Numbers that live in terminal scrollback get quoted from
memory, and numbers quoted from memory drift. This session has already seen it: a one-claim probe's
margin was carried forward as if it characterised a model, and a recall figure measured under the
wrong input format was reported as transferable. Every experiment now writes its own row, with the
configuration that produced it, so any claim can be traced to a run.

F1 IS RECORDED BUT IT IS NOT THE GATE. The gate is recall at a fixed false-positive rate, because
this is a per-observation filter on every trace and the FP budget is the binding constraint — an F1
optimum can sit at an FP rate nobody would accept. F1 is here because it is the conventional summary
and its absence invites someone to compute it wrongly from a partial row.

Every row also carries `n_pos`/`n_neg`: a precision of 1.000 over four positives is not a result,
and a row that cannot show its denominators cannot be challenged.
"""

from __future__ import annotations

import json
import platform
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path

LEDGER = Path("data/groundedness/experiments.json")


@dataclass(slots=True)
class Result:
    """One measurement of one configuration. `extra` carries metric families that do not fit the
    precision/recall shape — separation hit rate, margins, forward passes."""

    name: str
    checkpoint: str
    corpus: str
    recorded_at: str = ""
    precision: float | None = None
    recall: float | None = None
    f1: float | None = None
    fp_rate: float | None = None
    threshold: float | None = None
    n_pos: int | None = None
    n_neg: int | None = None
    config: dict = field(default_factory=dict)
    extra: dict = field(default_factory=dict)
    note: str = ""

    def __post_init__(self) -> None:
        if not self.recorded_at:
            self.recorded_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
        if self.f1 is None and self.precision is not None and self.recall is not None:
            self.f1 = f1_of(self.precision, self.recall)


def f1_of(precision: float, recall: float) -> float:
    """Harmonic mean, 0.0 when both are 0 rather than a ZeroDivisionError mid-run."""
    return 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0


def record(result: Result, path: Path = LEDGER) -> Result:
    """Append a row. Never rewrites history: a superseded result stays, because knowing a number was
    once believed is how a later correction can be understood."""
    path.parent.mkdir(parents=True, exist_ok=True)
    rows = json.loads(path.read_text(encoding="utf-8"))["results"] if path.exists() else []
    rows.append(asdict(result))
    path.write_text(
        json.dumps({"host": platform.node(), "results": rows}, indent=2), encoding="utf-8"
    )
    return result


def load(path: Path = LEDGER) -> list[Result]:
    if not path.exists():
        return []
    return [Result(**r) for r in json.loads(path.read_text(encoding="utf-8"))["results"]]


def table(path: Path = LEDGER, sort_by: str = "recorded_at") -> str:
    rows = load(path)
    if not rows:
        return "no experiments recorded yet"
    rows.sort(key=lambda r: (getattr(r, sort_by) is None, getattr(r, sort_by)), reverse=(sort_by != "recorded_at"))
    out = [
        f"  {'experiment':<34} {'prec':>6} {'recall':>7} {'F1':>6} {'fp':>6} {'thr':>6} "
        f"{'pos/neg':>9}  extra",
        "  " + "-" * 104,
    ]
    for r in rows:
        fmt = lambda v: f"{v:.3f}" if isinstance(v, float) else "-"
        extra = " ".join(f"{k}={v:.3f}" if isinstance(v, float) else f"{k}={v}" for k, v in r.extra.items())
        out.append(
            f"  {r.name[:34]:<34} {fmt(r.precision):>6} {fmt(r.recall):>7} {fmt(r.f1):>6} "
            f"{fmt(r.fp_rate):>6} {fmt(r.threshold):>6} "
            f"{(str(r.n_pos) + '/' + str(r.n_neg)) if r.n_pos is not None else '-':>9}  {extra[:40]}"
        )
    return "\n".join(out)


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--sort-by", default="recorded_at", choices=("recorded_at", "f1", "recall", "precision"))
    print(table(sort_by=ap.parse_args().sort_by))
