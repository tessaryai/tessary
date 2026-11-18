# SPDX-License-Identifier: Apache-2.0
import logging
import threading

from conftest import Clock, Runner, envelope

from tessary_prompts import PromptApiError, PromptNotFound, TessaryPrompts, TextPrompt
from tessary_prompts.cache import PromptCache, RefreshWorker


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


def test_default_runner_runs_the_job_on_one_long_lived_daemon_thread():
    # Bug: the refresh runs on the caller's thread, on a thread that keeps the process alive, or on a
    # new thread per job.
    worker = RefreshWorker()
    done = threading.Event()
    seen = []

    def job():
        seen.append(threading.current_thread())
        if len(seen) == 2:
            done.set()

    worker(job)
    worker(job)
    assert done.wait(5)
    assert seen[0] is seen[1]
    assert seen[0] is not threading.current_thread()
    assert seen[0].daemon
    assert seen[0].name == "tessary-prompts-refresh"


def test_many_stale_keys_refresh_on_one_thread():
    # Bug: 500 stale keys start 500 refresh threads (review finding P6).
    clock = Clock()
    worker = RefreshWorker()
    cache = PromptCache(clock, worker)
    threads = set()
    done = threading.Event()
    lock = threading.Lock()
    calls = {"n": 0}

    def load():
        with lock:
            calls["n"] += 1
            if calls["n"] > 500:
                threads.add(threading.current_thread())
            if calls["n"] == 1000:
                done.set()
        return text(1)

    for i in range(500):
        cache.get(f"p{i}|label:production", 60, load)
    clock.now = 61
    before = threading.active_count()
    for i in range(500):
        cache.get(f"p{i}|label:production", 60, load)
    assert threading.active_count() <= before + 1
    assert done.wait(10)
    assert len(threads) == 1


def test_a_failing_job_does_not_stop_the_worker(caplog):
    # Bug: one job that raises kills the worker thread, so no stale key is ever refreshed again.
    worker = RefreshWorker()
    done = threading.Event()

    def boom():
        raise RuntimeError("boom")

    with caplog.at_level(logging.DEBUG, logger="tessary_prompts"):
        worker(boom)
        worker(done.set)
        assert done.wait(5)
    assert "refresh job failed: RuntimeError" in caplog.text


def test_worker_starts_a_new_thread_when_its_thread_is_gone():
    # Bug: after a fork the worker thread does not exist in the child, and queued refreshes never run.
    worker = RefreshWorker()
    first = threading.Event()
    worker(first.set)
    assert first.wait(5)
    worker._thread = threading.Thread(target=lambda: None)  # a thread that is not alive, as after fork
    second = threading.Event()
    worker(second.set)
    assert second.wait(5)


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


def test_concurrent_cold_misses_share_one_request():
    # Bug: N threads that start cold send N requests (review finding 1 and P4).
    cache = PromptCache(Clock(), Runner())
    n = 8
    entered = threading.Event()
    release = threading.Event()
    calls = {"n": 0}

    def load():
        calls["n"] += 1
        entered.set()
        assert release.wait(5)
        return text(1)

    results = []
    waiting = threading.Barrier(n)

    def caller():
        waiting.wait(5)
        results.append(cache.get("a|label:production", 60, load))

    threads = [threading.Thread(target=caller) for _ in range(n)]
    for t in threads:
        t.start()
    assert entered.wait(5)
    _wait_for_waiters(cache, "a|label:production", n - 1)
    release.set()
    for t in threads:
        t.join(5)
    assert calls["n"] == 1
    assert len(results) == n
    assert all(r is results[0] for r in results)


def test_shared_cold_load_error_reaches_every_waiter():
    # Bug: when the shared load fails, a waiter hangs or gets None instead of the error.
    cache = PromptCache(Clock(), Runner())
    entered = threading.Event()
    release = threading.Event()

    def load():
        entered.set()
        assert release.wait(5)
        raise PromptApiError(503, "http_503", "down")

    errors = []

    def caller():
        try:
            cache.get("a|label:production", 60, load)
        except PromptApiError as error:
            errors.append(error)

    threads = [threading.Thread(target=caller) for _ in range(3)]
    threads[0].start()
    assert entered.wait(5)
    for t in threads[1:]:
        t.start()
    _wait_for_waiters(cache, "a|label:production", 2)
    release.set()
    for t in threads:
        t.join(5)
    assert len(errors) == 3
    assert all(e is errors[0] for e in errors)
    assert cache.size() == 0


def test_a_write_during_a_shared_cold_load_starts_a_new_load():
    # Bug: a caller after a write joins the load that started before it and gets the old version.
    cache = PromptCache(Clock(), Runner())
    entered = threading.Event()
    release = threading.Event()
    old = []

    def slow():
        entered.set()
        assert release.wait(5)
        return text(1)

    t = threading.Thread(target=lambda: old.append(cache.get("a|label:production", 60, slow)))
    t.start()
    assert entered.wait(5)
    cache.evict_name("a")
    assert cache.get("a|label:production", 60, Loader(text(2))).version == 2
    release.set()
    t.join(5)
    assert old[0].version == 1
    assert cache.get("a|label:production", 60, Loader()).version == 2


def test_clear_drops_every_entry_and_stops_loads_in_flight_from_storing():
    # Bug: clear_prompt_cache leaves a refresh or cold load that started before it free to store its old value.
    clock, runner = Clock(), Runner()
    cache = PromptCache(clock, runner)
    load = Loader(text(1), text(1), text(9))
    cache.get("a|label:production", 60, load)
    clock.now = 61
    cache.get("a|label:production", 60, load)

    def cold():
        cache.clear()
        return text(5)

    assert cache.get("b|label:production", 60, cold).version == 5
    runner.run_all()
    assert cache.size() == 0
    assert cache.get("a|label:production", 60, load).version == 9


def _wait_for_waiters(cache, key, count):
    """Spin until ``count`` callers wait on the load in flight for ``key``; no sleep-based ordering."""
    for _ in range(100000):
        if cache._waiters(key) >= count:
            return
        threading.Event().wait(0.001)
    raise AssertionError(f"only {cache._waiters(key)} of {count} callers joined the load")
