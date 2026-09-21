# SPDX-License-Identifier: Apache-2.0
"""Serving latency of the groundedness token head, by engine, device and input length.

The question this answers is the operator's one: how long does ONE response take to score, on what
hardware, as its evidence grows — and does a cheaper engine (int8, a GPU, CoreML) give the same
answer. The inputs are fixed once (`--build-inputs`) so every engine scores the identical set: real
RAGTruth test responses bucketed by their encoded length, and, for buckets the corpus does not
naturally fill, the same responses with extra retrieved documents appended from other rows until the
encoding lands in the bucket — real text, not padding, so attention has real work to do.

Engines (`--engine`):
  torch-cpu   PyTorch, fp32, `--threads` intra-op threads
  torch-mps   PyTorch on Apple Metal, `--dtype fp32|fp16`
  ort-cpu     onnxruntime CPUExecutionProvider on `--model` (the served fp32 export, or an int8 one)
  ort-coreml  onnxruntime CoreMLExecutionProvider (Apple Neural Engine / GPU), CPU fallback for what it cannot place
  http        the served path: classify-service over HTTP (`--url`, `--key`), i.e. transformers.js + onnxruntime-node

Every run records, per input: wall time of one forward pass at batch 1 and the response-level
P(unsupported) the engine produced, so a results file both times an engine and pins its parity
against the fp32 torch reference (`--reference results.json`).
"""

from __future__ import annotations

import argparse
import json
import statistics
import time
from pathlib import Path

from groundedness.token_data import lettuce_prompt

BUCKETS = [("<512", 0, 512), ("512-1k", 512, 1024), ("1k-2k", 1024, 2048), ("2k-4k", 2048, 4096),
           ("4k-8k", 4096, 8192), ("8k(cut)", 8192, 10**9)]
MAX_LENGTH = 8192
LABEL_UNSUPPORTED = (1, 2)


def _bucket(n: int) -> str | None:
    for name, lo, hi in BUCKETS:
        if lo <= n < hi:
            return name
    return None


def build_inputs(checkpoint: str, per_bucket: int, out: Path, seed: int = 7) -> None:
    import random
    from datasets import load_dataset
    from transformers import AutoTokenizer

    tok = AutoTokenizer.from_pretrained(checkpoint)
    rows = [r for r in load_dataset("wandb/RAGTruth-processed")["test"] if r["task_type"] in ("Summary", "QA")]
    rng = random.Random(seed)
    rng.shuffle(rows)

    def as_input(r, extra_docs):
        docs = [d.strip() for d in r["context"].split("\n\n") if d.strip()] + extra_docs
        question = None if r["task_type"] == "Summary" else r["query"]
        return {"passages": docs, "question": question, "answer": r["output"], "source_id": r["id"]}

    def length(inp):
        return len(tok(lettuce_prompt(inp["passages"], inp["question"]), inp["answer"], truncation=False)["input_ids"])

    chosen: dict[str, list] = {name: [] for name, _, _ in BUCKETS}
    # Natural fills first.
    for r in rows:
        inp = as_input(r, [])
        n = length(inp)
        b = _bucket(n)
        if b and len(chosen[b]) < per_bucket:
            inp["tokens"] = n
            inp["synthetic_docs"] = 0
            chosen[b].append(inp)
    # Synthetic fills: append other rows' documents until the encoding lands in the bucket.
    pool = [d.strip() for r in rows for d in r["context"].split("\n\n") if d.strip()]
    rng.shuffle(pool)
    pi = 0
    for name, lo, hi in BUCKETS:
        while len(chosen[name]) < per_bucket:
            if pi >= len(pool):
                raise SystemExit(f"document pool exhausted filling bucket {name} at {len(chosen[name])}/{per_bucket}")
            base = rng.choice(rows)
            extra = []
            inp = as_input(base, extra)
            n = length(inp)
            while n < lo and pi < len(pool):
                extra.append(pool[pi])
                pi += 1
                inp = as_input(base, extra)
                n = length(inp)
            if not (lo <= n < hi):
                # Overshot the top of a bounded bucket: drop the last doc and accept what fits, else skip.
                if n >= hi and hi < 10**9 and extra:
                    extra.pop()
                    inp = as_input(base, extra)
                    n = length(inp)
                if not (lo <= n < hi):
                    continue
            inp["tokens"] = n
            inp["synthetic_docs"] = len(extra)
            chosen[name].append(inp)
    flat = [dict(item, bucket=name) for name, _, _ in BUCKETS for item in chosen[name]]
    out.write_text(json.dumps({"checkpoint": checkpoint, "max_length": MAX_LENGTH, "inputs": flat}, indent=1))
    for name, _, _ in BUCKETS:
        print(name, [(i["tokens"], i["synthetic_docs"]) for i in chosen[name]])


# ---- engines ---------------------------------------------------------------------------------------

class TorchEngine:
    def __init__(self, checkpoint: str, device: str, dtype: str, threads: int | None, attn: str | None):
        import torch
        from transformers import AutoModelForTokenClassification, AutoTokenizer

        self.torch = torch
        if threads:
            torch.set_num_threads(threads)
        self.tok = AutoTokenizer.from_pretrained(checkpoint)
        kw = {"attn_implementation": attn} if attn else {}
        self.model = AutoModelForTokenClassification.from_pretrained(checkpoint, **kw).eval()
        self.device = device
        if dtype == "fp16":
            self.model = self.model.half()
        elif dtype == "bf16":
            self.model = self.model.to(torch.bfloat16)
        self.model.to(device)

    def score(self, inp) -> float:
        enc = self.tok(lettuce_prompt(inp["passages"], inp["question"]), inp["answer"], truncation="only_first",
                       max_length=MAX_LENGTH, return_tensors="pt")
        seq = enc.sequence_ids()
        enc = {k: v.to(self.device) for k, v in enc.items()}
        with self.torch.no_grad():
            logits = self.model(**enc).logits[0].float()
        if self.device == "mps":
            self.torch.mps.synchronize()
        p = logits.softmax(-1)
        resp = [i for i, s in enumerate(seq) if s == 1]
        return float((p[resp, 1] + p[resp, 2]).max())


class OrtEngine:
    def __init__(self, checkpoint: str, model_path: str, provider: str, threads: int | None):
        import numpy as np
        import onnxruntime as ort
        from transformers import AutoTokenizer

        self.np = np
        self.tok = AutoTokenizer.from_pretrained(checkpoint)
        so = ort.SessionOptions()
        if threads:
            so.intra_op_num_threads = threads
        providers = [provider, "CPUExecutionProvider"] if provider != "CPUExecutionProvider" else [provider]
        if provider == "CoreMLExecutionProvider":
            providers = [("CoreMLExecutionProvider", {"MLComputeUnits": "ALL", "ModelFormat": "MLProgram"}),
                         "CPUExecutionProvider"]
        self.sess = ort.InferenceSession(model_path, so, providers=providers)
        self.providers = self.sess.get_providers()

    def score(self, inp) -> float:
        enc = self.tok(lettuce_prompt(inp["passages"], inp["question"]), inp["answer"], truncation="only_first",
                       max_length=MAX_LENGTH, return_tensors="np")
        seq = enc.sequence_ids()
        feeds = {"input_ids": enc["input_ids"].astype(self.np.int64),
                 "attention_mask": enc["attention_mask"].astype(self.np.int64)}
        logits = self.sess.run(None, feeds)[0][0].astype(self.np.float64)
        e = self.np.exp(logits - logits.max(-1, keepdims=True))
        p = e / e.sum(-1, keepdims=True)
        resp = [i for i, s in enumerate(seq) if s == 1]
        return float((p[resp, 1] + p[resp, 2]).max())


class HttpEngine:
    def __init__(self, url: str, key: str):
        import urllib.request

        self.url, self.key, self.req = url.rstrip("/"), key, urllib.request

    def score(self, inp) -> float:
        body = json.dumps({"head": "groundedness", "responses": [
            {"passages": inp["passages"], "question": inp["question"], "answer": inp["answer"]}]}).encode()
        r = self.req.Request(self.url + "/classify", data=body, method="POST",
                             headers={"Authorization": "Bearer " + self.key, "Content-Type": "application/json"})
        with self.req.urlopen(r, timeout=600) as resp:
            return float(json.loads(resp.read())["scores"][0]["unsupported"])


def run(args) -> None:
    spec = json.loads(Path(args.inputs).read_text())
    inputs = spec["inputs"]
    ck = spec["checkpoint"]
    t0 = time.time()
    if args.engine == "torch-cpu":
        eng = TorchEngine(ck, "cpu", args.dtype, args.threads, args.attn)
    elif args.engine == "torch-mps":
        eng = TorchEngine(ck, "mps", args.dtype, None, args.attn)
    elif args.engine == "ort-cpu":
        eng = OrtEngine(ck, args.model, "CPUExecutionProvider", args.threads)
    elif args.engine == "ort-coreml":
        eng = OrtEngine(ck, args.model, "CoreMLExecutionProvider", args.threads)
    elif args.engine == "http":
        eng = HttpEngine(args.url, args.key)
    else:
        raise SystemExit("unknown engine " + args.engine)
    load_s = time.time() - t0
    # Warm-up on the shortest input, twice: JIT, kernel selection, page-in.
    for _ in range(2):
        eng.score(inputs[0])
    results = []
    for inp in inputs:
        if args.max_tokens and inp["tokens"] > args.max_tokens:
            continue
        t = time.time()
        try:
            score = eng.score(inp)
            err = None
        except Exception as e:  # noqa: BLE001 — the point is to record the failure per input
            score, err = None, f"{type(e).__name__}: {str(e)[:200]}"
        dt = time.time() - t
        results.append({"bucket": inp["bucket"], "tokens": inp["tokens"], "source_id": inp["source_id"],
                        "seconds": dt, "unsupported": score, "error": err})
        print(f"{inp['bucket']:8s} {inp['tokens']:5d} tok  {dt:7.3f}s  p={score if score is None else round(score, 4)}  {err or ''}",
              flush=True)
    label = args.label or f"{args.engine}" + (f"-t{args.threads}" if args.threads else "") + (
        f"-{args.dtype}" if args.engine.startswith("torch") else "")
    summary = {}
    for name, _, _ in BUCKETS:
        xs = [r["seconds"] for r in results if r["bucket"] == name and r["error"] is None]
        if xs:
            summary[name] = {"n": len(xs), "median_s": statistics.median(xs), "max_s": max(xs),
                             "per_min": 60.0 / statistics.median(xs)}
    out = {"label": label, "engine": args.engine, "threads": args.threads, "dtype": args.dtype, "model": args.model,
           "attn": args.attn, "providers": getattr(eng, "providers", None), "load_seconds": load_s,
           "summary": summary, "results": results}
    if args.reference:
        ref = {r["source_id"] + r["bucket"]: r["unsupported"] for r in json.loads(Path(args.reference).read_text())["results"]}
        deltas = [abs(r["unsupported"] - ref[r["source_id"] + r["bucket"]]) for r in results
                  if r["unsupported"] is not None and (r["source_id"] + r["bucket"]) in ref]
        out["parity"] = {"n": len(deltas), "max_abs_delta": max(deltas) if deltas else None,
                         "mean_abs_delta": statistics.fmean(deltas) if deltas else None}
    Path(args.out).write_text(json.dumps(out, indent=1))
    print(json.dumps({"label": label, "load_s": round(load_s, 1), "summary": {k: round(v["median_s"], 3) for k, v in summary.items()},
                      "parity": out.get("parity")}))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", default="artifacts/groundedness/groundedness-token-train-20260917-102552")
    ap.add_argument("--build-inputs", metavar="OUT.json")
    ap.add_argument("--per-bucket", type=int, default=4)
    ap.add_argument("--inputs", default="artifacts/groundedness/bench/inputs.json")
    ap.add_argument("--engine", choices=["torch-cpu", "torch-mps", "ort-cpu", "ort-coreml", "http"])
    ap.add_argument("--threads", type=int)
    ap.add_argument("--dtype", default="fp32", choices=["fp32", "fp16", "bf16"])
    ap.add_argument("--attn", choices=["sdpa", "eager"])
    ap.add_argument("--model", help="ONNX file for the ort engines")
    ap.add_argument("--url", default="http://127.0.0.1:18080")
    ap.add_argument("--key", default="dev-classify-key")
    ap.add_argument("--max-tokens", type=int, help="skip inputs longer than this (memory-bound engines)")
    ap.add_argument("--label")
    ap.add_argument("--reference", help="a results file whose scores are the parity reference")
    ap.add_argument("--out", default="bench.json")
    args = ap.parse_args()
    if args.build_inputs:
        Path(args.build_inputs).parent.mkdir(parents=True, exist_ok=True)
        build_inputs(args.checkpoint, args.per_bucket, Path(args.build_inputs))
        return
    if not args.engine:
        ap.error("--engine is required unless --build-inputs")
    run(args)


if __name__ == "__main__":
    main()
