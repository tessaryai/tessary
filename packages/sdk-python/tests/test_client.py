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
        (("http://x", "k"), {"timeout": 0}),
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


def test_update_prompt_body_is_exact_and_evicts_the_name(server):
    # Bug: camelCase lost on newLabels, or a cached production read survives a promote.
    server.script({"status": 200, "body": envelope(name="a")}, {"status": 200, "body": envelope(name="a", version=2)})
    client = make(server)
    client.get_prompt("a")
    assert client.update_prompt(name="a", version=2, new_labels=["production"]).version == 2
    patch = server.requests[1]
    assert (patch.method, patch.path) == ("PATCH", "/v1/prompts/labels")
    assert patch.body == {"name": "a", "version": 2, "newLabels": ["production"]}
    assert client.cache.size() == 0


def test_update_prompt_sends_remove_labels(server):
    # Bug: remove_labels never reaches the server, so a label cannot be taken off from code.
    server.script({"status": 200, "body": envelope(name="a", version=2)})
    make(server).update_prompt(name="a", version=2, remove_labels=["staging"])
    assert server.requests[0].body == {"name": "a", "version": 2, "removeLabels": ["staging"]}


@pytest.mark.parametrize("kwargs", [{"name": "", "version": 1}, {"name": "a", "version": 0}])
def test_bad_update_prompt_arguments_raise(server, kwargs):
    # Bug: a label move with no prompt or no version reaches the server.
    with pytest.raises(ValueError):
        make(server).update_prompt(**kwargs, new_labels=["x"])
    assert server.requests == []


def test_update_prompt_404_names_the_version(server):
    # Bug: a missing version is reported without saying which one.
    server.script({"status": 404, "body": api_error(404, "PROMPTS.VERSION_NOT_FOUND", "Prompt a has no version 9")})
    with pytest.raises(PromptNotFound) as caught:
        make(server).update_prompt(name="a", version=9, new_labels=["production"])
    assert (caught.value.name, caught.value.version) == ("a", 9)


def test_delete_prompt_by_label_evicts_the_name(server):
    # Bug: a stale cached version is served after a delete.
    deleted = {"meta": {"status": 200, "success": True}, "data": {"deleted": True}}
    server.script(
        {"status": 200, "body": envelope(name="a")},
        {"status": 200, "body": deleted},
        {"status": 200, "body": envelope(name="a", version=2)},
    )
    client = make(server)
    client.get_prompt("a")
    assert client.delete_prompt("a", label="production") is True
    req = server.requests[1]
    assert (req.method, req.path, req.query) == ("DELETE", "/v1/prompts", "name=a&label=production")
    assert req.body is None
    assert client.get_prompt("a").version == 2
    assert len(server.requests) == 3


def test_delete_prompt_sends_the_version_or_nothing(server):
    # Bug: the version is dropped, so a version delete removes the whole prompt.
    deleted = {"meta": {"status": 200, "success": True}, "data": {"deleted": True}}
    server.script({"status": 200, "body": deleted}, {"status": 200, "body": deleted})
    client = make(server)
    client.delete_prompt("a", version=3)
    client.delete_prompt("a")
    assert [r.query for r in server.requests] == ["name=a&version=3", "name=a"]


@pytest.mark.parametrize(
    "args, kwargs", [(("",), {}), (("a",), {"version": 0}), (("a",), {"version": 1, "label": "x"})]
)
def test_bad_delete_prompt_arguments_raise(server, args, kwargs):
    # Bug: a delete with both a version and a label, or no name, reaches the server.
    with pytest.raises(ValueError):
        make(server).delete_prompt(*args, **kwargs)
    assert server.requests == []


def test_delete_prompt_is_not_retried_on_503(server):
    # Bug: a delete that committed is sent again and deletes the next version a label moved to.
    server.script({"status": 503}, {"status": 200})
    with pytest.raises(PromptApiError):
        make(server).delete_prompt("a", label="production")
    assert len(server.requests) == 1


def test_list_prompts_passes_filters_and_returns_the_page(server):
    # Bug: filters are dropped from the query, or the page comes back without its meta.
    page = {
        "data": [{"name": "a", "type": "text", "versions": [1], "labels": [], "tags": [], "lastUpdatedAt": "x", "lastConfig": {}}],
        "meta": {"page": 2, "limit": 5, "totalItems": 6, "totalPages": 2},
    }
    server.script({"status": 200, "body": {"meta": {"status": 200, "success": True}, "data": page}}, {"status": 200, "body": {"meta": {}, "data": page}})
    client = make(server)
    assert client.list_prompts(
        name="a",
        label="production",
        tag="t",
        page=2,
        limit=5,
        from_updated_at="2026-11-01T00:00:00Z",
        to_updated_at="2026-11-02T00:00:00Z",
    ) == page
    client.list_prompts()
    first, second = server.requests
    assert (first.method, first.path) == ("GET", "/v1/prompts")
    assert first.query == (
        "name=a&label=production&tag=t&page=2&limit=5"
        "&fromUpdatedAt=2026-11-01T00%3A00%3A00Z&toUpdatedAt=2026-11-02T00%3A00%3A00Z"
    )
    assert second.query == ""


def test_clear_prompt_cache_forces_a_fetch(server):
    # Bug: there is no way to drop the cache, so a process serves a moved label for a full TTL.
    server.script({"status": 200, "body": envelope(version=1)}, {"status": 200, "body": envelope(version=2)})
    client = make(server)
    assert client.get_prompt("a").version == 1
    client.clear_prompt_cache()
    assert client.cache.size() == 0
    assert client.get_prompt("a").version == 2
    assert len(server.requests) == 2


def test_per_call_retries_and_timeout_override_the_client(server):
    # Bug: a caller cannot shorten the 16 s worst case for one call (review finding P5).
    sleeps = Sleeps()
    server.script(*[{"status": 503}] * 2, {"status": 200, "body": envelope(), "delay": 0.5})
    client = make(server, sleep=sleeps, max_retries=4, timeout=5.0)
    with pytest.raises(PromptApiError):
        client.get_prompt("a", max_retries=0)
    assert len(server.requests) == 1
    with pytest.raises(PromptTransportError):
        client.get_prompt("a", max_retries=1, fetch_timeout_seconds=0.05)
    assert len(server.requests) == 3


def test_a_cold_load_is_shared_only_by_callers_with_the_same_per_call_options(server):
    # Bug: the client hands the cache no per-call options, so a caller with a 1 s timeout and no retries joins
    # a default caller's load and waits out its retries.
    server.script(*[{"status": 200, "body": envelope()}] * 3)
    client = make(server, cache_ttl_seconds=60)
    shares = []
    get = client.cache.get

    def spy(key, ttl, loader, share=None):
        shares.append(share)
        return get(key, ttl, loader, share)

    client.cache.get = spy  # type: ignore[method-assign]
    client.get_prompt("a")
    client.get_prompt("b", max_retries=0, fetch_timeout_seconds=1)
    client.get_prompt("c", max_retries=9)
    assert shares == [None, "0:1", "4:None"]


def test_retries_are_capped_at_four(server):
    # Bug: a large retry count parks the caller for minutes on a dead instance.
    sleeps = Sleeps()
    server.script(*[{"status": 503}] * 12)
    client = make(server, sleep=sleeps, max_retries=10)
    with pytest.raises(PromptApiError):
        client.get_prompt("a")
    assert len(server.requests) == 5
    with pytest.raises(PromptApiError):
        client.get_prompt("a", max_retries=9)
    assert len(server.requests) == 10
    with pytest.raises(PromptApiError):
        client.get_prompt("a", max_retries=-3)
    assert len(server.requests) == 11


def test_bad_fetch_timeout_raises_before_any_request(server):
    # Bug: a zero or negative timeout reaches urllib, which then blocks or fails with a raw error.
    with pytest.raises(ValueError):
        make(server).get_prompt("a", fetch_timeout_seconds=0)
    assert server.requests == []


@pytest.mark.parametrize(
    "overrides",
    [
        {"version": None},
        {"version": "3"},
        {"version": True},
        {"name": 7},
        {"type": "json"},
        {"prompt": None},
        {"type": "chat", "prompt": "not a list"},
        {"type": "text", "prompt": [{"role": "user", "content": "x"}]},
    ],
)
def test_malformed_success_body_returns_the_fallback(server, overrides):
    # Bug: a 2xx without version (or with a wrong-typed field) raises KeyError past the fallback.
    data = envelope(**overrides)
    if overrides.get("version", 0) is None:
        del data["data"]["version"]
    server.script({"status": 200, "body": data}, {"status": 200, "body": data})
    client = make(server)
    assert client.get_prompt("a", fallback="x").is_fallback
    with pytest.raises(PromptTransportError):
        client.get_prompt("a")


def test_a_write_with_a_malformed_body_still_evicts_the_name(server):
    # Bug: the write committed but the bad body raised first, so the old cached version is served on.
    server.script({"status": 200, "body": envelope(name="a")}, {"status": 201, "body": envelope(name="a", version=None)})
    client = make(server)
    client.get_prompt("a")
    with pytest.raises(PromptTransportError):
        client.create_prompt("a", "p")
    assert client.cache.size() == 0


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


@pytest.mark.parametrize("data", ["x", [1], None])
def test_2xx_whose_data_is_not_an_object_is_a_transport_error(server, data):
    # Bug: a data string or list crashes the caller with a bare AttributeError past the fallback.
    server.script({"status": 200, "body": {"meta": {"status": 200, "success": True}, "data": data}})
    assert make(server).get_prompt("a", fallback="f").is_fallback


def test_connection_refused_is_a_transport_error():
    # Bug: an unreachable host escapes as a raw URLError.
    client = TessaryPrompts("http://127.0.0.1:9", "tsy_q_test", max_retries=0)
    with pytest.raises(PromptTransportError):
        client.get_prompt("a")
