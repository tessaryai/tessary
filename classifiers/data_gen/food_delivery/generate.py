# SPDX-License-Identifier: Apache-2.0
"""Generate the ZipEats support-conversation corpus with Claude Haiku on Bedrock.

Produces one JSON object per line in a shard file under ``classifiers/data/food_delivery/shards/``
— a full multi-turn conversation SCRIPT (user messages, tool/retrieval rounds, assistant replies)
for one customer-support intent, with the agent always following the SOP correctly (this is the
happy-path reference corpus; see ``classifiers/data/food_delivery/sop.md``).

Resumable: it counts existing lines in the output file and keeps generating until ``--target`` is
reached, so a timed-out or interrupted run picks up where it left off with no de-dup bookkeeping
needed beyond "how many are already on disk". Failures (Bedrock throttling, network loss, bad
JSON) are logged with a traceback rather than swallowed, and retried with exponential backoff — a
shard that produces zero conversations now always leaves a trail in its log file explaining why.

Run:  cd classifiers && python -m data_gen.food_delivery.generate --target 1200 --max-calls 40
See ``run_parallel.py`` for the concurrent multi-shard driver used to reach that target quickly.
"""

from __future__ import annotations

import argparse
import json
import logging
import random
import sys
import time
import traceback
import uuid
from pathlib import Path

from . import env

env.load()

import boto3  # noqa: E402 — after env.load() so AWS_REGION is populated first

from .design import INTENTS, PERSONAS, RAG_COLLECTIONS, RAG_SUMMARIES, TOOLS  # noqa: E402

DATA_DIR = Path(__file__).resolve().parents[2] / "data" / "food_delivery"
OUT_PATH = DATA_DIR / "conversations.jsonl"
MODEL_ID = "global.anthropic.claude-haiku-4-5-20251001-v1:0"
MAX_CONSECUTIVE_FAILURES = 8

_RECURRING_CUSTOMERS = [f"cust-{i:05d}" for i in range(1, 41)]


def _make_logger(tag: str, log_path: Path | None) -> logging.Logger:
    logger = logging.getLogger(f"data_gen.food_delivery.generate.{tag or 'main'}")
    logger.setLevel(logging.INFO)
    logger.propagate = False
    if logger.handlers:
        return logger
    fmt = logging.Formatter(f"%(asctime)s [generate:{tag}] %(levelname)s %(message)s")
    stream = logging.StreamHandler(sys.stdout)
    stream.setFormatter(fmt)
    logger.addHandler(stream)
    if log_path is not None:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        fileh = logging.FileHandler(log_path)
        fileh.setFormatter(fmt)
        logger.addHandler(fileh)
    return logger


class Haiku:
    """Bedrock client sized for long generations.

    botocore defaults to a 60s read timeout, but a batch of ~20 conversations is ~20k output tokens
    and takes minutes — every call would time out mid-generation. Streaming keeps the socket active
    and surfaces partial output, and the read timeout below bounds a genuinely stuck call rather than
    a merely slow one.
    """

    def __init__(self, model: str = MODEL_ID, max_tokens: int = 16000, temperature: float = 1.0):
        self.model = model
        self.max_tokens = max_tokens
        self.temperature = temperature
        import os

        from botocore.config import Config

        self.client = boto3.client(
            "bedrock-runtime",
            region_name=os.environ.get("AWS_REGION", "us-east-1"),
            config=Config(
                connect_timeout=15,
                read_timeout=900,
                retries={"max_attempts": 4, "mode": "adaptive"},
            ),
        )

    def complete(self, system: str, user: str) -> str:
        body = {
            "anthropic_version": "bedrock-2023-05-31",
            "max_tokens": self.max_tokens,
            "temperature": self.temperature,
            "system": system,
            "messages": [{"role": "user", "content": user}],
        }
        resp = self.client.invoke_model_with_response_stream(modelId=self.model, body=json.dumps(body))
        parts: list[str] = []
        for event in resp["body"]:
            chunk = event.get("chunk")
            if not chunk:
                continue
            payload = json.loads(chunk["bytes"])
            if payload.get("type") == "content_block_delta":
                parts.append(payload.get("delta", {}).get("text", ""))
        return "".join(parts)


def _system_prompt() -> str:
    tool_lines = "\n".join(f"- {name}{sig}" for name, sig in TOOLS.items())
    rag_lines = "\n".join(f"- {name}: {RAG_SUMMARIES[name]}" for name in RAG_COLLECTIONS)
    return f"""You write realistic training-data SCRIPTS of customer-support conversations for
ZipEats, a food-delivery app support agent. You never actually run tools — you invent plausible
tool call arguments AND plausible tool call results yourself, consistent with the tool's return
shape and with the rest of the conversation (order ids, item names, amounts, times must stay
internally consistent within one conversation).

The agent in these scripts is ALWAYS competent and ALWAYS follows this SOP correctly — this is a
reference "happy path" corpus, not an error corpus. It resolves identity/order lookup before
acting, diagnoses live status before promising anything, retrieves the right policy document
before financial decisions, stays within its refund/credit authority, logs complaint tickets where
the SOP requires it, and escalates when the SOP says to escalate. A tool call FAILING and the
agent retrying or working around it is normal, realistic operation — not a mistake — so include
that sometimes.

CRITICAL rule about missing information: if the order id (or other required identifier) is not
yet known at a given point in the conversation, do NOT call a tool with a placeholder/guessed
argument like "unknown" or "N/A" — instead that turn should have "rounds": [] and the
"final_answer" simply asks the customer for what's needed (per SOP-1: resolve identity/order
before acting). Only call order/customer-scoped tools once a real id has actually appeared in the
conversation.

Tool catalogue:
{tool_lines}

Help-centre / policy documents the agent can retrieve from (RETRIEVER step, not a tool call). When
you write a retrieval "snippet", it must restate facts consistent with the summary below — never
invent numbers or rules that contradict it:
{rag_lines}

Output STRICT JSON ONLY: an array of conversation objects, one per requested spec, same order.
Each conversation object:
{{
  "turns": [
    {{
      "user_message": "<the customer's message this turn, matching the requested persona/tone>",
      "rounds": [
        {{
          "actions": [
            {{"kind": "tool", "name": "<tool name>", "args": {{...}}, "result": {{...}}, "error": null}},
            {{"kind": "tool", "name": "<tool name>", "args": {{...}}, "result": null, "error": "<short failure message>"}},
            {{"kind": "retrieval", "collection": "<collection name>", "query": "<retrieval query>", "snippet": "<1-2 sentence excerpt the retrieval returned>"}}
          ]
        }}
      ],
      "final_answer": "<the agent's reply to the customer this turn>"
    }}
  ]
}}
Turns with no tool/retrieval need (e.g. a closing "thanks!" / "you're welcome") may have
"rounds": []. Vary sentence length and phrasing across conversations — do not reuse the same
opening line twice. No prose outside the JSON array. No markdown fences.
"""


def _spec_line(idx: int, spec: dict) -> str:
    intent = INTENTS[spec["intent"]]
    return (
        f"Conversation {idx}: intent={spec['intent']} ({intent['summary']}); "
        f"persona tone={spec['persona_tone']}; target_turns={spec['turns']}; "
        f"customer {'gives the order id right away' if spec['order_id_upfront'] else 'does not have the order id ready and must be asked or looked up by other details'}; "
        f"outcome should be {'fully resolved within this conversation' if spec['resolved'] else 'end with an escalation / ticket still pending, not a full immediate resolution'}; "
        f"typical tools for this intent: {', '.join(intent['typical_tools'])}; "
        f"typical retrieval collections: {', '.join(intent['typical_rag']) or 'none needed'}."
    )


def _sample_spec(rng: random.Random) -> dict:
    intents = list(INTENTS)
    weights = [INTENTS[k]["weight"] for k in intents]
    intent = rng.choices(intents, weights=weights, k=1)[0]
    persona = rng.choice(PERSONAS)
    turns = rng.choices([2, 3, 4, 5, 6, 7, 8, 9], weights=[10, 20, 22, 18, 12, 9, 6, 3], k=1)[0]
    return {
        "intent": intent,
        "persona_id": persona["id"],
        "persona_tone": persona["tone"],
        "turns": turns,
        "order_id_upfront": rng.random() < 0.55,
        "resolved": rng.random() < 0.82,
    }


def _extract_json_array(text: str) -> list[dict]:
    start = text.find("[")
    if start == -1:
        return []
    depth = 0
    in_string = False
    escape = False
    objs: list[dict] = []
    obj_start: int | None = None
    for i in range(start, len(text)):
        c = text[i]
        if in_string:
            if escape:
                escape = False
            elif c == "\\":
                escape = True
            elif c == '"':
                in_string = False
            continue
        if c == '"':
            in_string = True
            continue
        if c == "{":
            if depth == 0:
                obj_start = i
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0 and obj_start is not None:
                chunk = text[obj_start : i + 1]
                try:
                    objs.append(json.loads(chunk))
                except json.JSONDecodeError:
                    pass
                obj_start = None
    return objs


_PLACEHOLDER_IDS = {
    "unknown", "n/a", "na", "none", "tbd", "not_applicable", "not applicable", "not_yet_identified",
    "not yet identified", "not provided", "pending", "recent_order_lookup_not_applicable", "?",
}


def _looks_like_placeholder(value: object) -> bool:
    return isinstance(value, str) and value.strip().strip("\"'").lower() in _PLACEHOLDER_IDS


def _valid(conv: dict, spec: dict) -> bool:
    turns = conv.get("turns")
    if not isinstance(turns, list) or not turns:
        return False
    for t in turns:
        if not isinstance(t.get("user_message"), str) or not t["user_message"].strip():
            return False
        if not isinstance(t.get("final_answer"), str) or not t["final_answer"].strip():
            return False
        for rnd in t.get("rounds", []):
            for act in rnd.get("actions", []):
                if act.get("kind") == "tool":
                    if act.get("name") not in TOOLS:
                        return False
                    args = act.get("args") or {}
                    if isinstance(args, dict) and any(
                        k in ("order_id", "customer_id") and _looks_like_placeholder(v)
                        for k, v in args.items()
                    ):
                        return False
                elif act.get("kind") == "retrieval":
                    if act.get("collection") not in RAG_COLLECTIONS:
                        return False
                else:
                    return False
    return True


def _assign_identity(spec: dict, rng: random.Random, tag: str, seq: int) -> dict:
    conversation_id = f"cnv-fd-{tag}{seq:05d}-{uuid.uuid4().hex[:4]}"
    if spec["persona_id"] == "loyal_regular" or rng.random() < 0.12:
        customer_id = rng.choice(_RECURRING_CUSTOMERS)
    else:
        customer_id = f"cust-{uuid.uuid4().hex[:8]}"
    return {"conversation_id": conversation_id, "customer_id": customer_id}


def generate(
    target: int,
    max_calls: int,
    seed: int,
    out_path: Path,
    tag: str,
    batch_min: int = 6,
    batch_max: int = 10,
    max_tokens: int = 16000,
    log_path: Path | None = None,
) -> int:
    logger = _make_logger(tag, log_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    existing = 0
    if out_path.exists():
        existing = sum(1 for line in out_path.read_text().splitlines() if line.strip())
    rng = random.Random(f"{seed}-{existing}-{time.time_ns()}")  # vary the draw sequence across resumed invocations
    if existing >= target:
        logger.info(f"already have {existing} >= target {target}, nothing to do")
        return existing

    haiku = Haiku(max_tokens=max_tokens)
    system = _system_prompt()
    written = existing
    calls = 0
    consecutive_failures = 0
    logger.info(f"starting: existing={existing} target={target} max_calls={max_calls} out={out_path}")
    with out_path.open("a") as fh:
        while written < target and calls < max_calls:
            batch_size = rng.randint(batch_min, batch_max)
            specs = [_sample_spec(rng) for _ in range(batch_size)]
            user = "Generate these conversations:\n\n" + "\n".join(
                _spec_line(i + 1, s) for i, s in enumerate(specs)
            )
            calls += 1
            try:
                raw = haiku.complete(system, user)
            except Exception:
                consecutive_failures += 1
                logger.error(
                    f"call {calls} (batch_size={batch_size}) failed "
                    f"(consecutive_failures={consecutive_failures}):\n{traceback.format_exc()}"
                )
                if consecutive_failures >= MAX_CONSECUTIVE_FAILURES:
                    logger.critical(
                        f"{consecutive_failures} consecutive failures, giving up this invocation "
                        f"at {written}/{target}"
                    )
                    break
                time.sleep(min(2**consecutive_failures, 60))
                continue

            try:
                convs = _extract_json_array(raw)
                added = 0
                for spec, conv in zip(specs, convs, strict=False):
                    if not _valid(conv, spec):
                        continue
                    ident = _assign_identity(spec, rng, tag, written + 1)
                    record = {**ident, **spec, **conv}
                    fh.write(json.dumps(record) + "\n")
                    written += 1
                    added += 1
                fh.flush()
            except Exception:
                consecutive_failures += 1
                logger.error(
                    f"call {calls} (batch_size={batch_size}) parse/write failed "
                    f"(consecutive_failures={consecutive_failures}):\n{traceback.format_exc()}"
                )
                if consecutive_failures >= MAX_CONSECUTIVE_FAILURES:
                    logger.critical(
                        f"{consecutive_failures} consecutive failures, giving up this invocation "
                        f"at {written}/{target}"
                    )
                    break
                time.sleep(min(2**consecutive_failures, 60))
                continue

            consecutive_failures = 0
            requested = len(specs)
            logger.info(
                f"call {calls}: requested={requested} parsed={len(convs)} valid={added} "
                f"{written}/{target} total"
            )
    logger.info(f"done: {written}/{target} on disk after {calls} calls")
    return written


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", type=int, default=1200)
    ap.add_argument("--batch-min", type=int, default=6)
    ap.add_argument("--batch-max", type=int, default=10)
    ap.add_argument("--max-tokens", type=int, default=16000)
    ap.add_argument("--max-calls", type=int, default=20)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", type=str, default=str(OUT_PATH))
    ap.add_argument("--tag", type=str, default="")
    ap.add_argument("--log", type=str, default="")
    args = ap.parse_args()
    log_path = Path(args.log) if args.log else None
    written = generate(
        args.target,
        args.max_calls,
        args.seed,
        Path(args.out),
        args.tag,
        batch_min=args.batch_min,
        batch_max=args.batch_max,
        max_tokens=args.max_tokens,
        log_path=log_path,
    )
    print(f"[generate:{args.tag}] done this invocation: {written} conversations on disk")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
