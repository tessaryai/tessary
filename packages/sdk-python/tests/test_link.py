# SPDX-License-Identifier: Apache-2.0
from tessary_prompts import TextPrompt


class FakeSpan:
    def __init__(self) -> None:
        self.attributes: dict[str, object] = {}

    def set_attribute(self, key: str, value: object) -> None:
        self.attributes[key] = value


def test_link_sets_both_attributes_as_strings():
    # Bug: the version is sent as a number, which the ingest contract does not read.
    span = FakeSpan()
    prompt = TextPrompt(name="a", version=3, prompt="p")
    assert prompt.span_attributes() == {"tessary.prompt.name": "a", "tessary.prompt.version": "3"}
    assert prompt.link(span) is span
    assert span.attributes == {"tessary.prompt.name": "a", "tessary.prompt.version": "3"}


def test_fallback_sets_nothing():
    # Bug: fallback traffic is counted against a version that never served it.
    span = FakeSpan()
    TextPrompt(name="a", version=0, prompt="p", is_fallback=True).link(span)
    assert span.attributes == {}


def test_span_without_set_attribute_is_returned_unchanged():
    # Bug: a foreign or no-op span object crashes the caller.
    span = object()
    assert TextPrompt(name="a", version=3, prompt="p").link(span) is span
