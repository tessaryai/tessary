# SPDX-License-Identifier: Apache-2.0
"""Serve the groundedness token head from a native process — on the GPU a container cannot reach.

The same HTTP contract as classify-service's `groundedness` head (classify-service/groundedness.js),
so the backend points at this instead of the container with two environment variables and nothing
else changes:

    POST /classify   Authorization: Bearer <key>
                     {"head": "groundedness", "responses": [{"passages": [...], "question": "...?", "answer": "..."}]}
                  -> {"scores": [{"unsupported": p, "conflict": p, "spans": [{"start", "end", "unsupported", "conflict"}]}]}
    GET  /healthz -> {"ok": true}

Why this exists: on a Mac, Docker cannot pass the GPU through, so a self-hosted stack's groundedness
classifier runs on CPU inside the VM — measured at 2 to 6 seconds per short response and unable to
score an 8k-token one at all. This process runs on the host with PyTorch on Metal (CUDA on Linux,
CPU anywhere else) and scores the same response in a tenth of a second, with the scores the
evaluation harness produced: the encoding here IS `token_eval`'s, and the sentence reduction mirrors
groundedness.js line for line.

Run:  uv run --extra train python -m groundedness.serve --key <secret> [--device mps] [--port 18080]
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from groundedness.ragtruth_pairs import MIN_SENT_CHARS, _sentences
from groundedness.token_data import lettuce_prompt

log = logging.getLogger("groundedness.serve")

DEFAULT_MODEL = "tessaryai/groundedness-token-v1"
DEFAULT_REVISION = "6746fa25f4f6cdb60f994f056c1919300e6c2b12"
MAX_LENGTH = 8192
MAX_RESPONSES = int(os.environ.get("GROUNDEDNESS_MAX_RESPONSES", "16"))
MAX_PASSAGE_CHARS = int(os.environ.get("GROUNDEDNESS_MAX_PASSAGE_CHARS", "20000"))
MAX_ANSWER_CHARS = int(os.environ.get("GROUNDEDNESS_MAX_ANSWER_CHARS", "8000"))
BASELESS, CONFLICT = 1, 2


def pick_device(requested: str) -> str:
    import torch

    if requested != "auto":
        return requested
    if torch.backends.mps.is_available():
        return "mps"
    if torch.cuda.is_available():
        return "cuda"
    return "cpu"


class Head:
    """The loaded model and the one scoring function. One forward pass per response, serialised."""

    def __init__(self, model: str, revision: str | None, device: str, dtype: str):
        import torch
        from transformers import AutoModelForTokenClassification, AutoTokenizer

        self.torch = torch
        kw = {"revision": revision} if revision and not os.path.isdir(model) else {}
        self.tok = AutoTokenizer.from_pretrained(model, **kw)
        self.model = AutoModelForTokenClassification.from_pretrained(model, **kw).eval()
        if dtype == "fp16":
            self.model = self.model.half()
        self.device = device
        self.model.to(device)
        self.dtype = dtype
        self.lock = threading.Lock()

    def score(self, passages: list[str], question: str | None, answer: str) -> dict:
        passages = [p[:MAX_PASSAGE_CHARS] for p in passages]
        answer = answer[:MAX_ANSWER_CHARS]
        # groundedness.js refuses an answer that leaves no room for context ("answer too long to
        # score", a 400); HF's only_first truncation would instead log and run the over-long pair,
        # so the same bound is enforced here before encoding.
        if len(self.tok(answer, add_special_tokens=False)["input_ids"]) > MAX_LENGTH - 3 - 1:
            raise ValueError("groundedness answer too long to score")
        enc = self.tok(lettuce_prompt(passages, question), answer, truncation="only_first",
                       max_length=MAX_LENGTH, return_offsets_mapping=True, return_tensors="pt")
        offsets = enc.pop("offset_mapping")[0].tolist()
        seq = enc.sequence_ids()
        with self.lock, self.torch.no_grad():
            logits = self.model(**{k: v.to(self.device) for k, v in enc.items()}).logits[0].float()
            if self.device == "mps":
                self.torch.mps.synchronize()
            probs = logits.softmax(-1).cpu().tolist()
        return reduce(probs, offsets, seq, answer)


def reduce(probs: list[list[float]], offsets: list[list[int]], seq: list, answer: str) -> dict:
    """groundedness.js `reduce`, in Python: per sentence of the answer (at least MIN_SENT_CHARS once
    stripped), the max over its tokens of P(BASELESS)+P(CONFLICT) and of P(CONFLICT); the response
    takes the max sentence. `seq` is the tokenizer's sequence_ids (1 marks answer tokens)."""
    answer_tokens = [(i, offsets[i]) for i, s in enumerate(seq) if s == 1]
    unsupported = conflict = 0.0
    spans = []
    for start, end, text in _sentences(answer):
        if len(text.strip()) < MIN_SENT_CHARS:
            continue
        u = c = 0.0
        for i, (a, b) in answer_tokens:
            if a < end and b > start:
                u = max(u, probs[i][BASELESS] + probs[i][CONFLICT])
                c = max(c, probs[i][CONFLICT])
        spans.append({"start": start, "end": end, "unsupported": u, "conflict": c})
        unsupported, conflict = max(unsupported, u), max(conflict, c)
    return {"unsupported": unsupported, "conflict": conflict, "spans": spans}


def validate(payload: dict) -> list[dict]:
    if payload.get("head") != "groundedness":
        raise ValueError(f"UNAVAILABLE_IN_OPEN_EDITION: head '{payload.get('head')}' is not served by this process — "
                         "it serves the groundedness token head only")
    if "texts" in payload or "pairs" in payload:
        raise ValueError("head 'groundedness' takes responses: [{passages[], question?, answer}] — not texts or pairs")
    responses = payload.get("responses")
    ok = isinstance(responses, list) and responses and all(
        isinstance(r, dict) and isinstance(r.get("passages"), list) and r["passages"]
        and all(isinstance(p, str) for p in r["passages"]) and isinstance(r.get("answer"), str)
        and (r.get("question") is None or isinstance(r.get("question"), str)) for r in responses)
    if not ok:
        raise ValueError("groundedness responses must be a non-empty array of {passages: string[], question?: string, answer: string}")
    if len(responses) > MAX_RESPONSES:
        raise ValueError(f"groundedness batch exceeds {MAX_RESPONSES} responses")
    return responses


def make_handler(head: Head, key: str):
    class Handler(BaseHTTPRequestHandler):
        def _json(self, status: int, body: dict) -> None:
            data = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):  # noqa: N802 — http.server's contract
            if self.path == "/healthz":
                return self._json(200, {"ok": True, "device": head.device, "dtype": head.dtype})
            return self._json(404, {"error": "not found"})

        def do_POST(self):  # noqa: N802
            if self.path != "/classify":
                return self._json(404, {"error": "not found"})
            if self.headers.get("Authorization") != f"Bearer {key}":
                return self._json(401, {"error": "unauthorized"})
            try:
                payload = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
                responses = validate(payload)
            except ValueError as e:
                return self._json(400, {"error": str(e)})
            except Exception as e:  # noqa: BLE001 — malformed JSON is a 400, not a crash
                return self._json(400, {"error": f"bad request: {e}"})
            started = time.time()
            try:
                scores = [head.score(r["passages"], r.get("question"), r["answer"]) for r in responses]
            except ValueError as e:  # a request-shaped refusal (an over-long answer): the caller's to fix
                return self._json(400, {"error": str(e)})
            except Exception as e:  # noqa: BLE001 — an OOM or a device fault is a 500, never a dropped socket
                log.exception("scoring failed on %s", head.device)
                return self._json(500, {"error": f"scoring failed: {type(e).__name__}"})
            log.info("scored %d response(s) in %.3fs on %s", len(scores), time.time() - started, head.device)
            return self._json(200, {"scores": scores})

        def log_message(self, fmt, *args):  # quiet the per-request access log; the INFO line above suffices
            return

    return Handler


def main() -> None:
    ap = argparse.ArgumentParser(description="Serve the groundedness token head natively (GPU where there is one).")
    ap.add_argument("--model", default=DEFAULT_MODEL, help="Hugging Face repo id or a local checkpoint directory")
    ap.add_argument("--revision", default=DEFAULT_REVISION, help="pinned commit for a hub model")
    ap.add_argument("--device", default="auto", help="auto | mps | cuda | cpu")
    ap.add_argument("--dtype", default="fp32", choices=["fp32", "fp16"])
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=int(os.environ.get("PORT", "18080")))
    ap.add_argument("--key", default=os.environ.get("CLASSIFY_API_KEY"),
                    help="the bearer key the backend presents (TESSARY_OBSERVER_ENCODER_API_KEY); generated if omitted")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    key = args.key or secrets.token_urlsafe(24)
    device = pick_device(args.device)
    started = time.time()
    head = Head(args.model, args.revision, device, args.dtype)
    head.score(["warm-up document, twenty characters long."], "warm-up?", "A warm-up answer sentence long enough to score.")
    log.info("loaded %s on %s (%s) in %.1fs", args.model, device, args.dtype, time.time() - started)
    if not args.key:
        log.info("no --key given; generated one for this run: %s", key)
    log.info("listening on http://%s:%d — set TESSARY_OBSERVER_ENCODER_URL=http://host.docker.internal:%d and "
             "TESSARY_OBSERVER_ENCODER_API_KEY to the key", args.host, args.port, args.port)
    ThreadingHTTPServer((args.host, args.port), make_handler(head, key)).serve_forever()


if __name__ == "__main__":
    main()
