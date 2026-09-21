# SPDX-License-Identifier: Apache-2.0
"""Turn a verified generated corpus into eval pairs.

Separate from `pairs.py` on purpose. The two sets have different provenance — one authored by a
person, one written by a model and filtered by a second model — and a run that silently mixed them
would report one number over two populations. `eval_ablations.py` takes them as named corpora so
every table says which it measured.

This is also where the long-premise shapes live. `pairs.py` tops out around 2,700 characters, which
four chunks already cover; generated documents run 3,500-4,000 characters each, so `six_long`
assembles ~22,000 characters and lands past the 20,000 clamp — the regime where the window budget
and the even-spacing sampler finally do something.
"""

from __future__ import annotations

import json
from pathlib import Path

from .labels import is_positive
from .pairs import Pair
from .verify_corpus import OUT as VERIFIED

#: `name -> (documents in the premise, 0-based index of the source)`. Deliberately overlapping
#: `pairs.py`'s names where the shape matches, so a slice breakdown reads across both corpora.
LONG_SHAPES: dict[str, tuple[int, int]] = {
    "single": (1, 0),
    "three_first": (3, 0),
    "four_mid": (4, 2),
    "six_deep": (6, 4),
    "six_last": (6, 5),  # the source is the LAST document, past where the sampler tends to look
}


def _merge(paths: list[Path]) -> dict:
    """Concatenate several verified corpora into one. Document ids are globally unique (each is
    prefixed with its domain), so a collision means the same domain was generated twice and the
    second copy is dropped rather than silently duplicating premises."""
    merged: dict = {"verified": True, "documents": [], "claims": [], "dropped": [], "parts": []}
    seen_docs: set[str] = set()
    kappas: list[float] = []
    for path in paths:
        corpus = json.loads(path.read_text(encoding="utf-8"))
        if not corpus.get("verified"):
            raise ValueError(
                f"{path} is not marked verified. generate_corpus.py writes intent labels; run "
                "verify_corpus.py before measuring anything against them."
            )
        new_docs = [d for d in corpus["documents"] if d["id"] not in seen_docs]
        seen_docs.update(d["id"] for d in new_docs)
        kept_ids = {d["id"] for d in new_docs}
        merged["documents"].extend(new_docs)
        merged["claims"].extend(c for c in corpus["claims"] if c["source"] in kept_ids)
        merged["dropped"].extend(corpus.get("dropped", []))
        merged["parts"].append(path.name)
        if corpus.get("kappa") is not None:
            kappas.append(float(corpus["kappa"]))
        merged.setdefault("generator", corpus.get("generator"))
        merged.setdefault("verifier_model", corpus.get("verifier_model"))
    # The weakest part's agreement, not the mean: a corpus is only as trustworthy as its worst half.
    merged["kappa"] = min(kappas) if kappas else None
    return merged


def load(path: str | Path | None = None) -> tuple[list[Pair], dict]:
    """Every verified claim in every long-premise shape, plus the corpus's own metadata.

    With no path, merges every `verified_corpus*.json` under `data/groundedness/` so a corpus grown
    in batches is one population rather than several runs nobody compares.
    """
    if path is None:
        paths = sorted(Path("data/groundedness").glob("verified_corpus*.json"))
        if not paths:
            raise FileNotFoundError("no data/groundedness/verified_corpus*.json — run verify_corpus.py")
    else:
        paths = [Path(path)]
    corpus = _merge(paths)
    docs = {d["id"]: d for d in corpus["documents"]}

    out: list[Pair] = []
    for claim in corpus["claims"]:
        src = docs[claim["source"]]
        same = [d for d in corpus["documents"] if d["domain"] == src["domain"] and d["id"] != src["id"]]
        other = [d for d in corpus["documents"] if d["domain"] != src["domain"]]
        pool = same + other
        for shape, (total, position) in LONG_SHAPES.items():
            chosen = pool[: total - 1]
            chosen = chosen[:]
            chosen.insert(min(position, len(chosen)), src)
            out.append(
                Pair(
                    documents=tuple(d["text"] for d in chosen),
                    premise="\n\n".join(d["text"] for d in chosen),
                    claim=claim["text"],
                    label=is_positive(claim["kind"]),
                    source=f"generated:{claim['source']}",
                    slices={
                        "kind": claim["kind"],
                        "shape": shape,
                        "domain": src["domain"],
                        "decontextualized": "false",
                    },
                )
            )
    return out, corpus


def summary(path: str | Path | None = None) -> str:
    pairs, corpus = load(path)
    n_claims = len(corpus["claims"])
    pos = sum(1 for c in corpus["claims"] if is_positive(c["kind"]))
    doc_lens = [len(d["text"]) for d in corpus["documents"]]
    lines = [
        f"generator: {corpus.get('generator')}  verifier: {corpus.get('verifier_model')}  "
        f"weakest kappa: {corpus.get('kappa'):.3f}",
        f"parts: {', '.join(corpus.get('parts', []))}",
        f"claims: {n_claims} kept ({pos} positive), {len(corpus.get('dropped', []))} dropped in verification",
        f"pairs:  {len(pairs)} = {n_claims} claims x {len(LONG_SHAPES)} shapes",
        f"documents: {len(corpus['documents'])} across "
        f"{len(set(d['domain'] for d in corpus['documents']))} domains, "
        f"{min(doc_lens)}-{max(doc_lens)} chars each",
        "premise chars by shape: " + ", ".join(
            f"{s}={len(next(p for p in pairs if p.slices['shape'] == s).premise)}" for s in LONG_SHAPES),
    ]
    return "\n".join(lines)


if __name__ == "__main__":
    print(summary())
