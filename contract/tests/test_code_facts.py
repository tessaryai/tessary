# SPDX-License-Identifier: Apache-2.0
"""The vendored validator's rules for the CODE-TRACKED FACTS.

`output_schema`, `tools` and `capabilities` describe the product's source rather than its traffic,
and the platform and the plugin have to agree on their semantics exactly — the `.tessary/` bundle is
the only thing between the two repos. What is pinned here is specifically the behaviour the platform
depends on; see README.md for why these tests live in the private repo.

The distinctions below are load-bearing rather than stylistic:

* ABSENT vs EXPLICIT NULL on `output_schema`. Absent means "this bundle does not carry the fact" and
  the platform keeps whatever it captured; `output_schema: null` asserts the code declares none and
  clears the capture. If the validator rejected the null, the second state would be inexpressible.
* `kind` is an OPEN vocabulary. The platform holds it as a plain string and its observer is the
  intended writer of the manifest, so rejecting an unknown kind here would make the validator reject
  a bundle the platform itself produced.
* `name` is the only required field on a tool. Requiring more would silently drop exactly the tools
  we understand least, which are the interesting ones.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

# The vendored plugin sources sit one directory up. validate.py imports pipeline_io as a sibling.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import validate  # noqa: E402


def errors(pipeline: dict) -> list[str]:
    return validate._bundle_code_facts(pipeline)


def call_site(**fields) -> dict:
    return {"call_sites": [{"id": "support.answer", **fields}]}


# --------------------------------------------------------------------- output_schema


def test_absent_schema_is_not_an_error():
    """Absence is the common case: most shards carry no schema and must not be flagged."""
    assert errors(call_site()) == []


def test_explicit_null_schema_is_accepted():
    """The assertion 'the code declares no structured output'. Rejecting it would make the state
    inexpressible, and the platform relies on it to clear a stale capture."""
    assert errors(call_site(output_schema=None)) == []


def test_a_scalar_schema_is_rejected():
    """The platform binds this to a JsonNode and compiles it as a JSON Schema. A bare string parses
    but means nothing, and networknt compiles some of those into permissive schemas rather than
    failing — so a bad value fires on real outputs instead of being skipped."""
    assert errors(call_site(output_schema="nope"))


def test_a_nested_schema_is_accepted_without_inspecting_its_contents():
    """Validation is deliberately shallow: the schema's contents are the user's business."""
    schema = {
        "type": "object",
        "properties": {"answer": {"type": "string"}, "citations": {"type": "array"}},
        "required": ["answer"],
    }
    assert errors(call_site(output_schema=schema)) == []


# --------------------------------------------------------------------------- tools


def test_a_tool_needs_only_a_name():
    assert errors(call_site(tools=[{"name": "escalate"}])) == []


def test_a_nameless_tool_is_rejected():
    assert errors(call_site(tools=[{"description": "no name"}]))


def test_tools_must_be_a_list():
    assert errors(call_site(tools={"name": "escalate"}))


def test_a_tool_input_schema_must_be_a_mapping():
    assert errors(call_site(tools=[{"name": "t", "input_schema": [1, 2]}]))


def test_a_fully_specified_tool_is_accepted():
    tool = {
        "name": "search_docs",
        "description": "full-text search over the handbook",
        "input_schema": {"type": "object", "properties": {"query": {"type": "string"}}},
        "source": "src/tools/search.py:41",
    }
    assert errors(call_site(tools=[tool])) == []


# -------------------------------------------------------------------- capabilities


def test_a_capability_needs_a_name():
    assert errors({"capabilities": [{"kind": "tool"}]})


def test_an_absent_kind_is_accepted():
    """It defaults to `tool` platform-side. A manifest written by an agent reading code must not take
    the whole bundle down over one unlabelled entry."""
    assert errors({"capabilities": [{"name": "x"}]}) == []


@pytest.mark.parametrize("kind", ["tool", "skill", "mcp_server", "subagent", "workflow", "future_thing"])
def test_kind_is_an_open_vocabulary(kind):
    """The known kinds AND ones we have not invented yet. The platform holds `kind` as a plain string
    and its observer WRITES this manifest, so rejecting an unrecognized kind would mean the validator
    rejects a bundle the platform just produced — the validator becoming the thing that breaks the
    contract it exists to protect."""
    assert errors({"capabilities": [{"name": "x", "kind": kind}]}) == []


def test_a_non_string_kind_is_still_rejected():
    """A shape error, not a vocabulary one."""
    assert errors({"capabilities": [{"name": "x", "kind": 7}]})


def test_capabilities_must_be_a_list():
    assert errors({"capabilities": {"name": "x"}})


def test_call_site_ids_must_be_a_list():
    assert errors({"capabilities": [{"name": "x", "call_site_ids": "support.answer"}]})


def test_an_unbound_capability_is_accepted():
    """Empty/absent call_site_ids means product-wide, not malformed."""
    assert errors({"capabilities": [{"name": "x", "kind": "skill"}]}) == []


# ------------------------------------------------------------------------- wiring


def test_the_check_is_reachable_from_the_bundle_entry_point():
    """A validator rule that never runs is worse than no rule — it reads as coverage. Guards the
    wiring, not the logic."""
    source = (Path(validate.__file__)).read_text()
    assert "_bundle_code_facts(pipeline)" in source, "_bundle_code_facts must be called from _run_bundle"
