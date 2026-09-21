# SPDX-License-Identifier: Apache-2.0
"""Score (premise, claim) pairs with a local three-way NLI checkpoint, the way the service does.

`ClassifyServiceScorer.score_pairs` is the right instrument for measuring the head we actually
serve. This is the instrument for measuring one we do NOT serve yet — a candidate checkpoint that
has no ONNX export and no `models.json` entry — without having to build either first.

It composes the shipping tiling (`sweep_corpus`) with a local model, so the only thing that differs
from production is the checkpoint. That matters: a bake-off that also changed the windowing would
not tell you which of the two moved the number.

The score returned is SUPPORT (`1 - P(contradiction)`), the same quantity `/classify` returns for
this head, so a threshold learned here transfers to the served head without rescaling.
"""

from __future__ import annotations

from dataclasses import dataclass

import re

#: Clause boundaries that usually separate independent assertions. Deliberately conservative:
#: splitting on every comma shatters "within 14 days, provided they are unworn" into fragments that
#: assert nothing, and a fragment that asserts nothing cannot be contradicted.
_CLAUSE_SPLIT = re.compile(r"\s*(?:,\s+and\s+|\s+and\s+|,\s+but\s+|\s+but\s+|;\s*)", re.IGNORECASE)

from .sweep_corpus import (
    windows_for,
    PAIR_CLAIM_MAX_CHARS,
    PAIR_MAX_CHUNKS,
    PAIR_MAX_PREMISE_CHARS,
    PAIR_TOTAL_CHARS,
    de_blob,
    premise_chunks_for,
    prepare_pair,
    reducer_for,
)


@dataclass(slots=True)
class Config:
    """The ablation axes. Defaults reproduce what the service does today (post-reducer-fix)."""

    max_chunks: int = PAIR_MAX_CHUNKS
    #: Premise characters per window for tiling="doc". The service budget (1,800 chars, ~400
    #: tokens) was sized for throughput, not for detection: on RAGTruth the contradicted fact is one
    #: clause of a news article and the rest of the window is entailed or neutral text pulling the
    #: softmax toward neutral. Smaller windows put the clause in front of the model; the price is
    #: passes per claim AND a wider false-fire surface (min over more windows), which is why this
    #: is measured at fixed FP and not assumed.
    window_chars: int = 0
    #: "byte" tiles the concatenated premise on byte offsets, as the service does. "doc" scores
    #: each retrieved document whole, which needs document boundaries the wire does not carry
    #: today — measuring it here is how we find out whether recovering them is worth a contract
    #: change.
    tiling: str = "byte"
    #: "min" is the fixed reducer (contradicted if ANY window contradicts). "max" reproduces the
    #: pre-fix behaviour so a run can show the delta rather than assert it.
    reduce: str = "min"
    #: How the two strings reach the model. "concat" is what classify.js does — one string,
    #: `chunk + eos_token + claim`, inherited from MiniCheck's input format. "pair" is the
    #: encoder's own pair path (`text_pair`), which is how an NLI checkpoint is normally fed and
    #: what puts the segment boundary where the model was trained to expect it.
    #: DEFAULT IS "concat" ON PURPOSE: the rig must measure what ships, and anything else reports
    #: a number that does not transfer. The "pair" arm exists to decide whether classify.js should
    #: change, not to quietly assume it already has.
    input_format: str = "concat"
    #: How the per-window scores become ONE decision statistic.
    #:   "none"   — max contradiction, compared against a global threshold. What ships today.
    #:   "margin" — max contradiction MINUS the median of the others, per observation.
    #:   "zscore" — (max - mean) / std over this observation's own windows.
    #: The last two exist because ranking and calibration came apart: our v3-large fine-tune has the
    #: BEST separation of any model tested (hit rate 0.880 vs the reference's 0.840) and the WORST
    #: gate recall among passing candidates (0.404 vs 0.668), with its threshold at 0.187 against the
    #: reference's 0.912. Good ranking that a global threshold cannot exploit is a calibration
    #: problem, and a per-observation statistic is the cheapest thing that turns rank into decision.
    #:   "contra_minus_entail" — P(contradiction) - P(entailment) per window, then max.
    normalize: str = "none"
    #: Split a claim into atomic clauses and score each separately, taking the max contradiction.
    #: Targets the measured deficit rather than a general hope: on the serving-shape model,
    #: `contradiction` claims reach 0.69 recall (already over the 0.66 bar) while `compound` claims —
    #: one sentence asserting TWO things, exactly one of which the source contradicts — reach 0.50.
    #: A single NLI forward pass over "You have 90 days to return it AND the refund lands in 2 hours"
    #: has to call the whole sentence contradicted on the strength of one clause, and the entailed
    #: clause pulls the score toward neutral. This is what `VerifiableClaims` does in production, so
    #: measuring it here says whether that component is worth changing.
    decompose: bool = False
    #: Minimum characters for a split clause to count as an independent assertion. Every extra
    #: scoring unit is another chance to fire, so over-splitting spends FP budget: decomposition
    #: cost the NC reference 0.262 recall because its threshold had to climb to 0.992 to hold
    #: fp<=0.02. A stricter floor yields fewer, more confident units.
    min_clause_chars: int = 25


class LocalNliPairScorer:
    """A three-way NLI checkpoint scored as a pair head. Torch, `train` extra."""

    def __init__(self, checkpoint: str, config: Config | None = None, batch: int = 16):
        import os

        import torch  # lazy: only when scoring locally
        from transformers import AutoModelForSequenceClassification, AutoTokenizer

        self._torch = torch
        self.checkpoint = checkpoint
        self.config = config or Config()
        self.batch = batch
        self.tok = AutoTokenizer.from_pretrained(checkpoint)

        # An ONNX export directory, detected the same way LocalHFScorer detects one, so "score the
        # thing the service serves" and "score the torch checkpoint" are one code path with one
        # tiling and one reducer. Anything else and an ONNX/torch comparison would be measuring two
        # harnesses as well as two runtimes.
        onnx = any(
            os.path.exists(os.path.join(str(checkpoint), n))
            for n in ("model.onnx", "model_quantized.onnx")
        )
        if onnx:
            from optimum.onnxruntime import ORTModelForSequenceClassification  # lazy (train extra)

            quantized = os.path.exists(os.path.join(str(checkpoint), "model_quantized.onnx"))
            self.model = ORTModelForSequenceClassification.from_pretrained(
                checkpoint, file_name="model_quantized.onnx" if quantized else "model.onnx"
            )
            self.runtime = "onnx-int8" if quantized else "onnx-fp32"
        else:
            self.model = AutoModelForSequenceClassification.from_pretrained(checkpoint).eval()
            self.runtime = "torch-fp32"

        labels = {i: l.lower() for i, l in self.model.config.id2label.items()}
        self.entail_index = next((i for i, l in labels.items() if l == "entailment"), None)
        try:
            self.contra_index = next(i for i, l in labels.items() if l == "contradiction")
        except StopIteration as exc:
            raise ValueError(
                f"{checkpoint} is not a three-way NLI head: id2label={self.model.config.id2label}. "
                "A binary support/not-support head cannot express the abstain this classifier "
                "depends on, which is the whole reason the head was swapped."
            ) from exc

    def _clauses(self, claim: str) -> list[str]:
        parts = [p.strip(" .,;") for p in _CLAUSE_SPLIT.split(claim)]
        # A clause under ~25 chars is a fragment, not an assertion ("and it is free"). Keep the whole
        # claim as one unit in that case rather than scoring noise.
        parts = [p for p in parts if len(p) >= self.config.min_clause_chars]
        return parts if len(parts) > 1 else [claim]

    def _windows_for(self, premise: str, claim: str, documents: tuple[str, ...] = ()) -> list[str]:
        chunks, clamped = prepare_pair(
            premise, claim, eos=self.tok.eos_token or "</s>", max_chunks=self.config.max_chunks
        )
        if self.config.tiling == "doc":
            # The premise's ACTUAL documents, passed in — never recovered by splitting the joined
            # string, which yields paragraphs wherever a document has a blank line of its own.
            # Not reachable in production without a wire change (SubstrateReadRepository joins
            # evidence rows with a bare "\n"); see the module README.
            if not documents:
                raise ValueError(
                    "tiling='doc' needs the premise's documents. Pass them to score_pairs — "
                    "splitting the joined premise is what produced paragraph chunks mislabelled "
                    "as documents."
                )
            # Window WITHIN each document, never hand a whole one to the encoder. A retrieved
            # document runs to 4,000 characters (EVIDENCE_CHARS_PER_ROW) which is ~780 tokens; in
            # concat format the claim is appended AFTER the premise, so a document that overflows
            # the 512-token window truncates the claim away and the model scores premise-only text.
            # That returns P(contradiction) ~ 0 for everything and reads as a detector that found
            # nothing rather than one that was never asked. Same budget rule classify.js applies,
            # applied per document instead of across the concatenation.
            chunks = []
            for doc in documents:
                if self.config.window_chars:
                    chunks.extend(windows_for(
                        de_blob(doc[:PAIR_MAX_PREMISE_CHARS]),
                        window_chars=self.config.window_chars,
                        overlap=min(200, self.config.window_chars // 4),
                        max_windows=10**6,  # every window; the budget is the FP rate, not a cap
                    ))
                    continue
                chunks.extend(
                    premise_chunks_for(
                        de_blob(doc[:PAIR_MAX_PREMISE_CHARS]),
                        len(clamped) + len(self.tok.eos_token or "</s>"),
                        max_chunks=self.config.max_chunks,
                    )
                )
        elif self.config.tiling == "para":
            # Finer than a document: every paragraph scored on its own. This is what the first
            # doc-aligned run accidentally measured, and it is a real (expensive) arm worth
            # keeping now that it is named honestly.
            chunks = [d for d in premise.split("\n\n") if d.strip()] or chunks
        elif self.config.tiling != "byte":
            raise ValueError(f"unknown tiling {self.config.tiling!r}")

        # The invariant every tiling has to hold: premise chunk plus claim fits the budget the
        # encoder window is sized against. Broken once already (tiling='doc' handed the model whole
        # 4,000-character documents and the claim fell off the end), and the symptom was a clean
        # zero rather than an error — so it is asserted here instead of trusted.
        budget = PAIR_TOTAL_CHARS
        for c in chunks:
            if len(c) + len(clamped) > budget + PAIR_CLAIM_MAX_CHARS:
                raise ValueError(
                    f"tiling={self.config.tiling!r} produced a {len(c)}-char premise chunk with a "
                    f"{len(clamped)}-char claim, past the {budget}-char budget. In concat format "
                    "the claim is appended last, so this silently truncates the claim away and "
                    "every score collapses to 'no contradiction'."
                )
        return [(c, clamped) for c in chunks]

    def _contradiction(self, encoded_pairs: list[tuple[str, str]]) -> list[float]:
        eos = self.tok.eos_token or "</s>"
        out: list[float] = []
        for start in range(0, len(encoded_pairs), self.batch):
            chunk = encoded_pairs[start : start + self.batch]
            with self._torch.no_grad():
                if self.config.input_format == "concat":
                    # One string, exactly as classifyPairs assembles it.
                    enc = self.tok(
                        [p + eos + c for p, c in chunk],
                        return_tensors="pt", padding=True, truncation=True, max_length=512,
                    )
                elif self.config.input_format == "pair":
                    enc = self.tok(
                        [p for p, _ in chunk], [c for _, c in chunk],
                        return_tensors="pt", padding=True, truncation=True, max_length=512,
                    )
                else:
                    raise ValueError(f"unknown input_format {self.config.input_format!r}")
                probs = self._torch.softmax(self.model(**enc).logits, dim=-1)
            if self.config.normalize == "contra_minus_entail" and self.entail_index is not None:
                # P(contradiction) - P(entailment), per window. The head is three-way and we have
                # been throwing two thirds of it away: a window that is 0.5 contradiction / 0.45
                # entailment is genuinely ambiguous, while 0.5 contradiction / 0.02 entailment is
                # not, and P(contradiction) alone cannot tell them apart. Costs nothing: the logits
                # are already computed.
                out.extend((probs[:, self.contra_index] - probs[:, self.entail_index]).tolist())
            else:
                out.extend(probs[:, self.contra_index].tolist())
        return out

    def score_pairs(
        self, pairs: list[tuple[str, str]], documents: list[tuple[str, ...]] | None = None
    ) -> list[float]:
        """One SUPPORT score per pair, index-aligned. `documents[i]` is required for tiling='doc'."""
        flat: list[tuple[str, str]] = []
        owner: list[int] = []
        for i, (premise, claim) in enumerate(pairs):
            claims = self._clauses(claim) if self.config.decompose else [claim]
            for sub in claims:
                for window in self._windows_for(premise, sub, documents[i] if documents else ()):
                    flat.append(window)
                    owner.append(i)

        support = [1.0 - c for c in self._contradiction(flat)]
        reduce = min if self.config.reduce == "min" else max
        if self.config.reduce not in ("min", "max"):
            raise ValueError(f"unknown reduce {self.config.reduce!r}")

        buckets: list[list[float]] = [[] for _ in pairs]
        for score, i in zip(support, owner):
            buckets[i].append(score)

        if self.config.normalize in ("none", "contra_minus_entail"):
            return [reduce(b) if b else 1.0 for b in buckets]

        # The caller derives `unsupported = 1 - support`, so a normalised statistic is returned in
        # the same sense: bigger statistic -> more contradicted -> smaller "support".
        import statistics as st

        out: list[float] = []
        for b in buckets:
            if not b:
                out.append(1.0)
                continue
            contra = sorted((1.0 - s for s in b), reverse=True)
            top = contra[0]
            rest = contra[1:]
            if not rest:
                stat = top  # one window: nothing to normalise against, fall back to the raw score
            elif self.config.normalize == "margin":
                stat = top - st.median(rest)
            elif self.config.normalize == "zscore":
                spread = st.pstdev(contra) or 1e-6
                stat = (top - st.fmean(contra)) / spread
            else:
                raise ValueError(f"unknown normalize {self.config.normalize!r}")
            out.append(1.0 - stat)
        return out

    def forward_passes(
        self, pairs: list[tuple[str, str]], documents: list[tuple[str, ...]] | None = None
    ) -> int:
        """How many model calls this configuration costs for `pairs`. The per-event cost that
        every recall lever trades against — reported next to recall, never separately."""
        return sum(
            sum(len(self._windows_for(p, sub, documents[i] if documents else ()))
                for sub in (self._clauses(c) if self.config.decompose else [c]))
            for i, (p, c) in enumerate(pairs)
        )
