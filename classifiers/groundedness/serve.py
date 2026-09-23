# SPDX-License-Identifier: Apache-2.0
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "torch>=2.4",
#     "transformers>=4.48",
# ]
# ///
"""Serve the groundedness model over HTTP, on the GPU of the machine it runs on.

One file with no imports from this repository, so it runs straight from its URL:

    uv run https://raw.githubusercontent.com/tessaryai/tessary/<ref>/classifiers/groundedness/serve.py --key <secret>

uv reads the dependency block above and installs torch and transformers on first run. The setup
guides in `setup/` are the supported way to run it, on a Mac with Apple silicon or on a GPU instance.
The backend reaches it through TESSARY_OBSERVER_ENCODER_URL and TESSARY_OBSERVER_ENCODER_API_KEY:

    POST /classify   Authorization: Bearer <key>
                     {"head": "groundedness", "responses": [{"passages": [...], "question": "...?", "answer": "..."}]}
                  -> {"scores": [{"unsupported": p, "conflict": p, "spans": [{"start", "end", "unsupported", "conflict"}]}]}
    GET  /healthz -> {"ok": true, "heads": ["groundedness"], "device", "dtype", "idle_seconds"}

`contract/` holds one request and one response in this shape; the backend's tests and this file's
tests both read them. `/healthz` needs no key. `idle_seconds` counts from the end of the last
`/classify` request (or from startup), and `--idle-exit-minutes` stops the process once it passes that
many minutes, so a GPU instance can stop itself between sweeps.

The encoding is the one the model was trained and evaluated on (experiments repo,
groundedness-token-v1): the passages and question laid out as `lettuce_prompt`, the answer as the
second sequence, the context truncated first, and one score per answer sentence.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import re
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable, Iterable

log = logging.getLogger("groundedness.serve")

DEFAULT_MODEL = "tessaryai/groundedness-classifier-v1"
DEFAULT_REVISION = "6746fa25f4f6cdb60f994f056c1919300e6c2b12"
MAX_LENGTH = 8192
MAX_RESPONSES = int(os.environ.get("GROUNDEDNESS_MAX_RESPONSES", "16"))
MAX_PASSAGE_CHARS = int(os.environ.get("GROUNDEDNESS_MAX_PASSAGE_CHARS", "20000"))
MAX_ANSWER_CHARS = int(os.environ.get("GROUNDEDNESS_MAX_ANSWER_CHARS", "8000"))
BASELESS, CONFLICT = 1, 2


# The encoding helpers, copied verbatim from the training code (ragtruth_pairs.py and token_data.py
# in the experiments repo's groundedness-token-v1) so this file runs alone. A copy can drift, so
# tests/fixtures/groundedness_answer_key.json records what the originals produce on the pinned
# tokenizer, and tests/test_groundedness_serve.py checks these copies against it.
_SENT = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9\"'(])")
MIN_SENT_CHARS = 20


def _sentences(text: str) -> Iterable[tuple[int, int, str]]:
    """(start, end, sentence) with character offsets into `text`, so spans can be aligned."""
    pos = 0
    for m in _SENT.finditer(text):
        yield pos, m.start(), text[pos : m.start()]
        pos = m.end()
    yield pos, len(text), text[pos:]


_LETTUCE_QA = ("Briefly answer the following question:\n{question}\nBear in mind that your response should be "
               "strictly based on the following {n} passages:\n{context}\nIn case the passages do not contain the "
               "necessary information to answer the question, please reply with: \"Unable to answer based on "
               "given passages.\"\noutput:")
_LETTUCE_SUMMARY = "Summarize the following text:\n{text}\noutput:"


def lettuce_prompt(passages: list[str], question: str | None) -> str:
    ctx = "\n".join(f"passage {i + 1}: {p}" for i, p in enumerate(passages))
    if question:
        return _LETTUCE_QA.format(question=question, n=len(passages), context=ctx)
    return _LETTUCE_SUMMARY.format(text=ctx)


def pick_device(requested: str) -> str:
    import torch

    if requested != "auto":
        return requested
    if torch.backends.mps.is_available():
        return "mps"
    if torch.cuda.is_available():
        return "cuda"
    return "cpu"


class Gate:
    """A concurrency bound in front of the one GPU: at most `max_inflight` requests score at once,
    at most `max_queue` wait for a slot, and a waiter gives up after `timeout_s`. Past either bound the
    request is answered 429 with Retry-After, so the backend backs off in-process instead of piling
    threads onto the one GPU and timing out its own 5-minute requests."""

    def __init__(self, max_inflight: int, max_queue: int, timeout_s: float):
        self.slots = threading.BoundedSemaphore(max(1, max_inflight))
        self.max_queue = max(0, max_queue)
        self.timeout_s = timeout_s
        self.waiting = 0
        self.lock = threading.Lock()

    def acquire(self) -> bool:
        if self.slots.acquire(blocking=False):
            return True  # a free slot: no queueing at all
        with self.lock:
            if self.waiting >= self.max_queue:
                return False  # the queue is full: refuse now rather than hold a socket open
            self.waiting += 1
        try:
            return self.slots.acquire(timeout=self.timeout_s)
        finally:
            with self.lock:
                self.waiting -= 1

    def release(self) -> None:
        self.slots.release()


class IdleClock:
    """Seconds since the last `/classify` request finished, or since startup if none has. A request
    still in flight counts as activity, so a long batch never reads as idle. `/healthz` reports it and
    `--idle-exit-minutes` acts on it; health probes don't reset it."""

    def __init__(self, now: Callable[[], float] = time.monotonic):
        self.now = now
        self.last = now()
        self.busy = 0
        self.lock = threading.Lock()

    def start(self) -> None:
        with self.lock:
            self.busy += 1

    def finish(self) -> None:
        with self.lock:
            self.busy -= 1
            self.last = self.now()

    def seconds(self) -> int:
        with self.lock:
            return 0 if self.busy else int(self.now() - self.last)


def watch_idle(clock: IdleClock, minutes: int, stop: Callable[[], None], interval_s: float = 60.0) -> None:
    """Call `stop` once `clock` has been idle for `minutes`, checking every `interval_s`. Runs on a
    daemon thread; `stop` shuts the server down so the process exits 0."""
    while True:
        time.sleep(interval_s)
        if clock.seconds() >= minutes * 60:
            log.info("no /classify request for %d minute(s); exiting", minutes)
            stop()
            return


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
        # An answer that leaves no room for context is refused as a 400 ("answer too long to
        # score"); HF's only_first truncation would instead log and run the over-long pair, so the
        # bound is enforced here before encoding.
        if len(self.tok(answer, add_special_tokens=False)["input_ids"]) > MAX_LENGTH - 3 - 1:
            raise ValueError("groundedness answer too long to score")
        enc = encode(self.tok, passages, question, answer, return_tensors="pt")
        offsets = enc.pop("offset_mapping")[0].tolist()
        seq = enc.sequence_ids()
        with self.lock, self.torch.no_grad():
            logits = self.model(**{k: v.to(self.device) for k, v in enc.items()}).logits[0].float()
            if self.device == "mps":
                self.torch.mps.synchronize()
            probs = logits.softmax(-1).cpu().tolist()
        return reduce(probs, offsets, seq, answer)


def encode(tok, passages: list[str], question: str | None, answer: str, return_tensors: str | None = None):
    """The model's input for one response: the prompt as the first sequence and the answer as the
    second, with only the prompt truncated to fit MAX_LENGTH, and character offsets for `reduce`."""
    return tok(lettuce_prompt(passages, question), answer, truncation="only_first", max_length=MAX_LENGTH,
               return_offsets_mapping=True, return_tensors=return_tensors)


def reduce(probs: list[list[float]], offsets: list[list[int]], seq: list, answer: str) -> dict:
    """Per sentence of the answer (at least MIN_SENT_CHARS once stripped), the max over its tokens of
    P(BASELESS)+P(CONFLICT) and of P(CONFLICT); the response takes the max sentence. `seq` is the
    tokenizer's sequence_ids (1 marks answer tokens)."""
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
        raise ValueError(f"unknown head: {payload.get('head')}")
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


def make_handler(head: Head, key: str, gate: Gate, clock: IdleClock):
    class Handler(BaseHTTPRequestHandler):
        def _json(self, status: int, body: dict, headers: dict | None = None) -> None:
            data = json.dumps(body).encode()
            self.send_response(status)
            for k, v in (headers or {}).items():
                self.send_header(k, v)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):  # noqa: N802 — http.server's contract
            if self.path == "/healthz":
                return self._json(200, {"ok": True, "heads": ["groundedness"], "device": head.device,
                                        "dtype": head.dtype, "idle_seconds": clock.seconds()})
            return self._json(404, {"error": "not found"})

        def do_POST(self):  # noqa: N802
            if self.path != "/classify":
                return self._json(404, {"error": "not found"})
            if self.headers.get("Authorization") != f"Bearer {key}":
                return self._json(401, {"error": "unauthorized"})
            clock.start()
            try:
                return self._classify()
            finally:
                clock.finish()

        def _classify(self):
            try:
                payload = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
                responses = validate(payload)
            except ValueError as e:
                return self._json(400, {"error": str(e)})
            except Exception as e:  # noqa: BLE001 — malformed JSON is a 400, not a crash
                return self._json(400, {"error": f"bad request: {e}"})
            if not gate.acquire():
                return self._json(429, {"error": "at capacity, retry later"}, {"Retry-After": "2"})
            started = time.time()
            try:
                scores = [head.score(r["passages"], r.get("question"), r["answer"]) for r in responses]
            except ValueError as e:  # a request-shaped refusal (an over-long answer): the caller's to fix
                return self._json(400, {"error": str(e)})
            except Exception as e:  # noqa: BLE001 — an OOM or a device fault is a 500, never a dropped socket
                log.exception("scoring failed on %s", head.device)
                return self._json(500, {"error": f"scoring failed: {type(e).__name__}"})
            finally:
                gate.release()
            log.info("scored %d response(s) in %.3fs on %s", len(scores), time.time() - started, head.device)
            return self._json(200, {"scores": scores})

        def log_message(self, fmt, *args):  # quiet the per-request access log; the INFO line above suffices
            return

    return Handler


def main() -> None:
    ap = argparse.ArgumentParser(description="Serve the groundedness model on this machine's GPU.")
    ap.add_argument("--model", default=DEFAULT_MODEL, help="Hugging Face repo id or a local checkpoint directory")
    ap.add_argument("--revision", default=DEFAULT_REVISION, help="pinned commit for a hub model")
    ap.add_argument("--device", default="auto", help="auto | mps | cuda | cpu")
    ap.add_argument("--dtype", default="fp32", choices=["fp32", "fp16"])
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=int(os.environ.get("PORT", "18080")))
    ap.add_argument("--key", default=os.environ.get("CLASSIFY_API_KEY"),
                    help="the bearer key the backend presents (TESSARY_OBSERVER_ENCODER_API_KEY); generated if omitted")
    ap.add_argument("--max-inflight", type=int, default=int(os.environ.get("MAX_INFLIGHT", "1")),
                    help="requests scoring at once (one GPU: 1); the backend's encoder.max-inflight must not exceed it")
    ap.add_argument("--max-queue", type=int, default=int(os.environ.get("MAX_QUEUE", "8")),
                    help="requests allowed to wait for a slot before 429")
    ap.add_argument("--queue-timeout", type=float, default=float(os.environ.get("QUEUE_TIMEOUT_S", "20")),
                    help="seconds a request may wait for a slot before 429")
    ap.add_argument("--idle-exit-minutes", type=int, default=0,
                    help="exit (code 0) after this many minutes with no /classify request; 0 never exits")
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
    gate = Gate(args.max_inflight, args.max_queue, args.queue_timeout)
    log.info("inflight %d, queue %d, queue timeout %.0fs; past those the answer is 429 + Retry-After",
             args.max_inflight, args.max_queue, args.queue_timeout)
    clock = IdleClock()
    server = ThreadingHTTPServer((args.host, args.port), make_handler(head, key, gate, clock))
    if args.idle_exit_minutes > 0:
        threading.Thread(target=watch_idle, args=(clock, args.idle_exit_minutes, server.shutdown), daemon=True).start()
        log.info("exits after %d idle minute(s)", args.idle_exit_minutes)
    server.serve_forever()


if __name__ == "__main__":
    main()
