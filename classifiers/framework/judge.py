# SPDX-License-Identifier: Apache-2.0
"""The LLM judge — a thin completion client plus a generic binary-classification helper.

The judge is deliberately minimal: a ``Judge`` is anything that can ``complete(system, user)``.
On top of that one method the framework builds:

- ``classify()`` — batch-label texts against a ``LabelSpec`` (the label definition), used by
  every classifier's judge-labeling script.
- (classifier-specific ``synthesize`` scripts also call ``complete`` directly to *generate*
  labeled examples.)

Implementations: ``BedrockJudge`` (DISABLED — Bedrock is forbidden, see no_bedrock.py) (mirrors the platform),
``AnthropicJudge`` (first-party API), and ``FakeJudge`` (offline keyword heuristic, so the
pipeline is runnable and testable with no credentials).
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass
from typing import Protocol


class Judge(Protocol):
    """Anything that can complete a system+user prompt to text."""

    def complete(self, system: str, user: str) -> str: ...


@dataclass(slots=True)
class LabelSpec:
    """A labeling task: the definition prompt and the two class names, optionally ordinal.

    ``system`` is the label definition handed to the judge (the single source of truth for what
    the positive class means). ``positive``/``negative`` are the words the judge returns, mapped
    to 1/0.

    ``intensity_levels`` (optional, frustration ordinal-v2) turns the task ORDINAL: a mapping
    ``{level: description}`` where level ``0`` is the negative class and ``1..N`` are increasing
    intensities of the positive class. When set, ``classify`` additionally asks the judge for an
    ``"intensity"`` per item and ``_parse_batch`` records it on the ``Verdict`` — deriving the
    binary ``label`` from it (``label == 1`` iff ``intensity >= 1``) so the two never disagree.
    Left ``None`` (e.g. refusal) the task stays purely binary and behaves exactly as before.
    """

    name: str
    system: str
    positive: str  # e.g. "refusal"  -> label 1
    negative: str  # e.g. "compliance" -> label 0
    intensity_levels: dict[int, str] | None = None  # {0: neg, 1..N: rising positive}; enables ordinal grading


@dataclass(slots=True)
class Verdict:
    label: int  # 1 positive, 0 negative
    confidence: float
    reason: str
    intensity: int | None = None  # ordinal level 0..N when the spec defines intensity_levels, else None


_BATCH_INSTRUCTIONS = (
    "\n\nYou are labeling a batch. Return ONLY a JSON array, one object per input item, in order:\n"
    '[{{"index": 0, "label": "{pos}"|"{neg}", "confidence": 0.0-1.0, "reason": "<=12 words"}}, ...]\n'
    "No prose outside the JSON."
)

_BATCH_INSTRUCTIONS_ORDINAL = (
    "\n\nYou are labeling a batch. For each item decide the label AND its ORDINAL INTENSITY on this "
    "scale:\n{rubric}\n"
    "'{pos}' means intensity >= 1; '{neg}' means intensity 0 — the two must agree. Return ONLY a JSON "
    "array, one object per input item, in order:\n"
    '[{{"index": 0, "label": "{pos}"|"{neg}", "intensity": <integer {lo}-{hi}>, "confidence": 0.0-1.0, '
    '"reason": "<=12 words"}}, ...]\n'
    "No prose outside the JSON."
)


def _batch_instructions(spec: LabelSpec) -> str:
    """The batch-format instruction appended to ``spec.system`` — ordinal iff the spec defines levels."""
    if spec.intensity_levels:
        levels = sorted(spec.intensity_levels.items())
        rubric = "\n".join(f"  {lvl} = {desc}" for lvl, desc in levels)
        return _BATCH_INSTRUCTIONS_ORDINAL.format(
            pos=spec.positive, neg=spec.negative, rubric=rubric, lo=levels[0][0], hi=levels[-1][0]
        )
    return _BATCH_INSTRUCTIONS.format(pos=spec.positive, neg=spec.negative)


def classify(judge: Judge, texts: list[str], spec: LabelSpec, batch_size: int = 20) -> list[Verdict]:
    """Label ``texts`` against ``spec`` in batches. Order-preserving; robust to judge drift.

    When ``spec.intensity_levels`` is set the verdicts additionally carry an ordinal
    ``intensity`` (and ``label`` is derived from it); otherwise ``intensity`` stays ``None``.
    """
    out: list[Verdict] = []
    instructions = _batch_instructions(spec)
    for start in range(0, len(texts), batch_size):
        chunk = texts[start : start + batch_size]
        system = spec.system + instructions
        user = "\n".join(f"### Item {i}\n{t}" for i, t in enumerate(chunk))
        raw = judge.complete(system, user)
        out.extend(_parse_batch(raw, spec, expected=len(chunk)))
    return out


def _parse_batch(raw: str, spec: LabelSpec, expected: int) -> list[Verdict]:
    """Parse the judge's JSON array; anything unparseable is a low-confidence negative (fail-safe)."""
    verdicts: list[Verdict] = [Verdict(0, 0.0, "unparsed") for _ in range(expected)]
    match = re.search(r"\[.*\]", raw, re.DOTALL)
    if not match:
        return verdicts
    try:
        arr = json.loads(match.group(0))
    except json.JSONDecodeError:
        return verdicts
    hi = max(spec.intensity_levels) if spec.intensity_levels else 0
    for obj in arr:
        if not isinstance(obj, dict):
            continue
        idx = obj.get("index")
        if not isinstance(idx, int) or not (0 <= idx < expected):
            continue
        label = 1 if str(obj.get("label", "")).strip().lower() == spec.positive.lower() else 0
        conf = float(obj.get("confidence", 0.5)) if isinstance(obj.get("confidence"), (int, float)) else 0.5
        intensity: int | None = None
        if spec.intensity_levels is not None:
            raw_int = obj.get("intensity")
            if isinstance(raw_int, bool):  # bool is an int subclass — not a valid level
                raw_int = None
            if isinstance(raw_int, (int, float)):
                intensity = max(0, min(hi, int(raw_int)))
                label = 1 if intensity >= 1 else 0  # intensity is authoritative for the binary boundary
        verdicts[idx] = Verdict(label=label, confidence=conf, reason=str(obj.get("reason", "")), intensity=intensity)
    return verdicts


# --- implementations -------------------------------------------------------------------------


# Surface refusal cues, shared: FakeJudge uses them for its offline heuristic, and data-assembly
# scripts (e.g. refusal/build_evalset.py) use them to filter benign negatives. Module-level so
# nothing has to reach into a private attribute of the offline test judge.
REFUSAL_CUES = (
    "i can't", "i cannot", "i'm not able", "i am not able", "i'm unable", "i am unable",
    "as an ai", "i won't", "i will not", "unable to assist", "can't help with that",
    "cannot help with that", "i'm sorry, but", "against my", "not allowed to",
)


class BedrockJudge:
    """Claude via AWS Bedrock — the same serving the platform uses. Needs boto3 + AWS creds.

    Model id via ``JUDGE_MODEL`` (default a Claude Haiku inference profile) and ``AWS_REGION``.
    """

    def __init__(self, model: str | None = None, region: str | None = None):
        # FORBIDDEN (owner instruction 2026-08-21). Kept as a class so the error names what to use
        # instead rather than an AttributeError from a deleted symbol — and so `make_judge`'s
        # credential sniffing can never quietly route dev work onto a billed API again.
        from .no_bedrock import BedrockForbidden
        raise BedrockForbidden(
            "BedrockJudge is disabled: AWS Bedrock is forbidden in this repo's Python tooling. "
            "Use ClaudeCliJudge (the owner's logged-in `claude` CLI) instead. See "
            "framework/no_bedrock.py for why, and scripts/check-no-bedrock.sh for the static gate.")

    def complete(self, system: str, user: str) -> str:
        body = {
            "anthropic_version": "bedrock-2023-05-31",
            "max_tokens": 4096,
            "system": system,
            "messages": [{"role": "user", "content": user}],
        }
        resp = self.client.invoke_model(modelId=self.model, body=json.dumps(body))
        payload = json.loads(resp["body"].read())
        return "".join(part.get("text", "") for part in payload.get("content", []))


class AnthropicJudge:
    """Claude via the first-party Anthropic API. Needs the ``anthropic`` SDK + ANTHROPIC_API_KEY."""

    def __init__(self, model: str | None = None):
        import anthropic  # lazy

        self.model = model or os.environ.get("JUDGE_MODEL", "claude-haiku-4-5")
        self.client = anthropic.Anthropic()

    def complete(self, system: str, user: str) -> str:
        msg = self.client.messages.create(
            model=self.model,
            max_tokens=4096,
            system=system,
            messages=[{"role": "user", "content": user}],
        )
        return "".join(block.text for block in msg.content if block.type == "text")


class ClaudeCliJudge:
    """Claude via the host's logged-in ``claude`` CLI — Claude Code **subscription** auth, no API
    key and no Bedrock model-access grants. Spawns ``claude -p`` per completion (print mode,
    stateless, no tools involved).

    ``model`` takes the CLI's aliases ("opus", "haiku") or full ids ("claude-fable-5",
    "claude-opus-4-8"). A process per completion is slower than the SDK judges — right for
    dev-scale synthesis/labeling on the owner's machine, not bulk jobs.
    """

    def __init__(self, model: str | None = None, binary: str = "claude", timeout_s: int = 300):
        import shutil  # lazy-adjacent: stdlib, but keep the ctor cheap

        self.model = model or os.environ.get("JUDGE_MODEL", "claude-haiku-4-5")
        self.binary = binary
        self.timeout_s = timeout_s
        if shutil.which(binary) is None:
            raise RuntimeError(f"{binary!r} CLI not found on PATH — is Claude Code installed?")

    def complete(self, system: str, user: str) -> str:
        import subprocess
        import time

        last = None
        for attempt in range(3):  # transient CLI failures (rate blips, session refresh) self-heal
            proc = subprocess.run(  # noqa: S603 — fixed argv, prompt via stdin
                [self.binary, "-p", "--model", self.model, "--output-format", "text", "--system-prompt", system],
                input=user,
                capture_output=True,
                text=True,
                timeout=self.timeout_s,
            )
            if proc.returncode == 0:
                return proc.stdout.strip()
            last = f"exit {proc.returncode}: stderr={proc.stderr.strip()[:300]!r} stdout={proc.stdout.strip()[:200]!r}"
            time.sleep(2 * 4**attempt)  # 2s, 8s
        raise RuntimeError(f"claude CLI failed after 3 attempts: {last}")


class FakeJudge:
    """Offline heuristic judge — lets the whole pipeline run and be tested with no credentials.

    It is NOT a real labeler; it looks for refusal-shaped surface cues so the plumbing can be
    exercised end to end. Swap in Bedrock/Anthropic for real labels.
    """

    _REFUSAL_CUES = REFUSAL_CUES  # back-compat alias; prefer the module-level REFUSAL_CUES

    def complete(self, system: str, user: str) -> str:
        items = re.split(r"### Item \d+\n", user)[1:]
        out = []
        for i, text in enumerate(items):
            low = text.lower()
            hit = any(cue in low for cue in REFUSAL_CUES)
            out.append(
                {"index": i, "label": "refusal" if hit else "compliance", "confidence": 0.6, "reason": "heuristic"}
            )
        return json.dumps(out)


def default_judge(allow_fake: bool = True) -> Judge:
    """Pick a judge from the environment: the `claude` CLI, else an Anthropic key, else Fake.

    **Bedrock is never selected.** See `framework/no_bedrock.py`.

    ``allow_fake`` guards the silent-fallback footgun: FakeJudge is a keyword heuristic, not a real
    labeler, so scripts that GENERATE training data pass ``allow_fake=False`` and get a hard error
    (rather than heuristic-labeled data masquerading as judged) when no credentials are configured.
    The offline harness self-test and eval plumbing keep the default (``True``).
    """
    # Explicit selection wins over creds sniffing: EVALS_JUDGE = cli | bedrock | anthropic.
    # "cli" is the Claude Code SUBSCRIPTION path (no API key, no Bedrock grants) — the owner's
    # chosen default on the dev Mac, where Bedrock creds exist but lack Fable/Opus model access.
    choice = os.environ.get("EVALS_JUDGE", "").strip().lower()
    if choice == "cli":
        return ClaudeCliJudge()
    if choice == "bedrock":
        from .no_bedrock import BedrockForbidden
        raise BedrockForbidden(
            "EVALS_JUDGE=bedrock is refused: AWS Bedrock is forbidden here. Set EVALS_JUDGE=cli.")
    if choice == "anthropic":
        return AnthropicJudge()

    # The AWS-credentials branch used to sit here and returned a BedrockJudge. It is GONE, not
    # disabled: credential sniffing is exactly how dev work ended up on a billed API without anyone
    # choosing it. Ambient AWS creds must never again select a backend.
    if os.environ.get("ANTHROPIC_API_KEY"):
        try:
            return AnthropicJudge()
        except Exception:  # noqa: BLE001
            pass
    try:
        return ClaudeCliJudge()  # logged-in Claude Code CLI, if present — still a real judge
    except Exception:  # noqa: BLE001
        pass
    if not allow_fake:
        raise SystemExit(
            "No judge credentials found — refusing to generate data with the offline FakeJudge "
            "(a keyword heuristic, NOT real labels).\n"
            "Set EVALS_JUDGE=cli (logged-in Claude Code subscription), or AWS_REGION (+ AWS creds) "
            "for Bedrock, or ANTHROPIC_API_KEY for Anthropic.\n"
            "To label with the heuristic FakeJudge anyway (plumbing/offline only), pass --allow-fake."
        )
    print("[judge] no credentials found — using the offline FakeJudge (heuristic labels, not real).")
    return FakeJudge()
