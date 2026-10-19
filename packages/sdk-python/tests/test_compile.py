# SPDX-License-Identifier: Apache-2.0
import pytest
from conftest import envelope

from tessary_prompts import ChatPrompt, TessaryPrompts, TextPrompt


def text(body: str) -> TextPrompt:
    return TextPrompt(name="a", version=1, prompt=body)


def chat(body: list) -> ChatPrompt:
    return ChatPrompt(name="a", version=1, prompt=body)


def test_substitution_allows_inner_whitespace():
    # Bug: only the tight {{name}} spelling is matched.
    assert text("Hi {{ name }} and {{name}}").compile(name="Ada") == "Hi Ada and Ada"


def test_a_variable_named_self_is_filled():
    # Bug: compile took self as a named parameter, so the valid template variable {{self}} raised
    # "got multiple values for argument 'self'".
    assert text("Describe {{self}}").compile(self="me") == "Describe me"
    assert chat([{"role": "user", "content": "Describe {{self}}"}]).compile(self="me") == [
        {"role": "user", "content": "Describe me"}
    ]


def test_none_renders_as_empty():
    # Bug: None renders as the string "None".
    assert text("Hi {{ name }}!").compile(name=None) == "Hi !"


def test_missing_variable_is_left_literal():
    # Bug: a variable the caller did not pass silently becomes empty.
    assert text("Hi {{ name }}").compile() == "Hi {{ name }}"


def test_non_identifier_inner_text_is_left_literal():
    # Bug: JSON or prose inside braces is eaten as a variable.
    prompt = text('{{"a":1}} {{a b}} {{}}')
    assert prompt.compile(a=1, b=2) == '{{"a":1}} {{a b}} {{}}'
    assert prompt.variables() == []


def test_number_becomes_string():
    # Bug: str() is not applied to a non-string value.
    assert text("{{ count }} items").compile(count=3) == "3 items"


def test_variables_keep_first_seen_order_without_duplicates():
    # Bug: duplicates reported, or names sorted.
    assert text("{{b}} {{a}} {{ b }}").variables() == ["b", "a"]


def test_chat_variables_scan_every_content_and_skip_placeholders():
    # Bug: only the first message, or the placeholder name, is reported.
    prompt = chat(
        [
            {"role": "system", "content": "You help {{ team }}"},
            {"type": "placeholder", "name": "history"},
            {"role": "user", "content": "{{ question }} for {{ team }}"},
        ]
    )
    assert prompt.variables() == ["team", "question"]


def test_chat_message_content_is_compiled_and_other_keys_kept():
    # Bug: chat bodies are not compiled, or extra message keys are dropped.
    prompt = chat([{"role": "user", "content": "Hi {{ name }}", "name": "u1"}])
    assert prompt.compile(name="Ada") == [{"role": "user", "content": "Hi Ada", "name": "u1"}]


def test_placeholder_is_filled_and_its_items_compiled():
    # Bug: placeholder items are pasted in raw.
    prompt = chat([{"type": "placeholder", "name": "history"}, {"role": "user", "content": "next"}])
    out = prompt.compile(history=[{"role": "user", "content": "{{ q }}"}], q="x")
    assert out == [{"role": "user", "content": "x"}, {"role": "user", "content": "next"}]


def test_unresolved_placeholder_is_kept_in_place():
    # Bug: a placeholder the caller did not fill silently disappears.
    prompt = chat(
        [{"role": "system", "content": "s"}, {"type": "placeholder", "name": "history"}, {"role": "user", "content": "u"}]
    )
    out = prompt.compile(history="not a list")
    assert out[1] == {"type": "placeholder", "name": "history"}
    assert len(out) == 3


def test_non_object_placeholder_item_raises_type_error():
    # Bug: string items pass straight through into the provider request.
    prompt = chat([{"type": "placeholder", "name": "history"}])
    with pytest.raises(TypeError):
        prompt.compile(history=["hi"])
    with pytest.raises(TypeError):
        prompt.compile(history=[{"role": "user"}])


def test_a_list_fills_the_placeholder_and_renders_in_text():
    # Bug: a list-valued variable fills only one of the two places it is named.
    notes = [{"role": "user", "content": "n"}]
    prompt = chat([{"role": "system", "content": "Notes: {{ notes }}"}, {"type": "placeholder", "name": "notes"}])
    out = prompt.compile(notes=notes)
    assert out == [{"role": "system", "content": f"Notes: {notes}"}, {"role": "user", "content": "n"}]


def test_fallback_prompt_is_marked_unversioned_and_still_compiles(server):
    # Bug: a fallback is linked to a version, cached, or cannot be compiled.
    server.script({"status": 503, "body": "down"})
    client = TessaryPrompts(server.url, "tsy_q_test", max_retries=0)
    prompt = client.get_prompt("a", fallback="Hi {{ name }}")
    assert isinstance(prompt, TextPrompt)
    assert prompt.is_fallback
    assert prompt.version == 0
    assert prompt.labels == [] and prompt.tags == [] and prompt.config == {}
    assert prompt.span_attributes() == {}
    assert prompt.compile(name="Ada") == "Hi Ada"


def test_chat_fallback_is_a_chat_prompt(server):
    # Bug: a list fallback is built as a text prompt.
    server.script({"status": 503, "body": "down"})
    client = TessaryPrompts(server.url, "tsy_q_test", max_retries=0)
    prompt = client.get_prompt("a", fallback=[{"role": "user", "content": "{{ q }}"}])
    assert isinstance(prompt, ChatPrompt)
    assert prompt.compile(q="x") == [{"role": "user", "content": "x"}]


def test_fetched_chat_prompt_is_a_chat_prompt(server):
    # Bug: the wire type is ignored and every prompt is built as text.
    body = [{"role": "system", "content": "s"}, {"type": "placeholder", "name": "h"}]
    server.script({"status": 200, "body": envelope(type="chat", prompt=body)})
    prompt = TessaryPrompts(server.url, "tsy_q_test").get_prompt("team/a/b")
    assert isinstance(prompt, ChatPrompt)
    assert prompt.type == "chat"
    assert prompt.prompt == body


def test_to_dict_uses_the_public_prompt_field_names():
    # Bug: snake_case keys leak into a shape that mirrors the camelCase public API.
    prompt = TextPrompt(name="a", version=2, prompt="p", config={"t": 1}, labels=["x"], tags=["y"], commit_message="m")
    assert prompt.to_dict() == {
        "name": "a",
        "version": 2,
        "type": "text",
        "prompt": "p",
        "config": {"t": 1},
        "labels": ["x"],
        "tags": ["y"],
        "commitMessage": "m",
        "isFallback": False,
    }
