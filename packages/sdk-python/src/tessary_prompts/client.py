# SPDX-License-Identifier: Apache-2.0
"""The client: read through the cache, fall back when asked, and write through the public API."""

from __future__ import annotations

import time
from typing import Any, Callable

from .cache import Job, PromptCache, RefreshWorker
from .errors import PromptNotFound, TessaryPromptsError
from .http import HttpTransport
from .prompts import ChatPrompt, TextPrompt, fallback_prompt, from_data

MAX_RETRIES_CAP = 4


def _bounded_retries(max_retries: int) -> int:
    return max(0, min(max_retries, MAX_RETRIES_CAP))


def _check_version(version: Any) -> None:
    if isinstance(version, bool) or not isinstance(version, int) or version < 1:
        raise ValueError("version must be an integer of at least 1")


def _check_name(name: str) -> None:
    if not name:
        raise ValueError("name must not be empty")


class TessaryPrompts:
    """Fetch, cache, and write Tessary managed prompts over ``/v1/prompts``.

    ``host`` is the instance origin, for example ``https://tessary.example.com``. Reads need a
    query (``tsy_q_``) or admin key; writes need an admin (``tsy_a_``) key.
    """

    def __init__(
        self,
        host: str,
        api_key: str,
        *,
        timeout: float = 5.0,
        cache_ttl_seconds: int = 60,
        max_retries: int = 2,
        retry_backoff_seconds: float = 0.5,
        sleep: Callable[[float], None] = time.sleep,
        clock: Callable[[], float] = time.monotonic,
        runner: Callable[[Job], None] | None = None,
    ) -> None:
        if not host.startswith(("http://", "https://")):
            raise ValueError("host must start with http:// or https://")
        if not api_key:
            raise ValueError("api_key must not be empty")
        if cache_ttl_seconds < 0 or max_retries < 0:
            raise ValueError("cache_ttl_seconds and max_retries must not be negative")
        if timeout <= 0:
            raise ValueError("timeout must be positive")
        self._ttl = cache_ttl_seconds
        self.transport = HttpTransport(
            host.rstrip("/"), api_key, timeout, _bounded_retries(max_retries), retry_backoff_seconds, sleep
        )
        self.cache = PromptCache(clock, runner or RefreshWorker())

    def get_prompt(
        self,
        name: str,
        *,
        version: int | None = None,
        label: str | None = None,
        cache_ttl_seconds: int | None = None,
        fallback: str | list[dict[str, Any]] | None = None,
        max_retries: int | None = None,
        fetch_timeout_seconds: float | None = None,
    ) -> TextPrompt | ChatPrompt:
        """Return the version by number, by label, or the ``production`` label when neither is given.

        A stale cached prompt is returned at once and refreshed in the background. When the fetch
        fails and nothing is cached, ``fallback`` (text or chat messages) is returned instead of
        raising; it has version 0, is never cached, and links nothing. ``max_retries`` (0 to 4) and
        ``fetch_timeout_seconds`` override the client's values for this call.
        """
        _check_name(name)
        if version is not None:
            _check_version(version)
        if version is not None and label:
            raise ValueError("give version or label, not both")
        if cache_ttl_seconds is not None and cache_ttl_seconds < 0:
            raise ValueError("cache_ttl_seconds must not be negative")
        if fallback is not None and not isinstance(fallback, (str, list)):
            raise ValueError("fallback must be a str (text) or a list of messages (chat)")
        if fetch_timeout_seconds is not None and fetch_timeout_seconds <= 0:
            raise ValueError("fetch_timeout_seconds must be positive")
        retries = None if max_retries is None else _bounded_retries(max_retries)
        query = {"name": name}
        if version is not None:
            query["version"] = str(version)
        if label:
            query["label"] = label
        key = PromptCache.key(name, version, label)
        ttl = self._ttl if cache_ttl_seconds is None else cache_ttl_seconds

        def load() -> TextPrompt | ChatPrompt:
            data = self.transport.request(
                "GET",
                "/v1/prompts/fetch",
                (name, version, label),
                query=query,
                max_retries=retries,
                timeout=fetch_timeout_seconds,
            )
            return from_data(data)

        try:
            return self.cache.get(key, ttl, load)  # type: ignore[return-value]
        except TessaryPromptsError as error:
            if isinstance(error, PromptNotFound):
                self.cache.evict(key)
            if fallback is None:
                raise
            return fallback_prompt(name, fallback)

    def create_prompt(
        self,
        name: str,
        prompt: str | list[dict[str, Any]],
        *,
        type: str = "text",
        config: dict[str, Any] | None = None,
        labels: list[str] | None = None,
        tags: list[str] | None = None,
        commit_message: str | None = None,
    ) -> TextPrompt | ChatPrompt:
        """Create a prompt, or a new version of an existing one. Needs an admin key."""
        _check_name(name)
        if type not in ("text", "chat"):
            raise ValueError("type must be text or chat")
        if not isinstance(prompt, str if type == "text" else list):
            raise ValueError("a text prompt is a str and a chat prompt is a list of messages")
        body: dict[str, Any] = {"name": name, "type": type, "prompt": prompt}
        optional = {"config": config, "labels": labels, "tags": tags, "commitMessage": commit_message}
        body.update({k: v for k, v in optional.items() if v is not None})
        data = self.transport.request("POST", "/v1/prompts", (name, None, None), body=body)
        self.cache.evict_name(name)
        return from_data(data)

    def update_prompt(
        self,
        *,
        name: str,
        version: int,
        new_labels: list[str] | None = None,
        remove_labels: list[str] | None = None,
    ) -> TextPrompt | ChatPrompt:
        """Add ``new_labels`` to a version, moving each one off any other version, then take
        ``remove_labels`` off it. Needs an admin key."""
        _check_name(name)
        _check_version(version)
        body: dict[str, Any] = {"name": name, "version": version}
        optional = {"newLabels": new_labels, "removeLabels": remove_labels}
        body.update({k: v for k, v in optional.items() if v is not None})
        data = self.transport.request("PATCH", "/v1/prompts/labels", (name, version, None), body=body)
        self.cache.evict_name(name)
        return from_data(data)

    def delete_prompt(self, name: str, *, version: int | None = None, label: str | None = None) -> bool:
        """Delete one version by number, the version a label points at, or with neither the whole
        prompt. Deleting the last version deletes the prompt. Needs an admin key; never retried."""
        _check_name(name)
        if version is not None:
            _check_version(version)
        if version is not None and label:
            raise ValueError("give version or label, not both")
        query = {"name": name}
        if version is not None:
            query["version"] = str(version)
        if label:
            query["label"] = label
        data = self.transport.request("DELETE", "/v1/prompts", (name, version, label), query=query)
        self.cache.evict_name(name)
        return data.get("deleted") is True

    def list_prompts(
        self,
        *,
        name: str | None = None,
        label: str | None = None,
        tag: str | None = None,
        page: int | None = None,
        limit: int | None = None,
        from_updated_at: str | None = None,
        to_updated_at: str | None = None,
    ) -> dict[str, Any]:
        """One page of the project's prompts, ordered by name: ``{"data": [...], "meta": {...}}``.
        Never cached. ``from_updated_at`` and ``to_updated_at`` are ISO-8601 instants."""
        params = {
            "name": name,
            "label": label,
            "tag": tag,
            "page": page,
            "limit": limit,
            "fromUpdatedAt": from_updated_at,
            "toUpdatedAt": to_updated_at,
        }
        query = {k: str(v) for k, v in params.items() if v is not None}
        return self.transport.request("GET", "/v1/prompts", (name or "", None, label), query=query)

    def clear_prompt_cache(self) -> None:
        """Drop every cached prompt, so the next read of each one goes to the server."""
        self.cache.clear()
