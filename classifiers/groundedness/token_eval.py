# SPDX-License-Identifier: Apache-2.0
"""Score a token classifier on the same corpora, at the same operating point, as the pair head.

THE COMPARABLE NUMBER. The pair head is measured as sentence recall at fp <= 2% with the threshold
cross-validated by response. To compare like with like, one pass per response produces per-token
P(CONFLICT); a sentence's score is the MAX over its tokens; the sentences are the same ones
`ragtruth_pairs` builds (same splitter, same labels, same `cluster`), and the rows go through the
same `cv_threshold.cv_recall`. Nothing about the metric changes — only the model that produced the
scores.

Generated corpora are scored the same way with the premise as context and the claim as response,
so the old held-out tables get a row for the new model too.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

from .token_data import BASELESS_ID, CONFLICT_ID, TEMPLATES, context_of, generated_context, rows, spans_of
from .ragtruth_pairs import MIN_SENT_CHARS, _sentences


class TokenScorer:
    def __init__(self, checkpoint: str, max_length: int = 4096, batch: int = 4, template: str | None = None):
        import json
        import torch
        from pathlib import Path
        from transformers import AutoModelForTokenClassification, AutoTokenizer

        # The template is a property of the checkpoint: read it from provenance unless overridden.
        prov = Path(checkpoint) / "training_provenance.json"
        self.template = template or (json.loads(prov.read_text()).get("template", "plain") if prov.exists() else "plain")
        self.tok = AutoTokenizer.from_pretrained(checkpoint)
        self.model = AutoModelForTokenClassification.from_pretrained(checkpoint).eval()
        self.max_length, self.batch, self.torch = max_length, batch, torch
        self.passes = 0

    def token_probs(self, context: str, response: str) -> list[tuple[int, int, float, float]]:
        """(start, end, P(CONFLICT), P(BASELESS)) per response token, offsets into `response`."""
        enc = self.tok(context, response, truncation="only_first", max_length=self.max_length,
                       return_offsets_mapping=True, return_tensors="pt")
        offsets = enc.pop("offset_mapping")[0].tolist()
        seq = enc.sequence_ids()
        with self.torch.no_grad():
            probs = self.model(**enc).logits[0].softmax(-1)
        self.passes += 1
        pc, pb = probs[:, CONFLICT_ID].tolist(), probs[:, BASELESS_ID].tolist()
        return [(a, b, c, d) for (a, b), s, c, d in zip(offsets, seq, pc, pb) if s == 1 and b > a]

    def sentence_scores(self, context: str, response: str) -> list[tuple[str, float, float, int, int]]:
        """(sentence, max P(CONFLICT), max P(CONFLICT)+P(BASELESS), start, end) per sentence."""
        toks = self.token_probs(context, response)
        out = []
        for start, end, sent in _sentences(response):
            if len(sent.strip()) < MIN_SENT_CHARS:
                continue
            inside = [(c, c + d) for a, b, c, d in toks if a < end and b > start]
            out.append((sent.strip(), max((c for c, _ in inside), default=0.0),
                        max((u for _, u in inside), default=0.0), start, end))
        return out


def ragtruth_rows(scorer: TokenScorer, checkpoint: str, split: str = "test") -> list[dict]:
    from .ragtruth_pairs import CONFLICT  # noqa: F401  (label semantics shared)

    out = []
    for r in rows(split):
        spans = spans_of(r)
        conflicts = [(s, e) for s, e, l in spans if l == CONFLICT_ID]
        for sent, score_c, score_any, start, end in scorer.sentence_scores(context_of(r, scorer.template), r["output"]):
            hit = lambda sp: any(a < end and b > start for a, b, *_ in sp)
            out.append({"arm": "token-classifier", "checkpoint": checkpoint, "pair_index": len(out),
                        "cluster": str(r["id"]), "shape": r["task_type"].lower(), "domain": r["model"],
                        "source": f"ragtruth:{r['id']}",
                        # Both contracts from one pass: conflict-only (the pair head's) and
                        # any-unsupported (conflict OR baseless — the published RAGTruth target).
                        "label_conflict": int(hit(conflicts)), "label_any": int(hit(spans)),
                        "score_conflict": score_c, "score_any": score_any})
    return out


def generated_rows(scorer: TokenScorer, checkpoint: str, path: str) -> list[dict]:
    from .generated_pairs import load

    pairs, _ = load(path)
    out = []
    for i, p in enumerate(pairs):
        toks = scorer.token_probs(generated_context(p, scorer.template), p.claim)
        kind = p.slices["kind"]
        out.append({"arm": "token-classifier", "checkpoint": checkpoint, "pair_index": i,
                    "cluster": str(i // 5), "kind": kind, "shape": p.slices["shape"],
                    "source": p.source,
                    "label_conflict": p.label,
                    # Under the any-unsupported contract a neutral addition is positive, and so is a
                    # tool fact: true, but not in the retrieved evidence. That is the contract change.
                    "label_any": int(p.label == 1 or kind in ("neutral_addition", "tool_fact")),
                    "score_conflict": max((c for _, _, c, _ in toks), default=0.0),
                    "score_any": max((c + d for _, _, c, d in toks), default=0.0)})
    return out


def project(rws: list[dict], target: str, level: str) -> list[dict]:
    """Rows with `label`/`score` for one contract; `response` level takes the max over a response."""
    lab, sc = f"label_{target}", f"score_{target}"
    if level == "sentence":
        return [{**r, "label": r[lab], "score": r[sc]} for r in rws]
    by: dict[str, dict] = {}
    for r in rws:
        cur = by.get(r["cluster"])
        if cur is None:
            by[r["cluster"]] = {**r, "label": r[lab], "score": r[sc]}
        else:
            cur["label"] = max(cur["label"], r[lab]); cur["score"] = max(cur["score"], r[sc])
    return list(by.values())


def best_f1(rws: list[dict]) -> tuple[float, float, float, float]:
    """(F1, precision, recall, threshold) at the F1-maximising threshold — the literature's number."""
    ys = [r["label"] for r in rws]; ss = [r["score"] for r in rws]
    cands = sorted(set(ss)); cands = cands[:: max(1, len(cands) // 500)]
    best = (0.0, 0.0, 0.0, 1.0)
    for t in cands:
        tp = sum(1 for y, s_ in zip(ys, ss) if y and s_ >= t); fp = sum(1 for y, s_ in zip(ys, ss) if not y and s_ >= t)
        fn = sum(1 for y, s_ in zip(ys, ss) if y and s_ < t)
        pr = tp / (tp + fp) if tp + fp else 0.0; rc = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * pr * rc / (pr + rc) if pr + rc else 0.0
        if f1 > best[0]:
            best = (f1, pr, rc, t)
    return best


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--checkpoint", required=True)
    ap.add_argument("--corpus", default="ragtruth", choices=("ragtruth", "generated"))
    ap.add_argument("--corpus-path", default=None)
    ap.add_argument("--max-length", type=int, default=4096)
    ap.add_argument("--dump", required=True)
    ap.add_argument("--target", default="conflict", choices=("conflict", "any"),
                    help="conflict = the pair head's contract; any = conflict OR baseless (published RAGTruth target)")
    ap.add_argument("--template", default=None, choices=TEMPLATES, help="override the checkpoint's recorded template")
    ap.add_argument("--level", default="sentence", choices=("sentence", "response"))
    args = ap.parse_args()

    from .cv_threshold import cv_recall, in_sample
    from .labels import GATE
    from . import results

    scorer = TokenScorer(args.checkpoint, args.max_length, template=args.template)
    t0 = time.time()
    if args.corpus == "ragtruth":
        rws = ragtruth_rows(scorer, args.checkpoint)
        corpus = "ragtruth-test"
    else:
        rws = generated_rows(scorer, args.checkpoint, args.corpus_path)
        corpus = f"generated — HELD-OUT {args.corpus_path}"
    elapsed = time.time() - t0
    Path(args.dump).write_text(json.dumps({"corpus": corpus, "predictions": rws}), encoding="utf-8")
    report(rws, args, corpus, elapsed, scorer.passes)
    return 0


def report(rws: list[dict], args, corpus: str, elapsed: float, passes: int) -> None:
    from .cv_threshold import cv_recall, in_sample
    from .labels import GATE, UNSUPPORTED_GATE
    from . import results

    raw = rws
    rws = project(raw, args.target, args.level)
    response_gate = args.target == "any" and args.level == "response"
    ins = in_sample(rws)
    cv = cv_recall(rws, 5, guard=UNSUPPORTED_GATE.fit_guard if response_gate else 0.0)
    f1, pr, rc, thr = best_f1(rws)
    for r in rws:
        r["fired"] = bool(r["score"] >= ins["threshold"])

    if response_gate:
        # The contract-2 gate (labels.UNSUPPORTED_GATE): both parts, response-level.
        g = UNSUPPORTED_GATE
        gate = ("PASS" if g.passes(cv["recall"], cv["fp_rate"], cv["precision"], f1) else "fail") + (
            f" [B: recall>={g.min_recall} & prec>={g.min_precision} @fp<={g.max_fp}; A: best-F1>={g.min_best_f1}]")
    else:
        gate = ("PASS" if cv["recall"] >= GATE.min_recall and cv["fp_rate"] <= GATE.max_fp else "fail") + " [old sentence gate; informational]"
    print(f"corpus: {corpus}   target: {args.target}   level: {args.level}   rows: {len(rws)}  "
          f"positives: {cv['n_pos']}  passes: {passes}  ({elapsed:.0f}s)")
    print(f"  in-sample recall {ins['recall']:.3f} @ thr {ins['threshold']:.3f}")
    print(f"  CV recall {cv['recall']:.3f}  fp {cv['fp_rate']:.3f}  prec {cv['precision']:.3f}  "
          f"F1 {cv['f1']:.3f}   gate {gate}")
    print(f"  best-F1 operating point: F1 {f1:.3f}  prec {pr:.3f}  recall {rc:.3f}  @ thr {thr:.3f}")
    if args.corpus == "ragtruth" and args.level == "sentence":
        for key in ("shape", "domain"):
            print(f"  by {key}: " + "  ".join(
                f"{k}={sum(1 for r in rws if r[key] == k and r['label'] == 1 and r['score'] >= ins['threshold'])}"
                f"/{sum(1 for r in rws if r[key] == k and r['label'] == 1)}"
                for k in sorted({r[key] for r in rws})))
    results.record(results.Result(
        name=f"cv/token-classifier/{args.target}/{args.level}", checkpoint=args.checkpoint, corpus=corpus,
        precision=cv["precision"], recall=cv["recall"], fp_rate=cv["fp_rate"],
        threshold=sum(cv["thresholds"]) / len(cv["thresholds"]), n_pos=cv["n_pos"], n_neg=cv["n_neg"],
        config={"max_length": args.max_length, "target": args.target, "level": args.level,
                "score": "max over tokens of P(CONFLICT)" + (" + P(BASELESS)" if args.target == "any" else ""),
                "threshold_selection": "cross-validated by response"},
        extra={"in_sample_recall": round(ins["recall"], 3), "passes": passes, "seconds": round(elapsed),
               "best_f1": round(f1, 3), "best_f1_precision": round(pr, 3), "best_f1_recall": round(rc, 3)},
        note="long-context token classifier, one pass per response",
    ))


def rescore() -> int:
    """Re-read an existing dump under another target/level — no model pass needed."""
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump", required=True)
    ap.add_argument("--target", default="any", choices=("conflict", "any"))
    ap.add_argument("--level", default="response", choices=("sentence", "response"))
    ap.add_argument("--corpus", default="ragtruth")
    ap.add_argument("--checkpoint", default="?"); ap.add_argument("--max-length", type=int, default=4096)
    args = ap.parse_args()
    d = json.loads(Path(args.dump).read_text(encoding="utf-8"))
    rws = d["predictions"]
    args.checkpoint = rws[0]["checkpoint"]
    report(rws, args, d["corpus"], 0.0, 0)
    return 0


if __name__ == "__main__":
    import sys

    raise SystemExit(rescore() if "--rescore" in sys.argv and sys.argv.remove("--rescore") is None else main())
