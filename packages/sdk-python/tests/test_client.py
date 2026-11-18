# SPDX-License-Identifier: Apache-2.0
import logging

import pytest
from conftest import Clock, Runner, api_error, envelope

from tessary_prompts import (
    PromptApiError,
    PromptNotFound,
    PromptTransportError,
    TessaryPrompts,
    TextPrompt,
    __version__,
)


class Sleeps:
    def __init__(self) -> None:
        self.calls: list[float] = []

    def __call__(self, seconds: float) -> None:
        self.calls.append(seconds)


def make(server, **kwargs) -> TessaryPrompts:
    kwargs.setdefault("sleep", Sleeps())
    return TessaryPrompts(server.url, "tsy_q_test", **kwargs)


def test_successful_fetch_sends_the_right_request_and_parses_the_envelope(server):
    # Bug: wrong path, wrong headers, a raw "/" in the query, or fields read from the wrong place.
    server.script({"status": 200, "body": envelope()})
    prompt = make(server).get_prompt("team/a/b")
    (req,) = server.requests
    assert req.method == "GET"
    assert req.path == "/v1/prompts/fetch"
    assert req.query == "name=team%2Fa%2Fb"
    assert req.headers["authorization"] == "Bearer tsy_q_test"
    assert req.headers["accept"] == "application/json"
    assert req.headers["user-agent"] == f"tessary-prompts-python/{__version__}"
    assert "content-type" not in req.headers
    assert isinstance(prompt, TextPrompt)
    assert (prompt.name, prompt.version, prompt.type) == ("team/a/b", 3, "text")
    assert prompt.labels == ["production", "latest"]
    assert prompt.tags == ["onboarding"]
    assert prompt.config == {"temperature": 0.2}
    assert prompt.commit_message == "warmer tone"
    assert prompt.is_fallback is False


def test_version_and_label_are_sent_as_query_parameters(server):
    # Bug: version or label dropped, or sent in the wrong form.
    server.script({"status": 200, "body": envelope(version=2)}, {"status": 200, "body": envelope()})
    client = make(server)
    client.get_prompt("a", version=2)
    client.get_prompt("a", label="staging")
    assert [r.query for r in server.requests] == ["name=a&version=2", "name=a&label=staging"]


@pytest.mark.parametrize(
    "kwargs",
    [
        {"version": 2, "label": "staging"},
        {"version": 0},
        {"version": True},
        {"cache_ttl_seconds": -1},
        {"fallback": 3},
    ],
)
def test_bad_get_arguments_raise_before_any_request(server, kwargs):
    # Bug: an invalid read reaches the server, or both version and label are sent.
    with pytest.raises(ValueError):
        make(server).get_prompt("a", **kwargs)
    with pytest.raises(ValueError):
        make(server).get_prompt("")
    assert server.requests == []


@pytest.mark.parametrize(
    "args, kwargs",
    [
        (("ftp://x", "k"), {}),
        (("http://x", ""), {}),
        (("http://x", "k"), {"cache_ttl_seconds": -1}),
        (("http://x", "k"), {"max_retries": -1}),
    ],
)
def test_bad_client_arguments_raise(args, kwargs):
    # Bug: a client that can never work is built and fails later, far from the cause.
    with pytest.raises(ValueError):
        TessaryPrompts(*args, **kwargs)


def test_404_raises_and_evicts(server):
    # Bug: a deleted prompt keeps being served from the cache.
    server.script(
        {"status": 200, "body": envelope()},
        {"status": 404, "body": api_error(404, "PROMPTS.NOT_FOUND", "Prompt team/a/b not found")},
    )
    client = make(server)
    client.get_prompt("team/a/b")
    assert client.cache.size() == 1
    with pytest.raises(PromptNotFound) as caught:
        client.get_prompt("team/a/b", cache_ttl_seconds=0)
    assert caught.value.name == "team/a/b"
    assert caught.value.version is None
    assert caught.value.label is None
    assert str(caught.value) == "Prompt team/a/b not found"
    assert client.cache.size() == 0


def test_404_with_fallback_returns_the_fallback(server):
    # Bug: the fallback is ignored when the prompt does not exist.
    server.script({"status": 404, "body": api_error(404, "PROMPTS.LABEL_NOT_FOUND", "no label")})
    client = make(server)
    prompt = client.get_prompt("a", label="staging", fallback="x")
    assert prompt.is_fallback
    assert client.cache.size() == 0


def test_429_then_200_honours_retry_after(server):
    # Bug: Retry-After ignored.
    sleeps = Sleeps()
    server.script(
        {"status": 429, "headers": {"Retry-After": "1"}, "body": {"ok": False, "error": {"code": "rate.limited", "message": "slow"}}},
        {"status": 200, "body": envelope()},
    )
    assert make(server, sleep=sleeps).get_prompt("a").version == 3
    assert sleeps.calls == [1.0]
    assert len(server.requests) == 2


def test_retry_after_is_capped_at_30_seconds(server):
    # Bug: a hostile or broken Retry-After parks the caller for minutes.
    sleeps = Sleeps()
    server.script({"status": 429, "headers": {"Retry-After": "600"}}, {"status": 200, "body": envelope()})
    make(server, sleep=sleeps).get_prompt("a")
    assert sleeps.calls == [30.0]


def test_429_without_header_waits_backoff_plus_jitter(server):
    # Bug: no jitter, so every client retries in lockstep.
    sleeps = Sleeps()
    server.script({"status": 429}, {"status": 200, "body": envelope()})
    client = make(server, sleep=sleeps)
    client.transport.rng = lambda: 0.5
    client.get_prompt("a")
    assert sleeps.calls == [0.625]


def test_503_three_times_on_a_read_gives_up_after_the_retries(server, caplog):
    # Bug: retries unbounded, or absent.
    sleeps = Sleeps()
    server.script(*[{"status": 503, "body": "<html>upstream down</html>"}] * 3)
    with caplog.at_level(logging.DEBUG, logger="tessary_prompts"), pytest.raises(PromptApiError) as caught:
        make(server, sleep=sleeps).get_prompt("a")
    assert caught.value.status == 503
    assert caught.value.code == "http_503"
    assert len(server.requests) == 3
    assert len(sleeps.calls) == 2
    assert "retrying GET /v1/prompts/fetch after 503 (attempt 1 of 3)" in caplog.text
    assert "tsy_q_test" not in caplog.text


def test_timeout_is_a_transport_error_and_the_fallback_covers_it(server):
    # Bug: a timeout escapes as a raw socket error, so the fallback never applies.
    server.script({"status": 200, "body": envelope(), "delay": 0.5}, {"status": 200, "body": envelope(), "delay": 0.5})
    client = make(server, timeout=0.05, max_retries=0)
    with pytest.raises(PromptTransportError):
        client.get_prompt("a")
    assert client.get_prompt("a", fallback="x").is_fallback


def test_timeout_on_a_read_is_retried(server):
    # Bug: a slow first answer fails the read although a retry would succeed.
    sleeps = Sleeps()
    server.script({"status": 200, "body": envelope(), "delay": 0.5}, {"status": 200, "body": envelope()})
    assert make(server, timeout=0.2, sleep=sleeps).get_prompt("a").version == 3
    assert len(sleeps.calls) == 1


def test_401_filter_shape_is_parsed(server):
    # Bug: the filter's {"ok":false,"error":{...}} body crashes the envelope parser.
    server.script({"status": 401, "body": {"ok": False, "error": {"code": "auth.unauthorized", "message": "bad key"}}})
    with pytest.raises(PromptApiError) as caught:
        make(server).get_prompt("a")
    assert (caught.value.status, caught.value.code, caught.value.message) == (401, "auth.unauthorized", "bad key")


def test_403_on_a_write_carries_the_envelope_code(server):
    # Bug: a scope error is reported as the wrong class or loses its code.
    server.script(
        {"status": 403, "body": api_error(403, "PROMPTS.WRONG_KEY_SCOPE", "this API key cannot write prompts")}
    )
    with pytest.raises(PromptApiError) as caught:
        make(server).create_prompt("a", "p")
    assert (caught.value.status, caught.value.code) == (403, "PROMPTS.WRONG_KEY_SCOPE")
    assert len(server.requests) == 1


def test_post_is_not_retried_on_503(server):
    # Bug: a retried insert that had committed creates a duplicate version.
    server.script({"status": 503}, {"status": 200, "body": envelope()})
    with pytest.raises(PromptApiError) as caught:
        make(server).create_prompt("a", "p")
    assert caught.value.status == 503
    assert len(server.requests) == 1


def test_post_is_not_retried_on_a_timeout(server):
    # Bug: a write that timed out after committing is sent again.
    server.script({"status": 200, "body": envelope(), "delay": 0.5})
    with pytest.raises(PromptTransportError):
        make(server, timeout=0.05).create_prompt("a", "p")
    assert len(server.requests) == 1


def test_post_is_retried_on_429_and_accepts_200_or_201(server):
    # Bug: a create fails under the write rate limit, or a 201 is read as an error.
    server.script(
        {"status": 429, "headers": {"Retry-After": "0"}},
        {"status": 200, "body": envelope(version=1)},
        {"status": 201, "body": envelope(version=2)},
    )
    client = make(server)
    assert client.create_prompt("a", "p").version == 1
    assert client.create_prompt("a", "p").version == 2
    assert len(server.requests) == 3


def test_302_is_a_transport_error_after_one_request(server):
    # Bug: a redirect is followed, re-sending the bearer key to another origin.
    server.script({"status": 302, "headers": {"Location": server.url + "/elsewhere"}})
    with pytest.raises(PromptTransportError):
        make(server).get_prompt("a")
    assert len(server.requests) == 1


def test_create_body_is_exact_and_evicts_the_name(server):
    # Bug: wrong field names, absent optionals sent as null, or a stale read survives a write.
    body = [{"role": "user", "content": "{{ q }}"}]
    server.script({"status": 200, "body": envelope(name="a")}, {"status": 200, "body": envelope(name="a", type="chat", prompt=body)})
    client = make(server)
    client.get_prompt("a")
    client.create_prompt("a", body, type="chat", labels=["staging"])
    create = server.requests[1]
    assert (create.method, create.path) == ("POST", "/v1/prompts")
    assert create.headers["content-type"] == "application/json"
    assert create.body == {"name": "a", "type": "chat", "prompt": body, "labels": ["staging"]}
    assert client.cache.size() == 0


def test_create_sends_every_optional_when_given(server):
    # Bug: config, tags or the commit message never reach the server.
    server.script({"status": 200, "body": envelope()})
    make(server).create_prompt("a", "p", config={"t": 1}, labels=["x"], tags=["y"], commit_message="m")
    assert server.requests[0].body == {
        "name": "a",
        "type": "text",
        "prompt": "p",
        "config": {"t": 1},
        "labels": ["x"],
        "tags": ["y"],
        "commitMessage": "m",
    }


@pytest.mark.parametrize(
    "args, kwargs",
    [(("", "p"), {}), (("a", "p"), {"type": "json"}), (("a", ["m"]), {}), (("a", "p"), {"type": "chat"})],
)
def test_bad_create_arguments_raise_before_any_request(server, args, kwargs):
    # Bug: a prompt whose body does not match its type reaches the server.
    with pytest.raises(ValueError):
        make(server).create_prompt(*args, **kwargs)
    assert server.requests == []


def test_update_labels_body_is_exact_and_evicts_the_name(server):
    # Bug: camelCase lost on newLabels, or a cached production read survives a promote.
    server.script({"status": 200, "body": envelope(name="a")}, {"status": 200, "body": envelope(name="a", version=2)})
    client = make(server)
    client.get_prompt("a")
    assert client.update_labels("a", 2, ["production"]).version == 2
    patch = server.requests[1]
    assert (patch.method, patch.path) == ("PATCH", "/v1/prompts/labels")
    assert patch.body == {"name": "a", "version": 2, "newLabels": ["production"]}
    assert client.cache.size() == 0


@pytest.mark.parametrize("args", [("", 1, ["x"]), ("a", 0, ["x"])])
def test_bad_update_labels_arguments_raise(server, args):
    # Bug: a label move with no prompt or no version reaches the server.
    with pytest.raises(ValueError):
        make(server).update_labels(*args)
    assert server.requests == []


def test_update_labels_404_names_the_version(server):
    # Bug: a missing version is reported without saying which one.
    server.script({"status": 404, "body": api_error(404, "PROMPTS.VERSION_NOT_FOUND", "Prompt a has no version 9")})
    with pytest.raises(PromptNotFound) as caught:
        make(server).update_labels("a", 9, ["production"])
    assert (caught.value.name, caught.value.version) == ("a", 9)


def test_host_is_normalised(server):
    # Bug: a trailing slash produces //v1 in the path.
    server.script({"status": 200, "body": envelope()})
    TessaryPrompts(server.url + "/", "tsy_q_test").get_prompt("a")
    assert server.requests[0].path == "/v1/prompts/fetch"


def test_stale_while_revalidate_end_to_end(server):
    # Bug: the background refresh never reaches the network.
    clock, runner = Clock(), Runner()
    server.script({"status": 200, "body": envelope(version=1)}, {"status": 200, "body": envelope(version=2)})
    client = make(server, clock=clock, runner=runner)
    assert client.get_prompt("a").version == 1
    clock.now = 61
    assert client.get_prompt("a").version == 1
    assert len(server.requests) == 1
    runner.run_all()
    assert len(server.requests) == 2
    assert client.get_prompt("a").version == 2


def test_non_json_2xx_is_a_transport_error(server):
    # Bug: a proxy's HTML page crashes the caller with a bare decode error.
    server.script({"status": 200, "body": "<html>hi</html>"})
    with pytest.raises(PromptTransportError):
        make(server).get_prompt("a")


def test_2xx_without_a_data_object_is_a_transport_error(server):
    # Bug: an envelope with no data crashes the caller with a bare KeyError.
    server.script({"status": 200, "body": {"meta": {"status": 200, "success": True}}})
    with pytest.raises(PromptTransportError):
        make(server).get_prompt("a")


def test_connection_refused_is_a_transport_error():
    # Bug: an unreachable host escapes as a raw URLError.
    client = TessaryPrompts("http://127.0.0.1:9", "tsy_q_test", max_retries=0)
    with pytest.raises(PromptTransportError):
        client.get_prompt("a")
