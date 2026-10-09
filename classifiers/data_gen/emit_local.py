# SPDX-License-Identifier: Apache-2.0
"""Emit synthetic corpora to a running Tessary instance as OTLP traces.

The platform's receiver is protobuf-only and authenticates a project-scoped `tsy_` bearer. The
call site is a property of the agent surface, stamped on every span in the trace, rather than on
TOOL spans only (which would shatter one agent into many call sites).

Shape produced, matching the substrate spine `StructuralEnricher` builds:

    session (session.id)
      └── conversation (gen_ai.conversation.id)     <- interposed when BOTH keys are stated
            └── turn (one per distinct provider trace)
                  └── trace
                        └── observations: AGENT root, LLM / TOOL / RETRIEVAL children

One OTel trace per conversational TURN: that is what makes turn segmentation work.

Timestamps are spread across a trailing window (default 28 days) preserving within-conversation
ordering.

Resumable: emitted conversation ids are appended to a per-corpus ledger, so re-running neither
duplicates nor loses work.

    # preflight only, proves the endpoint, token and key scope before sending anything
    python -m data_gen.emit_local --check

    # one conversation of the canary corpus
    python -m data_gen.emit_local --corpus canary --limit 1 --no-resume
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import random
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterator

from opentelemetry import trace
from opentelemetry.context import Context
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import SpanKind

log = logging.getLogger("emit_local")

REPO = Path(__file__).resolve().parents[2]
LEDGER_DIR = REPO / "classifiers" / "data" / ".emit_local"

DEFAULT_ENDPOINT = "http://localhost/v1/traces"
NS = 1_000_000_000
MAX_PAYLOAD_CHARS = 4000

# The receiver bounds the request body; the exporter batches spans, so keep batches modest.
EXPORT_BATCH = 128


# --------------------------------------------------------------------------------------------------
# Corpus-independent intermediate form. Every loader reduces to this, so the span builder is written
# once and corpora cannot drift apart in shape.
# --------------------------------------------------------------------------------------------------


@dataclass
class Step:
    """One action inside a turn. `kind` is the canonical observation kind, not a vendor type."""

    kind: str  # "llm" | "tool" | "retrieval"
    name: str
    args: Any = None
    result: Any = None
    is_error: bool = False
    model: str | None = None
    usage: dict[str, int] = field(default_factory=dict)
    corpus: str | None = None  # retrieval collection, when kind == "retrieval"
    # Retrieved passages, when kind == "retrieval": emitted as OpenInference
    # `retrieval.documents.<N>.document.*` attributes, which the ingest maps to `retrieved_doc` rows,
    # the evidence the groundedness classifier grounds an answer against.
    documents: list[str] = field(default_factory=list)


@dataclass
class Turn:
    user: str
    assistant: str
    steps: list[Step]


@dataclass
class Conversation:
    conversation_id: str
    user_id: str
    turns: list[Turn]
    attributes: dict[str, str] = field(default_factory=dict)


@dataclass
class CorpusSpec:
    key: str
    call_site_id: str
    agent_name: str
    service_name: str
    model: str
    repo_url: str
    load: Any  # () -> Iterator[Conversation]


# --------------------------------------------------------------------------------------------------
# Loaders
# --------------------------------------------------------------------------------------------------


def _clip(value: Any) -> str:
    if value is None:
        return ""
    text = value if isinstance(value, str) else json.dumps(value, separators=(",", ":"))
    return text[:MAX_PAYLOAD_CHARS]


def load_canary() -> Iterator[Conversation]:
    """One hand-built conversation, no fixture file, no LLM key, for scripts/check-open-boot.sh.

    `secret_leak` is the one classifier here that scores an observation's own output against a fixed
    pattern with no baseline to accumulate first, so this conversation's one tool call returns a value
    shaped to fire it on the very first sweep after ingest, keeping `/classifiers/events` reliably
    non-empty without waiting on volume.
    """
    yield Conversation(
        conversation_id="canary-open-boot-check-0001",
        user_id="canary-operator",
        turns=[
            Turn(
                user="Can you look up the current deploy config for the payments service?",
                steps=[
                    Step(kind="llm", name="plan"),
                    Step(
                        kind="tool",
                        name="read_deploy_config",
                        args={"service": "payments"},
                        # Deliberately fake and unusable, a canary shape, not a real credential.
                        # sk_test_ + 16+ alnum matches SecretLeakDetector's "stripe-key" pattern
                        # (strong, no entropy gate) exactly.
                        #
                        # Not an AWS AKIA key: the platform's default PII redaction guard rewrites
                        # AKIA[0-9A-Z]{16} to [REDACTED_API_KEY] before the row is persisted, and
                        # SecretLeakDetector scores the persisted output, so an AKIA canary can
                        # never produce a detection. The Stripe shape is matched by the detector
                        # and by no built-in redaction rule.
                        result={"config": "region=us-east-1\nstripe_sk=sk_test_BOOTCHECKCANARY000001\n"},
                    ),
                    Step(kind="llm", name="answer"),
                ],
                assistant="Found it — region us-east-1, and the config block is above.",
            )
        ],
        attributes={"tessary.canary": "true"},
    )


CORPORA: dict[str, CorpusSpec] = {
    "canary": CorpusSpec(
        key="canary",
        call_site_id="open-boot-check-canary",
        agent_name="open-boot-check-canary-agent",
        service_name="open-boot-check-canary-agent",
        model="anthropic.claude-haiku-4-5",
        repo_url="https://github.com/tessaryai/tessary",
        load=load_canary,
    ),
}


# --------------------------------------------------------------------------------------------------
# Emission
# --------------------------------------------------------------------------------------------------


def _messages(role: str, text: str) -> str:
    """The canonical `gen_ai.{input,output}.messages` encoding: [{role, parts:[{type, content}]}]."""
    return json.dumps([{"role": role, "parts": [{"type": "text", "content": _clip(text)}]}])


def _provider(spec: CorpusSpec, endpoint: str, token: str, environment: str) -> TracerProvider:
    resource = Resource.create(
        {
            "service.name": spec.service_name,
            "deployment.environment.name": environment,
            "vcs.repository.url": spec.repo_url,
        }
    )
    provider = TracerProvider(resource=resource)
    provider.add_span_processor(
        BatchSpanProcessor(
            OTLPSpanExporter(endpoint=endpoint, headers={"Authorization": f"Bearer {token}"}),
            max_export_batch_size=EXPORT_BATCH,
        )
    )
    return provider


def _base_attributes(spec: CorpusSpec, conv: Conversation, turn_index: int) -> dict[str, Any]:
    """Stamped on EVERY span in the trace.

    `session.id` + `gen_ai.conversation.id` together are what make `StructuralEnricher` interpose a
    conversation context between the session root and its turns.
    """
    return {
        "gen_ai.conversation.id": conv.conversation_id,
        "session.id": conv.conversation_id,
        "tessary.call_site.id": spec.call_site_id,
        "gen_ai.agent.name": spec.agent_name,
        "tessary.turn.index": str(turn_index),
        "user.id": conv.user_id,
        "enduser.id": conv.user_id,
        "tessary.entity": conv.user_id,
        **conv.attributes,
    }


def _step_attributes(spec: CorpusSpec, step: Step) -> dict[str, Any]:
    if step.kind == "llm":
        usage = step.usage or {}
        attrs: dict[str, Any] = {
            "gen_ai.operation.name": "chat",
            "gen_ai.provider.name": "aws.bedrock",
            "gen_ai.system": "anthropic",
            "gen_ai.request.model": step.model or spec.model,
            "gen_ai.response.model": step.model or spec.model,
        }
        if usage.get("input_tokens") is not None:
            attrs["gen_ai.usage.input_tokens"] = int(usage["input_tokens"])
        if usage.get("output_tokens") is not None:
            attrs["gen_ai.usage.output_tokens"] = int(usage["output_tokens"])
        if usage.get("cache_read_input_tokens"):
            attrs["gen_ai.usage.cache_read.input_tokens"] = int(usage["cache_read_input_tokens"])
        if usage.get("cache_creation_input_tokens"):
            attrs["gen_ai.usage.cache_creation.input_tokens"] = int(usage["cache_creation_input_tokens"])
        return attrs
    if step.kind == "retrieval":
        attrs = {
            "gen_ai.operation.name": "retrieval",
            "gen_ai.tool.name": step.name,
            "gen_ai.tool.type": "function",
            "gen_ai.data_source.id": step.corpus or step.name,
        }
        for i, doc in enumerate(step.documents):
            attrs[f"retrieval.documents.{i}.document.id"] = f"{step.corpus or step.name}-{i}"
            attrs[f"retrieval.documents.{i}.document.content"] = doc
            attrs[f"retrieval.documents.{i}.document.score"] = round(0.95 - 0.05 * i, 2)
        return attrs
    return {
        "gen_ai.operation.name": "execute_tool",
        "gen_ai.tool.name": step.name,
        "gen_ai.tool.type": "function",
    }


def _emit_turn(tracer, spec: CorpusSpec, conv: Conversation, turn: Turn, index: int, start_ns: int) -> int:
    """One OTel trace for this turn. Returns the wall-clock nanosecond cursor after it."""
    base = _base_attributes(spec, conv, index)
    cursor = start_ns

    # Context() with no active span forces a fresh trace id: one provider trace per turn is what
    # `StructuralEnricher` segments turns on.
    root = tracer.start_span(
        name=f"{spec.agent_name} turn {index}",
        context=Context(),
        kind=SpanKind.SERVER,
        start_time=cursor,
        attributes={**base, "gen_ai.operation.name": "invoke_agent"},
    )
    root.set_attribute("gen_ai.input.messages", _messages("user", turn.user))
    child_ctx = trace.set_span_in_context(root)

    for n, step in enumerate(turn.steps):
        duration = random.randint(250, 2600) * 1_000_000 if step.kind == "llm" else random.randint(15, 400) * 1_000_000
        attrs = {**base, **_step_attributes(spec, step)}
        if step.kind != "llm":
            attrs["gen_ai.tool.call.id"] = f"toolu_{index}_{n}"
        span = tracer.start_span(
            name=f"{step.kind}:{step.name}",
            context=child_ctx,
            kind=SpanKind.INTERNAL,
            start_time=cursor,
            attributes=attrs,
        )
        if step.args is not None:
            span.set_attribute("gen_ai.input.messages", _messages("user", _clip(step.args)))
        if step.result is not None:
            span.set_attribute("gen_ai.output.messages", _messages("assistant", _clip(step.result)))
        if step.is_error:
            span.set_status(trace.Status(trace.StatusCode.ERROR, "tool reported an error"))
            span.set_attribute("error.type", "tool_error")
        cursor += duration
        span.end(end_time=cursor)

    root.set_attribute("gen_ai.output.messages", _messages("assistant", turn.assistant))
    root.end(end_time=cursor)
    # Gap before the user's next turn.
    return cursor + random.randint(4, 90) * NS


def _conversation_start(conv_id: str, window_days: int, now: datetime) -> int:
    """Deterministic start instant for a conversation, spread across the trailing window.

    Hashed off the conversation id rather than drawn from the RNG so a resumed run places a
    conversation at the same point in the window it would have had in a single pass.
    """
    if window_days <= 0:
        return int(now.timestamp() * NS)
    digest = hashlib.sha256(conv_id.encode()).digest()
    offset_seconds = int.from_bytes(digest[:6], "big") % (window_days * 24 * 3600)
    start = now - timedelta(days=window_days) + timedelta(seconds=offset_seconds)
    return int(start.timestamp() * NS)


def _ledger_path(corpus: str) -> Path:
    LEDGER_DIR.mkdir(parents=True, exist_ok=True)
    return LEDGER_DIR / f"{corpus}.emitted"


def _load_ledger(corpus: str) -> set[str]:
    path = _ledger_path(corpus)
    return set(path.read_text().split()) if path.exists() else set()


def emit_corpus(spec: CorpusSpec, args: argparse.Namespace) -> tuple[int, int]:
    done = set() if args.no_resume else _load_ledger(spec.key)
    provider = _provider(spec, args.endpoint, args.token, args.environment)
    tracer = provider.get_tracer("tessary.emit_local")
    ledger = _ledger_path(spec.key)
    now = datetime.now(timezone.utc)

    convs = 0
    turns = 0
    try:
        with ledger.open("a") as fh:
            loaded = spec.load()
            if args.start_order:
                # Trace-grain sweeps advance a keyset cursor on started_at while ingest settles traces
                # in arrival order; a trace that settles after the cursor passed its start is never
                # swept. Emitting in start order makes the two orders agree.
                loaded = sorted(loaded, key=lambda c: _conversation_start(c.conversation_id, args.window_days, now))
            for conv in loaded:
                if conv.conversation_id in done:
                    continue
                if args.limit and convs >= args.limit:
                    break
                # Re-seed per conversation, off the corpus and the conversation id. The OTel SDK draws
                # trace and span ids from `random`, so one global seed made turn 0 of every corpus
                # share a trace id, merging them on ingest. Still deterministic, still resumable, now
                # unique per (corpus, conversation).
                random.seed(f"{args.seed}:{spec.key}:{conv.conversation_id}")
                if not conv.turns:
                    continue
                cursor = _conversation_start(conv.conversation_id, args.window_days, now)
                for index, turn in enumerate(conv.turns):
                    if index > 0 and args.turn_gap_seconds > 0:
                        provider.force_flush()
                        time.sleep(args.turn_gap_seconds)
                    cursor = _emit_turn(tracer, spec, conv, turn, index, cursor)
                    turns += 1
                convs += 1
                if not args.dry_run:
                    fh.write(conv.conversation_id + "\n")
                    fh.flush()
                if convs % 250 == 0:
                    log.info("%s: %d conversations / %d turns queued", spec.key, convs, turns)
    finally:
        provider.force_flush(timeout_millis=120_000)
        provider.shutdown()
    return convs, turns


# --------------------------------------------------------------------------------------------------
# Preflight
# --------------------------------------------------------------------------------------------------


def _empty_export_request() -> bytes:
    """A valid ExportTraceServiceRequest carrying zero spans, and zero *bytes* too.

    A default-constructed request serializes to an empty byte string, which Spring rejects before
    `OtlpTraceController.export` is ever entered, so it tests nothing. One empty `ResourceSpans`
    gives a 2-byte body that parses cleanly and still contains no spans, so `StructuralEnricher`
    short-circuits on `entries.isEmpty()` and nothing is written.
    """
    from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest

    req = ExportTraceServiceRequest()
    req.resource_spans.add()
    return req.SerializeToString()


def preflight(endpoint: str, token: str) -> bool:
    """POST a zero-span OTLP request to exercise the auth path and nothing else.

    This reaches the controller body, so it proves project resolution, the project-scoped-token
    requirement and the WRITE key-scope check, all of which a silent OTLP exporter would swallow,
    making a 401 look exactly like a successful export.
    """
    req = urllib.request.Request(
        endpoint,
        data=_empty_export_request(),
        method="POST",
        headers={"Content-Type": "application/x-protobuf", "Authorization": f"Bearer {token}"},
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            log.info("preflight OK — HTTP %s from %s", resp.status, endpoint)
            return True
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")[:400]
        hint = {
            400: "body rejected before the controller — the request is not valid OTLP protobuf",
            401: "token rejected — needs a project-scoped tsy_ key",
            403: "wrong key scope — the receiver requires a WRITE (or mcp) key, not query-only",
            404: "route absent — deployment may be tessary.ingest.otlp.transport=grpc",
            413: "body too large — lower EXPORT_BATCH",
            415: "content type rejected — the receiver consumes application/x-protobuf only",
        }.get(e.code, "")
        log.error("preflight FAILED — HTTP %s %s\n%s", e.code, f"({hint})" if hint else "", body)
        return False
    except urllib.error.URLError as e:
        log.error("preflight FAILED — cannot reach %s: %s\n"
                  "Is the local stack up? (task dev:local)", endpoint, e.reason)
        return False


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--endpoint", default=os.environ.get("TESSARY_OTLP_ENDPOINT", DEFAULT_ENDPOINT))
    ap.add_argument("--token", default=os.environ.get("TESSARY_INGEST_TOKEN", ""))
    ap.add_argument("--corpus", choices=list(CORPORA), default="canary")
    ap.add_argument("--environment", default="production", help="deployment.environment.name")
    ap.add_argument("--window-days", type=int, default=28, help="trailing window to spread traffic across (0 = every conversation starts now)")
    ap.add_argument("--start-order", action="store_true", help="emit conversations in ascending start order, so ingest settles them in the order a trace-grain sweep cursor reads them")
    ap.add_argument("--limit", type=int, default=0, help="max NEW conversations per corpus (0 = all)")
    ap.add_argument("--no-resume", action="store_true", help="ignore the ledger and re-emit everything")
    ap.add_argument("--dry-run", action="store_true", help="build and send spans but do not record the ledger")
    ap.add_argument(
        "--turn-gap-seconds",
        type=float,
        default=0.0,
        help="real-time pause between the turns of one conversation, flushing after each. A turn-grain "
        "classifier scores a turn against the turns already ingested and threaded; emitting a whole "
        "conversation in one flush can race the sweep, which then scores the later turn with no prior "
        "context and moves its cursor past it for good",
    )
    ap.add_argument("--check", action="store_true", help="run the auth preflight and exit")
    ap.add_argument("--seed", type=int, default=17)
    args = ap.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    random.seed(args.seed)

    if not args.token:
        log.error("no token — pass --token or set TESSARY_INGEST_TOKEN")
        return 2
    if not preflight(args.endpoint, args.token):
        return 1
    if args.check:
        return 0

    selected = [CORPORA[args.corpus]]
    total_c = total_t = 0
    for spec in selected:
        log.info("=== %s -> call site %r, environment %r ===", spec.key, spec.call_site_id, args.environment)
        convs, turns = emit_corpus(spec, args)
        log.info("%s: emitted %d conversations / %d turns (traces)", spec.key, convs, turns)
        total_c += convs
        total_t += turns

    log.info("done — %d conversations, %d turns across %d call site(s)", total_c, total_t, len(selected))
    log.info("call sites: %s", ", ".join(s.call_site_id for s in selected))
    return 0


if __name__ == "__main__":
    sys.exit(main())
