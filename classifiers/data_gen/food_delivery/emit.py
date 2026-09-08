# SPDX-License-Identifier: Apache-2.0
"""Emit generated conversations to Langfuse as OpenTelemetry traces.

One Langfuse trace per conversational TURN, matching the span/attribute shape the platform's other
sample app already produces: an AGENT root, a GENERATION per model call named for the tools that call
decided to invoke, and TOOL / RETRIEVER children per action.

Resumable: emitted conversation ids are appended to a ledger, so re-running neither duplicates nor
loses work.

Run:  python -m data_gen.food_delivery.emit --limit 5      # verification batch
      python -m data_gen.food_delivery.emit                # everything remaining
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import random
from datetime import datetime, timedelta
from pathlib import Path

from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import SpanKind, Status, StatusCode

from .design import AGENT_NAME, DEPLOYMENT_ENVIRONMENT

DATA = Path(__file__).resolve().parents[2] / "data" / "food_delivery"
LEDGER = DATA / ".emitted"
RUN_TAG = "run:fd-clean-1"
MODEL = "anthropic.claude-haiku-4-5"
WINDOW_DAYS = 28
NS = 1_000_000_000


def _env(name: str) -> str:
    v = os.environ.get(name)
    if not v:
        raise SystemExit(f"{name} missing — source the repo .env first")
    return v


def _provider() -> TracerProvider:
    host = os.environ.get("LANGFUSE_POLICYGPT_HOST", "https://us.cloud.langfuse.com").rstrip("/")
    auth = base64.b64encode(
        f"{_env('LANGFUSE_POLICYGPT_PUBLIC_KEY')}:{_env('LANGFUSE_POLICYGPT_SECRET_KEY')}".encode()
    ).decode()
    resource = Resource.create(
        {
            "service.name": AGENT_NAME,
            "deployment.environment.name": DEPLOYMENT_ENVIRONMENT,
            "vcs.repository.url": "https://github.com/tessaryai/evals-sample-app",
        }
    )
    provider = TracerProvider(resource=resource)
    provider.add_span_processor(
        BatchSpanProcessor(
            OTLPSpanExporter(
                endpoint=f"{host}/api/public/otel/v1/traces",
                headers={"Authorization": f"Basic {auth}"},
            ),
            max_export_batch_size=128,
        )
    )
    return provider


def _base_attrs(conv: dict, turn_index: int) -> dict:
    cid, cust = conv["conversation_id"], conv["customer_id"]
    return {
        "gen_ai.conversation.id": cid,
        "session.id": cid,
        "user.id": cust,
        "enduser.id": cust,
        "tessary.entity": cust,
        "tessary.context.path": f"entity:{cust}/project:{AGENT_NAME}/conversation:{cid}",
        "tessary.turn.index": str(turn_index),
        "gen_ai.agent.name": AGENT_NAME,
        "langfuse.tags": json.dumps(
            [RUN_TAG, f"intent:{conv.get('intent', 'unknown')}", f"persona:{conv.get('persona_id', 'unknown')}"]
        ),
    }


def _round_label(actions: list[dict]) -> str:
    names = [a.get("name") or a.get("collection") or "unknown" for a in actions]
    return "llm → " + (", ".join(names) if names else "answer")


def emit_turn(tracer, conv: dict, turn: dict, idx: int, start_ns: int) -> int:
    base = _base_attrs(conv, idx)
    cursor = start_ns
    root = tracer.start_span(
        f"invoke_agent {AGENT_NAME}",
        kind=SpanKind.SERVER,
        start_time=cursor,
        attributes={**base, "gen_ai.operation.name": "invoke_agent"},
    )
    root.set_attribute("input.value", json.dumps([{"role": "user", "content": turn.get("user_message", "")}]))
    ctx = trace.set_span_in_context(root)

    for rnd in turn.get("rounds", []):
        actions = rnd.get("actions", [])
        cursor += random.randint(400, 1800) * 1_000_000
        gen = tracer.start_span(
            _round_label(actions),
            context=ctx,
            start_time=cursor,
            attributes={
                **base,
                "gen_ai.operation.name": "chat",
                "gen_ai.provider.name": "aws.bedrock",
                "gen_ai.request.model": MODEL,
                "gen_ai.response.model": MODEL,
                "gen_ai.usage.input_tokens": str(random.randint(900, 4200)),
                "gen_ai.usage.output_tokens": str(random.randint(30, 400)),
                "gen_ai.response.finish_reasons": '["tool_use"]' if actions else '["end_turn"]',
            },
        )
        cursor += random.randint(200, 900) * 1_000_000
        gen.end(end_time=cursor)

        for n, act in enumerate(actions):
            is_retrieval = act.get("kind") == "retrieval" or bool(act.get("collection"))
            name = act.get("name") or act.get("collection") or "unknown"
            attrs = {
                **base,
                "gen_ai.tool.name": name,
                "gen_ai.tool.call.id": f"toolu_{idx}_{n}",
                "gen_ai.tool.type": "function",
                "tessary.call_site.id": f"zipeats.{name}",
            }
            if is_retrieval:
                attrs["gen_ai.operation.name"] = "retrieval"
                attrs["langfuse.observation.type"] = "retriever"
                attrs["gen_ai.data_source.id"] = act.get("collection", name)
            else:
                attrs["gen_ai.operation.name"] = "execute_tool"
            span = tracer.start_span(name, context=ctx, start_time=cursor, attributes=attrs)
            if act.get("args"):
                span.set_attribute("input.value", json.dumps(act["args"])[:4000])
            payload = act.get("result") if not is_retrieval else act.get("snippet")
            if payload is not None:
                span.set_attribute("output.value", json.dumps(payload)[:4000])
            if act.get("error"):
                span.set_status(Status(StatusCode.ERROR, str(act["error"])[:300]))
                span.set_attribute("error.type", str(act["error"])[:120])
            cursor += random.randint(120, 1400) * 1_000_000
            span.end(end_time=cursor)

    cursor += random.randint(300, 1500) * 1_000_000
    answer = tracer.start_span(
        "llm → answer",
        context=ctx,
        start_time=cursor,
        attributes={
            **base,
            "gen_ai.operation.name": "chat",
            "gen_ai.provider.name": "aws.bedrock",
            "gen_ai.request.model": MODEL,
            "gen_ai.response.model": MODEL,
            "gen_ai.usage.input_tokens": str(random.randint(1200, 5200)),
            "gen_ai.usage.output_tokens": str(random.randint(40, 320)),
            "gen_ai.response.finish_reasons": '["end_turn"]',
        },
    )
    answer.set_attribute("output.value", json.dumps([{"role": "assistant", "content": turn.get("final_answer", "")}]))
    cursor += random.randint(400, 2200) * 1_000_000
    answer.end(end_time=cursor)

    root.set_attribute("output.value", json.dumps([{"role": "assistant", "content": turn.get("final_answer", "")}]))
    root.end(end_time=cursor)
    return cursor


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    convs = [json.loads(line) for line in (DATA / "conversations.jsonl").read_text().splitlines() if line.strip()]
    done = set(LEDGER.read_text().split()) if LEDGER.exists() else set()
    pending = [c for c in convs if c["conversation_id"] not in done]
    if args.limit:
        pending = pending[: args.limit]
    if not pending:
        print("nothing pending")
        return 0

    provider = _provider()
    tracer = provider.get_tracer(AGENT_NAME)
    rng = random.Random(17)
    origin = datetime(2026, 7, 1)

    sent = 0
    with LEDGER.open("a") as ledger:
        for c in pending:
            offset = timedelta(seconds=rng.randint(0, WINDOW_DAYS * 86400))
            cursor = int((origin + offset).timestamp() * NS)
            for i, turn in enumerate(c.get("turns", [])):
                cursor = emit_turn(tracer, c, turn, i, cursor)
                cursor += rng.randint(5, 900) * NS
            ledger.write(c["conversation_id"] + "\n")
            ledger.flush()
            sent += 1
            if sent % 50 == 0:
                provider.force_flush(30_000)
                print(f"  emitted {sent}/{len(pending)}", flush=True)

    provider.force_flush(60_000)
    provider.shutdown()
    print(f"emitted {sent} conversations ({sum(len(c.get('turns', [])) for c in pending)} turns)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
