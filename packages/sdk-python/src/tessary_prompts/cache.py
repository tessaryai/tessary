# SPDX-License-Identifier: Apache-2.0
"""In-process prompt cache: a fresh entry is served, a stale entry is served at once and
refreshed in the background (one refresh per key), a miss loads on the caller's thread. A write
bumps its name's generation, so a load or refresh that started before the write stores nothing."""

from __future__ import annotations

import logging
import threading
from typing import Callable

from .errors import PromptNotFound
from .prompts import BasePrompt

_LOG = logging.getLogger("tessary_prompts")

Job = Callable[[], None]


def thread_runner(job: Job) -> None:
    """The default runner: each refresh on its own daemon thread, so it never holds the process open."""
    threading.Thread(target=job, daemon=True, name="tessary-prompts-refresh").start()


def _name_of(key: str) -> str:
    """The prompt name of a cache key; names cannot hold ``|``."""
    return key.split("|", 1)[0]


class PromptCache:
    def __init__(self, clock: Callable[[], float], runner: Callable[[Job], None]) -> None:
        self._clock = clock
        self._runner = runner
        self._lock = threading.Lock()
        self._entries: dict[str, tuple[BasePrompt, float]] = {}
        # key -> token of the refresh in flight, so an old refresh never unmarks a newer one
        self._refreshing: dict[str, object] = {}
        self._generations: dict[str, int] = {}

    @staticmethod
    def key(name: str, version: int | None, label: str | None) -> str:
        if version is not None:
            return f"{name}|version:{version}"
        return f"{name}|label:{label or 'production'}"

    def get(self, key: str, ttl: float, loader: Callable[[], BasePrompt]) -> BasePrompt:
        if ttl == 0:
            return loader()
        token = object()
        with self._lock:
            entry = self._entries.get(key)
            generation = self._generations.get(_name_of(key), 0)
            stale = entry is not None and self._clock() - entry[1] >= ttl
            start = stale and key not in self._refreshing
            if start:
                self._refreshing[key] = token
        if entry is None:
            value = loader()
            self._store(key, generation, value)
            return value
        if start:
            self._runner(lambda: self._refresh(key, loader, generation, token))
        return entry[0]

    def _refresh(self, key: str, loader: Callable[[], BasePrompt], generation: int, token: object) -> None:
        try:
            value = loader()
        except PromptNotFound:
            self.evict(key)
        except Exception as error:  # a refresh never raises: the stale entry stays and is retried later
            _LOG.debug("refresh failed for %s: %s", key, type(error).__name__)
        else:
            self._store(key, generation, value)
        finally:
            with self._lock:
                if self._refreshing.get(key) is token:
                    del self._refreshing[key]

    def _store(self, key: str, generation: int, value: BasePrompt) -> None:
        """Stores only when no write to the name happened since the load started."""
        with self._lock:
            if self._generations.get(_name_of(key), 0) == generation:
                self._entries[key] = (value, self._clock())

    def _bump(self, name: str) -> None:
        self._generations[name] = self._generations.get(name, 0) + 1

    def evict(self, key: str) -> None:
        with self._lock:
            self._bump(_name_of(key))
            self._entries.pop(key, None)

    def evict_name(self, name: str) -> None:
        """Drop every entry and refresh mark for ``name``, so nothing read before a write is stored
        after it; names cannot hold ``|``, so ``team/a`` never matches ``team/ab``."""
        prefix = name + "|"
        with self._lock:
            self._bump(name)
            for table in (self._entries, self._refreshing):
                for key in [k for k in table if k.startswith(prefix)]:
                    del table[key]

    def size(self) -> int:
        with self._lock:
            return len(self._entries)
