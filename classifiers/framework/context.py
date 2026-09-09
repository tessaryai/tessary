# SPDX-License-Identifier: Apache-2.0
"""The context serialization contract (v2) — SYNTHESIS.md §3.7 rule 1, made executable.

An encoder only ever sees a conversation thread as *rendered text*, so the flattening of prior
turns must be defined ONCE and used identically by synthesis, the gold-set builders, and (when
the platform widens `SignalField` beyond the single turn) serving. This module is that single
definition; nothing else in the repo may invent its own rendering.

Contract v2 — trajectory-preserving REDUCTION. Raw agent threads (tool/RAG payloads) blow past
the encoder window, and dropping tool/retrieval observations loses the agent's *failure history*
— the antecedent that makes quiet user frustration ("nevermind, I'll do it myself") legible. So
the thread is compressed to a compact, trajectory-preserving form, purely deterministic (no LLM):

- Prior turns render oldest-first, one turn per block, blocks separated by single newlines.
  ``user`` turns are prefixed ``[user] ``, ``assistant`` turns ``[assistant] `` — the scored
  (final user) message degrades to the bare stripped text when there is no prior context.
- **user** turns render verbatim.
- **assistant** turns keep the user-facing prose, capped at :data:`ASSISTANT_CAP_TOKENS` tokens
  (head + a ``…[+N tok]`` marker); raw tool-call argument blobs collapse to markers.
- **tool outcomes** render as terse markers, never full payloads: success is bare
  ``[tool:<name> ok]``; error is a snippet ``[tool:<name> error: <msg>]`` where ``<msg>`` is a
  dumb head-truncation (≤ :data:`_ERROR_SNIPPET_MAX` chars, whitespace-collapsed) of the error
  — no summarization. Inline ``tool_call``/``tool_result`` parts enrich into these markers;
  standalone tool observations surface as marker turns via :func:`reduce_thread`.
- **multimodal** placeholders (``[image]``/``[image: cap]``, ``[file: name]``/``[file]``,
  ``[unsupported]``) render unchanged so the encoder is aware of, never blind to, an attachment.
- The character budget uses TRAJECTORY-PRESERVING eviction (:func:`reduce_thread`): it keeps the
  baseline head (earliest turn + earliest failure marker) and the most-recent K turns, thins the
  redundant middle, never drops the scored final turn, and marks any drop with
  ``[… N earlier turns elided …]`` — elision is marked, never silent.

Both sides are pinned byte-for-byte by ``framework/fixtures/context_contract.json`` and the Java
mirror (``ConversationThreadRenderer`` + ``ContentExtractor.partPlaceholder``). If this module
diverges, serving feeds the model an input format it never trained on — the exact train/serve
skew the fixture catches.
"""

from __future__ import annotations

import re

USER_PREFIX = "[user] "
ASSISTANT_PREFIX = "[assistant] "
_PREFIXES = {"user": USER_PREFIX, "assistant": ASSISTANT_PREFIX}

# Contract v2 multimodal-awareness: a text encoder cannot see an image, but it must know one was
# present, so a message's non-text parts render as typed placeholders instead of being dropped.
_IMAGE_TYPES = {"image", "image_url", "input_image", "output_image", "image_ref"}
# "file"/"document"/"input_file" is the third-party/OTel placeholder vocabulary; the platform's own
# document_ref/document_b64/document_url kinds route here too, mirroring Java
# ContentExtractor.partPlaceholder's identical reconciliation.
_FILE_TYPES = {"file", "document", "input_file", "document_ref", "document_b64", "document_url"}
_TOOL_CALL_TYPES = {"tool_call", "tool_use"}
_TOOL_RESULT_TYPES = {"tool_result", "tool_call_response"}
_TEXT_TYPES = {"", "text", "reasoning", "thinking"}

# Reduction knobs — the Java mirror pins the SAME constants (ConversationThreadRenderer).
ASSISTANT_CAP_TOKENS = 256
_CHARS_PER_TOKEN = 4
_ERROR_SNIPPET_MAX = 120
DEFAULT_RECENT_TURNS = 3

_WS_RE = re.compile(r"\s+")


def _est_tokens(text: str) -> int:
    """A deterministic, cross-language token estimate: ``ceil(len / 4)`` (~4 chars/token)."""
    return (len(text) + _CHARS_PER_TOKEN - 1) // _CHARS_PER_TOKEN


def cap_assistant(text: str) -> str:
    """Cap assistant prose at :data:`ASSISTANT_CAP_TOKENS` tokens: under the cap it rides through
    stripped; over it, the head is kept and a ``…[+N tok]`` marker records the dropped tail (N =
    estimated tokens dropped). A dumb head-truncation — no summarization."""
    text = text.strip()
    if _est_tokens(text) <= ASSISTANT_CAP_TOKENS:
        return text
    budget = ASSISTANT_CAP_TOKENS * _CHARS_PER_TOKEN
    head = text[:budget].rstrip()
    dropped = _est_tokens(text[budget:])
    return f"{head} …[+{dropped} tok]"


def error_snippet(msg) -> str:
    """A tool error rendered terse: whitespace-collapsed, stripped, head-truncated to
    :data:`_ERROR_SNIPPET_MAX` chars. Dumb truncation — no summarization, no LLM."""
    return _WS_RE.sub(" ", str(msg)).strip()[:_ERROR_SNIPPET_MAX]


def tool_marker(name, error) -> str:
    """A tool outcome as a terse marker: ``[tool:<name> ok]`` on success, ``[tool:<name> error:
    <snippet>]`` on error. A blank name drops to ``[tool ok]`` / ``[tool error: …]``."""
    name = (name or "").strip()
    head = f"tool:{name}" if name else "tool"
    if error is not None and str(error).strip():
        return f"[{head} error: {error_snippet(error)}]"
    return f"[{head} ok]"


def _part_error(part: dict):
    """The error text a tool part carries, or ``None`` when it succeeded. A part errs when it sets
    ``is_error: true`` or carries a non-empty ``error``/``error_type`` string."""
    if part.get("is_error") is True:
        for field in ("error", "error_type", "content", "result", "response"):
            value = part.get(field)
            if isinstance(value, str) and value.strip():
                return value
        return "error"
    for field in ("error", "error_type"):
        value = part.get(field)
        if isinstance(value, str) and value.strip():
            return value
    return None


def _part_text_field(part: dict) -> str:
    for field in ("text", "content"):
        value = part.get(field)
        if isinstance(value, str):
            return value
    return ""


def render_part(part) -> str:
    """One content part rendered for the thread view: text parts return their text; tool parts
    become terse outcome markers; every other non-text part returns a typed placeholder so the
    encoder knows it was present. Mirrored by Java ``ContentExtractor.partPlaceholder``; pinned by
    ``context_contract.json``. A successful ``tool_result`` collapses to ``""`` — the paired
    ``tool_call`` already reported the ``ok``; only errors add a result marker."""
    if isinstance(part, str):
        return part.strip()
    if not isinstance(part, dict):
        return ""
    ptype = part.get("type", "") or ""
    if ptype in _TEXT_TYPES:
        return _part_text_field(part).strip()
    if ptype in _IMAGE_TYPES:
        caption = str(part.get("caption") or part.get("alt") or "").strip()
        return f"[image: {caption}]" if caption else "[image]"
    if ptype in _FILE_TYPES:
        name = str(part.get("name") or part.get("filename") or "").strip()
        return f"[file: {name}]" if name else "[file]"
    if ptype in _TOOL_CALL_TYPES:
        return tool_marker(part.get("name"), _part_error(part))
    if ptype in _TOOL_RESULT_TYPES:
        error = _part_error(part)
        return tool_marker("", error) if error else ""
    text = _part_text_field(part).strip()
    return text if text else "[unsupported]"


def render_parts(parts: list) -> str:
    """Render a message's parts to its turn text, non-text parts as markers/placeholders. The
    non-empty part tokens are joined with a single space."""
    return " ".join(token for token in (render_part(p) for p in parts) if token)


def _turn_text(content) -> str:
    """A turn's text: a bare string verbatim, or a parts list flattened via :func:`render_parts`."""
    return content if isinstance(content, str) else render_parts(content)


def render_context(turns: list) -> str:
    """Render prior turns per contract v2. ``turns`` = [(speaker, content), ...] oldest-first,
    speaker in {"user", "assistant"}; ``content`` is a string or a multimodal parts list. Assistant
    prose is capped (:func:`cap_assistant`); user turns ride verbatim. Returns "" for no context."""
    blocks = []
    for speaker, content in turns:
        prefix = _PREFIXES.get(speaker)
        if prefix is None:
            raise ValueError(f"unknown speaker {speaker!r} (contract v2 knows user/assistant)")
        text = _turn_text(content)
        blocks.append(prefix + (cap_assistant(text) if speaker == "assistant" else text.strip()))
    return "\n".join(blocks)


def render_input(context: str, final) -> str:
    """The full model input: rendered prior turns + the scored user message, contract order. ``final``
    is a string or a multimodal parts list.

    With empty context this degrades to the bare final message — the exact single-turn input
    the incumbent head sees — so one function serves both scoring modes.
    """
    final_text = _turn_text(final).strip()
    final_block = USER_PREFIX + final_text
    return f"{context}\n{final_block}" if context else final_text


ELISION = "[… {n} earlier turns elided …]"


def _reduced_block(item) -> tuple:
    """One context turn reduced to ``(speaker, rendered, is_error)``. ``item`` is ``[speaker,
    content]`` for user/assistant (content a string or parts list) or ``[\"tool\", name, error]``
    for a standalone tool observation (``error`` null/absent = success)."""
    speaker = item[0]
    if speaker == "tool":
        name = item[1]
        error = item[2] if len(item) > 2 else None
        return ("tool", tool_marker(name, error), bool(error is not None and str(error).strip()))
    content = item[1]
    text = _turn_text(content)
    if speaker == "user":
        return ("user", USER_PREFIX + text.strip(), False)
    if speaker == "assistant":
        return ("assistant", ASSISTANT_PREFIX + cap_assistant(text), False)
    raise ValueError(f"unknown speaker {speaker!r} (contract v2 knows user/assistant/tool)")


def _final_block(final, has_context: bool) -> str:
    """The scored trailing turn. A user turn with no prior context degrades to the bare stripped
    message (incumbent single-turn parity); otherwise it is role-prefixed. Never capped — the
    scored turn rides through whole (serving-side truncation is the tokenizer's job)."""
    speaker = final[0]
    if speaker == "tool":
        error = final[2] if len(final) > 2 else None
        return tool_marker(final[1], error)
    text = _turn_text(final[1]).strip()
    if speaker == "user":
        return USER_PREFIX + text if has_context else text
    prefix = _PREFIXES.get(speaker)
    if prefix is None:
        raise ValueError(f"unknown speaker {speaker!r} (contract v2 knows user/assistant/tool)")
    return prefix + text


def reduce_thread(context: list, final, budget: int, recent_k: int = DEFAULT_RECENT_TURNS) -> str:
    """Serialize a whole session thread within ``budget`` characters using trajectory-preserving
    eviction. ``context`` is the oldest-first prior turns (see :func:`_reduced_block`), ``final`` the
    scored trailing turn. Under budget the whole thread renders; over it, the baseline head (earliest
    turn through the earliest failure marker) and the most-recent ``recent_k`` turns are kept, the
    redundant middle is thinned, and the drop is marked ``[… N earlier turns elided …]``. The scored
    final turn is never dropped."""
    blocks = [_reduced_block(it) for it in context]
    has_context = bool(blocks)
    final_block = _final_block(final, has_context)
    texts = [b[1] for b in blocks]
    full = "\n".join(texts + [final_block]) if texts else final_block
    if len(full) <= budget or not blocks:
        return full

    n = len(blocks)
    head_end = 1  # the earliest turn is always an anchor
    err_idx = next((i for i, b in enumerate(blocks) if b[2]), None)
    if err_idx is not None and err_idx + 1 > head_end:
        head_end = err_idx + 1  # keep through the earliest failure — the antecedent
    tail_start = max(head_end, n - recent_k)
    head = texts[:head_end]
    tail = texts[tail_start:]
    dropped = tail_start - head_end

    def assemble(head_blocks, tail_blocks, dropped_count):
        mid = [ELISION.format(n=dropped_count)] if dropped_count > 0 else []
        return "\n".join(head_blocks + mid + tail_blocks + [final_block])

    result = assemble(head, tail, dropped)
    while len(result) > budget and len(tail) > 1:
        tail = tail[1:]  # thin the recent tail from its oldest side
        dropped += 1
        result = assemble(head, tail, dropped)
    return result


ASSISTANT_STUB = "[reply]"


def window_by_user_turns(context: list, user_turns: int) -> list:
    """The tail of ``context`` starting at the ``user_turns``-th-from-last USER turn — one "exchange"
    per user turn, so the assistant reply and any tool markers FOLLOWING a kept user turn ride with it.

    Anchored on user turns rather than a raw block count: a block count cuts an arbitrary distance
    into the past, so one turn that fired six tools would consume the whole window and evict the user
    message the reply is answering. ``user_turns <= 0`` returns an empty context (the bare final turn);
    a window larger than the thread returns it unchanged. Mirrors Java ``windowByUserTurns``.
    """
    if user_turns <= 0:
        return []
    seen = 0
    for i in range(len(context) - 1, -1, -1):
        if context[i][0] == "user":
            seen += 1
            if seen == user_turns:
                return list(context[i:])
    return list(context)


def stub_assistants(context: list) -> list:
    """``context`` with every assistant turn's prose replaced by :data:`ASSISTANT_STUB`, leaving user
    turns and tool markers untouched. Turn COUNT and ORDER are preserved — the alternation is the
    signal being kept. Mirrors Java ``stubAssistants``.

    Why: a pooled single-utterance head scores the whole string at once, and the agent's own words are
    what it reads most wrongly — a sympathetic apology ("I understand your frustration") reads as the
    USER's emotion — while their mere presence is what makes a terse reply ("ok fine") legible as a
    reaction rather than an opening.
    """
    return [(["assistant", ASSISTANT_STUB] if item[0] == "assistant" else item) for item in context]


def render_serving_v0(user_texts: list[str]) -> str:
    """What the platform sent the encoder BEFORE the thread widening (kept for the incumbent
    single-turn parity fixtures).

    Traced from the live serving code, not assumed: the incumbent path scored
    ``ContentExtractor.columnText(input, "user")`` — every user-role message in the observation's
    gen_ai envelope, in message order, newline-joined, empties skipped, no speaker prefixes,
    assistant turns dropped. Pinned by the ``serving_v0`` fixtures.
    """
    return "\n".join(t.strip() for t in user_texts if t and t.strip())
