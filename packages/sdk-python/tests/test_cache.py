# SPDX-License-Identifier: Apache-2.0
import logging
import threading

from conftest import Clock, Runner, envelope

from tessary_prompts import PromptApiError, PromptNotFound, TessaryPrompts, TextPrompt
from tessary_prompts.cache import PromptCache, thread_runner


def text(version: int) -> TextPrompt:
    return TextPrompt(name="a", version=version, prompt=f"v{version}")


class Loader:
    def __init__(self, *results):
        self.results = list(results)
        self.calls = 0

    def __call__(self):
        self.calls += 1
        result = self.results.pop(0)
        if isinstance(result, Exception):
            raise result
        return result


def test_fresh_hit_skips_the_loader():
    # Bug: the TTL is ignored and every call goes to the network.
    clock = Clock()
    cache = PromptCache(clock, Runner())
    load = Loader(text(1))
    first = cache.get("a|label:production", 60, load)
    clock.now = 59
    assert cache.get("a|label:production", 60, load) is first
    assert load.calls == 1


def test_stale_hit_returns_the_old_value_and_refreshes_once():
    # Bug: the refresh blocks the caller, or every stale call starts its own refresh.
    clock, runner = Clock(), Runner()
    cache = PromptCache(clock, runner)
    load = Loader(text(1), text(2))
    cache.get("k", 60, load)
    clock.now = 61
    assert cache.get("k", 60, load).version == 1
    assert len(runner.jobs) == 1
    assert cache.get("k", 60, load).version == 1
    assert len(runner.jobs) == 1
    runner.run_all()
    assert cache.get("k", 60, load).version == 2


def test_refresh_failure_keeps_the_stale_entry(caplog):
    # Bug: a flaky backend empties the cache.
    clock, runner = Clock(), Runner()
    cache = PromptCache(clock, runner)
    load = Loader(text(1), PromptApiError(503, "http_503", "down"))
    cache.get("k", 60, load)
    clock.now = 61
    cache.get("k", 60, load)
    with caplog.at_level(logging.DEBUG, logger="tessary_prompts"):
        runner.run_all()
    assert cache.size() == 1
    assert cache.get("k", 60, load).version == 1
    assert "refresh failed for k: PromptApiError" in caplog.text


def test_refresh_404_evicts():
    # Bug: a deleted prompt is served forever.
    clock, runner = Clock(), Runner()
    cache = PromptCache(clock, runner)
    load = Loader(text(1), PromptNotFound("gone", name="a"), text(3))
    cache.get("k", 60, load)
    clock.now = 61
    cache.get("k", 60, load)
    runner.run_all()
    assert cache.size() == 0
    assert cache.get("k", 60, load).version == 3
    assert load.calls == 3


def test_ttl_zero_bypasses_the_cache():
    # Bug: cache_ttl_seconds=0 still caches.
    cache = PromptCache(Clock(), Runner())
    load = Loader(text(1), text(2))
    cache.get("k", 0, load)
    cache.get("k", 0, load)
    assert load.calls == 2
    assert cache.size() == 0


def test_per_call_ttl_wins(server):
    # Bug: the per-call override is ignored in favour of the client TTL.
    clock, runner = Clock(), Runner()
    server.script({"status": 200, "body": envelope()}, {"status": 200, "body": envelope(version=4)})
    client = TessaryPrompts(server.url, "tsy_q_test", cache_ttl_seconds=60, clock=clock, runner=runner)
    client.get_prompt("team/a/b", cache_ttl_seconds=1)
    clock.now = 2
    client.get_prompt("team/a/b", cache_ttl_seconds=1)
    assert len(runner.jobs) == 1


def test_key_derivation_keeps_version_and_label_apart():
    # Bug: a version read and a label read collide in one cache slot.
    assert PromptCache.key("a", 3, None) == "a|version:3"
    assert PromptCache.key("a", None, "staging") == "a|label:staging"
    assert PromptCache.key("a", None, None) == "a|label:production"


def test_evict_name_is_exact():
    # Bug: evicting team/a also evicts team/ab.
    cache = PromptCache(Clock(), Runner())
    cache.get("team/a|label:production", 60, Loader(text(1)))
    cache.get("team/a|version:1", 60, Loader(text(1)))
    cache.get("team/ab|label:production", 60, Loader(text(1)))
    cache.evict_name("team/a")
    assert cache.size() == 1
    cache.get("team/ab|label:production", 60, Loader())


def test_default_runner_runs_the_job_on_a_daemon_thread():
    # Bug: the refresh runs on the caller's thread, or on a thread that keeps the process alive.
    done = threading.Event()
    seen = {}

    def job():
        seen["thread"] = threading.current_thread()
        done.set()

    thread_runner(job)
    assert done.wait(5)
    assert seen["thread"].daemon
    assert seen["thread"].name == "tessary-prompts-refresh"


def test_refresh_in_flight_during_a_write_does_not_store_the_old_value():
    # Bug: a background refresh that read v1 before create() promoted v2 stores v1 after
    # evict_name ran, so the process that just made the write serves the old version for a TTL.
    clock, runner = Clock(), Runner()
    cache = PromptCache(clock, runner)
    load = Loader(text(1), text(1), text(2))
    cache.get("a|label:production", 60, load)
    clock.now = 61
    assert cache.get("a|label:production", 60, load).version == 1
    cache.evict_name("a")
    runner.run_all()
    assert cache.size() == 0
    assert cache.get("a|label:production", 60, load).version == 2


def test_refresh_can_start_again_after_a_write_drops_it():
    # Bug: evict_name leaves the key marked as refreshing, or the old refresh unmarks a newer one,
    # so a later stale read never refreshes.
    clock, runner = Clock(), Runner()
    cache = PromptCache(clock, runner)
    key = "a|label:production"
    load = Loader(text(1), text(1), text(2), text(3))
    cache.get(key, 60, load)
    clock.now = 61
    cache.get(key, 60, load)
    cache.evict_name("a")
    cache.get(key, 60, load)
    clock.now = 122
    cache.get(key, 60, load)
    assert len(runner.jobs) == 2
    runner.run_all()
    assert cache.get(key, 60, load).version == 3


def test_cold_miss_during_a_write_is_not_stored():
    # Bug: a cold load that started before the write stores its old value afterwards.
    cache = PromptCache(Clock(), Runner())

    def load():
        cache.evict_name("a")
        return text(1)

    assert cache.get("a|label:production", 60, load).version == 1
    assert cache.size() == 0
