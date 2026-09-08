# SPDX-License-Identifier: Apache-2.0
"""Emit the ZipEats and policygpt corpora to a local Tessary instance as OTLP traces.

Why this exists separately from `data_gen.food_delivery.emit` (which targets Langfuse):

  * the platform's own receiver is **protobuf-only** (`OtlpTraceController` declares
    `consumes = application/x-protobuf`) and authenticates a project-scoped `tsy_` bearer, so the
    project is resolved from the token — no org/project path segment;
  * that emitter tags `tessary.call_site.id` on TOOL spans only, one id per tool name. Behaviour
    drift resolves a trace's scope from the *first tagged span*
    (`BehaviorSubstrateRepository.SELECT_TRACE_HEAD`), so per-tool tagging shatters one agent into
    ~15 call sites, none of which ever accumulates enough support to arm. Here the call site is a
    property of the **agent surface** and is stamped on every span in the trace.

Shape produced, matching the substrate spine `StructuralEnricher` builds:

    session (session.id)
      └── conversation (gen_ai.conversation.id)     <- interposed when BOTH keys are stated
            └── turn (one per distinct provider trace)
                  └── trace
                        └── observations: AGENT root, LLM / TOOL / RETRIEVAL children

One OTel trace per conversational TURN — that is what makes turn segmentation work, and it is the
grain the offline evals in `behavior_drift/` were measured against, so the fitted profile here is
comparable to the numbers in PROGRAM.md.

Timestamps are spread across a trailing window (default 28 days) preserving within-conversation
ordering, so the quarantine -> graduation clock and the arming saturation curve have real time
spread to work with. Both corpora were generated in a single day and would otherwise all land in
one bucket, where nothing can graduate.

Resumable: emitted conversation ids are appended to a per-corpus ledger, so re-running neither
duplicates nor loses work.

    # preflight only — proves the endpoint, token and key scope before sending anything
    python -m data_gen.emit_local --check

    # small verification batch, look at it in the UI first
    python -m data_gen.emit_local --corpus zipeats --limit 25

    # everything remaining, both corpora
    python -m data_gen.emit_local
"""

from __future__ import annotations

import argparse
import glob
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
ZIPEATS_FILE = REPO / "classifiers" / "data" / "food_delivery" / "conversations.jsonl"
POLICYGPT_DIR = Path(os.environ.get("POLICYGPT_STATE_DIR", Path.home() / "Downloads" / "pr2" / "state"))
LEDGER_DIR = REPO / "classifiers" / "data" / ".emit_local"

DEFAULT_ENDPOINT = "http://localhost:8000/v1/traces"
NS = 1_000_000_000
MAX_PAYLOAD_CHARS = 4000

# The receiver bounds the request body; the exporter batches spans, so keep batches modest.
EXPORT_BATCH = 128


# --------------------------------------------------------------------------------------------------
# Corpus-independent intermediate form. Both loaders reduce to this, so the span builder is written
# once and the two corpora cannot drift apart in shape.
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


def load_zipeats() -> Iterator[Conversation]:
    """ZipEats: turns -> rounds -> actions.

    Each round is one model call that decided on a set of actions, so it reduces to an `llm:plan`
    followed by that round's actions; the turn closes with an `llm:answer`. That is exactly the
    reduction `behavior_drift.eval_food_delivery.load()` performs, so the sequence the backend fits
    is the sequence the offline numbers were measured on.
    """
    if not ZIPEATS_FILE.exists():
        raise SystemExit(f"ZipEats corpus not found: {ZIPEATS_FILE}")
    for line in ZIPEATS_FILE.read_text().splitlines():
        if not line.strip():
            continue
        c = json.loads(line)
        turns: list[Turn] = []
        for t in c.get("turns", []):
            steps: list[Step] = []
            for rnd in t.get("rounds", []):
                actions = rnd.get("actions", [])
                steps.append(Step(kind="llm", name="plan"))
                for a in actions:
                    is_retrieval = a.get("kind") == "retrieval" or bool(a.get("collection"))
                    steps.append(
                        Step(
                            kind="retrieval" if is_retrieval else "tool",
                            name=a.get("name") or a.get("collection") or "unknown",
                            args=a.get("args"),
                            result=a.get("result"),
                            is_error=bool(a.get("error")),
                            corpus=a.get("collection"),
                        )
                    )
            steps.append(Step(kind="llm", name="answer"))
            turns.append(Turn(user=t.get("user_message", ""), assistant=t.get("final_answer", ""), steps=steps))
        yield Conversation(
            conversation_id=c["conversation_id"],
            user_id=c.get("customer_id", "unknown"),
            turns=turns,
            attributes={
                k: str(v)
                for k, v in {
                    "tessary.intent": c.get("intent"),
                    "tessary.persona.id": c.get("persona_id"),
                    "tessary.persona.tone": c.get("persona_tone"),
                    "tessary.resolved": c.get("resolved"),
                }.items()
                if v is not None
            },
        )


def load_policygpt() -> Iterator[Conversation]:
    """policygpt run-2: turns carry a already-interleaved flat `steps` list of llm / tool entries."""
    files = sorted(glob.glob(str(POLICYGPT_DIR / "cnv-pr2-*.json")))
    if not files:
        raise SystemExit(f"no policygpt state files under {POLICYGPT_DIR} (set POLICYGPT_STATE_DIR)")
    for path in files:
        d = json.loads(Path(path).read_text())
        turns: list[Turn] = []
        for t in d.get("turns", []):
            steps: list[Step] = []
            for s in t.get("steps", []):
                if s.get("kind") == "tool":
                    result = s.get("result") or {}
                    errored = isinstance(result, dict) and (
                        result.get("error") is not None or result.get("verified") is False
                    )
                    steps.append(
                        Step(
                            kind="tool",
                            name=s.get("name", "unknown"),
                            args=s.get("args"),
                            result=result,
                            is_error=bool(errored),
                        )
                    )
                else:
                    steps.append(
                        Step(
                            kind="llm",
                            name="chat",
                            model=s.get("model"),
                            usage=s.get("usage") or {},
                        )
                    )
            turns.append(Turn(user=t.get("user", ""), assistant=t.get("assistant", ""), steps=steps))
        yield Conversation(
            conversation_id=d["conversation_id"],
            user_id=d.get("verified_member_id") or d.get("policy_no") or "unknown",
            turns=turns,
            attributes={
                k: str(v)
                for k, v in {
                    "tessary.policy_no": d.get("policy_no"),
                    "tessary.persona.id": d.get("persona_id"),
                    "tessary.status": d.get("status"),
                    "tessary.ended_reason": d.get("ended_reason"),
                }.items()
                if v is not None
            },
        )


def load_canary() -> Iterator[Conversation]:
    """One hand-built conversation, no fixture file, no LLM key — for scripts/check-open-boot.sh (#878).

    Why this exists instead of reusing `zipeats`/`policygpt` as the plan for #878 originally assumed:
    neither is actually available in a genuine clean-room export. `zipeats`'s backing file
    (`classifiers/data/food_delivery/conversations.jsonl`) is covered by `classifiers/.gitignore`'s
    blanket `data/` rule and is only on a machine that ran `data_gen.food_delivery.generate` (an
    LLM-keyed step — exactly the kind of credential this check exists to prove absent) at some
    point; `policygpt` reads from `POLICYGPT_STATE_DIR`, which defaults to a path under the
    OPERATOR'S HOME directory outside the repo entirely. `scripts/lib/export-simulate.sh` copies
    tracked-or-not-ignored files only, so a fresh CI checkout (`actions/checkout` into a clean
    workspace) has neither. This loader needs nothing but the interpreter.

    Also solves a second problem the original plan's "either corpus satisfies the same proof"
    framing did not check against the code: of the classifiers the open edition actually provisions
    (`duration_drift`/`cost_drift`/`tool_error`/`secret_leak`/`malformed_output` — `frustration` and
    `groundedness`, the only two that read the classify-service encoder, are in
    `CapabilityService.UNAVAILABLE_IN_OPEN_EDITION`), every one but `secret_leak` is a statistical
    detector requiring a 100-500-call baseline (`min_sample`/`min_baseline_calls` in
    `BuiltInClassifierCatalog`) before it can fire at all — no realistic single-trace emission trips
    them. `secret_leak` is `Grain.OBSERVATION`: it scores one call's own output against a fixed
    pattern set with no baseline. So this conversation's one tool call returns an AWS-access-key-id
    shaped string (`AKIA` + 16 upper/digit chars) matching `SecretLeakDetector`'s first, STRONG
    pattern verbatim — no entropy gate, no ambiguity, HIGH confidence, fires on the very first sweep
    after ingest. That is what makes `/classifiers/events` reliably non-empty for step (g) of the
    check without waiting on volume the check's time budget cannot afford.
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
                        # Deliberately fake and unusable — a canary shape, not a real credential.
                        # sk_test_ + 16+ alnum matches SecretLeakDetector's "stripe-key" pattern
                        # (strong, no entropy gate) exactly.
                        #
                        # WHY NOT AN AWS AKIA KEY (the previous literal): the platform's default PII
                        # redaction guard (BuiltInRedactionRules "Provider API key", enabled on every
                        # project, run in SubstrateWriter BEFORE the row is persisted) rewrites
                        # AKIA[0-9A-Z]{16} to [REDACTED_API_KEY] — and SecretLeakDetector scores the
                        # PERSISTED output. So an AKIA canary can never produce a detection; the first
                        # CI run of check-open-boot.sh to reach this step (33614514928) proved that.
                        # The Stripe shape is matched by the detector and by NO built-in redaction
                        # rule (checked against both lists). The gap itself — every strong secret_leak
                        # pattern except this one is also a default redaction rule — is #1044; this
                        # literal only keeps the boot canary honest until that lands.
                        result={"config": "region=us-east-1\nstripe_sk=sk_test_BOOTCHECKCANARY000001\n"},
                    ),
                    Step(kind="llm", name="answer"),
                ],
                assistant="Found it — region us-east-1, and the config block is above.",
            )
        ],
        attributes={"tessary.canary": "true"},
    )


def load_groundedness_canary() -> Iterator[Conversation]:
    """One RAG turn whose answer contradicts the passage it retrieved (epic 5, #1137).

    The groundedness detector (paid) scores an `rag_answer`-shaped call site's answer against the
    `retrieved_doc` rows of the nearest earlier retrieval span in the conversation. So this turn
    retrieves one passage that states a 14-day return window and then answers with a 90-day window
    and a two-hour refund, two verifiable claims the passage does not support. The call site's shape
    is not on the wire (shape is a pipeline fact); the local detection run sets it before emitting.
    """
    yield Conversation(
        conversation_id="canary-groundedness-0001",
        user_id="canary-operator",
        turns=[
            Turn(
                user="How long do I have to return a jacket I bought online, and when would the refund land?",
                steps=[
                    Step(kind="llm", name="plan"),
                    Step(
                        kind="retrieval",
                        name="policy_search",
                        corpus="returns-policy",
                        args={"query": "online return window and refund timing"},
                        documents=[
                            "Returns policy: items bought online may be returned within 14 days of delivery. "
                            "Refunds are issued to the original payment method within 5 to 7 business days "
                            "after the returned item is received at the warehouse."
                        ],
                        result={"hits": 1},
                    ),
                    # The classifier scores the LLM observation's OWN output, not the agent root's, so
                    # the answering span carries the question and the answer the way a real chat span does.
                    Step(
                        kind="llm",
                        name="answer",
                        args="How long do I have to return a jacket I bought online, and when would the refund land?",
                        result=(
                            "You have 90 days from delivery to return the jacket. The refund is paid to your card "
                            "within 2 hours of the courier collecting the parcel."
                        ),
                    ),
                ],
                assistant=(
                    "You have 90 days from delivery to return the jacket. The refund is paid to your card "
                    "within 2 hours of the courier collecting the parcel."
                ),
            )
        ],
        attributes={"tessary.canary": "true"},
    )


def load_frustration_canary() -> Iterator[Conversation]:
    """Two user turns, the second unmistakably angry AT THE AGENT (epic 5, #1161).

    The frustration built-in scores the user turn with one prior user turn of context
    (`context_min_prior_user_turns: 1`) and demotes a fire to LOW unless the attribution head agrees
    the agent caused it, so the anger here names the assistant's own failures, not a courier or a
    third party. Deterministic and fixture-free, like `canary`.
    """
    yield Conversation(
        conversation_id="canary-frustration-0001",
        user_id="canary-operator",
        turns=[
            Turn(
                user="I need to change the delivery address on order 44812 before it ships.",
                steps=[
                    Step(kind="llm", name="plan", args="I need to change the delivery address on order 44812 before it ships."),
                    Step(kind="tool", name="lookup_order", args={"order_id": "44812"}, result={"status": "packed"}),
                    Step(
                        kind="llm",
                        name="answer",
                        args="I need to change the delivery address on order 44812 before it ships.",
                        result="I can see order 44812. Could you tell me the new address?",
                    ),
                ],
                assistant="I can see order 44812. Could you tell me the new address?",
            ),
            Turn(
                user=(
                    "I already gave you the new address twice and you keep asking me for it again. "
                    "This is the third time you have ignored what I typed. You are useless, you are wasting "
                    "my time, and I want a human agent right now."
                ),
                steps=[
                    Step(
                        kind="llm",
                        name="answer",
                        args=(
                            "I already gave you the new address twice and you keep asking me for it again. "
                            "This is the third time you have ignored what I typed. You are useless, you are wasting "
                            "my time, and I want a human agent right now."
                        ),
                        result="I am sorry for the trouble. Could you confirm the new address once more?",
                    ),
                ],
                assistant="I am sorry for the trouble. Could you confirm the new address once more?",
            ),
        ],
        attributes={"tessary.canary": "true"},
    )


DRIFT_CANARY_CONVERSATIONS = 320


def load_drift_canary() -> Iterator[Conversation]:
    """A stable order-support behaviour, repeated enough to fit and ARM a drift profile (epic 5, #1160).

    Behaviour drift fits n-gram baselines over a call site's traces and arms the profile only past
    hard floors (200 reservoir samples, 300 traces by default) once discovery has flattened across
    sustained fits. Every conversation here runs the same four-step sequence, `llm:plan`,
    `tool:lookup_order`, `tool:check_refund_eligibility`, `llm:answer`, with only the order id and
    the wording varying, so the alphabet saturates quickly and the profile arms. Deterministic and
    fixture-free; the companion `drift-canary-novel` breaks the sequence.
    """
    openers = [
        "Where is my order {oid}? It was due yesterday.",
        "Can I get a refund on order {oid}? The food arrived cold.",
        "Order {oid} is missing the drinks I paid for.",
        "I was charged twice for order {oid}.",
    ]
    for i in range(DRIFT_CANARY_CONVERSATIONS):
        oid = 50000 + i
        user = openers[i % len(openers)].format(oid=oid)
        yield Conversation(
            conversation_id=f"canary-drift-{i:04d}",
            user_id=f"canary-customer-{i % 40:02d}",
            turns=[
                Turn(
                    user=user,
                    steps=[
                        Step(kind="llm", name="plan", args=user),
                        Step(kind="tool", name="lookup_order", args={"order_id": str(oid)}, result={"status": "delivered"}),
                        Step(kind="tool", name="check_refund_eligibility", args={"order_id": str(oid)}, result={"eligible": i % 3 == 0}),
                        Step(kind="llm", name="answer", args=user, result=f"I have looked up order {oid} and checked its refund eligibility; here is what I can do."),
                    ],
                    assistant=f"I have looked up order {oid} and checked its refund eligibility; here is what I can do.",
                )
            ],
            attributes={"tessary.canary": "true"},
        )


def load_drift_canary_novel() -> Iterator[Conversation]:
    """One conversation that breaks the fitted sequence: no lookup, two never-seen tools (epic 5, #1160).

    Against an ARMED profile fitted on `drift-canary`, the grams here are novel, which is the D1
    novelty firing the drift detector writes a `behavior_drift_detection` row for. The same turn
    also omits `tool:lookup_order`, so an engine SOP obliging that tool on every conversation scores
    it as a violation once the SOP is compiled: one conversation serves both remaining arms.
    """
    user = "Order 99901 never arrived and nobody answers the phone. Give me my money back."
    yield Conversation(
        conversation_id="canary-drift-novel-0001",
        user_id="canary-customer-99",
        turns=[
            Turn(
                user=user,
                steps=[
                    Step(kind="llm", name="plan", args=user),
                    Step(kind="tool", name="escalate_to_human", args={"reason": "no delivery"}, result={"ticket": "T-99901"}),
                    Step(kind="tool", name="issue_manual_credit", args={"amount": 42.5}, result={"ok": True}),
                    Step(kind="llm", name="answer", args=user, result="I have escalated this and issued a manual credit."),
                ],
                assistant="I have escalated this and issued a manual credit.",
            )
        ],
        attributes={"tessary.canary": "true"},
    )


def load_drift_canary_violation() -> Iterator[Conversation]:
    """A second sequence-breaking conversation, distinct id, for the conformance sweep (epic 5, #1160).

    Emitted after the SOP compiles so the sweep has a fresh turn to score; a re-emit of the novel
    conversation would carry the same deterministic trace id and be deduplicated by ingest.
    """
    user = "Cancel order 99902 and refund me, the restaurant closed."
    yield Conversation(
        conversation_id="canary-drift-violation-0001",
        user_id="canary-customer-98",
        turns=[
            Turn(
                user=user,
                steps=[
                    Step(kind="llm", name="plan", args=user),
                    Step(kind="tool", name="cancel_order", args={"order_id": "99902"}, result={"ok": True}),
                    Step(kind="llm", name="answer", args=user, result="Cancelled and refunded."),
                ],
                assistant="Cancelled and refunded.",
            )
        ],
        attributes={"tessary.canary": "true"},
    )


CORPORA: dict[str, CorpusSpec] = {
    "drift-canary-violation": CorpusSpec(
        key="drift-canary-violation",
        call_site_id="paid-detections-canary-orders",
        agent_name="paid-detections-canary-agent",
        service_name="paid-detections-canary-agent",
        model="anthropic.claude-haiku-4-5",
        repo_url="https://github.com/tessaryai/tessary",
        load=load_drift_canary_violation,
    ),
    "drift-canary": CorpusSpec(
        key="drift-canary",
        call_site_id="paid-detections-canary-orders",
        agent_name="paid-detections-canary-agent",
        service_name="paid-detections-canary-agent",
        model="anthropic.claude-haiku-4-5",
        repo_url="https://github.com/tessaryai/tessary",
        load=load_drift_canary,
    ),
    "drift-canary-novel": CorpusSpec(
        key="drift-canary-novel",
        call_site_id="paid-detections-canary-orders",
        agent_name="paid-detections-canary-agent",
        service_name="paid-detections-canary-agent",
        model="anthropic.claude-haiku-4-5",
        repo_url="https://github.com/tessaryai/tessary",
        load=load_drift_canary_novel,
    ),
    "groundedness-canary": CorpusSpec(
        key="groundedness-canary",
        call_site_id="paid-detections-canary-rag",
        agent_name="paid-detections-canary-agent",
        service_name="paid-detections-canary-agent",
        model="anthropic.claude-haiku-4-5",
        repo_url="https://github.com/tessaryai/tessary",
        load=load_groundedness_canary,
    ),
    "frustration-canary": CorpusSpec(
        key="frustration-canary",
        call_site_id="paid-detections-canary-support",
        agent_name="paid-detections-canary-agent",
        service_name="paid-detections-canary-agent",
        model="anthropic.claude-haiku-4-5",
        repo_url="https://github.com/tessaryai/tessary",
        load=load_frustration_canary,
    ),
    "canary": CorpusSpec(
        key="canary",
        call_site_id="open-boot-check-canary",
        agent_name="open-boot-check-canary-agent",
        service_name="open-boot-check-canary-agent",
        model="anthropic.claude-haiku-4-5",
        repo_url="https://github.com/tessaryai/tessary",
        load=load_canary,
    ),
    "zipeats": CorpusSpec(
        key="zipeats",
        call_site_id="zipeats-support",
        agent_name="zipeats-support-agent",
        service_name="zipeats-support-agent",
        model="anthropic.claude-haiku-4-5",
        repo_url="https://github.com/tessaryai/evals-sample-app",
        load=load_zipeats,
    ),
    "policygpt": CorpusSpec(
        key="policygpt",
        call_site_id="policygpt-member-support",
        agent_name="policygpt-member-support-agent",
        service_name="policygpt",
        model="anthropic.claude-haiku-4-5",
        repo_url="https://github.com/tessaryai/evals-sample-app",
        load=load_policygpt,
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
    conversation context between the session root and its turns. `tessary.call_site.id` is on every
    span rather than just the root because the drift sweep takes the first tagged span it finds and a
    root-only tag would leave the scope hostage to span ordering.
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

    # Context() with no active span forces a fresh trace id — one provider trace per turn is what
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
                # Trace-grain sweeps advance a keyset cursor on started_at while ingest settles traces on a
                # wall-clock timer in ARRIVAL order; a trace that settles after the cursor passed its start
                # is never swept. Emitting in start order makes the two orders agree (epic 5, #1160).
                loaded = sorted(loaded, key=lambda c: _conversation_start(c.conversation_id, args.window_days, now))
            for conv in loaded:
                if conv.conversation_id in done:
                    continue
                if args.limit and convs >= args.limit:
                    break
                # Re-seed per conversation, off the corpus AND the conversation id. The OTel SDK draws
                # trace and span ids from `random`, so one global seed made turn 0 of EVERY corpus share
                # a trace id; ingesting two corpora into one project then merged them (epic 5, #1137).
                # Still deterministic, still resumable, now unique per (corpus, conversation).
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
    """A valid ExportTraceServiceRequest carrying zero spans, and — importantly — zero *bytes*.

    A default-constructed request serializes to an empty byte string, which Spring rejects during
    argument resolution ("Required request body is missing") before `OtlpTraceController.export` is
    ever entered — so it tests nothing. Adding one empty `ResourceSpans` gives a 2-byte body
    (field 1, wire type 2, length 0) that parses cleanly and still contains no spans, so
    `StructuralEnricher` short-circuits on `entries.isEmpty()` and nothing is written.
    """
    from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest

    req = ExportTraceServiceRequest()
    req.resource_spans.add()
    return req.SerializeToString()


def preflight(endpoint: str, token: str) -> bool:
    """POST a zero-span OTLP request to exercise the auth path and nothing else.

    This reaches the controller body, so it proves project resolution, the project-scoped-token
    requirement and the WRITE key-scope check — all of which a silent OTLP exporter would swallow,
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
            404: "route absent — deployment may be evals.ingest.otlp.transport=grpc",
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
    ap.add_argument("--corpus", choices=[*CORPORA, "both"], default="both")
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
        "context and moves its cursor past it for good (epic 5, #1161)",
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

    selected = list(CORPORA.values()) if args.corpus == "both" else [CORPORA[args.corpus]]
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
