# SPDX-License-Identifier: Apache-2.0
"""Shared data schema for the classifier modules.

Every classifier speaks the same two shapes so the harness, the judge-labeling, and the
training scripts are reusable across classifiers:

- ``LabeledExample`` — one (text, label) training row. ``label`` is 1 for the positive class
  (e.g. a refusal) and 0 for the negative class.
- ``EvalItem`` — one held-out evaluation row, same fields plus ``slices`` so the harness can
  report metrics broken down by refusal type, domain, etc.

Both persist as JSONL (one JSON object per line) — the lingua franca of every script here.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Iterable, Iterator


@dataclass(slots=True)
class LabeledExample:
    """One training row. ``label`` is 1 (positive class) or 0 (negative class).

    ``context`` is the rendered prior-turns string per the serialization contract
    (``framework.context``, contract v2); "" means single-turn. ``text`` is always the scored
    unit alone (e.g. the final user message), never the concatenated input — consumers compose
    the model input with ``framework.context.render_input(context, text)``.
    """

    text: str
    label: int
    source: str  # provenance: which dataset / judge / synthesis produced this row
    meta: dict = field(default_factory=dict)
    context: str = ""


@dataclass(slots=True)
class EvalItem:
    """One held-out evaluation row, with named slices for sliced metrics.

    Same ``context`` semantics as ``LabeledExample`` — and the harness reports the
    single-turn slice (``context == ""``) separately, per SYNTHESIS.md §7 decision 4.
    """

    text: str
    label: int
    source: str
    slices: dict[str, str] = field(default_factory=dict)
    context: str = ""


def write_jsonl(path: str | Path, rows: Iterable[LabeledExample | EvalItem]) -> int:
    """Write dataclass rows as JSONL; returns the count written."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    n = 0
    with path.open("w", encoding="utf-8") as fh:
        for row in rows:
            fh.write(json.dumps(asdict(row), ensure_ascii=False) + "\n")
            n += 1
    return n


def read_examples(path: str | Path) -> Iterator[LabeledExample]:
    """Stream ``LabeledExample`` rows back from JSONL."""
    with Path(path).open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                d = json.loads(line)
                meta = d.get("meta", {})
                yield LabeledExample(
                    text=d["text"],
                    label=int(d["label"]),
                    source=d["source"],
                    meta=meta,
                    # Older/synthesis rows carried context inside meta; first-class field wins.
                    context=d.get("context") or meta.get("context", ""),
                )


def read_evalset(path: str | Path) -> list[EvalItem]:
    """Load an eval set from JSONL into memory (eval sets are small)."""
    out: list[EvalItem] = []
    with Path(path).open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                d = json.loads(line)
                out.append(
                    EvalItem(
                        text=d["text"],
                        label=int(d["label"]),
                        source=d["source"],
                        slices=d.get("slices", {}),
                        context=d.get("context", ""),
                    )
                )
    return out
