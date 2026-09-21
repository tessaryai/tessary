# SPDX-License-Identifier: Apache-2.0
"""Scorers — how the harness turns text into a score in [0,1].

A ``Scorer`` is any callable ``list[str] -> list[float]``. Two ship:

- ``ClassifyServiceScorer`` — calls the standalone **classify-service** ``/classify`` (extracted
  from the launcher onto its own box), so we baseline the *actual* head the platform runs.
- ``LocalHFScorer`` — loads a local HuggingFace/ONNX model directly, for evaluating a freshly
  trained artifact before it's deployed to the classify-service.
"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from typing import Protocol


class Scorer(Protocol):
    def __call__(self, texts: list[str]) -> list[float]: ...


class PairScorer(Protocol):
    """A PAIR head scores a (premise, claim) RELATIONSHIP, not one string in isolation.

    Separate from ``Scorer`` rather than an overload of it because the two request shapes are
    mutually exclusive per head on the wire — ``/classify`` rejects a pair head sent ``texts`` and
    a single-text head sent ``pairs`` — so a caller that can confuse them is a caller that gets a
    400 rather than a wrong number.
    """

    def score_pairs(self, pairs: list[tuple[str, str]]) -> list[float]: ...


class ClassifyServiceScorer:
    """POST /classify on the standalone classify-service. ``head`` is a name in its ``models.json``
    (e.g. 'refusal'). Config mirrors the backend's ``tessary.observer.encoder.*``:

    - URL  from ``CLASSIFY_URL`` (default ``http://localhost:8080``). Note the dev compose service is
      ``expose``-only, so from the host you need it published/port-forwarded, or point at the ECS URL.
    - key  from ``CLASSIFY_API_KEY`` (default ``dev-classify-key``, the compose dev default).

    The service caps concurrency (``MAX_INFLIGHT``) and returns 429 at capacity, so requests retry
    with backoff. Batches stay modest to stay under its body-size (413) limit.
    """

    def __init__(self, head: str, base_url: str | None = None, api_key: str | None = None, batch: int = 32):
        self.head = head
        self.base_url = (base_url or os.environ.get("CLASSIFY_URL", "http://localhost:8080")).rstrip("/")
        self.api_key = api_key or os.environ.get("CLASSIFY_API_KEY", "dev-classify-key")
        self.batch = batch

    def _post(self, chunk: list[str], attempts: int = 6) -> list[float]:
        return self._post_body({"head": self.head, "texts": chunk}, attempts)

    def _post_pairs(self, chunk: list[tuple[str, str]], attempts: int = 6) -> list[float]:
        payload = {"head": self.head, "pairs": [{"premise": p, "claim": c} for p, c in chunk]}
        return self._post_body(payload, attempts)

    def _post_body(self, payload: dict, attempts: int = 6) -> list[float]:
        body = json.dumps(payload).encode()
        for attempt in range(attempts):
            req = urllib.request.Request(
                f"{self.base_url}/classify",
                data=body,
                headers={"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"},
            )
            try:
                with urllib.request.urlopen(req, timeout=300) as resp:
                    return json.load(resp).get("scores", [])
            except urllib.error.HTTPError as e:
                # 429 = the service is at capacity (MAX_INFLIGHT); back off and retry.
                if e.code == 429 and attempt < attempts - 1:
                    time.sleep(min(2**attempt, 20))
                    continue
                raise
        raise RuntimeError("classify-service stayed at capacity (429) after retries")

    def __call__(self, texts: list[str]) -> list[float]:
        scores: list[float] = []
        for start in range(0, len(texts), self.batch):
            chunk = texts[start : start + self.batch]
            got = self._post(chunk)
            if len(got) != len(chunk):
                raise RuntimeError(f"/classify returned {len(got)} scores for {len(chunk)} texts")
            scores.extend(float(s) for s in got)
        return scores

    def score_pairs(self, pairs: list[tuple[str, str]]) -> list[float]:
        """One score per (premise, claim), index-aligned. The service does its own premise
        windowing and per-head reduction, so this is the scored population production sees."""
        scores: list[float] = []
        for start in range(0, len(pairs), self.batch):
            chunk = pairs[start : start + self.batch]
            got = self._post_pairs(chunk)
            if len(got) != len(chunk):
                raise RuntimeError(f"/classify returned {len(got)} scores for {len(chunk)} pairs")
            scores.extend(float(s) for s in got)
        return scores


class LocalHFScorer:
    """Score with a local text-classification model. ``positive_label`` is the class whose
    probability is the score (e.g. 'refusal', 'REJECTION', 'LABEL_1').

    Handles both artifact layouts ``train.py`` writes: a plain HuggingFace dir (``hf/``, with
    ``model.safetensors``/``pytorch_model.bin``) and an ONNX export dir (``onnx/``, with
    ``model.onnx``). A raw ONNX dir can't be loaded by ``transformers.pipeline`` directly, so when a
    ``model.onnx`` is present we load it via ``optimum.onnxruntime`` (in the ``train`` extra) and wrap
    it in a transformers pipeline. This makes the documented ``eval_baseline --local .../onnx`` path work.
    """

    def __init__(self, model_path: str, positive_label: str, batch: int = 32):
        from transformers import pipeline  # lazy: only when scoring locally

        if os.path.exists(os.path.join(model_path, "model.onnx")):
            # ONNX export dir — load through optimum's ORT model, then wrap in a pipeline.
            from optimum.onnxruntime import ORTModelForSequenceClassification  # lazy (train extra)
            from transformers import AutoTokenizer

            ort_model = ORTModelForSequenceClassification.from_pretrained(model_path)
            tokenizer = AutoTokenizer.from_pretrained(model_path)
            self.pipe = pipeline(
                "text-classification", model=ort_model, tokenizer=tokenizer, top_k=None
            )
        else:
            # Plain HuggingFace (torch) dir.
            self.pipe = pipeline("text-classification", model=model_path, top_k=None)
        self.positive_label = positive_label
        self.batch = batch

    def __call__(self, texts: list[str]) -> list[float]:
        scores: list[float] = []
        for start in range(0, len(texts), self.batch):
            chunk = texts[start : start + self.batch]
            for row in self.pipe(chunk, batch_size=len(chunk)):
                by_label = {r["label"]: r["score"] for r in row}
                scores.append(float(by_label.get(self.positive_label, 0.0)))
        return scores
