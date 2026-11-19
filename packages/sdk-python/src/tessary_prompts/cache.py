# SPDX-License-Identifier: Apache-2.0
"""In-process prompt cache: a fresh entry is served, a stale entry is served at once and
refreshed in the background (one refresh per key, all on one worker thread), and concurrent misses
on one key share one load when they ask for the same per-call retries and timeout. A write bumps
its name's generation and a clear bumps the cache's epoch, so a load or refresh that started before
either stores nothing."""

from __future__ import annotations

import logging
import queue
import threading
from typing import Callable

from .errors import PromptNotFound
from .prompts import BasePrompt

_LOG = logging.getLogger("tessary_prompts")

Job = Callable[[], None]


class RefreshWorker:
    """The default runner: one daemon thread drains a queue of refresh jobs, so many stale keys never
    start many threads and the worker never holds the process open."""

    def __init__(self) -> None:
        self._queue: queue.SimpleQueue[Job] = queue.SimpleQueue()
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None

    def __call__(self, job: Job) -> None:
        self._queue.put(job)
        with self._lock:
            # A forked child inherits the reference but not the thread, so a dead one is replaced.
            if self._thread is None or not self._thread.is_alive():
                self._thread = threading.Thread(target=self._run, daemon=True, name="tessary-prompts-refresh")
                self._thread.start()

    def _run(self) -> None:
        while True:
            job = self._queue.get()
            try:
                job()
            except Exception as error:
                _LOG.debug("refresh job failed: %s", type(error).__name__)


def _name_of(key: str) -> str:
    """The prompt name of a cache key; names cannot hold ``|``."""
    return key.split("|", 1)[0]


class _Load:
    """A cold load in flight: the first caller runs it, later callers wait for its value or error."""

    def __init__(self) -> None:
        self.done = threading.Event()
        self.value: BasePrompt | None = None
        self.error: BaseException | None = None
        self.waiters = 0


class PromptCache:
    def __init__(self, clock: Callable[[], float], runner: Callable[[Job], None]) -> None:
        self._clock = clock
        self._runner = runner
        self._lock = threading.Lock()
        self._entries: dict[str, tuple[BasePrompt, float]] = {}
        self._loading: dict[str, _Load] = {}
        # key -> token of the refresh in flight, so an old refresh never unmarks a newer one
        self._refreshing: dict[str, object] = {}
        self._generations: dict[str, int] = {}
        self._epoch = 0

    @staticmethod
    def key(name: str, version: int | None, label: str | None) -> str:
        if version is not None:
            return f"{name}|version:{version}"
        return f"{name}|label:{label or 'production'}"

    def _stamp(self, key: str) -> tuple[int, int]:
        return self._epoch, self._generations.get(_name_of(key), 0)

    @staticmethod
    def _load_key(key: str, share: str | None) -> str:
        # The suffix keeps the name prefix, so evict_name still drops the load.
        return key if share is None else f"{key}|{share}"

    def get(self, key: str, ttl: float, loader: Callable[[], BasePrompt], share: str | None = None) -> BasePrompt:
        """``share`` names the caller's per-call retries and timeout: a cold miss joins only a load
        started with the same ones, so no caller waits on a load slower than it asked for."""
        if ttl == 0:
            return loader()
        token = object()
        with self._lock:
            stamp = self._stamp(key)
            entry = self._entries.get(key)
            if entry is None:
                load_key = self._load_key(key, share)
                load = self._loading.get(load_key)
                lead = load is None
                if lead:
                    load = self._loading[load_key] = _Load()
                else:
                    load.waiters += 1
            else:
                start = self._clock() - entry[1] >= ttl and key not in self._refreshing
                if start:
                    self._refreshing[key] = token
        if entry is None:
            return self._lead(key, load_key, load, stamp, loader) if lead else self._join(load)
        if start:
            self._runner(lambda: self._refresh(key, loader, stamp, token))
        return entry[0]

    def _lead(
        self, key: str, load_key: str, load: _Load, stamp: tuple[int, int], loader: Callable[[], BasePrompt]
    ) -> BasePrompt:
        try:
            load.value = loader()
            return load.value
        except BaseException as error:
            load.error = error
            raise
        finally:
            with self._lock:
                if load.error is None and self._stamp(key) == stamp:
                    self._entries[key] = (load.value, self._clock())  # type: ignore[assignment]
                # A write or clear may have dropped this load and a newer one may hold the slot.
                if self._loading.get(load_key) is load:
                    del self._loading[load_key]
            load.done.set()

    @staticmethod
    def _join(load: _Load) -> BasePrompt:
        load.done.wait()
        if load.error is not None:
            raise load.error
        return load.value  # type: ignore[return-value]

    def _refresh(self, key: str, loader: Callable[[], BasePrompt], stamp: tuple[int, int], token: object) -> None:
        try:
            value = loader()
        except PromptNotFound:
            self.evict(key)
        except Exception as error:  # a refresh never raises: the stale entry stays and is retried later
            _LOG.debug("refresh failed for %s: %s", key, type(error).__name__)
        else:
            with self._lock:
                if self._stamp(key) == stamp:
                    self._entries[key] = (value, self._clock())
        finally:
            with self._lock:
                if self._refreshing.get(key) is token:
                    del self._refreshing[key]

    def _bump(self, name: str) -> None:
        self._generations[name] = self._generations.get(name, 0) + 1

    def evict(self, key: str) -> None:
        with self._lock:
            self._bump(_name_of(key))
            self._entries.pop(key, None)

    def evict_name(self, name: str) -> None:
        """Drop every entry, load and refresh mark for ``name``, so nothing read before a write is
        served or stored after it; names cannot hold ``|``, so ``team/a`` never matches ``team/ab``."""
        prefix = name + "|"
        with self._lock:
            self._bump(name)
            for table in (self._entries, self._loading, self._refreshing):
                for key in [k for k in table if k.startswith(prefix)]:
                    del table[key]

    def clear(self) -> None:
        """Drop everything, and stop every load and refresh in flight from storing its value."""
        with self._lock:
            self._epoch += 1
            self._entries.clear()
            self._loading.clear()
            self._refreshing.clear()

    def size(self) -> int:
        with self._lock:
            return len(self._entries)

    def _waiters(self, key: str, share: str | None = None) -> int:
        """Callers waiting on the load in flight for ``key`` and ``share``. For tests."""
        with self._lock:
            load = self._loading.get(self._load_key(key, share))
            return 0 if load is None else load.waiters
