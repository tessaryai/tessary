# SPDX-License-Identifier: Apache-2.0
"""framework.context — the serialization contract is load-bearing; pin it exactly."""

import json
from pathlib import Path

import pytest

from framework import EvalItem, LabeledExample, read_evalset, read_examples, write_jsonl
from framework.context import (
    ASSISTANT_STUB,
    cap_assistant,
    error_snippet,
    reduce_thread,
    render_context,
    render_input,
    render_serving_v0,
    stub_assistants,
    tool_marker,
    window_by_user_turns,
)

FIXTURES = json.loads(
    (Path(__file__).parent.parent / "framework" / "fixtures" / "context_contract.json").read_text()
)


def test_contract_v2_golden_fixtures():
    for case in FIXTURES["contract_v2"]:
        turns = [tuple(t) for t in case["turns"]]
        assert render_context(turns) == case["rendered_context"], case["name"]
        assert render_input(case["rendered_context"], case["final"]) == case["rendered_input"], case["name"]


def test_reduction_golden_fixtures():
    for case in FIXTURES["reduction"]:
        got = reduce_thread(case["context"], case["final"], case["budget"], case["recent_k"])
        assert got == case["reduced"], case["name"]


def test_narrowing_golden_fixtures():
    # Non-emptiness guard, mirroring ConversationThreadContractParityTest: a fixture section that
    # went missing would otherwise make this test vacuously pass in Python while failing in Java.
    assert FIXTURES["narrowing"], "narrowing cases present in the fixture"
    for case in FIXTURES["narrowing"]:
        shaped = case["context"]
        if case["user_turns"] >= 0:
            shaped = window_by_user_turns(shaped, case["user_turns"])
        if case["stub_assistant"]:
            shaped = stub_assistants(shaped)
        got = reduce_thread(shaped, ["user", case["final"]], 100000, 3)
        assert got == case["narrowed"], case["name"]


def test_window_counts_user_turns_not_blocks():
    """A tool-heavy exchange must not push the user message it belongs to out of a 1-turn window."""
    ctx = [
        ["user", "old ask"],
        ["assistant", "old reply"],
        ["user", "check the preauth"],
        ["tool", "a", None],
        ["tool", "b", None],
        ["tool", "c", None],
    ]
    assert window_by_user_turns(ctx, 1) == ctx[2:]  # the user turn survives all three tool blocks


def test_window_zero_empties_and_oversized_window_is_identity():
    ctx = [["user", "q"], ["assistant", "a"]]
    assert window_by_user_turns(ctx, 0) == []
    assert window_by_user_turns(ctx, 99) == ctx
    assert window_by_user_turns([], 1) == []


def test_stub_replaces_only_assistant_prose_and_preserves_order():
    ctx = [["user", "u1"], ["assistant", "a long agent reply"], ["tool", "lookup", "boom"]]
    assert stub_assistants(ctx) == [["user", "u1"], ["assistant", ASSISTANT_STUB], ["tool", "lookup", "boom"]]


def test_serving_v0_golden_fixtures():
    for case in FIXTURES["serving_v0"]:
        assert render_serving_v0(case["user_texts"]) == case["rendered"], case["name"]


def test_tool_marker_ok_and_error():
    assert tool_marker("deploy", None) == "[tool:deploy ok]"
    assert tool_marker("", None) == "[tool ok]"
    assert tool_marker("search", "boom") == "[tool:search error: boom]"


def test_error_snippet_collapses_whitespace_and_head_truncates():
    assert error_snippet("a\n  b\t c") == "a b c"
    assert error_snippet("x" * 200) == "x" * 120


def test_cap_assistant_passes_short_prose_and_caps_long():
    assert cap_assistant("  short  ") == "short"
    long = "word " * 400
    capped = cap_assistant(long)
    assert capped.endswith(" tok]") and "…[+" in capped
    assert len(capped) < len(long)


def test_reduce_thread_single_turn_degrades_to_bare_message():
    assert reduce_thread([], ["user", "the export button returns a 403"], 4000) == "the export button returns a 403"


def test_render_context_contract_v2():
    turns = [("user", "hi, can you export this?"), ("assistant", "Sure — running the export now.")]
    assert render_context(turns) == "[user] hi, can you export this?\n[assistant] Sure — running the export now."


def test_render_context_empty_and_unknown_speaker():
    assert render_context([]) == ""
    with pytest.raises(ValueError):
        render_context([("system", "nope")])


def test_render_input_with_and_without_context():
    ctx = render_context([("assistant", "Done.")])
    assert render_input(ctx, "still broken") == "[assistant] Done.\n[user] still broken"
    # No context degrades to the bare message — exact single-turn serving parity.
    assert render_input("", "still broken") == "still broken"


def test_schema_context_roundtrip(tmp_path):
    path = tmp_path / "rows.jsonl"
    write_jsonl(
        path,
        [
            LabeledExample("no.", 1, "t", context="[assistant] I could not find that file."),
            LabeledExample("plain single turn", 0, "t"),
        ],
    )
    back = list(read_examples(path))
    assert back[0].context.startswith("[assistant]")
    assert back[1].context == ""


def test_read_examples_falls_back_to_meta_context(tmp_path):
    # Synthesis rows produced before the first-class field carried context in meta.
    path = tmp_path / "legacy.jsonl"
    path.write_text('{"text": "no.", "label": 1, "source": "synth", "meta": {"context": "[assistant] hm"}}\n')
    (row,) = list(read_examples(path))
    assert row.context == "[assistant] hm"


def test_evalset_context_roundtrip(tmp_path):
    path = tmp_path / "eval.jsonl"
    write_jsonl(path, [EvalItem("ok.", 1, "t", slices={"context": "in_dialogue"}, context="[user] earlier")])
    (item,) = read_evalset(path)
    assert item.context == "[user] earlier"
